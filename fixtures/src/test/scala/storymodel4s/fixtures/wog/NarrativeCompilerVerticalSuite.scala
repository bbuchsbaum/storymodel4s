package storymodel4s.fixtures.wog

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.align.bridge.StorySourceView
import storymodel4s.core.*
import storymodel4s.document.*
import storymodel4s.proposition.*
import storymodel4s.recall.RecallSegmenter
import storymodel4s.story.*

/** The raw-source-to-signature court for ADR 0005.
  *
  * It uses the public-domain WOG source and a raw recall-style transcript, but never imports the
  * hand-authored WOG narrative model. One hand-built checked chart for one sentence stands in for a
  * parser; [[ChartProposalProvider]] derives every proposal from it and records exactly what it
  * emitted. This is a mechanical reachability court, not a claim of WOG narrative coverage or
  * scientific accuracy.
  */
class NarrativeCompilerVerticalSuite extends FunSuite:
  private val source = StorySource
    .titled(
      WarOfTheGhostsText.text,
      WarOfTheGhostsModel.title,
      metadata = Map("source" -> WarOfTheGhostsText.provenance)
    )
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val selected = atlas.sentences(3)
  private val chartStage = StageId.unsafe("wog-hand-chart")
  private val aligner = Fingerprint.unsafe("wog:hand-chart:1")

  private val c0 = ConceptId.unsafe("c0")
  private val c1 = ConceptId.unsafe("c1")
  private val c2 = ConceptId.unsafe("c2")

  private def spanOf(unit: SurfaceUnit, word: String): SpanRef =
    val text = atlas.text(unit)
    val at = text.indexOf(word)
    assert(at >= 0, s"'$word' is not in '$text'")
    SpanRef(
      Some(unit.id),
      TextSpan.unsafe(unit.span.start + at, unit.span.start + at + word.length)
    )

  private def align(unit: SurfaceUnit, word: String, concept: ConceptId): PropositionAlignment =
    val spans = SpanSet.one(spanOf(unit, word))
    val evidence = Evidence(
      EvidenceId.unsafe(s"ev:align:${unit.id.value}:$word"),
      Some(spans),
      Set.empty,
      aligner,
      chartStage
    )
    PropositionAlignment(
      AlignmentTarget.Concepts(NonEmptySet.one(concept)),
      spans,
      Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
      ClaimMeta.unsafe(
        ClaimId.unsafe(s"claim:align:${unit.id.value}:$word"),
        EpistemicStatus.SurfaceExplicit,
        Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
        NonEmptyVector.one(evidence),
        Provenance.deterministic("test", Checksum.ofText("wog-hand-chart"))
      )
    )

  /** "It became foggy and calm." as a hand chart: the focus predicate and its two properties. */
  private val chart: PropositionEvidence =
    val unchecked = PropositionChart.unchecked(
      Some(c0),
      Map(
        c0 -> Concept.predicate("become"),
        c1 -> Concept.property("foggy"),
        c2 -> Concept.property("calm")
      ),
      Vector(
        PropositionRelation(c0, RoleAssignment.arg(1), ConceptTarget.Node(c1)),
        PropositionRelation(c0, RoleAssignment.arg(1), ConceptTarget.Node(c2))
      ),
      Map.empty,
      Vector.empty,
      Vector(
        align(selected, "became", c0),
        align(selected, "foggy", c1),
        align(selected, "calm", c2)
      ),
      ChartProvenance.hand,
      Some(selected.id)
    )
    PropositionEvidence.hand(
      ChartValidator.check(unchecked).fold(v => fail(v.toString), identity)
    )

  private def compilation: NarrativeCompilation =
    val input = ChartProposalProvider
      .input(source, atlas, Vector(selected.id -> chart), Some(chartStage), 0L)
      .fold(e => fail(e.message), identity)
    NarrativeCompiler.compile(input).fold(e => fail(e.message), identity)

  test("raw public source plus a raw recall transcript reaches RecallSignature unattended") {
    val compiled = compilation
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    val view = StorySourceView.validated(model)

    val rawRecall = StorySource
      .fromText(
        "It was misty and completely still on the water.",
        Some("raw recall-style transcript")
      )
      .fold(e => fail(e.message), identity)
    val recall = RecallSegmenter.segment(rawRecall)
    val semantic = SemanticDistance.lexicalJaccard
    val candidates = CandidateGenerator(semantic).generate(recall.ordered, view)
    val result = GraphHsmm
      .infer(recall, view, candidates, DefaultLocalCostModel(semantic = semantic))
      .fold(e => fail(e.message), identity)
    val signature =
      RecallSignature.compute(result, recall, view).fold(e => fail(e.message), identity)

    assertEquals(atlas.text(selected), "It became foggy and calm.")
    assertEquals(compiled.derivation.gaps, Vector.empty)
    assertEquals(model.graph.situations.size, 1)
    assertEquals(
      model.graph.situations.values.head.predicate,
      Predicate("become", None, "become")
    )
    assertEquals(compiled.receipt.stages.map(_._1), Vector(chartStage, ChartProposalProvider.Stage))
    assertEquals(view.leaves.size, 1)
    assert(recall.ordered.nonEmpty)
    assert(candidates.totalSize > 0)
    assertEquals(signature.estimandVersion, RecallSignature.EstimandVersion)
    assert(signature.uniformCoverage.isFinite)
    assert(signature.externalMass.attributed.isFinite)
    assert(signature.externalMass.unranked.isFinite)
  }
