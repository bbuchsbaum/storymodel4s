package storymodel4s.align

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

/** A stream may declare another bundle's axis as its native axis (core admits it;
  * bd-01M5MZ8J3KV0S1JY45737X4WSH). Equal axes are the same footage only for the axis's own bundle
  * or a declared composition of it, so support from a borrowing bundle must not count as a part's.
  */
class BorrowedAxisSuite extends FunSuite:
  import SherlockShapedSource.*

  // A film whose only picture stream borrows part a's primary axis.
  private val borrowed: SourceBundle =
    val stream = SourceStream
      .of(
        StreamId.unsafe("borrowed-picture"),
        StreamKind.Picture,
        Checksum.ofText("borrowed-picture"),
        a.primaryAxis.id,
        a.primaryAxis.extent,
        a.primaryAxis.timebase,
        Vector.empty
      )
      .toOption
      .get
    val edition = EditionId.unsafe("borrowed-film")
    val axis = SourceBundle
      .editionPlaybackAxis(edition, Vector(stream), Vector(stream.id), 0L, 40L, timebase)
      .toOption
      .get
    SourceBundle
      .of(
        Some(edition),
        SourceKind.FilmEdition,
        Vector(stream),
        axis,
        Vector(stream.id),
        Vector.empty
      )
      .fold(e => fail(s"borrowed-axis film: ${e.message}"), identity)

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

  test("support from a bundle that borrows a part's axis is refused as foreign"):
    val listed = NonEmptyVector.of(a, b, borrowed).map(BundleEntry.media)
    val result = SourceRepresentation.of(
      view.copy(nodes = Vector(nodes.head)),
      listed,
      None,
      Map(e(1) -> onPartA(borrowed, borrowed.streams.head.id))
    )
    assertEquals(result.map(_ => ()), Left(MappingRefusal.ForeignAxis(a.primaryAxis.id)))

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
