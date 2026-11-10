package storymodel4s.pipeline.agentmapping

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
