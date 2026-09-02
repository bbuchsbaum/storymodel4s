package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import io.circe.parser.parse as parseJson

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

  /** Source-text policy for the embedded rendering.
    *
    *   - `bare` (default, the historical behaviour): the coder's description alone.
    *   - `enriched`: location and the characters present, then the description.
    *
    * The annotation table carries location and character columns that the description prose usually
    * omits, and until now they reached only the lexical-overlap channel, which the pipeline
    * disables. Distant confusions measured on development recalls are dominated by segments whose
    * descriptions read alike but whose place and cast differ, so the fields are worth their own
    * arm. The policy changes what is embedded, never the human-readable text or the report.
    */
  def sourceTextPolicy: String =
    sys.env.get("STORYMODEL4S_SOURCE_TEXT").map(_.trim).filter(_.nonEmpty).getOrElse("bare")

  /** Leaf and scene enrichment are separate effects and must be separable arms: measured on the
    * development recalls, enriching both at once was strictly worse than bare on concentration and
    * source mass, and a bundled arm cannot say which half caused it. `enriched` keeps the bundled
    * meaning for the record of that run; `enriched-leaf` and `enriched-scene` isolate the halves.
    */
  /** Machine scene descriptions, keyed by atlas scene ordinal, from a study run of the `media`
    * captioning court. Supplied by path, never fetched: the file is a recorded outcome whose model
    * pin and frame digest live beside it.
    *
    * Why the scene level: three quarters of the distant recall-anchor confusions measured on the
    * development recalls cross a scene boundary, and a scene node's text is otherwise a
    * near-contentless label such as "2. War Scene". Unlike the location-and-cast enrichment, whose
    * vocabulary recurs across the episode and measurably spread mass rather than concentrating it,
    * a caption is high-cardinality: it describes what is distinctively in view.
    */
  lazy val sceneCaptions: Map[Int, String] =
    sys.env.get("STORYMODEL4S_SCENE_CAPTIONS").map(_.trim).filter(_.nonEmpty) match
      case None => Map.empty
      case Some(path) =>
        val text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
        parseJson(text).toOption
          .flatMap(_.hcursor.downField("captions").as[Map[String, String]].toOption)
          .map(_.flatMap { case (k, v) => k.toIntOption.map(_ -> v) })
          .getOrElse(
            throw new IllegalArgumentException(s"scene captions at $path have no captions object")
          )

  private def enrichLeaves: Boolean =
    sourceTextPolicy == "enriched" || sourceTextPolicy == "enriched-leaf"
  /** Samples per scene and word budget for the digest control, chosen to match what the captioner
    * saw and produced: eight evenly spaced frames, and a median caption of 85 words.
    */
  private val DigestSamples = 8
  private val DigestWords = 85

  private def digestScenes: Boolean = sourceTextPolicy == "digest-scene"

  private def enrichScenes: Boolean =
    sourceTextPolicy == "enriched" || sourceTextPolicy == "enriched-scene"

  private def enriched(row: SherlockAnnotations.Row): String =
    val where = row.location.map(_.trim).filter(_.nonEmpty)
    val who = row.namesAll.map(_.trim).filter(_.nonEmpty).distinct
    val prefix =
      (where.toVector ++ (if who.isEmpty then Vector.empty else Vector(who.mkString(", "))))
    if prefix.isEmpty then row.description else prefix.mkString(". ") + ". " + row.description

  /** A scene's embedded rendering under the enriched policy: its label, then the places and cast
    * that occur in it. The bare label ("2. War Scene") is nearly contentless, and three quarters of
    * the distant confusions measured on development recalls cross a scene boundary, so the scene
    * level is where discrimination is worth adding first.
    */
  private def enrichedGroup(atlas: Atlas, ordinal: Int, label: String): String =
    val rows = atlas.rows.filter(r => atlas.sceneOf(r.row).exists(_.ordinal == ordinal))
    val places = rows.flatMap(_.location).map(_.trim).filter(_.nonEmpty).distinct
    val cast = rows.flatMap(_.namesAll).map(_.trim).filter(_.nonEmpty).distinct
    val parts = Vector(label) ++
      (if places.isEmpty then Vector.empty else Vector(places.mkString(", "))) ++
      (if cast.isEmpty then Vector.empty else Vector(cast.mkString(", ")))
    parts.mkString(". ")

  /** The control the caption arm needs: a scene's own coder descriptions, sampled the way the
    * frames were sampled.
    *
    * A caption arm moves two things at once against baseline. The scene node gains content, and
    * that content is visual. This control gives the scene node content of the same shape and length
    * with nothing visual in it, so the gap between the two arms is what the camera contributed
    * beyond what a human coder had already written down. Eight descriptions are taken at evenly
    * spaced midpoints across the scene, mirroring the captioner's eight evenly spaced frames, and
    * the text is truncated to the captions' median length so that length is not the difference
    * being measured.
    */
  private def digestGroup(atlas: Atlas, ordinal: Int, label: String): String =
    val rows = atlas.rows.filter(r => atlas.sceneOf(r.row).exists(_.ordinal == ordinal))
    val picks =
      if rows.size <= DigestSamples then rows
      else
        Vector
          .tabulate(DigestSamples)(k => rows((k * 2 + 1) * rows.size / (DigestSamples * 2)))
          .distinct
    val words =
      picks.map(_.description.trim).filter(_.nonEmpty).mkString(" ").split("\\s+").toVector
    (Vector(label + ".") ++ words.take(DigestWords)).mkString(" ")

  def segments(atlas: Atlas): Vector[TimedSegment] =
    atlas.rows.map { row =>
      TimedSegment(
        ordinal = row.row,
        text = row.description,
        locus = atlas.mediaByRow.get(row.row).map(convert),
        group = atlas
          .sceneOf(row.row)
          .map(s =>
            TimedSegment.Group(
              s.ordinal,
              s.label,
              sceneCaptions
                .get(s.ordinal)
                .map(c => s"${s.label}. $c")
                .orElse(
                  if enrichScenes then Some(enrichedGroup(atlas, s.ordinal, s.label))
                  else if digestScenes then Some(digestGroup(atlas, s.ordinal, s.label))
                  else None
                )
            )
          ),
        extraLemmas = stemsOf(row.namesAll) ++ stemsOf(row.namesSpeaking) ++
          row.location.map(Lexical.stems(_).toSet).getOrElse(Set.empty),
        locations = row.location.map(Lexical.words).getOrElse(Vector.empty),
        embedText = if enrichLeaves then Some(enriched(row)) else None
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
  println(s"source text policy: ${SherlockAnnotationView.sourceTextPolicy}")
  println(s"scene captions: ${SherlockAnnotationView.sceneCaptions.size}")

  val csv = new String(Files.readAllBytes(Paths.get(recallCsv)), StandardCharsets.UTF_8)
  val words = RecallWordsCsv.parse(csv).fold(e => throw new IllegalArgumentException(e), identity)
  RecallToVideo.run(
    built,
    words,
    SherlockAnnotationView.axes(atlas),
    "sherlock-recall",
    Paths.get(outPath)
  )
