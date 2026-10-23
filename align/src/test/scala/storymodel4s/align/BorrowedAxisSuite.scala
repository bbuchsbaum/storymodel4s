package storymodel4s.align

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

/** ADR 0025 lets a stream sit on a foreign axis only when its bundle names that axis as the source
  * of one of its own `TrackComposition` mappings, and every piece of such a mapping is public. So a
  * film can self-declare a composition from part a's axis. Equal axes are the same footage only for
  * the part's own streams or the declared composition, so its support must not count as part a's.
  */
class BorrowedAxisSuite extends FunSuite:
  import SherlockShapedSource.*

  private val receipt =
    SourceDerivationReceipt.of("self-declared/v1", "borrowed", Vector.empty).toOption.get

  // A film whose only stream sits on part a's axis, optionally under its own composition from it.
  private def borrower(stream: SourceStream, declare: Boolean): Either[DomainError, SourceBundle] =
    val edition = EditionId.unsafe(s"borrower-${stream.checksum.hex.take(8)}")
    val axis = SourceBundle
      .editionPlaybackAxis(edition, Vector(stream), Vector(stream.id), 0L, 40L, timebase)
      .toOption
      .get
    val mappings =
      if !declare then Vector.empty
      else
        val segment = CompositionSegment
          .of(
            PlaybackInterval.on(a.primaryAxis, 0L, 40L).toOption.get,
            PlaybackInterval.on(axis, 0L, 40L).toOption.get,
            OccurrenceId.unsafe("self")
          )
          .toOption
          .get
        Vector(
          TrackComposition.of(a.primaryAxis.id, axis.id, Vector(segment), receipt).toOption.get
        )
    SourceBundle.of(
      Some(edition),
      SourceKind.FilmEdition,
      Vector(stream),
      axis,
      Vector(stream.id),
      mappings
    )

  private def onAxisA(id: StreamId, checksum: String): SourceStream = SourceStream
    .of(
      id,
      StreamKind.Picture,
      Checksum.ofText(checksum),
      a.primaryAxis.id,
      a.primaryAxis.extent,
      a.primaryAxis.timebase,
      Vector.empty
    )
    .toOption
    .get

  private def onPartA(owner: SourceBundle, stream: StreamId): SourceSupportStatus =
    val anchor = EvidenceAnchor.MediaTime(
      owner.id,
      stream,
      a.primaryAxis.id,
      PlaybackIntervalSet.one(PlaybackInterval.on(a.primaryAxis, 2L, 5L).toOption.get)
    )
    val support = EvidenceSupport
      .of(owner, Vector(anchor))
      .fold(e => fail(s"support on part a's axis: ${e.message}"), identity)
    SourceSupportStatus.located(TypedSupport.Anchored(support))

  private def asPartA(owner: SourceBundle, stream: StreamId) = SourceRepresentation.of(
    view.copy(nodes = Vector(nodes.head)),
    NonEmptyVector.of(a, b, owner).map(BundleEntry.media),
    None,
    Map(e(1) -> onPartA(owner, stream))
  )

  test("core refuses a film that borrows a part's axis without declaring a composition"):
    val stream = onAxisA(StreamId.unsafe("borrowed-picture"), "borrowed-picture")
    assert(borrower(stream, declare = false).left.exists(_.message.contains("bundle/stream-axis")))

  test("a borrowing bundle's own composition over the axis does not make it the part"):
    val stream = onAxisA(StreamId.unsafe("borrowed-picture"), "borrowed-picture")
    val composer = borrower(stream, declare = true).fold(e => fail(e.message), identity)
    assertEquals(
      asPartA(composer, stream.id).map(_ => ()),
      Left(MappingRefusal.ForeignAxis(a.primaryAxis.id))
    )

  test("a borrowing stream that reuses the part's stream id is still different footage"):
    val impostor = onAxisA(a.streams.head.id, "impostor-bytes")
    val film = borrower(impostor, declare = true).fold(e => fail(e.message), identity)
    assertEquals(
      asPartA(film, impostor.id).map(_ => ()),
      Left(MappingRefusal.ForeignAxis(a.primaryAxis.id))
    )

  // A two-part composition whose second part carries an extra stream natively on the composed
  // axis. Bundle ids hash stream id, kind and checksum only, so that axis can be derived before
  // the stream is bound to it.
  private object PartOnComposedAxis:
    private def draft(name: String) = SourceStream
      .of(
        StreamId.unsafe(name),
        StreamKind.Picture,
        Checksum.ofText(name),
        a.primaryAxis.id,
        a.primaryAxis.extent,
        a.primaryAxis.timebase,
        Vector.empty
      )
      .toOption
      .get
    private def bind(s: SourceStream, axis: PresentationAxis) = SourceStream
      .of(s.id, s.kind, s.checksum, axis.id, axis.extent, axis.timebase, Vector.empty)
      .toOption
      .get
    private val (picture, extra) = (draft("p2-picture"), draft("p2-extra"))
    private val p2Edition = EditionId.unsafe("p2")
    private val p2Axis = SourceBundle
      .editionPlaybackAxis(p2Edition, Vector(picture, extra), Vector(picture.id), 0L, 40L, timebase)
      .toOption
      .get
    private val cEdition = EditionId.unsafe("p2-composed")
    private val authority = a.streams.map(_.id) :+ picture.id
    val composedAxis: PresentationAxis = SourceBundle
      .editionPlaybackAxis(
        cEdition,
        a.streams ++ Vector(picture, extra),
        authority,
        0L,
        80L,
        timebase
      )
      .toOption
      .get
    val onComposed: SourceStream = bind(extra, composedAxis)
    private val p2Picture = bind(picture, p2Axis)
    // ADR 0025: p2 may keep a stream on the composed axis only under its own composition from it.
    private val p2Own = TrackComposition
      .of(
        composedAxis.id,
        p2Axis.id,
        Vector(
          CompositionSegment
            .of(
              PlaybackInterval.on(composedAxis, 0L, 40L).toOption.get,
              PlaybackInterval.on(p2Axis, 0L, 40L).toOption.get,
              OccurrenceId.unsafe("p2-own")
            )
            .toOption
            .get
        ),
        SourceDerivationReceipt.of("p2-own/v1", "p2", Vector.empty).toOption.get
      )
      .toOption
      .get
    val p2: SourceBundle = SourceBundle
      .of(
        Some(p2Edition),
        SourceKind.FilmEdition,
        Vector(p2Picture, onComposed),
        p2Axis,
        Vector(picture.id),
        Vector(p2Own)
      )
      .fold(e => fail(s"part p2: ${e.message}"), identity)
    private val receipt = SourceDerivationReceipt
      .of("p2-composition/v1", "a then p2", Vector(a.identity, p2.identity))
      .toOption
      .get
    private def segment(from: PresentationAxis, start: Long, label: String) = CompositionSegment
      .of(
        PlaybackInterval.on(from, 0L, 40L).toOption.get,
        PlaybackInterval.on(composedAxis, start, start + 40L).toOption.get,
        OccurrenceId.unsafe(label)
      )
      .toOption
      .get
    private val mappings = Vector(
      TrackComposition
        .of(a.primaryAxis.id, composedAxis.id, Vector(segment(a.primaryAxis, 0L, "a")), receipt)
        .toOption
        .get,
      TrackComposition
        .of(p2Axis.id, composedAxis.id, Vector(segment(p2Axis, 40L, "p2")), receipt)
        .toOption
        .get
    )
    val composedBundle: SourceBundle = SourceBundle
      .of(
        Some(cEdition),
        SourceKind.FilmEdition,
        a.streams ++ Vector(p2Picture, onComposed),
        composedAxis,
        authority,
        mappings
      )
      .fold(e => fail(s"composed bundle: ${e.message}"), identity)
    val declared: DeclaredComposition = DeclaredComposition
      .of(composedBundle, NonEmptyVector.of(a, p2))
      .fold(e => fail(s"declared composition: ${e}"), identity)
    def onComposedAxis(owner: SourceBundle): SourceSupportStatus =
      val anchor = EvidenceAnchor.MediaTime(
        owner.id,
        onComposed.id,
        composedAxis.id,
        PlaybackIntervalSet.one(PlaybackInterval.on(composedAxis, 2L, 5L).toOption.get)
      )
      val support = EvidenceSupport
        .of(owner, Vector(anchor))
        .fold(e => fail(s"support on the composed axis: ${e.message}"), identity)
      SourceSupportStatus.located(TypedSupport.Anchored(support))
    def represent(owner: SourceBundle) = SourceRepresentation.of(
      view.copy(nodes = Vector(nodes.head)),
      NonEmptyVector.of(a, p2).map(BundleEntry.media),
      Some(declared),
      Map(e(1) -> onComposedAxis(owner))
    )

  test("a part with a stream on the composed axis does not take the composition's role"):
    import PartOnComposedAxis.*
    assertEquals(represent(p2).map(_ => ()), Left(MappingRefusal.ForeignAxis(composedAxis.id)))

  test("control: the composed bundle's own support through that stream is admitted"):
    import PartOnComposedAxis.*
    val result = represent(composedBundle)
    assert(result.isRight, result.left.toOption.map(_.toString).getOrElse(""))

  test("control: the declared composed bundle may carry support on a part's axis"):
    val result = SourceRepresentation.of(
      view.copy(nodes = Vector(nodes.head)),
      bundles,
      Some(composition),
      Map(e(1) -> onPartA(composed, a.streams.head.id))
    )
    assert(result.isRight, result.left.toOption.map(_.toString).getOrElse(""))

  test("control: a part's own support on its axis is admitted"):
    val result = SourceRepresentation.of(
      view.copy(nodes = Vector(nodes.head)),
      bundles,
      None,
      Map(e(1) -> onPartA(a, a.streams.head.id))
    )
    assert(result.isRight, result.left.toOption.map(_.toString).getOrElse(""))
