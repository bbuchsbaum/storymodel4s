package storymodel4s.align

import storymodel4s.core.*

/** Concentration is conditional on declared allocation, with exact tick geometry and full ledgers.
  */
object TemporalConcentration:
  val Version: String = "temporal-concentration/tick-lattice/v1"

  /** Conditioning is explicit so a source-only fraction cannot masquerade as whole-row coverage. */
  enum Target:
    case AbsoluteQuantity(value: Double)
    case ShareOfSupplied(value: Double)
    case ShareOfAllocatedSource(value: Double)

  /** Refusals preserve missing allocation, unattainable quantity and absent conditioning
    * separately.
    */
  enum Refusal:
    case InvalidWidth, InvalidTarget, UndefinedConditioning, UnavailableAllocation, UnsupportedAxis
    case Unattainable(requested: Double, allocatable: Double)
    case Query(reason: TemporalQuery.Refusal)

  /** Marginal concentration cannot supply a joint path probability. */
  enum JointStatus:
    case UnavailableWithoutJointEvidence
  val jointStatus: JointStatus = JointStatus.UnavailableWithoutJointEvidence

  /** A connected interval result carries the complete prepared row and its unallocated ledgers. */
  final class Window private[TemporalConcentration] (
      val readout: TemporalQuery.Readout,
      val width: BigInt,
      val target: Option[Target],
      val digest: Checksum
  ):
    override def equals(other: Any): Boolean = other match
      case that: Window => digest == that.digest
      case _            => false
    override def hashCode(): Int = digest.hashCode
    override def toString: String = s"TemporalConcentration.Window(${digest.hex},$width)"

  /** Disconnected sets are unions of declared grid cells, never claims of sub-cell precision. */
  final class Disconnected private[TemporalConcentration] (
      val grid: TemporalGrid,
      val selected: Vector[Int],
      val quantity: Double,
      val target: Target,
      val digest: Checksum
  ):
    override def equals(other: Any): Boolean = other match
      case that: Disconnected => digest == that.digest
      case _                  => false
    override def hashCode(): Int = digest.hashCode
    override def toString: String = s"TemporalConcentration.Disconnected(${digest.hex})"

  /** Imported concentration retains the exact supplied row and its inspection-only identity. */
  final class InspectionWindow private[TemporalConcentration] (
      val readout: TemporalQuery.InspectionReadout,
      val width: BigInt,
      val target: Option[Target],
      val digest: Checksum
  ):
    private def fields = (readout, width, target, digest)
    override def equals(other: Any): Boolean = other match
      case that: InspectionWindow => fields == that.fields
      case _                      => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String =
      s"TemporalConcentration.InspectionWindow(${digest.short()},$width)"

  /** Maximize on integer start ticks; equal numerical quantities choose the earliest start. */
  def bestWindow(prepared: TemporalQuery.Prepared, width: BigInt): Either[Refusal, Window] =
    bestReadout(
      prepared.coordinate.axis,
      prepared.domains.flatMap(_.availability.toOption),
      prepared.ledger,
      width,
      r => prepared.query(r).left.map(Refusal.Query(_)),
      (r: TemporalQuery.Readout) => r.allocatedRegionMass
    ).map(window(_, width, None))

  /** Same lattice calculation over imported claims, without constructing a live mapping result. */
  def bestInspectionWindow(
      prepared: TemporalQuery.Inspection,
      width: BigInt
  ): Either[Refusal, InspectionWindow] =
    val geometry = prepared.components.flatMap(_.location match
      case TemporalQuery.Location.Resolved(domain) => domain.availability.toOption
      case _                                       => None)
    bestReadout(
      prepared.coordinate.axis,
      geometry,
      prepared.ledger,
      width,
      r => prepared.query(r).left.map(Refusal.Query(_)),
      (r: TemporalQuery.InspectionReadout) => r.allocatedRegionMass
    )
      .map(inspectionWindow(_, width, None))

  private def bestReadout[A](
      axis: PresentationAxis,
      geometry: Vector[PlaybackSupport],
      ledger: TemporalQuery.Ledger,
      width: BigInt,
      query: TemporalQuery.Region => Either[Refusal, A],
      mass: A => Double
  ): Either[Refusal, A] =
    if ledger.supportWithoutAllocation > 0.0 then Left(Refusal.UnavailableAllocation)
    else
      axis.extent match
        case extent: AxisExtent.PlaybackTicks =>
          val lo = BigInt(extent.start)
          val hi = BigInt(extent.endExclusive)
          if width <= 0 || width > hi - lo then Left(Refusal.InvalidWidth)
          else
            val edges = geometry.flatMap(g =>
              g.intervals.flatMap(i => Vector(BigInt(i.start), BigInt(i.endExclusive))) ++
                g.points.flatMap(p => Vector(BigInt(p.at), BigInt(p.at) + 1))
            )
            val starts = (Vector(lo, hi - width) ++ edges ++ edges.map(_ - width))
              .map(_.max(lo).min(hi - width))
              .distinct
              .sorted
            val windows = starts.map { start =>
              TemporalQuery.Region
                .onTimeAxis(axis, Vector(start.toLong -> (start + width).toLong), Vector.empty)
                .left
                .map(Refusal.Query(_))
                .flatMap(query)
            }
            windows.collectFirst { case Left(error) => error } match
              case Some(error) => Left(error)
              case None        =>
                val results = windows.flatMap(_.toOption)
                Right(
                  results.tail.foldLeft(results.head)((a, b) => if mass(b) > mass(a) then b else a)
                )
        case _ => Left(Refusal.UnsupportedAxis)

  /** Shortest connected lattice interval attaining an explicitly conditioned quantity. */
  def shortestWindow(prepared: TemporalQuery.Prepared, target: Target): Either[Refusal, Window] =
    shortestWidth(
      prepared.coordinate.axis,
      prepared.ledger,
      target,
      width => bestWindow(prepared, width).map(_.readout.allocatedRegionMass)
    ).flatMap { width =>
      if width == 0 then
        TemporalQuery.Region
          .onTimeAxis(prepared.coordinate.axis, Vector.empty, Vector.empty)
          .flatMap(prepared.query)
          .left
          .map(Refusal.Query(_))
          .map(window(_, width, Some(target)))
      else bestWindow(prepared, width).map(v => window(v.readout, width, Some(target)))
    }

  /** Conditional precision over supplied claims; omitted and unallocated quantities survive. */
  def shortestInspectionWindow(
      prepared: TemporalQuery.Inspection,
      target: Target
  ): Either[Refusal, InspectionWindow] =
    shortestWidth(
      prepared.coordinate.axis,
      prepared.ledger,
      target,
      width => bestInspectionWindow(prepared, width).map(_.readout.allocatedRegionMass)
    ).flatMap { width =>
      if width == 0 then
        TemporalQuery.Region
          .onTimeAxis(prepared.coordinate.axis, Vector.empty, Vector.empty)
          .flatMap(prepared.query)
          .left
          .map(Refusal.Query(_))
          .map(inspectionWindow(_, width, Some(target)))
      else
        bestInspectionWindow(prepared, width).map(v =>
          inspectionWindow(v.readout, width, Some(target))
        )
    }

  private def shortestWidth(
      axis: PresentationAxis,
      ledger: TemporalQuery.Ledger,
      target: Target,
      bestMass: BigInt => Either[Refusal, Double]
  ): Either[Refusal, BigInt] = for
    needed <- quantity(ledger, target)
    _ <- Either.cond(ledger.supportWithoutAllocation == 0.0, (), Refusal.UnavailableAllocation)
    extent <- axis.extent match
      case e: AxisExtent.PlaybackTicks => Right(e)
      case _                           => Left(Refusal.UnsupportedAxis)
    _ <- Either.cond(
      needed <= ledger.allocatedSource,
      (),
      Refusal.Unattainable(needed, ledger.allocatedSource)
    )
    result <-
      if needed == 0 then Right(BigInt(0))
      else
        var lo = BigInt(1)
        var hi = BigInt(extent.endExclusive) - BigInt(extent.start)
        var error: Option[Refusal] = None
        while lo < hi && error.isEmpty do
          val mid = (lo + hi) / 2
          bestMass(mid) match
            case Left(reason) => error = Some(reason)
            case Right(mass)  => if mass >= needed then hi = mid else lo = mid + 1
        error.toLeft(lo)
  yield result

  /** Highest-quantity grid cells first; ties keep original bin order and all omitted mass. */
  def disconnected(grid: TemporalGrid, target: Target): Either[Refusal, Disconnected] =
    for
      needed <- quantity(grid.prepared.ledger, target)
      _ <- Either.cond(
        grid.prepared.ledger.supportWithoutAllocation == 0.0,
        (),
        Refusal.UnavailableAllocation
      )
      available = grid.allocated.toVector.sortBy(_._1).map(_._2).sum
      _ <- Either.cond(needed <= available, (), Refusal.Unattainable(needed, available))
    yield
      val ranked = grid.allocated.keys.toVector.sortBy(i => (-grid.allocated(i), i))
      var total = 0.0
      val selected = ranked.takeWhile { i =>
        val take = total < needed
        if take then total += grid.allocated(i)
        take
      }.sorted
      val identity = MappingRender.digest(
        Vector(Version, "disconnected", grid.digest.hex, targetKey(target)) ++ selected.map(
          _.toString
        )
      )
      new Disconnected(grid, selected, total, target, identity)

  private def quantity(ledger: TemporalQuery.Ledger, target: Target): Either[Refusal, Double] =
    val (value, scale, share) = target match
      case Target.AbsoluteQuantity(v)       => (v, 1.0, false)
      case Target.ShareOfSupplied(v)        => (v, ledger.suppliedTotal, true)
      case Target.ShareOfAllocatedSource(v) => (v, ledger.allocatedSource, true)
    if !value.isFinite || value < 0.0 || (share && value > 1.0) || !(value * scale).isFinite then
      Left(Refusal.InvalidTarget)
    else if share && scale == 0.0 then Left(Refusal.UndefinedConditioning)
    else Right(value * scale)
  private def targetKey(target: Target): String = target match
    case Target.AbsoluteQuantity(v) => "quantity:" + storymodel4s.features.CanonicalDouble.render(v)
    case Target.ShareOfSupplied(v)  => "supplied:" + storymodel4s.features.CanonicalDouble.render(v)
    case Target.ShareOfAllocatedSource(v) =>
      "allocated:" + storymodel4s.features.CanonicalDouble.render(v)
  private def window(
      readout: TemporalQuery.Readout,
      width: BigInt,
      target: Option[Target]
  ): Window =
    val digest = MappingRender.digest(
      Vector(Version, "window", readout.digest.hex, width.toString) ++ target.toVector.map(
        targetKey
      )
    )
    new Window(readout, width, target, digest)

  private def inspectionWindow(
      readout: TemporalQuery.InspectionReadout,
      width: BigInt,
      target: Option[Target]
  ): InspectionWindow =
    val digest = MappingRender.digest(
      Vector(Version, "inspection-window", readout.digest.hex, width.toString) ++
        target.toVector.map(targetKey)
    )
    new InspectionWindow(readout, width, target, digest)
