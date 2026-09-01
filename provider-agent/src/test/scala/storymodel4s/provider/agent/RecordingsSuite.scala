package storymodel4s.provider.agent

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import storymodel4s.core.Checksum
import storymodel4s.provider.parser.*

class RecordingsSuite extends FunSuite:
  import AgentFixtures.*

  private val first = inputs.head
  private val single = ParserBatch.validated(Vector(first)).toOption.get

  private def keyOf(item: RequestItem, promptPackage: AgentPromptPackage = prompt): RecordingKey =
    ModelRequest.render(runtime.model, promptPackage, item, 4096L, 1000L).key

  test("the recording key is content-derived: request ids do not participate") {
    val item = requestItem(first)
    assertEquals(keyOf(item), keyOf(item.copy(id = "some-other-request")))
    assertEquals(keyOf(item), keyOf(item.copy(sentenceId = "elsewhere")))
    assertEquals(keyOf(item), keyFor(first))
  }

  test("changing one token text, the prompt text, or the model changes the key") {
    val item = requestItem(first)
    val altered = item.copy(tokens = item.tokens.updated(4, item.tokens(4).copy(text = "Egulak")))
    assertNotEquals(keyOf(item), keyOf(altered))
    val otherPrompt = AgentPromptPackage
      .fromText(prompt.systemPrompt + "\nOne more instruction.")
      .fold(error => fail(error.message), identity)
    assertNotEquals(keyOf(item), keyOf(item, otherPrompt))
    assertNotEquals(
      keyOf(item),
      ModelRequest.render("claude-other", prompt, item, 4096L, 1000L).key
    )
    assertNotEquals(
      keyOf(item),
      ModelRequest.render(runtime.model, prompt, item, 8192L, 1000L).key,
      "the token budget did not reach the key"
    )
    assertEquals(
      keyOf(item),
      ModelRequest.render(runtime.model, prompt, item, 4096L, 999999L).key,
      "the timeout must not reach the key"
    )
  }

  test(
    "a missing recording is Io(recording-missing) for the exchange and TransportIo for the attempt"
  ) {
    val empty = recordingsWith(Map.empty)
    val transport = transportOver(new ModelExchange.Recorded(empty))
    val requestJson = ParserEnvelope.encodeRequest(single, runtime, config)
    assertEquals(
      transport.exchange(requestJson, config.timeoutMillis),
      Left(TransportFailure.Io(AgentFailureCodes.RecordingMissing))
    )
    val attempt = replayProvider(new ModelExchange.Recorded(empty)).parse(single).attempts.head
    assertEquals(failureOf(attempt), ParserFailure.TransportIo(AgentFailureCodes.RecordingMissing))
    assert(attempt.receipt.call.nonEmpty, "a transport failure still mints a receipt call")
  }

  test("a corrupt recording file and a recording for another model are both RecordingCorrupt") {
    val store = recordingsWith(Map(first -> reply(penman(0))))
    val key = keyFor(first)
    Files.write(store.path(key), "{ not json".getBytes(StandardCharsets.UTF_8))
    store.read(key, runtime.model) match
      case Left(ExchangeFailure.RecordingCorrupt(found, _)) => assertEquals(found, key)
      case other => fail(s"expected RecordingCorrupt, got $other")
    val foreign = reply(penman(0)).copy(model = "claude-other")
    store.write(key, foreign).fold(error => fail(error.toString), identity)
    store.read(key, runtime.model) match
      case Left(ExchangeFailure.RecordingCorrupt(found, _)) => assertEquals(found, key)
      case other => fail(s"expected RecordingCorrupt for a foreign model, got $other")
  }

  test("a missing recordings directory is refused by open and created by at") {
    val parent = Files.createTempDirectory("provider-agent-open")
    val absent = parent.resolve("absent")
    assertEquals(Recordings.open(absent), Left(RecordingsError.Missing(absent.toString)))
    assert(!Files.exists(absent))
    assert(Recordings.at(absent).isRight)
    assert(Files.isDirectory(absent))
    val file = Files.createFile(parent.resolve("file"))
    assertEquals(Recordings.open(file), Left(RecordingsError.NotADirectory(file.toString)))
    assertEquals(Recordings.at(file), Left(RecordingsError.NotADirectory(file.toString)))
  }

  test("encode and decode round-trip captured and authored replies without loss") {
    val capturedReply = ModelReply(
      "claude-sonnet-5",
      "  (x / thing~e.0)\n",
      ModelStopReason.Other("stop_sequence"),
      ReplyEvidence.Captured(Some("claude-sonnet-5-snapshot"), ModelUsage(1L, 2L, None), 4L)
    )
    assertEquals(Recordings.decode(Recordings.encode(capturedReply)), Right(capturedReply))
    val authoredReply =
      ModelReply("claude-sonnet-5", "ABSTAIN", ModelStopReason.EndTurn, ReplyEvidence.Authored)
    assertEquals(Recordings.decode(Recordings.encode(authoredReply)), Right(authoredReply))
    assert(!Recordings.encode(authoredReply).contains("durationMillis"))
    assert(Recordings.encode(authoredReply).contains("\"origin\" : \"authored\""))
    assertEquals(ModelStopReason.fromWire("refusal"), ModelStopReason.Refusal)
    assertEquals(ModelStopReason.fromWire("max_tokens"), ModelStopReason.MaxTokens)
    assertEquals(ModelStopReason.fromWire("end_turn"), ModelStopReason.EndTurn)
  }

  test("an authored recording that carries accounting is refused, as is negative accounting") {
    val authoredWithUsage = Recordings
      .encode(reply(penman(0)))
      .replace(
        "\"origin\" : \"authored\",",
        "\"origin\" : \"authored\",\n  \"durationMillis\" : 5,"
      )
    assert(Recordings.decode(authoredWithUsage).isLeft, "authored recording kept a duration")
    val negativeDuration = Recordings
      .encode(captured(penman(0), 1500L))
      .replace("\"durationMillis\" : 1500", "\"durationMillis\" : -1")
    assert(Recordings.decode(negativeDuration).isLeft, "negative duration admitted")
    val negativeUsage = Recordings
      .encode(captured(penman(0), 1500L))
      .replace("\"outputTokens\" : 60", "\"outputTokens\" : -60")
    assert(Recordings.decode(negativeUsage).isLeft, "negative usage admitted")
    val unknownOrigin = Recordings
      .encode(reply(penman(0)))
      .replace("\"origin\" : \"authored\"", "\"origin\" : \"guessed\"")
    assert(Recordings.decode(unknownOrigin).isLeft, "unknown origin admitted")
  }

  test("a Recorded exchange never writes, whatever it is asked") {
    val store = recordingsWith(Map(first -> reply(penman(0))))
    val before = Files.list(store.dir).iterator().asScala.toVector.map(_.getFileName.toString)
    val exchange = new ModelExchange.Recorded(store)
    val provider = replayProvider(exchange)
    provider.parse(batch)
    provider.parse(single)
    val after = Files.list(store.dir).iterator().asScala.toVector.map(_.getFileName.toString)
    assertEquals(after, before)
  }

  test("every failure code literal was admitted rather than thrown on") {
    assertEquals(AgentFailureCodes.all.size, 16)
    assert(AgentFailureCodes.all.forall(code => code.value.nonEmpty))
    assertEquals(AgentFailureCodes.serviceError(429).value, "service-error-429")
    assertEquals(ExchangeFailure.Timeout(7L).toTransport, TransportFailure.Timeout(7L))
    assertEquals(
      ExchangeFailure.RateLimited.toTransport,
      TransportFailure.Io(AgentFailureCodes.RateLimited)
    )
    assertEquals(
      ExchangeFailure.ServiceError(529).toTransport,
      TransportFailure.Io(AgentFailureCodes.serviceError(529))
    )
    assertEquals(
      ExchangeFailure.ConnectionFailed(Checksum.ofText("x")).toTransport,
      TransportFailure.Io(AgentFailureCodes.ConnectionFailed)
    )
  }

  test("the prompt package manifests under the provider-agent custom role with a stable ref") {
    assertEquals(prompt.ref.name, "penman-parse")
    assertEquals(prompt.ref.version, "v1")
    assertEquals(
      prompt.manifest.role,
      storymodel4s.acquire.PromptRole.Custom("provider-agent", "penman-parse")
    )
    assert(storymodel4s.acquire.PromptPackageManifest.verify(prompt.manifest, prompt.ref))
    assertEquals(prompt.promptTextChecksum, Checksum.ofText(prompt.systemPrompt))
    assert(prompt.systemPrompt.contains("ABSTAIN"))
    assert(prompt.systemPrompt.contains("~e.N"))
    assert(!prompt.systemPrompt.contains("Egulac"), "the prompt leaks the acceptance fixture")
  }
