package storymodel4s.provider.agent

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import storymodel4s.provider.parser.*

/** The OpenAI-compatible backend, exercised against a `com.sun.net.httpserver` on an ephemeral
  * loopback port. Every reply is canned, so each expectation below is a literal; nothing here
  * reaches a network beyond 127.0.0.1 and nothing here can spend.
  */
class OpenAiExchangeSuite extends FunSuite:
  import OpenAiExchangeSuite.*

  private def requestFor(baseUrl: String, timeoutMillis: Long = 5000L): ModelRequest =
    ModelRequest.render(
      backendFor(baseUrl),
      AgentFixtures.prompt,
      AgentFixtures.requestItem(AgentFixtures.inputs.head),
      MaxTokens,
      timeoutMillis
    )

  private def backendFor(baseUrl: String): ModelBackend =
    AgentCredentials
      .backend(envFor(baseUrl, None))
      .fold(refusal => fail(refusal.message), identity)

  private def clientFor(baseUrl: String, key: Option[String]): ModelClient =
    LiveAuthorization
      .from(envFor(baseUrl, key))
      .fold(refusal => fail(refusal.message), ModelClient.live)

  private def completed(baseUrl: String, key: Option[String]): Either[ExchangeFailure, ModelReply] =
    clientFor(baseUrl, key).complete(requestFor(baseUrl))

  test("the happy path sends one POST carrying the model, the budget, and exactly two messages") {
    withServer(Vector(Ok -> completion("(x / example~e.0)", "stop"))) { (baseUrl, seen) =>
      val request = requestFor(baseUrl)
      val reply = clientFor(baseUrl, Some(Key))
        .complete(request)
        .fold(failure => fail(s"the canned reply was refused: $failure"), identity)
      assertEquals(reply.model, Model)
      assertEquals(reply.text, "(x / example~e.0)")
      assertEquals(reply.stopReason, ModelStopReason.EndTurn)
      reply.evidence match
        case ReplyEvidence.Captured(reportedModel, usage, durationMillis) =>
          assertEquals(reportedModel, Some(ReportedModel))
          assertEquals(usage, ModelUsage(910L, 64L, None))
          assert(durationMillis >= 0L, "a captured reply carries no measured duration")
        case ReplyEvidence.Authored => fail("a live reply was recorded as authored")
      val requests = seen()
      assertEquals(requests.size, 1)
      val sent = requests.head
      assertEquals(sent.method, "POST")
      assertEquals(sent.authorization, Some(s"Bearer $Key"))
      val body = parse(sent.body).fold(error => fail(error.message), identity)
      assertEquals(
        body.asObject.map(_.keys.toVector).getOrElse(fail("the body is not a JSON object")),
        Vector("model", "max_tokens", "messages")
      )
      assertEquals(body.hcursor.downField("model").as[String], Right(Model))
      assertEquals(body.hcursor.downField("max_tokens").as[Long], Right(MaxTokens))
      assertEquals(
        body.hcursor.downField("messages").as[Vector[Json]],
        Right(
          Vector(
            Json.obj("role" -> "system".asJson, "content" -> request.systemPrompt.asJson),
            Json.obj("role" -> "user".asJson, "content" -> request.userMessage.asJson)
          )
        )
      )
    }
  }

  test("the authorization header is present exactly when the court admitted a key") {
    withServer(Vector(Ok -> completion("(x / example~e.0)", "stop"))) { (baseUrl, seen) =>
      val _ = completed(baseUrl, Some(Key)).fold(failure => fail(failure.toString), identity)
      assertEquals(seen().map(_.authorization), Vector(Some(s"Bearer $Key")))
    }
    withServer(Vector(Ok -> completion("(x / example~e.0)", "stop"))) { (baseUrl, seen) =>
      val _ = completed(baseUrl, None).fold(failure => fail(failure.toString), identity)
      assertEquals(seen().map(_.authorization), Vector(None))
    }
  }

  test("length is a max-tokens stop and content_filter is a refusal, with the text preserved") {
    withServer(Vector(Ok -> completion("half a graph", "length"))) { (baseUrl, _) =>
      val reply = completed(baseUrl, None).fold(failure => fail(failure.toString), identity)
      assertEquals(reply.stopReason, ModelStopReason.MaxTokens)
      assertEquals(reply.text, "half a graph")
    }
    withServer(Vector(Ok -> filtered)) { (baseUrl, _) =>
      val reply = completed(baseUrl, None).fold(failure => fail(failure.toString), identity)
      assertEquals(reply.stopReason, ModelStopReason.Refusal)
      assertEquals(reply.text, "")
    }
  }

  test("a finish reason nobody anticipated is kept verbatim, never read as a clean stop") {
    withServer(Vector(Ok -> completion("{}", "tool_calls"))) { (baseUrl, _) =>
      val reply = completed(baseUrl, None).fold(failure => fail(failure.toString), identity)
      assertEquals(reply.stopReason, ModelStopReason.Other("tool_calls"))
      assertEquals(
        ReplyInterpretation.interpret(reply),
        ItemOutcome.Failed(AgentFailureCodes.UnexpectedStop)
      )
    }
  }

  test("a 429 is retried and the next reply is taken") {
    val replies = Vector(TooManyRequests -> "{}", Ok -> completion("(x / example~e.0)", "stop"))
    withServer(replies) { (baseUrl, seen) =>
      val reply = completed(baseUrl, None).fold(failure => fail(failure.toString), identity)
      assertEquals(reply.text, "(x / example~e.0)")
      assertEquals(seen().size, 2)
    }
  }

  test("three server faults exhaust the two retries and become one typed service error") {
    val replies = Vector(ServerFault -> "{}", ServerFault -> "{}", ServerFault -> "{}")
    withServer(replies) { (baseUrl, seen) =>
      assertEquals(completed(baseUrl, None), Left(ExchangeFailure.ServiceError(ServerFault)))
      assertEquals(seen().size, 3)
    }
  }

  test("a 400 is not retried; a client fault will fail the same way twice") {
    withServer(Vector(BadRequest -> "{}")) { (baseUrl, seen) =>
      assertEquals(completed(baseUrl, None), Left(ExchangeFailure.ServiceError(BadRequest)))
      assertEquals(seen().size, 1)
    }
  }

  test("a 2xx body that is not one reply is refused, and the call is never read as a stop") {
    val bodies = Vector(
      "not json at all",
      Json.obj("choices" -> Json.arr()).noSpaces,
      withoutField("usage"),
      withoutFinishReason,
      withoutContent
    )
    bodies.foreach { body =>
      withServer(Vector(Ok -> body)) { (baseUrl, seen) =>
        completed(baseUrl, None) match
          case Left(ExchangeFailure.ReplyUndecodable(_)) => ()
          case other => fail(s"$body was not refused as undecodable: $other")
        assertEquals(seen().size, 1, "an undecodable reply must not be retried")
      }
    }
  }

  /** The request count is deliberately not asserted here: the canned server handles requests on one
    * thread, so a retry would queue behind the sleeping handler and might not be recorded before
    * the assertion runs. That a non-retryable failure is not repeated is proved by the 400 above,
    * where the server answers immediately.
    */
  test("a server slower than the request budget produces one timeout carrying that budget") {
    withServer(Vector(Ok -> completion("(x / example~e.0)", "stop")), delayMillis = 2000L) {
      (baseUrl, seen) =>
        val client = clientFor(baseUrl, None)
        assertEquals(
          client.complete(requestFor(baseUrl, timeoutMillis = 300L)),
          Left(ExchangeFailure.Timeout(300L))
        )
        assert(seen().nonEmpty, "the request never reached the server")
    }
  }

  test("the key never appears in any failure this client can produce") {
    val rendered = Vector(ServerFault -> "{}", Ok -> "not json at all").flatMap { reply =>
      withServer(Vector(reply)) { (baseUrl, _) =>
        val failure = completed(baseUrl, Some(Key)).swap
          .fold(admitted => fail(s"expected a failure, got $admitted"), identity)
        Vector(failure.toString, failure.code.value, failure.toTransport.toString)
      }
    }
    assertEquals(rendered.size, 6)
    rendered.foreach(text => assert(!text.contains(Key), s"the key reached a rendered failure"))
    val authorization =
      LiveAuthorization
        .from(envFor("http://127.0.0.1:9/v1", Some(Key)))
        .fold(refusal => fail(refusal.message), identity)
    assert(!authorization.toString.contains(Key), "the key reached the authorization's toString")
  }

  test("two backends serving the same model id do not share a recording key") {
    val item = AgentFixtures.requestItem(AgentFixtures.inputs.head)
    val shared = "shared-model-id"
    val anthropic = ModelBackend.Anthropic(shared)
    val openAi = ModelBackend.OpenAiCompatible("127.0.0.1:11434", shared)
    assertEquals(anthropic.model, openAi.model)
    val keyOf = (chosen: ModelBackend) =>
      ModelRequest.render(chosen, AgentFixtures.prompt, item, MaxTokens, 1000L).key.checksum
    assertNotEquals(keyOf(anthropic), keyOf(openAi))
    assertNotEquals(
      keyOf(openAi),
      keyOf(ModelBackend.OpenAiCompatible("127.0.0.1:8000", shared)),
      "two servers on one host must not share a key"
    )
  }

  test("a reply recorded under an OpenAI-compatible backend replays through the same court") {
    val chosen = ModelBackend.OpenAiCompatible("127.0.0.1:11434", "llama3.1:8b")
    val input = AgentFixtures.inputs.head
    val store = AgentFixtures.recordingsWith(
      Map(input -> AgentFixtures.reply(AgentFixtures.penman(0), chosen = chosen)),
      chosen
    )
    assert(
      !store.contains(AgentFixtures.keyFor(input)),
      "the reply was also findable under the anthropic key"
    )
    val batch = ParserBatch.validated(Vector(input)).toOption.get
    val transport = AgentFixtures.transportOver(new ModelExchange.Recorded(store), chosen)
    assertEquals(transport.runtime.provider, "openai-compatible:127.0.0.1:11434")
    assertEquals(transport.runtime.model, "llama3.1:8b")
    val result =
      AgentFixtures.replayProvider(new ModelExchange.Recorded(store), chosen).parse(batch)
    assertEquals(result.total, 1)
    assertEquals(result.covered, 1)
  }

