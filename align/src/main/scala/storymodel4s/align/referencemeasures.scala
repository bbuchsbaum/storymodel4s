package storymodel4s.align

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** One reference meaning whose film support is resolved on one declared axis (ADR 0031 §1).
  *
  * Why: reference measures are only meaningful when the members, the weights and the support were
  * checked together against one source and one axis selection, so the input is born checked.
  *
  * '''Provisional.''' This stands in for the ADR 0030 detail-account reference until that account's
  * runtime types land. It carries only what the reference measures read: the meaning, the supplied
  * weights, whether they are exhaustive, and each constituent's source units. It has no wire format
  * and no recall-side support.
  *
  * Each constituent names the source units (mapping targets) whose supplied playback support
  * composes it. Support is read with [[TemporalSupport.read]] on the declared selection; this type
  * does not invent a second time representation.
  */
final class ProvisionalReference private (
    val sourceDigest: Checksum,
    val coordinate: TemporalSupport.Coordinate,
    val declaration: ProvisionalReference.Declaration,
    val coverage: ProvisionalReference.WeightCoverage,
    resolved: Map[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]]
):
  /** The resolved support of one source unit cited by this reference, if it cites it. */
  def support(unit: SourceNodeRef): Option[Either[TemporalSupport.Refusal, TemporalSupport]] =
    resolved.get(unit)

  /** Every source unit this reference cites, in canonical key order. */
  def units: Vector[SourceNodeRef] = resolved.keys.toVector.sortBy(_.key)

  // Resolved support is derived from the source and selection, so these fields determine it.
  private def fields = (
    sourceDigest,
    coordinate.selection,
    coordinate.bundle,
    coordinate.axis,
    declaration,
    coverage
  )
  override def equals(other: Any): Boolean = other match
    case that: ProvisionalReference => fields == that.fields
    case _                          => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String =
    s"ProvisionalReference(${sourceDigest.short()},${declaration.productPrefix})"

