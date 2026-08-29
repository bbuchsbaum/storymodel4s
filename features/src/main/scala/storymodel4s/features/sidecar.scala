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
  * reference and what integrity checks verify.
  */
final case class SidecarManifest(
    space: FeatureSpaceId,
    dimension: Int,
    rowCount: Int,
    dtype: Dtype,
    checksum: Checksum,
    layout: Layout = Layout.RowMajor
):
  def bytesPerValue: Int = dtype match
    case Dtype.Float32 => 4
    case Dtype.Float64 => 8

  /** Computes the payload size without permitting a wrapped byte count. */
  def expectedByteLength: Either[DomainError, Long] =
    SidecarManifest.expectedByteLength(this)

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

  def validated(m: SidecarManifest): Either[DomainError, SidecarManifest] =
    for
      _ <- m.expectedByteLength
      _ <- m.layout match
        case Layout.RowMajor                 => Right(())
        case Layout.BlockedRowMajor(rows, _) => SidecarBlockRows.from(rows.value).map(_ => ())
    yield m

/** Reference from a target to a row of a sidecar. */
final case class FeatureRef(target: FeatureTarget, space: FeatureSpaceId, row: Int)

object FeatureRef:
  def validated(ref: FeatureRef, manifest: SidecarManifest): Either[DomainError, FeatureRef] =
    val path = s"features/ref/${ref.space.value}/${ref.row}"
    if ref.space != manifest.space then
      Left(
        DomainError
          .InvariantViolation(path, s"space mismatch with manifest ${manifest.space.value}")
      )
    else if ref.row < 0 || ref.row >= manifest.rowCount then
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
