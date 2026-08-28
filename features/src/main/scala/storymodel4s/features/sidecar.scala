package storymodel4s.features

import storymodel4s.core.*

enum Dtype:
  case Float32, Float64

enum Layout:
  case RowMajor

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
  def expectedByteLength: Long = dimension.toLong * rowCount.toLong * bytesPerValue

object SidecarManifest:
  def validated(m: SidecarManifest): Either[DomainError, SidecarManifest] =
    val path = s"features/sidecar/${m.space.value}"
    if m.dimension <= 0 then Left(DomainError.InvariantViolation(path, "non-positive dimension"))
    else if m.rowCount < 0 then Left(DomainError.InvariantViolation(path, "negative row count"))
    else Right(m)

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
