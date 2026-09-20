package storymodel4s.core

import munit.FunSuite

class D1bPointSupportSuite extends FunSuite:
  private def right[A](value: Either[DomainError, A]): A = value.fold(e => fail(e.message), identity)
  private val bundle = right(SourceBundle.filmEdition(
    EditionId.unsafe("point-film"), Checksum.ofText("point-picture"), 0L, 100L,
    RationalTimebase.Millisecond))
  private val axis = bundle.primaryAxis
  private val stream = bundle.streams.head.id
  private def point(tick: Long) = right(PlaybackInstant.on(axis, tick))
  private def interval(start: Long, end: Long) = right(PlaybackInterval.on(axis, start, end))
  private def support(anchors: EvidenceAnchor*) = right(EvidenceSupport.of(bundle, anchors.toVector))
  private def anchor(tick: Long) = EvidenceAnchor.MediaPoint(bundle.id, stream, point(tick))

  test("accepting control: exact native point survives support and complete projection"):
    val value = support(anchor(25L))
    assertEquals(value.anchors.toVector, Vector(anchor(25L)))
    val projection = right(value.playbackOn(axis.id))
    assertEquals(projection.intervals, Vector.empty)
    assertEquals(projection.points, Vector(point(25L)))
    assertEquals(projection.bounds, (25L, 25L))
    assertEquals(PrimaryProjection.on(bundle, TypedSupport.Anchored(value)),
      Right(PrimaryProjection.Playback(axis.id, projection)))

  test("mixed support retains interval gaps and explicit inside-interval points"):
    val value = support(
      EvidenceAnchor.MediaTime(bundle.id, stream, axis.id,
        right(PlaybackIntervalSet.of(Vector(interval(10L, 20L), interval(30L, 40L))))),
      anchor(15L), anchor(25L), anchor(25L))
    val projection = right(value.playbackOn(axis.id))
    assertEquals(projection.intervals.map(i => (i.start, i.endExclusive)), Vector((10L, 20L), (30L, 40L)))
    assertEquals(projection.points.map(_.at), Vector(15L, 25L))
    assertEquals(right(PlaybackSupport.of(axis.id, projection.intervals.reverse, projection.points.reverse)), projection)
    assertEquals(TypedSupport.Anchored(value).identity,
      TypedSupport.Anchored(support(value.anchors.toVector.reverse*)).identity)

  test("complete support requires a member and one common axis"):
    val foreign = right(SourceBundle.filmEdition(EditionId.unsafe("foreign-point"),
      Checksum.ofText("foreign"), 0L, 100L, RationalTimebase.Millisecond))
    val at = right(PlaybackInstant.on(foreign.primaryAxis, 25L))
    assert(PlaybackSupport.of(axis.id, Vector.empty, Vector.empty).isLeft)
    assert(PlaybackSupport.of(axis.id, Vector.empty, Vector(at)).isLeft)
    assert(PlaybackSupport.of(axis.id, Vector(right(PlaybackInterval.on(foreign.primaryAxis, 1L, 2L))), Vector(point(25L))).isLeft)
    assert(support(anchor(25L)).playbackOn(foreign.primaryAxis.id).isLeft)

  test("point anchors refuse foreign bundle, stream, axis and excluded native end"):
    val foreign = right(SourceBundle.filmEdition(EditionId.unsafe("foreign-point"),
      Checksum.ofText("foreign"), 0L, 101L, RationalTimebase.Millisecond))
    val wrong = Vector(
      EvidenceAnchor.MediaPoint(foreign.id, stream, point(25L)),
      EvidenceAnchor.MediaPoint(bundle.id, StreamId.unsafe("absent"), point(25L)),
      EvidenceAnchor.MediaPoint(bundle.id, stream, right(PlaybackInstant.on(foreign.primaryAxis, 25L))))
    wrong.foreach(a => assert(EvidenceSupport.of(bundle, Vector(a)).isLeft))
    assert(PlaybackInstant.on(axis, 100L).isLeft)
    assert(PlaybackInstant.on(axis, 0L).isRight)

  test("point support has no interval-only hull and no invented interval"):
    assert(support(anchor(25L)).intervalsOn(axis.id).isLeft)
    assert(support(anchor(25L)).hullOn(axis.id).isLeft)
    assert(support(anchor(25L), EvidenceAnchor.MediaTime(bundle.id, stream, axis.id,
      PlaybackIntervalSet.one(interval(10L, 20L)))).hullOn(axis.id).isLeft)

  test("containment distinguishes points, gaps and positive duration"):
    val parent = right(PlaybackSupport.of(axis.id, Vector(interval(10L, 20L), interval(30L, 40L)), Vector(point(25L))))
    def child(at: Long) = right(PlaybackSupport.of(axis.id, Vector.empty, Vector(point(at))))
    assert(parent.contains(child(10L)))
    assert(parent.contains(child(25L)))
    assert(!parent.contains(child(20L)))
    assert(!parent.contains(child(40L)))
    assert(!parent.contains(right(PlaybackSupport.of(axis.id, Vector(interval(25L, 26L)), Vector.empty))))

  test("full bundle binding refuses same-id metadata drift until explicit checked rebuild"):
    val first = bundle.streams.head
    val changed = right(SourceStream.of(first.id, first.kind, first.checksum, first.nativeAxis,
      first.extent, first.timebase, Vector(StreamId.unsafe("upstream-receipt"))))
    val altered = right(SourceBundle.of(bundle.edition, bundle.sourceKind, Vector(changed),
      bundle.primaryAxis, bundle.authorityTracks, bundle.mappings))
    assertEquals(altered.id, bundle.id)
    val original = support(anchor(25L))
    assert(original.checkedOn(altered).isLeft)
    assert(PrimaryProjection.on(altered, TypedSupport.Anchored(original)).isLeft)
    val rebuilt = right(EvidenceSupport.of(altered, original.anchors.toVector))
    assert(rebuilt.checkedOn(altered).isRight)
    assertNotEquals(TypedSupport.Anchored(original).identity, TypedSupport.Anchored(rebuilt).identity)

  test("physical identity distinguishes point tick axis stream and interval shape"):
    val original = TypedSupport.Anchored(support(anchor(25L))).identity
    assertNotEquals(original, TypedSupport.Anchored(support(anchor(26L))).identity)
    val extent = support(EvidenceAnchor.MediaTime(bundle.id, stream, axis.id,
      PlaybackIntervalSet.one(interval(25L, 26L))))
    assertNotEquals(original, TypedSupport.Anchored(extent).identity)
