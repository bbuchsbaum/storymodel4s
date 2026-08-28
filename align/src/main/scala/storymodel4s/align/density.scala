package storymodel4s.align

import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.recall.RecallUnitId

/** A recall unit's source-time density `a_i(t) = Σ_v P_iv h_v(t)` sampled on a grid over `[0, 1]`.
  * Total mass equals the row's source mass; width is the mass-weighted standard deviation; peaks
  * are local maxima above `peakThreshold`. This is the thin convenience view; [[Density.toTrack]]
  * materializes it as an aligned `features.FeatureTrack` over token windows with coverage.
  */
final case class Density(unit: RecallUnitId, grid: Vector[Double], values: Vector[Double]):
  def mass: Double = values.sum
  def mean: Double = if mass <= 0 then 0.0 else grid.zip(values).map(_ * _).sum / mass
  def width: Double =
    if mass <= 0 then 0.0
    else
      val mu = mean
      math.sqrt(grid.zip(values).map { case (g, v) => v * (g - mu) * (g - mu) }.sum / mass)
  def peaks(peakThreshold: Double = 0.0): Vector[Int] =
    values.indices.filter { k =>
      val v = values(k)
      v > peakThreshold &&
      (k == 0 || values(k - 1) < v) &&
      (k == values.size - 1 || values(k + 1) <= v)
    }.toVector

  /** The density as an aligned feature track: each grid cell becomes the token window it covers in
    * a surface sequence of `tokenCount` tokens; the observation carries the cell's mass, and its
    * coverage records how many source nodes contributed positive mass out of those in the row.
    */
  def toTrack(
      tokenCount: Int,
      contributing: Int,
      eligible: Int,
      provenance: TrackProvenance
  ): FeatureTrack[FeatureTarget.Window, Double] =
    val n = grid.size
    val obs: Vector[FeatureObservation[FeatureTarget.Window, Double]] =
      grid.indices.toVector.flatMap { k =>
        val start = math.min(tokenCount, (k.toDouble / n * tokenCount).floor.toInt)
        val end = math.min(tokenCount, ((k + 1).toDouble / n * tokenCount).floor.toInt)
        if end <= start then None
        else
          Some(
            FeatureObservation[FeatureTarget.Window, Double](
              FeatureTarget.Window(TokenRange.unsafe(start, end)),
              Estimate.observed(values(k)),
              None,
              Coverage.of(eligible, math.min(eligible, contributing)).toOption
            )
          )
      }
    FeatureTrack(Density.space(unit), obs, Some(Density.derivation), provenance)

object Density:
  val SpaceFingerprint: Fingerprint = Fingerprint.unsafe("storymodel4s:align:support-density:0.1")

  def space(unit: RecallUnitId): FeatureSpace[Double] =
    FeatureSpace[Double](
      FeatureSpaceId.unsafe(s"align.support-density.${unit.value}"),
      "posterior source-time density of one recall unit over discourse position",
      FeatureValueSchema.Scalar(Some("posterior mass")),
      Some("posterior mass"),
      SpaceFingerprint,
      normalized = false
    )

  val derivation: FeatureDerivation = FeatureDerivation(
    cats.data.NonEmptyVector.one(FeatureSpaceId.unsafe("align.posterior")),
    None,
    ReducerId.unsafe("kernel-density"),
    WeightingPolicy.Kernel("gaussian", 0.03),
    MissingValuePolicy.IgnoreMissing,
    None,
    "support-density-1"
  )

object SupportDensity:

  /** Soft support derived (never stored) from exact spans: a Gaussian kernel of `bandwidth` (in
    * units of relative discourse position) around each node's support midpoint.
    */
  def discourse(
      posterior: AlignmentMatrix,
      view: SourceView,
      grid: Int = 50,
      bandwidth: Double = 0.03
  ): Vector[Density] =
    build(posterior, grid, bandwidth, ref => Some(view.relativePosition(ref)))

  /** World-time variant using the source's world order ranks when known. */
  def worldTime(
      posterior: AlignmentMatrix,
      view: SourceView,
      grid: Int = 50,
      bandwidth: Double = 0.03
  ): Option[Vector[Density]] =
    view.worldOrder.map { order =>
      val maxRank = math.max(1, order.values.maxOption.getOrElse(1))
      build(posterior, grid, bandwidth, ref => order.get(ref).map(_.toDouble / maxRank))
    }

  private def build(
      posterior: AlignmentMatrix,
      grid: Int,
      bandwidth: Double,
      position: SourceNodeRef => Option[Double]
  ): Vector[Density] =
    val g = (0 until grid).toVector.map(k => (k + 0.5) / grid)
    posterior.rows.map { row =>
      val contributions = row.mass.toVector.collect {
        case (AlignState.Source(ref), m) if m > 0 =>
          position(ref).map(p => (p, m))
      }.flatten
      val values = g.map { x =>
        contributions.map { case (p, m) =>
          val k = g.map(y => math.exp(-0.5 * ((y - p) / bandwidth) * ((y - p) / bandwidth)))
          val z = k.sum
          if z <= 0 then 0.0
          else m * math.exp(-0.5 * ((x - p) / bandwidth) * ((x - p) / bandwidth)) / z
        }.sum
      }
      Density(row.unit, g, values)
    }

  /** Materialize every unit's density as an aligned feature track (design record §110). */
  def tracks(
      densities: Vector[Density],
      posterior: AlignmentMatrix,
      tokenCount: Int,
      provenance: TrackProvenance
  ): Vector[FeatureTrack[FeatureTarget.Window, Double]] =
    densities.map { d =>
      val row = posterior.row(d.unit)
      val eligible = row.map(_.mass.count(_._1.isSource)).getOrElse(0)
      val contributing = row.map(_.mass.count { case (s, m) => s.isSource && m > 0 }).getOrElse(0)
      d.toTrack(tokenCount, contributing, eligible, provenance)
    }
