package storymodel4s.align

import cats.data.NonEmptySet
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

object MappingTolerance:
  /** Shape check only, matching the documented HSMM row-sum tolerance. No values are rescaled. */
  val RowSum: Double = 1e-9

enum ScoreDirection:
  case HigherIsBetter, LowerIsBetter

final class RawScores private (
    val channel: String,
    val direction: ScoreDirection,
    val scale: String,
    val values: Map[Destination, Double],
    val stage: StageEntryId,
    val derivation: MeasureDerivation
)
object RawScores:
  def of(
      channel: String,
      direction: ScoreDirection,
      scale: String,
      values: Map[Destination, Double],
      stage: StageEntryId
  ): Either[MappingRefusal, RawScores] =
    if channel.trim.isEmpty || scale.trim.isEmpty || !values.values.forall(_.isFinite) then
      Left(
        MappingRefusal
          .InvalidValue("rawScores", "channel and scale must be nonempty and values finite")
      )
    else Right(new RawScores(channel, direction, scale, values, stage, MeasureDerivation.Supplied))

  def fromCosts(
      result: HsmmResult,
      unit: RecallUnitId,
      binding: DerivationBinding,
      stage: StageEntryId
  ): Either[MappingRefusal, RawScores] =
    for
      derivation <- MeasureDerivation.fromResult(result, unit, binding)
      row <- result.costs.get(unit).toRight(MappingRefusal.MissingRow(unit))
      entries = row.toVector.map((s, c) => Destination.of(s) -> c.total)
      _ <- Either.cond(
        entries.map(_._1).distinct.size == entries.size && entries.forall(_._2.isFinite),
        (),
        MappingRefusal.InvalidValue("rawScores", "cost projection must be unique and finite")
      )
    yield new RawScores(
      "hsmm.local-cost",
      ScoreDirection.LowerIsBetter,
      "local-cost",
      entries.toMap,
      stage,
      derivation
    )

final class NormalizedScoreMass private (
    val universe: TargetUniverseId,
    val prior: ReferencePriorId,
    val temperature: Double,
    val mass: Map[Destination, Double],
    val stage: StageEntryId
)
object NormalizedScoreMass:
  def of(
      universe: TargetUniverseId,
      prior: ReferencePriorId,
      temperature: Double,
      mass: Map[Destination, Double],
      stage: StageEntryId
  ): Either[MappingRefusal, NormalizedScoreMass] =
    val sum = mass.toVector.sortBy(_._1.key).map(_._2).sum
    if !temperature.isFinite || !(temperature > 0.0) || !MappingMeasureChecks.mass(mass.values) then
      Left(
        MappingRefusal.InvalidValue(
          "normalizedScoreMass",
          "temperature must be finite and positive; masses finite and nonnegative"
        )
      )
    else if !(math.abs(sum - 1.0) <= MappingTolerance.RowSum) then
      Left(
        MappingRefusal
          .InvalidValue("normalizedScoreMass", "row must sum to one within the declared tolerance")
      )
    else Right(new NormalizedScoreMass(universe, prior, temperature, mass, stage))

/** Transport carries its declared row budget; it is not a normalized posterior. */
final class TransportMass private (
    val rowBudget: Double,
    val mass: Map[Destination, Double],
    val stage: StageEntryId
)
object TransportMass:
  def of(
      rowBudget: Double,
      mass: Map[Destination, Double],
      stage: StageEntryId
  ): Either[MappingRefusal, TransportMass] =
    if !rowBudget.isFinite || !(rowBudget >= 0.0) || !MappingMeasureChecks.mass(mass.values) then
      Left(
        MappingRefusal
          .InvalidValue("transportMass", "budget and quantities must be finite and nonnegative")
      )
    else Right(new TransportMass(rowBudget, mass, stage))

final class ModelPosterior private (
    val unit: RecallUnitId,
    val mass: Map[AlignState, Double],
    val stage: StageEntryId,
    val binding: DerivationBinding
)
object ModelPosterior:
  def of(
      result: HsmmResult,
      unit: RecallUnitId,
      binding: DerivationBinding,
      stage: StageEntryId
  ): Either[MappingRefusal, ModelPosterior] =
    binding.checkResult(result).flatMap { _ =>
      result.posterior
        .row(unit)
        .toRight(MappingRefusal.UnknownUnit(unit))
        .map(row => new ModelPosterior(unit, row.mass, stage, binding))
    }

sealed trait CalibratedEvent
object CalibratedEvent:
  final case class ChosenDecisionCorrect(policy: DecisionPolicyId) extends CalibratedEvent

/** Reserved. G1 offers no constructor or decoder for calibrated authority. */
final class CalibratedProbability private (
    val event: CalibratedEvent,
    val probability: Probability,
    val artifact: CalibrationArtifactId
)

final class UnitMeasures private (
    val raw: Vector[RawScores],
    val normalized: Option[NormalizedScoreMass],
    val transport: Option[TransportMass],
    val posterior: Option[ModelPosterior]
):
  def destinations: Set[Destination] =
    raw.flatMap(_.values.keys).toSet ++ normalized.toVector.flatMap(_.mass.keys) ++
      transport.toVector.flatMap(_.mass.keys) ++ posterior.toVector.flatMap(
        _.mass.keys.map(Destination.of)
      )
object UnitMeasures:
  def of(
      raw: Vector[RawScores],
      normalized: Option[NormalizedScoreMass],
      transport: Option[TransportMass],
      posterior: Option[ModelPosterior]
  ): Either[MappingRefusal, UnitMeasures] =
    if raw.map(_.channel).distinct.size != raw.size then
      Left(MappingRefusal.InvalidValue("unitMeasures.raw", "channels must be unique"))
    else Right(new UnitMeasures(raw, normalized, transport, posterior))
  val empty: UnitMeasures = new UnitMeasures(Vector.empty, None, None, None)

