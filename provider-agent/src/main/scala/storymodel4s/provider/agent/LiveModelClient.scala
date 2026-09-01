package storymodel4s.provider.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.RequestOptions
import com.anthropic.errors.{AnthropicIoException, AnthropicServiceException, RateLimitException}
import com.anthropic.models.messages.{CacheControlEphemeral, MessageCreateParams, TextBlockParam}
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import storymodel4s.core.Checksum

/** The only file that touches the Anthropic SDK. It sends exactly what a `ModelRequest` carries:
  * the model id from the request, a cached system prompt, one user message, and a per-call timeout.
  * No sampling parameters and no thinking configuration are sent.
  */
final class LiveModelClient private (client: AnthropicClient):
  def complete(request: ModelRequest): Either[ExchangeFailure, ModelReply] =
    val params = MessageCreateParams
      .builder()
      .model(request.model)
      .maxTokens(request.maxTokens)
      .systemOfTextBlockParams(
        java.util.List.of(
          TextBlockParam
            .builder()
            .text(request.systemPrompt)
            .cacheControl(CacheControlEphemeral.builder().build())
            .build()
        )
      )
      .addUserMessage(request.userMessage)
      .build()
    val options = RequestOptions
      .builder()
      .timeout(Duration.ofMillis(request.timeoutMillis))
      .build()
    val started = System.nanoTime()
    try
      val message = client.messages().create(params, options)
      val elapsed = (System.nanoTime() - started) / 1000000L
      val text = message.content().asScala.flatMap(block => block.text().toScala).map(_.text())
      val stop = message.stopReason().toScala.map(_.toString).getOrElse("")
      val usage = message.usage()
      Right(
        ModelReply(
          model = message.model().toString,
          text = text.mkString,
          stopReason = ModelStopReason.fromWire(stop),
          usage = ModelUsage(
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheReadInputTokens().toScala.map(Long.unbox).getOrElse(0L)
          ),
          durationMillis = elapsed
        )
      )
    catch
      case _: RateLimitException => Left(ExchangeFailure.RateLimited)
      case error: AnthropicServiceException =>
        Left(ExchangeFailure.ServiceError(error.statusCode()))
      case error: AnthropicIoException =>
        val timedOut = Option(error.getCause).exists {
          case _: java.net.SocketTimeoutException => true
          case _: java.util.concurrent.TimeoutException => true
          case _                                  => false
        }
        if timedOut then Left(ExchangeFailure.Timeout(request.timeoutMillis))
        else Left(ExchangeFailure.ConnectionFailed(Checksum.ofText(describe(error))))

  private def describe(error: Throwable): String =
    s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"

object LiveModelClient:
  /** A client exists only behind an admitted live authorization; the key never leaves it. */
  def from(authorization: LiveAuthorization): LiveModelClient =
    new LiveModelClient(
      AnthropicOkHttpClient.builder().apiKey(authorization.apiKey.secret).build()
    )
