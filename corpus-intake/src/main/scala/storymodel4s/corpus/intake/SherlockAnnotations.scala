package storymodel4s.corpus.intake

import storymodel4s.core.*

/** Read-only adapter for the admitted Sherlock annotation table (Microsegment1000) and its
  * owner-established crosswalk onto the two presentation-edition media parts.
  *
  * Why this exists: the published annotation table carries run-local integer seconds, and the
  * repaired notebook clock is a scanner/TR analysis axis that is *wrong for media* (it deletes rows
  * 481-482 and offsets run 2 by 1419 s). The only lawful route from an annotation row to a playable
  * media coordinate is the `annotation-raw-to-part-playback-v1` crosswalk recorded in
  * `docs/data/sherlock/timebase-repair.json` (schemaVersion 2): run 1 rows map to part-A playback
  * ticks and run 2 rows to part-B playback ticks by the identity `ticks = rawSeconds * 2500`. This
  * adapter replays exactly that record against exact input bytes; it never reads, copies, or
  * decodes video, and it refuses rather than repairs any input that does not match the pinned
  * identity.
  */
object SherlockAnnotations:

  /** Identity constants of one pinned media part (from `presentationEditionIdentity`). The hash is
    * the movie file's byte identity, recorded so downstream playback can re-verify caller-supplied
    * media; this adapter itself never touches those bytes.
    */
  final case class PartIdentity(
      partId: String,
      presentationOrdinal: Int,
      sha256: Checksum,
      durationTicks: Long,
      ticksPerSecond: Long
  )

  /** The published identity record this adapter replays. `run1EndRow` is the last annotation row
    * presented from part A; every later row is run 2 on part B.
    */
  final case class MediaManifest(
      annotationSha256: Checksum,
      partA: PartIdentity,
      partB: PartIdentity,
      totalRows: Int,
      run1EndRow: Int
  )

  /** Which presentation run a row belongs to. Derived from the row number against the manifest
    * boundary, never asserted by a caller.
    */
  enum RunId:
    case Run1, Run2

  /** One parsed annotation row. Raw seconds are run-local; `startTr`/`endTr` are `None` exactly
    * where the admitted table leaves them blank (the two scan-break rows). A zero-duration row
    * (`rawStartSeconds == rawEndSeconds`) is lawful here and becomes a media *instant*, never a
    * fabricated interval.
    */
  final case class Row(
      row: Int,
      rawStartSeconds: Int,
      rawEndSeconds: Int,
      startTr: Option[Int],
      endTr: Option[Int],
      sceneLabel: Option[String],
      description: String,
      space: Option[String],
      namesAll: Vector[String],
      namesSpeaking: Vector[String],
      location: Option[String]
  )

  /** Exact media coordinate of one annotation row on one part's playback axis. An `Extent` is a
    * half-open tick interval; an `Instant` records a zero-duration annotation bound as a point. The
    * two are distinct claims and are never converted into each other.
    */
  enum MediaLocus:
    case Extent(partId: String, interval: PlaybackInterval)
    case Instant(partId: String, at: PlaybackInstant)

    def part: String = this match
      case Extent(p, _)  => p
      case Instant(p, _) => p

    def startTick: Long = this match
      case Extent(_, iv)  => iv.start
      case Instant(_, at) => at.at

    def endTick: Long = this match
      case Extent(_, iv)  => iv.endExclusive
      case Instant(_, at) => at.at

  /** One coded scene: a maximal run of consecutive rows opened by a `Scene Segments` label. */
  final case class Scene(ordinal: Int, label: String, firstRow: Int, lastRow: Int)

  /** The checked atlas: rows, scene grouping, per-part film-edition bundles, and every row's exact
    * media locus. Construction is private: `parse` is the only door, so an atlas value witnesses
    * that its input bytes matched the pinned annotation identity and that every media coordinate
    * came from the recorded crosswalk.
    */
  final class Atlas private[SherlockAnnotations] (
      val manifest: MediaManifest,
      val partABundle: SourceBundle,
      val partBBundle: SourceBundle,
      val rows: Vector[Row],
      val mediaByRow: Map[Int, MediaLocus],
      val scenes: Vector[Scene],
      val repairRecord: TimebaseRepair.Record,
      val repairsByRun: Map[String, ClockRepair],
      val repairByRow: Map[Int, ClockRepair]
  ):
    def runOf(row: Int): RunId =
      if row <= manifest.run1EndRow then RunId.Run1 else RunId.Run2

    def sceneOf(row: Int): Option[Scene] =
      scenes.find(s => row >= s.firstRow && row <= s.lastRow)

    override def toString: String =
      s"SherlockAnnotations.Atlas(rows=${rows.size}, scenes=${scenes.size})"

  private def fail(path: String, reason: String): DomainError =
    DomainError.InvariantViolation(path, reason)

  private val IntegerField = "^\\d+$".r

  private def splitNames(cell: String): Vector[String] =
    cell.split("[/,]").toVector.map(_.trim).filter(_.nonEmpty)

  private def optCell(cells: Array[String], i: Int): Option[String] =
    if i < cells.length then Some(cells(i).trim).filter(_.nonEmpty) else None

  private def parseRow(line: String, expectedRow: Int): Either[DomainError, Row] =
    val cells = line.split('\t')
    if cells.length < 7 then
      Left(
        fail(
          s"sherlock/row/$expectedRow",
          s"expected >= 7 tab-separated cells, found ${cells.length}"
        )
      )
    else
      val rowCell = cells(0).trim
      val startCell = cells(1).trim
      val endCell = cells(2).trim
      for
        row <- rowCell.toIntOption.toRight(
          fail(s"sherlock/row/$expectedRow", s"row number '$rowCell' is not an integer")
        )
        _ <- Either.cond(
          row == expectedRow,
          (),
          fail(s"sherlock/row/$expectedRow", s"row numbering breaks: found $row")
        )
        start <- Either.cond(
          IntegerField.matches(startCell) && startCell.toIntOption.nonEmpty,
          startCell.toIntOption.getOrElse(0),
          fail(
            s"sherlock/row/$row",
            s"start '$startCell' is not an integer second; the crosswalk claims integer bounds"
          )
        )
        end <- Either.cond(
          IntegerField.matches(endCell) && endCell.toIntOption.nonEmpty,
          endCell.toIntOption.getOrElse(0),
          fail(
            s"sherlock/row/$row",
            s"end '$endCell' is not an integer second; the crosswalk claims integer bounds"
          )
        )
        _ <- Either.cond(
          start <= end,
          (),
          fail(s"sherlock/row/$row", s"reversed bounds: start $start > end $end")
        )
        startTr <- optCell(cells, 3) match
          case None    => Right(None)
          case Some(v) =>
            v.toIntOption
              .map(Some(_))
              .toRight(fail(s"sherlock/row/$row", s"start TR '$v' is not an integer"))
        endTr <- optCell(cells, 4) match
          case None    => Right(None)
          case Some(v) =>
            v.toIntOption
              .map(Some(_))
              .toRight(fail(s"sherlock/row/$row", s"end TR '$v' is not an integer"))
        description <- optCell(cells, 6).toRight(
          fail(s"sherlock/row/$row", "empty scene-detail description")
        )
      yield Row(
        row = row,
        rawStartSeconds = start,
        rawEndSeconds = end,
        startTr = startTr,
        endTr = endTr,
        sceneLabel = optCell(cells, 5),
        description = description,
        space = optCell(cells, 7),
        namesAll = optCell(cells, 8).map(splitNames).getOrElse(Vector.empty),
        namesSpeaking = optCell(cells, 10).map(splitNames).getOrElse(Vector.empty),
        location = optCell(cells, 11)
      )

  private[intake] def partBundle(part: PartIdentity): Either[DomainError, SourceBundle] =
    for
      edition <- EditionId.from(s"sherlock-nn2017-${part.partId}")
      timebase <- RationalTimebase.of(1L, part.ticksPerSecond)
      bundle <- SourceBundle.filmEdition(
        edition,
        part.sha256,
        0L,
        part.durationTicks,
        timebase
      )
    yield bundle

  /** This seam is shared by production and the wrong-axis/fractional-tick refusal courts. */
  private[intake] def project(
      seconds: ExactRational,
      sourceAxis: PresentationAxisId,
      bundle: SourceBundle,
      repair: ClockRepair
  ): Either[DomainError, Long] =
    ClockRepair.projectRunLocalSeconds(seconds, sourceAxis, bundle.primaryAxis.id, repair).flatMap {
      ticks =>
        Either.cond(
          ticks.denominator == 1L,
          ticks.numerator,
          fail("sherlock/clock/integrality", "projection must produce integral representable ticks")
        )
    }

  private[intake] def locusFor(
      row: Row,
      run: TimebaseRepair.Run,
      bundle: SourceBundle,
      repair: ClockRepair
  ): Either[DomainError, MediaLocus] =
    for
      startTick <- project(
        ExactRational.integer(row.rawStartSeconds.toLong),
        run.sourceAxis,
        bundle,
        repair
      )
      endTick <- project(
        ExactRational.integer(row.rawEndSeconds.toLong),
        run.sourceAxis,
        bundle,
        repair
      )
      locus <-
        if startTick == endTick then
          PlaybackInstant.on(bundle.primaryAxis, startTick).map(MediaLocus.Instant(run.partId, _))
        else
          PlaybackInterval
            .on(bundle.primaryAxis, startTick, endTick)
            .map(MediaLocus.Extent(run.partId, _))
    yield locus

  private def groupScenes(rows: Vector[Row]): Either[DomainError, Vector[Scene]] =
    rows.headOption match
      case Some(first) if first.sceneLabel.isEmpty =>
        Left(fail("sherlock/scenes", "the first row does not open a scene"))
      case _ =>
        val opens = rows.filter(_.sceneLabel.nonEmpty)
        val scenes = opens.zipWithIndex.map { case (open, i) =>
          val next = opens.lift(i + 1).map(_.row - 1).getOrElse(rows.last.row)
          Scene(i + 1, open.sceneLabel.getOrElse(""), open.row, next)
        }
        Right(scenes)

  /** Parse and check the annotation table against a checked repair record.
    *
    * Refuses (never repairs): a byte identity other than the pinned annotation hash, a row count or
    * numbering break, non-integer or reversed second bounds, an empty description, and any
    * crosswalked coordinate that escapes its part's playback extent.
    */
  def parse(
      bytes: Array[Byte],
      record: TimebaseRepair.Record
  ): Either[DomainError, Atlas] =
    val owned = bytes.clone()
    val manifest = record.manifest
    val observed = Checksum.ofBytes(owned)
    if observed != manifest.annotationSha256 then
      Left(
        fail(
          "sherlock/annotation/identity",
          s"input bytes hash to ${observed.short()}, not the admitted ${manifest.annotationSha256.short()}"
        )
      )
    else
      val content = new String(owned, java.nio.charset.StandardCharsets.UTF_8)
      val lines = content.split('\n').toVector.map(_.stripSuffix("\r"))
      val dataLines = lines.drop(1).filter(_.nonEmpty)
      if dataLines.size != manifest.totalRows then
        Left(
          fail(
            "sherlock/annotation/rows",
            s"expected ${manifest.totalRows} data rows, found ${dataLines.size}"
          )
        )
      else
        for
          rows <- dataLines.zipWithIndex.foldLeft(
            Right(Vector.empty[Row]): Either[DomainError, Vector[Row]]
          ) { case (acc, (line, i)) =>
            acc.flatMap(rs => parseRow(line, i + 1).map(rs :+ _))
          }
          bundleA <- partBundle(manifest.partA)
          bundleB <- partBundle(manifest.partB)
          bundles = Map(manifest.partA.partId -> bundleA, manifest.partB.partId -> bundleB)
          repairs <- TimebaseRepair.clockRepairs(record, bundles)
          byRun = record.runs.map(_.runId).zip(repairs).toMap
          media <- rows.foldLeft(
            Right(Map.empty[Int, MediaLocus]): Either[DomainError, Map[Int, MediaLocus]]
          ) { (acc, row) =>
            acc.flatMap { m =>
              val run = record.runs.find(r => row.row >= r.firstRow && row.row <= r.lastRow).get
              locusFor(row, run, bundles(run.partId), byRun(run.runId)).map(l => m + (row.row -> l))
            }
          }
          _ <- TimebaseRepair.validateRows(record, rows)
          scenes <- groupScenes(rows)
        yield new Atlas(
          manifest,
          bundleA,
          bundleB,
          rows,
          media,
          scenes,
          record,
          byRun,
          rows
            .map(r => r.row -> byRun(if r.row <= manifest.run1EndRow then "run-1" else "run-2"))
            .toMap
        )

  /** Omitting the declaration cannot silently select a literal edition or clock. */
  def parse(bytes: Array[Byte]): Either[DomainError, Atlas] =
    Left(
      fail(
        "sherlock/repair/missing",
        s"${bytes.length} annotation bytes require a checked repair record"
      )
    )
