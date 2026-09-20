package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.LocalDate

import io.circe.parser.parse as parseJson
import io.circe.Json

import storymodel4s.corpus.intake.{SherlockAnnotations, SherlockSourceAtlas, TimebaseRepair}
import storymodel4s.corpus.intake.SherlockAnnotations.Atlas
import storymodel4s.bench.video.{
  MediaLocus,
  RecallToVideo,
  RecallWordsCsv,
  TimedSegment,
  TimedSourceView,
  WorldOrderInput,
  WorldOrderWitness
}
import storymodel4s.core.{Checksum, DomainError, ExactRational, PresentationAxis, TypedSupport}
import storymodel4s.align.{ScoringPosition, ViewFingerprint}
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
      case None       => Map.empty
      case Some(path) =>
        val text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
        parseJson(text).toOption
          .flatMap(_.hcursor.downField("captions").as[Map[String, String]].toOption)
          .map(_.flatMap { case (k, v) => k.toIntOption.map(_ -> v) })
          .getOrElse(
            throw new IllegalArgumentException(s"scene captions at $path have no captions object")
          )

  /** Which channel a scene caption is routed to.
    *
    * Captions were first given to the encoder and measurably hurt localisation, in the same way and
    * for the same reason that location and cast metadata did: length and recurring vocabulary
    * dilute a mean-pooled vector. That metadata then produced the study's largest gain once it was
    * routed to the lexical index instead. A caption is the same shape of signal, so it gets the
    * same test rather than an assumption.
    */
  def captionChannel: String =
    sys.env.get("STORYMODEL4S_CAPTION_CHANNEL").map(_.trim.toLowerCase) match
      case Some("lexical") => "lexical"
      case _               => "embed"

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
                .filter(_ => captionChannel == "embed")
                .map(c => s"${s.label}. $c")
                .orElse(
                  if enrichScenes then Some(enrichedGroup(atlas, s.ordinal, s.label))
                  else if digestScenes then Some(digestGroup(atlas, s.ordinal, s.label))
                  else None
                ),
              sceneCaptions.get(s.ordinal).filter(_ => captionChannel == "lexical")
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

  /** Content-free provenance kept separate so coordinate integration does not rewrite the mapping
    * report. Every admitted annotation row, including unselected rows and instants, cites a repair.
    */
  private[bench] def clockProvenance(
      atlas: Atlas,
      built: TimedSourceView.Built,
      report: Checksum
  ): Json =
    def rational(value: ExactRational): Json = Json.obj(
      "numerator" -> Json.fromString(value.numerator.toString),
      "denominator" -> Json.fromString(value.denominator.toString)
    )
    val record = atlas.repairRecord
    Json.obj(
      "schema" -> Json.fromString("storymodel4s.bench.clock-repair"),
      "schemaVersion" -> Json.fromInt(1),
      "reportSha256" -> Json.fromString(report.hex),
      "sourceFingerprint" -> Json.fromString(ViewFingerprint.of(built.view).toString),
      "recordSchema" -> Json.fromString(TimebaseRepair.Schema),
      "recordSchemaVersion" -> Json.fromInt(TimebaseRepair.SchemaVersion),
      "recordSha256" -> Json.fromString(record.checksum.hex),
      "recordIdentityBasis" -> Json.fromString("observed-local-root-bytes"),
      "annotationSha256" -> Json.fromString(record.annotationSha256.hex),
      "certifies" -> Json.fromString(record.certifies),
      "doesNotCertify" -> Json.fromString(record.doesNotCertify),
      "whyNotRepaired" -> Json.fromString(record.whyNotRepaired),
      "explicitlyNotUsed" -> Json.fromString(record.explicitlyNotUsed),
      "notebookProvenanceStatus" -> Json.fromString(record.notebookProvenanceStatus),
      "scientificRestrictions" -> Json.obj(record.scientificRestrictions.toVector.sortBy(_._1).map {
        (key, values) => key -> Json.arr(values.map(Json.fromString)*)
      }*),
      "nonEquivalences" -> Json.arr(record.nonEquivalences.map(Json.fromString)*),
      "repairs" -> Json.arr(record.runs.map { run =>
        val repair = atlas.repairsByRun(run.runId)
        Json.obj(
          "run" -> Json.fromString(run.runId),
          "part" -> Json.fromString(run.partId),
          "declaredTargetAxis" -> Json.fromString(run.axisId),
          "sourceAxis" -> Json.fromString(repair.relation.sourceAxis.value),
          "targetAxis" -> Json.fromString(repair.relation.targetAxis.value),
          "scale" -> rational(repair.scale),
          "offset" -> rational(repair.offset),
          "receiptId" -> Json.fromString(repair.receipt.identity.hex),
          "algorithm" -> Json.fromString(repair.receipt.algorithm),
          "parameters" -> Json.fromString(repair.receipt.parameters),
          "inputChecksums" -> Json.arr(
            repair.receipt.inputChecksums.map(c => Json.fromString(c.hex))*
          )
        )
      }*),
      "rows" -> Json.arr(atlas.rows.map { row =>
        val locus = atlas.mediaByRow(row.row)
        Json.obj(
          "row" -> Json.fromInt(row.row),
          "receiptId" -> Json.fromString(atlas.repairByRow(row.row).receipt.identity.hex),
          "part" -> Json.fromString(locus.part),
          "startTick" -> Json.fromString(locus.startTick.toString),
          "endTick" -> Json.fromString(locus.endTick.toString)
        )
      }*)
    )

  /** The world clock this edition is built under (ADR 0013). A Study in Pink is read as presented:
    * the 50-scene annotation follows the broadcast cut and this declaration treats that cut as
    * story-world order. That is a claim with known exceptions, recorded in the basis rather than
    * hidden: the annotation marks the opening Afghanistan nightmare at rows 8-18 (named at row 21)
    * and flashback inserts at rows 390-402, interleaved with present-tense rows (Sherlock's
    * deduction replays the laboratory meeting), and at 953-954 (the search for the case). Under
    * this declaration they sit where they are shown, not when they happened. An `Explicit` rank
    * that moves them to story time is available and has not been run.
    */
  val worldOrder: WorldOrderInput = WorldOrderInput.SameAsPresentation(
    WorldOrderWitness(
      assertedBy = "study owner",
      basis =
        "A Study in Pink read as presented: the broadcast cut is taken as story-world order; " +
          "the opening Afghanistan nightmare at annotation rows 8-18 (named at row 21) and the " +
          "flashback inserts interleaved within rows 390-402 and at rows 953-954 " +
          "are placed where shown",
      asserted = LocalDate.of(2026, 9, 4)
    )
  )

  def build(atlas: Atlas): Either[DomainError, TimedSourceView.Built] =
    buildWith(atlas, worldOrder)

  /** Build under another declaration. The diagnostic uses [[worldOrder]] and nothing else. */
  def buildWith(
      atlas: Atlas,
      declared: WorldOrderInput
  ): Either[DomainError, TimedSourceView.Built] =
    for
      source <- SherlockSourceAtlas.of(atlas)
      features <- TimedSourceView.build(segments(atlas), declared, axes(atlas), naming)
        .left.map(error => DomainError.InvariantViolation("sherlock/world-order", error.message))
      nodes <- features.view.nodes.foldLeft[Either[DomainError, Vector[storymodel4s.align.NodeSummary]]](Right(Vector.empty)) {
        (acc, node) =>
          val unit = features.segmentByRef.get(node.ref).flatMap(s => source.rows.get(s.ordinal))
            .orElse(features.groupByRef.get(node.ref).flatMap(g => source.scenes.get(g.ordinal)))
          for
            previous <- acc
            admitted <- unit.toRight(DomainError.InvariantViolation("sherlock/source-view", s"missing unit ${node.ref.key}"))
            position <- node.scoringPosition.toRight(DomainError.InvariantViolation(
              "sherlock/source-view", "legacy annotation scoring feature is absent"))
          yield previous :+ node.copy(support = TypedSupport.Anchored(admitted.support),
            scoringPosition = Some(ScoringPosition.LegacyAnnotationText(position.spans)))
      }
    yield features.copy(view = features.view.copy(nodes = nodes), sourceAtlas = Some(source.atlas))

