package storymodel4s.provider.agent

import storymodel4s.core.Checksum
import storymodel4s.provider.parser.{ProviderFailureCode, TransportFailure}

/** How the model stopped, typed so a refusal and a truncated reply are distinct outcomes. */
enum ModelStopReason:
  case EndTurn
  case MaxTokens
  case Refusal
  case Other(raw: String)

  def wire: String = this match
    case EndTurn    => "end_turn"
    case MaxTokens  => "max_tokens"
    case Refusal    => "refusal"
    case Other(raw) => raw

object ModelStopReason:
  def fromWire(raw: String): ModelStopReason = raw match
    case "end_turn"   => EndTurn
    case "max_tokens" => MaxTokens
    case "refusal"    => Refusal
    case other        => Other(other)

/** Token accounting the provider reported; the cache-read count is absent when not reported. */
final case class ModelUsage(
    inputTokens: Long,
    outputTokens: Long,
    cacheReadInputTokens: Option[Long]
)

/** What stands behind a reply. A captured reply carries the provider's own accounting and the model
  * id it reported; an authored reply (hand-written evidence) carries none, so an invented number
  * can never masquerade as a measured one.
  */
enum ReplyEvidence:
  case Captured(reportedModel: Option[String], usage: ModelUsage, durationMillis: Long)
  case Authored

  def wire: String = this match
    case Captured(_, _, _) => "captured"
    case Authored          => "authored"

/** The raw reply to one request: the requested model, the verbatim text, the stop reason, and the
  * evidence behind it. Honest product data: every combination is a lawful reply.
  */
final case class ModelReply(
    model: String,
    text: String,
    stopReason: ModelStopReason,
    evidence: ReplyEvidence
)

/** One rendered model request. The recording key is derived together with the message so a request
  * can never carry a key that describes a different sentence.
  */
final class ModelRequest private (
    val model: String,
    val systemPrompt: String,
    val userMessage: String,
    val maxTokens: Long,
    val timeoutMillis: Long,
    val key: RecordingKey
):
  override def toString: String =
    s"ModelRequest($model, key=${key.checksum.short()}, timeoutMillis=$timeoutMillis)"

object ModelRequest:
  /** Render the user message from the sentence and its token list, and derive the key. */
  private[agent] def render(
      model: String,
      prompt: AgentPromptPackage,
      item: RequestItem,
      maxTokens: Long,
      timeoutMillis: Long
  ): ModelRequest =
    val tokenLines = item.tokens.zipWithIndex.map { (token, index) => s"$index: ${token.text}" }
    val userMessage = (Vector(s"Sentence: ${item.text}", "Tokens:") ++ tokenLines).mkString("\n")
    val key = RecordingKey.of(
      model,
      prompt.ref.checksum,
      prompt.promptTextChecksum,
      item.textChecksum,
      item.tokens
    )
    new ModelRequest(model, prompt.systemPrompt, userMessage, maxTokens, timeoutMillis, key)

/** Why an exchange produced no reply; every case maps to one sanitized transport failure. */
enum ExchangeFailure:
  case RecordingMissing(key: RecordingKey)
  case RecordingCorrupt(key: RecordingKey, reasonChecksum: Checksum)
  case RecordingUnwritable(key: RecordingKey, reasonChecksum: Checksum)
  case RateLimited
  case ServiceError(status: Int)
  case ConnectionFailed(reasonChecksum: Checksum)
  case Timeout(limitMillis: Long)

  def code: ProviderFailureCode = this match
    case RecordingMissing(_)       => AgentFailureCodes.RecordingMissing
    case RecordingCorrupt(_, _)    => AgentFailureCodes.RecordingCorrupt
    case RecordingUnwritable(_, _) => AgentFailureCodes.RecordingUnwritable
    case RateLimited               => AgentFailureCodes.RateLimited
    case ServiceError(status)      => AgentFailureCodes.serviceError(status)
    case ConnectionFailed(_)       => AgentFailureCodes.ConnectionFailed
    case Timeout(_)                => AgentFailureCodes.ConnectionFailed

  /** A timeout keeps its limit; every other failure is an I/O code with no provider prose. */
  def toTransport: TransportFailure = this match
    case Timeout(limit) => TransportFailure.Timeout(limit)
    case other          => TransportFailure.Io(other.code)

/** The single boundary through which model text enters. `Recorded` holds no client, so a replay
  * cannot spend by construction; the model id always comes from the request, never from the
  * exchange, so a receipt's model and the wire's model cannot diverge.
  */
sealed trait ModelExchange:
  def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply]

object ModelExchange:
  /** Every request goes to the provider and nothing is written. */
  final class Live(client: LiveModelClient) extends ModelExchange:
    def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply] =
      client.complete(request)

  /** Every request is served from recordings; a missing key is a typed failure, never a call. */
  final class Recorded(recordings: Recordings) extends ModelExchange:
    def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply] =
      recordings.read(request.key, request.model)

  /** Serve an existing recording; otherwise call the provider and record its reply. */
  final class RecordingLive(client: LiveModelClient, recordings: Recordings) extends ModelExchange:
    def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply] =
      recordings.read(request.key, request.model) match
        case Right(reply)                              => Right(reply)
        case Left(ExchangeFailure.RecordingMissing(_)) =>
          client
            .complete(request)
            .flatMap(reply => recordings.write(request.key, reply).map(_ => reply))
        case Left(other) => Left(other)
