package storymodel4s.core

/** Test-only construction of complete checked bundles; no production constructor is widened. */
object D1aBundleFixtures:
  private def right[A](value: Either[DomainError, A]): A = value.fold(e => throw new IllegalArgumentException(e.message), identity)
  /** Two native picture axes in one legacy bundle identity; either may be primary. */
  def twoAxes(): (SourceBundle, SourceBundle) =
    val seed = right(SourceBundle.filmEdition(EditionId.unsafe("seed"), Checksum.ofText("seed"), 0L, 100L, RationalTimebase.Millisecond))
    val edition = Some(EditionId.unsafe("two-axis-film"))
    val aId = StreamId.unsafe("picture-a")
    val bId = StreamId.unsafe("picture-b")
    def stream(id: StreamId, axis: PresentationAxis): SourceStream =
      right(SourceStream.of(id, StreamKind.Picture, Checksum.ofText(id.value), axis.id,
        axis.extent, axis.timebase, Vector.empty))
    val placeholders = Vector(stream(aId, seed.primaryAxis), stream(bId, seed.primaryAxis))
    val id = right(SourceBundle.computeId(edition, SourceKind.FilmEdition, placeholders,
      AxisKind.EditionPlayback, Vector(aId, bId)))
    val a = right(PresentationAxis.editionPlayback(id, edition.get, 0L, 100L, RationalTimebase.Millisecond))
    val b = right(PresentationAxis.editionPlayback(id, edition.get, 0L, 200L, RationalTimebase.Millisecond))
    val streams = Vector(stream(aId, a), stream(bId, b))
    def bundle(axis: PresentationAxis) = right(SourceBundle.of(edition, SourceKind.FilmEdition,
      streams, axis, Vector(aId, bId), Vector.empty))
    (bundle(a), bundle(b))

