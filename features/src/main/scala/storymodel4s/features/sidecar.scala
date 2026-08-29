package storymodel4s.features

import storymodel4s.core.*

enum Dtype:
  case Float32, Float64

/** A positive count of complete compact rows in one independently checked sidecar block.
  *
  * Why: storage blocks must never split a logical feature row, and invalid zero/negative
  * granularities must be rejected before any byte-layout arithmetic.
  */
opaque type SidecarBlockRows = Int

object SidecarBlockRows:
  /** Check an explicit storage granularity; the library deliberately supplies no guessed default.
    */
  def from(value: Int): Either[DomainError, SidecarBlockRows] =
    Either.cond(
      value > 0,
      value,
      DomainError.InvariantViolation(
        "features/sidecar/layout/rowsPerBlock",
        "rows per block must be positive"
      )
    )

  extension (rows: SidecarBlockRows) def value: Int = rows

/** The physical organization and independently trusted range index of a numeric sidecar.
  *
  * Why: a blocked layout keeps global feature-row identity independent of Atlas/Codex semantic
  * tiles while carrying the manifest-rooted checksum needed to verify partial range reads.
  */
enum Layout:
  case RowMajor
  case BlockedRowMajor(rowsPerBlock: SidecarBlockRows, indexChecksum: Checksum)

/** Description of a binary vector sidecar: one row per target, never written into canonical JSON.
  *
  * Why: dense embeddings live outside the graph payload; the manifest is what canonical artifacts
  * reference and what integrity checks verify. Not a case class: a companion `fromProduct` would
  * mint a non-positive dimension or overflowing byte length that `of` refuses.
  */
final class SidecarManifest private (
    val space: FeatureSpaceId,
    val dimension: Int,
    val rowCount: Int,
    val dtype: Dtype,
    val checksum: Checksum,
    val layout: Layout
):
  def bytesPerValue: Int = dtype match
    case Dtype.Float32 => 4
    case Dtype.Float64 => 8

  /** Computes the payload size without permitting a wrapped byte count. */
  def expectedByteLength: Either[DomainError, Long] =
    SidecarManifest.expectedByteLength(this)

  override def equals(other: Any): Boolean = other match
    case that: SidecarManifest =>
      space == that.space && dimension == that.dimension && rowCount == that.rowCount &&
      dtype == that.dtype && checksum == that.checksum && layout == that.layout
    case _ => false

  override def hashCode(): Int =
    (space, dimension, rowCount, dtype, checksum, layout).##

  override def toString: String =
    s"SidecarManifest(space=${space.value}, dimension=$dimension, rowCount=$rowCount, dtype=$dtype)"

object SidecarManifest:
  private def path(m: SidecarManifest): String = s"features/sidecar/${m.space.value}"

  private def expectedByteLength(m: SidecarManifest): Either[DomainError, Long] =
    val errorPath = path(m)
    if m.dimension <= 0 then
      Left(DomainError.InvariantViolation(errorPath, "non-positive dimension"))
    else if m.rowCount < 0 then
      Left(DomainError.InvariantViolation(errorPath, "negative row count"))
    else
      val valueCount = m.dimension.toLong * m.rowCount.toLong
      val bytesPerValue = m.bytesPerValue.toLong
      if valueCount > Long.MaxValue / bytesPerValue then
        Left(DomainError.InvariantViolation(errorPath, "expected byte length exceeds Long range"))
      else Right(valueCount * bytesPerValue)

  private def checks(m: SidecarManifest): Either[DomainError, SidecarManifest] =
    for
      _ <- m.expectedByteLength
      _ <- m.layout match
        case Layout.RowMajor                 => Right(())
        case Layout.BlockedRowMajor(rows, _) => SidecarBlockRows.from(rows.value).map(_ => ())
    yield m

  def of(
      space: FeatureSpaceId,
      dimension: Int,
      rowCount: Int,
      dtype: Dtype,
      checksum: Checksum,
      layout: Layout = Layout.RowMajor
  ): Either[DomainError, SidecarManifest] =
    checks(new SidecarManifest(space, dimension, rowCount, dtype, checksum, layout))

  def unsafe(
      space: FeatureSpaceId,
      dimension: Int,
      rowCount: Int,
      dtype: Dtype,
      checksum: Checksum,
      layout: Layout = Layout.RowMajor
  ): SidecarManifest =
    of(space, dimension, rowCount, dtype, checksum, layout)
      .fold(e => throw new IllegalArgumentException(e.message), identity)

  def validated(m: SidecarManifest): Either[DomainError, SidecarManifest] = checks(m)

/** Reference from a target to a row of a sidecar.
  *
  * Why a non-case class: a row index is part of the type's meaning, and `fromProduct` would mint a
  * negative row that pairing with a manifest then has to refuse. Pairing (space match, row in
  * `[0, rowCount)`) stays on [[FeatureRef.validated]]; construction only refuses what the fields
  * themselves can decide.
  */
final class FeatureRef private (
    val target: FeatureTarget,
    val space: FeatureSpaceId,
    val row: Int
):
  override def equals(other: Any): Boolean = other match
    case that: FeatureRef =>
      target == that.target && space == that.space && row == that.row
    case _ => false

  override def hashCode(): Int = (target, space, row).##

  override def toString: String =
    s"FeatureRef(target=$target, space=${space.value}, row=$row)"

object FeatureRef:
  def of(
      target: FeatureTarget,
      space: FeatureSpaceId,
      row: Int
  ): Either[DomainError, FeatureRef] =
    if row < 0 then
      Left(
        DomainError.InvariantViolation(
          s"features/ref/${space.value}/$row",
          "row must be non-negative"
        )
      )
    else Right(new FeatureRef(target, space, row))

  def unsafe(target: FeatureTarget, space: FeatureSpaceId, row: Int): FeatureRef =
    of(target, space, row).fold(e => throw new IllegalArgumentException(e.message), identity)

  def validated(ref: FeatureRef, manifest: SidecarManifest): Either[DomainError, FeatureRef] =
    val path = s"features/ref/${ref.space.value}/${ref.row}"
    if ref.space != manifest.space then
      Left(
        DomainError
          .InvariantViolation(path, s"space mismatch with manifest ${manifest.space.value}")
      )
    else if ref.row >= manifest.rowCount then
      Left(DomainError.InvariantViolation(path, s"row outside [0, ${manifest.rowCount})"))
    else Right(ref)

  def validatedAll(
      refs: Iterable[FeatureRef],
      manifests: Map[FeatureSpaceId, SidecarManifest]
  ): Either[DomainError, Unit] =
    refs.foldLeft[Either[DomainError, Unit]](Right(())) { (acc, r) =>
      acc.flatMap { _ =>
        manifests.get(r.space) match
          case None =>
            Left(
              DomainError.InvariantViolation(
                s"features/ref/${r.space.value}",
                "no manifest for space"
              )
            )
          case Some(m) => validated(r, m).map(_ => ())
      }
    }
