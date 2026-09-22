package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.Checksum
import storymodel4s.features.CanonicalDouble
import storymodel4s.recall.{RecallInventory, RecallUnitId}

type DerivationSource = MappingResult.Derivation
object DerivationSource:
  export MappingResult.Derivation.{NoDerivedValues, Bound}

/** One checked inventory/source join. A bound result proves content identity, not execution. */
final class MappingResult private (
    val inventory: RecallInventory,
    val source: SourceRepresentation,
    val policies: MappingPolicies,
    val roles: UnitRoles,
    val ledger: StageLedger,
    val derivation: DerivationSource,
    val outcomes: Vector[UnitOutcome]
):
  def outcome(unit: RecallUnitId): Option[UnitOutcome] = outcomes.find(_.unit == unit)
  def digest: Checksum = MappingResultRender.digest(this)

object MappingResult:
  sealed trait Derivation
  object Derivation:
    case object NoDerivedValues extends Derivation
    final class Bound private (val binding: DerivationBinding) extends Derivation
    object Bound:
      private[MappingResult] def derived(binding: DerivationBinding): Bound = new Bound(binding)

  def checked(
      inventory: RecallInventory,
      source: SourceRepresentation,
      policies: MappingPolicies,
      roles: UnitRoles,
      ledger: StageLedger,
      outcomes: Vector[UnitOutcome]
  ): Either[MappingRefusal, MappingResult] =
    val universe = policies.universe.targets.toSet
    val dictionary = source.targets.map(_.ref).toSet
    val mentioned = outcomes
      .flatMap { outcome =>
        outcome.measures.destinations.toVector ++ outcome.links.map(_.destination) ++
          outcome.decision.toVector.flatMap { decision =>
            decision.chosen.toVector ++ (decision.request match
              case DecisionRequest.ExternalDecode(chosen, _) => Vector(chosen)
              case _                                         => Vector.empty)
          }
      }
      .collect { case Destination.Target(ref) => ref }
      .distinct
      .sorted
    val unknown = (policies.universe.targets ++ mentioned).filterNot(dictionary).sorted.headOption
    val wrongGrain = policies.universe.targets.exists { ref =>
      source.target(ref).exists { target =>
        policies.universe.grain match
          case TargetGrain.SingleLevel(level) => target.level != level
          case TargetGrain.Hierarchy(levels)  => !levels.contains(target.level)
      }
    }
    val wrongRoles = Vector(roles.inference, roles.organization, roles.projection).exists {
      case AnalysisGrain.InferenceUnit(segmentation) => segmentation != inventory.segmentation
      case _                                         => false
    }
    val normalized = outcomes.flatMap(_.measures.normalized)
    val wrongPrior = normalized.exists { measure =>
      policies.referencePrior match
        case ReferencePrior.Declared(id)     => measure.prior != id
        case ReferencePrior.NotApplicable(_) => true
    }
    val scopeFailures = outcomes
      .flatMap(_.links)
      .collect {
        case link if link.fidelity.isInstanceOf[FidelityStatus.Assessed] => link.destination
      }
      .collect {
        case Destination.Target(ref)
            if source.target(ref).exists(_.propositional != PropositionalScope.Declared) =>
          ref
      }
      .sorted
    def bindingOf(value: MeasureDerivation): Vector[DerivationBinding] = value match
      case derived: MeasureDerivation.FromResult => Vector(derived.binding)
      case MeasureDerivation.Supplied            => Vector.empty
    val bindings = outcomes.flatMap { outcome =>
      outcome.measures.raw.flatMap(r => bindingOf(r.derivation)) ++
        outcome.measures.posterior.toVector.map(_.binding) ++
        outcome.links.flatMap(l => bindingOf(l.derivation))
    }.distinct
    def stageExists(id: StageEntryId): Either[MappingRefusal, Unit] =
      ledger.get(id).toRight(MappingRefusal.DanglingStageEntry(id)).map(_ => ())
    def checkStages(outcome: UnitOutcome): Either[MappingRefusal, Unit] =
      for
        _ <- outcome.stages.traverse_(s =>
          UnitStageRefs.of(ledger, s.inference, s.candidates, s.decision).map(_ => ())
        )
        _ <- (outcome.measures.raw.map(_.stage) ++
          outcome.measures.normalized.toVector.map(_.stage) ++
          outcome.measures.transport.toVector.map(_.stage) ++
          outcome.measures.posterior.toVector.map(_.stage) ++
          outcome.links.map(_.inferenceStage)).traverse_(stageExists)
      yield ()
    for
      _ <- Either.cond(
        outcomes.map(_.unit) == inventory.units.map(_.id),
        (),
        MappingRefusal.OutcomeInventoryMismatch
      )
      _ <- unknown.toLeft(()).left.map(MappingRefusal.UnknownTarget(_))
      _ <- Either.cond(
        !wrongGrain && mentioned.forall(universe),
        (),
        MappingRefusal.UniverseMismatch
      )
      _ <- Either.cond(!wrongRoles, (), MappingRefusal.RolesMismatch)
      _ <- Either.cond(
        normalized.forall(_.universe == policies.universe.id),
        (),
        MappingRefusal.UniverseMismatch
      )
      _ <- Either.cond(!wrongPrior, (), MappingRefusal.PriorMismatch)
      _ <- outcomes.traverse_(checkStages)
      _ <- scopeFailures.headOption.toLeft(()).left.map(MappingRefusal.ScopeUndeclared(_))
      _ <- Either.cond(bindings.size <= 1, (), MappingRefusal.BindingMismatch("record.derivation"))
      _ <- bindings.traverse_ { binding =>
        if binding.inventoryDigest != inventory.digest then
          Left(MappingRefusal.BindingMismatch("inventoryDigest"))
        else if binding.viewFingerprint != source.viewFingerprint then
          Left(MappingRefusal.BindingMismatch("viewFingerprint"))
        else if binding.scopeDigest != source.scopeDigest then
          Left(MappingRefusal.BindingMismatch("scopeDigest"))
        else Right(())
      }
    yield new MappingResult(
      inventory,
      source,
      policies,
      roles,
      ledger,
      bindings.headOption
        .fold[DerivationSource](Derivation.NoDerivedValues)(Derivation.Bound.derived),
      outcomes
    )

