package storymodel4s.bench.filmfestival

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import storymodel4s.bench.video.{MediaLocus, RecallToVideo, RecallWordsCsv, TimedSegment, TimedSourceView}
import storymodel4s.core.*
import storymodel4s.recall.Lexical

/** Read-only adapter for the Film Festival annotation, as replayed by
  * `tools/corpus/filmfest_annotation.py`.
  *
  * The corpus is ten short films watched in two scanning runs, so the two runs play the part the
  * two media parts play for Sherlock: one presentation axis each, run-relative seconds. Films are
  * not axes; they are ranges of scene numbers inside a run, which is the space the recall-to-scene
  * gold already uses.
  *
  * Three differences from Sherlock, all of them the reason this is a separate adapter rather than a
  * reuse of `SherlockAnnotations`:
  *
  *   - There is no admitted media edition. The playback axis here is the **annotation's own
  *     extent**, identified by the checksum of the replayed TSV, not by any film's bytes. It is a
  *     placeholder in exactly the sense Sherlock's axis was before the owner declared an edition,
  *     and nothing downstream may read it as a claim about a film.
  *   - Coarse segment numbers restart in run 2 while the gold uses one global 1..216 space. The
  *     replay tool resolves that and writes `scene_number` already global, or leaves it empty when a
  *     coder's numbering cannot carry the gold. A row without a scene number simply gets no group.
  *   - A segment whose successor is in the other run has no end, so it is bound as an `Instant`
  *     rather than given an invented duration.
  */
object FilmFestivalAnnotation:

  /** Ticks per second on the annotation axis. The replay emits whole seconds; the finer tick keeps
    * headroom for a later replay that carries sub-second bounds.
    */
  val TicksPerSecond: Long = 1000L

  /** One replayed annotation row. `sceneNumber` is global across both runs when the coder's
    * numbering carries the gold's space, and absent when it does not.
    */
  final case class Row(
      segment: Int,
      partId: String,
      run: String,
      film: String,
      sceneNumber: Option[Int],
      startSeconds: Int,
      endSeconds: Option[Int],
      description: String
  )

  final case class Table(rows: Vector[Row], checksum: Checksum):
    def parts: Vector[String] = rows.map(_.partId).distinct
    def rowsOf(partId: String): Vector[Row] = rows.filter(_.partId == partId)

    /** Exclusive end of a part's axis: one second past the last bound the annotation states. */
    def endSecondsOf(partId: String): Int =
      rowsOf(partId).map(r => r.endSeconds.getOrElse(r.startSeconds)).maxOption.getOrElse(0) + 1

  private val Header =
    Vector(
      "segment", "part_id", "run", "film", "scene_number",
      "coarse_start_s", "start_s", "end_s", "description"
    )

  def parse(bytes: Array[Byte]): Either[String, Table] =
    val text = new String(bytes, StandardCharsets.UTF_8)
    val lines = text.split('\n').toVector.map(_.stripSuffix("\r")).filter(_.nonEmpty)
    lines match
      case header +: body if header.split('\t').toVector == Header =>
        val parsed = body.zipWithIndex.map { case (line, i) => rowOf(line, i + 2) }
        parsed.collectFirst { case Left(e) => e } match
          case Some(e) => Left(e)
          case None =>
            val rows = parsed.collect { case Right(r) => r }
            if rows.isEmpty then Left("annotation replay has no rows")
            else if rows.map(_.segment) != rows.indices.map(_ + 1).toVector then
              Left("annotation replay segment numbers are not 1..n")
            else Right(Table(rows, Checksum.ofBytes(bytes)))
      case header +: _ =>
        Left(s"unexpected annotation replay header: ${header.split('\t').toVector}")
      case _ => Left("annotation replay is empty")

  private def rowOf(line: String, lineNo: Int): Either[String, Row] =
    val c = line.split('\t')
    def at(i: Int): String = if i < c.length then c(i).trim else ""
    def intAt(i: Int, name: String): Either[String, Int] =
      at(i).toIntOption.toRight(s"line $lineNo: $name is not an integer: '${at(i)}'")
    for
      segment <- intAt(0, "segment")
      start <- intAt(6, "start_s")
      description <-
        if at(8).nonEmpty then Right(at(8)) else Left(s"line $lineNo: empty description")
    yield Row(
      segment = segment,
      partId = at(1),
      run = at(2),
      film = at(3),
      sceneNumber = at(4).toIntOption,
      startSeconds = start,
      endSeconds = at(7).toIntOption.filter(_ > start),
      description = description
    )

