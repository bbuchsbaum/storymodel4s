package storymodel4s.corpus.intake

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.RecallTiming

class ScannerCrosswalkSuite extends FunSuite:
  import ScannerCrosswalk.*
  private val key = RunKey("synthetic", "v1", "p1", None, "recall", "1")
  private val image = Checksum.ofText("synthetic-image")
  private val header = Checksum.ofText("synthetic-header")
  private val receipt = Checksum.ofText("declared-crop")
  private def rational(n: Long, d: Long = 1L): ExactRational = ExactRational.of(n, d).toOption.get
  private def run(k: RunKey = key): Run = Run
    .regular(k, image, header, 8, rational(3, 2), Origin.FirstStoredSample, AppliedHistory.Unknown)
    .toOption
    .get
  private def reference(name: String = "clock"): Reference = Reference.recall(
    RecallTiming.Clock.declared(
      Checksum.ofText(name),
      RecallTiming.ClockKey.unsafe("openneuro-word-onset-seconds"),
      Checksum.ofText("columns"),
      RecallTiming.RecordingIdentity.Unestablished,
      RecallTiming.Origin.Unestablished
    )
  )
  private def binding(
      ref: Reference,
      r: Run,
      scale: ExactRational = rational(3, 2),
      offset: ExactRational = rational(-2),
      start: ExactRational = rational(0),
      end: ExactRational = rational(20)
  ): Binding =
    Binding
      .declared(
        ref,
        r,
        Window.of(start, end).toOption.get,
        scale,
        offset,
        Evidence(ref.digest, r.digest, receipt, Vector(header))
      )
      .toOption
      .get

  test("crop padding drop and censor preserve original stored time and explicit missingness") {
    val r = run()
    val slots = Vector(
      Slot.Padding("leading"),
      Slot.Padding("leading"),
      Slot.Acquired(2, Censoring.Included),
      Slot.Acquired(4, Censoring.Censored(receipt)),
      Slot.Acquired(7, Censoring.Unknown)
    )
    val layout =
      Layout.declared(r, Checksum.ofText("analysis-array"), 5, slots, receipt).toOption.get
    assertEquals(layout.sample(0), Right(None))
    assertEquals(layout.sample(2).toOption.get.get.seconds, rational(3))
    assertEquals(layout.sample(3).toOption.get.get.seconds, rational(6))
    assertEquals(layout.sample(4).toOption.get.get.seconds, rational(21, 2))
    assertEquals(layout.droppedStoredIndices, Vector(0, 1, 3, 5, 6))
    assertEquals(layout.slots(3), Slot.Acquired(4, Censoring.Censored(receipt)))
    assertEquals(layout.sample(5), Left(Refusal.InvalidIndex))
  }
  test("inventory and layout reject inconsistent lengths ambiguous origins and invalid indices") {
    val r = run()
    assert(
      Run
        .declared(
          key,
          image,
          header,
          7,
          r.sampleSeconds,
          Origin.FirstStoredSample,
          AppliedHistory.Unknown
        )
        .isLeft
    )
    assert(
      Run
        .declared(
          key,
          image,
          header,
          2,
          Vector(rational(1), rational(2)),
          Origin.FirstStoredSample,
          AppliedHistory.Unknown
        )
        .isLeft
    )
    assert(
      Run
        .declared(
          key,
          image,
          header,
          2,
          Vector(rational(0), rational(0)),
          Origin.FirstStoredSample,
          AppliedHistory.Unknown
        )
        .isLeft
    )
    assert(
      Run
        .regular(
          key,
          image,
          header,
          8,
          rational(0),
          Origin.FirstStoredSample,
          AppliedHistory.Unknown
        )
        .isLeft
    )
    Vector(Vector(2, 2), Vector(3, 2), Vector(-1), Vector(8)).foreach { ids =>
      assert(
        Layout
          .declared(r, image, ids.size, ids.map(Slot.Acquired(_, Censoring.Included)), receipt)
          .isLeft
      )
    }
    assert(Layout.declared(r, image, 2, Vector(Slot.Padding(" ")), receipt).isLeft)
    assert(Layout.declared(r, image, 1, Vector(Slot.Padding(" ")), receipt).isLeft)
  }
  test("clock and run identities bind the join even when all numeric times agree") {
    val ref = reference()
    val r = run()
    val b = binding(ref, r)
    assertEquals(
      b.project(reference("different-column-artifact").at(rational(3))),
      Left(Refusal.ForeignClock)
    )
    Vector(
      key.copy(participant = "p2"),
      key.copy(run = "2"),
      key.copy(revision = "v2"),
      key.copy(session = Some("s1"))
    ).foreach { k =>
      val other = run(k)
      assertNotEquals(other.digest, r.digest)
      assertEquals(b.inverse(other.at(rational(1))), Left(Refusal.ForeignClock))
      assertEquals(
        Binding.declared(ref, other, b.domain, b.repair.scale, b.repair.offset, b.evidence),
        Left(Refusal.BindingMismatch)
      )
    }
    val wrongHeader = Run
      .declared(
        key,
        image,
        Checksum.ofText("different-header"),
        8,
        r.sampleSeconds,
        r.origin,
        r.history
      )
      .toOption
      .get
    assertNotEquals(wrongHeader.digest, r.digest)
    val knownHistory = Run
      .declared(
        key,
        image,
        header,
        8,
        r.sampleSeconds,
        r.origin,
        AppliedHistory.Declared(Vector.empty)
      )
      .toOption
      .get
    assertNotEquals(knownHistory.digest, r.digest)
  }
  test("negative offsets nonunit scales inverse landmarks and half-open validity are exact") {
    val ref = reference()
    val r = run()
    val b = binding(ref, r)
    assertEquals(b.project(ref.at(rational(0))).toOption.get.seconds, rational(-2))
    assertEquals(b.project(ref.at(rational(4))).toOption.get.seconds, rational(4))
    assertEquals(b.inverse(r.at(rational(4))).toOption.get.seconds, rational(4))
    assertEquals(b.project(ref.at(rational(20))), Left(Refusal.OutOfDomain))
    assertEquals(b.inverse(r.at(rational(28))), Left(Refusal.OutOfDomain))
    assertEquals(b.inverse(r.at(rational(-3))), Left(Refusal.OutOfDomain))
    assertEquals(b.project(ref.at(rational(-1))), Left(Refusal.OutOfDomain))
  }
  test(
    "reduction precedes representability checks including Long Min and ticks beyond 2 power 53"
  ) {
    val ref = reference()
    val r = run()
    val big = 9007199254740993L
    val b = binding(ref, r, rational(2), rational(-big), rational(big), rational(big + 10))
    assertEquals(b.project(ref.at(rational(big))).toOption.get.seconds, rational(big))
    assertEquals(b.inverse(r.at(rational(big + 2))).toOption.get.seconds, rational(big + 1))
    val cancellation = binding(
      ref,
      r,
      rational(2),
      rational(Long.MinValue),
      rational(Long.MaxValue - 2),
      rational(Long.MaxValue)
    )
    assertEquals(
      cancellation.project(ref.at(rational(Long.MaxValue - 1))).toOption.get.seconds,
      rational(Long.MaxValue - 3)
    )
    val half = binding(ref, r, rational(Long.MaxValue, 2), rational(0), rational(0), rational(2))
    assertEquals(half.project(ref.at(rational(1))).toOption.get.seconds, rational(Long.MaxValue, 2))
    assertEquals(half.inverse(r.at(rational(Long.MaxValue, 2))).toOption.get.seconds, rational(1))
  }
  test("missing evidence and invalid scale cannot produce a declared crosswalk") {
    val ref = reference()
    val r = run()
    val b = binding(ref, r)
    assertEquals(
      Binding.declared(
        ref,
        r,
        b.domain,
        rational(1),
        rational(0),
        b.evidence.copy(supportingArtifacts = Vector.empty)
      ),
      Left(Refusal.MissingEvidence)
    )
    assertEquals(
      Binding.declared(ref, r, b.domain, rational(-1), rational(0), b.evidence),
      Left(Refusal.InvalidScale)
    )
    assertEquals(Window.of(rational(3), rational(3)), Left(Refusal.InvalidDomain))
  }
  test("malformed package-internal rationals cannot establish positive clocks or ordered samples") {
    val ref = reference()
    val r = run()
    val b = binding(ref, r)
    Vector(1L -> -1L, 1L -> 0L, 2L -> 2L, 0L -> 2L).foreach { (n, d) =>
      val malformed = ScannerRationalForgery.make(n, d)
      assertEquals(
        Binding.declared(ref, r, b.domain, malformed, rational(0), b.evidence),
        Left(Refusal.InvalidRational)
      )
      assertEquals(
        Binding.declared(ref, r, b.domain, rational(1), malformed, b.evidence),
        Left(Refusal.InvalidRational)
      )
      assertEquals(Window.of(malformed, rational(20)), Left(Refusal.InvalidRational))
      assertEquals(
        Run.declared(
          key,
          image,
          header,
          1,
          Vector(malformed),
          Origin.Declared(receipt),
          AppliedHistory.Unknown
        ),
        Left(Refusal.InvalidRational)
      )
      assertEquals(
        Run.regular(
          key,
          image,
          header,
          1,
          malformed,
          Origin.FirstStoredSample,
          AppliedHistory.Unknown
        ),
        Left(Refusal.InvalidRational)
      )
      assertEquals(b.project(ref.at(malformed)), Left(Refusal.InvalidRational))
      assertEquals(b.inverse(r.at(malformed)), Left(Refusal.InvalidRational))
    }
  }
  test("media timebase is exact and an occurrence declaration cannot extend into a scan break") {
    val part = SourceBundle
      .filmEdition(
        EditionId.unsafe("part"),
        Checksum.ofText("media"),
        0L,
        40L,
        RationalTimebase.of(1L, 10L).toOption.get
      )
      .toOption
      .get
    val ref = Reference.mediaPart(part).toOption.get
    assertEquals(ref.tick(3).toOption.get.seconds, rational(3, 10))
    assertEquals(ref.tick(40), Left(Refusal.OutOfDomain))
    val mediaRun = run()
    assertEquals(
      Binding.declared(
        ref,
        mediaRun,
        Window.of(rational(100), rational(101)).toOption.get,
        rational(1),
        rational(0),
        Evidence(ref.digest, mediaRun.digest, receipt, Vector(header))
      ),
      Left(Refusal.InvalidDomain)
    )
    val axis = SourceBundle
      .editionPlaybackAxis(
        EditionId.unsafe("composition"),
        part.streams,
        part.authorityTracks,
        0L,
        80L,
        RationalTimebase.of(1L, 10L).toOption.get
      )
      .toOption
      .get
    val segment = CompositionSegment
      .of(
        PlaybackInterval.on(part.primaryAxis, 0L, 40L).toOption.get,
        PlaybackInterval.on(axis, 40L, 80L).toOption.get,
        OccurrenceId.unsafe("second")
      )
      .toOption
      .get
    val transform = TrackComposition
      .of(
        part.primaryAxis.id,
        axis.id,
        Vector(segment),
        SourceDerivationReceipt
          .of("synthetic/v1", "second part", Vector(part.identity))
          .toOption
          .get
      )
      .toOption
      .get
    val composed = SourceBundle
      .of(
        axis.edition,
        SourceKind.FilmEdition,
        part.streams,
        axis,
        part.authorityTracks,
        Vector(transform)
      )
      .toOption
      .get
    val occurrence =
      Reference.mediaOccurrence(composed, transform.identity, segment.occurrence).toOption.get
    val r = run(key.copy(task = "movie", run = "2"))
    val evidence = Evidence(occurrence.digest, r.digest, receipt, Vector(header))
    val valid = Binding
      .declared(
        occurrence,
        r,
        Window.of(rational(4), rational(8)).toOption.get,
        rational(1),
        rational(-4),
        evidence
      )
      .toOption
      .get
    assertEquals(valid.project(occurrence.at(rational(4))).toOption.get.seconds, rational(0))
    val whole = valid.mediaWindow(r, Window.of(rational(0), rational(4)).toOption.get).toOption.get
    assertEquals(whole.interval.start, 40L)
    assertEquals(whole.interval.endExclusive, 80L)
    assertEquals(whole.axis, axis)
    assertEquals(
      valid.mediaWindow(r, Window.of(rational(0), rational(1, 3)).toOption.get),
      Left(Refusal.InexactTick)
    )
    assertEquals(
      valid.inverseWindow(r, Window.of(rational(-1), rational(1)).toOption.get),
      Left(Refusal.OutOfDomain)
    )
    assertEquals(
      valid.inverseWindow(run(), Window.of(rational(0), rational(1)).toOption.get),
      Left(Refusal.ForeignClock)
    )
    assertEquals(
      Binding.declared(
        occurrence,
        r,
        Window.of(rational(0), rational(8)).toOption.get,
        rational(1),
        rational(0),
        evidence
      ),
      Left(Refusal.InvalidDomain)
    )
    assertNotEquals(r.digest, run().digest)
  }
  test("a native-to-native composition cannot borrow the primary playback timebase") {
    val a = SourceBundle
      .filmEdition(
        EditionId.unsafe("a"),
        Checksum.ofText("a"),
        0L,
        40L,
        RationalTimebase.of(1L, 10L).toOption.get
      )
      .toOption
      .get
    val b = SourceBundle
      .filmEdition(
        EditionId.unsafe("b"),
        Checksum.ofText("b"),
        0L,
        40L,
        RationalTimebase.of(1L, 20L).toOption.get
      )
      .toOption
      .get
    val streams = a.streams ++ b.streams
    val axis = SourceBundle
      .editionPlaybackAxis(
        EditionId.unsafe("c"),
        streams,
        streams.map(_.id),
        0L,
        80L,
        RationalTimebase.of(1L, 30L).toOption.get
      )
      .toOption
      .get
    val occurrence = OccurrenceId.unsafe("native-only")
    val segment = CompositionSegment
      .of(
        PlaybackInterval.on(a.primaryAxis, 0L, 20L).toOption.get,
        PlaybackInterval.on(b.primaryAxis, 0L, 20L).toOption.get,
        occurrence
      )
      .toOption
      .get
    val transform = TrackComposition
      .of(
        a.primaryAxis.id,
        b.primaryAxis.id,
        Vector(segment),
        SourceDerivationReceipt
          .of("native/v1", "native only", Vector(a.identity, b.identity))
          .toOption
          .get
      )
      .toOption
      .get
    val bundle = SourceBundle
      .of(axis.edition, SourceKind.FilmEdition, streams, axis, streams.map(_.id), Vector(transform))
      .toOption
      .get
    assertEquals(
      Reference.mediaOccurrence(bundle, transform.identity, occurrence),
      Left(Refusal.UnknownOccurrence)
    )
  }
