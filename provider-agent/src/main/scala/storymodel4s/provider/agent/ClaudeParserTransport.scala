package storymodel4s.provider.agent

import cats.Id
import cats.syntax.all.*
import scala.util.Try
import storymodel4s.amr.graph.{Decoder as AmrDecoder}
import storymodel4s.core.Checksum
import storymodel4s.provider.parser.{
  ParserEnvelope,
  ParserSetupFailure,
  ParserTransport,
  RemoteRuntime,
  TransportFailure
}

/** Turn one model reply into the item outcome the result schema can carry. */
private[agent] object ReplyInterpretation:
  private val AbstainLine = "ABSTAIN"
  private val Fence = "```"

  /** Trim and drop one surrounding code fence; the recorded text itself is never rewritten. */
  def normalize(text: String): String =
    val trimmed = text.trim
    val withoutOpening =
      if trimmed.startsWith(Fence) then
        val newline = trimmed.indexOf('\n')
        if newline < 0 then "" else trimmed.substring(newline + 1)
      else trimmed
    val withoutClosing =
      if withoutOpening.trim.endsWith(Fence) then
        val body = withoutOpening.trim
        body.substring(0, body.length - Fence.length)
      else withoutOpening
    withoutClosing.trim

  def interpret(reply: ModelReply): ItemOutcome = reply.stopReason match
    case ModelStopReason.Refusal   => ItemOutcome.Abstained(AgentFailureCodes.Refusal)
    case ModelStopReason.MaxTokens => ItemOutcome.Failed(AgentFailureCodes.MaxTokens)
    case ModelStopReason.Other(_)  => ItemOutcome.Failed(AgentFailureCodes.UnexpectedStop)
    case ModelStopReason.EndTurn   =>
      val text = normalize(reply.text)
      if text == AbstainLine then ItemOutcome.Abstained(AgentFailureCodes.Abstain)
      else
        Try(AmrDecoder.fromPenman(text)).toEither.left.map(_ => "decoder threw").flatten match
          case Left(_)        => ItemOutcome.Failed(AgentFailureCodes.PenmanUnparsable)
          case Right(decoded) =>
            SidecarDerivation.derive(decoded.markers) match
              case Left(SidecarRefusal.MarkerNotOnConcept(_, _)) =>
                ItemOutcome.Failed(AgentFailureCodes.MarkerNotOnConcept)
              case Right(rows) => ItemOutcome.Proposed(text, rows)

  /** Only captured replies carry a measured duration; authored evidence contributes nothing. */
  def measuredDuration(reply: ModelReply): Long = reply.evidence match
    case ReplyEvidence.Captured(_, _, durationMillis) => durationMillis
    case ReplyEvidence.Authored                       => 0L

/** Why a transport could not be built. */
enum TransportSetupError:
  case RuntimeRefused(failure: ParserSetupFailure)
  case MaxTokensNotPositive(maxTokens: Long)

  def message: String = this match
    case RuntimeRefused(failure)         => failure.message
    case MaxTokensNotPositive(maxTokens) => s"max tokens must be positive, got $maxTokens"

/** `ParserTransport[Id]` over one model exchange: one model call per request item, each bounded by
  * the request's `timeoutMillis`, assembled into one result/v2 envelope.
  *
  * Why the runtime is derived here and not passed in: the receipt's prompt-template version and
  * fingerprint must describe the prompt actually sent, so both come from one `AgentPromptPackage`.
  * The request's config params must name the same prompt and token budget, or the exchange is
  * refused; a receipt therefore cannot claim a prompt this transport did not use.
  *
  * Why one call per item: the admission court judges sentences, and a batch-level model call would
  * let one sentence's reply contaminate another's. The driver additionally wraps the provider in
  * `SentenceIsolatingParserProvider`, so in practice one exchange carries one item.
  */
