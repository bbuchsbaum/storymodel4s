package storymodel4s.corpus.intake

import java.nio.file.{Files, Path}

import storymodel4s.corpus.intake.SherlockAnnotations.MediaManifest
import storymodel4s.core.*

import munit.FunSuite

/** The point of this suite is one behaviour: CHANGE A VALUE IN `timebase-repair.json` AND IT GOES
  * RED.
  *
  * The suite it replaces, `SherlockAnnotationsSuite:188-199`, is named "the pinned nn2017 manifest
  * carries the crosswalk constants of timebase-repair.json v2" and never opens the JSON -- it
  * asserts Scala literals against Scala literals, so it cannot fail for the reason its name gives.
  * Every assertion below compares PARSED against USED.
  */
class TimebaseRepairSuite extends FunSuite:
  private val path = Path.of("../docs/data/sherlock/timebase-repair.json")
  private val alt = Path.of("docs/data/sherlock/timebase-repair.json")

  private def source: String =
    if Files.exists(path) then Files.readString(path)
    else if Files.exists(alt) then Files.readString(alt)
    else fail(s"timebase-repair.json not found at $path or $alt")

  private def record = TimebaseRepair.parse(source).fold(r => fail(r.message), identity)

  test("the record parses, and its schema and version are checked") {
    assertEquals(record.runs.size, 2)
    assert(TimebaseRepair.parse("""{"schema":"wrong","schemaVersion":2}""").isLeft)
    assert(
      TimebaseRepair
        .parse("""{"schema":"storymodel4s.sherlock.timebase-repair","schemaVersion":1}""")
        .isLeft
    )
    assert(TimebaseRepair.parse("not json").isLeft)
  }

  test("PARSED annotation checksum equals the one SherlockAnnotations USES") {
    assertEquals(record.annotationSha256, MediaManifest.nn2017.annotationSha256)
  }

  test("PARSED row accounting equals what SherlockAnnotations USES") {
    assertEquals(record.inputRows, MediaManifest.nn2017.totalRows)
    assertEquals(record.run1EndRow, Some(MediaManifest.nn2017.run1EndRow))
  }

  test("the DERIVATION that lived only in prose: end + tail = duration") {
    val a = record.run("run-1").getOrElse(fail("no run-1"))
    val b = record.run("run-2").getOrElse(fail("no run-2"))
    // 3,565,000 + 500 and 3,860,000 + 27,000 -- nowhere in code before this
    assertEquals(a.durationTicks, MediaManifest.nn2017.partA.durationTicks)
    assertEquals(b.durationTicks, MediaManifest.nn2017.partB.durationTicks)
    assertEquals(a.playbackEndTicks + a.uncoveredTailTicks, a.durationTicks)
  }

  test("the record yields REAL ClockRepairs -- core's mapping vocabulary gets its first caller") {
    val ticks = MediaManifest.nn2017.partA.ticksPerSecond
    val repairs = TimebaseRepair
      .clockRepairs(record, ticks)
      .fold(e => fail(e.message), identity)
    assertEquals(repairs.size, 2)
    val first = repairs.head
    val seconds = ExactRational.of(10L, 1L).fold(e => fail(e.message), identity)
    val projected = ClockRepair
      .projectRunLocalSeconds(
        seconds,
        first.relation.sourceAxis,
        first.relation.targetAxis,
        first
      )
      .fold(e => fail(e.message), identity)
    // 10 s at 2500 ticks/s is 25,000 ticks, by the record's own formula
    assertEquals(projected, ExactRational.of(10L * ticks, 1L).toOption.get)
  }

  test("a repair refuses axes it does not bind, which is what makes it a DECLARED map") {
    val repairs = TimebaseRepair
      .clockRepairs(record, MediaManifest.nn2017.partA.ticksPerSecond)
      .fold(e => fail(e.message), identity)
    val a = repairs(0)
    val b = repairs(1)
    val seconds = ExactRational.of(1L, 1L).toOption.get
    assert(
      ClockRepair
        .projectRunLocalSeconds(seconds, a.relation.sourceAxis, b.relation.targetAxis, a)
        .isLeft
    )
  }

  test("the two runs cover the annotation rows without gap or overlap") {
    val a = record.run("run-1").get
    val b = record.run("run-2").get
    assertEquals(a.firstRow, 1)
    assertEquals(b.firstRow, a.lastRow + 1)
    assertEquals(b.lastRow, record.inputRows)
  }

  /** The gaps found by hunting for a load-bearing value no test covered.
    *
    * Commit 09292e25 claimed "change a value in the JSON and a test goes red" on the strength of
    * two demonstrated values. Mutating the crosswalk section BY JSON PATH found four more that
    * could be changed in silence: partId, axisId, annotationEndSeconds and playbackStartTicks. Each
    * is load-bearing -- partId says which media part a run maps to, and axisId becomes the target
    * axis of a real ClockRepair -- so a wrong value produces a confidently wrong mapping.
    */
  test("each run maps to its OWN media part, and the two parts differ") {
    val a = record.run("run-1").getOrElse(fail("no run-1"))
    val b = record.run("run-2").getOrElse(fail("no run-2"))
    assertEquals(a.partId, "media-part-a")
    assertEquals(b.partId, "media-part-b")
    assertNotEquals(a.partId, b.partId)
  }

  test("each run's axis id is the part's own playback axis, and becomes the repair's target") {
    val a = record.run("run-1").getOrElse(fail("no run-1"))
    val b = record.run("run-2").getOrElse(fail("no run-2"))
    assertEquals(a.axisId, "media-part-a-playback-ticks")
    assertEquals(b.axisId, "media-part-b-playback-ticks")
    // the declared axis id is what the ClockRepair actually binds, not decoration
    val repairs = TimebaseRepair
      .clockRepairs(record, MediaManifest.nn2017.partA.ticksPerSecond)
      .fold(e => fail(e.message), identity)
    assertEquals(repairs(0).relation.targetAxis, PresentationAxisId.unsafe(a.axisId))
    assertEquals(repairs(1).relation.targetAxis, PresentationAxisId.unsafe(b.axisId))
  }

  test("each run's annotation extent matches the annotation rows it covers") {
    val a = record.run("run-1").getOrElse(fail("no run-1"))
    val b = record.run("run-2").getOrElse(fail("no run-2"))
    // measured: run 1 ends at 1426 s and run 2 at 1544 s on their own run-local clocks
    assertEquals(a.annotationEndSeconds, 1426L)
    assertEquals(b.annotationEndSeconds, 1544L)
    // and the annotation end is consistent with the playback end at the declared tick rate
    val ticks = MediaManifest.nn2017.partA.ticksPerSecond
    assertEquals(a.annotationEndSeconds * ticks, a.playbackEndTicks)
    assertEquals(b.annotationEndSeconds * ticks, b.playbackEndTicks)
  }

  test("both runs start at the origin, which is what makes the repair an identity") {
    record.runs.foreach { r =>
      assertEquals(r.playbackStartTicks, 0L, s"${r.runId} does not start at the origin")
    }
  }

  test("offset is zero for both runs, because each maps to its own part's origin") {
    // the record declares annotationStartSeconds 0 and playbackStartTicks 0 for each run: an
    // offset would only be needed to map both onto ONE continuous timeline, which is the repaired
    // notebook clock this record explicitly refuses for media
    record.runs.foreach { r =>
      assertEquals(r.playbackStartTicks, 0L, s"${r.runId}")
    }
    val repairs = TimebaseRepair
      .clockRepairs(record, MediaManifest.nn2017.partA.ticksPerSecond)
      .fold(e => fail(e.message), identity)
    // a zero offset means second 0 of a run is tick 0 of its part
    val zero = ExactRational.of(0L, 1L).toOption.get
    repairs.foreach { rep =>
      val projected = ClockRepair
        .projectRunLocalSeconds(zero, rep.relation.sourceAxis, rep.relation.targetAxis, rep)
        .fold(e => fail(e.message), identity)
      assertEquals(projected, zero)
    }
  }

  test("the two runs get DISTINCT source axes, because run-local seconds are two spaces") {
    // run-1 second 100 and run-2 second 100 are different moments. One shared source axis would let
    // it map to two targets, and the axis check in ClockRepair would stop meaning anything.
    val repairs = TimebaseRepair
      .clockRepairs(record, MediaManifest.nn2017.partA.ticksPerSecond)
      .fold(e => fail(e.message), identity)
    assertNotEquals(repairs(0).relation.sourceAxis, repairs(1).relation.sourceAxis)
    assertNotEquals(repairs(0).relation.targetAxis, repairs(1).relation.targetAxis)
    // and crossing them is refused, which is only possible BECAUSE they are distinct
    val s = ExactRational.of(100L, 1L).toOption.get
    assert(
      ClockRepair
        .projectRunLocalSeconds(
          s,
          repairs(1).relation.sourceAxis,
          repairs(0).relation.targetAxis,
          repairs(0)
        )
        .isLeft
    )
  }