object OpenAiExchangeSuite:
  val Model: String = "local-test-model"
  val ReportedModel: String = "local-test-model:q4"
  val Key: String = "test-key-not-a-secret-8f3a"
  val MaxTokens: Long = 4096L
  val Ok: Int = 200
  val BadRequest: Int = 400
  val TooManyRequests: Int = 429
  val ServerFault: Int = 503
  val ChatPath: String = "/v1/chat/completions"

  /** What one canned request looked like on the wire, as the server saw it. */
  final case class SeenRequest(method: String, authorization: Option[String], body: String)

  def envFor(baseUrl: String, key: Option[String]): Map[String, String] =
    Map(
      AgentCredentials.BackendVariable -> AgentCredentials.OpenAiBackend,
      AgentCredentials.OpenAiBaseUrlVariable -> baseUrl,
      AgentCredentials.OpenAiModelVariable -> Model,
      AgentCredentials.LiveVariable -> AgentCredentials.LiveValue
    ) ++ key.map(AgentCredentials.OpenAiKeyVariable -> _).toMap

  private def reply(content: Json, finishReason: Json, usage: Option[Json]): String =
    Json
      .obj(
        (Vector(
          "id" -> "chatcmpl-canned".asJson,
          "object" -> "chat.completion".asJson,
          "model" -> ReportedModel.asJson,
          "choices" -> Json.arr(
            Json.obj(
              "index" -> 0.asJson,
              "message" -> Json.obj("role" -> "assistant".asJson, "content" -> content),
              "finish_reason" -> finishReason
            )
          )
        ) ++ usage.map("usage" -> _))*
      )
      .noSpaces

  private val cannedUsage: Json =
    Json.obj("prompt_tokens" -> 910.asJson, "completion_tokens" -> 64.asJson)

  def completion(content: String, finishReason: String): String =
    reply(content.asJson, finishReason.asJson, Some(cannedUsage))

  /** A refused reply as several servers really send it: no content at all, only the reason. */
  def filtered: String = reply(Json.Null, "content_filter".asJson, Some(cannedUsage))

  def withoutField(name: String): String =
    parse(completion("(x / example~e.0)", "stop"))
      .map(_.mapObject(_.remove(name)).noSpaces)
      .getOrElse(throw new IllegalStateException("the canned completion is not JSON"))

  def withoutFinishReason: String = reply("(x / example~e.0)".asJson, Json.Null, Some(cannedUsage))

  def withoutContent: String =
    parse(completion("(x / example~e.0)", "stop"))
      .map(
        _.hcursor
          .downField("choices")
          .downArray
          .downField("message")
          .downField("content")
          .delete
          .top
          .getOrElse(Json.Null)
          .noSpaces
      )
      .getOrElse(throw new IllegalStateException("the canned completion is not JSON"))

  /** Serve `replies` in order (the last repeats), recording what arrived, on an ephemeral port. */
  def withServer[A](replies: Vector[(Int, String)], delayMillis: Long = 0L)(
      body: (String, () => Vector[SeenRequest]) => A
  ): A =
    val seen = new ConcurrentLinkedQueue[SeenRequest]()
    val next = new AtomicInteger(0)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val _ = server.createContext(
      ChatPath,
      (exchange: HttpExchange) =>
        try
          val payload = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          val _ = seen.add(
            SeenRequest(
              exchange.getRequestMethod,
              Option(exchange.getRequestHeaders.getFirst("Authorization")),
              payload
            )
          )
          if delayMillis > 0L then Thread.sleep(delayMillis)
          val (status, canned) = replies(math.min(next.getAndIncrement(), replies.size - 1))
          val bytes = canned.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("content-type", "application/json")
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        catch case _: Throwable => ()
        finally exchange.close()
    )
    server.start()
    try
      body(
        s"http://127.0.0.1:${server.getAddress.getPort}/v1",
        () => seen.iterator.asScala.toVector
      )
    finally server.stop(0)