final class ClaudeParserTransport private (
    val runtime: RemoteRuntime,
    prompt: AgentPromptPackage,
    model: ModelExchange,
    maxTokens: Long,
    backend: ModelBackend
) extends ParserTransport[Id]:
  private val requiredParams = ClaudeParserTransport.configParams(prompt, maxTokens)

  def exchange(requestJson: String, timeoutMillis: Long): Either[TransportFailure, String] =
    AgentEnvelope.decodeRequest(requestJson) match
      case Left(_) => Left(TransportFailure.Io(AgentFailureCodes.RequestUndecodable))
      case Right(request) if request.schema != ParserEnvelope.RequestSchema =>
        Left(TransportFailure.Io(AgentFailureCodes.RequestSchemaMismatch))
      case Right(request) if request.runtimeFingerprint != runtime.fingerprint.value =>
        Left(TransportFailure.Io(AgentFailureCodes.RuntimeFingerprintMismatch))
      case Right(request) if !requiredParams.forall(request.params.contains) =>
        Left(TransportFailure.Io(AgentFailureCodes.ConfigMismatch))
      case Right(request) =>
        request.items.traverse(item => itemResponse(item, timeoutMillis)).map { responses =>
          AgentEnvelope.encodeResponse(
            request.runtimeFingerprint,
            request.configChecksum,
            responses.map(_._1),
            responses.map(_._2).sum
          )
        }

  private def itemResponse(
      item: RequestItem,
      timeoutMillis: Long
  ): Either[TransportFailure, (ItemResponse, Long)] =
    if Checksum.ofText(item.text) != item.textChecksum then
      Left(TransportFailure.Io(AgentFailureCodes.TextChecksumMismatch))
    else
      val request = ModelRequest.render(backend, prompt, item, maxTokens, timeoutMillis)
      model
        .complete(request)
        .left
        .map(_.toTransport)
        .map(reply =>
          ItemResponse(item, ReplyInterpretation.interpret(reply)) ->
            ReplyInterpretation.measuredDuration(reply)
        )

object ClaudeParserTransport:
  val DefaultMaxTokens: Long = 4096L

  /** The backend a run takes when the environment names none: the hosted Anthropic model. */
  val DefaultBackend: ModelBackend = ModelBackend.default

  val Provider: String = DefaultBackend.provider
  val Model: String = DefaultBackend.model

  /** The wrapper version a receipt records, taken from the build's single pinned SDK version. */
  val SdkVersion: String = DefaultBackend.sdkVersion

  /** The remote runtime one prompt package and one backend imply; the only way to name this
    * adapter's identity. Every scalar comes from the backend the environment court admitted, so a
    * receipt naming `openai-compatible:127.0.0.1:11434` was produced by a run configured that way.
    */
  def runtimeFor(
      prompt: AgentPromptPackage,
      backend: ModelBackend = DefaultBackend
  ): Either[ParserSetupFailure, RemoteRuntime] =
    RemoteRuntime.from(
      backend.provider,
      backend.model,
      backend.sdkVersion,
      prompt.ref,
      prompt.promptTextChecksum,
      ParserEnvelope.ResultSchema
    )

  /** The parser-config params a request must carry so its identity names this prompt. */
  def configParams(prompt: AgentPromptPackage, maxTokens: Long): Map[String, String] =
    Map(
      "prompt-package" -> prompt.ref.checksum.hex,
      "prompt-text" -> prompt.promptTextChecksum.hex,
      "max-tokens" -> maxTokens.toString
    )

  /** Build a transport whose runtime identity is derived from the prompt it will send and the
    * backend that will send it. The backend also keys the recordings, so one value decides both
    * and they cannot disagree.
    */
  def from(
      prompt: AgentPromptPackage,
      model: ModelExchange,
      maxTokens: Long = DefaultMaxTokens,
      backend: ModelBackend = DefaultBackend
  ): Either[TransportSetupError, ClaudeParserTransport] =
    if maxTokens <= 0L then Left(TransportSetupError.MaxTokensNotPositive(maxTokens))
    else
      runtimeFor(prompt, backend).left
        .map(TransportSetupError.RuntimeRefused(_))
        .map(runtime => new ClaudeParserTransport(runtime, prompt, model, maxTokens, backend))
