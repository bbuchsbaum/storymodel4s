package storymodel4s.provider.agent

import io.circe.{ACursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.annotation.tailrec
import scala.util.control.NonFatal
import storymodel4s.core.Checksum

/** The second model backend: any server speaking the OpenAI chat-completions shape (OpenRouter,
  * Ollama, vLLM, LM Studio), reached with the JDK's own HTTP client and circe.
  *
  * Why no SDK: the request this module sends is one POST with four fields and the reply is read
  * from three, so an added dependency would buy typed errors this file already produces and a retry
  * policy this file already states. ADR 0008 (2026-09-02 amendment) records the choice.
  *
  * What is sent, and nothing else: the model id from the request, the token budget, and exactly two
  * messages (the cached-by-Anthropic system prompt as `system`, the rendered sentence as `user`).
  * No temperature, top_p, seed, or streaming flag, because a sampling knob nobody set is a
  * difference between two runs that no receipt would record.
  *
  * Retries: at most two, and only on 429 or 5xx, with a 200 ms then 400 ms backoff, so one reply
  * (and one receipt) may stand behind up to three HTTP attempts, each bounded independently by the
  * request's `timeoutMillis`. A timeout is never retried: the budget the caller set is for the
  * exchange, not per attempt after a stall.
  *
  * The key: held opaque, written only into the `Authorization` header, and absent from every value
  * this file can produce. Failures carry a status code or a checksum of the exception's class and
  * message, never a body, a URL, or a header.
  */
final class OpenAiCompatibleModelClient private (
    http: HttpClient,
    chatCompletions: URI,
    apiKey: Option[ApiKey]
) extends ModelClient:
  import OpenAiCompatibleModelClient.*

  def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply] =
    val body = requestBody(request)
    val started = System.nanoTime()
    send(body, request.timeoutMillis, attempt = 0).flatMap { payload =>
      val elapsed = (System.nanoTime() - started) / 1000000L
      decodeReply(request.model, payload, elapsed)
    }

  @tailrec
  private def send(
      body: String,
      timeoutMillis: Long,
      attempt: Int
  ): Either[ExchangeFailure, String] =
    attemptOnce(body, timeoutMillis) match
      case Right(payload)                                              => Right(payload)
      case Left(failure) if attempt < MaxRetries && retryable(failure) =>
        Thread.sleep(BackoffMillis(attempt))
        send(body, timeoutMillis, attempt + 1)
      case Left(failure) => Left(failure)

  private def attemptOnce(body: String, timeoutMillis: Long): Either[ExchangeFailure, String] =
    val base = HttpRequest
      .newBuilder(chatCompletions)
      .timeout(Duration.ofMillis(timeoutMillis))
      .header("content-type", "application/json")
      .header("accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    val prepared =
      apiKey.fold(base)(key => base.header("authorization", s"Bearer ${key.secret}")).build()
    try
      val response = http.send(prepared, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      val status = response.statusCode()
      if status >= 200 && status < 300 then Right(response.body())
      else if status == TooManyRequests then Left(ExchangeFailure.RateLimited)
      else Left(ExchangeFailure.ServiceError(status))
    catch
      case _: HttpTimeoutException            => Left(ExchangeFailure.Timeout(timeoutMillis))
      case _: java.net.SocketTimeoutException => Left(ExchangeFailure.Timeout(timeoutMillis))
      case error: InterruptedException        =>
        Thread.currentThread().interrupt()
        Left(ExchangeFailure.ConnectionFailed(Checksum.ofText(describe(error))))
      case NonFatal(error) =>
        Left(ExchangeFailure.ConnectionFailed(Checksum.ofText(describe(error))))

object OpenAiCompatibleModelClient:
  /** How many extra attempts a retryable status buys; three attempts in total. */
  val MaxRetries: Int = 2

  /** The wait before each retry, in order; short enough that a stuck server still fails fast. */
  val BackoffMillis: Vector[Long] = Vector(200L, 400L)

  /** How long a connection may take to establish, independent of the per-request read budget. */
  val ConnectTimeoutMillis: Long = 10000L

  private val TooManyRequests: Int = 429

  /** A client exists only behind an admitted authorization; the key never leaves it.
    *
    * HTTP/1.1 is forced and redirects are never followed: every server in scope speaks 1.1, and a
    * followed redirect would repeat the `Authorization` header to a host the court never admitted.
    */
  def from(authorization: LiveAuthorization.OpenAiCompatible): OpenAiCompatibleModelClient =
    new OpenAiCompatibleModelClient(
      HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofMillis(ConnectTimeoutMillis))
        .build(),
      authorization.chatCompletions,
      authorization.apiKey
    )

  /** Only a rate limit or a server fault is worth repeating; a 4xx will fail the same way twice. */
  private[agent] def retryable(failure: ExchangeFailure): Boolean = failure match
    case ExchangeFailure.RateLimited          => true
    case ExchangeFailure.ServiceError(status) => status >= 500
    case _                                    => false

  /** The exact body sent: model, budget, and the two messages. Nothing else, ever. */
  private[agent] def requestBody(request: ModelRequest): String =
    Json
      .obj(
        "model" -> request.model.asJson,
        "max_tokens" -> request.maxTokens.asJson,
        "messages" -> Json.arr(
          Json.obj("role" -> "system".asJson, "content" -> request.systemPrompt.asJson),
          Json.obj("role" -> "user".asJson, "content" -> request.userMessage.asJson)
        )
      )
      .noSpaces

  /** `finish_reason` in the vocabulary this module already has; an unknown value is kept verbatim
    * as [[ModelStopReason.Other]], which the transport answers with `unexpected-stop-reason`, so a
    * reason nobody anticipated can never be read as a clean stop.
    */
  private[agent] def stopReasonOf(finishReason: String): ModelStopReason = finishReason match
    case "stop"           => ModelStopReason.EndTurn
    case "length"         => ModelStopReason.MaxTokens
    case "content_filter" => ModelStopReason.Refusal
    case other            => ModelStopReason.Other(other)

  /** Read one chat-completions reply; every shortfall is one typed failure carrying a checksum of
    * the reason, so a malformed body never reaches a receipt and never enters a log.
    */
  private[agent] def decodeReply(
      model: String,
      payload: String,
      durationMillis: Long
  ): Either[ExchangeFailure, ModelReply] =
    decoded(model, payload, durationMillis).left.map(reason =>
      ExchangeFailure.ReplyUndecodable(Checksum.ofText(reason))
    )

  private def decoded(
      model: String,
      payload: String,
      durationMillis: Long
  ): Either[String, ModelReply] =
    for
      json <- parse(payload).left.map(_ => "the reply is not JSON")
      cursor = json.hcursor
      choices <- cursor.downField("choices").as[Vector[Json]].left.map(_ => "no choices array")
      first <- choices.headOption.toRight("the choices array is empty")
      content <- contentOf(first.hcursor.downField("message"))
      finish <- first.hcursor
        .downField("finish_reason")
        .as[String]
        .left
        .map(_ => "no finish_reason")
      usage <- usageOf(cursor.downField("usage"))
    yield ModelReply(
      model,
      content,
      stopReasonOf(finish),
      ReplyEvidence.Captured(
        cursor.downField("model").as[String].toOption,
        usage,
        durationMillis
      )
    )

  /** A refused reply legitimately carries `content: null`, and losing that would lose the refusal;
    * an absent or non-string content is refused, because empty text is a claim about the model.
    */
  private def contentOf(message: ACursor): Either[String, String] =
    message.downField("content").focus match
      case Some(value) if value.isNull => Right("")
      case Some(value)                 => value.asString.toRight("message content is not a string")
      case None                        => Left("no message content")

  /** Usage is required, not defaulted: a server that reported no accounting must not become a
    * recording whose zeros are indistinguishable from a measured zero. Cache reads are absent
    * rather than zero for the same reason -- this wire shape does not report them.
    */
  private def usageOf(usage: ACursor): Either[String, ModelUsage] =
    for
      input <- usage.downField("prompt_tokens").as[Long].left.map(_ => "no usage.prompt_tokens")
      output <- usage
        .downField("completion_tokens")
        .as[Long]
        .left
        .map(_ => "no usage.completion_tokens")
      _ <- Either.cond(input >= 0L && output >= 0L, (), "negative token usage")
    yield ModelUsage(input, output, None)

  private def describe(error: Throwable): String =
    s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
