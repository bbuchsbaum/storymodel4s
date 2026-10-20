package storymodel4s.pipeline

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.embed.Sensitivity
import storymodel4s.embed.onnx.{OnnxMappingFixture, OnnxMappingRegistration}
import storymodel4s.recall.*

/** Exercises the facade selection boundary before the independent local-reference consumer. */
class ReferenceOnnxSelectionSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A =
    value.fold(error => throw AssertionError(error.toString), identity)

  private def hierarchicalContext(): SurfaceScoringContext =
    val base = OnnxMappingFixture.context(query = "")
    val text = right(StorySource.fromText("hello world"))
    val atlas = SurfaceAnalyzer.analyze(text)
    val parent = base.source.nodes.head
    val nested = parent.copy(
      ref = SourceNodeRef.Situation(SituationId.unsafe("onnx-level-one")),
      level = 1,
      parent = Some(parent.ref),
      discoursePosition = parent.discoursePosition + 1
    )
    val view = InMemorySourceView(base.source.nodes :+ nested, Map.empty, None, text.canonicalText.length)
    val support = view.nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
    val representation = right(
      SourceRepresentation.of(
        view,
        NonEmptyVector.one(BundleEntry.text(text.canonicalChecksum)),
        None,
        support
      )
    )
    right(SurfaceRendering.prepare(base.recall, view, representation, atlas))

  private def inventory(context: SurfaceScoringContext): RecallInventory =
    right(
      RecallInventory.of(
        context.recall,
        SurfaceSequence(context.recall.atlas).lexicalTokens.map(_.span),
        WordIdPolicy.inputArtifact(Checksum.ofText("onnx-selection-suite"))
      )
    )

  private def registration(
      context: SurfaceScoringContext,
      selectedLevel: Option[Int]
  ): OnnxMappingRegistration.Registered =
    val candidates = right(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(1), false, None))
    val settings = right(
      OnnxMappingRegistration.Config.of(
        candidates,
        Sensitivity.Public,
        Sensitivity.Public,
        emptyPolicy = SurfaceEmptyPolicy.Unavailable,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none,
        selectedLevel = selectedLevel
      )
    )
    val encoder = OnnxMappingFixture.open()
    try right(OnnxMappingRegistration.record(encoder, context, settings))
    finally encoder.close()

  private def config(selectedLevel: Option[Int]): MappingPipeline.Config =
    MappingPipeline.Config(
      MappingPipeline.OnnxProfile,
      temperature = 1.0,
      perLevel = 1,
      tieBudget = 1,
      requested = Vector(MappingPipeline.Capability.Mapping),
      selectedLevel = selectedLevel
    )

  test("reference ONNX refuses absent selection before local-reference nomination") {
    val context = hierarchicalContext()
    val registered = registration(context, None)
    val paired = right(MappingPipeline.registeredOnnx(registered, inventory(context), config(None)))
    assert(paired.execution.reference.outcomes.forall(_.measures.normalized.isEmpty))
    assert(MappingPipeline.referenceOnnx(registered, inventory(context), config(None)).isLeft)
  }

  test("explicit level zero reference preserves all-unavailable accounting") {
    val context = hierarchicalContext()
    val registered = registration(context, Some(0))
    val reference = right(MappingPipeline.referenceOnnx(registered, inventory(context), config(Some(0))))
    assert(reference.execution.reference.outcomes.forall(_.measures.normalized.isEmpty))
  }
