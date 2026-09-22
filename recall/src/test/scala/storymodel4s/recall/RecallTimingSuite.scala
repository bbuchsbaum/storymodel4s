package storymodel4s.recall

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.RecallTiming.*

class RecallTimingSuite extends FunSuite:
  private val source = StorySource.fromText("one gap 😀 two three").toOption.get
  private val text = source.canonicalText
  private val words =
    "\\S+".r.findAllMatchIn(text).map(m => TextSpan.unsafe(m.start, m.end)).toVector
  private val unit = RecallUnit(
    RecallUnitId.unsafe("u"),
    0,
    SpanSet.unsafe(SpanRef(words(0)), SpanRef(TextSpan.unsafe(words(2).start, text.length))),
    text,
    DiscourseFunction.EpisodicAssertion,
    ExpressedUncertainty.Unmarked,
    PropositionSketch.empty,
    None
  )
  private val graph = RecallGraph
    .validated(source, SurfaceAnalyzer.analyze(source), Vector(unit), RecallRelations.empty)
    .toOption
    .get
  private val inventory = RecallInventory
    .of(graph, words, WordIdPolicy.inputArtifact(Checksum.ofText("parser")))
    .toOption
    .get
  private val clock = Clock.declared(
    Checksum.ofText("clock.csv"),
    ClockKey.unsafe("raw-bids-onset"),
    Checksum.ofText("column descriptor"),
    RecordingIdentity.Unestablished,
    Origin.Unestablished
  )
  private val evidence = Checksum.ofText("source")
  private val reported = Basis.SourceReported(evidence)
  private val provenance =
    RecallTiming.Provenance(Checksum.ofText("correspondence"), RecordingLink.NotEstablished)
  private def time(n: Long): ExactRational = ExactRational.integer(n)
  private def onset(n: Long): Observation = Observation.OnsetOnly(time(n), reported)
  private def entries(values: Vector[Observation]): Vector[Entry] =
    inventory.words.zip(values).map((w, v) => Entry(w.id, clock, v))
  private val observations = Vector(
    Observation.Missing(MissingReason.BlankSourceCell),
    onset(999),
    onset(10),
    onset(9),
    onset(9)
  )
  private def checked(es: Vector[Entry]): Either[Refusal, RecallTiming] =
    RecallTiming.checked(inventory, clock, es, provenance)
  private def value = checked(entries(observations)).toOption.get

  test("exact decimal conversion reduces before range checks") {
    val examples = Vector(
      "9007199254740993" -> (9007199254740993L, 1L),
      "-9223372036854775808" -> (Long.MinValue, 1L),
      "1.0000000000000000000" -> (1L, 1L),
      "+8.750e0" -> (35L, 4L),
      "-1.25e-2" -> (-1L, 80L),
      "-0" -> (0L, 1L),
      ".1" -> (1L, 10L),
      "1." -> (1L, 1L),
      "1.e2" -> (100L, 1L),
      "-.25E+2" -> (-25L, 1L),
      "+.0" -> (0L, 1L),
      "0e128" -> (0L, 1L),
      "-0e-128" -> (0L, 1L)
    )
    examples.foreach { (s, expected) =>
      val result = decimalSeconds(s).toOption.get
      assertEquals((result.numerator, result.denominator), expected)
    }
    Vector("9223372036854775808", "0.0000000000000000001", "1e128").foreach { s =>
      assertEquals(decimalSeconds(s), Left(DecimalRefusal.Unrepresentable))
    }
  }
  test("bounded decimal intake refuses nonfinite malformed and excessive text") {
    Vector("NaN", "Infinity", "", " 1", "1 ", "1/2", ".", ".e2", "--1", "1e").foreach { s =>
      assertEquals(decimalSeconds(s), Left(DecimalRefusal.Malformed))
    }
    Vector("1e129", "1e-129", "1" * 129, "0" * 513).foreach { s =>
      assertEquals(decimalSeconds(s), Left(DecimalRefusal.ResourceLimit))
    }
  }
  test("complete accounting includes unassigned words and canonicalizes input order") {
    assertEquals(value.entries.size, 5)
    assertEquals(value.entries(1).observation, onset(999))
    assertEquals(value.unitSummaries.head.totalWords, 4)
    assertEquals(checked(entries(observations).reverse).toOption.get.digest, value.digest)
    assertEquals(checked(entries(observations).reverse).toOption.get.entries, value.entries)
    val es = entries(observations)
    assertEquals(checked(es :+ es.head), Left(Refusal.DuplicateWord(es.head.word)))
    assertEquals(checked(es.drop(1)), Left(Refusal.MissingWord(es.head.word)))
    val foreign = es.head.copy(word = RecallWordId.unsafe("foreign"))
    assertEquals(checked(es :+ foreign), Left(Refusal.ForeignWord(foreign.word)))
  }
  test("foreign clocks refuse even when the observation is missing") {
    val foreign = Clock.declared(
      clock.artifact,
      clock.key,
      clock.descriptor,
      clock.recording,
      Origin.RecordingStart
    )
    val es = entries(observations)
    assertEquals(
      checked(es.updated(0, es.head.copy(clock = foreign))),
      Left(Refusal.ForeignClock(es.head.word))
    )
    assertNotEquals(clock.digest, foreign.digest)
  }
  test("first available onset is not a missing boundary; gaps never bridge adjacency") {
    val s = value.unitSummaries.head
    assertEquals(s.availableOnsets, 3)
    assertEquals(s.suppliedIntervals, 0)
    assertEquals(s.firstMember.get.observation, observations.head)
    assertEquals(s.firstAvailableOnset.get.word, inventory.words(2).id)
    assertEquals(s.firstAvailableOnset.get.seconds, time(10))
    assertEquals(s.lastAvailableOnset.get.seconds, time(9))
    assertEquals(s.comparableAdjacentPairs, 2)
    assertEquals(s.unobservedAdjacentPairs, 1)
    assertEquals(
      s.backwardPairs,
      Vector(BackwardPair(inventory.words(2).id, inventory.words(3).id))
    )
    assertEquals(s.equalAdjacentPairs, 1)
    val gap = value.entries.updated(
      3,
      value.entries(3).copy(observation = Observation.Missing(MissingReason.NotProvided))
    )
    val missing = checked(gap).toOption.get.unitSummaries.head
    assertEquals(missing.comparableAdjacentPairs, 0)
    assertEquals(missing.backwardPairs, Vector.empty)
    assertEquals(missing.unobservedAdjacentPairs, 3)
  }
  test("intervals require positive exact width; overlaps and backward order remain observations") {
    val intervals = Vector(
      Observation.Interval(time(Long.MinValue), time(0), reported),
      onset(999),
      Observation.Interval(time(-10), time(10), Basis.Estimated(evidence)),
      Observation.Interval(time(-11), time(11), reported),
      onset(9007199254740993L)
    )
    val result = checked(entries(intervals)).toOption.get
    assertEquals(result.unitSummaries.head.suppliedIntervals, 3)
    assertEquals(result.unitSummaries.head.estimatedWords, 1)
    assertEquals(result.unitSummaries.head.backwardPairs.size, 1)
    Vector((10L, 10L), (11L, 10L), (9007199254740993L, 9007199254740992L)).foreach { (a, b) =>
      val invalid = intervals.updated(0, Observation.Interval(time(a), time(b), reported))
      assertEquals(
        checked(entries(invalid)),
        Left(Refusal.NonPositiveInterval(inventory.words.head.id))
      )
    }
    val fractional = intervals.updated(
      0,
      Observation.Interval(
        ExactRational.of(Long.MaxValue - 1, Long.MaxValue).toOption.get,
        ExactRational.of(Long.MaxValue, Long.MaxValue - 1).toOption.get,
        reported
      )
    )
    assert(checked(entries(fractional)).isRight)
  }
  test("missing zero estimated and source-reported remain distinguishable") {
    val variations = Vector(
      Observation.Missing(MissingReason.NotProvided),
      Observation.Missing(MissingReason.BlankSourceCell),
      onset(0),
      Observation.OnsetOnly(time(0), Basis.Estimated(evidence))
    )
    val digests =
      variations.map(o => checked(entries(observations.updated(0, o))).toOption.get.digest)
    assertEquals(digests.distinct.size, 4)
    val declared = RecallTiming
      .checked(
        inventory,
        clock,
        entries(observations),
        provenance.copy(recordingLink = RecordingLink.Declared(evidence))
      )
      .toOption
      .get
    assertNotEquals(declared.digest, value.digest)
  }
  test("all-missing and wordless units have no onset or interval authority") {
    val missing = checked(
      entries(Vector.fill(5)(Observation.Missing(MissingReason.NotProvided)))
    ).toOption.get.unitSummaries.head
    assertEquals(missing.availableOnsets, 0)
    assertEquals(missing.firstAvailableOnset, None)
    assertEquals(missing.lastAvailableOnset, None)
    val empty = RecallInventory.of(graph, Vector.empty, inventory.idPolicy).toOption.get
    val s =
      RecallTiming.checked(empty, clock, Vector.empty, provenance).toOption.get.unitSummaries.head
    assertEquals(s.totalWords, 0)
    assertEquals(s.firstMember, None)
    assertEquals(s.unobservedAdjacentPairs, 0)
  }
  test("clock declarations bind every field and preserve unusual UTF-16 keys") {
    val keys = Vector(0xd800, 0xd801).map(c => ClockKey.unsafe(c.toChar.toString))
    val clocks = keys.map(k =>
      Clock.declared(clock.artifact, k, clock.descriptor, clock.recording, clock.origin)
    )
    assertNotEquals(clocks.head.digest, clocks.last.digest)
    val variants = Vector(
      Clock.declared(evidence, clock.key, clock.descriptor, clock.recording, clock.origin),
      Clock.declared(clock.artifact, clock.key, evidence, clock.recording, clock.origin),
      Clock.declared(
        clock.artifact,
        clock.key,
        clock.descriptor,
        RecordingIdentity.Declared(evidence),
        clock.origin
      ),
      Clock.declared(
        clock.artifact,
        clock.key,
        clock.descriptor,
        clock.recording,
        Origin.Declared(evidence)
      )
    )
    variants.foreach(c => assertNotEquals(c.digest, clock.digest))
  }

  test("checked timing refuses malformed legacy rationals before comparison") {
    val malformed = Vector((1L, 0L), (1L, -2L), (2L, 2L), (0L, 2L))
      .map((n, d) => RecallTimingRationalForgery.make(n, d))
    malformed.foreach { q =>
      Vector(
        Observation.OnsetOnly(q, reported),
        Observation.Interval(q, time(10), reported),
        Observation.Interval(time(-10), q, reported)
      ).foreach { o =>
        assertEquals(
          checked(entries(observations.updated(0, o))),
          Left(Refusal.InvalidRational(inventory.words.head.id))
        )
      }
    }
  }
