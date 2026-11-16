package storymodel4s.pipeline.agentmapping

import io.circe.Json
import storymodel4s.core.TextSourcePackage
import storymodel4s.recall.RecallSegmenter
import scala.util.control.NonFatal

import storymodel4s.core.{
  Checksum,
  SpanSet,
  StorySource,
  SurfaceAnalyzer,
  SurfaceSequence,
  TextSpan
}
import storymodel4s.recall.{
  DiscourseFunction,
  ExpressedUncertainty,
  PropositionSketch,
  RecallGraph,
  RecallGraphStatus,
  RecallInventory,
  RecallRelations,
  RecallUnit,
  RecallUnitId,
  WordIdPolicy
}

/** One session's recall as mapping needs it: the checked graph, its inventory, the text of each
  * unit in order, and where the unit boundaries came from.
  *
  * Why a type: the inventory, the unit ids and the texts must describe the same units in the same
  * order, or an answer about one stretch of speech is saved against another. A value is built only
  * by a factory that derives all three from one input, so the relation cannot be asserted.
  */
final class PreparedRecall private (
    val graph: RecallGraph[RecallGraphStatus.Checked],
    val inventory: RecallInventory,
    val texts: Vector[String],
    val segmentation: PreparedRecall.Segmentation
):
  /** Canonical text and half-open UTF-16 spans, derived from the actual segmentation graph.
    * Supplied inputs keep their legacy bundle shape. Raw text stays in the private export.
    */
  def transcriptDescriptor: Option[Json] = Option.when(segmentation.kind == "segmenter") {
    val source = graph.transcript
    Json.obj(
      "recipe" -> Json.fromString(segmentation.recipe),
      "rawSha256" -> Json.fromString(source.rawChecksum.hex),
      "canonicalSha256" -> Json.fromString(source.canonicalChecksum.hex),
      "canonicalization" -> Json.fromString(TextSourcePackage.Canonicalization),
      "offsetUnit" -> Json.fromString(TextSourcePackage.OffsetUnit),
      "canonicalText" -> Json.fromString(source.canonicalText),
      "units" -> Json.fromValues(graph.ordered.zipWithIndex.map { (unit, i) =>
        Json.obj(
          "u" -> Json.fromInt(i + 1),
          "unit" -> Json.fromString(unit.id.value),
          "text" -> Json.fromString(unit.text),
          "spans" -> Json.fromValues(unit.span.spans.toVector.map { span =>
            Json.obj(
              "start" -> Json.fromInt(span.start),
              "endExclusive" -> Json.fromInt(span.endExclusive)
            )
          })
        )
      })
    )
  }

  def unitIds: Vector[RecallUnitId] = inventory.units.map(_.id)
  override def equals(other: Any): Boolean = other match
    case that: PreparedRecall =>
      inventory.digest == that.inventory.digest && texts == that.texts &&
      segmentation == that.segmentation
    case _ => false
  override def hashCode: Int = (inventory.digest, texts, segmentation).##
  override def toString: String = s"PreparedRecall(units=${texts.size}, ${segmentation.kind})"

object PreparedRecall:
  /** Where unit boundaries came from: `supplied` by the study, or our `segmenter`. */
  final case class Segmentation(kind: String, artifactSha256: Checksum, recipe: String)

  val SegmenterRecipe: String = "recall-segmenter/v1"

  /** Strict UTF-8 intake keeps the segmenter's graph and canonical offsets intact. The raw file
    * checksum anchors word identity; canonicalization and the heuristic recipe stay explicit.
    */
  def segmented(bytes: Array[Byte]): Either[String, PreparedRecall] =
    try
      for
        text <- TextSourcePackage.fromUtf8(bytes).left.map(_.message)
        graph = RecallSegmenter.segment(text.source)
        _ <- Either.cond(graph.ordered.nonEmpty, (), "the segmenter produced no units")
        inventory <- RecallInventory
          .of(
            graph,
            SurfaceSequence(graph.atlas).lexicalTokens.map(_.span),
            WordIdPolicy.inputArtifact(text.source.rawChecksum)
          )
          .left
          .map(_.message)
      yield new PreparedRecall(
        graph,
        inventory,
        graph.ordered.map(_.text),
        Segmentation("segmenter", text.source.rawChecksum, SegmenterRecipe)
      )
    catch case NonFatal(e) => Left(s"transcript segmentation failed (${e.getClass.getSimpleName})")

  val SuppliedRecipe: String = "supplied-units/v1"

  /** Recall from units the study supplies, in order. The transcript is the unit texts joined by
    * line breaks, so each unit is one line. Units carry `DiscourseFunction.NotAssessed`: nobody
    * classified them, and nothing here guesses. `artifact` identifies the supplied units file.
    *
    * Refuses an empty session, an empty unit, a unit that spans lines, and text that is not in the
    * transcript's canonical form (a unit's recorded text must be exactly what the transcript
    * holds).
    */
  def supplied(
      title: String,
      texts: Vector[String],
      artifact: Checksum
  ): Either[String, PreparedRecall] =
    if texts.isEmpty then Left("a session needs at least one unit")
    else if texts.exists(t => t.isEmpty || t.exists(c => c == '\n' || c == '\r')) then
      Left("a unit is empty or spans lines")
    else
      val joined = texts.mkString("\n")
      for
        source <- StorySource.fromText(joined, Some(title)).left.map(_.message)
        _ <- Either.cond(
          source.canonicalText == joined,
          (),
          "unit texts are not in the transcript's canonical form"
        )
        atlas = SurfaceAnalyzer.analyze(source)
        starts = texts.scanLeft(0)((at, t) => at + t.length + 1)
        units = texts.zip(starts).zipWithIndex.map { case ((text, start), i) =>
          RecallUnit(
            RecallUnitId.unsafe(s"${source.id.value}:u$i"),
            i,
            SpanSet.one(TextSpan.unsafe(start, start + text.length)),
            text,
            DiscourseFunction.NotAssessed,
            // No hedge detector ran on supplied units. `Unmarked` here means "none recorded".
            ExpressedUncertainty.Unmarked,
            PropositionSketch.empty,
            None
          )
        }
        graph <- RecallGraph
          .validated(source, atlas, units, RecallRelations.empty)
          .toEither
          .left
          .map(_.head.message)
        inventory <- RecallInventory
          .of(
            graph,
            SurfaceSequence(atlas).lexicalTokens.map(_.span),
            WordIdPolicy.inputArtifact(artifact)
          )
          .left
          .map(_.message)
        _ <- Either.cond(
          graph.ordered.map(_.text) == texts,
          (),
          "the checked graph does not hold the supplied texts"
        )
      yield new PreparedRecall(
        graph,
        inventory,
        texts,
        Segmentation("supplied", artifact, SuppliedRecipe)
      )
