package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.*

/** Inspection-only sparse cells retain original imported claims through cropping and rebinning. */
final class TemporalInspection private (
    val prepared: TemporalQuery.Inspection,
    val regions: Vector[TemporalQuery.Region],
    val cells: Map[Int, Vector[TemporalQuery.Contribution]],
    val allocated: Map[Int, Double],
    val bounds: Map[Int, (Double, Double)],
    val allocatedOutside: Double,
    val digest: Checksum
):
  def allocatedAt(bin: Int): Option[Double] =
    regions.lift(bin).map(_ => allocated.getOrElse(bin, 0.0))
  def boundsAt(bin: Int): Option[(Double, Double)] =
    regions.lift(bin).map(_ => bounds.getOrElse(bin, 0.0 -> 0.0))

  /** Rebin from the original supplied measure, including alternatives omitted from this grid. */
  def rebin(regions: Vector[TemporalQuery.Region]): Either[TemporalInspection.Refusal, TemporalInspection] =
    TemporalInspection.checked(prepared, regions)
  def allocationDiscrepancy: Double =
    allocated.toVector
      .sortBy(_._1)
      .map(_._2)
      .sum + allocatedOutside - prepared.ledger.allocatedSource
  override def equals(other: Any): Boolean = other match
    case that: TemporalInspection => digest == that.digest
    case _                  => false
  override def hashCode(): Int = digest.hashCode
  override def toString: String =
    s"TemporalInspection(${digest.hex},${regions.size} bins,${cells.values.map(_.size).sum} cells)"

object TemporalInspection:
  val Version: String = "imported-sparse-temporal-grid/v1"
  val MaximumBins: Int = TemporalGrid.MaximumBins

  /** Foreign and overlapping bins refuse before sparse cells acquire a shared identity. */
  enum Refusal:
    case InvalidGrid, TooManyBins, ForeignAxis, Overlap, NonFiniteAggregate
    case Query(reason: TemporalQuery.Refusal)

  /** Resource-bounded regular bins use exact integer arithmetic, including a short final bin. */
  def regular(
      prepared: TemporalQuery.Inspection,
      start: Long,
      endExclusive: Long,
      width: BigInt
  ): Either[Refusal, TemporalInspection] =
    val length = BigInt(endExclusive) - BigInt(start)
    if width <= 0 || length <= 0 then Left(Refusal.InvalidGrid)
    else if (length + width - 1) / width > MaximumBins then Left(Refusal.TooManyBins)
    else
      val starts = Vector.iterate(BigInt(start), ((length + width - 1) / width).toInt)(_ + width)
      starts
        .traverse { a =>
          TemporalQuery.Region
            .onTimeAxis(
              prepared.coordinate.axis,
              Vector(a.toLong -> (a + width).min(BigInt(endExclusive)).toLong),
              Vector.empty
            )
            .left
            .map(Refusal.Query(_))
        }
        .flatMap(checked(prepared, _))

  /** Only cells with possible support or allocated quantity are materialized; zero bins survive. */
  def checked(
      prepared: TemporalQuery.Inspection,
      regions: Vector[TemporalQuery.Region]
  ): Either[Refusal, TemporalInspection] =
    if regions.size > MaximumBins then Left(Refusal.TooManyBins)
    else if regions.exists(_.axis != prepared.coordinate.axis) then Left(Refusal.ForeignAxis)
    else if overlaps(regions) then Left(Refusal.Overlap)
    else
      val summaries = regions.traverse { region =>
        prepared.query(region).left.map(Refusal.Query(_)).map { readout =>
          val kept = readout.contributions
            .filter(c => c.resolvedBounds.exists(_.upper > 0.0) || c.allocated.exists(_ > 0.0))
          (
            kept,
            readout.allocatedRegionMass,
            readout.resolvedBounds.lower -> readout.resolvedBounds.upper,
            readout.digest
          )
        }
      }
      for
        bins <- summaries
        _ <- Either.cond(bins.map(_._2).sum.isFinite, (), Refusal.NonFiniteAggregate)
        union <- TemporalQuery.Region
          .onTimeAxis(
            prepared.coordinate.axis,
            regions.flatMap(
              _.geometry.toVector.flatMap(_.intervals).map(i => i.start -> i.endExclusive)
            ),
            regions.flatMap(_.geometry.toVector.flatMap(_.points).map(_.at))
          )
          .left
          .map(Refusal.Query(_))
        whole <- prepared.partition(Vector(union)).left.map(Refusal.Query(_))
      yield
        val identity =
          MappingRender.digest(Vector(Version, prepared.digest.hex) ++ bins.map(_._4.hex))
        new TemporalInspection(
          prepared,
          regions,
          bins.zipWithIndex.collect {
            case ((values, _, _, _), i) if values.nonEmpty => i -> values
          }.toMap,
          bins.zipWithIndex.collect {
            case ((_, value, _, _), i) if value != 0.0 => i -> value
          }.toMap,
          bins.zipWithIndex.collect {
            case ((_, _, value, _), i) if value._2 != 0.0 => i -> value
          }.toMap,
          whole.allocatedOutside,
          identity
        )

  private def overlaps(regions: Vector[TemporalQuery.Region]): Boolean =
    val spans = regions.zipWithIndex
      .flatMap { (region, index) =>
        region.geometry.toVector.flatMap(g =>
          g.intervals.map(i => (i.start, i.endExclusive, false, index)) ++
            g.points.map(p => (p.at, p.at, true, index))
        )
      }
      .sortBy(s => (s._1, s._2, s._4))
    var active = Vector.empty[(Long, Boolean, Int)]
    spans.exists { (start, end, point, index) =>
      active = active.filter((end, point, _) => end > start || (point && end == start))
      val conflict = active.exists(_._3 != index)
      active = active :+ ((end, point, index))
      conflict
    }
