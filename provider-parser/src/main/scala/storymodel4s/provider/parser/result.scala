package storymodel4s.provider.parser

import storymodel4s.amr.interop.InteropError
import storymodel4s.core.*
import storymodel4s.proposition as p

/** Provider-emitted failure code validated as a nonblank, whitespace-free token. */
object ProviderFailureCode:
  opaque type ProviderFailureCode = String

  private val MaxLength = 128

  def from(raw: String): Either[String, ProviderFailureCode] =
    if raw.nonEmpty && raw.length <= MaxLength && !raw.exists(_.isWhitespace) then Right(raw)
    else Left(s"provider failure code must be a nonblank token of at most $MaxLength characters")

  extension (code: ProviderFailureCode) def value: String = code

type ProviderFailureCode = ProviderFailureCode.ProviderFailureCode

/** Stable category for an AMR interop rejection without retaining provider-emitted detail. */
enum InteropFailureKind:
  case Malformed
  case GraphInvalid
  case ChartInvalid
  case InvalidIdentifier
  case Lossy

  private[parser] def canonicalTag: String = this match
    case Malformed         => "malformed"
    case GraphInvalid      => "graph-invalid"
    case ChartInvalid      => "chart-invalid"
    case InvalidIdentifier => "invalid-identifier"
    case Lossy             => "lossy"

/** Exact structural reason a provider marker sidecar could not be trusted. */
enum AlignmentSidecarIssue:
  case MarkerCountMismatch(expected: Int, found: Int)
  case OrdinalMismatch(rowIndex: Int, found: Int)
  case ProviderNodeIdInvalid(rowIndex: Int)
  case EmptyIndices(rowIndex: Int)
  case IndicesUnsorted(rowIndex: Int)
  case DuplicateIndex(rowIndex: Int, index: Int)
  case TokenIndexOutOfRange(rowIndex: Int, index: Int, tokenCount: Int)
  case MarkerPrefixMismatch(rowIndex: Int)
  case MarkerVectorMismatch(rowIndex: Int, rendered: Vector[Int], structured: Vector[Int])

  private[parser] def render: String = this match
    case MarkerCountMismatch(expected, found)    => s"marker-count:$expected:$found"
    case OrdinalMismatch(row, found)             => s"ordinal:$row:$found"
    case ProviderNodeIdInvalid(row)              => s"provider-node-id:$row"
    case EmptyIndices(row)                       => s"empty-indices:$row"
    case IndicesUnsorted(row)                    => s"indices-unsorted:$row"
    case DuplicateIndex(row, index)              => s"duplicate-index:$row:$index"
    case TokenIndexOutOfRange(row, index, count) =>
      s"index-out-of-range:$row:$index:$count"
    case MarkerPrefixMismatch(row)                       => s"marker-prefix:$row"
    case MarkerVectorMismatch(row, rendered, structured) =>
      s"marker-vector:$row:${rendered.mkString(",")}:${structured.mkString(",")}"

  private[parser] def canonicalParts: Vector[String] = this match
    case MarkerCountMismatch(expected, found) =>
      Vector("marker-count-mismatch", expected.toString, found.toString)
    case OrdinalMismatch(row, found) =>
      Vector("ordinal-mismatch", row.toString, found.toString)
    case ProviderNodeIdInvalid(row) => Vector("provider-node-id-invalid", row.toString)
    case EmptyIndices(row)          => Vector("empty-indices", row.toString)
    case IndicesUnsorted(row)       => Vector("indices-unsorted", row.toString)
    case DuplicateIndex(row, index) =>
      Vector("duplicate-index", row.toString, index.toString)
    case TokenIndexOutOfRange(row, index, count) =>
      Vector("token-index-out-of-range", row.toString, index.toString, count.toString)
    case MarkerPrefixMismatch(row) => Vector("marker-prefix-mismatch", row.toString)
    case MarkerVectorMismatch(row, rendered, structured) =>
      Vector("marker-vector-mismatch", row.toString, "rendered", rendered.size.toString) ++
        rendered.map(_.toString) ++ Vector("structured", structured.size.toString) ++
        structured.map(_.toString)

/** Why a provider node identity could not enter accepted alignment provenance. */
enum ProviderNodeIdError:
  case Blank
  case TooLong(maxLength: Int)
  case ContainsWhitespace

/** Validated, bounded provider-local node identity retained only as alignment provenance. */
object ProviderNodeId:
  opaque type ProviderNodeId = String

  private val MaxLength = 128

  def from(raw: String): Either[ProviderNodeIdError, ProviderNodeId] =
    if raw.isEmpty then Left(ProviderNodeIdError.Blank)
    else if raw.length > MaxLength then Left(ProviderNodeIdError.TooLong(MaxLength))
    else if raw.exists(_.isWhitespace) then Left(ProviderNodeIdError.ContainsWhitespace)
    else Right(raw)

  extension (id: ProviderNodeId) def value: String = id

type ProviderNodeId = ProviderNodeId.ProviderNodeId

