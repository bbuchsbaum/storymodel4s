package storymodel4s.acquire

import java.nio.charset.StandardCharsets

import munit.FunSuite
import storymodel4s.acquire.SherlockAnnotations.*
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

  test("the pinned nn2017 manifest carries the crosswalk constants of timebase-repair.json v2") {
    val m = MediaManifest.nn2017
    assertEquals(m.totalRows, 1000)
    assertEquals(m.run1EndRow, 482)
    assertEquals(m.partA.durationTicks, 3565500L)
    assertEquals(m.partB.durationTicks, 3887000L)
    assertEquals(m.partA.ticksPerSecond, 2500L)
    assertEquals(
      m.annotationSha256.hex,
      "8c205826dcea8c58db24d7a17c71a3f99a9054e9379435e60b1dd0b987c2b296"
    )
  }
