package storymodel4s.align

import storymodel4s.core.*
import storymodel4s.recall.{RecallUnitId, SegmentationId}

/** A mapping destination. Processing failures are outcomes, never destinations. */
enum Destination:
  case Target(ref: SourceNodeRef)
  case External(state: ExternalState)
  def key: String = this match
    case Target(ref)     => ref.key
    case External(state) => s"ext:$state"
object Destination:
  def of(state: AlignState): Destination = state match
    case AlignState.Source(ref)       => Target(ref)
    case AlignState.Distorted(ref, _) => Target(ref)
    case AlignState.External(state)   => External(state)
  given Ordering[Destination] = Ordering.by(_.key)

enum MeasureKind:
  case RawScore, NormalizedScoreMass, TransportMass, ModelPosterior, CalibratedProbability

/** Typed failures at the mapping contract boundary. */
enum MappingRefusal:
  case InvalidValue(field: String, detail: String)
  case DuplicateStageEntry(id: StageEntryId)
  case MissingStages(stages: Vector[Stage])
  case NonCanonicalLedger
  case StageMismatch(expected: Stage, actual: Stage)
  case DanglingStageEntry(id: StageEntryId)
  case UnknownUnit(unit: RecallUnitId)
  case UnknownTarget(ref: SourceNodeRef)
  case DuplicateTarget(ref: SourceNodeRef)
  case PhysicalInventoryMismatch
  case ForeignBundle(identity: Checksum)
  case ForeignAxis(axis: PresentationAxisId)
  case AmbiguousAxis(axis: PresentationAxisId)
  case InvalidComposition(detail: String)
  case CrossPartWithoutComposition(ref: SourceNodeRef)
  case InvalidTextSource
  case BindingMismatch(field: String)
  case MissingCost(unit: RecallUnitId, state: AlignState)
  case MissingRow(unit: RecallUnitId)
  case MeasureMissing(kind: MeasureKind, channel: Option[String])
  case LinkDestinationsMismatch
  case CandidateSetMismatch(unit: RecallUnitId)
  case OutcomeInventoryMismatch
  case RolesMismatch
  case UniverseMismatch
  case PriorMismatch
  case ScopeUndeclared(ref: SourceNodeRef)
  case Reserved(tag: String)
  def message: String = toString

object DecisionPolicyId extends OpaqueId("DecisionPolicyId")
type DecisionPolicyId = DecisionPolicyId.T
object CandidatePolicyId extends OpaqueId("CandidatePolicyId")
type CandidatePolicyId = CandidatePolicyId.T
object ReferencePriorId extends OpaqueId("ReferencePriorId")
type ReferencePriorId = ReferencePriorId.T
object CalibrationArtifactId extends OpaqueId("CalibrationArtifactId")
type CalibrationArtifactId = CalibrationArtifactId.T

enum TargetGrain:
  case SingleLevel(level: Int)
  case Hierarchy(levels: Vector[Int])

/** A derived identifier of a declared target universe, not a claim about candidate coverage. */
final class TargetUniverseId private (val digest: Checksum):
  override def equals(other: Any): Boolean = other match
    case that: TargetUniverseId => digest == that.digest
    case _                      => false
  override def hashCode: Int = digest.hashCode
object TargetUniverseId:
  def of(
      targets: Vector[SourceNodeRef],
      grain: TargetGrain
  ): Either[MappingRefusal, TargetUniverseId] =
    MappingChecks.universe(targets, grain).map { _ =>
      new TargetUniverseId(
        MappingRender.digest(
          Vector(
            "target-universe/v1",
            MappingRender.sequence(targets.sorted.map(_.key)),
            MappingRender.grain(grain)
          )
        )
      )
    }

final class DeclaredUniverse private (
    val id: TargetUniverseId,
    val targets: Vector[SourceNodeRef],
    val grain: TargetGrain
)
object DeclaredUniverse:
  def of(
      targets: Vector[SourceNodeRef],
      grain: TargetGrain
  ): Either[MappingRefusal, DeclaredUniverse] =
    TargetUniverseId.of(targets, grain).map(id => new DeclaredUniverse(id, targets.sorted, grain))

/** Identity of the decision basis's keys, including explicit external alternatives. */
final class CandidateSetId private (val digest: Checksum):
  override def equals(other: Any): Boolean = other match
    case that: CandidateSetId => digest == that.digest
    case _                    => false
  override def hashCode: Int = digest.hashCode
object CandidateSetId:
  def of(candidates: StageEntryId, unit: RecallUnitId, basis: Set[Destination]): CandidateSetId =
    new CandidateSetId(
      MappingRender.digest(
        Vector(
          "candidate-set/v1",
          candidates.digest.hex,
          unit.value,
          MappingRender.sequence(basis.toVector.sorted.map(_.key))
        )
      )
    )

enum CandidateCoverage:
  case Truncated(perLevel: Int)
  case Complete
  case Unknown(reason: String)
