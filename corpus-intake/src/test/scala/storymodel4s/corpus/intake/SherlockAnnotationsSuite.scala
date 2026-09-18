package storymodel4s.corpus.intake

import java.nio.charset.StandardCharsets

import munit.FunSuite
import storymodel4s.corpus.intake.SherlockAnnotations.*
import storymodel4s.core.{Checksum, DomainError}

/** Courts for the Sherlock annotation adapter, on a synthetic six-row fixture shaped like the real
  * table (two runs, a scene opening each run, a zero-duration row, a scan-break-style row with
  * blank TRs). The real 1000-row table never enters the repository (fixture policy 14(d)); the
  * runner verifies it live against the pinned manifest hash.
  */
class SherlockAnnotationsSuite extends FunSuite:

  private val header =
    "Segment Number\tStart Time (s) \tEnd Time (s) \tStart TR\tEnd TR\tScene Segments\t" +
      "Scene Details - A Level \tSpace-In/Outdoor\tName - All\tName - Focus\tName - Speaking\t" +
      "Location\tCamera Angle\tMusic Presence \tWords on Screen "

  /** Six rows: run 1 = rows 1-4 on part A, run 2 = rows 5-6 on part B. Row 3 is zero-duration; row
    * 4 has blank TRs (scan-break shape).
    */
  private val fixtureRows = Vector(
    "1\t0\t10\t1\t7\t1. Opening\tA man walks in the rain.\tOutdoor\tMan\tMan\t\tStreet\tLong\tNo\t",
    "2\t10\t20\t8\t14\t\tThe man finds a red door and knocks twice.\tOutdoor\tMan\t\tMan\tStreet\tMedium\tNo\t",
    "3\t20\t20\t14\t14\t\tSmash cut to black.\tIndoor\t\t\t\t\tClose\tNo\t",
    "4\t20\t30\t\t\t\tBlack screen while the projector is switched.\tIndoor\t\t\t\t\tLong\tNo\t",
    "5\t0\t5\t21\t24\t2. Cartoon\tPeople in costumes parade and sing.\tIndoor\tSingers\tSingers\tSingers\tCartoon World\tLong\tYes\t",
    "6\t5\t12\t25\t29\t\tPopcorn pops in a glass machine.\tIndoor\tSinger\t\tSinger\tCartoon World\tMedium\tYes\t"
  )

  private def bytesOf(rows: Vector[String]): Array[Byte] =
    (header +: rows).mkString("\n").getBytes(StandardCharsets.UTF_8)

  private def manifestFor(bytes: Array[Byte]): MediaManifest =
    MediaManifest(
      annotationSha256 = Checksum.ofBytes(bytes),
      partA = PartIdentity(
        partId = "media-part-a",
        presentationOrdinal = 1,
        sha256 = Checksum.ofBytes("synthetic-part-a".getBytes(StandardCharsets.UTF_8)),
        durationTicks = 76000L, // 30.4 s at 1/2500: leaves a 0.4 s uncovered tail
        ticksPerSecond = 2500L
      ),
      partB = PartIdentity(
        partId = "media-part-b",
        presentationOrdinal = 2,
        sha256 = Checksum.ofBytes("synthetic-part-b".getBytes(StandardCharsets.UTF_8)),
        durationTicks = 30000L, // exactly 12 s: run-2 annotation may end flush with the extent
        ticksPerSecond = 2500L
      ),
      totalRows = 6,
      run1EndRow = 4
    )

  private def parseFixture(rows: Vector[String] = fixtureRows): Either[DomainError, Atlas] =
    val bytes = bytesOf(rows)
    SherlockAnnotations.parse(bytes, manifestFor(bytes))

  private def parsed: Atlas =
    parseFixture().fold(e => fail(s"fixture must parse: ${e.message}"), identity)

  test("the synthetic table parses with both runs, scenes, and typed missing TRs") {
    val atlas = parsed
    assertEquals(atlas.rows.size, 6)
    assertEquals(
      atlas.scenes.map(s => (s.ordinal, s.firstRow, s.lastRow)),
      Vector((1, 1, 4), (2, 5, 6))
    )
    assertEquals(atlas.runOf(4), RunId.Run1)
    assertEquals(atlas.runOf(5), RunId.Run2)
    val scanBreak = atlas.rows(3)
    assertEquals(scanBreak.startTr, None)
    assertEquals(scanBreak.endTr, None)
    assertEquals(atlas.rows(1).namesAll, Vector("Man"))
    assertEquals(atlas.rows(4).location, Some("Cartoon World"))
  }

  test("the crosswalk is the identity seconds-to-ticks mapping onto the run's own part") {
    val atlas = parsed
    atlas.mediaByRow(1) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-a")
        assertEquals(iv.start, 0L)
        assertEquals(iv.endExclusive, 25000L)
      case other => fail(s"row 1 must map to an extent, got $other")
    // Run 2 restarts at tick 0 on part B; its seconds are never offset onto a concatenated clock.
    atlas.mediaByRow(5) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-b")
        assertEquals(iv.start, 0L)
        assertEquals(iv.endExclusive, 12500L)
      case other => fail(s"row 5 must map to an extent, got $other")
    // The last run-2 row may end flush with the part extent.
    assertEquals(atlas.mediaByRow(6).endTick, 30000L)
  }

  test("a zero-duration row becomes a playback instant, never a fabricated interval") {
    parsed.mediaByRow(3) match
      case MediaLocus.Instant(part, at) =>
        assertEquals(part, "media-part-a")
        assertEquals(at.at, 50000L)
      case other => fail(s"row 3 must map to an instant, got $other")
  }

  test("each part bundle carries its own playback axis with the pinned extent") {
    val atlas = parsed
    assertEquals(atlas.partABundle.primaryAxis.id == atlas.partBBundle.primaryAxis.id, false)
    val extents = Vector(atlas.partABundle, atlas.partBBundle).map(_.primaryAxis.extent)
    assertEquals(
      extents.collect { case pt: storymodel4s.core.AxisExtent.PlaybackTicks => pt.endExclusive },
      Vector(76000L, 30000L)
    )
  }

  test("foreign bytes refuse before any parsing") {
    val bytes = bytesOf(fixtureRows)
    val foreign = manifestFor("something else entirely".getBytes(StandardCharsets.UTF_8))
    SherlockAnnotations.parse(bytes, foreign) match
      case Left(DomainError.InvariantViolation(path, _)) =>
        assertEquals(path, "sherlock/annotation/identity")
      case other => fail(s"expected an identity refusal, got $other")
  }

  test("a row-count mismatch refuses") {
    parseFixture(fixtureRows.dropRight(1)) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/annotation/rows")
        assert(reason.contains("expected 6"), reason)
      case other => fail(s"expected a row-count refusal, got $other")
  }

  test("a numbering break refuses") {
    val broken = fixtureRows.updated(2, fixtureRows(2).replaceFirst("^3\t", "7\t"))
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/row/3")
        assert(reason.contains("numbering breaks"), reason)
      case other => fail(s"expected a numbering refusal, got $other")
  }

  test("non-integer seconds refuse: the crosswalk claims integer bounds") {
    val broken = fixtureRows.updated(1, fixtureRows(1).replaceFirst("\t10\t20\t", "\t10.5\t20\t"))
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/row/2")
        assert(reason.contains("not an integer second"), reason)
      case other => fail(s"expected an integer refusal, got $other")
  }

  test("reversed bounds refuse") {
    val broken = fixtureRows.updated(1, fixtureRows(1).replaceFirst("\t10\t20\t", "\t20\t10\t"))
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/row/2")
        assert(reason.contains("reversed"), reason)
      case other => fail(s"expected a reversed-bounds refusal, got $other")
  }

  test("a coordinate escaping the part's playback extent refuses instead of stretching") {
    val broken = fixtureRows.updated(5, fixtureRows(5).replaceFirst("\t5\t12\t", "\t5\t13\t"))
    parseFixture(broken) match
      case Left(e)  => assert(e.message.contains("escapes"), e.message)
      case Right(_) => fail("a 13 s bound on a 12 s part must refuse")
  }

  test("an empty description refuses") {
    val broken = fixtureRows.updated(
      2,
      "3\t20\t20\t14\t14\t\t\tIndoor\t\t\t\t\tClose\tNo\t"
    )
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, "sherlock/row/3")
        assert(reason.contains("description"), reason)
      case other => fail(s"expected a description refusal, got $other")
  }

  test("a table whose first row opens no scene refuses") {
    val broken = fixtureRows.updated(0, fixtureRows(0).replaceFirst("\t1\\. Opening\t", "\t\t"))
    parseFixture(broken) match
      case Left(DomainError.InvariantViolation(path, _)) =>
        assertEquals(path, "sherlock/scenes")
      case other => fail(s"expected a scene refusal, got $other")
  }

  /** This test is NAMED for `timebase-repair.json` and, until now, never opened it.
    *
    * It asserted Scala literals against Scala literals: `assertEquals(m.partA.durationTicks,
    * 3565500L)` is true of any manifest that says 3565500, whatever the JSON says. The epic that
    * built this module exists because the Sherlock crosswalk is declared in several places at once;
    * a test that restates one of them is not a check, it is a fourth copy.
    *
    * Measured by mutation: seven fields of the JSON could be changed with the whole `corpus-intake`
    * suite staying green. Three of them are load-bearing --
    * `coordinateSystems[0].parts[*].endSeconds` (the part extents, which `nn2017` transcribes as
    * `durationTicks` after multiplying by 2500) and `coordinateSystems[2].secondsPerTr`.
    *
    * So the constants are now read FROM the file, and the file's two independent declarations of
    * the same quantity are bound to each other. `endSeconds * ticksPerSecond` must equal
    * `playbackEndTicks + uncoveredTailTicks`; nothing had ever required those to agree, and they
    * agreed by luck.
    */
  private def repairJson: String =
    val a = java.nio.file.Path.of("../docs/data/sherlock/timebase-repair.json")
    val b = java.nio.file.Path.of("docs/data/sherlock/timebase-repair.json")
    if java.nio.file.Files.exists(a) then java.nio.file.Files.readString(a)
    else java.nio.file.Files.readString(b)

  test("the pinned nn2017 manifest is READ FROM timebase-repair.json, not restated") {
    val m = MediaManifest.nn2017
    val record = TimebaseRepair
      .parse(repairJson)
      .fold(r => fail(s"the pinned repair record must parse: ${r.message}"), identity)

    assertEquals(m.annotationSha256, record.annotationSha256)
    assertEquals(Some(m.run1EndRow), record.run1EndRow)
    assertEquals(m.totalRows, record.inputRows)

    val run1 = record.run("run-1").getOrElse(fail("run-1 must be declared"))
    val run2 = record.run("run-2").getOrElse(fail("run-2 must be declared"))
    assertEquals(m.partA.partId, run1.partId)
    assertEquals(m.partB.partId, run2.partId)
    assertEquals(m.partA.durationTicks, run1.durationTicks)
    assertEquals(m.partB.durationTicks, run2.durationTicks)
  }

  /** The part BYTE IDENTITIES were transcribed too, and they are the most load-bearing values here.
    *
    * `SherlockAnnotations.scala:52-53` carries `Checksum.unsafe("eb036474...")` as a Scala literal
    * while `presentationEditionIdentity.parts[*].sha256` declares the same hash in the file, and
    * nothing compared them. That hash exists, in the adapter's own words, "so downstream playback
    * can re-verify caller-supplied media" -- so a drift between the record and the adapter means
    * the two vouch for DIFFERENT FILES while both claiming to identify the presented edition. Of
    * everything measured silent in this file, this is the one whose silence matters most.
    *
    * Found by cold review, which asked directly whether the earlier fix covered it. It did not: the
    * fix bound `partId` and `durationTicks` and stopped there.
    */
  test("the part byte identities are READ from the file, not transcribed beside it") {
    val m = MediaManifest.nn2017
    val cursor = io.circe.parser
      .parse(repairJson)
      .fold(e => fail(s"json must parse: $e"), _.hcursor)
    val parts = cursor
      .downField("presentationEditionIdentity")
      .downField("parts")
      .as[Vector[io.circe.Json]]
      .fold(e => fail(s"presentationEditionIdentity.parts must be an array: $e"), identity)
    assertEquals(parts.size, 2)

    Vector(m.partA, m.partB).zip(parts).foreach { (part, json) =>
      val c = json.hcursor
      assertEquals(
        c.get[String]("partId").toOption,
        Some(part.partId),
        "the edition identity names a different part than the manifest"
      )
      assertEquals(
        c.get[Int]("presentationOrdinal").toOption,
        Some(part.presentationOrdinal),
        s"${part.partId} is presented in a different position than the manifest says"
      )
      assertEquals(
        c.get[String]("sha256").toOption,
        Some(part.sha256.hex),
        s"${part.partId}: the record and the adapter vouch for DIFFERENT BYTES"
      )
    }
  }

  /** The file declares each part's extent TWICE, and nothing made the two agree.
    *
    * `annotationToPlaybackCrosswalk.runs[i]` gives `playbackEndTicks + uncoveredTailTicks`, which
    * the code reads. `coordinateSystems[0].parts[i].endSeconds` gives the same extent in seconds,
    * which the code does not read -- so it could be changed to 9999.9 with every test still green,
    * measured. Two declarations that nothing reconciles are two declarations that will drift.
    */
  test("the file's two declarations of each part extent agree") {
    val record = TimebaseRepair
      .parse(repairJson)
      .fold(r => fail(r.message), identity)
    val cursor = io.circe.parser
      .parse(repairJson)
      .fold(e => fail(s"json must parse: $e"), _.hcursor)
    val parts = cursor
      .downField("coordinateSystems")
      .downArray
      .downField("parts")
      .as[Vector[io.circe.Json]]
      .fold(e => fail(s"coordinateSystems[0].parts must be an array: $e"), identity)
    assertEquals(parts.size, 2)

    Vector(("run-1", 0, MediaManifest.nn2017.partA), ("run-2", 1, MediaManifest.nn2017.partB))
      .foreach { (runId, i, part) =>
        val run = record.run(runId).getOrElse(fail(s"$runId must be declared"))
        val c = parts(i).hcursor
        assertEquals(
          c.get[String]("partId").toOption,
          Some(run.partId),
          s"$runId names a different part in coordinateSystems than in the crosswalk"
        )
        // The rate is DERIVED from the file rather than taken from the manifest, so that the
        // manifest's own 2500 is checked against the file instead of checking itself. The file's
        // stated formula is `playbackTicks = rawAnnotationSeconds * ticksPerSecond`.
        assertEquals(
          run.playbackEndTicks % run.annotationEndSeconds,
          0L,
          s"$runId rate is not whole"
        )
        val ticksPerSecond = run.playbackEndTicks / run.annotationEndSeconds
        assertEquals(
          part.ticksPerSecond,
          ticksPerSecond,
          s"$runId: the manifest declares ${part.ticksPerSecond} ticks/s, the file implies $ticksPerSecond"
        )
        val endSeconds =
          c.get[Double]("endSeconds").fold(e => fail(s"parts[$i].endSeconds: $e"), identity)
        val declared = math.round(endSeconds * ticksPerSecond.toDouble)
        assertEquals(
          declared,
          run.durationTicks,
          s"$runId: coordinateSystems says ${endSeconds}s = $declared ticks, " +
            s"the crosswalk says ${run.durationTicks}"
        )
      }
  }
