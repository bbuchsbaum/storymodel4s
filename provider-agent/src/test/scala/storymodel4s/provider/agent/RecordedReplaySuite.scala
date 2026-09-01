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
    Recordings
      .at(committedRecordingsDir)
      .fold(error => fail(error.message), identity)

  test("the committed recordings are named by the content keys the fixtures derive") {
    val store = committed()
    inputs.foreach(input => assert(store.contains(keyFor(input)), s"no recording for ${input.id}"))
    assertEquals(listing(committedRecordingsDir).size, 3)
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
      assertEquals(call.cached, false)
      assertEquals(
        call.promptTemplateVersion.map(_.value),
        Some(s"penman-parse@v1#${prompt.ref.checksum.hex}")
      )
      assertEquals(call.params.get("prompt-package"), Some(prompt.ref.checksum.hex))
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

  test("the driver replays end to end and prints nothing but counts and checksums") {
    val work = Files.createTempDirectory("provider-agent-driver")
    val textPath = work.resolve("three.txt")
    Files.write(textPath, text.getBytes(StandardCharsets.UTF_8))
    val outDir = work.resolve("out")
    val summary = ClaudeParseDriver
      .run(
        DriverMode.Replay,
        textPath,
        committedRecordingsDir,
        outDir,
        Map.empty,
        1700000000000L
      )
      .fold(error => fail(error.message), identity)
    assertEquals(summary.sentences, 3)
    assertEquals(summary.proposed, 3)
    assertEquals(summary.failed, 0)
    assertEquals(summary.abstained, 0)
    assertEquals(summary.recordingHits, 3)
    assertEquals(summary.liveCalls, 0)
    Vector("s0000.penman", "s0000.chart.txt", "s0001.chart.txt", "s0002.chart.txt").foreach {
      name => assert(Files.isRegularFile(outDir.resolve(name)), s"$name missing")
    }
    val receipts =
      new String(Files.readAllBytes(outDir.resolve("receipts.json")), StandardCharsets.UTF_8)
    val summaryJson =
      new String(Files.readAllBytes(outDir.resolve("summary.json")), StandardCharsets.UTF_8)
    assert(!receipts.contains("Egulac"), "receipts carry source prose")
    assert(!summaryJson.contains("Egulac"), "summary carries source prose")
    assert(summaryJson.contains("\"weightsPinned\" : false"))
    assert(summaryJson.contains(summary.receiptChecksum.hex))
    val penman =
      new String(Files.readAllBytes(outDir.resolve("s0000.penman")), StandardCharsets.UTF_8)
    assertEquals(penman.trim, penman.trim)
    assert(penman.contains("be-located-at-91~e.1"))
  }

  test("record mode without the live flag is refused before anything is read or written") {
    val work = Files.createTempDirectory("provider-agent-driver-record")
    val textPath = work.resolve("three.txt")
    Files.write(textPath, text.getBytes(StandardCharsets.UTF_8))
    val recordingsDir = work.resolve("recordings")
    val outcome = ClaudeParseDriver.run(
      DriverMode.Record,
      textPath,
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
    assert(!Files.exists(work.resolve("out")), "outputs were written despite the refusal")
  }