/** Redacted proof of the exact string observed for one request and provider call. */
final class ModelInputObservation private (
    val checksum: Checksum,
    private[parser] val bindingChecksum: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: ModelInputObservation =>
      checksum == that.checksum && bindingChecksum == that.bindingChecksum
    case _ => false

  override def hashCode(): Int = (checksum, bindingChecksum).hashCode
  override def toString: String = s"ModelInputObservation(${checksum.short()})"

object ModelInputObservation:
  private[parser] def fromRaw(
      input: ParserSentenceInput,
      call: ProviderCall,
      raw: String
  ): ModelInputObservation =
    new ModelInputObservation(Checksum.ofText(raw), binding(input, call))

  private[parser] def admits(
      observation: ModelInputObservation,
      input: ParserSentenceInput,
      call: ProviderCall
  ): Boolean = observation.bindingChecksum == binding(input, call)

  private def binding(input: ParserSentenceInput, call: ProviderCall): Checksum =
    ParserIdentity.digest(
      "parser-model-input-observation-binding/v1",
      Vector(input.id.value, input.checksum.hex, ParserAttemptReceipt.callDigest(call).hex)
    )

/** Privately constructed proof that an ordered structured alignment sidecar passed admission.
  *
  * The checksum is derived inside the constructor boundary from the declared dialect, every
  * provider-node identity, and every exact token-index vector, so provenance fields cannot be
  * paired with an unrelated digest.
  */
final class AcceptedAlignmentSidecar private (
    val dialect: ParserAlignmentDialect,
    val providerNodeIds: Vector[ProviderNodeId],
    val tokenIndices: Vector[Vector[Int]],
    val checksum: Checksum,
    private[parser] val bindingChecksum: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: AcceptedAlignmentSidecar =>
      dialect == that.dialect && providerNodeIds == that.providerNodeIds &&
      tokenIndices == that.tokenIndices && checksum == that.checksum &&
      bindingChecksum == that.bindingChecksum
    case _ => false

  override def hashCode(): Int =
    (dialect, providerNodeIds, tokenIndices, checksum, bindingChecksum).hashCode

  override def toString: String =
    s"AcceptedAlignmentSidecar(${dialect.wireName}, rows=${providerNodeIds.size}, " +
      s"checksum=${checksum.short()})"

object AcceptedAlignmentSidecar:
  private[parser] def fromValidated(
      input: ParserSentenceInput,
      call: ProviderCall,
      dialect: ParserAlignmentDialect,
      acceptedRows: Vector[(ProviderNodeId, Vector[Int])]
  ): AcceptedAlignmentSidecar =
    val rows = acceptedRows.zipWithIndex.flatMap { case ((providerNodeId, indices), ordinal) =>
      Vector("row", ordinal.toString, providerNodeId.value, "indices", indices.size.toString) ++
        indices.map(_.toString)
    }
    val checksum = ParserIdentity.digest(
      "parser-marker-sidecar/v2",
      Vector(ParserEnvelope.MarkerSidecarSchema, dialect.wireName) ++ rows
    )
    val providerNodeIds = acceptedRows.map(_._1)
    val tokenIndices = acceptedRows.map(_._2)
    new AcceptedAlignmentSidecar(
      dialect,
      providerNodeIds,
      tokenIndices,
      checksum,
      binding(input, call)
    )

  private[parser] def admits(
      sidecar: AcceptedAlignmentSidecar,
      input: ParserSentenceInput,
      call: ProviderCall
  ): Boolean = sidecar.bindingChecksum == binding(input, call)

  private def binding(input: ParserSentenceInput, call: ProviderCall): Checksum =
    ParserIdentity.digest(
      "parser-alignment-sidecar-binding/v1",
      Vector(input.id.value, input.checksum.hex, ParserAttemptReceipt.callDigest(call).hex)
    )

/** Why one sentence did not produce checked, exactly aligned proposition evidence. */
enum ParserFailure:
  case RuntimeUnavailable(reason: ParserSetupFailure)
  case Timeout(limitMillis: Long)
  case NonZeroExit(exitCode: Int, stderrChecksum: Checksum)
  case MaterializationFailed(errorChecksum: Checksum)
  case TransportIo(code: ProviderFailureCode)
  case MalformedEnvelope(errorChecksum: Checksum)
  case WrongSchema(foundChecksum: Checksum)
  case RuntimeFingerprintMismatch(expected: Fingerprint, found: Fingerprint)
  case ConfigChecksumMismatch(expected: Checksum, found: Checksum)
  case MissingOutput(id: ParserRequestId)
  case DuplicateOutput(id: ParserRequestId, count: Int)
  case TokenEchoMismatch(index: Int)
  case ModelInputMismatch
  case ProviderFailed(code: ProviderFailureCode)
  case ProviderAbstained(code: ProviderFailureCode)
  case AlignmentSidecarSchemaMismatch(foundChecksum: Checksum)
  case AlignmentDialectUnsupported(foundChecksum: Checksum)
  case AlignmentSidecarInvalid(issue: AlignmentSidecarIssue)
  case InteropRejected(kind: InteropFailureKind, detailChecksum: Checksum)
  case AlignmentMissing
  case AlignmentEscapesSentence
  case AlignmentNotAtlasToken
  case ProvenanceMissing
  case ProviderContractViolation(errorChecksum: Checksum)
  case CacheCorrupt(reason: String)
  case Nondeterministic(expected: Checksum, found: Checksum)

  /** Sanitized rendering suitable for receipts and logs. */
  def render: String = this match
    case RuntimeUnavailable(reason)           => reason.render
    case Timeout(limit)                       => s"timeout:$limit"
    case NonZeroExit(code, sum)               => s"exit:$code:${sum.hex}"
    case MaterializationFailed(sum)           => s"materialization:${sum.hex}"
    case TransportIo(code)                    => s"transport:${code.value}"
    case MalformedEnvelope(sum)               => s"malformed-envelope:${sum.hex}"
    case WrongSchema(found)                   => s"wrong-schema:${found.hex}"
    case RuntimeFingerprintMismatch(exp, got) =>
      s"runtime-mismatch:${exp.value}:${got.value}"
    case ConfigChecksumMismatch(exp, got)    => s"config-mismatch:${exp.hex}:${got.hex}"
    case MissingOutput(id)                   => s"missing-output:${id.value}"
    case DuplicateOutput(id, count)          => s"duplicate-output:${id.value}:$count"
    case TokenEchoMismatch(index)            => s"token-echo-mismatch:$index"
    case ModelInputMismatch                  => "model-input-mismatch"
    case ProviderFailed(code)                => s"provider-failed:${code.value}"
    case ProviderAbstained(code)             => s"provider-abstained:${code.value}"
    case AlignmentSidecarSchemaMismatch(sum) => s"alignment-schema:${sum.hex}"
    case AlignmentDialectUnsupported(sum)    => s"alignment-dialect-unsupported:${sum.hex}"
    case AlignmentSidecarInvalid(issue)      => s"alignment-sidecar:${issue.render}"
    case InteropRejected(kind, detail)       => s"interop:$kind:${detail.hex}"
    case AlignmentMissing                    => "alignment-missing"
    case AlignmentEscapesSentence            => "alignment-escapes-sentence"
    case AlignmentNotAtlasToken              => "alignment-not-atlas-token"
    case ProvenanceMissing                   => "provenance-missing"
    case ProviderContractViolation(sum)      => s"provider-contract:${sum.hex}"
    case CacheCorrupt(reason)                => s"cache-corrupt:${Checksum.ofText(reason).hex}"
    case Nondeterministic(expected, got)     => s"nondeterministic:${expected.hex}:${got.hex}"

  private[parser] def canonicalParts: Vector[String] = this match
    case RuntimeUnavailable(reason) => Vector("runtime-unavailable", reason.checksum.hex)
    case Timeout(limit)             => Vector("timeout", limit.toString)
    case NonZeroExit(code, sum)     => Vector("non-zero-exit", code.toString, sum.hex)
    case MaterializationFailed(sum) => Vector("materialization-failed", sum.hex)
    case TransportIo(code)          => Vector("transport-io", code.value)
    case MalformedEnvelope(sum)     => Vector("malformed-envelope", sum.hex)
    case WrongSchema(found)         => Vector("wrong-schema", found.hex)
    case RuntimeFingerprintMismatch(expected, found) =>
      Vector("runtime-fingerprint-mismatch", expected.value, found.value)
    case ConfigChecksumMismatch(expected, found) =>
      Vector("config-checksum-mismatch", expected.hex, found.hex)
    case MissingOutput(id)                   => Vector("missing-output", id.value)
    case DuplicateOutput(id, count)          => Vector("duplicate-output", id.value, count.toString)
    case TokenEchoMismatch(index)            => Vector("token-echo-mismatch", index.toString)
    case ModelInputMismatch                  => Vector("model-input-mismatch")
    case ProviderFailed(code)                => Vector("provider-failed", code.value)
    case ProviderAbstained(code)             => Vector("provider-abstained", code.value)
    case AlignmentSidecarSchemaMismatch(sum) =>
      Vector("alignment-sidecar-schema-mismatch", sum.hex)
    case AlignmentDialectUnsupported(sum) =>
      Vector("alignment-dialect-unsupported", sum.hex)
    case AlignmentSidecarInvalid(issue) =>
      Vector("alignment-sidecar-invalid") ++ issue.canonicalParts
    case InteropRejected(kind, detail) =>
      Vector("interop-rejected", kind.canonicalTag, detail.hex)
    case AlignmentMissing                  => Vector("alignment-missing")
    case AlignmentEscapesSentence          => Vector("alignment-escapes-sentence")
    case AlignmentNotAtlasToken            => Vector("alignment-not-atlas-token")
    case ProvenanceMissing                 => Vector("provenance-missing")
    case ProviderContractViolation(sum)    => Vector("provider-contract-violation", sum.hex)
    case CacheCorrupt(reason)              => Vector("cache-corrupt", Checksum.ofText(reason).hex)
    case Nondeterministic(expected, found) =>
      Vector("nondeterministic", expected.hex, found.hex)

object ParserFailure:
  private[parser] def fromInterop(error: InteropError): ParserFailure =
    val kind = error match
      case InteropError.Malformed(_)               => InteropFailureKind.Malformed
      case InteropError.GraphInvalid(_)            => InteropFailureKind.GraphInvalid
      case InteropError.ChartInvalid(_)            => InteropFailureKind.ChartInvalid
      case InteropError.InvalidIdentifier(_, _, _) => InteropFailureKind.InvalidIdentifier
      case InteropError.Lossy(_)                   => InteropFailureKind.Lossy
    ParserFailure.InteropRejected(kind, Checksum.ofText(error.message))

  private[parser] def fromSetup(error: ParserSetupFailure): ParserFailure =
    ParserFailure.RuntimeUnavailable(error)

  private[parser] def fromTransport(error: TransportFailure): ParserFailure = error match
    case TransportFailure.Timeout(limit)         => ParserFailure.Timeout(limit)
    case TransportFailure.NonZeroExit(code, sum) => ParserFailure.NonZeroExit(code, sum)
    case TransportFailure.Materialization(sum)   => ParserFailure.MaterializationFailed(sum)
    case TransportFailure.Io(code)               => ParserFailure.TransportIo(code)

/** Batch-level normalization that cannot be attached to an input that never existed. */
enum ParserBatchDecision:
  case UnexpectedOutput(id: ParserRequestId)
  case DuplicateOutput(id: ParserRequestId, count: Int)
  case Reordered(id: ParserRequestId, fromIndex: Int, toIndex: Int)
  case EnvelopeRejected(errorChecksum: Checksum)

  private[parser] def render: String = this match
    case UnexpectedOutput(id)              => s"unexpected:${id.value}"
    case DuplicateOutput(id, count)        => s"duplicate:${id.value}:$count"
    case Reordered(id, fromIndex, toIndex) => s"reordered:${id.value}:$fromIndex:$toIndex"
    case EnvelopeRejected(sum)             => s"envelope-rejected:${sum.hex}"

/** Why an attempt has no fresh provider call or why a result was refused. */
enum ParserAttemptDecision:
  case CacheHit(cached: CachedParserProposal)
  case CacheMiss(key: ParserCacheKey)
  case RuntimeUnavailable(reason: ParserSetupFailure)
  case TransportFailed(reason: TransportFailure)
  case ModelInputObserved(observation: ModelInputObservation)
  case AlignmentSidecarAccepted(sidecar: AcceptedAlignmentSidecar)
  case ResultRejected(reason: ParserFailure)

  private[parser] def render: String = this match
    case CacheHit(cached) =>
      s"cache-hit:${cached.key.checksum.hex}:${cached.sourceReceipt.hex}"
    case CacheMiss(key)                    => s"cache-miss:${key.checksum.hex}"
    case RuntimeUnavailable(reason)        => reason.render
    case TransportFailed(reason)           => s"transport-failed:${reason.render}"
    case ModelInputObserved(observation)   => s"model-input:${observation.checksum.hex}"
    case AlignmentSidecarAccepted(sidecar) =>
      s"alignment-sidecar:${sidecar.dialect.wireName}:${sidecar.checksum.hex}"
    case ResultRejected(reason) => s"result-rejected:${reason.render}"

  private[parser] def canonicalParts: Vector[String] = this match
    case CacheHit(cached)           => Vector("cache-hit", cached.checksum.hex)
    case CacheMiss(key)             => Vector("cache-miss", key.checksum.hex)
    case RuntimeUnavailable(reason) =>
      Vector("runtime-unavailable", reason.checksum.hex)
    case TransportFailed(reason)         => Vector("transport-failed") ++ reason.canonicalParts
    case ModelInputObserved(observation) =>
      Vector(
        "model-input-observed",
        observation.checksum.hex,
        observation.bindingChecksum.hex
      )
    case AlignmentSidecarAccepted(sidecar) =>
      Vector(
        "alignment-sidecar-accepted",
        sidecar.dialect.wireName,
        sidecar.checksum.hex,
        sidecar.bindingChecksum.hex
      )
    case ResultRejected(reason) => Vector("result-rejected") ++ reason.canonicalParts

/** Content-addressed receipt for one parser attempt, including zero-call outcomes. */
final class ParserAttemptReceipt private (
    val requestId: ParserRequestId,
    val requestChecksum: Checksum,
    val call: Option[ProviderCall],
    val decisions: Vector[ParserAttemptDecision],
    val digest: Checksum
):
  def isCacheHit: Boolean = decisions.exists {
    case ParserAttemptDecision.CacheHit(_) => true
    case _                                 => false
  }

  /** Classify the complete result/decision relation as one legal attempt branch. */
  private[parser] def branch(
      input: ParserSentenceInput,
      result: Either[ParserFailure, ParserProposal],
      expectedCacheKey: Option[ParserCacheKey]
  ): Option[ParserAttemptReceipt.Branch] =
    ParserAttemptReceipt.DecisionParts.from(decisions).flatMap { parts =>
      result match
        case Right(proposal) => successBranch(input, proposal, expectedCacheKey, parts)
        case Left(failure)   => failureBranch(input, failure, expectedCacheKey, parts)
    }

  private def successBranch(
      input: ParserSentenceInput,
      proposal: ParserProposal,
      expectedCacheKey: Option[ParserCacheKey],
      parts: ParserAttemptReceipt.DecisionParts
  ): Option[ParserAttemptReceipt.Branch] =
    call match
      case Some(fresh)
          if !fresh.cached && parts.cacheHit.isEmpty && parts.unavailable.isEmpty &&
            parts.transport.isEmpty && parts.rejected.isEmpty &&
            parts.cacheMissMatches(expectedCacheKey) &&
            parts.observationsAdmit(input, fresh) &&
            ParserAdmission.containsCall(proposal.evidence, fresh) =>
        Some(ParserAttemptReceipt.Branch.FreshSuccess)
      case None
          if parts.cacheMiss.isEmpty && parts.unavailable.isEmpty && parts.transport.isEmpty &&
            parts.modelInput.isEmpty && parts.sidecar.isEmpty && parts.rejected.isEmpty =>
        (parts.cacheHit, expectedCacheKey) match
          case (Some(cached), Some(expected))
              if cached.key == expected && cached.admits(input, proposal) =>
            Some(ParserAttemptReceipt.Branch.CachedSuccess)
          case _ => None
      case _ => None

  private def failureBranch(
      input: ParserSentenceInput,
      failure: ParserFailure,
      expectedCacheKey: Option[ParserCacheKey],
      parts: ParserAttemptReceipt.DecisionParts
  ): Option[ParserAttemptReceipt.Branch] =
    if parts.rejected != Some(failure) then None
    else
      ParserAttemptReceipt.failureKind(failure) match
        case ParserAttemptReceipt.FailureKind.Runtime =>
          failure match
            case ParserFailure.RuntimeUnavailable(reason)
                if call.isEmpty && expectedCacheKey.isEmpty && parts.cacheHit.isEmpty &&
                  parts.cacheMiss.isEmpty && parts.unavailable.contains(reason) &&
                  parts.transport.isEmpty && parts.modelInput.isEmpty && parts.sidecar.isEmpty =>
              Some(ParserAttemptReceipt.Branch.RuntimeFailure)
            case _ => None
        case ParserAttemptReceipt.FailureKind.Transport =>
          parts.transport match
            case Some(reason)
                if call.exists(value => !value.cached) && parts.cacheHit.isEmpty &&
                  parts.unavailable.isEmpty && parts.modelInput.isEmpty && parts.sidecar.isEmpty &&
                  parts.cacheMissMatches(expectedCacheKey) &&
                  ParserFailure.fromTransport(reason) == failure =>
              Some(ParserAttemptReceipt.Branch.TransportFailure)
            case _ => None
        case ParserAttemptReceipt.FailureKind.ResponseUnobserved =>
          responseBranch(
            input,
            expectedCacheKey,
            parts,
            requireModelInput = false,
            allowSidecar = false
          )
        case ParserAttemptReceipt.FailureKind.ResponseObserved =>
          responseBranch(
            input,
            expectedCacheKey,
            parts,
            requireModelInput = true,
            allowSidecar = false
          )
        case ParserAttemptReceipt.FailureKind.ResponseInterop =>
          responseBranch(
            input,
            expectedCacheKey,
            parts,
            requireModelInput = true,
            allowSidecar = true
          )
        case ParserAttemptReceipt.FailureKind.ResponseAligned =>
          responseBranch(
            input,
            expectedCacheKey,
            parts,
            requireModelInput = true,
            allowSidecar = true
          ).filter(_ => parts.sidecar.nonEmpty)
        case ParserAttemptReceipt.FailureKind.Contract =>
          if call.isEmpty && parts.cacheHit.isEmpty && parts.unavailable.isEmpty &&
            parts.transport.isEmpty && parts.modelInput.isEmpty && parts.sidecar.isEmpty &&
            parts.cacheMissMatches(expectedCacheKey)
          then Some(ParserAttemptReceipt.Branch.ContractFailure)
          else None
        case ParserAttemptReceipt.FailureKind.Cache =>
          if call.nonEmpty || parts.unavailable.nonEmpty || parts.transport.nonEmpty ||
            parts.modelInput.nonEmpty || parts.sidecar.nonEmpty
          then None
          else
            (parts.cacheHit, parts.cacheMiss, expectedCacheKey) match
              case (Some(_), None, Some(_)) => Some(ParserAttemptReceipt.Branch.CacheFailure)
              case (None, Some(actual), Some(expected)) if actual == expected =>
                Some(ParserAttemptReceipt.Branch.CacheFailure)
              case _ => None
        case ParserAttemptReceipt.FailureKind.Unsupported => None

  private def responseBranch(
      input: ParserSentenceInput,
      expectedCacheKey: Option[ParserCacheKey],
      parts: ParserAttemptReceipt.DecisionParts,
      requireModelInput: Boolean,
      allowSidecar: Boolean
  ): Option[ParserAttemptReceipt.Branch] =
    call match
      case Some(fresh)
          if !fresh.cached && parts.cacheHit.isEmpty && parts.unavailable.isEmpty &&
            parts.transport.isEmpty && parts.cacheMissMatches(expectedCacheKey) &&
            parts.modelInput.nonEmpty == requireModelInput &&
            (allowSidecar || parts.sidecar.isEmpty) && parts.observationsAdmit(input, fresh) =>
        Some(ParserAttemptReceipt.Branch.ResponseFailure)
      case _ => None

  override def equals(other: Any): Boolean = other match
    case that: ParserAttemptReceipt =>
      requestId == that.requestId && requestChecksum == that.requestChecksum &&
      call == that.call && decisions == that.decisions && digest == that.digest
    case _ => false

  override def hashCode(): Int =
    (requestId, requestChecksum, call, decisions, digest).hashCode

  override def toString: String =
    s"ParserAttemptReceipt(${requestId.value}, call=${call.nonEmpty}, " +
      s"decisions=${decisions.size}, digest=${digest.short()})"

object ParserAttemptReceipt:
  private[parser] enum Branch:
    case FreshSuccess, CachedSuccess, RuntimeFailure, TransportFailure, ResponseFailure
    case ContractFailure, CacheFailure

  private enum FailureKind:
    case Runtime, Transport, ResponseUnobserved, ResponseObserved, ResponseInterop
    case ResponseAligned, Contract, Cache, Unsupported

  private final case class DecisionParts(
      cacheHit: Option[CachedParserProposal],
      cacheMiss: Option[ParserCacheKey],
      unavailable: Option[ParserSetupFailure],
      transport: Option[TransportFailure],
      modelInput: Option[ModelInputObservation],
      sidecar: Option[AcceptedAlignmentSidecar],
      rejected: Option[ParserFailure]
  ):
    def cacheMissMatches(expected: Option[ParserCacheKey]): Boolean =
      (cacheMiss, expected) match
        case (None, None)                   => true
        case (Some(actual), Some(required)) => actual == required
        case _                              => false

    def observationsAdmit(input: ParserSentenceInput, call: ProviderCall): Boolean =
      modelInput.forall(ModelInputObservation.admits(_, input, call)) &&
        sidecar.forall(AcceptedAlignmentSidecar.admits(_, input, call))

  private object DecisionParts:
    def from(decisions: Vector[ParserAttemptDecision]): Option[DecisionParts] =
      def one[A](values: Vector[A]): Option[Option[A]] =
        if values.size <= 1 then Some(values.headOption) else None

      for
        cacheHit <- one(decisions.collect { case ParserAttemptDecision.CacheHit(value) => value })
        cacheMiss <- one(decisions.collect { case ParserAttemptDecision.CacheMiss(value) => value })
        unavailable <- one(decisions.collect {
          case ParserAttemptDecision.RuntimeUnavailable(value) => value
        })
        transport <- one(decisions.collect { case ParserAttemptDecision.TransportFailed(value) =>
          value
        })
        modelInput <- one(decisions.collect {
          case ParserAttemptDecision.ModelInputObserved(value) => value
        })
        sidecar <- one(decisions.collect {
          case ParserAttemptDecision.AlignmentSidecarAccepted(value) => value
        })
        rejected <- one(decisions.collect { case ParserAttemptDecision.ResultRejected(value) =>
          value
        })
      yield DecisionParts(
        cacheHit,
        cacheMiss,
        unavailable,
        transport,
        modelInput,
        sidecar,
        rejected
      )

  private def failureKind(failure: ParserFailure): FailureKind = failure match
    case ParserFailure.RuntimeUnavailable(_) => FailureKind.Runtime
    case ParserFailure.Timeout(_) | ParserFailure.NonZeroExit(_, _) |
        ParserFailure.MaterializationFailed(_) | ParserFailure.TransportIo(_) =>
      FailureKind.Transport
    case ParserFailure.ProviderContractViolation(_) => FailureKind.Contract
    case ParserFailure.CacheCorrupt(_)              => FailureKind.Cache
    case ParserFailure.Nondeterministic(_, _)       => FailureKind.Unsupported
    case ParserFailure.MalformedEnvelope(_) | ParserFailure.WrongSchema(_) |
        ParserFailure.RuntimeFingerprintMismatch(_, _) |
        ParserFailure.ConfigChecksumMismatch(_, _) | ParserFailure.MissingOutput(_) |
        ParserFailure.DuplicateOutput(_, _) =>
      FailureKind.ResponseUnobserved
    case ParserFailure.TokenEchoMismatch(_) | ParserFailure.ModelInputMismatch |
        ParserFailure.ProviderFailed(_) | ParserFailure.ProviderAbstained(_) |
        ParserFailure.AlignmentSidecarSchemaMismatch(_) |
        ParserFailure.AlignmentDialectUnsupported(_) | ParserFailure.AlignmentSidecarInvalid(_) =>
      FailureKind.ResponseObserved
    case ParserFailure.InteropRejected(_, _) => FailureKind.ResponseInterop
    case ParserFailure.AlignmentMissing | ParserFailure.AlignmentEscapesSentence |
        ParserFailure.AlignmentNotAtlasToken | ParserFailure.ProvenanceMissing =>
      FailureKind.ResponseAligned

  /** Build a receipt whose digest covers the request, provider call, and every decision. */
  def of(
      input: ParserSentenceInput,
      call: Option[ProviderCall],
      decisions: Vector[ParserAttemptDecision]
  ): ParserAttemptReceipt =
    val material =
      Vector(input.id.value, input.checksum.hex) ++
        call.fold(Vector("call", "none"))(value => Vector("call", "some") ++ renderCall(value)) ++
        decisions.zipWithIndex.flatMap { (decision, index) =>
          Vector("decision", index.toString) ++ decision.canonicalParts
        }
    new ParserAttemptReceipt(
      input.id,
      input.checksum,
      call,
      decisions,
      ParserIdentity.digest("parser-attempt/v2", material)
    )

  private def renderCall(call: ProviderCall): Vector[String] =
    Vector(
      "call",
      call.provider,
      call.model,
      call.version,
      call.promptTemplateVersion.fold("none")(_ => "some"),
      call.promptTemplateVersion.fold("")(_.value),
      call.inputChecksum.hex,
      call.outputChecksum.hex,
      call.seed.fold("none")(_ => "some"),
      call.seed.fold("")(_.toString),
      call.cached.toString
    ) ++ call.params.toVector.sortBy(_._1).zipWithIndex.flatMap { case ((key, value), index) =>
      Vector("param", index.toString, key, value)
    }

  private[parser] def callDigest(call: ProviderCall): Checksum =
    ParserIdentity.digest("parser-provider-call/v1", renderCall(call))

/** Checked proposition evidence plus its graph-and-alignment identity for replay comparison. */
final class ParserProposal private (
    val evidence: p.PropositionEvidence,
    val canonicalDigest: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: ParserProposal =>
      evidence == that.evidence && canonicalDigest == that.canonicalDigest
    case _ => false

  override def hashCode(): Int = (evidence, canonicalDigest).hashCode
  override def toString: String = s"ParserProposal(${canonicalDigest.short()})"

object ParserProposal:
  /** Bind canonical chart structure to its exact source-alignment targets and spans. */
  def of(evidence: p.PropositionEvidence): ParserProposal =
    val chart = p.Canonical.form(evidence.chart)
    val alignments = chart.alignments.map(renderAlignment(chart, _)).sorted
    val digest = ParserIdentity.digest(
      "parser-proposal/v2",
      Vector(p.Canonical.serialization(chart), chart.sentence.fold("none")(_ => "some")) ++
        chart.sentence.toVector.map(_.value) ++ alignments
    )
    new ParserProposal(evidence, digest)

  private def renderAlignment(
      chart: p.PropositionChart[p.Checked],
      alignment: p.PropositionAlignment
  ): String =
    val targetParts = alignment.target match
      case p.AlignmentTarget.Concepts(ids) =>
        val values = ids.toSortedSet.toVector.map(_.value)
        Vector("concepts", values.size.toString) ++ values
      case p.AlignmentTarget.Relation(relation) =>
        Vector("relation", chart.relations.indexOf(relation).toString)
    val spans = alignment.spans.refs.toVector.zipWithIndex.flatMap { (ref, index) =>
      Vector(
        "span",
        index.toString,
        ref.unit.fold("none")(_ => "some"),
        ref.unit.fold("")(_.value),
        ref.span.start.toString,
        ref.span.endExclusive.toString
      )
    }
    ParserIdentity.orderKey(targetParts ++ spans)

/** One and only one normalized outcome for a parser request. */
final class ParserAttempt private (
    val id: ParserRequestId,
    val result: Either[ParserFailure, ParserProposal],
    val receipt: ParserAttemptReceipt
):
  def isCovered: Boolean = result.isRight

  override def equals(other: Any): Boolean = other match
    case that: ParserAttempt =>
      id == that.id && result == that.result && receipt == that.receipt
    case _ => false

  override def hashCode(): Int = (id, result, receipt).hashCode
  override def toString: String = s"ParserAttempt(${id.value}, covered=$isCovered)"

object ParserAttempt:
  /** Validate receipt identity and, for success, provenance plus exact atlas alignment. */
  def from(
      input: ParserSentenceInput,
      result: Either[ParserFailure, ParserProposal],
      receipt: ParserAttemptReceipt,
      expectedCacheKey: Option[ParserCacheKey] = None
  ): Either[ParserFailure, ParserAttempt] =
    if receipt.requestId != input.id || receipt.requestChecksum != input.checksum then
      Left(ParserFailure.ProvenanceMissing)
    else
      result match
        case Left(_) =>
          if receipt.branch(input, result, expectedCacheKey).nonEmpty then
            Right(new ParserAttempt(input.id, result, receipt))
          else Left(ParserFailure.ProvenanceMissing)
        case Right(proposal) =>
          if receipt.branch(input, result, expectedCacheKey).isEmpty then
            Left(ParserFailure.ProvenanceMissing)
          else
            ParserAdmission
              .validate(input, proposal.evidence)
              .map(_ => new ParserAttempt(input.id, result, receipt))

  /** Admit success only with a fresh provider call or a cache witness bound to this proposal. */
  def proposed(
      input: ParserSentenceInput,
      proposal: ParserProposal,
      receipt: ParserAttemptReceipt,
      expectedCacheKey: Option[ParserCacheKey] = None
  ): Either[ParserFailure, ParserAttempt] =
    from(input, Right(proposal), receipt, expectedCacheKey)

  /** Record a typed absence or fail closed to an internally generated contract violation. */
  private[parser] def failed(
      input: ParserSentenceInput,
      failure: ParserFailure,
      receipt: ParserAttemptReceipt,
      expectedCacheKey: Option[ParserCacheKey] = None
  ): ParserAttempt =
    from(input, Left(failure), receipt, expectedCacheKey).fold(
      _ => contractViolation(input, receipt.digest, expectedCacheKey),
      identity
    )

  private def contractViolation(
      input: ParserSentenceInput,
      rejectedReceipt: Checksum,
      expectedCacheKey: Option[ParserCacheKey]
  ): ParserAttempt =
    val failure = ParserFailure.ProviderContractViolation(rejectedReceipt)
    val receipt = ParserAttemptReceipt.of(
      input,
      None,
      expectedCacheKey.toVector.map(ParserAttemptDecision.CacheMiss(_)) :+
        ParserAttemptDecision.ResultRejected(failure)
    )
    from(input, Left(failure), receipt, expectedCacheKey).fold(
      _ => new ParserAttempt(input.id, Left(failure), receipt),
      identity
    )

/** Why a supposedly normalized batch result does not match its request batch. */
enum ParserResultError:
  case CountMismatch(expected: Int, found: Int)
  case AttemptMismatch(index: Int, expected: ParserRequestId, found: ParserRequestId)
  case ExactInputMismatch(index: Int, expected: Checksum, found: Checksum)

/** A batch result whose attempts are in input order and contain no missing or extra identity. */
final class ParserBatchResult private (
    val attempts: Vector[ParserAttempt],
    val decisions: Vector[ParserBatchDecision]
):
  def covered: Int = attempts.count(_.isCovered)
  def total: Int = attempts.size
  def misses: Vector[(ParserRequestId, ParserFailure)] = attempts.flatMap { attempt =>
    attempt.result match
      case Left(failure) => Some(attempt.id -> failure)
      case Right(_)      => None
  }

  /** Verify the exactly-one outcome law against the originating batch. */
  def conforms(batch: ParserBatch): Either[ParserResultError, ParserBatchResult] =
    if attempts.size != batch.size then
      Left(ParserResultError.CountMismatch(batch.size, attempts.size))
    else
      attempts
        .zip(batch.inputs)
        .zipWithIndex
        .collectFirst {
          case ((attempt, input), index) if attempt.id != input.id =>
            ParserResultError.AttemptMismatch(index, input.id, attempt.id)
          case ((attempt, input), index) if attempt.receipt.requestChecksum != input.checksum =>
            ParserResultError.ExactInputMismatch(
              index,
              input.checksum,
              attempt.receipt.requestChecksum
            )
        }
        .toLeft(this)

  override def equals(other: Any): Boolean = other match
    case that: ParserBatchResult => attempts == that.attempts && decisions == that.decisions
    case _                       => false

  override def hashCode(): Int = (attempts, decisions).hashCode
  override def toString: String = s"ParserBatchResult(covered=$covered/$total)"

object ParserBatchResult:
  /** Validate order and cardinality before exposing a provider result. */
  def validated(
      batch: ParserBatch,
      attempts: Vector[ParserAttempt],
      decisions: Vector[ParserBatchDecision] = Vector.empty
  ): Either[ParserResultError, ParserBatchResult] =
    new ParserBatchResult(attempts, decisions).conforms(batch)

  private[parser] def unsafe(
      attempts: Vector[ParserAttempt],
      decisions: Vector[ParserBatchDecision] = Vector.empty
  ): ParserBatchResult = new ParserBatchResult(attempts, decisions)
