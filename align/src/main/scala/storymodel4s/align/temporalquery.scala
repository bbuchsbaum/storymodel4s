package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.features.CanonicalDouble

/** Conditional temporal queries over a checked mapping; no new inference or calibration. */
object TemporalQuery:
  val Version: String = "conditional-temporal-query/v1"

  enum Measure:
    case NormalizedScoreMass, ModelPosterior

  /** A caller's scientific assumption, never inferred from support coverage or model confidence. */
  enum Assumption:
    case SuppliedSupportContainsReferent

  enum Allocation:
    case SupportOnly, UniformIntervals, UniformPoints
    case Mixture(intervalShare: Double)

  final case class Declaration(
      target: SourceNodeRef,
      assumption: Assumption,
      allocation: Allocation
  )

  enum Unavailable:
    case NoDeclaration, NoTemporalSupport, OtherCoordinates, ClippedOccurrence
    case MissingDescendant, DescendantOutsideDomain

  enum AllocationUnavailable:
    case SupportOnly, IncompatibleGeometry

  enum Refusal:
    case UnknownUnit, MissingMeasure, DuplicateDeclaration, UnmeasuredTarget, InvalidMixture
    case Processing(status: ProcessingStatus)
    case Support(reason: TemporalSupport.Refusal)
    case InvalidRegion, ForeignRegion, OverlappingRegions, InvalidTopK

  /** Posterior states retain their admitted fidelity mode instead of reducing to target IDs. */
  enum Alternative:
    case Score(value: Destination)
    case Posterior(state: AlignState)
    def destination: Destination = this match
      case Score(d)     => d
      case Posterior(s) => Destination.of(s)
    def key: String = this match
      case Score(d)     => MappingRender.sequence(Vector("score", d.key))
      case Posterior(s) => MappingRender.sequence(Vector("posterior") ++ AlignState.keyParts(s))

  final class Domain private[TemporalQuery] (
      val declaration: Declaration,
      val support: TemporalSupport,
      val availability: Either[Unavailable, PlaybackSupport],
      val allocation: Either[AllocationUnavailable, Allocation]
  )

  enum Location:
    case Resolved(domain: Domain)
    case Unresolved(reason: Unavailable)
    case External(state: ExternalState)

  final class Component private[TemporalQuery] (
      val alternative: Alternative,
      val weight: Double,
      val location: Location
  )

  /** Empty regions are valid. All geometry is canonical and checked against the actual axis. */
  final class Region private (val axis: PresentationAxis, val geometry: Option[PlaybackSupport])
  object Region:
    def on(
        axis: PresentationAxis,
        intervals: Vector[(Long, Long)],
        points: Vector[Long]
    ): Either[Refusal, Region] =
      for
        _ <- Either.cond(axis.kind == AxisKind.EditionPlayback, (), Refusal.InvalidRegion)
        is <- intervals.traverse((a, b) =>
          PlaybackInterval.on(axis, a, b).left.map(_ => Refusal.InvalidRegion)
        )
        ps <- points.traverse(p => PlaybackInstant.on(axis, p).left.map(_ => Refusal.InvalidRegion))
        geometry <-
          if is.isEmpty && ps.isEmpty then Right(None)
          else PlaybackSupport.of(axis.id, is, ps).left.map(_ => Refusal.InvalidRegion).map(Some(_))
      yield new Region(axis, geometry)

  final class Bounds private[TemporalQuery] (val lower: Double, val upper: Double)
  final class Contribution private[TemporalQuery] (
      val component: Component,
      val resolvedBounds: Option[Bounds],
      val allocated: Option[Double]
  )
  final class Ledger private[TemporalQuery] (
      val suppliedTotal: Double,
      val allocatedSource: Double,
      val supportWithoutAllocation: Double,
      val unavailableLocation: Double,
      val external: Vector[(ExternalState, Double)]
  ):
    /** Shape tolerance is not rescaling; even a small admitted discrepancy is preserved. */
    def rowSumDiscrepancy: Double = suppliedTotal - 1.0

  final class Readout private[TemporalQuery] (
      val prepared: Prepared,
      val region: Region,
      val contributions: Vector[Contribution],
      val resolvedBounds: Bounds,
      val allocatedRegionMass: Double,
      val digest: Checksum
  ):
    /** Rank original alternative weights, including external/unresolved alternatives; never
      * rescale.
      */
    def topK(k: Int): Either[Refusal, Ranked] =
      if k < 0 then Left(Refusal.InvalidTopK)
      else
        val ordered = contributions.sortBy(c => (-c.component.weight, c.component.alternative.key))
        Right(
          new Ranked(
            this,
            ordered.take(k),
            ordered.drop(k).map(_.component.weight).sum,
            ordered.drop(k).flatMap(_.allocated).sum
          )
        )

  final class Ranked private[TemporalQuery] (
      val readout: Readout,
      val retained: Vector[Contribution],
      val omittedWeight: Double,
      val omittedAllocatedRegionMass: Double
  )

  final class Partition private[TemporalQuery] (
      val prepared: Prepared,
      val bins: Vector[Readout],
      val allocatedOutside: Double
  ):
    /** Numerical summation diagnostic, not a license to renormalize bins. */
    def allocationDiscrepancy: Double =
      bins.map(_.allocatedRegionMass).sum + allocatedOutside - prepared.ledger.allocatedSource

  final class Prepared private[TemporalQuery] (
      val mapping: MappingResult,
      val unit: RecallUnitId,
      val measure: Measure,
      val stage: StageEntryId,
      val coordinate: TemporalSupport.Coordinate,
      val domains: Vector[Domain],
      val components: Vector[Component],
      val ledger: Ledger,
      val digest: Checksum
  ):
    /** Caller-declared disjoint regions; uncovered support and all nonallocated ledgers survive. */
    def partition(regions: Vector[Region]): Either[Refusal, Partition] =
      if regions.exists(_.axis != coordinate.axis) then Left(Refusal.ForeignRegion)
      else if regions.indices.exists(i =>
          regions.indices
            .drop(i + 1)
            .exists(j =>
              regions(i).geometry.exists(a => regions(j).geometry.exists(b => intersects(a, b)))
            )
        )
      then Left(Refusal.OverlappingRegions)
      else
        for
          bins <- regions.traverse(query)
          union <- Region.on(
            coordinate.axis,
            regions.flatMap(
              _.geometry.toVector.flatMap(_.intervals).map(i => i.start -> i.endExclusive)
            ),
            regions.flatMap(_.geometry.toVector.flatMap(_.points).map(_.at))
          )
        yield
          val outside = components.flatMap { c =>
            c.location match
              case Location.Resolved(d) =>
                d.allocation.toOption.map(a =>
                  c.weight * (1.0 - fraction(d.availability.toOption.get, union.geometry, a))
                )
              case _ => None
          }.sum
          new Partition(this, bins, outside)

    def query(region: Region): Either[Refusal, Readout] =
      if region.axis != coordinate.axis then Left(Refusal.ForeignRegion)
      else
        val contributions = components.map { c =>
          c.location match
            case Location.Resolved(domain) =>
              val support = domain.availability.toOption.get
              val lower = if region.geometry.exists(_.contains(support)) then c.weight else 0.0
              val upper = if region.geometry.exists(intersects(support, _)) then c.weight else 0.0
              val allocated = domain.allocation.toOption.map(a =>
                c.weight * fraction(support, region.geometry, a)
              )
              new Contribution(c, Some(new Bounds(lower, upper)), allocated)
            case _ => new Contribution(c, None, None)
        }
        val bounds = new Bounds(
          contributions.flatMap(_.resolvedBounds.map(_.lower)).sum,
          contributions.flatMap(_.resolvedBounds.map(_.upper)).sum
        )
        val identity =
          MappingRender.digest(Vector(Version, digest.hex, geometryKey(region.geometry)))
        Right(
          new Readout(
            this,
            region,
            contributions,
            bounds,
            contributions.flatMap(_.allocated).sum,
            identity
          )
        )

  def prepare(
      mapping: MappingResult,
      unit: RecallUnitId,
      measure: Measure,
      selection: TemporalSupport.Selection,
      declarations: Vector[Declaration]
  ): Either[Refusal, Prepared] =
    for
      outcome <- mapping.outcome(unit).toRight(Refusal.UnknownUnit)
      _ <- Either.cond(
        outcome.processing == ProcessingStatus.Complete,
        (),
        Refusal.Processing(outcome.processing)
      )
      row <- measure match
        case Measure.NormalizedScoreMass =>
          outcome.measures.normalized.toRight(Refusal.MissingMeasure).map { m =>
            (m.stage, m.mass.toVector.map((d, w) => (Alternative.Score(d), w)))
          }
        case Measure.ModelPosterior =>
          outcome.measures.posterior.toRight(Refusal.MissingMeasure).map { m =>
            (m.stage, m.mass.toVector.map((s, w) => (Alternative.Posterior(s), w)))
          }
      (stage, entries) = row
      targets = entries.map(_._1.destination).collect { case Destination.Target(ref) => ref }.toSet
      _ <- Either.cond(
        declarations.map(_.target).distinct.size == declarations.size,
        (),
        Refusal.DuplicateDeclaration
      )
      _ <- Either.cond(
        declarations.forall(d => targets.contains(d.target)),
        (),
        Refusal.UnmeasuredTarget
      )
      _ <- Either.cond(declarations.forall(d => valid(d.allocation)), (), Refusal.InvalidMixture)
      coordinate <- TemporalSupport.select(mapping.source, selection).left.map(Refusal.Support(_))
      domains <- declarations
        .sortBy(_.target.key)
        .traverse(d => domain(mapping.source, d, selection))
      byTarget = domains.map(d => d.declaration.target -> d).toMap
      components = entries.sortBy(_._1.key).map { (alternative, weight) =>
        val location = alternative.destination match
          case Destination.External(state) => Location.External(state)
          case Destination.Target(ref)     =>
            byTarget.get(ref) match
              case None    => Location.Unresolved(Unavailable.NoDeclaration)
              case Some(d) =>
                d.availability match
                  case Left(reason) => Location.Unresolved(reason)
                  case Right(_)     => Location.Resolved(d)
        new Component(alternative, weight, location)
      }
      ledger = accounting(components)
      identity = MappingRender.digest(
        Vector(
          Version,
          mapping.digest.hex,
          unit.value,
          measure.toString,
          selectionKey(selection),
          MappingRender.sequence(
            domains.map(d =>
              MappingRender.sequence(
                Vector(
                  d.declaration.target.key,
                  d.declaration.assumption.toString,
                  allocationKey(d.declaration.allocation)
                )
              )
            )
          )
        )
      )
    yield new Prepared(
      mapping,
      unit,
      measure,
      stage,
      coordinate,
      domains,
      components,
      ledger,
      identity
    )

  private def valid(allocation: Allocation): Boolean = allocation match
    case Allocation.Mixture(share) => share.isFinite && share >= 0.0 && share <= 1.0
    case _                         => true

  private def domain(
      source: SourceRepresentation,
      d: Declaration,
      selection: TemporalSupport.Selection
  ): Either[Refusal, Domain] =
    TemporalSupport.read(source, d.target, selection).left.map(Refusal.Support(_)).map { support =>
      def oneAxis(ref: SourceNodeRef): Boolean = source.target(ref).get.sourceSupport match
        case located: SourceSupportStatus.Located =>
          located.support match
            case TypedSupport.Anchored(e) =>
              e.anchors.toVector.forall(_.axisId.contains(support.coordinate.axis.id))
            case _ => false
        case _ => false
      val available =
        support.selected.included.toRight(Unavailable.NoTemporalSupport).flatMap { geometry =>
          if support.nodes.exists(_.excluded.nonEmpty) then Left(Unavailable.ClippedOccurrence)
          else if support.unavailableDescendants.nonEmpty then Left(Unavailable.MissingDescendant)
          else if !support.nodes.forall(n => oneAxis(n.ref)) then Left(Unavailable.OtherCoordinates)
          else if !support.nodes.forall(n => n.included.exists(geometry.contains)) then
            Left(Unavailable.DescendantOutsideDomain)
          else Right(geometry)
        }
      val allocation = available.toOption match
        case Some(g) if d.allocation != Allocation.SupportOnly && compatible(g, d.allocation) =>
          Right(d.allocation)
        case _ if d.allocation == Allocation.SupportOnly => Left(AllocationUnavailable.SupportOnly)
        case _ => Left(AllocationUnavailable.IncompatibleGeometry)
      new Domain(d, support, available, allocation)
    }

  private def compatible(g: PlaybackSupport, a: Allocation): Boolean = a match
    case Allocation.SupportOnly      => false
    case Allocation.UniformIntervals => g.intervals.nonEmpty && g.points.isEmpty
    case Allocation.UniformPoints    => g.points.nonEmpty && g.intervals.isEmpty
    case Allocation.Mixture(_)       => g.intervals.nonEmpty && g.points.nonEmpty

  private def accounting(components: Vector[Component]): Ledger =
    def total(p: Component => Boolean): Double = components.filter(p).map(_.weight).sum
    val allocated = total(_.location match
      case Location.Resolved(d) => d.allocation.isRight
      case _                    => false)
    val unallocated = total(_.location match
      case Location.Resolved(d) => d.allocation.isLeft
      case _                    => false)
    val unresolved = total(_.location.isInstanceOf[Location.Unresolved])
    val external = ExternalState.values.toVector.flatMap { state =>
      val rows = components.filter(_.location == Location.External(state))
      Option.when(rows.nonEmpty)(state -> rows.map(_.weight).sum)
    }
    new Ledger(components.map(_.weight).sum, allocated, unallocated, unresolved, external)

  private def contains(g: PlaybackSupport, p: Long): Boolean =
    g.points.exists(_.at == p) || g.intervals.exists(_.contains(p))
  private def intersects(a: PlaybackSupport, b: PlaybackSupport): Boolean =
    a.intervals.exists(i =>
      b.intervals.exists(j => i.start < j.endExclusive && j.start < i.endExclusive)
    ) ||
      a.points.exists(p => contains(b, p.at)) || b.points.exists(p => contains(a, p.at))

  private def fraction(
      s: PlaybackSupport,
      region: Option[PlaybackSupport],
      allocation: Allocation
  ): Double =
    // Integer geometry first: subtracting endpoints as Double loses ticks above 2^53.
    val duration = s.intervals.map(i => BigInt(i.endExclusive) - BigInt(i.start)).sum
    val overlap = region.toVector
      .flatMap(r =>
        s.intervals.flatMap(i =>
          r.intervals.map { j =>
            (BigInt(i.endExclusive.min(j.endExclusive)) - BigInt(i.start.max(j.start)))
              .max(BigInt(0))
          }
        )
      )
      .sum
    val hits = s.points.count(p => region.exists(contains(_, p.at)))
    def continuous: Double = overlap.toDouble / duration.toDouble
    def atomic: Double = hits.toDouble / s.points.size.toDouble
    allocation match
      case Allocation.UniformIntervals => continuous
      case Allocation.UniformPoints    => atomic
      case Allocation.Mixture(share)   => share * continuous + (1.0 - share) * atomic
      case Allocation.SupportOnly => throw new IllegalStateException("support-only has no kernel")

  private def allocationKey(a: Allocation): String = a match
    case Allocation.Mixture(share) =>
      MappingRender.sequence(Vector("mixture", CanonicalDouble.render(share)))
    case _ => a.toString
  private def selectionKey(s: TemporalSupport.Selection): String = s match
    case TemporalSupport.Selection.Part(bundle) =>
      MappingRender.sequence(Vector("part", bundle.hex))
    case TemporalSupport.Selection.Occurrence(mapping, occurrence) =>
      MappingRender.sequence(Vector("occurrence", mapping.hex, occurrence.value))
  private def geometryKey(value: Option[PlaybackSupport]): String = MappingRender.sequence(
    value.toVector.flatMap(g =>
      g.intervals.map(i =>
        MappingRender.sequence(Vector("interval", i.start.toString, i.endExclusive.toString))
      ) ++
        g.points.map(p => MappingRender.sequence(Vector("point", p.at.toString)))
    )
  )
