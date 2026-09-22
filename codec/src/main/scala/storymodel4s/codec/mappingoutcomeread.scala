package storymodel4s.codec

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.recall.*
import CanonicalPrimitives.given
import HsmmResultCodec.given
import MappingJson.*

private[codec] final case class MappingBoundContext(
    binding: DerivationBinding,
    inputs: DerivationContext
)

private[codec] object MappingOutcomeRead:
  private final case class Weighted(
      kind: MeasureKind,
      channel: Option[String],
      destination: Destination,
      value: Double,
      state: Option[AlignState]
  )
  private def bound(
      value: Option[MappingBoundContext],
      field: String
  ): Result[MappingBoundContext] =
    value.toRight(MappingCodecError.ContextRequired(field))
  private def rows(value: Json): Result[Vector[Weighted]] =
    read[Vector[Json]](value).flatMap(_.traverse { row =>
      for
        kind <- field[String](row, "measure_kind").flatMap(v =>
          enumValue(v, MeasureKind.values.toVector, "measure_kind")
        )
        channel <- field[Json](row, "channel").flatMap(v => readOptional(v)(read[String]))
        destination <- field[String](row, "destination").flatMap(MappingJson.destination)
        value <- field[Double](row, "raw_value")
        state <- field[Json](row, "state").flatMap(v => readOptional(v)(read[AlignState]))
      yield Weighted(kind, channel, destination, value, state)
    })
  private def matchedOrigin(
      value: Json,
      unit: RecallUnitId,
      context: MappingBoundContext
  ): Result[Unit] = for
    id <- field[String](value, "unit")
    _ <- Either.cond(id == unit.value, (), MappingCodecError.DigestMismatch("origin.unit"))
    binding <- field[Json](value, "binding")
    _ <- MappingMetadataRead.matchedBinding(binding, context.binding)
  yield ()
  private def measures(
      meta: Json,
      values: Json,
      unit: RecallUnitId,
      ledger: StageLedger,
      policies: MappingPolicies,
      context: Option[MappingBoundContext]
  ): Result[UnitMeasures] =
    for
      weighted <- rows(values)
      rawJson <- field[Vector[Json]](meta, "raw")
      raw <- rawJson.traverse { row =>
        for
          channel <- field[String](row, "channel")
          direction <- field[String](row, "direction").flatMap(v =>
            enumValue(v, ScoreDirection.values.toVector, "direction")
          )
          scale <- field[String](row, "scale")
          stage <- MappingMetadataRead.stage(row, "stage", ledger)
          entries <- unique(
            weighted
              .filter(v => v.kind == MeasureKind.RawScore && v.channel.contains(channel))
              .map(v => v.destination -> v.value),
            "raw.values"
          )
          origin <- field[Json](row, "derivation")
          kind <- status(origin)
          result <- kind match
            case "supplied" => checked(RawScores.of(channel, direction, scale, entries, stage))
            case "bound"    =>
              for
                c <- bound(context, "rawScores")
                _ <- matchedOrigin(origin, unit, c)
                result <- checked(RawScores.fromCosts(c.inputs.result, unit, c.binding, stage))
              yield result
            case other => invalid("measure.derivation", s"unknown status $other")
        yield result
      }
      normalized <- field[Json](meta, "normalized").flatMap(v =>
        readOptional(v) { row =>
          for
            universe <- field[String](row, "universe")
            _ <- Either.cond(
              universe == policies.universe.id.digest.hex,
              (),
              MappingCodecError.DigestMismatch("normalized.universe")
            )
            prior <- field[String](row, "prior").flatMap(id => domain(ReferencePriorId.from(id)))
            temperature <- field[Double](row, "temperature")
            stage <- MappingMetadataRead.stage(row, "stage", ledger)
            entries <- unique(
              weighted
                .filter(v => v.kind == MeasureKind.NormalizedScoreMass && v.channel.isEmpty)
                .map(v => v.destination -> v.value),
              "normalized.values"
            )
            result <- checked(
              NormalizedScoreMass.of(policies.universe.id, prior, temperature, entries, stage)
            )
          yield result
        }
      )
      transport <- field[Json](meta, "transport").flatMap(v =>
        readOptional(v) { row =>
          for
            budget <- field[Double](row, "row_budget")
            stage <- MappingMetadataRead.stage(row, "stage", ledger)
            entries <- unique(
              weighted
                .filter(v => v.kind == MeasureKind.TransportMass && v.channel.isEmpty)
                .map(v => v.destination -> v.value),
              "transport.values"
            )
            result <- checked(TransportMass.of(budget, entries, stage))
          yield result
        }
      )
      posterior <- field[Json](meta, "posterior").flatMap(v =>
        readOptional(v) { row =>
          for
            c <- bound(context, "posterior")
            _ <- matchedOrigin(row, unit, c)
            stage <- MappingMetadataRead.stage(row, "stage", ledger)
            result <- checked(ModelPosterior.of(c.inputs.result, unit, c.binding, stage))
          yield result
        }
      )
      result <- checked(UnitMeasures.of(raw, normalized, transport, posterior))
      _ <- exact(meta, MappingRecordWire.metadata(result), "measures")
      _ <- exact(values, MappingRecordWire.values(result), "mapping_links")
    yield result
  private def basis(value: Json): Result[DecisionBasis] = for
    kind <- field[String](value, "measure_kind").flatMap(v =>
      enumValue(v, MeasureKind.values.toVector, "decision.measure_kind")
    )
    channel <- field[Json](value, "channel").flatMap(v => readOptional(v)(read[String]))
    result <- checked(DecisionBasis.of(kind, channel))
  yield result
  private def request(value: Json): Result[DecisionRequest] =
    def policy: Result[DecisionPolicyId] =
      field[String](value, "policy").flatMap(v => domain(DecisionPolicyId.from(v)))
    status(value).flatMap {
      case "raw-argmax"      => Right(DecisionRequest.RawArgmax)
      case "external-decode" =>
        for
          p <- policy
          destination <- field[String](value, "chosen").flatMap(MappingJson.destination)
        yield DecisionRequest.ExternalDecode(destination, p)
      case "abstain" =>
        for
          p <- policy
          reason <- field[String](value, "reason")
        yield DecisionRequest.Abstain(p, reason)
      case other => invalid("request", s"unknown status $other")
    }
  private def candidates(
      basis: DecisionBasis,
      measures: UnitMeasures,
      unit: RecallUnitId,
      stages: UnitStageRefs
  ): Result[CandidateSetId] =
    val keys = basis.kind match
      case MeasureKind.RawScore =>
        measures.raw.find(r => basis.channel.contains(r.channel)).map(_.values.keySet)
      case MeasureKind.NormalizedScoreMass => measures.normalized.map(_.mass.keySet)
      case MeasureKind.TransportMass       => measures.transport.map(_.mass.keySet)
      case MeasureKind.ModelPosterior => measures.posterior.map(_.mass.keySet.map(Destination.of))
      case MeasureKind.CalibratedProbability => None
    keys
      .toRight(MappingCodecError.Rejected(MappingRefusal.MeasureMissing(basis.kind, basis.channel)))
      .map(CandidateSetId.of(stages.candidates, unit, _))
  private def link(
      value: Json,
      unit: RecallUnitId,
      stages: UnitStageRefs,
      candidate: CandidateSetId,
      source: SourceRepresentation,
      context: Option[MappingBoundContext]
  ): Result[MappingLink] =
    for
      _ <- field[Json](value, "term_support")
      destination <- field[String](value, "destination").flatMap(MappingJson.destination)
      origin <- field[Json](value, "derivation")
      kind <- status(origin)
      result <- kind match
        case "supplied" => Right(MappingLink.ungated(destination, stages, candidate))
        case "bound"    =>
          for
            c <- bound(context, "link")
            _ <- matchedOrigin(origin, unit, c)
            states = c.inputs.result.costs
              .get(unit)
              .toVector
              .flatMap(_.keys)
              .filter(s => Destination.of(s) == destination)
              .sorted
            rebuilt = states.flatMap(s =>
              MappingLink
                .fromResult(
                  c.inputs.result,
                  c.binding,
                  c.inputs.recall,
                  c.inputs.view,
                  source,
                  unit,
                  s,
                  stages,
                  candidate
                )
                .toOption
            )
            result <- rebuilt
              .find(r => MappingRecordWire.link(r) == value)
              .toRight(MappingCodecError.ValueMismatch("derived-link"))
          yield result
        case other => invalid("link.derivation", s"unknown status $other")
      _ <- exact(value, MappingRecordWire.link(result), "link")
    yield result
  def outcome(
      value: Json,
      ledger: StageLedger,
      policies: MappingPolicies,
      source: SourceRepresentation,
      context: Option[MappingBoundContext]
  ): Result[UnitOutcome] =
    def failure(value: Json): Result[ProcessingFailure] = for
      kind <- status(value)
      detail <- field[String](value, "detail")
      result <- kind match
        case "provider-failure"  => Right(ProcessingFailure.ProviderFailure(detail))
        case "invalid-output"    => Right(ProcessingFailure.InvalidOutput(detail))
        case "inference-refused" => Right(ProcessingFailure.InferenceRefused(detail))
        case other               => invalid("failure", s"unknown failure $other")
    yield result
    for
      unit <- field[String](value, "unit").flatMap(v => domain(RecallUnitId.from(v)))
      processing <- field[Json](value, "processing_status")
      kind <- status(processing)
      result <- kind match
        case "failed" =>
          field[Json](processing, "failure").flatMap(failure).map(UnitOutcome.failed(unit, _))
        case "excluded" => field[String](processing, "reason").map(UnitOutcome.excluded(unit, _))
        case "partial" | "ambiguous" | "manual-review" => Left(MappingCodecError.Reserved(kind))
        case "complete"                                =>
          for
            meta <- field[Json](value, "measures")
            rows <- field[Json](value, "mapping_links")
            measures <- measures(meta, rows, unit, ledger, policies, context)
            decision <- field[Json](value, "decision")
              .flatMap(v => readOptional(v)(j => Right(j)))
              .flatMap(_.toRight(MappingCodecError.ValueMismatch("complete.decision")))
            basis <- field[Json](decision, "basis").flatMap(basis)
            request <- field[Json](decision, "request").flatMap(request)
            stages <- field[Json](value, "stages")
              .flatMap(v => readOptional(v)(j => MappingMetadataRead.stages(j, ledger)))
              .flatMap(_.toRight(MappingCodecError.ValueMismatch("complete.stages")))
            candidate <- candidates(basis, measures, unit, stages)
            links <- field[Vector[Json]](value, "links")
              .flatMap(_.traverse(v => link(v, unit, stages, candidate, source, context)))
            result <- checked(UnitOutcome.computed(unit, measures, links, basis, request, stages))
          yield result
        case other => invalid("processing_status", s"unknown status $other")
      _ <- exact(value, MappingRecordWire.outcome(result), "outcome")
    yield result