object ProvisionalReference:
  val Version: String = "provisional-reference/v1"

  /** Hypothesis shape. Why: single, joint and broad meanings differ in what a constituent is. */
  enum Shape:
    case Single, Joint, Broad

  /** One located hypothesis.
    *
    * Why: extent is reported per constituent, so the constituents are declared, not inferred. For a
    * single or broad hypothesis each constituent is one occurrence the reference declares (one
    * constituent unless the referent repeats). For a joint hypothesis each constituent is one
    * moment. Each constituent lists the source units whose support composes it.
    */
  final case class Hypothesis(
      shape: Shape,
      constituents: NonEmptyVector[NonEmptyVector[SourceNodeRef]]
  )

  /** A member of an alternative. Why: an external destination is a member, never a location. */
  enum Member:
    case Located(hypothesis: Hypothesis)
    case External(state: ExternalState)

    /** Canonical identity of the meaning; constituent and unit order do not change it. */
    def key: String = this match
      case Located(h) =>
        val parts = h.constituents.toVector.map(_.toVector.map(_.key).sorted.mkString(",")).sorted
        MappingRender.sequence(Vector("located", h.shape.toString) ++ parts)
      case External(state) => MappingRender.sequence(Vector("external", state.toString))

  /** A member with an optional supplied weight. Why: weights are observations, never inferred. */
  final case class Weighted(member: Member, weight: Option[Double])

  /** A reference that was not resolved. Why: ADR 0030 keeps reference status apart from outcome.
    */
  enum Outcome:
    case NotAttempted(reason: String)
    case Failed(failure: ProcessingFailure)
    case SearchLimited(reason: String)

  /** The declared meaning. Why: each ADR 0030 reference meaning has its own measure semantics.
    *
    * `Definite` is a single, joint or broad reference, with an optional confidence weight in (0,
    * 1]. `Destination` is a reference to a typed external destination. `Unresolved` is an attempted
    * reference without an outcome; `NotResolved` records a processing outcome. An alternative's
    * members may each carry a weight, or only some of them may; raw weights are kept as supplied.
    */
  enum Declaration:
    case Definite(hypothesis: Hypothesis, confidence: Option[Double])
    case Destination(state: ExternalState)
    case Alternative(members: Vector[Weighted])
    case Unresolved
    case NotResolved(outcome: Outcome)

  /** Whether supplied weights cover every place the producer considered.
    *
    * Why: only an exhaustive declaration makes 1 - Σw a known unassigned share; otherwise the
    * remainder stays unknown (ADR 0022). The declaration governs a definite reference's confidence
    * too: under `RemainderUnknown`, 1 - confidence is not a known share.
    */
  enum WeightCoverage:
    case Exhaustive, RemainderUnknown

  /** Why a supplied weight was refused. Why: a refusal never carries a non-finite number. */
  enum WeightProblem:
    case NonFinite, NonPositive, AboveOne

  /** Typed construction refusals. Why: malformed references never reach a measure. */
  enum Refusal:
    case Axis(reason: TemporalSupport.Refusal)
    case UnknownUnit(unit: SourceNodeRef)
    case DuplicateUnit(unit: SourceNodeRef)
    case DuplicateConstituent
    case SingleMomentJoint
    case OneMemberAlternative
    case DuplicateMember(first: Int, second: Int)

    /** `position` is the alternative member's index. */
    case InvalidWeight(position: Int, problem: WeightProblem)

    /** A definite reference's confidence weight. */
    case InvalidConfidence(problem: WeightProblem)
    case WeightSumExceedsOne(total: Double)

  /** Checked factory: validates the meaning and weights, then resolves every cited unit's support
    * on `selection` with [[TemporalSupport.read]]. A per-unit support refusal is retained and
    * becomes a typed unavailable extent; an invalid selection or an unknown unit refuses.
    */
  def of(
      source: SourceRepresentation,
      selection: TemporalSupport.Selection,
      declaration: Declaration,
      coverage: WeightCoverage
  ): Either[Refusal, ProvisionalReference] =
    for
      coordinate <- TemporalSupport.select(source, selection).left.map(Refusal.Axis(_))
      _ <- check(declaration)
      cited = unitsOf(declaration)
      _ <- cited.find(u => source.target(u).isEmpty).map(Refusal.UnknownUnit(_)).toLeft(())
    yield new ProvisionalReference(
      source.digest,
      coordinate,
      declaration,
      coverage,
      cited.map(u => u -> TemporalSupport.read(source, u, selection)).toMap
    )

  private def unitsOf(declaration: Declaration): Vector[SourceNodeRef] =
    def located(h: Hypothesis) = h.constituents.toVector.flatMap(_.toVector)
    val all = declaration match
      case Declaration.Definite(h, _)       => located(h)
      case Declaration.Alternative(members) =>
        members.flatMap(_.member match
          case Member.Located(h)  => located(h)
          case Member.External(_) => Vector.empty)
      case _ => Vector.empty
    all.distinct.sortBy(_.key)

  private def check(declaration: Declaration): Either[Refusal, Unit] =
    declaration match
      case Declaration.Definite(h, confidence) =>
        for
          _ <- hypothesis(h)
          _ <- confidence.flatMap(problem).map(Refusal.InvalidConfidence(_)).toLeft(())
        yield ()
      case Declaration.Alternative(members) =>
        val keys = members.map(_.member.key)
        val duplicate = keys.indices.iterator
          .flatMap(i => keys.indices.drop(i + 1).find(j => keys(i) == keys(j)).map(i -> _))
          .nextOption()
        val supplied = members.flatMap(_.weight)
        for
          _ <- Either.cond(members.size >= 2, (), Refusal.OneMemberAlternative)
          _ <- members.foldLeft[Either[Refusal, Unit]](Right(())) { (acc, m) =>
            acc.flatMap(_ =>
              m.member match
                case Member.Located(h)  => hypothesis(h)
                case Member.External(_) => Right(())
            )
          }
          _ <- duplicate.map((i, j) => Refusal.DuplicateMember(i, j)).toLeft(())
          _ <- members.zipWithIndex.foldLeft[Either[Refusal, Unit]](Right(())) {
            case (acc, (m, i)) =>
              acc.flatMap(_ =>
                m.weight.flatMap(problem).map(Refusal.InvalidWeight(i, _)).toLeft(())
              )
          }
          // Sorted summation: whether the sum is refused cannot depend on member order.
          total = supplied.sorted.sum
          // The same expression as the share's snap: every admitted total has
          // 1 - total >= -RowSum, so the snap covers the whole admitted overshoot. NaN refuses.
          _ <- Either.cond(
            1.0 - total >= -MappingTolerance.RowSum,
            (),
            Refusal.WeightSumExceedsOne(total)
          )
        yield ()
      case _ => Right(())

  private def hypothesis(h: Hypothesis): Either[Refusal, Unit] =
    val repeated = h.constituents.toVector.flatMap { c =>
      c.toVector.groupBy(identity).collect { case (u, xs) if xs.size > 1 => u }
    }
    val constituentKeys = h.constituents.toVector.map(_.toVector.map(_.key).sorted)
    if repeated.nonEmpty then Left(Refusal.DuplicateUnit(repeated.minBy(_.key)))
    else if constituentKeys.distinct.size != constituentKeys.size then
      Left(Refusal.DuplicateConstituent)
    else if h.shape == Shape.Joint && h.constituents.length < 2 then Left(Refusal.SingleMomentJoint)
    else Right(())

  // Fail-closed: a NaN fails every comparison, so finiteness is tested first and explicitly.
  private def problem(w: Double): Option[WeightProblem] =
    if !w.isFinite then Some(WeightProblem.NonFinite)
    else if !(w > 0.0) then Some(WeightProblem.NonPositive)
    else if w > 1.0 then Some(WeightProblem.AboveOne)
    else None

