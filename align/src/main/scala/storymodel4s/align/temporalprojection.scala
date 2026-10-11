package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.recall.*

/** Binds per-unit conditional source grids to optional, explicitly declared recall-time exposure. */
object TemporalProjection:
  val Version: String = "temporal-projection/v1"

  /** An untrusted recall-clock window; its checksum must name the supplied checked clock. */
  final case class RecallWindow(clock: Checksum, start: ExactRational, endExclusive: ExactRational)

  /** Source bins are half-open ticks on the selected source axis. */
  final case class Request(
      selection: TemporalSupport.Selection,
      declarations: Vector[TemporalQuery.Declaration],
      bins: Vector[(Long, Long)],
      overlap: TemporalExposure.Overlap = TemporalExposure.Overlap.Refuse,
      recallWindow: Option[RecallWindow] = None
  )

  enum TimingState:
    case NotRequested(supplied: Boolean), Missing, Provided

  enum Refusal:
    case Selection(reason: TemporalSupport.Refusal)
    case Region(reason: TemporalQuery.Refusal)
    case Grid(reason: TemporalGrid.Refusal)
    case Exposure(reason: TemporalExposure.Refusal)
    case ForeignRecallWindow, DuplicateDeclaration
    case UnknownDeclaration(target: SourceNodeRef)

  /** A row always preserves the mapping unit and processing status; unavailable grids are explicit. */
  final class Row private[TemporalProjection] (
      val unit: RecallUnitId,
      val processing: ProcessingStatus,
      val grid: Either[Refusal, TemporalGrid],
      val exposure: Option[TemporalExposure.UnitReadout]
  ):
    private def fields = (unit, processing, grid, exposure)
    override def equals(other: Any): Boolean = other match
      case that: Row => fields == that.fields
      case _         => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"TemporalProjection.Row($unit,$processing)"

  /** Projection retains source rows even when a unit failed or has no requested measure. */
  final class Projection private[TemporalProjection] (
      val mapping: MappingResult,
      val measure: TemporalQuery.Measure,
      val request: Request,
      val timingState: TimingState,
      val rows: Vector[Row],
      val timing: Option[RecallTiming],
      val exposure: Option[TemporalExposure.Readout],
      val digest: Checksum
  ):
    override def equals(other: Any): Boolean = other match
      case that: Projection => digest == that.digest
      case _                => false
    override def hashCode(): Int = digest.hashCode
    override def toString: String =
      s"TemporalProjection(${digest.short()},${rows.size} rows,$timingState)"

  def compile(
      mapping: MappingResult,
      measure: TemporalQuery.Measure,
      request: Request,
      timing: Option[RecallTiming]
  ): Either[Refusal, Projection] =
    if request.declarations.map(_.target).distinct.size != request.declarations.size then
      Left(Refusal.DuplicateDeclaration)
    else request.declarations.find(d => mapping.source.target(d.target).isEmpty) match
      case Some(declaration) => Left(Refusal.UnknownDeclaration(declaration.target))
      case None => for
        coordinate <- TemporalSupport.select(mapping.source, request.selection).left.map(Refusal.Selection(_))
        regions <- request.bins.traverse { (start, endExclusive) =>
          TemporalQuery.Region
            .onTimeAxis(coordinate.axis, Vector(start -> endExclusive), Vector.empty)
            .left
            .map(Refusal.Region(_))
        }
        checkedExposure <- exposure(mapping, timing, request)
      yield
        val sourceRows = mapping.inventory.units.map { inventoryUnit =>
          val unitDeclarations = request.declarations.filter(d => measuredTargets(mapping, inventoryUnit.id, measure)(d.target))
          val result = for
            prepared <- TemporalQuery
              .prepare(mapping, inventoryUnit.id, measure, request.selection, unitDeclarations)
              .left
              .map(Refusal.Region(_))
            grid <- TemporalGrid.checked(prepared, regions).left.map(Refusal.Grid(_))
          yield grid
          new Row(
            inventoryUnit.id,
            mapping.outcome(inventoryUnit.id).get.processing,
            result,
            checkedExposure._2.flatMap(_.units.find(_.unit == inventoryUnit.id))
          )
        }
        val timingState = request.recallWindow match
          case None if timing.isDefined => TimingState.NotRequested(true)
          case None                     => TimingState.NotRequested(false)
          case Some(_) if timing.isEmpty => TimingState.Missing
          case Some(_)                  => TimingState.Provided
        val selection = request.selection match
          case TemporalSupport.Selection.Part(bundle) => s"part:${bundle.hex}"
          case TemporalSupport.Selection.Occurrence(mapping, occurrence) =>
            s"occurrence:${mapping.hex}:${occurrence.value}"
        val recallWindow = request.recallWindow.fold("absent")(w =>
          s"${w.clock.hex}:${w.start.numerator}/${w.start.denominator}:${w.endExclusive.numerator}/${w.endExclusive.denominator}"
        )
        val digest = MappingRender.digest(
          Vector(Version, mapping.digest.hex, measure.toString, coordinate.axis.id.value, selection,
            request.overlap.toString, recallWindow, timingState.toString) ++
            request.bins.flatMap((a, b) => Vector(a.toString, b.toString)) ++
            request.declarations.map(d => s"${d.target.key}:${d.assumption}:${d.allocation}") ++
            timing.toVector.map(_.digest.hex) ++ sourceRows.map(_.grid.toOption.map(_.digest.hex).getOrElse("unavailable")) ++
            checkedExposure._2.toVector.map(_.digest.hex)
        )
        new Projection(mapping, measure, request, timingState, sourceRows, timing, checkedExposure._2, digest)

  private def exposure(
      mapping: MappingResult,
      timing: Option[RecallTiming],
      request: Request
  ): Either[Refusal, (Option[TemporalExposure], Option[TemporalExposure.Readout])] =
    (request.recallWindow, timing) match
      case (Some(_), None) => Right(None -> None)
      case (None, None)    => Right(None -> None)
      case (None, Some(value)) =>
        TemporalExposure.checked(mapping, value, request.overlap).left.map(Refusal.Exposure(_)).map(v => Some(v) -> None)
      case (Some(window), Some(value)) =>
        for
          checked <- TemporalExposure.checked(mapping, value, request.overlap).left.map(Refusal.Exposure(_))
          _ <- Either.cond(window.clock == value.clock.digest, (), Refusal.ForeignRecallWindow)
          readout <- checked
            .query(value.clock, window.start, window.endExclusive)
            .left
            .map(Refusal.Exposure(_))
        yield Some(checked) -> Some(readout)

  private def measuredTargets(
      mapping: MappingResult,
      unit: RecallUnitId,
      measure: TemporalQuery.Measure
  ): Set[SourceNodeRef] =
    val destinations = mapping.outcome(unit).toVector.flatMap { outcome =>
      measure match
        case TemporalQuery.Measure.NormalizedScoreMass => outcome.measures.normalized.toVector.flatMap(_.mass.keys)
        case TemporalQuery.Measure.ModelPosterior => outcome.measures.posterior.toVector.flatMap(_.mass.keys.map(Destination.of))
        case TemporalQuery.Measure.TransportMass => outcome.measures.transport.toVector.flatMap(_.mass.keys)
    }
    destinations.collect { case Destination.Target(target) => target }.toSet
