package storymodel4s.core

import munit.FunSuite

class D1aSupportSuite extends FunSuite:
  private def film(end: Long = 100L): SourceBundle =
    SourceBundle.filmEdition(EditionId.unsafe("support-film"), Checksum.ofText("picture"),
      0L, end, RationalTimebase.Millisecond).toOption.get
  private val text = SourceBundle.writtenText(StorySource.fromText("Alpha beta.").toOption.get).toOption.get
  private val receipt = SourceDerivationReceipt.of("repair", "one", Vector.empty).toOption.get
  private def iv(bundle: SourceBundle, start: Long, end: Long): PlaybackInterval =
    PlaybackInterval.on(bundle.primaryAxis, start, end).toOption.get
  private def media(bundle: SourceBundle, start: Long, end: Long): EvidenceAnchor =
    EvidenceAnchor.MediaTime(bundle.id, bundle.streams.head.id, bundle.primaryAxis.id,
      PlaybackIntervalSet.one(iv(bundle, start, end)))
  private def support(bundle: SourceBundle, anchors: EvidenceAnchor*): EvidenceSupport =
    EvidenceSupport.of(bundle, anchors.toVector).toOption.get

  private def withExtra(extra: SourceStream): SourceBundle =
    val first = film()
    val streams = first.streams :+ extra
    val id = SourceBundle.computeId(first.edition, first.sourceKind, streams,
      AxisKind.EditionPlayback, first.authorityTracks).toOption.get
    val axis = PresentationAxis.editionPlayback(id, first.edition.get, 0L, 100L,
      RationalTimebase.Millisecond).toOption.get
    val picture = SourceStream.of(first.streams.head.id, StreamKind.Picture,
      first.streams.head.checksum, axis.id, axis.extent, axis.timebase, Vector.empty).toOption.get
    SourceBundle.of(first.edition, first.sourceKind, Vector(picture, extra), axis,
      first.authorityTracks, Vector.empty).toOption.get

  private val native = SourceBundle.filmEdition(EditionId.unsafe("native"), Checksum.ofText("native"),
    0L, 10L, RationalTimebase.Millisecond).toOption.get
  private def extra(kind: StreamKind = StreamKind.Audio, end: Long = 10L): SourceStream =
    SourceStream.of(StreamId.unsafe("secondary"), kind, Checksum.ofText("secondary"),
      native.primaryAxis.id, AxisExtent.playbackTicks(0L, end, RationalTimebase.Millisecond).toOption.get,
      Some(RationalTimebase.Millisecond), Vector.empty).toOption.get
  private def mapped(bundle: SourceBundle, mappings: CheckedMapping*): SourceBundle =
    SourceBundle.of(bundle.edition, bundle.sourceKind, bundle.streams, bundle.primaryAxis,
      bundle.authorityTracks, mappings.toVector).toOption.get
  private def secondary(bundle: SourceBundle, start: Long, end: Long): EvidenceAnchor =
    EvidenceAnchor.MediaTime(bundle.id, StreamId.unsafe("secondary"), bundle.primaryAxis.id,
      PlaybackIntervalSet.one(iv(bundle, start, end)))

  test("accepting control: all four lawful anchor kinds survive"):
    val bundle = film()
    val interval = iv(bundle, 2L, 6L)
    assert(EvidenceSupport.of(bundle, Vector(media(bundle, 2L, 6L),
      EvidenceAnchor.Shot(bundle.id, bundle.streams.head.id, ShotId.unsafe("s"), interval),
      EvidenceAnchor.Track(bundle.id, bundle.streams.head.id, TrackId.unsafe("t"),
        PlaybackIntervalSet.one(interval)))).isRight)
    assert(EvidenceSupport.text(text, text.streams.head.id, SpanSet.one(TextSpan.unsafe(0, 5))).isRight)

  test("refuse anchor stream-kind mismatch"):
    val bundle = film()
    assert(EvidenceSupport.text(bundle, bundle.streams.head.id, SpanSet.one(TextSpan.unsafe(0, 2))).isLeft)
    val audio = withExtra(extra())
    assert(EvidenceSupport.of(audio, Vector(EvidenceAnchor.Shot(audio.id, extra().id,
      ShotId.unsafe("wrong-kind"), iv(native, 0L, 4L)))).isLeft)

  test("refuse MediaTime axis different from its interval axis"):
    val bundle = film()
    val anchor = EvidenceAnchor.MediaTime(bundle.id, bundle.streams.head.id, native.primaryAxis.id,
      PlaybackIntervalSet.one(iv(bundle, 2L, 6L)))
    assert(EvidenceSupport.of(bundle, Vector(anchor)).isLeft)

  test("refuse foreign bundle even with a local stream"):
    val bundle = film()
    val anchor = EvidenceAnchor.MediaTime(native.id, bundle.streams.head.id, bundle.primaryAxis.id,
      PlaybackIntervalSet.one(iv(bundle, 2L, 6L)))
    assert(EvidenceSupport.of(bundle, Vector(anchor)).isLeft)

  test("refuse foreign stream even with a local bundle"):
    val bundle = film()
    val anchor = EvidenceAnchor.MediaTime(bundle.id, StreamId.unsafe("foreign"), bundle.primaryAxis.id,
      PlaybackIntervalSet.one(iv(bundle, 2L, 6L)))
    assert(EvidenceSupport.of(bundle, Vector(anchor)).isLeft)

  test("refuse foreign axis even with local bundle and stream"):
    val bundle = film()
    val anchor = EvidenceAnchor.MediaTime(bundle.id, bundle.streams.head.id, native.primaryAxis.id,
      PlaybackIntervalSet.one(iv(native, 2L, 6L)))
    assert(EvidenceSupport.of(bundle, Vector(anchor)).isLeft)

  test("refuse text spans outside the selected extent"):
    assert(EvidenceSupport.text(text, text.streams.head.id,
      SpanSet.one(TextSpan.unsafe(0, 12))).isLeft)

  test("text spans are selected per stream"):
    val first = SourceStream.of(StreamId.unsafe("text-one"), StreamKind.Subtitle,
      Checksum.ofText("abcdef"), PresentationAxisId.unsafe("text-axis-one"),
      AxisExtent.textChars(6).toOption.get, None, Vector.empty).toOption.get
    val second = SourceStream.of(StreamId.unsafe("text-two"), StreamKind.Annotation,
      Checksum.ofText("uvwxyz"), PresentationAxisId.unsafe("text-axis-two"),
      AxisExtent.textChars(6).toOption.get, None, Vector.empty).toOption.get
    val a = withExtra(first)
    val allStreams = a.streams :+ second
    val id = SourceBundle.computeId(a.edition, a.sourceKind, allStreams, AxisKind.EditionPlayback,
      a.authorityTracks).toOption.get
    val axis = PresentationAxis.editionPlayback(id, a.edition.get, 0L, 100L,
      RationalTimebase.Millisecond).toOption.get
    val bundle = SourceBundle.of(a.edition, a.sourceKind, allStreams, axis,
      a.authorityTracks, Vector.empty).toOption.get
    val left = SpanSet.one(TextSpan.unsafe(0, 2))
    val right = SpanSet.one(TextSpan.unsafe(4, 6))
    val both = support(bundle, EvidenceAnchor.Text(bundle.id, first.id, left),
      EvidenceAnchor.Text(bundle.id, second.id, right))
    assertEquals(both.textSpans(first.id), Some(left))
    assertEquals(both.textSpans(second.id), Some(right))
    assertEquals(both.textSpans(StreamId.unsafe("missing")), None)

  test("interval union merges overlap and adjacency while preserving gaps"):
    val bundle = film()
    val anchors = Vector(media(bundle, 1L, 4L), media(bundle, 3L, 6L),
      media(bundle, 6L, 8L), media(bundle, 11L, 14L))
    val result = support(bundle, anchors*).intervalsOn(bundle.primaryAxis.id).toOption.get
    assertEquals(result.intervals.toVector.map(i => (i.start, i.endExclusive)), Vector((1L, 8L), (11L, 14L)))
    assertEquals(support(bundle, anchors.reverse*).intervalsOn(bundle.primaryAxis.id), Right(result))
    assertEquals(EvidenceSupport.media(bundle, bundle.streams.head.id, result).toOption.get
      .intervalsOn(bundle.primaryAxis.id), Right(result))
    assert(support(bundle, anchors*).intervalsOn(native.primaryAxis.id).isLeft)

  test("right bundle does not license another stream's axis"):
    val bundle = withExtra(extra())
    assert(EvidenceSupport.of(bundle, Vector(secondary(bundle, 1L, 4L))).isLeft)
    val wrongNative = EvidenceAnchor.MediaTime(bundle.id, bundle.streams.head.id, native.primaryAxis.id,
      PlaybackIntervalSet.one(iv(native, 1L, 4L)))
    assert(EvidenceSupport.of(bundle, Vector(wrongNative)).isLeft)

  test("native support obeys the selected stream extent"):
    val bundle = withExtra(extra(end = 3L))
    val anchor = EvidenceAnchor.MediaTime(bundle.id, extra().id, native.primaryAxis.id,
      PlaybackIntervalSet.one(iv(native, 1L, 4L)))
    assert(EvidenceSupport.of(bundle, Vector(anchor)).isLeft)

  test("clock mapping admits its exact image and refuses partial or absent coverage"):
    val raw = withExtra(extra())
    val repair = ClockRepair.of(native.primaryAxis.id, raw.primaryAxis.id,
      ExactRational.integer(1000L), ExactRational.integer(20L), receipt).toOption.get
    val bundle = mapped(raw, repair)
    assert(EvidenceSupport.of(bundle, Vector(secondary(bundle, 20L, 30L))).isRight)
    assert(EvidenceSupport.of(bundle, Vector(secondary(bundle, 19L, 21L))).isLeft)
    assert(EvidenceSupport.of(bundle, Vector(secondary(bundle, 40L, 50L))).isLeft)
    val conflicting = ClockRepair.of(native.primaryAxis.id, raw.primaryAxis.id,
      ExactRational.integer(1000L), ExactRational.integer(21L), receipt).toOption.get
    assert(EvidenceSupport.of(mapped(raw, repair, conflicting), Vector(secondary(bundle, 21L, 25L))).isLeft)

  test("composition image preserves gaps and refuses source segments outside stream extent"):
    val raw = withExtra(extra())
    def segment(a: Long, b: Long, c: Long, d: Long, name: String): CompositionSegment =
      CompositionSegment.of(iv(native, a, b), iv(raw, c, d), OccurrenceId.unsafe(name)).toOption.get
    val composition = TrackComposition.of(native.primaryAxis.id, raw.primaryAxis.id,
      Vector(segment(0, 4, 20, 24, "a"), segment(5, 10, 30, 35, "b")), receipt).toOption.get
    val bundle = mapped(raw, composition)
    assert(EvidenceSupport.of(bundle, Vector(secondary(bundle, 20L, 24L))).isRight)
    assert(EvidenceSupport.of(bundle, Vector(secondary(bundle, 22L, 32L))).isLeft)
    assert(EvidenceSupport.of(bundle, Vector(secondary(bundle, 24L, 25L))).isLeft)
    val short = withExtra(extra(end = 3L))
    val shortComposition = TrackComposition.of(native.primaryAxis.id, short.primaryAxis.id,
      Vector(CompositionSegment.of(iv(native, 0, 4), iv(short, 20, 24), OccurrenceId.unsafe("bad"))
        .toOption.get), receipt).toOption.get
    assert(EvidenceSupport.of(mapped(short, shortComposition), Vector(secondary(short, 20, 24))).isLeft)

  test("full mapping and bundle identity distinguish equal relation IDs"):
    val raw = withExtra(extra())
    val first = ClockRepair.of(native.primaryAxis.id, raw.primaryAxis.id,
      ExactRational.integer(1000L), ExactRational.Zero, receipt).toOption.get
    val second = ClockRepair.of(native.primaryAxis.id, raw.primaryAxis.id,
      ExactRational.integer(1000L), ExactRational.integer(1L), receipt).toOption.get
    assertEquals(first.relation.id, second.relation.id)
    assertNotEquals(first.identity, second.identity)
    assertEquals(mapped(raw, first).id, mapped(raw, second).id)
    assertNotEquals(mapped(raw, first).identity, mapped(raw, second).identity)
    assertEquals(film().id, film(101L).id)
    assertNotEquals(film().identity, film(101L).identity)
    assertEquals(withExtra(extra()).id, withExtra(extra(end = 9L)).id)
    assertNotEquals(withExtra(extra()).identity, withExtra(extra(end = 9L)).identity)

  test("safe receipt binding distinguishes legacy delimiter collision"):
    val left = SourceDerivationReceipt.of("a\u0000b", "c", Vector.empty).toOption.get
    val right = SourceDerivationReceipt.of("a", "b\u0000c", Vector.empty).toOption.get
    assertEquals(left.identity, right.identity)
    assertNotEquals(left.bindingIdentity, right.bindingIdentity)
