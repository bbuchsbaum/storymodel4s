package storymodel4s.coreprobe

import munit.FunSuite
import storymodel4s.core.*

/** External-package court for ADR 0025: a stream's native axis is the bundle's primary axis, its
  * own stream clock re-minted from the bundle being built, or the source axis of one of the
  * bundle's `TrackComposition` mappings. Each refused route has a same-shape lawful control.
  */
class StreamAxisOwnershipSuite extends FunSuite:
  private val ms = RationalTimebase.Millisecond
  private val f1 = SourceBundle
    .filmEdition(EditionId.unsafe("ownership-f1"), Checksum.ofText("ownership-f1"), 0L, 100L, ms)
    .toOption
    .get
  private val edition = EditionId.unsafe("ownership-b2")
  private val extent = AxisExtent.playbackTicks(0L, 100L, ms).toOption.get

  // Stream drafts: a native axis does not enter the bundle id, so any placeholder serves.
  private def draft(name: String, kind: StreamKind): SourceStream = SourceStream
    .of(
      StreamId.unsafe(name),
      kind,
      Checksum.ofText(name),
      f1.primaryAxis.id,
      extent,
      Some(ms),
      Vector.empty
    )
    .toOption
    .get
  private val picture = draft("b2-picture", StreamKind.Picture)
  private val audio = draft("b2-audio", StreamKind.Audio)
  private val authority = Vector(picture.id)
  private val primary = SourceBundle
    .editionPlaybackAxis(edition, Vector(picture, audio), authority, 0L, 100L, ms)
    .toOption
    .get
  private val b2 = SourceBundle
    .prospectiveId(
      Some(edition),
      SourceKind.FilmEdition,
      Vector(picture, audio),
      AxisKind.EditionPlayback,
      authority
    )
    .toOption
    .get
  private def clock(bundle: SourceBundleId, ext: AxisExtent = extent): PresentationAxis =
    PresentationAxis
      .streamClock(bundle, SourceKind.FilmEdition, audio.id, StreamKind.Audio, ext, Some(ms))
      .toOption
      .get
  private def bound(s: SourceStream, axis: PresentationAxisId): SourceStream = SourceStream
    .of(s.id, s.kind, s.checksum, axis, s.extent, s.timebase, Vector.empty)
    .toOption
    .get
  private def build(
      audioAxis: PresentationAxisId,
      mappings: Vector[CheckedMapping] = Vector.empty
  ): Either[DomainError, SourceBundle] = SourceBundle.of(
    Some(edition),
    SourceKind.FilmEdition,
    Vector(bound(picture, primary.id), bound(audio, audioAxis)),
    primary,
    authority,
    mappings
  )
  private def refusedForAxis(result: Either[DomainError, SourceBundle]): Unit = result match
    case Left(e)  => assert(e.message.contains("bundle/stream-axis"), e.message)
    case Right(_) => fail("expected a bundle/stream-axis refusal")
  private val receipt =
    SourceDerivationReceipt.of("ownership/v1", "court", Vector.empty).toOption.get

  test("the prospective id is the id the bundle gets"):
    assertEquals(primary.bundle, b2)
    assertEquals(build(clock(b2).id).map(_.id), Right(b2))

  test("a stream on another bundle's primary axis is refused"):
    refusedForAxis(build(f1.primaryAxis.id))

  test("a stream clock minted with another bundle's id is refused"):
    refusedForAxis(build(clock(f1.id).id))

  test("a stream clock minted with a different extent is refused"):
    val shorter = AxisExtent.playbackTicks(0L, 50L, ms).toOption.get
    refusedForAxis(build(clock(b2, shorter).id))

  test("control: the stream's own clock is admitted, and support on it anchors"):
    val bundle = build(clock(b2).id).fold(e => fail(e.message), identity)
    val interval = PlaybackInterval.onTimeAxis(clock(b2), 10L, 20L).toOption.get
    val anchor =
      EvidenceAnchor.MediaTime(bundle.id, audio.id, clock(b2).id, PlaybackIntervalSet.one(interval))
    assert(EvidenceSupport.of(bundle, Vector(anchor)).isRight)

  test("a clock repair from a foreign axis does not license it"):
    val repair = ClockRepair
      .of(
        f1.primaryAxis.id,
        primary.id,
        ExactRational.of(1L, 1L).toOption.get,
        ExactRational.of(0L, 1L).toOption.get,
        receipt
      )
      .toOption
      .get
    refusedForAxis(build(f1.primaryAxis.id, Vector(repair)))

  test("control: a track composition from a foreign axis licenses it"):
    val segment = CompositionSegment
      .of(
        PlaybackInterval.on(f1.primaryAxis, 0L, 100L).toOption.get,
        PlaybackInterval.on(primary, 0L, 100L).toOption.get,
        OccurrenceId.unsafe("f1")
      )
      .toOption
      .get
    val composition =
      TrackComposition.of(f1.primaryAxis.id, primary.id, Vector(segment), receipt).toOption.get
    assert(build(f1.primaryAxis.id, Vector(composition)).isRight)

  test("a stream clock is never a bundle's primary axis"):
    val result = SourceBundle.of(
      Some(edition),
      SourceKind.FilmEdition,
      Vector(bound(picture, clock(b2).id), bound(audio, clock(b2).id)),
      clock(b2),
      authority,
      Vector.empty
    )
    assert(
      result.left.exists(_.message.contains("never a bundle's primary axis")),
      result
    )
