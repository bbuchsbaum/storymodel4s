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

  /** A producer's categorical choice: no score ranked it, so it is never an argmax or decode. */
  case ProducerChoice(policy: DecisionPolicyId)

type DecodedTargetMass = UnitOutcome.DecodedMass
object DecodedTargetMass:
  export UnitOutcome.DecodedMass.{InCandidateSupport, OutsideCandidateSupport, NoDecision}

enum DecisionCalibrationUnavailableReason:
  case NoCalibrationArtifact

type DecisionCalibration = UnitOutcome.Calibration
object DecisionCalibration:
  export UnitOutcome.Calibration.{Unavailable, Calibrated}

type UnitDecision = UnitOutcome.Decision

/** What licensed a decision: a numerical basis, or a producer's score-free categorical choice. */
type DecisionSupport = UnitOutcome.Support
object DecisionSupport:
  export UnitOutcome.Support.{Numerical, Categorical}

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

  /** Why a decision may be read as it is; each case carries only what its producer measured. */
  sealed trait Support
  object Support:

    /** Scores resolved from `UnitMeasures`: the only support that has an argmax or a mass. */
    final class Numerical private (
        val basis: DecisionBasis,
        val rawArgmax: Option[(Destination, Double)],
        val decodedMass: DecodedTargetMass
    ) extends Support
    object Numerical:
      private[UnitOutcome] def derived(
          basis: DecisionBasis,
          rawArgmax: Option[(Destination, Double)],
          decodedMass: DecodedTargetMass
      ): Numerical = new Numerical(basis, rawArgmax, decodedMass)

    /** A producer's categorical choice or abstention; no number exists to rank or calibrate it. */
    final class Categorical private (val policy: DecisionPolicyId) extends Support
    object Categorical:
      private[UnitOutcome] def derived(policy: DecisionPolicyId): Categorical =
        new Categorical(policy)

  /** Constructed only after the actual support, link inventory and unit bindings agree. */
  final class Decision private[UnitOutcome] (
      val support: DecisionSupport,
      val chosen: Option[Destination],
      val origin: DecisionOrigin,
      val calibration: DecisionCalibration,
      val policy: Option[DecisionPolicyId],
      val request: DecisionRequest
  ):
    /** The numerical support, absent (never defaulted) for a categorical decision. */
    def numerical: Option[Support.Numerical] = support match
      case value: Support.Numerical => Some(value)
      case _: Support.Categorical   => None

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
                Support.Numerical.derived(basis, argmax, decodedMass),
                chosen,
                origin,
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

  /** A genuine requested fill remains a fill even when its admitted posterior key has zero mass. */
  private[align] def retainRequestedFill(
      outcome: UnitOutcome,
      execution: MappingRun.Execution
  ): Either[MappingRefusal, UnitOutcome] =
    execution.reconstruction.decisions
      .find(_.unit == outcome.unit)
      .toRight(MappingRefusal.UnknownUnit(outcome.unit))
      .flatMap { actual =>
        if actual.origin != StructuredReconstruction.Origin.RequestedFill then Right(outcome)
        else
          val expectedPolicy = DecisionPolicyId.unsafe(
            "mapping-run:" + execution.reconstruction.profile.fingerprint.hex
          )
          for
            decision <- outcome.decision.toRight(MappingRefusal.BindingMismatch("fill.decision"))
            numerical <- decision.numerical.toRight(MappingRefusal.BindingMismatch("fill.decision"))
            posterior <- outcome.measures.posterior.toRight(
              MappingRefusal.BindingMismatch("fill.posterior")
            )
            _ <- posterior.binding.checkResult(execution.reconstruction.inference)
            _ <- Either.cond(
              posterior.unit == actual.unit &&
                numerical.basis.kind == MeasureKind.ModelPosterior &&
                decision.chosen == actual.anchor.map(Destination.Target(_)) &&
                decision.policy.contains(expectedPolicy),
              (),
              MappingRefusal.BindingMismatch("fill.execution")
            )
            retained = new Decision(
              numerical,
              decision.chosen,
              DecisionOrigin.GapFill(expectedPolicy),
              decision.calibration,
              decision.policy,
              decision.request
            )
          yield new UnitOutcome(
            outcome.unit,
            outcome.processing,
            outcome.localization,
            outcome.links,
            outcome.measures,
            Some(retained),
            outcome.stages
          )
      }

  /** A producer's score-free choice or explicit no-assignment, bound like a computed outcome.
    *
    * A choice (`ExternalDecode`) carries exactly one link, to the chosen destination; an abstention
    * carries none. Links name `CandidateSetId.categorical`, because no numeric basis keys exist.
    * Measures are empty, so no reader can find a score for this unit. Source and universe
    * membership of the destination is checked by `MappingResult.checked`. A raw argmax request is
    * refused: it has no categorical meaning.
    */
  def categorical(
      unit: RecallUnitId,
      request: DecisionRequest,
      links: Vector[MappingLink],
      stages: UnitStageRefs
  ): Either[MappingRefusal, UnitOutcome] =
    val foreignLink = links.exists(_.derivation match
      case d: MeasureDerivation.FromResult => d.unit != unit
      case MeasureDerivation.Supplied      => false)
    val candidateSet = CandidateSetId.categorical(stages.candidates, unit)
    val expected = request match
      case DecisionRequest.ExternalDecode(chosen, _) => Vector(chosen)
      case _                                         => Vector.empty
    request match
      case DecisionRequest.RawArgmax =>
        Left(
          MappingRefusal.InvalidValue(
            "decision.request",
            "a categorical decision needs an explicit choice or abstention"
          )
        )
      case DecisionRequest.Abstain(_, reason) if reason.trim.isEmpty =>
        Left(MappingRefusal.InvalidValue("decision.abstention", "reason must be nonempty"))
      case _ if foreignLink => Left(MappingRefusal.BindingMismatch("outcome.unit"))
      case _ if links.exists(_.inferenceStage != stages.inference) =>
        Left(MappingRefusal.BindingMismatch("outcome.inferenceStage"))
      case _ if links.exists(_.candidateSet != candidateSet) =>
        Left(MappingRefusal.CandidateSetMismatch(unit))
      case _ if links.map(_.destination) != expected =>
        Left(MappingRefusal.LinkDestinationsMismatch)
      case DecisionRequest.ExternalDecode(destination, policy) =>
        val localization = destination match
          case Destination.External(ExternalState.Unranked) => LocalizationStatus.Unranked
          case Destination.External(_)                      => LocalizationStatus.Nonlocalizable
          case Destination.Target(_)                        => LocalizationStatus.Located
        Right(
          categoricalOutcome(
            unit,
            localization,
            links,
            new Decision(
              Support.Categorical.derived(policy),
              Some(destination),
              DecisionOrigin.ProducerChoice(policy),
              Calibration.Unavailable.derived(),
              Some(policy),
              request
            ),
            stages
          )
        )
      case DecisionRequest.Abstain(policy, reason) =>
        Right(
          categoricalOutcome(
            unit,
            LocalizationStatus.NotComputed,
            links,
            new Decision(
              Support.Categorical.derived(policy),
              None,
              DecisionOrigin.Abstention(reason),
              Calibration.Unavailable.derived(),
              Some(policy),
              request
            ),
            stages
          )
        )

  private def categoricalOutcome(
      unit: RecallUnitId,
      localization: LocalizationStatus,
      links: Vector[MappingLink],
      decision: Decision,
      stages: UnitStageRefs
  ): UnitOutcome =
    new UnitOutcome(
      unit,
      ProcessingStatus.Complete,
      localization,
      links,
      UnitMeasures.empty,
      Some(decision),
      Some(stages)
    )

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
