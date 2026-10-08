package storymodel4s.bench.video

import io.circe.Json
import io.circe.syntax.*
import storymodel4s.codec.{FeatureCodecs, HsmmResultCodec}
import storymodel4s.embed.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.features.Estimate
import storymodel4s.align.*
import storymodel4s.core.Checksum
import storymodel4s.recall.RecallGraph
import storymodel4s.recall.RecallGraphStatus.Checked

/** Opt-in engineering evidence for comparing selection stages on the same admitted local costs.
  * Source/recall prose is omitted. This is a bench receipt, not a calibrated prediction contract.
  */
private[bench] object StageTrace:
  import FeatureCodecs.given

  /** Retain the exact originating evidence and configuration around the executed inference call. */
  final class Run private[StageTrace] (
      private[StageTrace] val recall: RecallGraph[Checked],
      private[StageTrace] val levels: Map[SourceNodeRef, Int],
      private[StageTrace] val video: Option[TimedSourceView.Built],
      private[StageTrace] val registration: Option[OnnxMappingRegistration.Registered],
      val evidence: LocalEvidence,
      val config: HsmmConfig,
      val result: HsmmResult
  ):
    private def parts = (recall, levels, video, evidence, config, result, registration)
    override def equals(other: Any): Boolean = other match
      case r: Run => parts == r.parts
      case _      => false
    override def hashCode: Int = (evidence, config, result, registration).hashCode
    override def toString: String =
      s"StageTrace.Run(units=${evidence.units.size}, passes=${result.refinementPasses})"

  /** Precomputed evidence is consumed without invoking its scoring providers again. */
  def infer(
      recall: RecallGraph[Checked],
      built: TimedSourceView.Built,
      evidence: LocalEvidence,
      config: HsmmConfig
  ): Either[AlignError, Run] =
    execute(recall, built.view, evidence, config, Some(built), None)

  /** The actual historical runner computes one evidence object before any inference. */
  def historical(
      recall: RecallGraph[Checked],
      built: TimedSourceView.Built,
      candidates: Candidates,
      pricing: LocalCostModel,
      config: HsmmConfig
  ): Either[AlignError, Run] =
    LocalEvidence
      .compute(recall, built.view, candidates, pricing, gate = true)
      .flatMap(infer(recall, built, _, config))

  /** Registered origin comes exclusively from the original concrete recording/replay envelope. */
  def registered(
      origin: OnnxMappingRegistration.Registered,
      config: HsmmConfig
  ): Either[AlignError, Run] =
    execute(
      origin.context.recall,
      origin.context.source,
      origin.evidence,
      config,
      None,
      Some(origin)
    )

  private def execute(
      recall: RecallGraph[Checked],
      source: SourceView,
      evidence: LocalEvidence,
      config: HsmmConfig,
      video: Option[TimedSourceView.Built],
      registration: Option[OnnxMappingRegistration.Registered]
  ): Either[AlignError, Run] =
    GraphHsmm.infer(recall, source, evidence, config).map { result =>
      new Run(
        recall,
        source.nodes.map(n => n.ref -> n.level).toMap,
        video,
        registration,
        evidence,
        config,
        result
      )
    }

  private def refused(detail: String): AlignError =
    AlignError.InconsistentResult(s"stage trace $detail")

  /** Independent emissions with a uniform state prior. Shifting costs cannot change the result. */
  def localMass(costs: Vector[Double], temperature: Double): Vector[Double] =
    require(costs.nonEmpty && costs.forall(_.isFinite), "finite nonempty local costs required")
    require(temperature.isFinite && temperature > 0.0, "positive finite temperature required")
    val least = costs.min
    val weights = costs.map(c => math.exp(-(c - least) / temperature))
    val total = weights.sum
    weights.map(_ / total)

  private def number(d: Double): Json =
    require(d.isFinite, "trace cannot publish nonfinite numbers")
    Json.fromDoubleOrNull(d)

  private def anchor(ref: Option[SourceNodeRef]): Json =
    ref.fold(Json.Null)(r => Json.fromString(r.key))

  private def configuration(config: HsmmConfig): Json = Json.obj(
    "fingerprint" -> Json.fromString(config.fingerprint.hex),
    "temperature" -> number(config.temperature),
    "refinementPasses" -> Json.fromInt(config.refinementPasses),
    "refinementWeight" -> number(config.refinementWeight),
    "transitionWeights" -> Json.obj(
      TransitionKind.values.toVector.map(k => k.toString -> number(config.transitions(k)))*
    )
  )

  private def policy(p: CandidateTiePolicy): Json = p match
    case CandidateTiePolicy.HistoricalKeyOrder =>
      Json.obj("kind" -> Json.fromString("HistoricalKeyOrder"))
    case c: CandidateTiePolicy.TieComplete =>
      val budget = c.budget match
        case TieBudget.Unbounded => Json.obj("kind" -> Json.fromString("Unbounded"))
        case b: TieBudget.AtMost =>
          Json.obj("kind" -> Json.fromString("AtMost"), "n" -> Json.fromInt(b.n))
      Json.obj("kind" -> Json.fromString("TieComplete"), "budget" -> budget)

  private def provenance(p: CandidateProvenance): Json = p match
    case CandidateProvenance.Unattested => Json.obj("kind" -> Json.fromString("Unattested"))
    case p: CandidateProvenance.Strict  =>
      Json.obj(
        "kind" -> Json.fromString("Strict"),
        "policy" -> policy(p.policy),
        "semanticChannel" -> p.semanticChannel.fold(Json.Null)(c => Json.fromString(c.kind))
      )

  private def accounting(p: CandidateProvenance, i: Int): Json = p match
    case CandidateProvenance.Unattested => Json.Null
    case p: CandidateProvenance.Strict  =>
      Json.obj(
        "tieOverflow" -> Json.fromValues(
          p.overflow(i)
            .map(o =>
              Json.obj(
                "level" -> Json.fromInt(o.level),
                "unionSize" -> Json.fromInt(o.unionSize),
                "budget" -> Json.fromInt(o.budget)
              )
            )
        ),
        "uniformSemantic" -> Json.fromValues(
          p.uniformSemantic(i)
            .map(u =>
              Json
                .obj("level" -> Json.fromInt(u.level), "scoredCount" -> Json.fromInt(u.scoredCount))
            )
        ),
        "semanticOutcomes" -> Json.fromValues(
          p.semanticOutcomes(i)
            .map(o =>
              Json.obj(
                "level" -> Json.fromInt(o.level),
                "observed" -> Json.fromInt(o.observed),
                "ineligible" -> Json.fromInt(o.ineligible),
                "missing" -> Json.fromValues(
                  o.missing.toVector
                    .sortBy(_._1.toString)
                    .map((reason, n) =>
                      Json.obj("reason" -> reason.asJson, "count" -> Json.fromInt(n))
                    )
                )
              )
            )
        )
      )

  private def batch(record: storymodel4s.embed.onnx.OnnxSentenceEmbedder.RecordedBatch): Json =
    val outcomes = record.result.outcomes.map { o =>
      val outcome = o.value match
        case Right(Estimate.Observed(_, _))  => Json.obj("status" -> Json.fromString("Observed"))
        case Right(Estimate.Missing(reason)) =>
          Json.obj("status" -> Json.fromString("Missing"), "reason" -> reason.asJson)
        case Right(Estimate.Ineligible) => Json.obj("status" -> Json.fromString("Ineligible"))
        case Left(failure)              =>
          val kind = failure match
            case ExecutionFailure.TooLong(_, _)                                  => "TooLong"
            case ExecutionFailure.ProviderError(_, _)                            => "ProviderError"
            case ExecutionFailure.PolicyDenied(_: PolicyDecision.KeyUnavailable) => "KeyUnavailable"
            case ExecutionFailure.PolicyDenied(_)                                => "PolicyDenied"
            case ExecutionFailure.LocalOnly(_)                                   => "LocalOnly"
            case ExecutionFailure.Transport(_)                                   => "Transport"
            case ExecutionFailure.Invalid(_)                                     => "Invalid"
          Json.obj("status" -> Json.fromString("ExecutionFailed"), "kind" -> Json.fromString(kind))
      Json.obj("request" -> Json.fromString(o.id.value), "outcome" -> outcome)
    }
    Json.obj(
      "provider" -> Json.fromString(record.provider.render),
      "runtime" -> Json.fromString(record.runtimeIdentity),
      "modelSha256" -> Json.fromString(record.model.modelChecksum.hex),
      "tokenizerSha256" -> Json.fromString(record.model.tokenizerChecksum.hex),
      "receipt" -> Json.fromString(record.result.receipt.digest.render),
      "providerCalls" -> Json.fromInt(record.result.receipt.providerCalls.size),
      "outcomes" -> Json.fromValues(outcomes)
    )

  private def registeredOrigin(r: OnnxMappingRegistration.Registered): Json =
    val p = r.pricing
    val c = r.effectiveCandidates
    Json.obj(
      "basis" -> Json.fromString(r.basis.toString),
      "surfacePolicy" -> Json.fromString(r.context.policy),
      "metric" -> Json.fromString(r.config.metric.tag),
      "emptyPolicy" -> Json.fromString(r.config.emptyPolicy.toString),
      "querySensitivity" -> Json.fromString(r.config.querySensitivity.toString),
      "documentSensitivity" -> Json.fromString(r.config.documentSensitivity.toString),
      "candidates" -> Json.obj(
        "perLevel" -> Json.fromInt(c.perLevel),
        "tiePolicy" -> policy(c.policy),
        "lexicalOverlap" -> Json.fromBoolean(c.lexicalOverlap),
        "space" -> c.space.fold(Json.Null)(Json.fromString)
      ),
      "pricing" -> Json.obj(
        "weights" -> Json.obj(
          CostTerm.values.toVector.map(t => t.toString -> number(p.weights(t)))*
        ),
        "functionPrior" -> Json.obj(
          p.functionPrior.costs.toVector
            .sortBy(_._1.toString)
            .map((f, v) => f.toString -> number(v))*
        ),
        "externalFloor" -> number(p.externalFloor),
        "externalMismatch" -> number(p.externalMismatch),
        "missingSemantic" -> number(p.missingSemantic),
        "distortionPenalty" -> number(p.distortionPenalty)
      ),
      "queryAttempt" -> batch(r.queryRecord),
      "documentAttempt" -> batch(r.documentRecord)
    )

  /** Serialize the retained run; downstream choices are reported diagnostics, not inference origin.
    */
  def render(
      run: Run,
      decisions: Vector[MonotoneScene.Decision],
      chosen: Vector[Option[SourceNodeRef]],
      reportChecksum: Checksum,
      sourceInputChecksum: Option[Checksum]
  ): Either[AlignError, String] =
    val result = run.result
    val evidence = run.evidence
    val config = run.config
    val units = run.recall.ordered
    if result.refinementPasses != 0 then
      Left(refused("cannot publish base emissions as refined emissions"))
    else if units.size != chosen.size || (decisions.nonEmpty && decisions.size != units.size) then
      Left(refused("downstream choices cover a different unit population"))
    else if decisions.nonEmpty && run.video.isEmpty then
      Left(refused("scene decisions require timed source metadata"))
    else if chosen.flatten.exists(ref => !run.levels.contains(ref)) ||
      decisions.flatMap(_.anchor).exists(ref => !run.levels.contains(ref))
    then Left(refused("downstream choice is outside the source"))
    else if decisions.nonEmpty && decisions.map(_.anchor) != chosen then
      Left(refused("scene decisions and final choices differ"))
    else if result.costs != evidence.units.zip(evidence.breakdowns).toMap then
      Left(refused("result base records differ from originating evidence"))
    else if evidence.candidates.flatMap(_.nominations).flatMap(_.rawScore).exists(!_.isFinite) then
      Left(refused("cannot publish nonfinite nomination scores"))
    else
      HsmmResultCodec.toJson(result).left.map(e => refused(e.message)).map { wire =>
        val noFill =
          if decisions.isEmpty then result.posterior.rows.map(_.mapSource)
          else MonotoneScene.decide(run.video.get, result.posterior.rows).map(_.anchor)
        Json
          .obj(
            "schema" -> Json.fromString("storymodel4s.bench.stage-trace/v2"),
            "reportSha256" -> Json.fromString(reportChecksum.hex),
            "sourceInputSha256" -> sourceInputChecksum.fold(Json.Null)(c => Json.fromString(c.hex)),
            "recallChecksum" -> Json.fromString(result.recallChecksum.toString),
            "sourceFingerprint" -> Json.fromString(result.viewFingerprint.checksum.toString),
            "temperature" -> number(config.temperature),
            "refinementPasses" -> Json.fromInt(result.refinementPasses),
            "inferenceConfig" -> configuration(config),
            "localEvidence" -> Json.obj(
              "identity" -> Json.fromString(evidence.identity.toString),
              "recallSupplement" -> Json.fromString(evidence.recallSupplement.hex),
              "sourceScope" -> Json.fromString(evidence.scopeDigest.hex),
              "gated" -> Json.fromBoolean(evidence.gated),
              "gateSemantics" -> Json.fromString(evidence.gateSemantics.toString),
              "provenance" -> provenance(evidence.provenance)
            ),
            // The engine retains these exact base records; the existing codec preserves every
            // support/reduction/imputation/exclusion field, including excluded bookkeeping cells.
            "baseCosts" -> wire.hcursor.downField("costs").focus.get,
            "registration" -> run.registration.fold(Json.Null)(registeredOrigin),
            "selectionBasis" -> Json.fromString("reported-downstream-diagnostic"),
            "units" -> Json.fromValues(units.zipWithIndex.map { case (u, i) =>
              val row = result.posterior.rows(i)
              val set = evidence.candidates(i)
              val costs = evidence.breakdowns(i).toVector.filterNot(_._2.excluded).sortBy(_._1.key)
              val masses =
                if costs.isEmpty then Vector.empty
                else localMass(costs.map(_._2.total), config.temperature)
              Json.obj(
                "unit" -> Json.fromInt(u.ordinal),
                "unitId" -> Json.fromString(u.id.toString),
                "abstained" -> Json.fromBoolean(set.abstained),
                "candidateAccounting" -> accounting(evidence.provenance, i),
                "admittedAnchors" -> Json
                  .fromValues(evidence.nominated(i).map(r => Json.fromString(r.key))),
                "localComparison" -> (if costs.isEmpty then
                                        Json.obj(
                                          "status" -> Json.fromString("NotComputed"),
                                          "reason" -> Json.fromString("NoAdmittedStates")
                                        )
                                      else Json.obj("status" -> Json.fromString("Computed"))),
                "nominations" -> Json.fromValues(set.nominations.map { n =>
                  Json.obj(
                    "ref" -> Json.fromString(n.ref.key),
                    "level" -> run.levels.get(n.ref).fold(Json.Null)(Json.fromInt),
                    "channel" -> Json.fromString(n.channel),
                    "rankWithinLevel" -> Json.fromInt(n.rank),
                    "rawScore" -> n.rawScore.fold(Json.Null)(number),
                    "space" -> n.space.fold(Json.Null)(Json.fromString),
                    "receipt" -> n.receipt.fold(Json.Null)(Json.fromString)
                  )
                }),
                "states" -> Json.fromValues(costs.zip(masses).map { case ((state, cost), mass) =>
                  Json.obj(
                    "state" -> Json.fromString(state.key),
                    "anchor" -> anchor(state.anchor),
                    "cost" -> number(cost.total),
                    "localMass" -> number(mass),
                    "posteriorMass" -> number(row(state)),
                    "terms" -> Json.obj(
                      cost.terms.toVector
                        .sortBy(_._1.toString)
                        .map((t, v) => t.toString -> number(v))*
                    ),
                    "missingTerms" -> Json.fromValues(
                      cost.missingTerms.toVector.map(_.toString).sorted.map(Json.fromString)
                    ),
                    "imputedTerms" -> Json.fromValues(
                      cost.imputedTerms.keys.toVector.map(_.toString).sorted.map(Json.fromString)
                    )
                  )
                }),
                "hsmmViterbi" -> Json.fromString(result.viterbi(i).key),
                "posteriorAnchor" -> anchor(row.mapSource),
                "noFillAnchor" -> anchor(noFill(i)),
                "finalAnchor" -> anchor(chosen(i)),
                "assignedScene" -> decisions.lift(i).fold(Json.Null)(d => Json.fromInt(d.scene))
              )
            })
          )
          .noSpaces
      }
