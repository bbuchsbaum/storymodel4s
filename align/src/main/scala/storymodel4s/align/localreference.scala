package storymodel4s.align

import storymodel4s.core.Checksum
import storymodel4s.features.CanonicalDouble
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Independent local score mass retains its actual evidence without running sequence inference. */
object LocalReference:
  /** The normalization prior is distinct from any function prior already used in pricing. */
  enum Prior:
    case UniformAdmittedStates

  /** The numerical rule is versioned so a profile identifies the quantity it produces. */
  enum Normalization:
    case ShiftedExponentialCostV1

  /** Equality of actual costs does not assert equality of every upstream semantic score. */
  enum Information:
    case UniformCosts, DistinguishedCosts

  /** Missing local measurement never acquires a uniform mass by default. */
  enum NotComputedReason:
    case TieOverflow, Unranked, NoAdmittedTargets, PriorOnlyTargetCost, UnmeasuredTargetCost

  /** A checked, single-level declaration makes the denominator and normalization explicit. */
  final class Profile private[LocalReference] (
      val universe: DeclaredUniverse,
      val temperature: Double,
      val level: Int
  ):
    val prior: Prior = Prior.UniformAdmittedStates
    val normalization: Normalization = Normalization.ShiftedExponentialCostV1
    val externalAlternatives: Vector[ExternalState] =
      ExternalState.values.toVector.filterNot(_ == ExternalState.Unranked)
    val fingerprint: Checksum = MappingRender.digest(
      Vector(
        "local-reference/profile/v1",
        universe.id.digest.hex,
        CanonicalDouble.render(temperature),
        prior.toString,
        normalization.toString,
        MappingRender.sequence(externalAlternatives.map(_.toString))
      )
    )
    override def equals(other: Any): Boolean = other match
      case p: Profile => fingerprint == p.fingerprint
      case _          => false
    override def hashCode: Int = fingerprint.hashCode
    override def toString: String =
      s"LocalReference.Profile(level=$level, targets=${universe.targets.size}, temperature=$temperature)"

  object Profile:
    /** Source membership and the actual grain are checked by the executing producer. */
    def of(universe: DeclaredUniverse, temperature: Double): Either[AlignError, Profile] =
      if !temperature.isFinite || !(temperature > 0.0) then
        Left(refused("temperature must be finite and positive"))
      else if universe.targets.isEmpty then Left(refused("target universe must be nonempty"))
      else
        universe.grain match
          case TargetGrain.SingleLevel(level) => Right(new Profile(universe, temperature, level))
          case _                              => Left(refused("v1 requires a single target level"))

  /** Every unit retains its full original prices and nomination accounting, including exclusions.
    */
  sealed trait Outcome:
    def unit: RecallUnitId
    def costs: Map[AlignState, CostBreakdown]
    def overflow: Vector[TieOverflow]
    def uniformSemantic: Vector[UniformSemanticScores]
    def semanticOutcomes: Vector[SemanticOutcomeSummary]

  /** Actual normalized cost mass preserves faithful, distorted and external state identities. */
  final class Computed private[LocalReference] (
      val unit: RecallUnitId,
      val costs: Map[AlignState, CostBreakdown],
      val mass: Map[AlignState, Double],
      val information: Information,
      val overflow: Vector[TieOverflow],
      val uniformSemantic: Vector[UniformSemanticScores],
      val semanticOutcomes: Vector[SemanticOutcomeSummary]
  ) extends Outcome:
    val kind: MeasureKind = MeasureKind.NormalizedScoreMass
    override def equals(other: Any): Boolean = other match
      case c: Computed =>
        unit == c.unit && costs == c.costs && mass == c.mass && information == c.information &&
        overflow == c.overflow && uniformSemantic == c.uniformSemantic &&
        semanticOutcomes == c.semanticOutcomes
      case _ => false
    override def hashCode: Int =
      (unit, costs, mass, information, overflow, uniformSemantic, semanticOutcomes).hashCode
    override def toString: String =
      s"LocalReference.Computed(unit=${unit.value}, states=${mass.size}, $information)"

  /** An unavailable comparison retains its actual reason instead of invented normalized values. */
  final class NotComputed private[LocalReference] (
      val unit: RecallUnitId,
      val costs: Map[AlignState, CostBreakdown],
      val reason: NotComputedReason,
      val overflow: Vector[TieOverflow],
      val uniformSemantic: Vector[UniformSemanticScores],
      val semanticOutcomes: Vector[SemanticOutcomeSummary]
  ) extends Outcome:
    override def equals(other: Any): Boolean = other match
      case n: NotComputed =>
        unit == n.unit && costs == n.costs && reason == n.reason && overflow == n.overflow &&
        uniformSemantic == n.uniformSemantic && semanticOutcomes == n.semanticOutcomes
      case _ => false
    override def hashCode: Int =
      (unit, costs, reason, overflow, uniformSemantic, semanticOutcomes).hashCode
    override def toString: String = s"LocalReference.NotComputed(unit=${unit.value}, $reason)"

  /** Only actual local computation can bind these rows to their originating evidence and profile.
    */
  final class Result private[LocalReference] (
      val evidence: LocalEvidence,
      val profile: Profile,
      val outcomes: Vector[Outcome]
  ):
    /** This is the engine evidence identity, not full provider/render/model execution authority. */
    def evidenceId: LocalEvidenceId = evidence.identity
    def outcome(unit: RecallUnitId): Option[Outcome] = outcomes.find(_.unit == unit)
    override def equals(other: Any): Boolean = other match
      case r: Result => evidence == r.evidence && profile == r.profile && outcomes == r.outcomes
      case _         => false
    override def hashCode: Int = (evidence, profile, outcomes).hashCode
    override def toString: String = s"LocalReference.Result(units=${outcomes.size})"

  private def refused(detail: String): AlignError =
    AlignError.InvalidConfig("local reference", detail)

  /** Consume unchanged canonical evidence before HSMM; never nominate, price, refine or decode. */
  def compute(
      recall: RecallGraph[Checked],
      source: SourceView,
      evidence: LocalEvidence,
      profile: Profile
  ): Either[AlignError, Result] =
    val view = MappingBindingRender.snapshot(source)
    for
      _ <- LocalEvidence.bound(evidence, recall, view, gate = true)
      provenance <- evidence.provenance match
        case p: CandidateProvenance.Strict
            if evidence.gateSemantics == GateSemantics.CanonicalContent &&
              p.semanticChannel.nonEmpty && p.policy.isInstanceOf[CandidateTiePolicy.TieComplete] =>
          Right(p)
        case _ => Left(refused("controlled canonical tie-complete evidence is required"))
      actualUniverse = view.nodes.filter(_.level == profile.level).map(_.ref).sorted
      _ <- Either.cond(
        actualUniverse == profile.universe.targets,
        (),
        refused("declared universe must equal the bound source's complete single-level cut")
      )
      _ <- Either.cond(
        evidence.nominated.flatten.forall(profile.universe.targets.toSet),
        (),
        refused("nominations outside the declared universe cannot be filtered after pricing")
      )
      rows = evidence.units.indices.toVector.map(i => row(evidence, provenance, profile, i))
    yield new Result(evidence, profile, rows)

  private def row(
      evidence: LocalEvidence,
      provenance: CandidateProvenance.Strict,
      profile: Profile,
      index: Int
  ): Outcome =
    val unit = evidence.units(index)
    val costs = evidence.breakdowns(index)
    val overflow = provenance.overflow(index)
    val uniform = provenance.uniformSemantic(index)
    val semantics = provenance.semanticOutcomes(index)
    val admitted = costs.toVector.filterNot(_._2.excluded).sortBy(_._1.key)
    val targets = admitted.filter(_._1.isSource)
    def unavailable(reason: NotComputedReason): NotComputed =
      new NotComputed(unit, costs, reason, overflow, uniform, semantics)
    if overflow.nonEmpty then unavailable(NotComputedReason.TieOverflow)
    else if admitted.map(_._1) == Vector(AlignState.unranked) then
      unavailable(NotComputedReason.Unranked)
    else if targets.isEmpty then unavailable(NotComputedReason.NoAdmittedTargets)
    else if targets.exists { (_, cost) =>
        cost.support match
          case p: SupportAssessment.Unestablished =>
            p.reason == SupportUnestablishedReason.ZeroEligibleWeight
          case _ => false
      }
    then unavailable(NotComputedReason.PriorOnlyTargetCost)
    else if targets.exists { (_, cost) =>
        cost.support match
          case measured: SupportAssessment.Assessed => !(measured.basis.measuredWeight > 0.0)
          case _                                    => true
      }
    then unavailable(NotComputedReason.UnmeasuredTargetCost)
    else
      val values = admitted.map(_._2.total)
      val least = values.min
      val weights = values.map(cost => math.exp(-((cost - least) / profile.temperature)))
      // Ordering by magnitude, rather than identifiers, makes the denominator rename-invariant.
      val denominator = weights.sorted.sum
      val mass = admitted.map(_._1).zip(weights.map(_ / denominator)).toMap
      val information =
        if values.forall(_ == least) then Information.UniformCosts
        else Information.DistinguishedCosts
      new Computed(unit, costs, mass, information, overflow, uniform, semantics)
