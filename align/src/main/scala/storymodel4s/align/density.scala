package storymodel4s.align

import storymodel4s.recall.RecallUnitId

/** A recall unit's source-time density `a_i(t) = Σ_v P_iv h_v(t)` sampled on a grid over `[0, 1]`.
  * Total mass equals the row's source mass; width is the mass-weighted standard deviation; peaks
  * are local maxima above `peakThreshold`.
  *
  * INTEGRATION: replaced by storymodel4s.features (aligned `FeatureTrack` with coverage).
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
