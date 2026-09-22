package storymodel4s.codec

import io.circe.Json
import io.circe.syntax.*
import storymodel4s.align.*
import HsmmResultCodec.given
import MappingJson.*

private[codec] object MappingRecordWire:
  def grain(value: TargetGrain): Json = value match
    case TargetGrain.SingleLevel(level) => tagged("single-level", "level" -> int(level))
    case TargetGrain.Hierarchy(levels)  => tagged("hierarchy", "levels" -> array(levels.map(int)))
  def analysis(value: AnalysisGrain): Json = value match
    case AnalysisGrain.Word                        => tagged("word")
    case AnalysisGrain.InferenceUnit(segmentation) =>
      tagged("inference-unit", "segmentation_id" -> str(segmentation.digest.hex))
    case AnalysisGrain.Targets(value) => tagged("targets", "grain" -> grain(value))
  def roles(value: UnitRoles): Json = obj(
    "inference_unit" -> analysis(value.inference),
    "organization_unit" -> analysis(value.organization),
    "projection_unit" -> analysis(value.projection)
  )
  def policies(value: MappingPolicies): Json = obj(
    "inference_policy_id" -> (value.inference match
      case InferencePolicy.HistoricalReconstruction(label) =>
        tagged("historical-reconstruction", "label" -> str(label))
      case InferencePolicy.Unspecified(reason) => tagged("unspecified", "reason" -> str(reason))),
    "context_policy_id" -> (value.context match
      case ContextPolicy.Unspecified(reason) => tagged("unspecified", "reason" -> str(reason))),
    "candidate_policy_id" -> (value.candidate match
      case CandidatePolicy.Unknown(reason)        => tagged("unknown", "reason" -> str(reason))
      case CandidatePolicy.Declared(id, coverage) =>
        tagged(
          "declared",
          "id" -> str(id.value),
          "candidate_coverage" -> (coverage match
            case CandidateCoverage.Complete         => tagged("complete")
            case CandidateCoverage.Truncated(count) => tagged("truncated", "count" -> int(count))
            case CandidateCoverage.Unknown(reason)  => tagged("unknown", "reason" -> str(reason)))
        )),
    "reference_prior_id" -> (value.referencePrior match
      case ReferencePrior.Declared(id)          => tagged("declared", "id" -> str(id.value))
      case ReferencePrior.NotApplicable(reason) =>
        tagged("not-applicable", "reason" -> str(reason))),
    "decision_policy_id" -> (value.decision match
      case DecisionPolicy.Declared(id)          => tagged("declared", "id" -> str(id.value))
      case DecisionPolicy.NotApplicable(reason) =>
        tagged("not-applicable", "reason" -> str(reason))),
    "target_universe_id" -> obj(
      "id" -> str(value.universe.id.digest.hex),
      "grain" -> grain(value.universe.grain),
      "targets" -> strings(value.universe.targets.map(_.key))
    )
  )
  def receipt(value: StageReceipt): Json = obj(
    "stage" -> str(value.stage.toString),
    "policy" -> str(value.policy),
    "config" -> str(value.config.hex),
    "inputs" -> strings(value.inputs.map(_.hex)),
    "provider" -> (value.provider match
      case ProviderStatus.Identified(id) =>
        tagged(
          "identified",
          "provider" -> str(id.provider),
          "model" -> str(id.model),
          "artifact" -> str(id.artifact.hex)
        )
      case ProviderStatus.NotApplicable(reason) =>
        tagged("not-applicable", "reason" -> str(reason)))
  )
  def entry(value: StageEntry): Json = obj(
    "id" -> str(value.id.digest.hex),
    "stage" -> str(value.stage.toString),
    "provenance" -> (value.provenance match
      case v: StageProvenance.Unknown =>
        tagged(
          "unknown",
          "reason" -> str(v.reason.toString),
          "asserted" -> optional(v.asserted)(receipt)
        )
      case v: StageProvenance.Derived => tagged("derived", "receipt" -> receipt(v.receipt)))
  )
  def stages(value: UnitStageRefs): Json = obj(
    "inference" -> str(value.inference.digest.hex),
    "candidates" -> str(value.candidates.digest.hex),
    "decision" -> str(value.decision.digest.hex)
  )
  def binding(value: DerivationBinding): Json = obj(
    "recall_checksum" -> str(value.recallChecksum.hex),
    "recall_supplement" -> str(value.recallSupplement.hex),
    "inventory_digest" -> str(value.inventoryDigest.hex),
    "view_fingerprint" -> str(value.viewFingerprint.checksum.hex),
    "scope_digest" -> str(value.scopeDigest.hex),
    "result_digest" -> str(value.resultDigest.hex)
  )
  def derivation(value: MeasureDerivation): Json = value match
    case MeasureDerivation.Supplied      => tagged("supplied")
    case v: MeasureDerivation.FromResult =>
      tagged("bound", "unit" -> str(v.unit.value), "binding" -> binding(v.binding))
  def basis(value: DecisionBasis): Json =
    obj("measure_kind" -> str(value.kind.toString), "channel" -> optional(value.channel)(str))
  def request(value: DecisionRequest): Json = value match
    case DecisionRequest.RawArgmax                      => tagged("raw-argmax")
    case DecisionRequest.ExternalDecode(chosen, policy) =>
      tagged("external-decode", "chosen" -> str(chosen.key), "policy" -> str(policy.value))
    case DecisionRequest.Abstain(policy, reason) =>
      tagged("abstain", "policy" -> str(policy.value), "reason" -> str(reason))
  def decision(value: UnitDecision): Json = obj(
    "basis" -> basis(value.basis),
    "request" -> request(value.request),
    "decoded_target_id" -> optional(value.chosen)(v => str(v.key)),
    "decision_origin" -> (value.origin match
      case DecisionOrigin.RawArgmax                => tagged("raw-argmax")
      case DecisionOrigin.StructuredDecode(policy) =>
        tagged("structured-decode", "decision_policy_id" -> str(policy.value))
      case DecisionOrigin.GapFill(policy) =>
        tagged("gap-fill", "decision_policy_id" -> str(policy.value))
      case DecisionOrigin.Abstention(reason) => tagged("abstention", "reason" -> str(reason))),
    "raw_argmax" -> optional(value.rawArgmax)((destination, v) =>
      obj("destination" -> str(destination.key), "value" -> number(v))
    ),
    "decoded_target_mass" -> (value.decodedMass match
      case v: DecodedTargetMass.InCandidateSupport =>
        tagged("value", "value" -> number(v.value), "decision_in_candidate_support" -> Json.True)
      case _: DecodedTargetMass.OutsideCandidateSupport =>
        tagged("value", "value" -> number(0.0), "decision_in_candidate_support" -> Json.False)
      case _: DecodedTargetMass.NoDecision => tagged("no-decision")),
    "calibration" -> (value.calibration match
      case v: DecisionCalibration.Unavailable =>
        tagged("unavailable", "reason" -> str(v.reason.toString))
      case v: DecisionCalibration.Calibrated =>
        tagged(
          "calibrated",
          "probability" -> number(v.probability.probability.value),
          "artifact" -> str(v.probability.artifact.value),
          "event" -> (v.probability.event match
            case CalibratedEvent.ChosenDecisionCorrect(policy) =>
              tagged("chosen-decision-correct", "decision_policy_id" -> str(policy.value)))
        )),
    "decision_policy_id" -> optional(value.policy)(v => str(v.value))
  )
  def fidelityStatus(value: FidelityStatus): Json = value match
    case FidelityStatus.NotApplicable  => tagged("not-applicable")
    case v: FidelityStatus.NotAssessed => tagged("not-assessed", "reason" -> str(v.reason.toString))
    case _: FidelityStatus.Assessed    => tagged("assessed")
  def link(value: MappingLink): Json = obj(
    "destination" -> str(value.destination.key),
    "inference_stage_id" -> str(value.inferenceStage.digest.hex),
    "candidate_set_id" -> str(value.candidateSet.digest.hex),
    "derivation" -> derivation(value.derivation),
    "gate_outcome" -> (value.gate match
      case GateOutcome.NotGated                   => tagged("not-gated")
      case _: GateOutcome.NoContradictionDetected => tagged("no-contradiction-detected")
      case v: GateOutcome.Contradicted            =>
        tagged("contradicted", "facets" -> strings(v.facets.toSortedSet.toVector.map(_.toString)))),
    "fidelity_status" -> fidelityStatus(value.fidelity),
    "fidelity_facets" -> optional(value.fidelity match
      case v: FidelityStatus.Assessed => Some(v.report)
      case _                          => None)(report =>
      array(
        report.verdicts.toVector
          .sortBy(_._1.ordinal)
          .map((facet, verdict) =>
            obj("facet" -> str(facet.toString), "verdict" -> str(verdict.toString))
          )
      )
    ),
    "term_support" -> (value.termSupport match
      case v: TermSupportStatus.NotComputed =>
        tagged("not-computed", "reason" -> str(v.reason.toString))
      case v: TermSupportStatus.Evaluated =>
        tagged("evaluated", "assessment" -> SupportAssessmentWire.encode(v.assessment)))
  )
  def metadata(value: UnitMeasures): Json = obj(
    "raw" -> array(
      value.raw.map(r =>
        obj(
          "channel" -> str(r.channel),
          "direction" -> str(r.direction.toString),
          "scale" -> str(r.scale),
          "stage" -> str(r.stage.digest.hex),
          "derivation" -> derivation(r.derivation)
        )
      )
    ),
    "normalized" -> optional(value.normalized)(r =>
      obj(
        "universe" -> str(r.universe.digest.hex),
        "prior" -> str(r.prior.value),
        "temperature" -> number(r.temperature),
        "stage" -> str(r.stage.digest.hex)
      )
    ),
    "transport" -> optional(value.transport)(r =>
      obj("row_budget" -> number(r.rowBudget), "stage" -> str(r.stage.digest.hex))
    ),
    "posterior" -> optional(value.posterior)(r =>
      obj(
        "unit" -> str(r.unit.value),
        "stage" -> str(r.stage.digest.hex),
        "binding" -> binding(r.binding)
      )
    )
  )
  def values(value: UnitMeasures): Json =
    def row(
        kind: MeasureKind,
        channel: Option[String],
        destination: Destination,
        value: Double,
        state: Option[AlignState]
    ): Json =
      obj(
        "measure_kind" -> str(kind.toString),
        "channel" -> optional(channel)(str),
        "destination" -> str(destination.key),
        "raw_value" -> number(value),
        "normalization_scope" -> str(kind match
          case MeasureKind.RawScore              => "not-normalized"
          case MeasureKind.NormalizedScoreMass   => "unit/supplied-destinations"
          case MeasureKind.TransportMass         => "not-row-normalized"
          case MeasureKind.ModelPosterior        => "unit/supplied-model-states"
          case MeasureKind.CalibratedProbability => "calibrated-event"),
        "state" -> optional(state)(_.asJson)
      )
    val raw = value.raw.flatMap(r =>
      r.values.toVector.map((d, v) => row(MeasureKind.RawScore, Some(r.channel), d, v, None))
    )
    val normalized = value.normalized.toVector.flatMap(
      _.mass.toVector.map((d, v) => row(MeasureKind.NormalizedScoreMass, None, d, v, None))
    )
    val transport = value.transport.toVector.flatMap(
      _.mass.toVector.map((d, v) => row(MeasureKind.TransportMass, None, d, v, None))
    )
    val posterior = value.posterior.toVector.flatMap(
      _.mass.toVector.map((s, v) =>
        row(MeasureKind.ModelPosterior, None, Destination.of(s), v, Some(s))
      )
    )
    array((raw ++ normalized ++ transport ++ posterior).sortBy(print))
  def processing(value: ProcessingStatus): Json = value match
    case ProcessingStatus.Complete                      => tagged("complete")
    case ProcessingStatus.ExcludedByInputPolicy(reason) =>
      tagged("excluded", "reason" -> str(reason))
    case ProcessingStatus.Failed(failure) =>
      tagged(
        "failed",
        "failure" -> (failure match
          case ProcessingFailure.ProviderFailure(detail) =>
            tagged("provider-failure", "detail" -> str(detail))
          case ProcessingFailure.InvalidOutput(detail) =>
            tagged("invalid-output", "detail" -> str(detail))
          case ProcessingFailure.InferenceRefused(detail) =>
            tagged("inference-refused", "detail" -> str(detail)))
      )
  def outcome(value: UnitOutcome): Json = obj(
    "unit" -> str(value.unit.value),
    "processing_status" -> processing(value.processing),
    "fidelity_assessment_status" -> optional(
      value.decision
        .flatMap(_.chosen)
        .flatMap(destination => value.links.find(_.destination == destination))
        .map(_.fidelity)
    )(fidelityStatus),
    "localization_status" -> str(value.localization.toString),
    "measures" -> metadata(value.measures),
    "mapping_links" -> values(value.measures),
    "links" -> array(value.links.map(link)),
    "decision" -> optional(value.decision)(decision),
    "stages" -> optional(value.stages)(stages)
  )
  def encode(value: MappingResult): Json = obj(
    "schema" -> str("storymodel4s.mapping-record"),
    "schemaVersion" -> str("mapping-record/v0.1"),
    "record_digest" -> str(value.digest.hex),
    "inventory" -> MappingSourceWire.inventory(value.inventory),
    "source" -> MappingSourceWire.source(value.source),
    "policies" -> policies(value.policies),
    "roles" -> roles(value.roles),
    "stage_assumption_receipts" -> array(value.ledger.entries.map(entry)),
    "derivation_source" -> (value.derivation match
      case DerivationSource.NoDerivedValues => tagged("none")
      case v: DerivationSource.Bound        => tagged("bound", "binding" -> binding(v.binding))),
    "outcomes" -> array(value.outcomes.map(outcome))
  )
