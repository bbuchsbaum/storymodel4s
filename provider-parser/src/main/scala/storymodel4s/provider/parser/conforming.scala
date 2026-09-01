package storymodel4s.provider.parser

import cats.{Applicative, Monad}
import cats.syntax.all.*
import storymodel4s.amr.graph.{Decoder as AmrDecoder, TokenMarker}
import storymodel4s.amr.interop.{AmrCandidates, InteropError, MarkerPolicy, ToChart, TokenSpans}
import storymodel4s.amr.schema.FrameLexicon
import storymodel4s.core.*
import storymodel4s.proposition as p

/** Sentence-to-chart provider contract; every implementation returns one ordered attempt per input.
  */
trait AmrCandidateProvider[F[_]]:
  def runtime: ParserRuntime
  def config: ParserConfig
  def parse(batch: ParserBatch): F[ParserBatchResult]

/** Deterministic admission checks not implied by successful PENMAN conversion alone. */
object ParserAdmission:
  /** Require the exact sentence, at least one alignment, only sentence-contained atlas token spans,
    * and retained non-conversion provider provenance.
    */
  def validate(
      input: ParserSentenceInput,
      evidence: p.PropositionEvidence
  ): Either[ParserFailure, Unit] =
    val chart = evidence.chart
    if evidence.provenance != chart.provenance then Left(ParserFailure.ProvenanceMissing)
    else if chart.sentence != Some(input.sentenceId) || chart.alignments.isEmpty then
      Left(ParserFailure.AlignmentMissing)
    else if chart.alignments.exists(alignment =>
        alignment.spans.spans.toVector.exists(span => !input.sentenceSpan.contains(span))
      )
    then Left(ParserFailure.AlignmentEscapesSentence)
    else
      val atlasSpans = input.tokens.map(_.span).toSet
      if chart.alignments.exists(alignment =>
          alignment.spans.spans.toVector.exists(span => !atlasSpans.contains(span))
        )
      then Left(ParserFailure.AlignmentNotAtlasToken)
      else if !chart.provenance.receipts.exists(_.provider != ToChart.Provider) then
        Left(ParserFailure.ProvenanceMissing)
      else Right(())

  /** A fresh attempt is authoritative only when its exact call is retained by the chart. */
  private[parser] def containsCall(
      evidence: p.PropositionEvidence,
      call: ProviderCall
  ): Boolean = evidence.chart.provenance.receipts.contains(call)

