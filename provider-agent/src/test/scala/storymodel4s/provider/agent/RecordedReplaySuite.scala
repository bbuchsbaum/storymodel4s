package storymodel4s.provider.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import storymodel4s.core.Checksum
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
        Some(s"penman-parse@v1#${prompt.ref.checksum.hex}")
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
