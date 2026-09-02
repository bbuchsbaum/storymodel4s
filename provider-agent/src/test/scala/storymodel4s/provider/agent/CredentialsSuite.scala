package storymodel4s.provider.agent

import munit.FunSuite

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