private[align] object MappingMeasureChecks:
  def mass(values: Iterable[Double]): Boolean = values.forall(v => v.isFinite && v >= 0.0)

enum FidelityNotAssessedReason:
  case NoGateEvaluation, ScopeUndeclared
enum TermSupportNotComputedReason:
  case NoCostBreakdown

type GateOutcome = MappingLink.Gate
object GateOutcome:
  export MappingLink.Gate.{NotGated, NoContradictionDetected, Contradicted}
type FidelityStatus = MappingLink.Fidelity
object FidelityStatus:
  export MappingLink.Fidelity.{NotAssessed, NotApplicable, Assessed}
type TermSupportStatus = MappingLink.TermSupport
object TermSupportStatus:
  export MappingLink.TermSupport.{NotComputed, Evaluated}

final class MappingLink private (
    val destination: Destination,
    val gate: GateOutcome,
    val fidelity: FidelityStatus,
    val termSupport: TermSupportStatus,
    val inferenceStage: StageEntryId,
    val candidateSet: CandidateSetId,
    val derivation: MeasureDerivation
)
object MappingLink:
  sealed trait Gate
  object Gate:
    case object NotGated extends Gate
    final class NoContradictionDetected private () extends Gate
    object NoContradictionDetected:
      private[MappingLink] def derived(): NoContradictionDetected = new NoContradictionDetected()
    final class Contradicted private (val facets: NonEmptySet[Facet]) extends Gate
    object Contradicted:
      private[MappingLink] def derived(facets: NonEmptySet[Facet]): Contradicted = new Contradicted(
        facets
      )
  sealed trait Fidelity
  object Fidelity:
    final class NotAssessed private (val reason: FidelityNotAssessedReason) extends Fidelity
    object NotAssessed:
      private[MappingLink] def derived(reason: FidelityNotAssessedReason): NotAssessed =
        new NotAssessed(reason)
    case object NotApplicable extends Fidelity
    final class Assessed private (val report: FidelityReport) extends Fidelity
    object Assessed:
      private[MappingLink] def derived(report: FidelityReport): Assessed = new Assessed(report)
  sealed trait TermSupport
  object TermSupport:
    final class NotComputed private (val reason: TermSupportNotComputedReason) extends TermSupport
    object NotComputed:
      private[MappingLink] def derived(): NotComputed = new NotComputed(
        TermSupportNotComputedReason.NoCostBreakdown
      )
    final class Evaluated private (val assessment: SupportAssessment) extends TermSupport
    object Evaluated:
      private[MappingLink] def derived(assessment: SupportAssessment): Evaluated = new Evaluated(
        assessment
      )

  def ungated(
      destination: Destination,
      stages: UnitStageRefs,
      candidateSet: CandidateSetId
  ): MappingLink =
    new MappingLink(
      destination,
      Gate.NotGated,
      Fidelity.NotAssessed.derived(FidelityNotAssessedReason.NoGateEvaluation),
      TermSupport.NotComputed.derived(),
      stages.inference,
      candidateSet,
      MeasureDerivation.Supplied
    )

  def fromResult(
      result: HsmmResult,
      binding: DerivationBinding,
      recall: RecallGraph[Checked],
      view: SourceView,
      source: SourceRepresentation,
      unit: RecallUnitId,
      state: AlignState,
      stages: UnitStageRefs,
      candidateSet: CandidateSetId
  ): Either[MappingRefusal, MappingLink] =
    val checkedView = MappingBindingRender.snapshot(view)
    for
      derivation <- MeasureDerivation.fromResult(result, unit, binding)
      _ <- binding.checkContext(recall, checkedView, source)
      recalled <- recall.byId.get(unit).toRight(MappingRefusal.UnknownUnit(unit))
      cost <- result.costs
        .get(unit)
        .flatMap(_.get(state))
        .toRight(MappingRefusal.MissingCost(unit, state))
      statuses <- state.anchor match
        case None =>
          Right(
            (
              Gate.NotGated,
              Fidelity.NotApplicable,
              TermSupport.Evaluated.derived(SupportAssessment.externalState)
            )
          )
        case Some(ref) =>
          for
            target <- source.target(ref).toRight(MappingRefusal.UnknownTarget(ref))
            node <- checkedView.node(ref).toRight(MappingRefusal.UnknownTarget(ref))
            admitted <- result.admissibility
              .get(unit)
              .flatMap(_.get(ref))
              .toRight(MappingRefusal.UnknownTarget(ref))
            gate <-
              if admitted.faithful then Right(Gate.NoContradictionDetected.derived())
              else
                admitted.distortion
                  .map(Gate.Contradicted.derived)
                  .toRight(
                    MappingRefusal.InvalidValue("link.gate", "contradicted admission has no facets")
                  )
            mode <- state.mode.toRight(
              MappingRefusal.InvalidValue("link.mode", "anchored state must have a mode")
            )
            fidelity = target.propositional match
              case PropositionalScope.Declared =>
                Fidelity.Assessed.derived(
                  FidelityFacets.assess(
                    recalled.proposition,
                    node.copy(propositional = target.propositional),
                    mode
                  )
                )
              case PropositionalScope.Undeclared(_) =>
                Fidelity.NotAssessed.derived(FidelityNotAssessedReason.ScopeUndeclared)
          yield (gate, fidelity, TermSupport.Evaluated.derived(cost.support))
    yield new MappingLink(
      Destination.of(state),
      statuses._1,
      statuses._2,
      statuses._3,
      stages.inference,
      candidateSet,
      derivation
    )
