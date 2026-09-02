package storymodel4s.provider.agent

import munit.FunSuite
import storymodel4s.provider.parser.{ParserRuntimeField, ParserSetupFailure}

class CredentialsSuite extends FunSuite:
  import AgentCredentials.*

  test("the project-scoped key wins over the fallback") {
    val env = Map(PrimaryKeyVariable -> "primary-value", FallbackKeyVariable -> "fallback-value")
    assertEquals(apiKey(env).map(_.secret), Some("primary-value"))
  }

  test("an empty or blank primary counts as absent and the nonblank fallback is used") {
    assertEquals(
      apiKey(Map(PrimaryKeyVariable -> "", FallbackKeyVariable -> "fallback-value")).map(_.secret),
      Some("fallback-value")
    )
    assertEquals(
      apiKey(Map(PrimaryKeyVariable -> "  ", FallbackKeyVariable -> "fallback-value"))
        .map(_.secret),
      Some("fallback-value")
    )
  }

  test("an empty fallback counts as absent too") {
    assertEquals(apiKey(Map(FallbackKeyVariable -> "")), None)
    assertEquals(apiKey(Map(PrimaryKeyVariable -> "", FallbackKeyVariable -> " ")), None)
    assertEquals(apiKey(Map.empty), None)
  }

  test("live authorization needs the flag set to exactly 1 and a nonblank key") {
    assertEquals(
      liveAuthorization(Map(PrimaryKeyVariable -> "value")),
      Left(LiveRefusal.LiveFlagAbsent(LiveVariable, LiveValue))
    )
    assertEquals(
      liveAuthorization(Map(LiveVariable -> "true", PrimaryKeyVariable -> "value")),
      Left(LiveRefusal.LiveFlagAbsent(LiveVariable, LiveValue))
    )
    assertEquals(
      liveAuthorization(Map(LiveVariable -> "1")),
      Left(LiveRefusal.ApiKeyAbsent(PrimaryKeyVariable, FallbackKeyVariable))
    )
    assertEquals(
      liveAuthorization(Map(LiveVariable -> "1", FallbackKeyVariable -> "")),
      Left(LiveRefusal.ApiKeyAbsent(PrimaryKeyVariable, FallbackKeyVariable))
    )
    val admitted = liveAuthorization(Map(LiveVariable -> "1", FallbackKeyVariable -> "value"))
    admitted match
      case Right(anthropic: LiveAuthorization.Anthropic) =>
        assertEquals(anthropic.apiKey.secret, "value")
        assertEquals(anthropic.backend, ModelBackend.Anthropic("claude-sonnet-5"))
      case other => fail(s"expected an admitted anthropic authorization, got $other")
  }

  test("the driver mode court accepts only replay and record") {
    assertEquals(DriverMode.parse("replay"), Right(DriverMode.Replay))
    assertEquals(DriverMode.parse("record"), Right(DriverMode.Record))
    assertEquals(DriverMode.parse("live"), Left(DriverError.UnknownMode("live")))
    assertEquals(DriverMode.parse(""), Left(DriverError.UnknownMode("")))
  }

  private val LocalBase = "http://127.0.0.1:11434/v1"
  private val RemoteBase = "https://openrouter.ai/api/v1"
  private val LocalModel = "llama3.1:8b"

  private def openAiEnv(base: String, extra: (String, String)*): Map[String, String] =
    Map(
      BackendVariable -> OpenAiBackend,
      OpenAiBaseUrlVariable -> base,
      OpenAiModelVariable -> LocalModel,
      LiveVariable -> LiveValue
    ) ++ extra

  test("an absent backend variable is the anthropic backend, and a blank one is too") {
    assertEquals(backend(Map.empty), Right(ModelBackend.Anthropic("claude-sonnet-5")))
    assertEquals(
      backend(Map(BackendVariable -> "  ")),
      Right(ModelBackend.Anthropic("claude-sonnet-5"))
    )
    assertEquals(
      backend(Map(BackendVariable -> AnthropicBackend)),
      Right(ModelBackend.Anthropic("claude-sonnet-5"))
    )
  }

  test("a backend name nobody admitted is refused, never defaulted") {
    assertEquals(
      backend(Map(BackendVariable -> "openai-compatible")),
      Left(BackendRefusal.UnknownBackend(BackendVariable, "openai-compatible", AdmittedBackends))
    )
    assertEquals(
      backend(Map(BackendVariable -> "OPENAI")),
      Left(BackendRefusal.UnknownBackend(BackendVariable, "OPENAI", AdmittedBackends))
    )
    assertEquals(AdmittedBackends, Vector("anthropic", "openai"))
  }

  test("the openai backend needs a base URL and a model, and the URL must be http or https") {
    assertEquals(
      backend(Map(BackendVariable -> OpenAiBackend, OpenAiModelVariable -> LocalModel)),
      Left(BackendRefusal.BaseUrlAbsent(OpenAiBaseUrlVariable))
    )
    assertEquals(
      backend(openAiEnv(LocalBase) - OpenAiModelVariable),
      Left(BackendRefusal.ModelAbsent(OpenAiModelVariable))
    )
    assertEquals(
      backend(openAiEnv(LocalBase) + (OpenAiModelVariable -> "  ")),
      Left(BackendRefusal.ModelAbsent(OpenAiModelVariable))
    )
    Vector("ftp://127.0.0.1/v1", "/v1/chat", "127.0.0.1:11434", "https:///v1").foreach { bad =>
      assertEquals(
        backend(openAiEnv(bad)),
        Left(BackendRefusal.BaseUrlNotHttp(OpenAiBaseUrlVariable)),
        s"$bad was admitted as a base URL"
      )
    }
  }

  test("a model id that cannot become a runtime identity is refused before any call") {
    Vector("my model", "model\tname", "a" * 81).foreach { bad =>
      assertEquals(
        backend(openAiEnv(LocalBase) + (OpenAiModelVariable -> bad)),
        Left(BackendRefusal.ModelNotIdentitySafe(OpenAiModelVariable, 80)),
        s"'$bad' was admitted as a model id"
      )
    }
    assert(backend(openAiEnv(LocalBase) + (OpenAiModelVariable -> ("a" * 80))).isRight)
  }

  test("the transport refuses an identity scalar the downstream fingerprint cannot represent") {
    val prompt = AgentFixtures.prompt
    assertEquals(
      ClaudeParserTransport
        .runtimeFor(prompt, ModelBackend.Anthropic("claude sonnet 5"))
        .swap
        .toOption
        .map(_.message),
      Some(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.Model).message)
    )
    assertEquals(
      ClaudeParserTransport
        .runtimeFor(prompt, ModelBackend.OpenAiCompatible("host with space", "llama3.1:8b"))
        .swap
        .toOption
        .map(_.message),
      Some(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.Provider).message)
    )
    assert(
      !ModelBackend.HttpClientVersion.exists(_.isWhitespace),
      "the http client version carries whitespace and would throw in a fingerprint"
    )
    assert(ClaudeParserTransport.runtimeFor(prompt, ModelBackend.default).isRight)
  }

  test("the backend identity carries the endpoint host with its port, and never the credentials") {
    assertEquals(
      backend(openAiEnv(LocalBase)),
      Right(ModelBackend.OpenAiCompatible("127.0.0.1:11434", LocalModel))
    )
    assertEquals(
      backend(openAiEnv(RemoteBase)),
      Right(ModelBackend.OpenAiCompatible("openrouter.ai", LocalModel))
    )
    assertEquals(
      backend(openAiEnv("http://127.0.0.1:11434/v1/")).map(_.provider),
      Right("openai-compatible:127.0.0.1:11434")
    )
    assertEquals(
      backend(openAiEnv("https://someone:secret@openrouter.ai/api/v1")).map(_.provider),
      Right("openai-compatible:openrouter.ai")
    )
  }

  test("a blank openai key is admitted for a loopback host and refused for any other") {
    Vector("http://127.0.0.1:11434/v1", "http://localhost:1234/v1", "http://[::1]:8000/v1")
      .foreach { base =>
        liveAuthorization(openAiEnv(base)) match
          case Right(admitted: LiveAuthorization.OpenAiCompatible) =>
            assertEquals(admitted.apiKey.map(_.secret), None)
          case other => fail(s"$base was refused without a key: $other")
      }
    assertEquals(
      liveAuthorization(openAiEnv(RemoteBase)),
      Left(LiveRefusal.ApiKeyAbsentForRemoteHost(OpenAiKeyVariable, "openrouter.ai"))
    )
    assertEquals(
      liveAuthorization(openAiEnv(RemoteBase, OpenAiKeyVariable -> "   ")),
      Left(LiveRefusal.ApiKeyAbsentForRemoteHost(OpenAiKeyVariable, "openrouter.ai"))
    )
    liveAuthorization(openAiEnv(RemoteBase, OpenAiKeyVariable -> "sk-test")) match
      case Right(admitted: LiveAuthorization.OpenAiCompatible) =>
        assertEquals(admitted.apiKey.map(_.secret), Some("sk-test"))
        assertEquals(
          admitted.chatCompletions.toString,
          "https://openrouter.ai/api/v1/chat/completions"
        )
      case other => fail(s"an admitted remote key was refused: $other")
  }

  test("the openai backend still needs the live flag, and an anthropic key does not stand in") {
    assertEquals(
      liveAuthorization(openAiEnv(LocalBase) - LiveVariable),
      Left(LiveRefusal.LiveFlagAbsent(LiveVariable, LiveValue))
    )
    assertEquals(
      liveAuthorization(
        openAiEnv(RemoteBase) - LiveVariable + (PrimaryKeyVariable -> "anthropic-value")
      ),
      Left(LiveRefusal.LiveFlagAbsent(LiveVariable, LiveValue))
    )
    assertEquals(
      liveAuthorization(openAiEnv(RemoteBase) + (PrimaryKeyVariable -> "anthropic-value")),
      Left(LiveRefusal.ApiKeyAbsentForRemoteHost(OpenAiKeyVariable, "openrouter.ai"))
    )
  }

  test("an unusable backend refuses a live call before the live flag is even considered") {
    assertEquals(
      liveAuthorization(Map(BackendVariable -> "gemini")),
      Left(
        LiveRefusal.BackendRefused(
          BackendRefusal.UnknownBackend(BackendVariable, "gemini", AdmittedBackends)
        )
      )
    )
  }

  test("a named exchange source the court contradicts is refused, never coerced") {
    val anthropic = liveAuthorization(Map(LiveVariable -> LiveValue, PrimaryKeyVariable -> "value"))
      .fold(refusal => fail(refusal.message), identity)
    val openAi = liveAuthorization(openAiEnv(LocalBase))
      .fold(refusal => fail(refusal.message), identity)
    assertEquals(
      ClaudeParseDriver.clientFor(ExchangeSource.OpenAiCompatible, anthropic).swap.toOption,
      Some(DriverError.BackendMismatch("openai-compatible:", "anthropic"))
    )
    assertEquals(
      ClaudeParseDriver.clientFor(ExchangeSource.Anthropic, openAi).swap.toOption,
      Some(DriverError.BackendMismatch("anthropic", "openai-compatible:127.0.0.1:11434"))
    )
    assert(
      ClaudeParseDriver.clientFor(ExchangeSource.Court, openAi).isRight,
      "the court's own client was refused"
    )
    assert(
      ClaudeParseDriver.clientFor(ExchangeSource.OpenAiCompatible, openAi).isRight,
      "a named source the court agrees with was refused"
    )
  }