/** JSON transport adapter that validates IDs, token echoes, PENMAN, alignment, and receipts. */
final class JsonAmrCandidateProvider[F[_]: Applicative] private (
    val runtime: ParserRuntime,
    val config: ParserConfig,
    lexicon: FrameLexicon,
    transport: ParserTransport[F]
) extends AmrCandidateProvider[F]:
  def parse(batch: ParserBatch): F[ParserBatchResult] =
    if batch.isEmpty then Applicative[F].pure(ParserBatchResult.unsafe(Vector.empty))
    else
      runtime match
        case ParserRuntime.Unavailable(reason) =>
          Applicative[F].pure(unavailable(batch, reason))
        case ParserRuntime.Ready(pinned) =>
          val requestJson = ParserEnvelope.encodeRequest(batch, pinned, config)
          transport.exchange(requestJson, config.timeoutMillis).map {
            case Left(failure) => transportFailure(batch, pinned, requestJson, failure)
            case Right(raw)    => decode(batch, pinned, requestJson, raw)
          }

  private def unavailable(
      batch: ParserBatch,
      reason: ParserSetupFailure
  ): ParserBatchResult =
    val failure = ParserFailure.fromSetup(reason)
    val attempts = batch.inputs.map { input =>
      val receipt = ParserAttemptReceipt.of(
        input,
        None,
        Vector(
          ParserAttemptDecision.RuntimeUnavailable(reason),
          ParserAttemptDecision.ResultRejected(failure)
        )
      )
      ParserAttempt.failed(input, failure, receipt)
    }
    ParserBatchResult.unsafe(attempts)

  private def transportFailure(
      batch: ParserBatch,
      pinned: PinnedRuntime,
      requestJson: String,
      reason: TransportFailure
  ): ParserBatchResult =
    val failure = ParserFailure.fromTransport(reason)
    val call = providerCall(
      pinned,
      requestJson,
      reason.render,
      Map("transport-outcome" -> reason.render)
    )
    val attempts = batch.inputs.map { input =>
      val receipt = ParserAttemptReceipt.of(
        input,
        Some(call),
        Vector(
          ParserAttemptDecision.TransportFailed(reason),
          ParserAttemptDecision.ResultRejected(failure)
        )
      )
      ParserAttempt.failed(input, failure, receipt)
    }
    ParserBatchResult.unsafe(attempts)

  private def decode(
      batch: ParserBatch,
      pinned: PinnedRuntime,
      requestJson: String,
      raw: String
  ): ParserBatchResult =
    ParserEnvelope.decodeResponse(raw) match
      case Left(error) =>
        val errorChecksum = Checksum.ofText(error)
        val failure = ParserFailure.MalformedEnvelope(errorChecksum)
        val call = providerCall(pinned, requestJson, raw, Map.empty)
        allFailed(
          batch,
          failure,
          Some(call),
          Vector(ParserBatchDecision.EnvelopeRejected(errorChecksum))
        )
      case Right(response) if response.schema != ParserEnvelope.ResultSchema =>
        val failure = ParserFailure.WrongSchema(Checksum.ofText(response.schema))
        val call = providerCall(pinned, requestJson, raw, diagnosticParams(response.diagnostics))
        allFailed(batch, failure, Some(call))
      case Right(response) if response.runtimeFingerprint != pinned.fingerprint =>
        val failure = ParserFailure.RuntimeFingerprintMismatch(
          pinned.fingerprint,
          response.runtimeFingerprint
        )
        val call = providerCall(pinned, requestJson, raw, diagnosticParams(response.diagnostics))
        allFailed(batch, failure, Some(call))
      case Right(response) if response.configChecksum != config.checksum =>
        val failure = ParserFailure.ConfigChecksumMismatch(
          config.checksum,
          response.configChecksum
        )
        val call = providerCall(pinned, requestJson, raw, diagnosticParams(response.diagnostics))
        allFailed(batch, failure, Some(call))
      case Right(response) => normalize(batch, pinned, requestJson, raw, response)

  private def normalize(
      batch: ParserBatch,
      pinned: PinnedRuntime,
      requestJson: String,
      raw: String,
      response: WireResponse
  ): ParserBatchResult =
    val call = providerCall(pinned, requestJson, raw, diagnosticParams(response.diagnostics))
    val expected = batch.ids.zipWithIndex.toMap
    val grouped = response.items.groupBy(_.id)
    val unexpected = grouped.keysIterator
      .filterNot(expected.contains)
      .toVector
      .sortBy(_.value)
      .map(ParserBatchDecision.UnexpectedOutput(_))
    val duplicates = grouped.toVector
      .collect { case (id, values) if values.size > 1 => id -> values.size }
      .sortBy(_._1.value)
      .map { (id, count) => ParserBatchDecision.DuplicateOutput(id, count) }
    val reordered = response.items.zipWithIndex.flatMap { (item, actual) =>
      expected.get(item.id).filter(_ != actual).map { wanted =>
        ParserBatchDecision.Reordered(item.id, actual, wanted)
      }
    }
    val decisions = unexpected ++ duplicates ++ reordered
    val attempts = batch.inputs.map { input =>
      grouped.getOrElse(input.id, Vector.empty) match
        case Vector(item)             => normalizeItem(input, item, call)
        case values if values.isEmpty =>
          failed(input, ParserFailure.MissingOutput(input.id), call)
        case values => failed(input, ParserFailure.DuplicateOutput(input.id, values.size), call)
    }
    ParserBatchResult.unsafe(attempts, decisions)

  private def normalizeItem(
      input: ParserSentenceInput,
      item: WireItem,
      call: ProviderCall
  ): ParserAttempt =
    val observations = Vector(
      ParserAttemptDecision.ModelInputObserved(
        ModelInputObservation.fromRaw(input, call, item.modelInput)
      )
    )
    tokenMismatch(input, item.tokens) match
      case Some(index) =>
        failed(input, ParserFailure.TokenEchoMismatch(index), call, observations)
      case None =>
        val expectedModelInput = input.tokens.map(_.text).mkString(" ")
        if item.modelInput != expectedModelInput then
          failed(input, ParserFailure.ModelInputMismatch, call, observations)
        else
          item.result match
            case WireItemResult.Failed(code) =>
              failed(input, ParserFailure.ProviderFailed(code), call, observations)
            case WireItemResult.Abstained(code) =>
              failed(input, ParserFailure.ProviderAbstained(code), call, observations)
            case WireItemResult.Proposed(penman, schema, dialect, alignments) =>
              proposal(input, penman, schema, dialect, alignments, call, observations)

  private def proposal(
      input: ParserSentenceInput,
      penman: String,
      alignmentSchema: String,
      alignmentDialect: String,
      alignments: Vector[WireMarkerAlignment],
      call: ProviderCall,
      observations: Vector[ParserAttemptDecision]
  ): ParserAttempt =
    if alignmentSchema != ParserEnvelope.MarkerSidecarSchema then
      failed(
        input,
        ParserFailure.AlignmentSidecarSchemaMismatch(Checksum.ofText(alignmentSchema)),
        call,
        observations
      )
    else
      ParserAlignmentDialect.fromWire(alignmentDialect) match
        case Some(ParserAlignmentDialect.ExplicitIndexListV1) =>
          AmrDecoder.fromPenman(penman) match
            case Left(error) =>
              failed(
                input,
                ParserFailure.fromInterop(InteropError.Malformed(error)),
                call,
                observations
              )
            case Right(decoded) =>
              validateSidecar(input, call, decoded.markers, alignments) match
                case Left(failure)  => failed(input, failure, call, observations)
                case Right(sidecar) =>
                  val accepted =
                    observations :+ ParserAttemptDecision.AlignmentSidecarAccepted(sidecar)
                  admitPenman(input, penman, call, accepted)
        case Some(ParserAlignmentDialect.IbmIsiRangeV1) | None =>
          failed(
            input,
            ParserFailure.AlignmentDialectUnsupported(Checksum.ofText(alignmentDialect)),
            call,
            observations
          )

  private def admitPenman(
      input: ParserSentenceInput,
      penman: String,
      call: ProviderCall,
      decisions: Vector[ParserAttemptDecision]
  ): ParserAttempt =
    AmrCandidates
      .fromPenman(
        penman,
        lexicon,
        Some(input.sentenceId),
        receipts = Vector(call),
        tokens = Some(TokenSpans.fromSpans(input.tokens.map(_.span))),
        markers = MarkerPolicy.Strict
      )
      .fold(
        error => failed(input, ParserFailure.fromInterop(error), call, decisions),
        chart =>
          val evidence = p.PropositionEvidence.of(chart)
          ParserAdmission.validate(input, evidence) match
            case Left(failure) => failed(input, failure, call, decisions)
            case Right(_)      =>
              val receipt = ParserAttemptReceipt.of(input, Some(call), decisions)
              ParserAttempt
                .proposed(input, ParserProposal.of(evidence), receipt)
                .fold(failure => ParserAttempt.failed(input, failure, receipt), identity)
      )

  private def validateSidecar(
      input: ParserSentenceInput,
      call: ProviderCall,
      markers: Vector[TokenMarker],
      alignments: Vector[WireMarkerAlignment]
  ): Either[ParserFailure, AcceptedAlignmentSidecar] =
    if markers.size != alignments.size then
      Left(
        ParserFailure.AlignmentSidecarInvalid(
          AlignmentSidecarIssue.MarkerCountMismatch(markers.size, alignments.size)
        )
      )
    else
      markers
        .zip(alignments)
        .zipWithIndex
        .foldLeft[Either[ParserFailure, Vector[(ProviderNodeId, Vector[Int])]]](
          Right(Vector.empty)
        ) {
          case (rejected @ Left(_), _)                                => rejected
          case (Right(acceptedRows), ((marker, alignment), rowIndex)) =>
            validateMarker(input, marker, alignment, rowIndex).map(acceptedRows :+ _)
        }
        .map { acceptedRows =>
          AcceptedAlignmentSidecar.fromValidated(
            input,
            call,
            ParserAlignmentDialect.ExplicitIndexListV1,
            acceptedRows
          )
        }

  private def validateMarker(
      input: ParserSentenceInput,
      marker: TokenMarker,
      alignment: WireMarkerAlignment,
      rowIndex: Int
  ): Either[ParserFailure, (ProviderNodeId, Vector[Int])] =
    def invalid(
        issue: AlignmentSidecarIssue
    ): Either[ParserFailure, (ProviderNodeId, Vector[Int])] =
      Left(ParserFailure.AlignmentSidecarInvalid(issue))

    val indices = alignment.tokenIndices
    if alignment.ordinal != rowIndex then
      invalid(AlignmentSidecarIssue.OrdinalMismatch(rowIndex, alignment.ordinal))
    else
      ProviderNodeId.from(alignment.providerNodeId) match
        case Left(_)               => invalid(AlignmentSidecarIssue.ProviderNodeIdInvalid(rowIndex))
        case Right(providerNodeId) =>
          if indices.isEmpty then invalid(AlignmentSidecarIssue.EmptyIndices(rowIndex))
          else if indices != indices.sorted then
            invalid(AlignmentSidecarIssue.IndicesUnsorted(rowIndex))
          else
            indices.sliding(2).collectFirst {
              case Vector(left, right) if left == right => left
            } match
              case Some(index) =>
                invalid(AlignmentSidecarIssue.DuplicateIndex(rowIndex, index))
              case None =>
                indices.find(index => index < 0 || index >= input.tokens.size) match
                  case Some(index) =>
                    invalid(
                      AlignmentSidecarIssue.TokenIndexOutOfRange(
                        rowIndex,
                        index,
                        input.tokens.size
                      )
                    )
                  case None if marker.marker.prefix != Some("e") =>
                    invalid(AlignmentSidecarIssue.MarkerPrefixMismatch(rowIndex))
                  case None if marker.marker.indices != indices =>
                    invalid(
                      AlignmentSidecarIssue.MarkerVectorMismatch(
                        rowIndex,
                        marker.marker.indices,
                        indices
                      )
                    )
                  case None => Right(providerNodeId -> indices)

  private def failed(
      input: ParserSentenceInput,
      failure: ParserFailure,
      call: ProviderCall,
      observations: Vector[ParserAttemptDecision] = Vector.empty
  ): ParserAttempt =
    val receipt = ParserAttemptReceipt.of(
      input,
      Some(call),
      observations :+ ParserAttemptDecision.ResultRejected(failure)
    )
    ParserAttempt.failed(input, failure, receipt)

  private def allFailed(
      batch: ParserBatch,
      failure: ParserFailure,
      call: Option[ProviderCall],
      decisions: Vector[ParserBatchDecision] = Vector.empty
  ): ParserBatchResult =
    val attempts = batch.inputs.map { input =>
      val receipt = ParserAttemptReceipt.of(
        input,
        call,
        Vector(ParserAttemptDecision.ResultRejected(failure))
      )
      ParserAttempt.failed(input, failure, receipt)
    }
    ParserBatchResult.unsafe(attempts, decisions)

  private def tokenMismatch(
      input: ParserSentenceInput,
      echoed: Vector[WireToken]
  ): Option[Int] =
    if echoed.size != input.tokens.size then Some(math.min(echoed.size, input.tokens.size))
    else
      val index = echoed.zip(input.tokens).indexWhere { (wire, atlas) =>
        wire.id != atlas.id || wire.start != atlas.span.start ||
        wire.endExclusive != atlas.span.endExclusive || wire.text != atlas.text
      }
      Option.when(index >= 0)(index)

  private def providerCall(
      pinned: PinnedRuntime,
      requestJson: String,
      outputMaterial: String,
      extraParams: Map[String, String]
  ): ProviderCall =
    ProviderCall(
      provider = pinned.provider,
      model = pinned.model,
      version = pinned.version,
      promptTemplateVersion = None,
      inputChecksum = Checksum.ofText(requestJson),
      outputChecksum = Checksum.ofText(outputMaterial),
      params = config.params ++ Map(
        "runtime-fingerprint" -> pinned.fingerprint.value,
        "request-schema" -> ParserEnvelope.RequestSchema,
        "result-schema" -> ParserEnvelope.ResultSchema,
        "alignment-schema" -> ParserEnvelope.MarkerSidecarSchema,
        "alignment-dialect" -> ParserAlignmentDialect.ExplicitIndexListV1.wireName
      ) ++ extraParams,
      seed = config.seed,
      cached = false
    )

  private def diagnosticParams(diagnostics: WireDiagnostics): Map[String, String] =
    Map(
      "duration-millis" -> diagnostics.durationMillis.toString,
      "stderr-bytes" -> diagnostics.stderrBytes.toString
    ) ++ diagnostics.exitCode.map(code => "exit-code" -> code.toString) ++
      diagnostics.stderrChecksum.map(sum => "stderr-checksum" -> sum.hex)

