package storymodel4s.bench.video

import cats.data.NonEmptyVector
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.embed.Sensitivity
import storymodel4s.embed.onnx.{HistoricalParityFixture, OnnxMappingRegistration}
import storymodel4s.recall.*

/** Existing public single-token tokenizer controls exercise the actual registered consumer seam. */
class LocalReferenceConsumerSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def fixture =
    val built = right(
      TimedSourceView.build(
        Vector(TimedSegment(1, "hello", None), TimedSegment(2, "world", None)),
        WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied)
      )
    )
    val recall = RecallSegmenter.segment(right(StorySource.fromText("hello")))
    val source = right(StorySource.fromText(built.document))
    val representation = right(
      SourceRepresentation.of(
        built.view,
        NonEmptyVector.one(BundleEntry.text(source.canonicalChecksum)),
        None,
        built.view.nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
      )
    )
    val context = right(
      SurfaceRendering.prepare(recall, built.view, representation, SurfaceAnalyzer.analyze(source))
    )
    val config = right(
      OnnxMappingRegistration.Config.of(
        right(StrictCandidateConfig.of(2, TieBudgetRequest.Unbounded, false, None)),
        Sensitivity.Public,
        Sensitivity.Public,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none
      )
    )
    val cwd = Path.of("").toAbsolutePath.normalize()
    val root = Vector(cwd, cwd.getParent)
      .find(p => Files.isRegularFile(p.resolve("embed-onnx/src/test/resources/fixture/model.onnx")))
      .get
    val encoder = HistoricalParityFixture.open(root)
    val registered =
      try right(OnnxMappingRegistration.record(encoder, context, config))
      finally encoder.close()
    val profile = right(
      LocalReference.Profile.of(
        right(DeclaredUniverse.of(built.view.nodes.map(_.ref), TargetGrain.SingleLevel(0))),
        1.0
      )
    )
    (built, registered, profile)

  test("registered evidence feeds public reference before actual StageTrace inference") {
    val (built, registration, profile) = fixture
    val local = right(
      LocalReference.compute(
        registration.context.recall,
        registration.context.source,
        registration.evidence,
        profile
      )
    )
    val row = local.outcomes.head.asInstanceOf[LocalReference.Computed]
    val trace = right(
      StageTrace.registered(registration, right(HsmmConfig.of(temperature = profile.temperature)))
    )
    assert(local.evidence eq registration.evidence)
    assert(trace.evidence eq local.evidence)
    assertEquals(trace.result.costs(row.unit), row.costs)
    assertEquals(trace.result.posterior.row(row.unit).get.mass.keySet, row.mass.keySet)
    assertEquals(local.evidenceId, trace.evidence.identity)
    val ordered = row.mass.keys.toVector.sorted
    val legacy = StageTrace.localMass(ordered.map(row.costs(_).total), profile.temperature)
    ordered
      .zip(legacy)
      .foreach((state, expected) => assertEqualsDouble(row.mass(state), expected, 1e-12))
    assertEquals(local.profile.universe.targets.toSet, built.view.nodes.map(_.ref).toSet)
  }

  test("exact replay keeps the original envelope outside the numerical reference result") {
    val (_, original, profile) = fixture
    val replayed = right(
      original.replay(
        original.context,
        original.config,
        HistoricalParityFixture.model,
        original.queryRecord.provider
      )
    )
    val recorded = right(
      LocalReference.compute(
        original.context.recall,
        original.context.source,
        original.evidence,
        profile
      )
    )
    val replay = right(
      LocalReference.compute(
        replayed.context.recall,
        replayed.context.source,
        replayed.evidence,
        profile
      )
    )
    assertEquals(recorded, replay)
    assertEquals(replay.evidenceId, recorded.evidenceId)
    assertEquals(replayed.queryRecord, original.queryRecord)
    assertEquals(replayed.documentRecord, original.documentRecord)
    // Concrete recorded/replayed authority belongs to these envelopes, not a new reference label.
    assertNotEquals(original.basis, replayed.basis)
  }

  test("historical trace normalization stays available without strict-reference promotion") {
    val (built, original, profile) = fixture
    val r = original.context.recall
    val candidates =
      CandidateGenerator(SemanticDistance.lexicalJaccard, 2).generate(r.ordered, built.view)
    val historical = right(
      StageTrace.historical(r, built, candidates, DefaultLocalCostModel(), HsmmConfig.default)
    )
    assert(LocalReference.compute(r, built.view, historical.evidence, profile).isLeft)
    assertEquals(StageTrace.localMass(Vector(0.0, 0.0), 1.0), Vector(0.5, 0.5))
  }