object FilmFestivalAnnotationView:
  import FilmFestivalAnnotation.{Row, Table, TicksPerSecond}

  val naming: TimedSourceView.Naming =
    TimedSourceView.Naming(n => f"filmfest:seg:$n%04d", n => f"filmfest:scene:$n%03d")

  private def stemsOf(parts: Iterable[String]): Set[String] =
    parts.iterator.flatMap(Lexical.stems).toSet

  /** One placeholder playback axis per scanning run, identified by the annotation's checksum.
    *
    * This is deliberately not a film edition: no Film Festival video is admitted, and the release
    * withholds it. The bundle exists so annotation bounds have a lawful axis to sit on.
    */
  def bundles(table: Table): Either[DomainError, Map[String, SourceBundle]] =
    table.parts
      .foldLeft[Either[DomainError, Map[String, SourceBundle]]](Right(Map.empty)) {
        case (acc, partId) =>
          for
            soFar <- acc
            edition <- EditionId.from(s"filmfestival-annotation-${table.checksum.short(12)}-$partId")
            timebase <- RationalTimebase.of(1L, TicksPerSecond)
            bundle <- SourceBundle.filmEdition(
              edition,
              table.checksum,
              0L,
              table.endSecondsOf(partId).toLong * TicksPerSecond,
              timebase
            )
          yield soFar + (partId -> bundle)
      }

  def axes(bundles: Map[String, SourceBundle]): Map[String, PresentationAxis] =
    bundles.view.mapValues(_.primaryAxis).toMap

  private def locus(row: Row, bundle: SourceBundle): Either[DomainError, MediaLocus] =
    val startTick = row.startSeconds.toLong * TicksPerSecond
    row.endSeconds match
      case Some(end) =>
        PlaybackInterval
          .on(bundle.primaryAxis, startTick, end.toLong * TicksPerSecond)
          .map(MediaLocus.Extent(row.partId, _))
      case None =>
        PlaybackInstant.on(bundle.primaryAxis, startTick).map(MediaLocus.Instant(row.partId, _))

  /** A scene's rendering for an embedding channel: its film, then the prose of its own segments. A
    * bare scene number carries no content, and the group label alone would be worse than useless.
    */
  private def groupText(table: Table, sceneNumber: Int): String =
    val rows = table.rows.filter(_.sceneNumber.contains(sceneNumber))
    val film = rows.headOption.map(_.film).filter(_.nonEmpty).map(_ + ". ").getOrElse("")
    film + rows.map(_.description).mkString(" ")

  def segments(table: Table, bundles: Map[String, SourceBundle]): Vector[TimedSegment] =
    table.rows.map { row =>
      TimedSegment(
        ordinal = row.segment,
        text = row.description,
        locus = bundles.get(row.partId).flatMap(b => locus(row, b).toOption),
        group = row.sceneNumber.map(n =>
          TimedSegment.Group(n, s"${row.film} scene $n".trim, Some(groupText(table, n)), None)
        ),
        extraLemmas = stemsOf(Option(row.film).filter(_.nonEmpty)),
        locations = Vector.empty
      )
    }

  def build(table: Table): Either[DomainError, (TimedSourceView.Built, Map[String, PresentationAxis])] =
    bundles(table).map { bs =>
      (TimedSourceView.build(segments(table, bs), axes(bs), naming), axes(bs))
    }

/** Terminal diagnostic: map one Film Festival recall transcript onto the two scanning runs.
  *
  * Usage: `filmFestivalRecallMap <annotation-replay.tsv> <recall-words.csv> <report.tsv>`. Both
  * inputs are produced by `tools/corpus/filmfest_annotation.py` and `tools/corpus/filmfest_recall.py`
  * and stay outside Git; the report contains recall text and is therefore local-sensitive, so it is
  * written under the local data root (`tools/data-root.sh`, `data/README.md`).
  */
@main def filmFestivalRecallMap(annotationTsv: String, recallCsv: String, outPath: String): Unit =
  val bytes = Files.readAllBytes(Paths.get(annotationTsv))
  val table = FilmFestivalAnnotation
    .parse(bytes)
    .fold(e => throw new IllegalArgumentException(e), identity)
  val (built, axes) = FilmFestivalAnnotationView
    .build(table)
    .fold(e => throw new IllegalArgumentException(e.message), identity)

  val scenes = table.rows.flatMap(_.sceneNumber).distinct.size
  println(s"annotation rows: ${table.rows.size}; scenes: $scenes; parts: ${table.parts.mkString(", ")}")
  println(s"annotation identity: ${table.checksum.short(16)}")
  table.parts.foreach(p => println(s"  $p: axis 0..${table.endSecondsOf(p)}s"))

  val csv = new String(Files.readAllBytes(Paths.get(recallCsv)), StandardCharsets.UTF_8)
  val words = RecallWordsCsv.parse(csv).fold(e => throw new IllegalArgumentException(e), identity)
  RecallToVideo.run(built, words, axes, "filmfestival-recall", Paths.get(outPath))
