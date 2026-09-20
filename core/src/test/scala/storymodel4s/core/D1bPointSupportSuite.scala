package storymodel4s.core

import munit.FunSuite

class D1bPointSupportSuite extends FunSuite:
  private def right[A](value: Either[DomainError, A]): A =
    value.fold(e => fail(e.message), identity)
  private val bundle = right(
    SourceBundle.filmEdition(
      EditionId.unsafe("point-film"),
      Checksum.ofText("point-picture"),
      0L,
      100L,
      RationalTimebase.Millisecond
    )
  )
  private val axis = bundle.primaryAxis
  private val stream = bundle.streams.head.id
  private def point(tick: Long) = right(PlaybackInstant.on(axis, tick))
  private def interval(start: Long, end: Long) = right(PlaybackInterval.on(axis, start, end))
  private def support(anchors: EvidenceAnchor*) = right(
    EvidenceSupport.of(bundle, anchors.toVector)
  )
  private def anchor(tick: Long) = EvidenceAnchor.MediaPoint(bundle.id, stream, point(tick))

  test("accepting control: exact native point survives support and complete projection"):
    val value = support(anchor(25L))
    assertEquals(value.anchors.toVector, Vector(anchor(25L)))
    val projection = right(value.playbackOn(axis.id))
    assertEquals(projection.intervals, Vector.empty)
    assertEquals(projection.points, Vector(point(25L)))
    assertEquals(projection.bounds, (25L, 25L))
    assertEquals(
      PrimaryProjection.on(bundle, TypedSupport.Anchored(value)),
      Right(PrimaryProjection.Playback(axis.id, projection))
    )

  test("mixed support retains interval gaps and explicit inside-interval points"):
    val value = support(
      EvidenceAnchor.MediaTime(
        bundle.id,
        stream,
        axis.id,
        right(PlaybackIntervalSet.of(Vector(interval(10L, 20L), interval(30L, 40L))))
      ),
      anchor(15L),
      anchor(25L),
      anchor(25L)
    )
    val projection = right(value.playbackOn(axis.id))
    assertEquals(
      projection.intervals.map(i => (i.start, i.endExclusive)),
      Vector((10L, 20L), (30L, 40L))
    )
    assertEquals(projection.points.map(_.at), Vector(15L, 25L))
    assertEquals(
      right(PlaybackSupport.of(axis.id, projection.intervals.reverse, projection.points.reverse)),
      projection
    )
    assertEquals(
      TypedSupport.Anchored(value).identity,
      TypedSupport.Anchored(support(value.anchors.toVector.reverse*)).identity
    )

  test("complete support requires a member and one common axis"):
    val foreign = right(
      SourceBundle.filmEdition(
        EditionId.unsafe("foreign-point"),
        Checksum.ofText("foreign"),
        0L,
        100L,
        RationalTimebase.Millisecond
      )
    )
    val at = right(PlaybackInstant.on(foreign.primaryAxis, 25L))
    assert(PlaybackSupport.of(axis.id, Vector.empty, Vector.empty).isLeft)
    assert(PlaybackSupport.of(axis.id, Vector.empty, Vector(at)).isLeft)
    assert(
      PlaybackSupport
        .of(
          axis.id,
          Vector(right(PlaybackInterval.on(foreign.primaryAxis, 1L, 2L))),
          Vector(point(25L))
        )
        .isLeft
    )
    assert(support(anchor(25L)).playbackOn(foreign.primaryAxis.id).isLeft)

  test("point anchors refuse foreign bundle, stream, axis and excluded native end"):
    val foreign = right(
      SourceBundle.filmEdition(
        EditionId.unsafe("foreign-point"),
        Checksum.ofText("foreign"),
        0L,
        101L,
        RationalTimebase.Millisecond
      )
    )
    val wrong = Vector(
      EvidenceAnchor.MediaPoint(foreign.id, stream, point(25L)),
      EvidenceAnchor.MediaPoint(bundle.id, StreamId.unsafe("absent"), point(25L)),
      EvidenceAnchor.MediaPoint(
        bundle.id,
        stream,
        right(PlaybackInstant.on(foreign.primaryAxis, 25L))
      )
    )
    wrong.foreach(a => assert(EvidenceSupport.of(bundle, Vector(a)).isLeft))
    assert(PlaybackInstant.on(axis, 100L).isLeft)
    assert(PlaybackInstant.on(axis, 0L).isRight)

  test("point support has no interval-only hull and no invented interval"):
    assert(support(anchor(25L)).intervalsOn(axis.id).isLeft)
    assert(support(anchor(25L)).hullOn(axis.id).isLeft)
    assert(
      support(
        anchor(25L),
        EvidenceAnchor.MediaTime(
          bundle.id,
          stream,
          axis.id,
          PlaybackIntervalSet.one(interval(10L, 20L))
        )
      ).hullOn(axis.id).isLeft
    )

  test("containment distinguishes points, gaps and positive duration"):
    val parent = right(
      PlaybackSupport.of(
        axis.id,
        Vector(interval(10L, 20L), interval(30L, 40L)),
        Vector(point(25L))
      )
    )
    def child(at: Long) = right(PlaybackSupport.of(axis.id, Vector.empty, Vector(point(at))))
    assert(parent.contains(child(10L)))
    assert(parent.contains(child(25L)))
    assert(!parent.contains(child(20L)))
    assert(!parent.contains(child(40L)))
    assert(
      !parent.contains(right(PlaybackSupport.of(axis.id, Vector(interval(25L, 26L)), Vector.empty)))
    )

  test("full bundle binding refuses same-id metadata drift until explicit checked rebuild"):
    val first = bundle.streams.head
    val changed = right(
      SourceStream.of(
        first.id,
        first.kind,
        first.checksum,
        first.nativeAxis,
        first.extent,
        first.timebase,
        Vector(StreamId.unsafe("upstream-receipt"))
      )
    )
    val altered = right(
      SourceBundle.of(
        bundle.edition,
        bundle.sourceKind,
        Vector(changed),
        bundle.primaryAxis,
        bundle.authorityTracks,
        bundle.mappings
      )
    )
    assertEquals(altered.id, bundle.id)
    val original = support(anchor(25L))
    assert(original.checkedOn(altered).isLeft)
    assert(PrimaryProjection.on(altered, TypedSupport.Anchored(original)).isLeft)
    val rebuilt = right(EvidenceSupport.of(altered, original.anchors.toVector))
    assert(rebuilt.checkedOn(altered).isRight)
    assertNotEquals(
      TypedSupport.Anchored(original).identity,
      TypedSupport.Anchored(rebuilt).identity
    )

  test("physical identity distinguishes point tick axis stream and interval shape"):
    val original = TypedSupport.Anchored(support(anchor(25L))).identity
    assertNotEquals(original, TypedSupport.Anchored(support(anchor(26L))).identity)
    val extent = support(
      EvidenceAnchor.MediaTime(
        bundle.id,
        stream,
        axis.id,
        PlaybackIntervalSet.one(interval(25L, 26L))
      )
    )
    assertNotEquals(original, TypedSupport.Anchored(extent).identity)

  private val native = right(
    SourceBundle.filmEdition(
      EditionId.unsafe("native-point"),
      Checksum.ofText("native"),
      0L,
      10L,
      RationalTimebase.Millisecond
    )
  )
  private val receipt = right(SourceDerivationReceipt.of("point-map", "fixture", Vector.empty))
  private def mapped(kind: StreamKind = StreamKind.Audio, nativeEnd: Long = 10L)(
      mappings: (PresentationAxis, SourceStream) => Vector[CheckedMapping]
  ): SourceBundle =
    val secondary = right(
      SourceStream.of(
        StreamId.unsafe("secondary-point"),
        kind,
        Checksum.ofText("secondary"),
        native.primaryAxis.id,
        right(AxisExtent.playbackTicks(0L, nativeEnd, RationalTimebase.Millisecond)),
        Some(RationalTimebase.Millisecond),
        Vector.empty
      )
    )
    val proposed = Vector(bundle.streams.head, secondary)
    val axis = right(
      SourceBundle.editionPlaybackAxis(
        bundle.edition.get,
        proposed,
        bundle.authorityTracks,
        0L,
        100L,
        RationalTimebase.Millisecond
      )
    )
    val first = bundle.streams.head
    val picture = right(
      SourceStream.of(
        first.id,
        first.kind,
        first.checksum,
        axis.id,
        axis.extent,
        axis.timebase,
        Vector.empty
      )
    )
    right(
      SourceBundle.of(
        bundle.edition,
        bundle.sourceKind,
        Vector(picture, secondary),
        axis,
        bundle.authorityTracks,
        mappings(axis, secondary)
      )
    )

  private def mappedPoint(b: SourceBundle, at: Long): Either[DomainError, EvidenceSupport] =
    EvidenceSupport.of(
      b,
      Vector(
        EvidenceAnchor.MediaPoint(
          b.id,
          b.streams(1).id,
          right(PlaybackInstant.on(b.primaryAxis, at))
        )
      )
    )

  test("mapped point obeys exact clock image with excluded end and ambiguous mapping refusal"):
    def repair(axis: PresentationAxis, offset: Long) = right(
      ClockRepair.of(
        native.primaryAxis.id,
        axis.id,
        ExactRational.integer(1000L),
        ExactRational.integer(offset),
        receipt
      )
    )
    val b = mapped()((axis, _) => Vector(repair(axis, 20L)))
    assert(mappedPoint(b, 20L).isRight)
    assert(mappedPoint(b, 29L).isRight)
    assert(mappedPoint(b, 19L).isLeft)
    assert(mappedPoint(b, 30L).isLeft)
    val ambiguous = mapped()((axis, _) => Vector(repair(axis, 20L), repair(axis, 21L)))
    assert(mappedPoint(ambiguous, 25L).isLeft)

  test("composition points preserve gaps, next segment start and source bounds"):
    def composition(axis: PresentationAxis) = right(
      TrackComposition.of(
        native.primaryAxis.id,
        axis.id,
        Vector((0L, 3L, 20L, 23L), (6L, 10L, 26L, 30L)).zipWithIndex.map { case ((a, b, c, d), n) =>
          right(
            CompositionSegment.of(
              right(PlaybackInterval.on(native.primaryAxis, a, b)),
              right(PlaybackInterval.on(axis, c, d)),
              OccurrenceId.unsafe(s"point-occurrence-$n")
            )
          )
        },
        receipt
      )
    )
    val b = mapped()((axis, _) => Vector(composition(axis)))
    assert(mappedPoint(b, 20L).isRight)
    assert(mappedPoint(b, 26L).isRight)
    Vector(23L, 25L, 30L).foreach(tick => assert(mappedPoint(b, tick).isLeft))
    val truncated = mapped(nativeEnd = 3L)((axis, _) => Vector(composition(axis)))
    assert(mappedPoint(truncated, 20L).isLeft)

  test("point membership independently checks selected stream extent and kind"):
    def nativePoint(b: SourceBundle, tick: Long) = EvidenceSupport.of(
      b,
      Vector(
        EvidenceAnchor.MediaPoint(
          b.id,
          b.streams(1).id,
          right(PlaybackInstant.on(native.primaryAxis, tick))
        )
      )
    )
    val b = mapped(nativeEnd = 3L)((_, _) => Vector.empty)
    assert(nativePoint(b, 0L).isRight)
    assert(nativePoint(b, 2L).isRight)
    assert(nativePoint(b, 3L).isLeft)
    val wrongKind = mapped(kind = StreamKind.CanonicalText)((_, _) => Vector.empty)
    assert(nativePoint(wrongKind, 2L).isLeft)

  test("point identity independently binds the selected stream and axis within one bundle") {
    val b = mapped()((axis, _) =>
      Vector(
        right(
          ClockRepair.of(
            native.primaryAxis.id,
            axis.id,
            ExactRational.integer(1000L),
            ExactRational.integer(0L),
            receipt
          )
        )
      )
    )
    def identity(stream: StreamId, at: PlaybackInstant) = TypedSupport
      .Anchored(right(EvidenceSupport.of(b, Vector(EvidenceAnchor.MediaPoint(b.id, stream, at)))))
      .identity
    val at = right(PlaybackInstant.on(b.primaryAxis, 5L))
    val original = identity(b.streams(1).id, at)
    assertNotEquals(original, identity(b.streams.head.id, at))
    assertNotEquals(
      original,
      identity(b.streams(1).id, right(PlaybackInstant.on(native.primaryAxis, 5L)))
    )
  }

  test("accepting control: an interval-only projection keeps its exact checked coordinates") {
    val value = support(EvidenceAnchor.MediaTime(bundle.id, stream, axis.id,
      PlaybackIntervalSet.one(interval(10L, 20L))))
    val projection = right(value.playbackOn(axis.id))
    assertEquals(projection.intervals, Vector(interval(10L, 20L)))
    assertEquals(projection.points, Vector.empty)
    assertEquals(projection.bounds, (10L, 20L))
    assertEquals(value.hullOn(axis.id), Right(interval(10L, 20L)))
  }

  test("complete union merges overlapping and adjacent intervals and bounds outer points") {
    val projection = right(PlaybackSupport.of(axis.id,
      Vector(interval(30L, 40L), interval(19L, 30L), interval(10L, 20L)),
      Vector(point(50L), point(5L), point(5L))))
    assertEquals(projection.intervals, Vector(interval(10L, 40L)))
    assertEquals(projection.points, Vector(point(5L), point(50L)))
    assertEquals(projection.bounds, (5L, 50L))
    val foreign = right(PlaybackSupport.of(native.primaryAxis.id, Vector.empty,
      Vector(right(PlaybackInstant.on(native.primaryAxis, 5L)))))
    assert(!projection.contains(foreign))
  }
