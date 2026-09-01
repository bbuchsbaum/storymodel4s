package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import storymodel4s.acquire.SherlockAnnotations
import storymodel4s.acquire.SherlockAnnotations.Atlas
import storymodel4s.bench.video.{
  MediaLocus,
  RecallToVideo,
  RecallWordsCsv,
  TimedSegment,
  TimedSourceView
}
import storymodel4s.core.PresentationAxis
import storymodel4s.recall.Lexical

/** Sherlock adapter for the general recall-to-video pipeline: the checked annotation atlas becomes
  * [[TimedSegment]]s (rows are leaves, scenes are groups), the two media parts supply their own
  * playback axes, and node identity keeps the historical `sherlock:row`/`sherlock:scene` rendering
  * so reports stay diffable across refactors. Everything downstream - view construction, channel
  * selection, inference, the report - is the general method in `bench.video`.
  *
  * Polarity and modality stay `Unknown` on source nodes: the annotation table asserts what is on
  * screen but was never coded for either, and the cost model's conflict rules fire only between two
  * known tags.
  */
object SherlockAnnotationView:

  val naming: TimedSourceView.Naming =
    TimedSourceView.Naming(n => f"sherlock:row:$n%04d", n => f"sherlock:scene:$n%02d")

  private def stemsOf(parts: Iterable[String]): Set[String] =
    parts.iterator.flatMap(Lexical.stems).toSet

  private def convert(m: SherlockAnnotations.MediaLocus): MediaLocus = m match
    case SherlockAnnotations.MediaLocus.Extent(p, iv)  => MediaLocus.Extent(p, iv)
    case SherlockAnnotations.MediaLocus.Instant(p, at) => MediaLocus.Instant(p, at)

  def segments(atlas: Atlas): Vector[TimedSegment] =
    atlas.rows.map { row =>
      TimedSegment(
        ordinal = row.row,
        text = row.description,
        locus = atlas.mediaByRow.get(row.row).map(convert),
        group = atlas.sceneOf(row.row).map(s => TimedSegment.Group(s.ordinal, s.label)),
        extraLemmas = stemsOf(row.namesAll) ++ stemsOf(row.namesSpeaking) ++
          row.location.map(Lexical.stems(_).toSet).getOrElse(Set.empty),
        locations = row.location.map(Lexical.words).getOrElse(Vector.empty)
      )
    }

  def axes(atlas: Atlas): Map[String, PresentationAxis] =
    Map(
      atlas.manifest.partA.partId -> atlas.partABundle.primaryAxis,
      atlas.manifest.partB.partId -> atlas.partBBundle.primaryAxis
    )

  def build(atlas: Atlas): TimedSourceView.Built =
    TimedSourceView.build(segments(atlas), axes(atlas), naming)

/** Terminal diagnostic: map one Sherlock recall transcript onto the two media parts.
  *
  * Usage: `sherlockRecallMap <annotation.tsv> <recall.csv> <report.tsv>`. Inputs stay outside Git;
  * the report contains recall text and is therefore local-sensitive (fixture policy 14(d)): it is
  * written wherever the caller points, normally the ignored `tmp/` directory.
  */
@main def sherlockRecallMap(annotationTsv: String, recallCsv: String, outPath: String): Unit =
  val bytes = Files.readAllBytes(Paths.get(annotationTsv))
  val atlas = SherlockAnnotations
    .parse(bytes)
    .fold(e => throw new IllegalArgumentException(e.message), identity)
  val built = SherlockAnnotationView.build(atlas)
  println(s"annotation rows: ${atlas.rows.size}; scenes: ${atlas.scenes.size}")

  val csv = new String(Files.readAllBytes(Paths.get(recallCsv)), StandardCharsets.UTF_8)
  val words = RecallWordsCsv.parse(csv).fold(e => throw new IllegalArgumentException(e), identity)
  RecallToVideo.run(
    built,
    words,
    SherlockAnnotationView.axes(atlas),
    "sherlock-recall",
    Paths.get(outPath)
  )
