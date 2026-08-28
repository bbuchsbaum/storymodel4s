package storymodel4s.fixtures.wog

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.story.*
import storymodel4s.view.*

class WarOfTheGhostsCodexCompilerSuite extends FunSuite:
  private val model = WarOfTheGhostsModel.model
  private val relationLayers = Set(
    RelationLayer.Participant,
    RelationLayer.WorldTime,
    RelationLayer.Causal,
    RelationLayer.Goal,
    RelationLayer.StateChange,
    RelationLayer.Reference,
    RelationLayer.EntityContinuity
  )

  private def compile(state: CommonViewState, spec: CodexSpec): CodexFlow =
    val config = CodexCompiler.configurationChecksum(state, spec)
    val provenance = ViewProvenance
      .fixture(model.source.canonicalChecksum, "wog-codex-compiler-test", config)
      .fold(error => fail(error.message), identity)
    CodexCompiler(provenance)
      .compile(model, state, spec)
      .fold(error => fail(error.message), identity)

  test("the WOG compiler emits only evidence-backed model objects and deterministic lanes"):
    val state = CommonViewState
      .of(relationLayers = relationLayers)
      .fold(error => fail(error.message), identity)
    val channels = CodexLens.Overview.channels :+
      AnnotationChannel(AnnotationKind.Hierarchy, AnnotationPriority.unsafe(900))
    val lanePolicy = LanePolicy.of(2).fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .of(channels, ChannelBudget.All, lanePolicy)
      .fold(error => fail(error.message), identity)
    val first = compile(state, spec)
    val second = compile(state, spec)

    assertEquals(first, second)
    assert(first.annotations.nonEmpty)
    assertEquals(first.lanes.placements.size, first.annotations.size)
    assertEquals(first.lanes.receipt.algorithm, LaneAlgorithm.IntervalFirstFit)
    assertEquals(
      first.runs.map(_.span),
      Vector(TextSpan.unsafe(0, model.source.canonicalText.length))
    )
    assert(
      first.annotations
        .filter(_.kind == AnnotationKind.Hierarchy)
        .forall(
          _.priority == AnnotationPriority.unsafe(900)
        )
    )

    first.annotations.foreach { annotation =>
      val storyRef = ViewRef.parse(annotation.target) match
        case Some(ViewRef.Story(ref)) => ref
        case other                    => fail(s"expected a StoryRef target, found $other")
      assertEquals(model.supporting(storyRef), Some(annotation.support))
      annotation.support.refs.toVector.foreach { support =>
        assert(model.covering(support.span).contains(storyRef))
      }
      val coreRefs = annotation.audit.upstream.flatMap(Addressable[CoreRef].parse)
      assert(coreRefs.exists {
        case CoreRef.Claim(_) => true
        case _                => false
      })
      assert(coreRefs.exists {
        case CoreRef.Evidence(_) => true
        case _                   => false
      })
    }

  test("a missing feature request remains explicit missingness rather than a zero-valued mark"):
    val missing = FeatureSelection.Derived(Checksum.ofText("feature:missing-from-wog"))
    val state = CommonViewState
      .of(feature = Some(missing))
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .forLens(CodexLens.Structure)
      .fold(error => fail(error.message), identity)
    val flow = compile(state, spec)

    assertEquals(flow.contract.feature, FeatureChannelState.Missing(missing))
    assert(!flow.annotations.exists(_.kind == AnnotationKind.Feature))

  test("Codex rejects reader horizons outside the canonical source"):
    val state = CommonViewState
      .of(horizon = EpistemicHorizon.ReaderAt(model.source.canonicalText.length + 1))
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec.forLens(CodexLens.Structure).fold(error => fail(error.message), identity)
    val provenance = ViewProvenance
      .fixture(
        model.source.canonicalChecksum,
        "wog-codex-compiler-test",
        CodexCompiler.configurationChecksum(state, spec)
      )
      .fold(error => fail(error.message), identity)

    assert(CodexCompiler(provenance).compile(model, state, spec).isLeft)

  test("reader-time compilation uses transitive evidence closure and never leaks future spans"):
    val offset = model.source.canonicalText.length / 2
    val readerState = CommonViewState
      .of(
        horizon = EpistemicHorizon.ReaderAt(offset),
        relationLayers = relationLayers
      )
      .fold(error => fail(error.message), identity)
    val omniscientState = CommonViewState
      .of(relationLayers = relationLayers)
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .forLens(CodexLens.Overview, ChannelBudget.All)
      .fold(error => fail(error.message), identity)
    val reader = compile(readerState, spec)
    val omniscient = compile(omniscientState, spec)
    val ledger = model.ledger.fold(error => fail(error.message), identity)

    assert(reader.annotations.nonEmpty)
    assert(reader.annotations.size < omniscient.annotations.size)
    assert(
      reader.annotations.forall(
        _.support.refs.toVector.forall(_.span.endExclusive <= offset)
      )
    )
    reader.annotations.foreach { annotation =>
      val storyRef = ViewRef.parse(annotation.target) match
        case Some(ViewRef.Story(ref)) => ref
        case other                    => fail(s"expected a StoryRef target, found $other")
      val expectedSupport = model
        .supporting(storyRef)
        .flatMap(support => SpanSet.of(support.refs.toVector.filter(_.span.endExclusive <= offset)))
      assertEquals(expectedSupport, Some(annotation.support))
      val claimIds = annotation.audit.upstream.flatMap(Addressable[CoreRef].parse).collect {
        case CoreRef.Claim(id) => id
      }
      assert(claimIds.nonEmpty)
      assert(claimIds.forall(id => availableAt(id, offset, ledger, Set.empty)))
    }

    val startState = CommonViewState
      .of(horizon = EpistemicHorizon.ReaderAt(0), relationLayers = relationLayers)
      .fold(error => fail(error.message), identity)
    assertEquals(compile(startState, spec).annotations, Vector.empty)

  private def availableAt(
      id: ClaimId,
      offset: Int,
      ledger: ClaimLedger,
      visiting: Set[ClaimId]
  ): Boolean =
    if visiting.contains(id) then false
    else
      ledger.lookup(id).exists { meta =>
        meta.evidence.toVector.forall { evidence =>
          val grounded = evidence.spans.nonEmpty || evidence.upstream.nonEmpty
          val spansVisible = evidence.spans.forall(
            _.refs.toVector.forall(_.span.endExclusive <= offset)
          )
          val upstreamVisible = evidence.upstream
            .forall(upstream => availableAt(upstream, offset, ledger, visiting + id))
          grounded && spansVisible && upstreamVisible
        }
      }
