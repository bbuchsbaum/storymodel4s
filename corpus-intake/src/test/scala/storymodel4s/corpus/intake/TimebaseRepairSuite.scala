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

  /** The receipt must QUOTE the record, not paraphrase it from memory.
    *
    * `clockRepairs` used to stamp a Scala literal id (`annotation-raw-to-part-playback-v1`) and a
    * formula string it BUILT ITSELF into every `SourceDerivationReceipt`, while the record's own
    * `id` and `formula` were parsed into nothing. Two consequences, both found by cold review: a v2
    * crosswalk record would emit receipts naming a v1 derivation, and the record's formula could be
    * changed to say anything at all -- `playbackTicks = rawAnnotationSeconds / 99` -- while the
    * emitted receipt went on asserting the multiplication the code happened to implement. A receipt
    * that contradicts its own source is worse than no receipt.
    *
    * Parsing them was not enough to make them load-bearing: the values flowed into the receipt and
    * nothing looked. This is the test that looks.
    */
  test("every emitted receipt quotes the record's own crosswalk id and formula") {
    val rec = record
    val repairs = TimebaseRepair
      .clockRepairs(rec, 2500L)
      .fold(e => fail(s"repairs must build: ${e.message}"), identity)

    assertEquals(repairs.size, rec.runs.size)
    repairs.zip(rec.runs).foreach { (repair, run) =>
      assertEquals(
        repair.receipt.algorithm,
        rec.crosswalkId,
        "the receipt names a derivation the record does not declare"
      )
      assertEquals(
        repair.receipt.parameters,
        run.formula,
        s"${run.runId}: the receipt asserts a formula its own source contradicts"
      )
      assertEquals(repair.receipt.inputChecksums, Vector(rec.annotationSha256))
    }
  }

  /** The offset is derived from the record, not asserted to be zero.
    *
    * `playbackStartTicks` was parsed and then never read: the repair passed `ExactRational.Zero`
    * regardless. It comes out zero for this record, which is what the prose argues, but a record
    * declaring otherwise was silently overridden rather than honoured or refused.
    */
  test("the repair offset comes from the record's declared starts") {
    val rec = record
    val repairs = TimebaseRepair.clockRepairs(rec, 2500L).fold(e => fail(e.message), identity)
    rec.runs.foreach { r =>
      assertEquals(r.annotationStartSeconds, 0L, "the zero-offset argument assumes this")
      assertEquals(r.playbackStartTicks, 0L, "and this")
    }
    repairs.foreach(rep => assertEquals(rep.offset, storymodel4s.core.ExactRational.Zero))
  }

  /** The record declares the mapping SHAPE, and only one shape is implemented. */
  test("a record declaring a non-identity mapping is REFUSED, not handed an identity repair") {
    val affine = source.replaceFirst("\"mapping\": \"identity\"", "\"mapping\": \"affine\"")
    assertNotEquals(affine, source, "the mutation must actually apply")
    val rec = TimebaseRepair.parse(affine).fold(r => fail(r.message), identity)
    TimebaseRepair.clockRepairs(rec, 2500L) match
      case Left(e)  => assert(e.message.contains("affine"), e.message)
      case Right(_) =>
        fail("a declared affine mapping was silently given an identity repair")
  }
