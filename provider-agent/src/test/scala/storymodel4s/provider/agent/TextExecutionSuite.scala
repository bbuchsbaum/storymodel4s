package storymodel4s.provider.agent

import io.circe.syntax.*
import java.nio.file.Files
import munit.FunSuite

class TextExecutionSuite extends FunSuite:
  private def authorization(baseUrl: String): LiveAuthorization.OpenAiCompatible =
    LiveAuthorization
      .from(OpenAiExchangeSuite.envFor(baseUrl, None))
      .fold(refusal => fail(refusal.message), identity)
      .asInstanceOf[LiveAuthorization.OpenAiCompatible]

  private def profile(baseUrl: String, tokens: Long = 128L): TextExecution.Profile =
    TextExecution.Profile
      .from(authorization(baseUrl), tokens, 1000L)
      .fold(fail, identity)

  test("the v2 profile binds endpoint and budget without serializing a credential"):
    val first = profile("http://127.0.0.1:19001/v1")
    assertEquals(TextExecution.Profile.decode(first.json), Right(first))
    assert(
      first.key("system", "prompt") != profile("http://127.0.0.1:19002/v1").key("system", "prompt")
    )
    assert(
      first.key("system", "prompt") != profile("http://127.0.0.1:19001/v1", 129L)
        .key("system", "prompt")
    )
    assert(!first.json.noSpaces.contains("test-key"))

  test("a live session records once, counts actual retry attempts, and then serves its cache"):
    OpenAiExchangeSuite.withServer(
      Vector(
        OpenAiExchangeSuite.TooManyRequests -> "{}",
        OpenAiExchangeSuite.Ok -> OpenAiExchangeSuite.completion("reply-canary", "stop")
      )
    ) { (baseUrl, seen) =>
      val store = Recordings.at(Files.createTempDirectory("text-live")).toOption.get
      val session = TextExecution.live(store, profile(baseUrl), authorization(baseUrl)).toOption.get
      try
        assertEquals(session.serve("system-canary", "prompt-canary").map(_.text), Right("reply-canary"))
        assertEquals(session.clientInvocations, 1)
        assertEquals(session.httpAttempts, 2)
        assertEquals(session.serve("system-canary", "prompt-canary").map(_.text), Right("reply-canary"))
        assertEquals(session.clientInvocations, 1)
        assertEquals(session.httpAttempts, 2)
        assertEquals(session.cacheHits, 1)
        assertEquals(seen().size, 2)
        val receipt = session.receipt.noSpaces
        assert(!receipt.contains("system-canary"))
        assert(!receipt.contains("prompt-canary"))
        assert(!receipt.contains("reply-canary"))
      finally session.close()
    }

  test("failed and corrupt prior state refuses a second live attempt"):
    OpenAiExchangeSuite.withServer(Vector(OpenAiExchangeSuite.ServerFault -> "{}")) {
      (baseUrl, seen) =>
        val store = Recordings.at(Files.createTempDirectory("text-journal")).toOption.get
        val active = TextExecution.live(store, profile(baseUrl), authorization(baseUrl)).toOption.get
        try
          assertEquals(active.serve("system", "prompt"), Left(ExchangeFailure.ServiceError(OpenAiExchangeSuite.ServerFault)))
          assertEquals(active.httpAttempts, 3)
          assert(active.serve("system", "prompt").isLeft)
          assertEquals(active.clientInvocations, 1)
        finally active.close()
        Files.writeString(store.dir.resolve(".text-execution-v2.journal.json.tmp"), "incomplete")
        val restarted = TextExecution.live(store, profile(baseUrl), authorization(baseUrl)).toOption.get
        try
          assert(restarted.serve("system", "prompt").isLeft)
          assertEquals(restarted.clientInvocations, 0)
        finally restarted.close()
        assertEquals(seen().size, 3)
    }

  test("one live writer owns a recordings directory and corrupt recordings never spend"):
    OpenAiExchangeSuite.withServer(Vector(OpenAiExchangeSuite.Ok -> OpenAiExchangeSuite.completion("x", "stop"))) {
      (baseUrl, seen) =>
        val store = Recordings.at(Files.createTempDirectory("text-lock")).toOption.get
        val p = profile(baseUrl)
        val first = TextExecution.live(store, p, authorization(baseUrl)).toOption.get
        try
          assert(TextExecution.live(store, p, authorization(baseUrl)).isLeft)
          Files.writeString(store.path(p.key("system", "prompt")), "not json")
          assert(first.serve("system", "prompt").isLeft)
          assertEquals(first.clientInvocations, 0)
          assertEquals(first.httpAttempts, 0)
          assertEquals(seen().size, 0)
        finally first.close()
    }

  test("a malformed or directly persisted started journal refuses before a call"):
    OpenAiExchangeSuite.withServer(Vector(OpenAiExchangeSuite.Ok -> OpenAiExchangeSuite.completion("x", "stop"))) {
      (baseUrl, seen) =>
        val store = Recordings.at(Files.createTempDirectory("text-corrupt-journal")).toOption.get
        val p = profile(baseUrl)
        val key = p.key("system", "prompt").checksum.hex
        val journal = store.dir.resolve(".text-execution-v2.journal.json")
        Files.writeString(journal, "{not-json")
        val corrupt = TextExecution.live(store, p, authorization(baseUrl)).toOption.get
        try
          assert(corrupt.serve("system", "prompt").isLeft)
          assertEquals(corrupt.clientInvocations, 0)
          assertEquals(corrupt.httpAttempts, 0)
        finally corrupt.close()
        Files.writeString(
          journal,
          s"""{"schema":"storymodel4s.provider.agent.text-journal/v2","entries":[{"key":"$key","status":"started","httpAttemptsStarted":"unknown","usage":"unknown"}]}"""
        )
        val started = TextExecution.live(store, p, authorization(baseUrl)).toOption.get
        try
          assertEquals(started.serve("system", "prompt"), Left(ExchangeFailure.PreviousAttemptUnknown(p.key("system", "prompt"))))
          assertEquals(started.clientInvocations, 0)
          assertEquals(started.httpAttempts, 0)
        finally started.close()
        val reply = ModelReply(p.model, "durable", ModelStopReason.EndTurn, ReplyEvidence.Authored)
        assertEquals(store.write(p.key("system", "prompt"), reply), Right(()))
        val cached = TextExecution.live(store, p, authorization(baseUrl)).toOption.get
        try
          assertEquals(cached.serve("system", "prompt"), Right(reply))
          assertEquals(cached.clientInvocations, 0)
        finally cached.close()
        assertEquals(seen().size, 0)
    }