object JsonAmrCandidateProvider:
  def apply[F[_]: Applicative](
      runtime: ParserRuntime,
      config: ParserConfig,
      lexicon: FrameLexicon,
      transport: ParserTransport[F]
  ): JsonAmrCandidateProvider[F] =
    new JsonAmrCandidateProvider(runtime, config, lexicon, transport)

/** Conservative adapter that isolates every sentence-level provider invocation from sibling
  * materialization failures.
  */
final class SentenceIsolatingParserProvider[F[_]: Monad] private (
    delegate: AmrCandidateProvider[F]
) extends AmrCandidateProvider[F]:
  val runtime: ParserRuntime = delegate.runtime
  val config: ParserConfig = delegate.config

  def parse(batch: ParserBatch): F[ParserBatchResult] =
    batch.inputs
      .traverse { input =>
        val singleton = ParserBatch.unsafe(Vector(input))
        delegate.parse(singleton).map { raw =>
          raw.conforms(singleton) match
            case Right(valid) =>
              valid.attempts match
                case Vector(attempt) => attempt -> valid.decisions
                case attempts        =>
                  rejected(input, valid.decisions, s"conformed-count:${attempts.size}")
            case Left(error) => rejected(input, raw.decisions, error.toString)
        }
      }
      .map { isolated =>
        ParserBatchResult.unsafe(
          isolated.map(_._1),
          isolated.flatMap(_._2)
        )
      }

  private def rejected(
      input: ParserSentenceInput,
      decisions: Vector[ParserBatchDecision],
      material: String
  ): (ParserAttempt, Vector[ParserBatchDecision]) =
    val failure = ParserFailure.ProviderContractViolation(Checksum.ofText(material))
    val receipt = ParserAttemptReceipt.of(
      input,
      None,
      Vector(ParserAttemptDecision.ResultRejected(failure))
    )
    ParserAttempt.failed(input, failure, receipt) -> decisions