enum CandidatePolicy:
  case Declared(id: CandidatePolicyId, coverage: CandidateCoverage)
  case Unknown(reason: String)
enum ContextPolicy:
  case Unspecified(reason: String)
enum InferencePolicy:
  case HistoricalReconstruction(label: String)
  case Unspecified(reason: String)
enum ReferencePrior:
  case Declared(id: ReferencePriorId)
  case NotApplicable(reason: String)
enum DecisionPolicy:
  case Declared(id: DecisionPolicyId)
  case NotApplicable(reason: String)

final class MappingPolicies private (
    val inference: InferencePolicy,
    val context: ContextPolicy,
    val candidate: CandidatePolicy,
    val referencePrior: ReferencePrior,
    val decision: DecisionPolicy,
    val universe: DeclaredUniverse
)
object MappingPolicies:
  def of(
      inference: InferencePolicy,
      context: ContextPolicy,
      candidate: CandidatePolicy,
      referencePrior: ReferencePrior,
      decision: DecisionPolicy,
      universe: DeclaredUniverse
  ): Either[MappingRefusal, MappingPolicies] =
    val labels = Vector(
      inference match
        case InferencePolicy.HistoricalReconstruction(label) => label
        case InferencePolicy.Unspecified(reason)             => reason
      ,
      context match
        case ContextPolicy.Unspecified(reason) => reason
    ) ++ (candidate match
      case CandidatePolicy.Unknown(reason)                                => Vector(reason)
      case CandidatePolicy.Declared(_, CandidateCoverage.Unknown(reason)) => Vector(reason)
      case _ => Vector.empty) ++ (referencePrior match
      case ReferencePrior.NotApplicable(reason) => Vector(reason)
      case _                                    => Vector.empty) ++ (decision match
      case DecisionPolicy.NotApplicable(reason) => Vector(reason)
      case _                                    => Vector.empty)
    val invalidCoverage = candidate match
      case CandidatePolicy.Declared(_, CandidateCoverage.Truncated(n)) => n <= 0
      case _                                                           => false
    if labels.exists(_.trim.isEmpty) || invalidCoverage then
      Left(
        MappingRefusal.InvalidValue(
          "policies",
          "reasons and labels must be nonempty; truncation must be positive"
        )
      )
    else
      Right(new MappingPolicies(inference, context, candidate, referencePrior, decision, universe))

enum AnalysisGrain:
  case InferenceUnit(segmentation: SegmentationId)
  case Word
  case Targets(grain: TargetGrain)
final class UnitRoles private (
    val inference: AnalysisGrain,
    val organization: AnalysisGrain,
    val projection: AnalysisGrain
)
object UnitRoles:
  def of(
      inference: AnalysisGrain,
      organization: AnalysisGrain,
      projection: AnalysisGrain
  ): Either[MappingRefusal, UnitRoles] =
    val valid = Vector(inference, organization, projection).forall {
      case AnalysisGrain.Targets(grain) => MappingChecks.grain(grain)
      case _                            => true
    }
    if valid then Right(new UnitRoles(inference, organization, projection))
    else Left(MappingRefusal.InvalidValue("roles", "invalid target grain"))

private[align] object MappingChecks:
  def grain(value: TargetGrain): Boolean = value match
    case TargetGrain.SingleLevel(level) => level >= 0
    case TargetGrain.Hierarchy(levels)  =>
      levels.nonEmpty && levels == levels.distinct.sorted && levels.forall(_ >= 0)
  def universe(targets: Vector[SourceNodeRef], value: TargetGrain): Either[MappingRefusal, Unit] =
    targets
      .groupBy(identity)
      .collect { case (ref, entries) if entries.size > 1 => ref }
      .toVector
      .sorted
      .headOption match
      case Some(ref)             => Left(MappingRefusal.DuplicateTarget(ref))
      case None if !grain(value) =>
        Left(
          MappingRefusal.InvalidValue(
            "universe.grain",
            "levels must be nonnegative, nonempty and canonical"
          )
        )
      case None => Right(())

/** New mapping identities preserve every admitted UTF-16 code unit, unlike lossy UTF-8 tokens. */
private[align] object MappingRender:
  def sequence(values: Vector[String]): String =
    values.size.toString + ":" + values.map(v => s"${v.length}:$v").mkString
  def digest(values: Vector[String]): Checksum =
    Checksum.ofText(sequence(values).iterator.map(c => f"${c.toInt}%04x").mkString)
  def optional(value: Option[String]): String =
    value.fold(sequence(Vector("none")))(s => sequence(Vector("some", s)))
  def grain(value: TargetGrain): String = value match
    case TargetGrain.SingleLevel(level) => sequence(Vector("single-level", level.toString))
    case TargetGrain.Hierarchy(levels)  =>
      sequence(Vector("hierarchy", sequence(levels.map(_.toString))))
