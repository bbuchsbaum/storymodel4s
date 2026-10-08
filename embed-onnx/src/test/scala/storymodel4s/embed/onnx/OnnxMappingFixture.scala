package storymodel4s.embed.onnx

import java.nio.file.Paths
import cats.data.NonEmptyVector
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.embed.*
import storymodel4s.recall.*

/** Project-authored synthetic ONNX/text fixtures are available to outside-package consumers. */
object OnnxMappingFixture:
  def model(maxTokens: Int = 4): OnnxSentenceModel = OnnxSentenceModel.testFixture(
    Checksum.unsafe("3e21121e42719ab61e888e8dbb9559591278701bec9193729bd2d1de38daca02"),
    Checksum.unsafe("0237b6bbc55d00142f9fa04557641e8ce5ea3abaf89e957aac5bca7dbd3bbf9a"),
    4,
    maxTokens
  )
  def open(): OnnxSentenceEmbedder = OnnxSentenceEmbedder
    .open(
      model(),
      OnnxSentenceArtifacts(
        Paths.get(getClass.getResource("/fixture/model.onnx").toURI),
        Paths.get(getClass.getResource("/fixture/tokenizer.json").toURI)
      )
    )
    .fold(e => throw new AssertionError(e.message), identity)

  def context(
      query: String = "hello",
      text: String = "hello world",
      ranges: Vector[(Int, Int)] = Vector(0 -> 5, 6 -> 11),
      renamed: Boolean = false,
      reverseStorage: Boolean = false
  ): SurfaceScoringContext =
    def checked[A, E](a: Either[E, A]): A =
      a.fold(e => throw new AssertionError(e.toString), identity)
    val transcript = checked(StorySource.fromText(if query.isEmpty then "hello" else query))
    val recallAtlas = SurfaceAnalyzer.analyze(transcript)
    val unit = RecallUnit(
      RecallUnitId.unsafe(if renamed then "renamed-query" else "query"),
      0,
      SpanSet.one(TextSpan.unsafe(0, if query.isEmpty then 0 else transcript.canonicalText.length)),
      if query.isEmpty then "" else transcript.canonicalText,
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
    val recall = RecallGraph
      .validated(transcript, recallAtlas, Vector(unit), RecallRelations.empty)
      .fold(e => throw new AssertionError(e.toString), identity)
    val source = checked(StorySource.fromText(text))
    val atlas = SurfaceAnalyzer.analyze(source)
    val nodes = ranges.zipWithIndex.map { (range, i) =>
      NodeSummary(
        SourceNodeRef.Situation(
          SituationId.unsafe(if renamed then s"renamed-$i" else s"target-$i")
        ),
        0,
        None,
        i,
        SpanSet.one(TextSpan.unsafe(range._1, range._2)),
        None,
        Vector.empty,
        ContextTag.NarratedWorld,
        PolarityTag.Unknown,
        ModalityTag.Unknown,
        Vector.empty,
        Set.empty
      )
    }
    val view = InMemorySourceView(
      if reverseStorage then nodes.reverse else nodes,
      Map.empty,
      None,
      source.canonicalText.length
    )
    val representation = checked(
      SourceRepresentation.of(
        view,
        NonEmptyVector.one(BundleEntry.text(source.canonicalChecksum)),
        None,
        nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
      )
    )
    checked(SurfaceRendering.prepare(recall, view, representation, atlas))
