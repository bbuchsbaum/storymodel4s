package storymodel4s.provider.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import storymodel4s.core.*
import storymodel4s.provider.parser.*

class RecordedReplaySuite extends FunSuite:
  import AgentFixtures.*

  private def listing(dir: Path): Vector[(String, Checksum)] =
    Files
      .list(dir)
      .iterator()
      .asScala
      .toVector
      .map(path => path.getFileName.toString -> Checksum.ofBytes(Files.readAllBytes(path)))
      .sortBy(_._1)

  private def committed(): Recordings =
    Recordings.open(committedRecordingsDir).fold(error => fail(error.message), identity)

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  test("the committed recordings are named by the content keys the fixtures derive") {
    val store = committed()
    inputs.foreach(input => assert(store.contains(keyFor(input)), s"no recording for ${input.id}"))
    assertEquals(listing(committedRecordingsDir).size, 3)
    inputs.foreach { input =>
      assertEquals(
        store.read(keyFor(input), runtime.model).map(_.evidence),
        Right(ReplyEvidence.Authored),
        "a committed recording claims provider accounting it never measured"
      )
    }
  }

  test("three WOG sentences replay through the full court with zero live calls") {
    val before = listing(committedRecordingsDir)
    val provider = replayProvider(new ModelExchange.Recorded(committed()))
    val result = provider.parse(batch)
    assertEquals(result.total, 3)
    assertEquals(result.misses, Vector.empty)
    assertEquals(result.covered, 3)
    result.attempts.foreach { attempt =>
      val call = attempt.receipt.call.getOrElse(fail(s"${attempt.id.value} has no provider call"))
      assertEquals(call.provider, "anthropic")
      assertEquals(call.model, "claude-sonnet-5")
      assertEquals(call.version, s"anthropic-java/${AnthropicSdkPin.version}")
      assertEquals(call.cached, false)
      assertEquals(
        call.promptTemplateVersion.map(_.value),
        Some(s"penman-parse@v1#${prompt.ref.checksum.hex}+${prompt.promptTextChecksum.hex}")
      )
      assertEquals(call.params.get("prompt-package"), Some(prompt.ref.checksum.hex))
      assertEquals(call.params.get("duration-millis"), Some("0"))
    }
    assertEquals(listing(committedRecordingsDir), before, "replay wrote into the recordings")
  }

  test("a second replay writes nothing and ParserDeterminism reports every sentence unchanged") {
    val before = listing(committedRecordingsDir)
    val provider = replayProvider(new ModelExchange.Recorded(committed()))
    val first = provider.parse(batch)
    val second = provider.parse(batch)
    assertEquals(first.covered, 3, "the determinism claim needs admitted charts, not two misses")
    val report = ParserDeterminism
      .compare(batch, first, second)
      .fold(error => fail(s"results do not conform: $error"), identity)
    assert(report.isStable, report.failures.toString)
    assertEquals(report.checks.size, 3)
    assertEquals(report.failures, Vector.empty)
    assertEquals(listing(committedRecordingsDir), before, "second replay wrote into the recordings")
  }

  test("the driver replays end to end: per-sentence artifacts, a derived ledger, no prose") {
    val work = Files.createTempDirectory("provider-agent-driver")
    val textPath = work.resolve("three.txt")
    Files.write(textPath, text.getBytes(StandardCharsets.UTF_8))
    val outDir = work.resolve("out")
    val summary = ClaudeParseDriver
      .run(DriverMode.Replay, textPath, committedRecordingsDir, outDir, Map.empty, 1700000000000L)
      .fold(error => fail(error.message), identity)
    assertEquals(summary.sentences, 3)
    assertEquals(summary.proposed, 3)
    assertEquals(summary.failed, 0)
    assertEquals(summary.abstained, 0)
    assertEquals(summary.transportFailures, 0)
    assertEquals(summary.replayedAuthored, 3)
    assertEquals(summary.replayedCaptured, 0)
    assertEquals(summary.capturedLive, 0)
    assertEquals(summary.unrecorded, 0)
    assertEquals(summary.corrupt, 0)
    assertEquals(summary.liveCalls, 0)

    inputs.zip(penman).foreach { (input, expected) =>
      assertEquals(read(outDir.resolve(s"${input.id.value}.penman")).trim, expected)
      assert(Files.isRegularFile(outDir.resolve(s"${input.id.value}.chart.txt")))
    }
    val receipts = read(outDir.resolve("receipts.json"))
    val summaryJson = read(outDir.resolve("summary.json"))
    assert(!receipts.contains("Egulac"), "receipts carry source prose")
    assert(!summaryJson.contains("Egulac"), "summary carries source prose")
    assertEquals(receipts.split("\"served\" : \"replayed-authored\"").length - 1, 3)
    assert(summaryJson.contains("\"replayedAuthored\" : 3"))
    assert(summaryJson.contains("\"liveCalls\" : 0"))
    assert(summaryJson.contains("\"cached\" : true"), "stage record did not derive cached")
    assert(summaryJson.contains("\"weightsPinned\" : false"))
    assert(summaryJson.contains(s"\"stage\" : \"${ClaudeParseDriver.Stage.value}\""))
    assert(summaryJson.contains(summary.receiptChecksum.hex))
  }

  test("a replay against a missing recordings directory is refused and creates nothing") {
    val work = Files.createTempDirectory("provider-agent-driver-missing")
    val textPath = work.resolve("three.txt")
    Files.write(textPath, text.getBytes(StandardCharsets.UTF_8))
    val recordingsDir = work.resolve("absent")
    val outcome = ClaudeParseDriver.run(
      DriverMode.Replay,
      textPath,
      recordingsDir,
      work.resolve("out"),
      Map.empty,
      0L
    )
    assertEquals(
      outcome,
      Left(DriverError.RecordingsUnavailable(RecordingsError.Missing(recordingsDir.toString)))
    )
    assert(!Files.exists(recordingsDir), "replay created the recordings directory")
    assert(!Files.exists(work.resolve("out")), "outputs were written despite the refusal")
  }

  test("a replay with no recordings reports every sentence unrecorded and a transport failure") {
    val work = Files.createTempDirectory("provider-agent-driver-empty")
    val textPath = work.resolve("three.txt")
    Files.write(textPath, text.getBytes(StandardCharsets.UTF_8))
    val recordingsDir = Files.createDirectory(work.resolve("empty"))
    val summary = ClaudeParseDriver
      .run(DriverMode.Replay, textPath, recordingsDir, work.resolve("out"), Map.empty, 0L)
      .fold(error => fail(error.message), identity)
    assertEquals(summary.proposed, 0)
    assertEquals(summary.transportFailures, 3)
    assertEquals(summary.unrecorded, 3)
    assertEquals(summary.liveCalls, 0)
    assertEquals(listing(recordingsDir), Vector.empty, "a replay wrote a recording")
  }

  test("record mode without the live flag is refused before anything is read or created") {
    val work = Files.createTempDirectory("provider-agent-driver-record")
    val recordingsDir = work.resolve("recordings")
    val outcome = ClaudeParseDriver.run(
      DriverMode.Record,
      work.resolve("absent.txt"),
      recordingsDir,
      work.resolve("out"),
      Map(AgentCredentials.PrimaryKeyVariable -> "not-a-real-key"),
      0L
    )
    assertEquals(
      outcome,
      Left(
        DriverError.LiveRefused(
          LiveRefusal.LiveFlagAbsent(AgentCredentials.LiveVariable, AgentCredentials.LiveValue)
        )
      )
    )
    assert(!Files.exists(recordingsDir), "the recordings directory was created")
    assert(!Files.exists(work.resolve("out")), "outputs were written despite the refusal")
  }

  private def oneSentenceBatch(work: Path, name: String, sentence: String): (Path, ParserBatch) =
    val textPath = work.resolve(name)
    Files.write(textPath, sentence.getBytes(StandardCharsets.UTF_8))
    val story = StorySource.fromText(sentence, Some(name)).fold(e => fail(e.message), identity)
    val batch = ClaudeParseDriver
      .inputs(SurfaceAnalyzer.analyze(story))
      .fold(error => fail(error.message), identity)
    (textPath, batch)

  test("a replay over a captured recording reports replayedCaptured and a cached stage") {
    val work = Files.createTempDirectory("provider-agent-replay-captured")
    val (textPath, one) = oneSentenceBatch(work, "one.txt", "They came down the river.")
    val store = Recordings.at(work.resolve("rec")).fold(e => fail(e.message), identity)
    val key = ClaudeParseDriver.recordingKeys(runtime, prompt, one).head
    store.write(key, captured(penman(2), 777L)).fold(e => fail(e.toString), identity)
    val outDir = work.resolve("out")
    val summary = ClaudeParseDriver
      .run(DriverMode.Replay, textPath, store.dir, outDir, Map.empty, 0L)
      .fold(error => fail(error.message), identity)
    assertEquals(summary.replayedCaptured, 1)
    assertEquals(summary.replayedAuthored, 0)
    assertEquals(summary.proposed, 1)
    assertEquals(summary.liveCalls, 0)
    val summaryJson = read(outDir.resolve("summary.json"))
    assert(summaryJson.contains("\"replayedCaptured\" : 1"))
    assert(summaryJson.contains("\"cached\" : true"))
    assert(read(outDir.resolve("receipts.json")).contains("\"served\" : \"replayed-captured\""))
  }

  test("record mode with a scripted client captures once, then replays it as captured") {
    val work = Files.createTempDirectory("provider-agent-record-scripted")
    val (textPath, one) = oneSentenceBatch(work, "one.txt", "They came down the river.")
    val key = ClaudeParseDriver.recordingKeys(runtime, prompt, one).head
    val recordingsDir = work.resolve("rec")
    val source = scripted(Map(key -> captured(penman(2), 555L)))
    val first = ClaudeParseDriver
      .run(DriverMode.Record, textPath, recordingsDir, work.resolve("out1"), recordEnv, 0L, source)
      .fold(error => fail(error.message), identity)
    assertEquals(first.capturedLive, 1)
    assertEquals(first.liveCalls, 1)
    assertEquals(first.proposed, 1)
    val store = Recordings.open(recordingsDir).fold(e => fail(e.message), identity)
    store.read(key, runtime.model).map(_.evidence) match
      case Right(ReplyEvidence.Captured(_, _, 555L)) => ()
      case other => fail(s"the scripted reply was not recorded as captured: $other")
    assert(!read(work.resolve("out1").resolve("summary.json")).contains("\"cached\" : true"))
    val second = ClaudeParseDriver
      .run(DriverMode.Record, textPath, recordingsDir, work.resolve("out2"), recordEnv, 0L, source)
      .fold(error => fail(error.message), identity)
    assertEquals(second.replayedCaptured, 1)
    assertEquals(second.capturedLive, 0)
    assertEquals(second.liveCalls, 0)
    assert(read(work.resolve("out2").resolve("summary.json")).contains("\"cached\" : true"))
  }

  test("record mode counts a failed live call as unrecorded and a corrupt recording as no call") {
    val work = Files.createTempDirectory("provider-agent-record-corrupt")
    val (textPath, two) =
      oneSentenceBatch(work, "two.txt", "They came down the river. There were people at Egulac.")
    val keys = ClaudeParseDriver.recordingKeys(runtime, prompt, two)
    assertEquals(keys.size, 2)
    val store = Recordings.at(work.resolve("rec")).fold(e => fail(e.message), identity)
    Files.write(store.path(keys(0)), "{ not json".getBytes(StandardCharsets.UTF_8))
    val source = scripted(Map.empty, ExchangeFailure.ServiceError(529))
    val summary = ClaudeParseDriver
      .run(DriverMode.Record, textPath, store.dir, work.resolve("out"), recordEnv, 0L, source)
      .fold(error => fail(error.message), identity)
    assertEquals(summary.corrupt, 1)
    assertEquals(summary.unrecorded, 1)
    assertEquals(summary.capturedLive, 0)
    assertEquals(summary.liveCalls, 1, "a corrupt recording must not count as a call")
    assertEquals(summary.transportFailures, 2)
    assertEquals(summary.proposed, 0)
    assert(!read(work.resolve("out").resolve("summary.json")).contains("\"cached\" : true"))
    assert(read(work.resolve("out").resolve("receipts.json")).contains("\"served\" : \"corrupt\""))
  }

  test("record mode reads the text before it may create the recordings directory") {
    val work = Files.createTempDirectory("provider-agent-record-unreadable")
    val recordingsDir = work.resolve("rec")
    val outcome = ClaudeParseDriver.run(
      DriverMode.Record,
      work.resolve("absent.txt"),
      recordingsDir,
      work.resolve("out"),
      recordEnv,
      0L,
      scripted(Map.empty)
    )
    outcome match
      case Left(DriverError.TextUnreadable(path, _)) =>
        assertEquals(path, work.resolve("absent.txt").toString)
      case other => fail(s"expected TextUnreadable, got $other")
    assert(!Files.exists(recordingsDir), "the recordings directory was created before the read")
    assert(!Files.exists(work.resolve("out")))
  }

  test("the served-from ledger classifies all six branches from the store alone") {
    val store = recordingsWith(Map.empty)
    val key = keyFor(inputs.head)
    def classify(wasPresent: Boolean): RecordingService =
      ClaudeParseDriver.service(store, runtime, key, wasPresent)
    assertEquals(classify(wasPresent = false), RecordingService.Unrecorded)
    assertEquals(classify(wasPresent = true), RecordingService.Unrecorded)
    store.write(key, reply(penman(0))).fold(e => fail(e.toString), identity)
    assertEquals(classify(wasPresent = true), RecordingService.ReplayedAuthored)
    assertEquals(classify(wasPresent = false), RecordingService.Foreign)
    store.write(key, captured(penman(0), 1L)).fold(e => fail(e.toString), identity)
    assertEquals(classify(wasPresent = true), RecordingService.ReplayedCaptured)
    assertEquals(classify(wasPresent = false), RecordingService.CapturedLive)
    Files.write(store.path(key), "{ not json".getBytes(StandardCharsets.UTF_8))
    assertEquals(classify(wasPresent = true), RecordingService.Corrupt)
    assertEquals(classify(wasPresent = false), RecordingService.Corrupt)
    assertEquals(
      RecordingService.values.toVector.filter(_.servedFromPresentRecording),
      Vector(RecordingService.ReplayedAuthored, RecordingService.ReplayedCaptured)
    )
  }

  test("a corrupt recording among present ones makes the stage uncached and counts no call") {
    val work = Files.createTempDirectory("provider-agent-replay-corrupt")
    val textPath = work.resolve("three.txt")
    Files.write(textPath, text.getBytes(StandardCharsets.UTF_8))
    val copy = Files.createDirectory(work.resolve("rec"))
    Files.list(committedRecordingsDir).iterator().asScala.foreach { source =>
      Files.copy(source, copy.resolve(source.getFileName.toString))
    }
    val store = Recordings.open(copy).fold(e => fail(e.message), identity)
    Files.write(store.path(keyFor(inputs(1))), "{ not json".getBytes(StandardCharsets.UTF_8))
    val outDir = work.resolve("out")
    val summary = ClaudeParseDriver
      .run(DriverMode.Replay, textPath, copy, outDir, Map.empty, 0L)
      .fold(error => fail(error.message), identity)
    assertEquals(summary.corrupt, 1)
    assertEquals(summary.replayedAuthored, 2)
    assertEquals(summary.proposed, 2)
    assertEquals(summary.transportFailures, 1)
    assertEquals(summary.liveCalls, 0)
    val summaryJson = read(outDir.resolve("summary.json"))
    assert(summaryJson.contains("\"cached\" : false"), "a corrupt recording was counted as cached")
    assert(summaryJson.contains("\"corrupt\" : 1"))
  }
