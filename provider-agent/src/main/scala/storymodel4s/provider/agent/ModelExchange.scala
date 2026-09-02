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
  * can never carry a key that describes a different sentence, budget, or template.
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
  /** Version of the user-message rendering below; it participates in every recording key. */
  val TemplateVersion: String = "storymodel4s.provider.agent.user-message/v1"

  /** Render the user message from the sentence and its token list, and derive the key.
    *
    * Why the whole backend and not a model id: the provider label and the model id are one identity
    * and must move together, so passing them separately would let a caller key an Anthropic request
    * under an OpenAI-compatible label.
    */
  private[agent] def render(
      backend: ModelBackend,
      prompt: AgentPromptPackage,
      item: RequestItem,
      maxTokens: Long,
      timeoutMillis: Long
  ): ModelRequest =
    val tokenLines = item.tokens.zipWithIndex.map { (token, index) => s"$index: ${token.text}" }
    val userMessage = (Vector(s"Sentence: ${item.text}", "Tokens:") ++ tokenLines).mkString("\n")
    val key = RecordingKey.of(
      backend,
      prompt.ref.checksum,
      prompt.promptTextChecksum,
      maxTokens,
      item.textChecksum,
      item.tokens
    )
    new ModelRequest(
      backend.model,
      prompt.systemPrompt,
      userMessage,
      maxTokens,
      timeoutMillis,
      key
    )

/** Why an exchange produced no reply; every case maps to one sanitized transport failure. */
enum ExchangeFailure:
  case RecordingMissing(key: RecordingKey)
  case RecordingCorrupt(key: RecordingKey, reasonChecksum: Checksum)
  case RecordingUnwritable(key: RecordingKey, reasonChecksum: Checksum)
  case RateLimited
  case ServiceError(status: Int)
  case ConnectionFailed(reasonChecksum: Checksum)
  case Timeout(limitMillis: Long)

  /** The provider answered with 2xx and a body this module could not read as one reply. Distinct
    * from a service error, because the call was made and may have been billed.
    */
  case ReplyUndecodable(reasonChecksum: Checksum)

  def code: ProviderFailureCode = this match
    case RecordingMissing(_)       => AgentFailureCodes.RecordingMissing
    case RecordingCorrupt(_, _)    => AgentFailureCodes.RecordingCorrupt
    case RecordingUnwritable(_, _) => AgentFailureCodes.RecordingUnwritable
    case RateLimited               => AgentFailureCodes.RateLimited
    case ServiceError(status)      => AgentFailureCodes.serviceError(status)
    case ConnectionFailed(_)       => AgentFailureCodes.ConnectionFailed
    case Timeout(_)                => AgentFailureCodes.ConnectionFailed
    case ReplyUndecodable(_)       => AgentFailureCodes.ReplyUndecodable

  /** A timeout keeps its limit; every other failure is an I/O code with no provider prose. */
  def toTransport: TransportFailure = this match
    case Timeout(limit) => TransportFailure.Timeout(limit)
    case other          => TransportFailure.Io(other.code)

/** Something that completes one rendered request. Three implementations exist and no more is
  * intended: `LiveModelClient` (the Anthropic SDK), `OpenAiCompatibleModelClient` (any
  * chat-completions server), and `ScriptedModelClient`, the offline stand-in that lets the record
  * path be exercised without spend.
  *
  * Why one method taking one value rather than a function parameter anywhere: a factory that
  * accepted `ModelRequest => ...` would let a caller supply behaviour the credential court never
  * admitted, and nothing in a receipt would say so.
  */
trait ModelClient:
  def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply]

object ModelClient:
  /** The live client an admitted authorization implies. The backend is decided by the environment
    * court that minted the authorization, so no call site chooses a provider.
    */
  def live(authorization: LiveAuthorization): ModelClient = authorization match
    case anthropic: LiveAuthorization.Anthropic => LiveModelClient.from(anthropic)
    case openAi: LiveAuthorization.OpenAiCompatible => OpenAiCompatibleModelClient.from(openAi)

/** An offline client answering from a fixed table by recording key; every unknown key gets the same
  * typed failure. It holds no credentials and cannot spend, by construction.
  */
final class ScriptedModelClient(replies: Map[RecordingKey, ModelReply], absent: ExchangeFailure)
    extends ModelClient:
  def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply] =
    replies.get(request.key).toRight(absent)

/** The single boundary through which model text enters. `Recorded` holds no client, so a replay
  * cannot spend by construction; the model id always comes from the request, never from the
  * exchange, so a receipt's model and the wire's model cannot diverge.
  */
sealed trait ModelExchange:
  def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply]

object ModelExchange:
  /** Every request goes to the client and nothing is written. */
  final class Live(client: ModelClient) extends ModelExchange:
    def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply] =
      client.complete(request)

  /** Every request is served from recordings; a missing key is a typed failure, never a call. */
  final class Recorded(recordings: Recordings) extends ModelExchange:
    def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply] =
      recordings.read(request.key, request.model)

  /** Serve an existing recording; otherwise call the client and record its reply. A corrupt
    * recording is refused without a call.
    */
  final class RecordingLive(client: ModelClient, recordings: Recordings) extends ModelExchange:
    def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply] =
      recordings.read(request.key, request.model) match
        case Right(reply)                              => Right(reply)
        case Left(ExchangeFailure.RecordingMissing(_)) =>
          client
            .complete(request)
            .flatMap(reply => recordings.write(request.key, reply).map(_ => reply))
        case Left(other) => Left(other)
