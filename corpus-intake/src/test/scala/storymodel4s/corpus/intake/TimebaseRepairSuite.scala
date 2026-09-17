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