object SentenceIsolatingParserProvider:
  /** Wrap a provider so a batch-level crash cannot erase a valid sibling result. */
  def apply[F[_]: Monad](
      delegate: AmrCandidateProvider[F]
  ): SentenceIsolatingParserProvider[F] = new SentenceIsolatingParserProvider(delegate)

/** Per-request evidence that a repeated execution stayed canonical under the same pin. */
enum ParserDeterminismCheck:
  case Stable(id: ParserRequestId)
  case Changed(id: ParserRequestId, failure: ParserFailure.Nondeterministic)

/** Complete determinism report; changed items never erase stable siblings. */
final class ParserDeterminismReport private[parser] (
    val checks: Vector[ParserDeterminismCheck]
):
  def isStable: Boolean = checks.forall {
    case ParserDeterminismCheck.Stable(_)     => true
    case ParserDeterminismCheck.Changed(_, _) => false
  }

  def failures: Vector[(ParserRequestId, ParserFailure.Nondeterministic)] = checks.collect {
    case ParserDeterminismCheck.Changed(id, failure) => id -> failure
  }

  override def equals(other: Any): Boolean = other match
    case that: ParserDeterminismReport => checks == that.checks
    case _                             => false

  override def hashCode(): Int = checks.hashCode
  override def toString: String =
    s"ParserDeterminismReport(stable=$isStable, checks=${checks.size})"

/** Canonical comparison court for two uncached executions under the same runtime and config. */
object ParserDeterminism:
  def compare(
      batch: ParserBatch,
      first: ParserBatchResult,
      second: ParserBatchResult
  ): Either[ParserResultError, ParserDeterminismReport] =
    for
      left <- first.conforms(batch)
      right <- second.conforms(batch)
    yield
      val checks = left.attempts.zip(right.attempts).map { (a, b) =>
        val expected = outcomeDigest(a)
        val found = outcomeDigest(b)
        if expected == found then ParserDeterminismCheck.Stable(a.id)
        else
          ParserDeterminismCheck.Changed(
            a.id,
            ParserFailure.Nondeterministic(expected, found)
          )
      }
      new ParserDeterminismReport(checks)

  private def outcomeDigest(attempt: ParserAttempt): Checksum = attempt.result match
    case Left(failure) =>
      ParserIdentity.digest("parser-result-failure/v2", failure.canonicalParts)
    case Right(proposal) => proposal.canonicalDigest