/** Measures on one provisional reference (ADR 0031 §1): alternatives present, member count,
  * per-constituent extent, occurrence count, support grain and unassigned share.
  *
  * Why: one entropy over film seconds conflates breadth, competing hypotheses and annotation grain;
  * these measures keep them apart. No supplied mass is renormalized, nothing is calibrated, and
  * alternative entropy is deliberately absent (deferred by ADR 0031).
  */
object ReferenceMeasures:
  val Version: String = "reference-measures/v1"

  import ProvisionalReference.{Declaration, Member, Shape, WeightCoverage}

  /** Why a whole-reference measure is unavailable. Why: outcomes are values, not numbers. */
  enum Unavailable:
    case Unresolved
    case NotResolved(outcome: ProvisionalReference.Outcome)

  /** Why one source unit contributes no measurable support on the declared axis.
    *
    * Why: each [[TemporalSupport]] state and refusal keeps its own reason. `NoTemporalSupport` is
    * text-only or unlocated (missing) support; `OffAxis` is no support on the axis or support
    * wholly outside the selected occurrence; `ClippedByOccurrence` is support partly outside it,
    * which is never measured as if it were whole.
    */
  enum UnitUnavailable:
    case NoTemporalSupport(state: TemporalSupport.State)
    case OffAxis(state: TemporalSupport.State)
    case ClippedByOccurrence
    case Refused(reason: TemporalSupport.Refusal)

  /** Why an extent (or an occurrence count) is unavailable. Why: never a plausible number.
    *
    * `PointSupport` carries the number of distinct points: points are counted, not measured.
    * `Support` lists every unit of the constituent without measurable support: when support is
    * incomplete the whole-constituent extent is withheld, never measured from its available part. A
    * separately named within-occurrence intersection is a possible later quantity, not this one.
    */
  enum ExtentUnavailable:
    case External(state: ExternalState)
    case PointSupport(points: Int)
    case Support(units: NonEmptyVector[(SourceNodeRef, UnitUnavailable)])
    case NoTimebase
    case Overflow

  /** Why the unassigned share is unavailable. Why: an unknown remainder is never 1 - Σw.
    *
    * `PartialWeights` is an alternative in which only some members carry a weight: the share of the
    * unweighted members is unknown, so no remainder is defined.
    */
  enum UnassignedUnavailable:
    case NoWeights
    case PartialWeights
    case RemainderUnknown(suppliedTotal: Double)
    case Outcome(reason: Unavailable)

  /** A duration on the declared axis, in integer ticks and exact seconds.
    *
    * Why: ticks keep the exact lattice and seconds use the axis timebase without floating point.
    */
  final class AxisDuration private[ReferenceMeasures] (val ticks: Long, val seconds: ExactRational):
    override def equals(other: Any): Boolean = other match
      case that: AxisDuration => ticks == that.ticks && seconds == that.seconds
      case _                  => false
    override def hashCode(): Int = (ticks, seconds).hashCode()
    override def toString: String = s"AxisDuration($ticks ticks, $seconds s)"

  /** Duration range of the source units bounding a constituent's interval support.
    *
    * Why: an extent no wider than its coarsest unit is bounded by the annotation, not by recall.
    * Each unit's duration is the measure of the unit's own supplied interval support; point-only
    * units have none and do not enter the range. `boundingUnits` counts the units that do.
    */
  final class Grain private[ReferenceMeasures] (
      val shortest: AxisDuration,
      val longest: AxisDuration,
      val boundingUnits: Int
  ):
    private def fields = (shortest, longest, boundingUnits)
    override def equals(other: Any): Boolean = other match
      case that: Grain => fields == that.fields
      case _           => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"Grain($shortest, $longest, units $boundingUnits)"

  /** The measured extent of one constituent.
    *
    * Why: breadth is the measure of the union of each unit's own supplied interval support
    * (overlaps once, gaps not counted); the hull is reported separately and points outside the
    * union are only counted.
    */
  final class Extent private[ReferenceMeasures] (
      val measure: AxisDuration,
      val hull: AxisDuration,
      val isolatedPoints: Int,
      val grain: Grain
  ):
    /** True when the extent does not exceed the longest bounding unit.
      *
      * This describes the annotation's resolution, not psychological precision: a one-unit
      * constituent is always at the floor, because its extent is that unit's own support.
      */
    def atAnnotationFloor: Boolean = measure.ticks <= grain.longest.ticks

    private def fields = (measure, hull, isolatedPoints, grain)
    override def equals(other: Any): Boolean = other match
      case that: Extent => fields == that.fields
      case _            => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"Extent($measure, hull $hull, points $isolatedPoints, $grain)"

  /** One extent-vector entry. Why: one entry per declared constituent, never merged. */
  final class Entry private[ReferenceMeasures] (
      val constituent: NonEmptyVector[SourceNodeRef],
      val extent: Either[ExtentUnavailable, Extent]
  ):
    override def equals(other: Any): Boolean = other match
      case that: Entry => constituent == that.constituent && extent == that.extent
      case _           => false
    override def hashCode(): Int = (constituent, extent).hashCode()
    override def toString: String = s"Entry(${constituent.toVector.map(_.key)}, $extent)"

  /** Measures of one member. Why: extent is per member and reads no other member's support.
    *
    * `weight` is the raw supplied weight (or confidence), never rescaled.
    */
  final class MemberMeasure private[ReferenceMeasures] (
      val member: Member,
      val weight: Option[Double],
      val occurrenceCount: Either[ExtentUnavailable, Int],
      val entries: Either[ExtentUnavailable, NonEmptyVector[Entry]]
  ):
    private def fields = (member, weight, occurrenceCount, entries)
    override def equals(other: Any): Boolean = other match
      case that: MemberMeasure => fields == that.fields
      case _                   => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"MemberMeasure(${member.key}, $weight, $entries)"

  /** All measures of one reference. Why: each quantity keeps its own availability.
    *
    * For a definite reference or a bare external destination, `members` holds that one hypothesis
    * or destination (weighted by any supplied confidence) and `memberCount` is 1. This is a
    * singleton convention: it does not mean a definite reference has alternative members, which
    * `alternativesPresent = false` states.
    *
    * The unassigned share is available only for exhaustive weights, and then lies in [0, 1]: when
    * |1 - Σw| is within [[MappingTolerance.RowSum]] it is exactly 0.0 (a declared symmetric snap
    * of the share only; the raw weights are untouched), otherwise it is 1 - Σw.
    */
  final class Measures private[ReferenceMeasures] (
      val reference: ProvisionalReference,
      val alternativesPresent: Either[Unavailable, Boolean],
      val memberCount: Either[Unavailable, Int],
      val members: Either[Unavailable, Vector[MemberMeasure]],
      val unassignedShare: Either[UnassignedUnavailable, Double]
  ):
    private def fields = (reference, alternativesPresent, memberCount, members, unassignedShare)
    override def equals(other: Any): Boolean = other match
      case that: Measures => fields == that.fields
      case _              => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String =
      s"ReferenceMeasures.Measures($alternativesPresent, $memberCount, $unassignedShare)"

  /** Compute every measure. Total: the input was checked by [[ProvisionalReference.of]]. */
  def of(reference: ProvisionalReference): Measures =
    def outcome(reason: Unavailable): Measures =
      new Measures(
        reference,
        Left(reason),
        Left(reason),
        Left(reason),
        Left(UnassignedUnavailable.Outcome(reason))
      )
    reference.declaration match
      case Declaration.Definite(h, confidence) =>
        new Measures(
          reference,
          Right(false),
          Right(1),
          Right(Vector(member(reference, Member.Located(h), confidence))),
          unassigned(confidence.toVector, reference.coverage)
        )
      case Declaration.Destination(state) =>
        new Measures(
          reference,
          Right(false),
          Right(1),
          Right(Vector(member(reference, Member.External(state), None))),
          Left(UnassignedUnavailable.NoWeights)
        )
      case Declaration.Alternative(members) =>
        new Measures(
          reference,
          Right(true),
          Right(members.size),
          Right(members.map(m => member(reference, m.member, m.weight))),
          if members.forall(_.weight.isDefined) || members.forall(_.weight.isEmpty) then
            unassigned(members.flatMap(_.weight), reference.coverage)
          else Left(UnassignedUnavailable.PartialWeights)
        )
      case Declaration.Unresolved       => outcome(Unavailable.Unresolved)
      case Declaration.NotResolved(out) => outcome(Unavailable.NotResolved(out))

  // Sorted summation: the total, and so the share, cannot depend on member order.
  private def unassigned(
      weights: Vector[Double],
      coverage: WeightCoverage
  ): Either[UnassignedUnavailable, Double] =
    if weights.isEmpty then Left(UnassignedUnavailable.NoWeights)
    else
      val total = weights.sorted.sum
      coverage match
        case WeightCoverage.Exhaustive =>
          val residual = 1.0 - total
          // Symmetric snap: Σw above 1 + tolerance was refused, so the share lies in [0, 1].
          Right(if math.abs(residual) <= MappingTolerance.RowSum then 0.0 else residual)
        case WeightCoverage.RemainderUnknown =>
          Left(UnassignedUnavailable.RemainderUnknown(total))

  private def member(
      reference: ProvisionalReference,
      value: Member,
      weight: Option[Double]
  ): MemberMeasure =
    value match
      case Member.External(state) =>
        val external = ExtentUnavailable.External(state)
        new MemberMeasure(value, weight, Left(external), Left(external))
      case Member.Located(h) =>
        val occurrences = if h.shape == Shape.Joint then 1 else h.constituents.length
        new MemberMeasure(
          value,
          weight,
          Right(occurrences),
          Right(h.constituents.map(c => entry(reference, c)))
        )

  private def entry(reference: ProvisionalReference, constituent: NonEmptyVector[SourceNodeRef]) =
    val read = constituent.toVector.map(u => u -> unit(reference.support(u).get))
    val missing = read.collect { case (u, Left(reason)) => u -> reason }
    val extent = NonEmptyVector.fromVector(missing) match
      case Some(units) => Left(ExtentUnavailable.Support(units))
      case None => measure(reference.coordinate.axis, read.collect { case (_, Right(s)) => s })
    new Entry(constituent, extent)

  private def unit(
      resolved: Either[TemporalSupport.Refusal, TemporalSupport]
  ): Either[UnitUnavailable, PlaybackSupport] =
    resolved match
      case Left(reason)   => Left(UnitUnavailable.Refused(reason))
      case Right(support) =>
        val node = support.selected
        // TemporalSupport makes a node Available exactly when it has included support, so the
        // included support decides. The state match is explicit so that a new State fails to
        // compile here instead of being classified by a catch-all.
        (node.included, node.excluded) match
          case (Some(inside), None) => Right(inside)
          case (Some(_), Some(_))   => Left(UnitUnavailable.ClippedByOccurrence)
          case (None, _)            =>
            node.state match
              case s @ (TemporalSupport.State.Unlocated | TemporalSupport.State.TextOnly) =>
                Left(UnitUnavailable.NoTemporalSupport(s))
              case s @ (TemporalSupport.State.NoSupportOnAxis |
                  TemporalSupport.State.OutsideOccurrence) =>
                Left(UnitUnavailable.OffAxis(s))
              // Unreachable: TemporalSupport never builds Available without included support.
              // Fail closed as no temporal support rather than inventing a location.
              case s @ TemporalSupport.State.Available =>
                Left(UnitUnavailable.NoTemporalSupport(s))

  // Integer geometry: the union of every unit's intervals, merged once; points are only counted.
  private[align] def measure(
      axis: PresentationAxis,
      supports: Vector[PlaybackSupport]
  ): Either[ExtentUnavailable, Extent] =
    val union = supports
      .flatMap(_.intervals)
      .map(i => (i.start, i.endExclusive))
      .sorted
      .foldLeft(Vector.empty[(Long, Long)]) { (out, next) =>
        out.lastOption match
          case Some((start, end)) if next._1 <= end => out.init :+ (start -> end.max(next._2))
          case _                                    => out :+ next
      }
    val points = supports.flatMap(_.points).map(_.at).distinct
    val isolated = points.count(p => !union.exists((start, end) => p >= start && p < end))
    if union.isEmpty then Left(ExtentUnavailable.PointSupport(isolated))
    else
      def length(intervals: Vector[(Long, Long)]): BigInt =
        intervals.map((start, end) => BigInt(end) - BigInt(start)).sum
      val units = supports
        .filter(_.intervals.nonEmpty)
        .map(s => length(s.intervals.map(i => (i.start, i.endExclusive))))
      for
        timebase <- axis.timebase.toRight(ExtentUnavailable.NoTimebase)
        measured <- duration(length(union), timebase)
        hull <- duration(BigInt(union.last._2) - BigInt(union.head._1), timebase)
        shortest <- duration(units.min, timebase)
        longest <- duration(units.max, timebase)
      yield new Extent(measured, hull, isolated, new Grain(shortest, longest, units.size))

  private def duration(
      ticks: BigInt,
      timebase: RationalTimebase
  ): Either[ExtentUnavailable, AxisDuration] =
    if !ticks.isValidLong then Left(ExtentUnavailable.Overflow)
    else
      (ExactRational.integer(ticks.toLong) * timebase.scale).left
        .map(_ => ExtentUnavailable.Overflow)
        .map(new AxisDuration(ticks.toLong, _))
