package storymodel4s.align

import storymodel4s.recall.RecallUnitId

enum ProcessingFailure:
  case ProviderFailure(detail: String)
  case InvalidOutput(detail: String)
  case InferenceRefused(detail: String)

enum ProcessingStatus:
  case Complete
  case Failed(failure: ProcessingFailure)
  case ExcludedByInputPolicy(reason: String)

enum LocalizationStatus:
  case Located, Nonlocalizable, Unranked, NotComputed

/** The measure used for the choice; raw channels must be named explicitly. */
final class DecisionBasis private (val kind: MeasureKind, val channel: Option[String]):
  private[align] def resolve(
      measures: UnitMeasures
  ): Either[MappingRefusal, (Map[Destination, Double], ScoreDirection)] =
    val missing = MappingRefusal.MeasureMissing(kind, channel)
    kind match
      case MeasureKind.RawScore =>
        measures.raw
          .find(r => channel.contains(r.channel))
          .map(r => r.values -> r.direction)
          .toRight(missing)
      case MeasureKind.NormalizedScoreMass =>
        measures.normalized.map(r => r.mass -> ScoreDirection.HigherIsBetter).toRight(missing)
      case MeasureKind.TransportMass =>
        measures.transport.map(r => r.mass -> ScoreDirection.HigherIsBetter).toRight(missing)
      case MeasureKind.ModelPosterior =>
        measures.posterior.toRight(missing).flatMap { posterior =>
          val entries = posterior.mass.toVector.map((s, p) => Destination.of(s) -> p)
          if entries.map(_._1).distinct.size != entries.size then
            Left(
              MappingRefusal
                .InvalidValue("decisionBasis", "posterior destination projection must be unique")
            )
          else Right(entries.toMap -> ScoreDirection.HigherIsBetter)
        }
      case MeasureKind.CalibratedProbability => Left(MappingRefusal.Reserved("calibrated"))

object DecisionBasis:
  def of(kind: MeasureKind, channel: Option[String]): Either[MappingRefusal, DecisionBasis] =
    if kind == MeasureKind.CalibratedProbability then Left(MappingRefusal.Reserved("calibrated"))
    else if (kind == MeasureKind.RawScore && channel.exists(_.trim.nonEmpty)) ||
      (kind != MeasureKind.RawScore && channel.isEmpty)
    then Right(new DecisionBasis(kind, channel))
    else
      Left(
        MappingRefusal.InvalidValue("decisionBasis", "only raw scores require a nonempty channel")
      )

enum DecisionRequest:
  case RawArgmax
  case ExternalDecode(chosen: Destination, policy: DecisionPolicyId)
  case Abstain(policy: DecisionPolicyId, reason: String)

enum DecisionOrigin:
  case RawArgmax
  case StructuredDecode(policy: DecisionPolicyId)
  case GapFill(policy: DecisionPolicyId)
  case Abstention(reason: String)

type DecodedTargetMass = UnitOutcome.DecodedMass
object DecodedTargetMass:
  export UnitOutcome.DecodedMass.{InCandidateSupport, OutsideCandidateSupport, NoDecision}

enum DecisionCalibrationUnavailableReason:
  case NoCalibrationArtifact

type DecisionCalibration = UnitOutcome.Calibration
object DecisionCalibration:
  export UnitOutcome.Calibration.{Unavailable, Calibrated}

type UnitDecision = UnitOutcome.Decision

