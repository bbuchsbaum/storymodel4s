package storymodel4s.bench

import storymodel4s.align.{SourceNodeRef, SourceView}
import storymodel4s.core.{Checksum, ContentAddress, StorySource}
import storymodel4s.recall.RecallGraph

/** One story–recall pair with its gold and its provenance.
  *
  * Why `story` alongside `view`: a semantic channel embeds the text under a node's support spans,
  * and the receipt of every number names the story's canonical checksum and the recall transcript's
  * checksum so a report line can be traced to exact inputs.
  */
final case class BenchCase(
    id: String,
    storyId: String,
    family: String,
    origin: Origin,
    story: StorySource,
    view: SourceView,
    recall: RecallGraph,
    gold: Gold
):
  /** Text under a source node's support, sliced from the story's canonical text. */
  def nodeText(ref: SourceNodeRef): Option[String] =
    view.node(ref).map { n =>
      n.support.spans.toVector
        .sortBy(_.start)
        .flatMap(_.slice(story.canonicalText).toOption)
        .mkString(" ")
    }

  /** Content address of the inputs a number was computed from. */
  def inputChecksum: Checksum =
    ContentAddress.digest(
      Vector(
        "bench-case/v1",
        id,
        storyId,
        family,
        origin.render,
        story.canonicalChecksum.hex,
        recall.transcript.canonicalChecksum.hex,
        gold.byUnit.toVector.sortBy(_._1.value).map(g => goldRender(g._2)).mkString("|")
      )
    )

  private def goldRender(g: GoldUnit): String =
    (Vector(g.unit.value, g.groundedness.toString, g.discourse.toString, g.blend.toString) ++
      g.targets.sortBy(_.node.key).map(t => s"${t.node.key}@${t.level}") ++
      g.facets.toVector.map(_.toString).sorted).mkString(",")
