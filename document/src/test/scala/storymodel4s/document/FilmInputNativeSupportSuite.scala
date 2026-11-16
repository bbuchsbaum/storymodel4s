package storymodel4s.document

import munit.FunSuite
import storymodel4s.core.*

/** Native-support F1 court.
  *
  * Admission: reuses only the independently admitted mechanical `Alpha.`/`Beta.` fixture from
  * film-input-mechanical/v1; no participant or external source material.
  */
class FilmInputNativeSupportSuite extends FunSuite:
  import FilmInputFixtures.*
  private val ms = RationalTimebase.Millisecond
  private val draft = SourceBundle
    .filmEdition(EditionId.unsafe("native-draft"), Checksum.ofText("draft"), 0, 400, ms)
    .toOption
    .get
  private val extent = AxisExtent.playbackTicks(0, 400, ms).toOption.get
  private val picture = SourceStream
    .of(
      StreamId.unsafe("picture"),
      StreamKind.Picture,
      Checksum.ofText("picture"),
      draft.primaryAxis.id,
      extent,
      Some(ms),
      Vector.empty
    )
    .toOption
    .get
  private val audio = SourceStream
    .of(
      StreamId.unsafe("audio"),
      StreamKind.Audio,
      Checksum.ofText("audio"),
      draft.primaryAxis.id,
      extent,
      Some(ms),
      Vector.empty
    )
    .toOption
    .get
  private val edition = EditionId.unsafe("native-film")
  private val primary = SourceBundle
    .editionPlaybackAxis(edition, Vector(picture, audio), Vector(picture.id), 0, 400, ms)
    .toOption
    .get
  private val prospective = SourceBundle
    .prospectiveId(
      Some(edition),
      SourceKind.FilmEdition,
      Vector(picture, audio),
      AxisKind.EditionPlayback,
      Vector(picture.id)
    )
    .toOption
    .get
  private val native = PresentationAxis
    .streamClock(prospective, SourceKind.FilmEdition, audio.id, StreamKind.Audio, extent, Some(ms))
    .toOption
    .get
  private val nativeBundle = SourceBundle
    .of(
      Some(edition),
      SourceKind.FilmEdition,
      Vector(
        SourceStream
          .of(
            picture.id,
            picture.kind,
            picture.checksum,
            primary.id,
            extent,
            Some(ms),
            Vector.empty
          )
          .toOption
          .get,
        SourceStream
          .of(audio.id, audio.kind, audio.checksum, native.id, extent, Some(ms), Vector.empty)
          .toOption
          .get
      ),
      primary,
      Vector(picture.id),
      Vector.empty
    )
    .toOption
    .get
  private def interval(axis: PresentationAxis, a: Long, b: Long) =
    PlaybackInterval.onTimeAxis(axis, a, b).toOption.get
  private def both(a: Long, b: Long) = EvidenceSupport
    .of(
      nativeBundle,
      Vector(
        EvidenceAnchor.MediaTime(
          nativeBundle.id,
          picture.id,
          nativeBundle.primaryAxis.id,
          PlaybackIntervalSet.one(interval(nativeBundle.primaryAxis, a, b))
        ),
        EvidenceAnchor.MediaTime(
          nativeBundle.id,
          audio.id,
          native.id,
          PlaybackIntervalSet.one(interval(native, a, b))
        )
      )
    )
    .toOption
    .get
  private def nativeOnly(a: Long, b: Long) = EvidenceSupport
    .of(
      nativeBundle,
      Vector(
        EvidenceAnchor.MediaTime(
          nativeBundle.id,
          audio.id,
          native.id,
          PlaybackIntervalSet.one(interval(native, a, b))
        )
      )
    )
    .toOption
    .get
  private val nativeRows =
    rows.zipWithIndex.map((r, i) => r.copy(support = both(i * 100, (i + 1) * 100)))
  private val nativePrepared = FilmProposalSurface
    .derive(
      nativeBundle,
      nativeRows,
      Vector.empty,
      FilmProposalSurface
        .expectedDerivation(
          nativeBundle,
          nativeRows,
          Vector.empty,
          Checksum.ofText("Alpha.\n\nBeta.")
        )
        .toOption
        .get
    )
    .toOption
    .get
  private val na = nativePrepared.origins(0).sentence;
  private val nb = nativePrepared.origins(1).sentence
  private val nea = chart(na, "na", Vector(callA, shared));
  private val neb = chart(nb, "nb", Vector(callB, shared))
  private def bind(
      sentence: SurfaceUnitId,
      ev: storymodel4s.proposition.PropositionEvidence,
      calls: Vector[ProviderCall]
  ) =
    FilmParseBinding(
      sentence,
      nativePrepared.identity,
      nativeRows(nativePrepared.origins.indexWhere(_.sentence == sentence)).identity,
      parser,
      calls.head.inputChecksum,
      Checksum.ofText("recipe"),
      FilmCompilerInput.fullChartChecksum(ev),
      calls,
      calls.head
    )
  private val ba = bind(na, nea, Vector(callA, shared));
  private val bb = bind(nb, neb, Vector(callB, shared))
  private val associations = Vector(FilmChartAssociation(nea, ba), FilmChartAssociation(neb, bb))
  private val refs =
    Vector(ChartNodeRef(na, nea.chart.conceptIds.head), ChartNodeRef(nb, neb.chart.conceptIds.head))
  private def input(
      sentences: Vector[SurfaceUnitId],
      rs: Vector[ChartNodeRef],
      s: EvidenceSupport
  ) =
    FilmCompilerInput.of(
      nativePrepared,
      associations,
      Vector(ba, bb),
      Vector(FilmAttemptScope("native", sentences, rs, s))
    )

  test("mixed primary and native support is accepted") {
    assert(input(Vector(na, nb), refs, both(0, 200)).isRight)
  }
  test("matching primary A with native B is refused; native A control is accepted") {
    val mismatch = EvidenceSupport
      .of(
        nativeBundle,
        Vector(
          EvidenceAnchor.MediaTime(
            nativeBundle.id,
            picture.id,
            nativeBundle.primaryAxis.id,
            PlaybackIntervalSet.one(interval(nativeBundle.primaryAxis, 0, 100))
          ),
          EvidenceAnchor.MediaTime(
            nativeBundle.id,
            audio.id,
            native.id,
            PlaybackIntervalSet.one(interval(native, 100, 200))
          )
        )
      )
      .toOption
      .get
    assert(input(Vector(na), Vector(refs.head), mismatch).isLeft)
    assert(input(Vector(na), Vector(refs.head), both(0, 100)).isRight)
  }
  test("native-only attempt is refused for missing direct primary support") {
    assert(input(Vector(na), Vector(refs.head), nativeOnly(0, 100)).isLeft)
  }
  test("native-only source record is refused during preparation") {
    val bad = nativeRows.updated(0, nativeRows.head.copy(support = nativeOnly(0, 100)))
    assert(
      FilmProposalSurface
        .derive(
          nativeBundle,
          bad,
          Vector.empty,
          FilmProposalSurface
            .expectedDerivation(nativeBundle, bad, Vector.empty, Checksum.ofText("Alpha.\n\nBeta."))
            .toOption
            .get
        )
        .isLeft
    )
  }

  test("same legacy bundle identity does not admit stale full-bound support") {
    val first = nativeBundle.streams.head
    val changedStream = SourceStream
      .of(
        first.id,
        first.kind,
        first.checksum,
        first.nativeAxis,
        first.extent,
        first.timebase,
        Vector(StreamId.unsafe("upstream"))
      )
      .toOption
      .get
    val altered = SourceBundle
      .of(
        nativeBundle.edition,
        nativeBundle.sourceKind,
        nativeBundle.streams.updated(0, changedStream),
        nativeBundle.primaryAxis,
        nativeBundle.authorityTracks,
        nativeBundle.mappings
      )
      .toOption
      .get
    assertEquals(altered.id, nativeBundle.id)
    assertNotEquals(altered.identity, nativeBundle.identity)
    val expectedSurface = FilmProposalSurface
      .expectedDerivation(
        altered,
        nativeRows,
        Vector.empty,
        nativePrepared.derivation.outputChecksum
      )
      .toOption
      .get
    assert(FilmProposalSurface.derive(altered, nativeRows, Vector.empty, expectedSurface).isLeft)
    val rebuiltRows = nativeRows.map(r =>
      r.copy(support = EvidenceSupport.of(altered, r.support.anchors.toVector).toOption.get)
    )
    val rebuiltExpectation = FilmProposalSurface
      .expectedDerivation(
        altered,
        rebuiltRows,
        Vector.empty,
        nativePrepared.derivation.outputChecksum
      )
      .toOption
      .get
    assert(
      FilmProposalSurface.derive(altered, rebuiltRows, Vector.empty, rebuiltExpectation).isRight
    )
    val foreign = EvidenceSupport.of(altered, both(0, 100).anchors.toVector).toOption.get
    assert(
      input(Vector(na), Vector(refs.head), foreign).left.toOption.get.toString
        .contains("support/bundle-identity")
    )
  }

  test("same-axis same-tick support on another stream is not licensed") {
    val movedAudio = SourceStream
      .of(audio.id, audio.kind, audio.checksum, primary.id, extent, Some(ms), Vector.empty)
      .toOption
      .get
    val sameAxis = SourceBundle
      .of(
        Some(edition),
        SourceKind.FilmEdition,
        Vector(nativeBundle.streams.head, movedAudio),
        primary,
        Vector(picture.id),
        Vector.empty
      )
      .toOption
      .get
    def onStream(id: StreamId) = EvidenceSupport
      .of(
        sameAxis,
        Vector(
          EvidenceAnchor.MediaTime(
            sameAxis.id,
            id,
            primary.id,
            PlaybackIntervalSet.one(interval(primary, 0, 100))
          )
        )
      )
      .toOption
      .get
    val recordRows = rows.map(_.copy(support = onStream(picture.id)))
    val prepared0 = FilmProposalSurface
      .derive(
        sameAxis,
        recordRows,
        Vector.empty,
        FilmProposalSurface
          .expectedDerivation(
            sameAxis,
            recordRows,
            Vector.empty,
            nativePrepared.derivation.outputChecksum
          )
          .toOption
          .get
      )
      .toOption
      .get
    assertEquals(prepared0.origins.map(_.sentence), Vector(na, nb))
    val pins = Vector(ba, bb)
      .zip(recordRows)
      .map((pin, row) =>
        pin.copy(preparedIdentity = prepared0.identity, originRecordIdentity = row.identity)
      )
    val charts = associations.zip(pins).map((association, pin) => association.copy(binding = pin))
    def checked(support: EvidenceSupport) = FilmCompilerInput.of(
      prepared0,
      charts,
      pins,
      Vector(FilmAttemptScope("same-axis", Vector(na), Vector(refs.head), support))
    )
    assert(checked(onStream(picture.id)).isRight)
    assert(checked(onStream(audio.id)).left.toOption.get.toString.contains("attempt/containment"))
  }