/** Content identity is independent of codec layout and reads the current immutable fields. */
private[align] object MappingResultRender:
  import MappingRender.{sequence, optional}
  private def number(value: Double): String = CanonicalDouble.render(value)
  private def weighted(values: Map[Destination, Double]): String = sequence(
    values.toVector
      .sortBy(_._1.key)
      .map((destination, value) => sequence(Vector(destination.key, number(value))))
  )
  private def origin(value: MeasureDerivation): String = value match
    case MeasureDerivation.Supplied            => "supplied"
    case derived: MeasureDerivation.FromResult =>
      sequence(Vector("bound", derived.binding.digest.hex, derived.unit.value))
  private def grain(value: AnalysisGrain): String = value match
    case AnalysisGrain.InferenceUnit(segmentation) =>
      sequence(Vector("inference-unit", segmentation.digest.hex))
    case AnalysisGrain.Word           => "word"
    case AnalysisGrain.Targets(value) => sequence(Vector("targets", MappingRender.grain(value)))
  private def policies(value: MappingPolicies): String = sequence(
    Vector(
      value.inference match
        case InferencePolicy.HistoricalReconstruction(label) =>
          sequence(Vector("historical-reconstruction", label))
        case InferencePolicy.Unspecified(reason) => sequence(Vector("unspecified", reason))
      ,
      value.context match
        case ContextPolicy.Unspecified(reason) => sequence(Vector("unspecified", reason))
      ,
      value.candidate match
        case CandidatePolicy.Unknown(reason)        => sequence(Vector("unknown", reason))
        case CandidatePolicy.Declared(id, coverage) =>
          sequence(
            Vector(
              "declared",
              id.value,
              coverage match
                case CandidateCoverage.Complete         => "complete"
                case CandidateCoverage.Truncated(count) =>
                  sequence(Vector("truncated", count.toString))
                case CandidateCoverage.Unknown(reason) => sequence(Vector("unknown", reason))
            )
          )
      ,
      value.referencePrior match
        case ReferencePrior.Declared(id)          => sequence(Vector("declared", id.value))
        case ReferencePrior.NotApplicable(reason) => sequence(Vector("not-applicable", reason))
      ,
      value.decision match
        case DecisionPolicy.Declared(id)          => sequence(Vector("declared", id.value))
        case DecisionPolicy.NotApplicable(reason) => sequence(Vector("not-applicable", reason))
      ,
      value.universe.id.digest.hex
    )
  )
  private def measures(value: UnitMeasures): String = sequence(
    Vector(
      sequence(value.raw.map { raw =>
        sequence(
          Vector(
            raw.channel,
            raw.direction.toString,
            raw.scale,
            weighted(raw.values),
            raw.stage.digest.hex,
            origin(raw.derivation)
          )
        )
      }),
      optional(
        value.normalized.map(m =>
          sequence(
            Vector(
              m.universe.digest.hex,
              m.prior.value,
              number(m.temperature),
              weighted(m.mass),
              m.stage.digest.hex
            )
          )
        )
      ),
      optional(
        value.transport.map(m =>
          sequence(Vector(number(m.rowBudget), weighted(m.mass), m.stage.digest.hex))
        )
      ),
      optional(value.posterior.map { posterior =>
        sequence(
          Vector(
            posterior.unit.value,
            posterior.stage.digest.hex,
            posterior.binding.digest.hex,
            sequence(
              posterior.mass.toVector
                .map((state, mass) => sequence(AlignState.keyParts(state)) -> number(mass))
                .sortBy(_._1)
                .map((state, mass) => sequence(Vector(state, mass)))
            )
          )
        )
      })
    )
  )
  private def link(value: MappingLink): String = sequence(
    Vector(
      value.destination.key,
      value.inferenceStage.digest.hex,
      value.candidateSet.digest.hex,
      origin(value.derivation),
      value.gate match
        case GateOutcome.NotGated                   => "not-gated"
        case _: GateOutcome.NoContradictionDetected => "no-contradiction-detected"
        case gate: GateOutcome.Contradicted         =>
          sequence(
            Vector("contradicted", sequence(gate.facets.toSortedSet.toVector.map(_.toString)))
          )
      ,
      value.fidelity match
        case FidelityStatus.NotApplicable       => "not-applicable"
        case status: FidelityStatus.NotAssessed =>
          sequence(Vector("not-assessed", status.reason.toString))
        case status: FidelityStatus.Assessed =>
          sequence(
            Vector(
              "assessed",
              sequence(
                status.report.verdicts.toVector
                  .sortBy(_._1.ordinal)
                  .map((facet, verdict) => sequence(Vector(facet.toString, verdict.toString)))
              )
            )
          )
      ,
      value.termSupport match
        case status: TermSupportStatus.NotComputed =>
          sequence(Vector("not-computed", status.reason.toString))
        case status: TermSupportStatus.Evaluated =>
          sequence(Vector("evaluated", MappingBindingRender.support(status.assessment)))
    )
  )
  private def request(value: DecisionRequest): String = value match
    case DecisionRequest.RawArgmax                      => "raw-argmax"
    case DecisionRequest.ExternalDecode(chosen, policy) =>
      sequence(Vector("external-decode", chosen.key, policy.value))
    case DecisionRequest.Abstain(policy, reason) =>
      sequence(Vector("abstain", policy.value, reason))
  private def decision(value: UnitDecision): String = sequence(
    Vector(
      value.basis.kind.toString,
      optional(value.basis.channel),
      optional(value.chosen.map(_.key)),
      value.origin match
        case DecisionOrigin.RawArgmax                => "raw-argmax"
        case DecisionOrigin.StructuredDecode(policy) =>
          sequence(Vector("structured-decode", policy.value))
        case DecisionOrigin.GapFill(policy)    => sequence(Vector("gap-fill", policy.value))
        case DecisionOrigin.Abstention(reason) => sequence(Vector("abstention", reason))
      ,
      optional(
        value.rawArgmax.map((destination, score) =>
          sequence(Vector(destination.key, number(score)))
        )
      ),
      value.decodedMass match
        case mass: DecodedTargetMass.InCandidateSupport =>
          sequence(Vector("in-candidate-support", number(mass.value)))
        case _: DecodedTargetMass.OutsideCandidateSupport => "outside-candidate-support"
        case _: DecodedTargetMass.NoDecision              => "no-decision"
      ,
      value.calibration match
        case status: DecisionCalibration.Unavailable =>
          sequence(Vector("unavailable", status.reason.toString))
        case status: DecisionCalibration.Calibrated =>
          sequence(
            Vector(
              "calibrated",
              number(status.probability.probability.value),
              status.probability.artifact.value,
              status.probability.event match
                case CalibratedEvent.ChosenDecisionCorrect(policy) =>
                  sequence(Vector("chosen-decision-correct", policy.value))
            )
          )
      ,
      optional(value.policy.map(_.value)),
      request(value.request)
    )
  )
  private def outcome(value: UnitOutcome): String = sequence(
    Vector(
      value.unit.value,
      value.processing match
        case ProcessingStatus.Complete                      => "complete"
        case ProcessingStatus.ExcludedByInputPolicy(reason) => sequence(Vector("excluded", reason))
        case ProcessingStatus.Failed(failure)               =>
          sequence(
            Vector(
              "failed",
              failure match
                case ProcessingFailure.ProviderFailure(detail) =>
                  sequence(Vector("provider-failure", detail))
                case ProcessingFailure.InvalidOutput(detail) =>
                  sequence(Vector("invalid-output", detail))
                case ProcessingFailure.InferenceRefused(detail) =>
                  sequence(Vector("inference-refused", detail))
            )
          )
      ,
      value.localization.toString,
      measures(value.measures),
      sequence(value.links.map(link)),
      optional(value.decision.map(decision)),
      optional(
        value.stages.map(s =>
          sequence(Vector(s.inference.digest.hex, s.candidates.digest.hex, s.decision.digest.hex))
        )
      )
    )
  )
  def digest(value: MappingResult): Checksum = MappingRender.digest(
    Vector(
      "mapping-result/v1",
      value.inventory.digest.hex,
      value.source.digest.hex,
      policies(value.policies),
      sequence(
        Vector(
          grain(value.roles.inference),
          grain(value.roles.organization),
          grain(value.roles.projection)
        )
      ),
      sequence(value.ledger.entries.map(_.id.digest.hex)),
      value.derivation match
        case DerivationSource.NoDerivedValues => "none"
        case bound: DerivationSource.Bound    => sequence(Vector("bound", bound.binding.digest.hex))
      ,
      sequence(value.outcomes.map(outcome))
    )
  )
