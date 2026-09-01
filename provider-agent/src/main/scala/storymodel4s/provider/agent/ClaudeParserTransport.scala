package storymodel4s.provider.agent

import cats.Id
import cats.syntax.all.*
import scala.util.Try
import storymodel4s.amr.graph.{Decoder as AmrDecoder}
import storymodel4s.core.Checksum
import storymodel4s.provider.parser.{
  ParserEnvelope,
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
            ItemOutcome.Proposed(text, SidecarDerivation.rows(decoded.markers))

/** `ParserTransport[Id]` over one model exchange: one model call per request item, each bounded by
  * the request's `timeoutMillis`, assembled into one result/v2 envelope.
  *
  * Why one call per item: the admission court judges sentences, and a batch-level model call would
  * let one sentence's reply contaminate another's. The driver additionally wraps the provider in
  * `SentenceIsolatingParserProvider`, so in practice one exchange carries one item.
  */
final class ClaudeParserTransport private (
    runtime: RemoteRuntime,
    prompt: AgentPromptPackage,
    model: ModelExchange,
    maxTokens: Long
) extends ParserTransport[Id]:
  def exchange(requestJson: String, timeoutMillis: Long): Either[TransportFailure, String] =
    AgentEnvelope.decodeRequest(requestJson) match
      case Left(_) => Left(TransportFailure.Io(AgentFailureCodes.RequestUndecodable))
      case Right(request) if request.schema != ParserEnvelope.RequestSchema =>
        Left(TransportFailure.Io(AgentFailureCodes.RequestSchemaMismatch))
      case Right(request) if request.runtimeFingerprint != runtime.fingerprint.value =>
        Left(TransportFailure.Io(AgentFailureCodes.RuntimeFingerprintMismatch))
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
      val request = ModelRequest.render(runtime.model, prompt, item, maxTokens, timeoutMillis)
      model
        .complete(request)
        .left
        .map(_.toTransport)
        .map(reply =>
          ItemResponse(item, ReplyInterpretation.interpret(reply)) -> reply.durationMillis
        )

object ClaudeParserTransport:
  val DefaultMaxTokens: Long = 4096L

  /** Build a transport bound to one remote runtime, one prompt package, and one exchange. */
  def apply(
      runtime: RemoteRuntime,
      prompt: AgentPromptPackage,
      model: ModelExchange,
      maxTokens: Long = DefaultMaxTokens
  ): ClaudeParserTransport = new ClaudeParserTransport(runtime, prompt, model, maxTokens)