/** One outcome for every inventory unit, including processing failures and explicit abstention. */
final class UnitOutcome private (
    val unit: RecallUnitId,
    val processing: ProcessingStatus,
    val localization: LocalizationStatus,
    val links: Vector[MappingLink],
    val measures: UnitMeasures,
    val decision: Option[UnitDecision],
    val stages: Option[UnitStageRefs]
)
object UnitOutcome:
  sealed trait DecodedMass
  object DecodedMass:
    final class InCandidateSupport private (val value: Double) extends DecodedMass
    object InCandidateSupport:
      private[UnitOutcome] def derived(value: Double): InCandidateSupport = new InCandidateSupport(
        value
      )
    final class OutsideCandidateSupport private () extends DecodedMass
    object OutsideCandidateSupport:
      private[UnitOutcome] def derived(): OutsideCandidateSupport = new OutsideCandidateSupport()
    final class NoDecision private () extends DecodedMass
    object NoDecision:
      private[UnitOutcome] def derived(): NoDecision = new NoDecision()

  sealed trait Calibration
  object Calibration:
    final class Unavailable private (val reason: DecisionCalibrationUnavailableReason)
        extends Calibration
    object Unavailable:
      private[UnitOutcome] def derived(): Unavailable = new Unavailable(
        DecisionCalibrationUnavailableReason.NoCalibrationArtifact
      )

    /** Reserved: no construction door in G1. */
    final class Calibrated private (val probability: CalibratedProbability) extends Calibration

  /** Constructed only after the actual basis, link inventory and unit bindings agree. */
  final class Decision private[UnitOutcome] (
      val basis: DecisionBasis,
      val chosen: Option[Destination],
      val origin: DecisionOrigin,
      val rawArgmax: Option[(Destination, Double)],
      val decodedMass: DecodedTargetMass,
      val calibration: DecisionCalibration,
      val policy: Option[DecisionPolicyId],
      val request: DecisionRequest
  )

  def computed(
      unit: RecallUnitId,
      measures: UnitMeasures,
      links: Vector[MappingLink],
      basis: DecisionBasis,
      request: DecisionRequest,
      stages: UnitStageRefs
  ): Either[MappingRefusal, UnitOutcome] =
    def foreign(origin: MeasureDerivation): Boolean = origin match
      case d: MeasureDerivation.FromResult => d.unit != unit
      case MeasureDerivation.Supplied      => false
    val foreignRaw = measures.raw.exists(r => foreign(r.derivation))
    val foreignPosterior = measures.posterior.exists(_.unit != unit)
    val foreignLink = links.exists(l => foreign(l.derivation))
    val destinations = links.map(_.destination)
    if foreignRaw || foreignPosterior || foreignLink then
      Left(MappingRefusal.BindingMismatch("outcome.unit"))
    else if destinations.distinct.size != destinations.size || destinations.toSet != measures.destinations
    then Left(MappingRefusal.LinkDestinationsMismatch)
    else if links.exists(_.inferenceStage != stages.inference) then
      Left(MappingRefusal.BindingMismatch("outcome.inferenceStage"))
    else
      basis.resolve(measures).flatMap { (values, direction) =>
        val candidateSet = CandidateSetId.of(stages.candidates, unit, values.keySet)
        if links.exists(_.candidateSet != candidateSet) then
          Left(MappingRefusal.CandidateSetMismatch(unit))
        else
          request match
            case DecisionRequest.Abstain(_, reason) if reason.trim.isEmpty =>
              Left(MappingRefusal.InvalidValue("decision.abstention", "reason must be nonempty"))
            case _ =>
              val argmax = values.toVector.sortWith { (a, b) =>
                if a._2 == b._2 then a._1.key < b._1.key
                else
                  direction match
                    case ScoreDirection.HigherIsBetter => a._2 > b._2
                    case ScoreDirection.LowerIsBetter  => a._2 < b._2
              }.headOption
              val policy = request match
                case DecisionRequest.RawArgmax            => None
                case DecisionRequest.ExternalDecode(_, p) => Some(p)
                case DecisionRequest.Abstain(p, _)        => Some(p)
              val (chosen, origin, decodedMass, localization) = request match
                case DecisionRequest.Abstain(_, reason) =>
                  (
                    None,
                    DecisionOrigin.Abstention(reason),
                    DecodedMass.NoDecision.derived(),
                    LocalizationStatus.NotComputed
                  )
                case _ if values.isEmpty =>
                  (
                    None,
                    DecisionOrigin.Abstention("EmptyDecisionBasis"),
                    DecodedMass.NoDecision.derived(),
                    LocalizationStatus.Unranked
                  )
                case _ =>
                  val destination = request match
                    case DecisionRequest.ExternalDecode(value, _) => value
                    case _                                        => argmax.get._1
                  val mass = values.get(destination) match
                    case Some(value) => DecodedMass.InCandidateSupport.derived(value)
                    case None        => DecodedMass.OutsideCandidateSupport.derived()
                  val provenance = request match
                    case DecisionRequest.ExternalDecode(_, p) =>
                      if values.contains(destination) then DecisionOrigin.StructuredDecode(p)
                      else DecisionOrigin.GapFill(p)
                    case _ => DecisionOrigin.RawArgmax
                  val location = destination match
                    case Destination.External(ExternalState.Unranked) => LocalizationStatus.Unranked
                    case Destination.External(_) => LocalizationStatus.Nonlocalizable
                    case Destination.Target(_)   => LocalizationStatus.Located
                  (Some(destination), provenance, mass, location)
              val decision = new Decision(
                basis,
                chosen,
                origin,
                argmax,
                decodedMass,
                Calibration.Unavailable.derived(),
                policy,
                request
              )
              Right(
                new UnitOutcome(
                  unit,
                  ProcessingStatus.Complete,
                  localization,
                  links.sortBy(_.destination.key),
                  measures,
                  Some(decision),
                  Some(stages)
                )
              )
      }

  def failed(unit: RecallUnitId, failure: ProcessingFailure): UnitOutcome =
    new UnitOutcome(
      unit,
      ProcessingStatus.Failed(failure),
      LocalizationStatus.NotComputed,
      Vector.empty,
      UnitMeasures.empty,
      None,
      None
    )

  def excluded(unit: RecallUnitId, reason: String): UnitOutcome =
    new UnitOutcome(
      unit,
      ProcessingStatus.ExcludedByInputPolicy(reason),
      LocalizationStatus.NotComputed,
      Vector.empty,
      UnitMeasures.empty,
      None,
      None
    )