/** Terminal diagnostic: map one Sherlock recall transcript onto the two media parts.
  *
  * Usage: `sherlockRecallMap <annotation.tsv> <recall.csv> <report.tsv>`. Inputs stay outside Git;
  * the report contains recall text and is therefore local-sensitive (fixture policy 14(d)): it is
  * written wherever the caller points, normally the study record under the local data root
  * (`tools/data-root.sh`, `data/README.md`).
  */
@main def sherlockRecallMap(annotationTsv: String, recallCsv: String, outPath: String): Unit =
  val bytes = Files.readAllBytes(Paths.get(annotationTsv))
  val record = TimebaseRepair
    .loadCommitted()
    .fold(e => throw new IllegalArgumentException(e.message), identity)
  val atlas = SherlockAnnotations
    .parse(bytes, record)
    .fold(e => throw new IllegalArgumentException(e.message), identity)
  val built = SherlockAnnotationView
    .build(atlas)
    .fold(e => throw new IllegalArgumentException(e.message), identity)
  println(s"annotation rows: ${atlas.rows.size}; scenes: ${atlas.scenes.size}")
  println(s"world order: ${built.worldOrder.render}")
  println(s"source text policy: ${SherlockAnnotationView.sourceTextPolicy}")
  println(
    s"scene captions: ${SherlockAnnotationView.sceneCaptions.size}" +
      s" channel=${SherlockAnnotationView.captionChannel}"
  )

  val csv = new String(Files.readAllBytes(Paths.get(recallCsv)), StandardCharsets.UTF_8)
  val words = RecallWordsCsv.parse(csv).fold(e => throw new IllegalArgumentException(e), identity)
  // The released scene coding rides beside the voyage when the run names it; the participant is
  // read from the recall file name under the pre-registered mapping.
  val coding = sys.env.get("STORYMODEL4S_SCENE_CODING").flatMap { path =>
    SherlockSceneCoding.participantOf(Paths.get(recallCsv).getFileName.toString) match
      case None    => println(s"scene coding: recall file name carries no NNxx participant"); None
      case Some(n) =>
        SherlockSceneCoding
          .load(Paths.get(path), n)
          .fold(e => throw new IllegalArgumentException(e.message), identity)
  }
  println(s"scene coding: ${coding.fold("none")(c => s"${c.name} ${c.intervals.size} intervals")}")
  RecallToVideo.run(
    built,
    words,
    SherlockAnnotationView.axes(atlas),
    "sherlock-recall",
    Paths.get(outPath),
    coding
  )
  val provenance = SherlockAnnotationView.clockProvenance(
    atlas,
    built,
    Checksum.ofBytes(Files.readAllBytes(Paths.get(outPath)))
  )
  val _ = Files.writeString(
    Paths.get(outPath + ".clock-repair.json"),
    provenance.spaces2 + "\n",
    StandardCharsets.UTF_8
  )
