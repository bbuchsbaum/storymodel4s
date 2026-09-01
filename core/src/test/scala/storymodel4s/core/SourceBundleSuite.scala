package storymodel4s.core

import munit.FunSuite

class SourceBundleSuite extends FunSuite:
  private def story(text: String): StorySource = StorySource.fromText(text).toOption.get

  private def film: SourceBundle =
    SourceBundle
      .filmEdition(
        EditionId.unsafe("edition-a"),
        Checksum.ofText("picture-bytes"),
        0L,
        90090L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get

  private def textBundle: SourceBundle =
    SourceBundle.writtenText(story("Alpha beta. Gamma.")).toOption.get

  test("written-text bundle is content-addressed and refuses an invented edition axis"):
    val src = story("Hello world.")
    val a = SourceBundle.writtenText(src).toOption.get
    val b = SourceBundle.writtenText(src).toOption.get
    assertEquals(a.id, b.id)
    assertEquals(a.primaryAxis.kind, AxisKind.TextCharacter)
    assertEquals(a.sourceKind, SourceKind.WrittenText)
    val tb = RationalTimebase.Millisecond
    assert(
      PresentationAxis
        .inventedEditionPlayback(a.id, SourceKind.WrittenText, 0L, 10L, tb)
        .isLeft
    )
    assert(
      SourceBundle
        .of(
          None,
          SourceKind.WrittenText,
          a.streams,
          PresentationAxis
            .editionPlayback(a.id, EditionId.unsafe("ghost"), 0L, 10L, tb)
            .toOption
            .get,
          a.authorityTracks,
          Vector.empty
        )
        .isLeft
    )

  test("film bundle requires a named edition playback axis"):
    val bundle = film
    assertEquals(bundle.sourceKind, SourceKind.FilmEdition)
    assertEquals(bundle.primaryAxis.kind, AxisKind.EditionPlayback)
    assert(bundle.edition.nonEmpty)
    val textAxis = PresentationAxis.textCharacter(bundle.id, 12).toOption.get
    assert(
      SourceBundle
        .of(
          bundle.edition,
          SourceKind.FilmEdition,
          bundle.streams,
          textAxis,
          bundle.authorityTracks,
          Vector.empty
        )
        .isLeft
    )

  test("PTS and DTS missingness are independent; present PTS < DTS refuses without repair"):
    assert(PacketTimeFields.of(TimestampField.missing, TimestampField.missing).isRight)
    assert(PacketTimeFields.of(TimestampField.present(10L), TimestampField.missing).isRight)
    assert(PacketTimeFields.of(TimestampField.missing, TimestampField.present(10L)).isRight)
    assert(PacketTimeFields.of(TimestampField.present(10L), TimestampField.present(10L)).isRight)
    assert(PacketTimeFields.of(TimestampField.present(10L), TimestampField.present(11L)).isLeft)
    assert(PacketTimeFields.of(TimestampField.present(11L), TimestampField.present(10L)).isRight)

  test("fixture INT64_MIN is missing; the same raw rescale overflow is a refusal"):
    val witness = FixturePacketWitness.synthetic(Long.MinValue, Long.MinValue, 0L)
    val classified = FixturePacketNormalizer.classify(witness).toOption.get
    assertEquals(classified.fields.pts, TimestampField.missing)
    assertEquals(classified.fields.dts, TimestampField.missing)
    assertEquals(classified.duration, MediaDuration.Unknown)
    assertEquals(classified.authority, ObservationAuthority.FixtureScoped)

    val present = FixturePacketNormalizer
      .classify(FixturePacketWitness.synthetic(9000L, 8000L, 40L))
      .toOption
      .get
    present.fields.pts match
      case TimestampField.Present(tick) => assertEquals(tick, 9000L)
      case TimestampField.Missing       => fail("present packet field became missing")
    present.duration match
      case k: MediaDuration.KnownPositive => assertEquals(k.ticks, 40L)
      case MediaDuration.Unknown          => fail("positive duration became unknown")

    val huge = ExactRescaler.rescaleExact(
      Long.MaxValue,
      RationalTimebase.of(1L, 1L).toOption.get,
      RationalTimebase.of(1L, Long.MaxValue).toOption.get
    )
    huge match
      case RescaleOutcome.Refused(RescaleRefusal.Overflow, _) => ()
      case other => fail(s"expected overflow refusal, got $other")

  test("exact rational rescale stays exact; inexact integral conversion refuses"):
    val ms = RationalTimebase.Millisecond
    val us = RationalTimebase.of(1L, 1_000_000L).toOption.get
    ExactRescaler.rescaleExact(3L, ms, us) match
      case RescaleOutcome.Exact(tick, target, _) =>
        assertEquals(tick, 3000L)
        assertEquals(target, us)
      case other => fail(s"expected exact 3000us, got $other")
    ExactRescaler.rescaleExact(1L, us, ms) match
      case RescaleOutcome.Refused(RescaleRefusal.InexactIntegral, _) => ()
      case other => fail(s"expected inexact refusal, got $other")
    ExactRescaler.rescaleIntegral(1L, us, ms, RoundingPolicy.TowardZero) match
      case RescaleOutcome.Quantized(tick, _, RoundingPolicy.TowardZero, residual, _) =>
        assertEquals(tick, 0L)
        assert(!residual.isZero)
      case other => fail(s"expected quantized residual, got $other")

  test("provenance flip and receipt substitution refuse"):
    val classified = FixturePacketNormalizer
      .classify(FixturePacketWitness.synthetic(10L, 10L, 1L))
      .toOption
      .get
    assert(FixturePacketNormalizer.promoteToRuntime(classified).isLeft)
    val forged = Checksum.ofText("not-the-preimage")
    assert(FixturePacketWitness.rehydrate(10L, 10L, 1L, forged).isLeft)
    assert(FixturePacketWitness.rehydrate(10L, 10L, 1L, classified.witnessIdentity).isRight)

    val exact = ExactRescaler.rescaleExact(
      2L,
      RationalTimebase.Millisecond,
      RationalTimebase.of(1L, 1_000_000L).toOption.get
    )
    assert(ExactRescaler.substituteIdentity(exact, forged).isLeft)
    exact match
      case RescaleOutcome.Exact(_, _, id) =>
        assert(ExactRescaler.substituteIdentity(exact, id).isRight)
      case other => fail(s"expected exact outcome, got $other")

    val draft = CallerRuntimePacketRecord.draft(1L, 1L, 1L, Checksum.ofText("receipt")).toOption.get
    assertEquals(draft.authority, ObservationAuthority.Draft)
    assert(CallerRuntimePacketRecord.promoteToRuntime(draft).isLeft)

  test("foreign stream, bundle, or axis identity refuses"):
    val bundle = film
    val other = textBundle
    val iv = PlaybackInterval.on(bundle.primaryAxis, 0L, 40L).toOption.get
    val set = PlaybackIntervalSet.one(iv)
    assert(EvidenceSupport.media(bundle, bundle.streams.head.id, set).isRight)
    assert(EvidenceSupport.media(bundle, other.streams.head.id, set).isLeft)
    val a = PlaybackCoordinate.on(bundle.primaryAxis, 0L).toOption.get
    val b = PlaybackCoordinate.on(bundle.primaryAxis, 10L).toOption.get
    assert(PlaybackCoordinate.compare(a, b).isRight)
    val otherFilm = SourceBundle
      .filmEdition(
        EditionId.unsafe("edition-b"),
        Checksum.ofText("other-picture"),
        0L,
        1000L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get
    val c = PlaybackCoordinate.on(otherFilm.primaryAxis, 0L).toOption.get
    assert(PlaybackCoordinate.compare(a, c).isLeft)
    assert(
      TextCoordinate
        .compare(
          TextCoordinate.on(other.primaryAxis, 0).toOption.get,
          TextCoordinate.on(other.primaryAxis, 1).toOption.get
        )
        .isRight
    )

  test("empty support, empty interval set, and unknown extent refuse"):
    assert(EvidenceSupport.of(film, Vector.empty).isLeft)
    assert(PlaybackIntervalSet.of(Vector.empty).isLeft)
    assert(PlaybackInterval.on(film.primaryAxis, 10L, 10L).isLeft)
    assert(MediaDuration.knownPositive(0L).isLeft)
    assert(MediaDuration.knownPositive(-1L).isLeft)
    assert(AxisExtent.textChars(0).isLeft)
    assert(RelativePosition.of(film.primaryAxis.id, Double.NaN).isLeft)
    assert(RelativePosition.of(film.primaryAxis.id, Double.PositiveInfinity).isLeft)
    assert(AxisNormalization.playback(film.primaryAxis, 90090L).isLeft)

  test("mixed-axis support has no hull; same-axis hull stays on the selected axis"):
    val bundle = film
    val iv = PlaybackInterval.on(bundle.primaryAxis, 0L, 40L).toOption.get
    val media = EvidenceSupport
      .media(bundle, bundle.streams.head.id, PlaybackIntervalSet.one(iv))
      .toOption
      .get
    assert(media.selectedPlaybackAxis.isRight)
    assertEquals(media.hullOn(bundle.primaryAxis.id).toOption.get.start, 0L)
    assert(media.hullOn(textBundle.primaryAxis.id).isLeft)
    val text = EvidenceSupport
      .text(textBundle, textBundle.streams.head.id, SpanSet.one(TextSpan.unsafe(0, 5)))
      .toOption
      .get
    assert(text.selectedPlaybackAxis.isLeft)
    assertEquals(text.textSpans.map(_.coveredLength), Some(5))

  test("clock repair is required before run-local seconds become PTS"):
    val src = PresentationAxisId.unsafe("run-local")
    val tgt = film.primaryAxis.id
    val receipt = SourceDerivationReceipt.of("clock-repair", "affine-v1", Vector.empty).toOption.get
    assert(ClockRepair.projectRunLocalSeconds(ExactRational.integer(2), src, tgt).isLeft)
    val repair = ClockRepair
      .of(src, tgt, ExactRational.integer(1000), ExactRational.Zero, receipt)
      .toOption
      .get
    assertEquals(
      ClockRepair.projectRunLocalSeconds(ExactRational.integer(2), src, tgt, repair).toOption.get,
      ExactRational.integer(2000)
    )
    assert(ClockRepair.projectRunLocalSeconds(ExactRational.integer(2), tgt, src, repair).isLeft)

  test("typed boundaries remain distinct at the same instant"):
    val at = PlaybackInstant.on(film.primaryAxis, 40L).toOption.get
    val shot =
      BoundaryClaim.shot(BoundaryId.unsafe("cut-40"), ShotMorphology.hardCut(at)).toOption.get
    val scene = BoundaryClaim.codedScene(BoundaryId.unsafe("scene-40"), at).toOption.get
    assertEquals(shot.layer, BoundaryLayer.Shot)
    assertEquals(scene.layer, BoundaryLayer.CodedScene)
    assertNotEquals(shot, scene)
    assert(BoundarySearchCoverage.negativeFromEmptyDetector(BoundaryLayer.Shot).isLeft)

  test("AudioSpan absent, nonempty, and legacy-empty remain distinct and unpromoted"):
    val absent: Option[AudioSpan] = None
    val empty = AudioSpan.of(40L, 40L).toOption.get
    val nonempty = AudioSpan.of(40L, 80L).toOption.get
    assertNotEquals(absent, Some(empty))
    assertNotEquals(Some(empty), Some(nonempty))
    assertEquals(empty.durationMillis, 0L)
    assertEquals(nonempty.durationMillis, 40L)
    assert(
      LegacyAudioBinding.toMediaSupport(empty, film, film.streams.head.id, film.primaryAxis).isLeft
    )
    assert(
      LegacyAudioBinding
        .toMediaSupport(nonempty, film, film.streams.head.id, film.primaryAxis)
        .isLeft
    )

  test("SpanSet text behavior is unchanged beside the new support type"):
    val a = SpanSet.one(TextSpan.unsafe(0, 4))
    val b = SpanSet.one(TextSpan.unsafe(4, 8))
    val joined = a ++ b
    assertEquals(joined.coveredLength, 8)
    assert(joined.isContiguous)
    assertEquals(SpanSet.of(Vector.empty), None)
    val support = EvidenceSupport.text(textBundle, textBundle.streams.head.id, joined).toOption.get
    assertEquals(support.textSpans.map(_.coveredLength), Some(8))

  test("text and playback coordinates have no common comparison"):
    val text = TextCoordinate.on(textBundle.primaryAxis, 0).toOption.get
    val play = PlaybackCoordinate.on(film.primaryAxis, 0L).toOption.get
    assertEquals(text.offset, 0)
    assertEquals(play.tick, 0L)
    assertNotEquals(text.axis, play.axis)

  test("zero-denominator and non-positive timebases refuse"):
    assert(ExactRational.of(1L, 0L).isLeft)
    assert(RationalTimebase.of(0L, 1L).isLeft)
    assert(RationalTimebase.of(-1L, 1000L).isLeft)
    assertEquals(ExactRational.of(2L, 4L).toOption.get, ExactRational.of(1L, 2L).toOption.get)
