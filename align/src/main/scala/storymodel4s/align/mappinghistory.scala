package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** A supplied historical decoder choice is not another observation or execution receipt. */
enum HistoricalDecode:
  case ArgmaxOnly
  case Decoded(chosen: Map[RecallUnitId, Option[SourceNodeRef]], label: String)

/** Additive adapter: preserve existing values and mark every stage's provenance unknown. */
object HistoricalMapping:
  def of(
      result: HsmmResult,
      recall: RecallGraph[Checked],
      view: SourceView,
      inventory: RecallInventory,
      source: SourceRepresentation,
      decode: HistoricalDecode
  ): Either[MappingRefusal, MappingResult] =
    val checkedView = MappingBindingRender.snapshot(view)
    val units = inventory.units.map(_.id)
    val label = decode match
      case HistoricalDecode.ArgmaxOnly        => "historical-hsmm/argmax-only"
      case HistoricalDecode.Decoded(_, label) => label
    val levels = source.targets.map(_.level).distinct.sorted
    val grain = if levels.isEmpty then TargetGrain.SingleLevel(0) else TargetGrain.Hierarchy(levels)
    for
      binding <- DerivationBinding.of(result, recall, inventory, checkedView, source)
      _ <- decode match
        case HistoricalDecode.Decoded(chosen, _) if chosen.keySet != units.toSet =>
          Left(MappingRefusal.OutcomeInventoryMismatch)
        case _ => Right(())
      policy <- DecisionPolicyId
        .from(label)
        .left
        .map(e => MappingRefusal.InvalidValue("historicalDecode.label", e.message))
      ledger <- Stage.values.toVector
        .traverse(stage =>
          StageEntry
            .of(stage, StageProvenance.unknown(UnknownProvenanceReason.HistoricalArtifact, None))
        )
        .flatMap(StageLedger.of)
      stages <- UnitStageRefs.of(
        ledger,
        ledger.at(Stage.Inference).head.id,
        ledger.at(Stage.Candidates).head.id,
        ledger.at(Stage.Decision).head.id
      )
      universe <- DeclaredUniverse.of(source.targets.map(_.ref), grain)
      policies <- MappingPolicies.of(
        InferencePolicy.HistoricalReconstruction(label),
        ContextPolicy.Unspecified("Historical context policy is not receipted"),
        CandidatePolicy.Unknown("Historical nomination provenance is unproven"),
        ReferencePrior.NotApplicable("Historical model posterior; no normalized-score prior"),
        DecisionPolicy.NotApplicable(
          "Historical choices carry no executed decision-policy receipt"
        ),
        universe
      )
      roles <- UnitRoles.of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(grain)
      )
      basis <- DecisionBasis.of(MeasureKind.ModelPosterior, None)
      outcomes <- units.traverse { unit =>
        for
          posterior <- ModelPosterior.of(result, unit, binding, stages.inference)
          candidates = CandidateSetId.of(
            stages.candidates,
            unit,
            posterior.mass.keySet.map(Destination.of)
          )
          states = (posterior.mass.keySet ++ result.costs
            .get(unit)
            .toVector
            .flatMap(_.keys)).toVector.sorted
          links <- states.traverse(state =>
            MappingLink.fromResult(
              result,
              binding,
              recall,
              checkedView,
              source,
              unit,
              state,
              stages,
              candidates
            )
          )
          raw <- RawScores.fromCosts(result, unit, binding, ledger.at(Stage.Scoring).head.id)
          measures <- UnitMeasures.of(Vector(raw), None, None, Some(posterior))
          request = decode match
            case HistoricalDecode.ArgmaxOnly         => DecisionRequest.RawArgmax
            case HistoricalDecode.Decoded(chosen, _) =>
              chosen(unit) match
                case Some(ref) => DecisionRequest.ExternalDecode(Destination.Target(ref), policy)
                case None      =>
                  DecisionRequest.Abstain(policy, "Historical decoder supplied no destination")
          outcome <- UnitOutcome.computed(unit, measures, links, basis, request, stages)
        yield outcome
      }
      record <- MappingResult.checked(inventory, source, policies, roles, ledger, outcomes)
    yield record
