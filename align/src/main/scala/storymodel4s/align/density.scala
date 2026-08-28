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
  val SpaceFingerprint: Fingerprint = Fingerprint.unsafe("storymodel4s:align:support-density:0.2")

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
    ReducerId.unsafe("smoothed-box-density"),
    WeightingPolicy.Kernel("gaussian", 0.03),
    MissingValuePolicy.IgnoreMissing,
    None,
    "support-density-2"
  )

/** Soft support derived (never stored) from exact spans. Each node contributes its posterior mass
  * spread over its *support interval* — a box over `[start, end]` of its span hull, smoothed by a
  * Gaussian of `bandwidth` — so a correctly recalled summary reports the width of its segment, not
  * a spike at its midpoint (review #14). Every contribution is normalized once on the grid so the
  * unit's density integrates to its source mass (review #38).
  */
object SupportDensity:

  /** Densities of a gated result (the proof type; see [[HsmmResult]]). */
  def discourse(result: HsmmResult, view: SourceView): Vector[Density] =
    discourse(result.posterior, view)

  def discourse(
      result: HsmmResult,
      view: SourceView,
      grid: Int,
      bandwidth: Double
  ): Vector[Density] =
    discourse(result.posterior, view, grid, bandwidth)

  def worldTime(result: HsmmResult, view: SourceView): Option[Vector[Density]] =
    worldTime(result.posterior, view)

  /** Densities of a bare posterior. Unlike the [[HsmmResult]] overloads above, these accept any
    * well-formed `AlignmentMatrix` — including one built through [[AlignmentMatrix.of]] or the
    * baseline aligner's ablation output — and therefore carry no mode-gate proof. Scientific
    * consumers should use the `HsmmResult` overloads; these forms exist for ablations and
    * diagnostics.
    */
  def discourse(
      posterior: AlignmentMatrix,
      view: SourceView,
      grid: Int = 50,
      bandwidth: Double = 0.03
  ): Vector[Density] =
    build(posterior, grid, bandwidth, view.relativeSpan)

  /** World-time variant using the source's world order ranks when known. Ranked nodes occupy the
    * rank interval `[r, r+1)`; nodes without a rank (e.g. speech-scoped content) get a uniform
    * prior over `[0, 1]` rather than being dropped, so the density still integrates to the row's
    * source mass.
    */
  def worldTime(
      posterior: AlignmentMatrix,
      view: SourceView,
      grid: Int = 50,
      bandwidth: Double = 0.03
  ): Option[Vector[Density]] =
    view.worldOrder.map { order =>
      val maxRank = math.max(1, order.values.maxOption.getOrElse(0) + 1)
      build(
        posterior,
        grid,
        bandwidth,
        ref =>
          order.get(ref) match
            case Some(r) => Some((r.toDouble / maxRank, (r + 1).toDouble / maxRank))
            case None    => Some((0.0, 1.0))
      )
    }

  private def build(
      posterior: AlignmentMatrix,
      grid: Int,
      bandwidth: Double,
      span: SourceNodeRef => Option[(Double, Double)]
  ): Vector[Density] =
    val g = (0 until grid).toVector.map(k => (k + 0.5) / grid)
    val bw = math.max(bandwidth, 1e-6)
    // Smoothed box: Φ((x − a)/bw) − Φ((x − b)/bw) with a minimum width of one grid cell.
    def kernel(a: Double, b: Double): Vector[Double] =
      val lo = math.min(a, b)
      val hi = math.max(lo + 1.0 / grid, b)
      val raw = g.map(x => Gauss.cdf((x - lo) / bw) - Gauss.cdf((x - hi) / bw))
      val z = raw.sum
      if z <= 0.0 then Vector.fill(grid)(1.0 / grid) else raw.map(_ / z)
    posterior.rows.map { row =>
      val contributions = row.mass.toVector
        .sortBy(_._1.key)
        .flatMap { case (st, m) =>
          st.anchor.filter(_ => m > 0).flatMap(r => span(r).map(s => (s, m)))
        }
      val acc = Array.fill(grid)(0.0)
      contributions.foreach { case ((a, b), m) =>
        val k = kernel(a, b)
        var i = 0
        while i < grid do
          acc(i) += m * k(i)
          i += 1
      }
      Density(row.unit, g, acc.toVector)
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

/** Standard normal CDF via the Abramowitz–Stegun 7.1.26 erf approximation (|ε| < 1.5e−7). */
private[align] object Gauss:
  def erf(x: Double): Double =
    val sign = if x < 0 then -1.0 else 1.0
    val ax = math.abs(x)
    val t = 1.0 / (1.0 + 0.3275911 * ax)
    val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t +
      0.254829592) * t * math.exp(-ax * ax)
    sign * y
  def cdf(z: Double): Double = 0.5 * (1.0 + erf(z / math.sqrt(2.0)))
