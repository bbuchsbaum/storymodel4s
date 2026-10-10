package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.Checksum
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Execute both estimands over the same immutable evidence before publishing either projection. */
object MappingRun:
  enum Member:
    case Reference, Reconstruction
    case Weighted(selection: StructuralSelection)

  /** Execution authority can only originate in the two actual producers. */
  final class Execution private[MappingRun] (
      val recall: RecallGraph[Checked],
      val view: SourceView,
      val inventory: RecallInventory,
      val source: SourceRepresentation,
      val reference: LocalReference.Result,
      val reconstruction: StructuredReconstruction.Result
  ):
    val identity: Checksum = MappingRender.digest(
      Vector(
        "mapping-execution/v1",
        inventory.digest.hex,
        source.digest.hex,
        reference.evidence.identity.checksum.hex,
        reference.profile.fingerprint.hex,
        reconstruction.profile.fingerprint.hex,
        reconstruction.grouping.fingerprint.hex,
        MappingRender.optional(reconstruction.fill.map(_.fingerprint.hex))
      )
    )
    lazy val referenceRecord: Either[MappingRefusal, MappingResult] =
      MappingResult.executed(this, Member.Reference)
    lazy val reconstructionRecord: Either[MappingRefusal, MappingResult] =
      MappingResult.executed(this, Member.Reconstruction)
    def record(member: Member): Either[MappingRefusal, MappingResult] = member match
      case Member.Reference           => referenceRecord
      case Member.Reconstruction      => reconstructionRecord
      case Member.Weighted(selection) =>
        if selection.execution.identity != identity then
          Left(MappingRefusal.BindingMismatch("weighted execution"))
        else if selection.weight.isReference then referenceRecord
        else if selection.weight.isReconstruction then reconstructionRecord
        else MappingResult.executed(this, member)
    def weighted(weight: StructuralWeight): Either[AlignError, StructuralSelection] =
      StructuralSelection.of(this, weight)
    private[align] def receipt(member: Member, stage: Stage): StageReceipt =
      val label = member match
        case Member.Weighted(selection) =>
          s"Weighted/${selection.profile.fingerprint.hex}/${selection.weight}"
        case _ => member.toString
      StageReceipt
        .of(
          stage,
          s"mapping-run/v1/$label/$stage",
          member match
            case Member.Weighted(selection) => selection.identity
            case _                          => identity,
          Vector(reference.evidence.identity.checksum, inventory.digest, source.digest),
          ProviderStatus.NotApplicable("portable deterministic execution")
        )
        .toOption
        .get
    override def equals(other: Any): Boolean = other match
      case e: Execution => identity == e.identity
      case _            => false
    override def hashCode: Int = identity.hashCode
    override def toString: String = s"MappingRun(${identity.short()})"

  def compute(
      recall: RecallGraph[Checked],
      view: SourceView,
      inventory: RecallInventory,
      source: SourceRepresentation,
      evidence: LocalEvidence,
      reference: LocalReference.Profile,
      reconstruction: StructuredReconstruction.Profile,
      grouping: StructuredReconstruction.Grouping,
      fill: Option[StructuredReconstruction.FillRequest] = None
  ): Either[AlignError, Execution] =
    val captured = MappingBindingRender.snapshot(view)
    for
      _ <- Either.cond(
        inventory.describes(recall) &&
          source.viewFingerprint == ViewFingerprint.of(captured) &&
          source.scopeDigest == MappingSourceRender.scope(captured.nodes),
        (),
        AlignError.InvalidConfig("mapping run", "foreign inventory or source")
      )
      local <- LocalReference.compute(recall, captured, evidence, reference)
      structured <- StructuredReconstruction.compute(
        recall,
        captured,
        evidence,
        reconstruction,
        grouping,
        fill
      )
    yield new Execution(recall, captured, inventory, source, local, structured)

  private[align] final case class Parts(
      policies: MappingPolicies,
      roles: UnitRoles,
      ledger: StageLedger,
      outcomes: Vector[UnitOutcome]
  )

  /** All rows and receipts come from the retained producer outputs, never caller-supplied parts. */
  private[align] def parts(e: Execution, member: Member): Either[MappingRefusal, Parts] =
    val isReference = member == Member.Reference
    val profile = member match
      case Member.Reference           => e.reference.profile.fingerprint
      case Member.Reconstruction      => e.reconstruction.profile.fingerprint
      case Member.Weighted(selection) => selection.profile.fingerprint
    val decisionId = DecisionPolicyId.unsafe("mapping-run:" + profile.hex)
    val prior = ReferencePriorId.unsafe("uniform-admitted-states/v1")
    for
      ledger <- Stage.values.toVector
        .traverse { stage =>
          val provenance =
            if stage == Stage.Inference || stage == Stage.Decision then
              StageProvenance.executed(e, member, stage)
            else StageProvenance.unknown(UnknownProvenanceReason.NominationProvenanceUnproven, None)
          StageEntry.of(stage, provenance)
        }
        .flatMap(StageLedger.of)
      stages <- UnitStageRefs.of(
        ledger,
        ledger.at(Stage.Inference).head.id,
        ledger.at(Stage.Candidates).head.id,
        ledger.at(Stage.Decision).head.id
      )
      universe <-
        if isReference then Right(e.reference.profile.universe)
        else
          DeclaredUniverse.of(
            e.source.targets.map(_.ref),
            TargetGrain.Hierarchy(e.source.targets.map(_.level).distinct.sorted)
          )
      policies <- MappingPolicies.of(
        if isReference then
          InferencePolicy.Unspecified("local-reference/shifted-exponential-cost/v1")
        else
          member match
            case Member.Weighted(selection) =>
              InferencePolicy.Unspecified(
                s"structural-coefficients/sherlock/v1;lambda=${selection.weight};profile=${profile.hex}"
              )
            case _ =>
              InferencePolicy.HistoricalReconstruction("structured-reconstruction:" + profile.hex),
        ContextPolicy.Unspecified("exact originating evidence retained by execution"),
        CandidatePolicy.Unknown("upstream registration belongs to enclosing run"),
        if isReference then ReferencePrior.Declared(prior)
        else ReferencePrior.NotApplicable("model posterior"),
        DecisionPolicy.Declared(decisionId),
        universe
      )
      roles <- UnitRoles.of(
        AnalysisGrain.InferenceUnit(e.inventory.segmentation),
        AnalysisGrain.InferenceUnit(e.inventory.segmentation),
        AnalysisGrain.Targets(universe.grain)
      )
      outcomes <- member match
        case Member.Reference =>
          e.reference.outcomes.traverse {
            case n: LocalReference.NotComputed =>
              Right(UnitOutcome.excluded(n.unit, "local-reference:" + n.reason.toString))
            case c: LocalReference.Computed =>
              val mass = c.mass.toVector.groupMapReduce((s, _) => Destination.of(s))(_._2)(_ + _)
              val candidates = CandidateSetId.of(stages.candidates, c.unit, mass.keySet)
              for
                normalized <- NormalizedScoreMass.of(
                  universe.id,
                  prior,
                  e.reference.profile.temperature,
                  mass,
                  stages.inference
                )
                measures <- UnitMeasures.of(Vector.empty, Some(normalized), None, None)
                basis <- DecisionBasis.of(MeasureKind.NormalizedScoreMass, None)
                links <- c.mass.keys.toVector.sorted.traverse(state =>
                  MappingLink.fromEvidence(
                    e.reference.evidence,
                    e.recall,
                    e.view,
                    e.source,
                    c.unit,
                    state,
                    stages,
                    candidates
                  )
                )
                outcome <- UnitOutcome.computed(
                  c.unit,
                  measures,
                  links,
                  basis,
                  DecisionRequest.RawArgmax,
                  stages
                )
              yield outcome
          }
        case Member.Weighted(selection) =>
          selection.inference
            .toRight(MappingRefusal.BindingMismatch("interior inference"))
            .flatMap { result =>
              for
                binding <- DerivationBinding.of(result, e.recall, e.inventory, e.view, e.source)
                rows <- e.inventory.units.traverse { unit =>
                  for
                    posterior <- ModelPosterior.of(result, unit.id, binding, stages.inference)
                    candidates = CandidateSetId.of(
                      stages.candidates,
                      unit.id,
                      posterior.mass.keys.map(Destination.of).toSet
                    )
                    states = (posterior.mass.keySet ++ result.costs
                      .get(unit.id)
                      .toVector
                      .flatMap(_.keys)).toVector.sorted
                    links <- states.traverse(
                      MappingLink.fromResult(
                        result,
                        binding,
                        e.recall,
                        e.view,
                        e.source,
                        unit.id,
                        _,
                        stages,
                        candidates
                      )
                    )
                    raw <- RawScores.fromCosts(
                      result,
                      unit.id,
                      binding,
                      ledger.at(Stage.Scoring).head.id
                    )
                    measures <- UnitMeasures.of(Vector(raw), None, None, Some(posterior))
                    basis <- DecisionBasis.of(MeasureKind.ModelPosterior, None)
                    outcome <- UnitOutcome.computed(
                      unit.id,
                      measures,
                      links,
                      basis,
                      DecisionRequest.RawArgmax,
                      stages
                    )
                  yield outcome
                }
              yield rows
            }
        case Member.Reconstruction =>
          val result = e.reconstruction.inference
          for
            binding <- DerivationBinding.of(result, e.recall, e.inventory, e.view, e.source)
            rows <- e.reconstruction.decisions.traverse { d =>
              for
                posterior <- ModelPosterior.of(result, d.unit, binding, stages.inference)
                candidates = CandidateSetId.of(
                  stages.candidates,
                  d.unit,
                  posterior.mass.keys.map(Destination.of).toSet
                )
                states = (posterior.mass.keySet ++ result.costs
                  .get(d.unit)
                  .toVector
                  .flatMap(_.keys)).toVector.sorted
                links <- states.traverse(
                  MappingLink.fromResult(
                    result,
                    binding,
                    e.recall,
                    e.view,
                    e.source,
                    d.unit,
                    _,
                    stages,
                    candidates
                  )
                )
                raw <- RawScores.fromCosts(
                  result,
                  d.unit,
                  binding,
                  ledger.at(Stage.Scoring).head.id
                )
                measures <- UnitMeasures.of(Vector(raw), None, None, Some(posterior))
                basis <- DecisionBasis.of(MeasureKind.ModelPosterior, None)
                request = d.anchor match
                  case Some(ref) =>
                    DecisionRequest.ExternalDecode(Destination.Target(ref), decisionId)
                  case None =>
                    d.rawArgmax match
                      case Some(state) if !state.isSource =>
                        DecisionRequest.ExternalDecode(Destination.of(state), decisionId)
                      case _ =>
                        DecisionRequest.Abstain(
                          decisionId,
                          "structured decoder supplied no destination"
                        )
                outcome <- UnitOutcome.computed(d.unit, measures, links, basis, request, stages)
                retained <- UnitOutcome.retainRequestedFill(outcome, e)
              yield retained
            }
          yield rows
    yield Parts(policies, roles, ledger, outcomes)
