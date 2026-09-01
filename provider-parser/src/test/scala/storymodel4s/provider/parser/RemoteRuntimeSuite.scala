package storymodel4s.provider.parser

import cats.Id
import munit.FunSuite
import storymodel4s.acquire.PromptPackageRef
import storymodel4s.core.*

class RemoteRuntimeSuite extends FunSuite:
  import TestFixtures.*

  private val promptA = PromptPackageRef("penman-parse", "v1", Checksum.ofText("prompt-a"))

  /** Same name and version: only the manifest checksum differs, so the digest must carry it. */
  private val promptB = PromptPackageRef("penman-parse", "v1", Checksum.ofText("prompt-b"))
  private val promptText = Checksum.ofText("system prompt text")

  private def remote(
      provider: String = "anthropic",
      model: String = "claude-sonnet-5",
      sdkVersion: String = "anthropic-java/2.34.0",
      promptPackage: PromptPackageRef = promptA,
      promptTextChecksum: Checksum = promptText,
      resultSchema: String = ParserEnvelope.ResultSchema
  ): Either[ParserSetupFailure, RemoteRuntime] =
    RemoteRuntime.from(
      provider,
      model,
      sdkVersion,
      promptPackage,
      promptTextChecksum,
      resultSchema
    )

  private def admitted(result: Either[ParserSetupFailure, RemoteRuntime]): RemoteRuntime =
    result.fold(
      error => fail(s"expected an admitted remote runtime, got ${error.message}"),
      identity
    )

  test("a remote runtime never claims pinned weights and carries a derived fingerprint") {
    val runtime = admitted(remote())
    assertEquals(runtime.weightsPinned, false)
    assertEquals(runtime.fingerprint.value, s"remote-runtime:${runtime.checksum.hex}")
    assertEquals(runtime.version, "anthropic-java/2.34.0")
  }

  test("the fingerprint changes when any identity field changes") {
    val base = admitted(remote())
    val variants = Vector(
      "provider" -> remote(provider = "other-provider"),
      "model" -> remote(model = "claude-other"),
      "sdkVersion" -> remote(sdkVersion = "anthropic-java/2.35.0"),
      "promptPackage" -> remote(promptPackage = promptB),
      "promptTextChecksum" -> remote(promptTextChecksum = Checksum.ofText("other text")),
      "resultSchema" -> remote(resultSchema = "storymodel4s.parser.result/v3")
    )
    variants.foreach { (field, variant) =>
      val changed = admitted(variant)
      assertNotEquals(changed.fingerprint, base.fingerprint, s"$field did not move fingerprint")
      assertNotEquals(changed.checksum, base.checksum, s"$field did not move checksum")
    }
  }

  test("changing the prompt text changes the prompt-template version and the identity") {
    val base = admitted(remote())
    val other = admitted(remote(promptTextChecksum = Checksum.ofText("other text")))
    assertNotEquals(other.promptTemplateVersion, base.promptTemplateVersion)
    assertNotEquals(other.fingerprint, base.fingerprint)
    assertEquals(
      base.promptTemplateVersion.map(_.value),
      Some(s"penman-parse@v1#${promptA.checksum.hex}+${promptText.hex}")
    )
    assertEquals(
      other.promptTemplateVersion.map(_.value),
      Some(s"penman-parse@v1#${promptA.checksum.hex}+${Checksum.ofText("other text").hex}")
    )
  }

  test("blank identity scalars are refused with the typed field named") {
    assertEquals(
      remote(provider = " "),
      Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.Provider))
    )
    assertEquals(
      remote(model = ""),
      Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.Model))
    )
    assertEquals(
      remote(sdkVersion = "\t"),
      Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.SdkVersion))
    )
    assertEquals(
      remote(promptPackage = promptA.copy(name = "")),
      Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.PromptPackageName))
    )
    assertEquals(
      remote(promptPackage = promptA.copy(version = " ")),
      Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.PromptPackageVersion))
    )
    assertEquals(
      remote(resultSchema = ""),
      Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.ResultSchema))
    )
  }

  test("the cache key differs across prompt packages under one model") {
    val first = admitted(remote(promptPackage = promptA))
    val second = admitted(remote(promptPackage = promptB))
    assertNotEquals(
      ParserCacheKey.of(inputs.head, first, config),
      ParserCacheKey.of(inputs.head, second, config)
    )
    assertEquals(
      ParserCacheKey.of(inputs.head, first, config),
      ParserCacheKey.of(inputs.head, admitted(remote(promptPackage = promptA)), config)
    )
  }

  test("a remote receipt carries the prompt package as its prompt-template version") {
    val remoteRuntime = admitted(remote())
    val transport = new ScriptedTransport(
      Vector(Right(response(Vector(wantItem(), sleepItem()), remoteRuntime.fingerprint)))
    )
    val provider = JsonAmrCandidateProvider[Id](
      ParserRuntime.Remote(remoteRuntime),
      config,
      lexicon,
      transport
    )
    val result = provider.parse(batch)
    assertEquals(result.covered, 2)
    val calls = result.attempts.flatMap(_.receipt.call)
    assertEquals(calls.size, 2)
    calls.foreach { call =>
      assertEquals(call.provider, "anthropic")
      assertEquals(call.model, "claude-sonnet-5")
      assertEquals(call.version, "anthropic-java/2.34.0")
      assertEquals(
        call.promptTemplateVersion.map(_.value),
        Some(s"penman-parse@v1#${promptA.checksum.hex}+${promptText.hex}")
      )
      assertEquals(call.params.get("runtime-fingerprint"), Some(remoteRuntime.fingerprint.value))
    }
  }

  test("a pinned receipt keeps no prompt-template version") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val provider = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      transport
    )
    val calls = provider.parse(batch).attempts.flatMap(_.receipt.call)
    assertEquals(calls.size, 2)
    assert(calls.forall(_.promptTemplateVersion.isEmpty))
    assertEquals(runtime.weightsPinned, true)
  }

  test("a remote runtime with a foreign response fingerprint is refused by the court") {
    val remoteRuntime = admitted(remote())
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val provider = JsonAmrCandidateProvider[Id](
      ParserRuntime.Remote(remoteRuntime),
      config,
      lexicon,
      transport
    )
    val result = provider.parse(batch)
    assertEquals(result.covered, 0)
    result.attempts.foreach { attempt =>
      assertEquals(
        attempt.result,
        Left(
          ParserFailure.RuntimeFingerprintMismatch(remoteRuntime.fingerprint, runtime.fingerprint)
        )
      )
    }
  }

  test("the caching wrapper accepts a remote delegate and replays without transport calls") {
    val remoteRuntime = admitted(remote())
    val transport = new ScriptedTransport(
      Vector(Right(response(Vector(wantItem(), sleepItem()), remoteRuntime.fingerprint)))
    )
    val delegate = JsonAmrCandidateProvider[Id](
      ParserRuntime.Remote(remoteRuntime),
      config,
      lexicon,
      transport
    )
    val cached = CachingParserProvider.from[Id](delegate, new MemoryCache).toOption.get
    val first = cached.parse(batch)
    val second = cached.parse(batch)
    assertEquals(transport.calls, 1)
    assertEquals(first.covered, 2)
    assertEquals(second.attempts.map(_.result), first.attempts.map(_.result))
    assert(second.attempts.forall(_.receipt.isCacheHit))
  }
