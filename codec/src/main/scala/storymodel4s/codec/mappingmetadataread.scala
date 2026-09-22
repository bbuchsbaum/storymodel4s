package storymodel4s.codec

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import MappingJson.*

private[codec] object MappingMetadataRead:
  private def reason(value: Json): Result[String] = field[String](value, "reason")
  private def checksum(value: Json, name: String): Result[Checksum] =
    field[String](value, name).flatMap(v => domain(Checksum.from(v)))
  def grain(value: Json): Result[TargetGrain] = status(value).flatMap {
    case "single-level" => field[Int](value, "level").map(TargetGrain.SingleLevel(_))
    case "hierarchy"    => field[Vector[Int]](value, "levels").map(TargetGrain.Hierarchy(_))
    case other          => invalid("grain", s"unknown grain $other")
  }
  def roles(value: Json, inventory: RecallInventory): Result[UnitRoles] =
    def analysis(value: Json): Result[AnalysisGrain] = status(value).flatMap {
      case "word"    => Right(AnalysisGrain.Word)
      case "targets" => field[Json](value, "grain").flatMap(grain).map(AnalysisGrain.Targets(_))
      case "inference-unit" =>
        field[String](value, "segmentation_id").flatMap(id =>
          Either.cond(
            id == inventory.segmentation.digest.hex,
            AnalysisGrain.InferenceUnit(inventory.segmentation),
            MappingCodecError.Rejected(MappingRefusal.RolesMismatch)
          )
        )
      case other => invalid("analysis-grain", s"unknown grain $other")
    }
    for
      i <- field[Json](value, "inference_unit").flatMap(analysis)
      o <- field[Json](value, "organization_unit").flatMap(analysis)
      p <- field[Json](value, "projection_unit").flatMap(analysis)
      roles <- checked(UnitRoles.of(i, o, p))
    yield roles
  def policies(value: Json): Result[MappingPolicies] =
    def inference(v: Json): Result[InferencePolicy] = status(v).flatMap {
      case "unspecified"               => reason(v).map(InferencePolicy.Unspecified(_))
      case "historical-reconstruction" =>
        field[String](v, "label").map(InferencePolicy.HistoricalReconstruction(_))
      case other => invalid("inference_policy_id", s"unknown policy $other")
    }
    def context(v: Json): Result[ContextPolicy] = status(v).flatMap {
      case "unspecified" => reason(v).map(ContextPolicy.Unspecified(_))
      case other         => invalid("context_policy_id", s"unknown policy $other")
    }
    def coverage(v: Json): Result[CandidateCoverage] = status(v).flatMap {
      case "complete"  => Right(CandidateCoverage.Complete)
      case "truncated" => field[Int](v, "count").map(CandidateCoverage.Truncated(_))
      case "unknown"   => reason(v).map(CandidateCoverage.Unknown(_))
      case other       => invalid("candidate.coverage", s"unknown coverage $other")
    }
    def candidate(v: Json): Result[CandidatePolicy] = status(v).flatMap {
      case "unknown"  => reason(v).map(CandidatePolicy.Unknown(_))
      case "declared" =>
        for
          id <- field[String](v, "id").flatMap(id => domain(CandidatePolicyId.from(id)))
          c <- field[Json](v, "candidate_coverage").flatMap(coverage)
        yield CandidatePolicy.Declared(id, c)
      case other => invalid("candidate_policy_id", s"unknown policy $other")
    }
    def prior(v: Json): Result[ReferencePrior] = status(v).flatMap {
      case "not-applicable" => reason(v).map(ReferencePrior.NotApplicable(_))
      case "declared"       =>
        field[String](v, "id")
          .flatMap(id => domain(ReferencePriorId.from(id)))
          .map(ReferencePrior.Declared(_))
      case other => invalid("reference-prior", s"unknown prior $other")
    }
    def decision(v: Json): Result[DecisionPolicy] = status(v).flatMap {
      case "not-applicable" => reason(v).map(DecisionPolicy.NotApplicable(_))
      case "declared"       =>
        field[String](v, "id")
          .flatMap(id => domain(DecisionPolicyId.from(id)))
          .map(DecisionPolicy.Declared(_))
      case other => invalid("decision-policy", s"unknown policy $other")
    }
    def universe(v: Json): Result[DeclaredUniverse] = for
      g <- field[Json](v, "grain").flatMap(grain)
      targets <- field[Vector[String]](v, "targets").flatMap(_.traverse(sourceRef))
      result <- checked(DeclaredUniverse.of(targets, g))
      id <- field[String](v, "id")
      _ <- Either.cond(
        id == result.id.digest.hex,
        (),
        MappingCodecError.DigestMismatch("target_universe")
      )
    yield result
    for
      i <- field[Json](value, "inference_policy_id").flatMap(inference)
      c <- field[Json](value, "context_policy_id").flatMap(context)
      candidates <- field[Json](value, "candidate_policy_id").flatMap(candidate)
      p <- field[Json](value, "reference_prior_id").flatMap(prior)
      d <- field[Json](value, "decision_policy_id").flatMap(decision)
      u <- field[Json](value, "target_universe_id").flatMap(universe)
      result <- checked(MappingPolicies.of(i, c, candidates, p, d, u))
    yield result
  def receipt(value: Json): Result[StageReceipt] =
    def provider(value: Json): Result[ProviderStatus] = status(value).flatMap {
      case "not-applicable" => reason(value).map(ProviderStatus.NotApplicable(_))
      case "identified"     =>
        for
          p <- field[String](value, "provider")
          m <- field[String](value, "model")
          a <- checksum(value, "artifact")
          id <- checked(ProviderIdentity.of(p, m, a))
        yield ProviderStatus.Identified(id)
      case other => invalid("provider", s"unknown provider status $other")
    }
    for
      stage <- field[String](value, "stage").flatMap(v =>
        enumValue(v, Stage.values.toVector, "stage")
      )
      policy <- field[String](value, "policy")
      config <- checksum(value, "config")
      inputs <- field[Vector[String]](value, "inputs")
        .flatMap(_.traverse(v => domain(Checksum.from(v))))
      p <- field[Json](value, "provider").flatMap(provider)
      result <- checked(StageReceipt.of(stage, policy, config, inputs, p))
    yield result
  def ledger(value: Json): Result[StageLedger] =
    def provenance(value: Json): Result[StageProvenance] = status(value).flatMap {
      case "derived" => Left(MappingCodecError.Reserved("derived"))
      case "unknown" =>
        for
          reason <- field[String](value, "reason").flatMap(v =>
            enumValue(v, UnknownProvenanceReason.values.toVector, "provenance.reason")
          )
          asserted <- field[Json](value, "asserted").flatMap(v => readOptional(v)(receipt))
        yield StageProvenance.unknown(reason, asserted)
      case other => invalid("provenance", s"unknown status $other")
    }
    read[Vector[Json]](value)
      .flatMap(_.traverse { row =>
        for
          stage <- field[String](row, "stage")
            .flatMap(v => enumValue(v, Stage.values.toVector, "stage"))
          p <- field[Json](row, "provenance").flatMap(provenance)
          result <- checked(StageEntry.of(stage, p))
          id <- field[String](row, "id")
          _ <- Either
            .cond(id == result.id.digest.hex, (), MappingCodecError.DigestMismatch("stage_entry"))
        yield result
      })
      .flatMap(v => checked(StageLedger.of(v)))
  def stage(value: Json, name: String, ledger: StageLedger): Result[StageEntryId] =
    field[String](value, name)
      .flatMap(id =>
        ledger.entries
          .find(_.id.digest.hex == id)
          .map(_.id)
          .toRight(MappingCodecError.ValueMismatch(s"stage.$name"))
      )
  def stages(value: Json, ledger: StageLedger): Result[UnitStageRefs] = for
    i <- stage(value, "inference", ledger)
    c <- stage(value, "candidates", ledger)
    d <- stage(value, "decision", ledger)
    result <- checked(UnitStageRefs.of(ledger, i, c, d))
  yield result
  def matchedBinding(value: Json, expected: DerivationBinding): Result[Unit] =
    val expectedJson = MappingRecordWire.binding(expected)
    expectedJson.asObject.toVector.flatMap(_.toVector).traverse_ { (key, v) =>
      field[String](value, key).flatMap(actual =>
        Either.cond(Json.fromString(actual) == v, (), MappingCodecError.DigestMismatch(key))
      )
    }
