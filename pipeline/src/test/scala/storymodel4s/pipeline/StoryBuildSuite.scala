package storymodel4s.pipeline

import cats.data.NonEmptyVector
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import storymodel4s.codec.StoryModelCodec
import storymodel4s.core.*
import storymodel4s.document.{CoverageCounts, NarrativeCompilerError}
import storymodel4s.fixtures.wog.WarOfTheGhostsText
import storymodel4s.provider.agent.*

/** The replay-mode War of the Ghosts court for the story-build orchestrator (ADR 0009).
  *
  * Every run here is `replay`, so `liveCalls == 0` by construction. Two recording sets live under
  * this module's test resources, both hand-written (`origin: authored`) and both carrying the same
  * three PENMAN replies `provider-agent` commits:
  *
  *   - `recordings/three/`: byte-for-byte copies of `provider-agent/src/test/resources/recordings`,
  *     keyed to the three-sentence text those replies were written for;
  *   - `recordings/wog/`: the same three files re-keyed to the full fixture text. A recording key
  *     digests the token identities of its sentence, and a token id names its story, so a reply
  *     recorded for the three-sentence story cannot serve the fifty-sentence one; the re-keyed copy
  *     is what lets the full text replay its first three sentences without a model call.
  *
  * The text is the admitted fixture text written to a temp file; the pipeline reads it as-is. The
  * suite pins counts as literals: they are what the three replies cover under the 1.3 provider's
  * rules, and a change in the recordings, the provider, or the compiler must move them.
  */
class StoryBuildSuite extends FunSuite:
  private val Now = 1700000000000L

  /** The model every recording names; `provider-agent`'s replay suite pins the same literal. */
  private val Model = "claude-sonnet-5"

  private val wogRecordings: Path = Paths.get(getClass.getResource("/recordings/wog").toURI)
  private val threeRecordings: Path = Paths.get(getClass.getResource("/recordings/three").toURI)

  private val threeText: String =
    "There were people at Egulac. One night two young men went to hunt seals. " +
      "They came down the river."

  /** The three authored replies, as `provider-agent`'s fixture pins them. */
  private val penman: Vector[String] = Vector(
    "(b / be-located-at-91~e.1 :ARG1 (p / person~e.2) " +
      ":ARG2 (c / city~e.4 :name (n / name~e.4 :op1 \"Egulac\")))",
    "(g / go-02~e.5 :ARG0 (m / man~e.4 :quant 2 :mod (y / young~e.3)) " +
      ":purpose (h / hunt-01~e.7 :ARG0 m :ARG1 (s / seal~e.8)) " +
      ":time (n / night~e.1 :quant 1))",
    "(c / come-01~e.1 :ARG1 (t / they~e.0) :direction (d / down~e.2) :path (r / river~e.4))"
  )

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def writeText(work: Path, name: String, text: String): Path =
    val path = work.resolve(name)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
    path

  private def wogText(work: Path): Path =
    writeText(work, "war-of-the-ghosts.txt", WarOfTheGhostsText.text)

  private def work(name: String): Path = Files.createTempDirectory(s"pipeline-$name")

  private def build(textPath: Path, recordings: Path, outDir: Path): BuildSummary =
    StoryPipeline
      .run(DriverMode.Replay, textPath, recordings, outDir, Map.empty, Now)
      .fold(error => fail(error.message), identity)

  private def parsed(textPath: Path, recordings: Path): ParseOutcome =
    ClaudeParseDriver
      .parse(DriverMode.Replay, textPath, recordings, Map.empty, Now)
      .fold(error => fail(error.message), identity)

  private def json(path: Path): Json = parse(read(path)).fold(e => fail(e.message), identity)

  private def keys(value: Json): Set[String] =
    value.asObject.map(_.keys.toSet).getOrElse(fail("not a JSON object"))

  private def coverageRows(report: Json): Vector[Json] =
    report.hcursor
      .downField("coverage")
      .downField("rows")
      .as[Vector[Json]]
      .fold(e => fail(e.message), identity)

  private def kindOf(row: Json): String =
    row.hcursor.downField("kind").as[String].fold(e => fail(e.message), identity)

  private def copyRecordings(from: Path, into: Path): Path =
    Files.createDirectories(into)
    Files.list(from).iterator().asScala.foreach { source =>
      Files.copy(source, into.resolve(source.getFileName.toString))
    }
    into

  test("both recording sets are the three authored replies, keyed to their own source") {
    val dir = work("recordings")
    val cases = Vector(
      wogText(dir) -> wogRecordings,
      writeText(dir, "three.txt", threeText) -> threeRecordings
    )
    cases.foreach { (textPath, recordings) =>
      val outcome = parsed(textPath, recordings)
      val store = Recordings.open(recordings).fold(e => fail(e.message), identity)
      assertEquals(Files.list(recordings).iterator().asScala.size, 3)
      outcome.recordingKeys.take(3).zip(penman).foreach { (key, expected) =>
        assert(store.contains(key), s"no recording under $recordings for ${key.checksum.hex}")
        val reply = store.read(key, Model).fold(e => fail(e.toString), identity)
        assertEquals(reply.text, expected)
        assertEquals(reply.evidence, ReplyEvidence.Authored)
      }
      outcome.recordingKeys.drop(3).foreach(key => assert(!store.contains(key)))
    }
  }

  test("the WOG replay court: 50 sentences, three charts, two situations, a partial draft") {
    val dir = work("wog")
    val outDir = dir.resolve("out")
    val summary = build(wogText(dir), wogRecordings, outDir)

    assertEquals(summary.sentences, 50)
    assertEquals(summary.charts, 3)
    assertEquals(summary.parser.sentences, 50)
    assertEquals(summary.parser.proposed, 3)
    assertEquals(summary.parser.failed, 47)
    assertEquals(summary.parser.abstained, 0)
    assertEquals(summary.parser.transportFailures, 47)
    assertEquals(summary.parser.replayedAuthored, 3)
    assertEquals(summary.parser.replayedCaptured, 0)
    assertEquals(summary.parser.capturedLive, 0)
    assertEquals(summary.parser.unrecorded, 47)
    assertEquals(summary.parser.foreign, 0)
    assertEquals(summary.parser.corrupt, 0)
    assertEquals(summary.liveCalls, 0)

    // Three charts reach the provider. "One night two young men went to hunt seals" (go-02) and
    // "They came down the river" (come-01) have predicate roots and are proposed. "There were
    // people at Egulac" parses to be-located-at-91, which the AMR adapter classes as
    // ConceptKind.Special, so the provider abstains with focus-not-predicate:Special even though
    // its StateFrames set names that frame (the 1.3 court's hand chart gave it Predicate kind and
    // never met the adapter's classification). The other 47 sentences have no chart. The 1.3
    // court's 4/2/1/43 came from seven hand charts and does not transfer.
    assertEquals(summary.coverage, CoverageCounts(2, 1, 0, 47))
    // Gaps: three NoProposal gaps at the abstained anchor (situation, context, membership) and one
    // trajectory step between the two emitted situations that lacks participant and temporal
    // inputs until phase 1.4 lands.
    assertEquals(summary.gaps, 4)
    assertEquals(summary.errors, 5)
    assertEquals(summary.warnings, 0)
    assertEquals(summary.validated, false)
    assertEquals(ExitStatus.of(Right(summary)), ExitStatus.Incomplete)

    val files = StoryPipeline.files(outDir)
    assertEquals(summary.files, files)
    files.all.foreach(path => assert(Files.isRegularFile(path), s"$path was not written"))

    val report = json(files.report)
    assertEquals(
      keys(report),
      Set(
        "schemaVersion",
        "mode",
        "source",
        "parser",
        "coverage",
        "gaps",
        "validation",
        "compilation",
        "model"
      )
    )
    val receipts = json(files.receipts)
    assertEquals(
      keys(receipts),
      Set(
        "schemaVersion",
        "mode",
        "parser",
        "sentences",
        "calls",
        "provenance",
        "buildReceipt",
        "buildReceiptContentChecksum"
      )
    )
    val model = json(files.model)
    assert(keys(model).contains("source"), "storymodel.json lacks the model's source")
    assert(keys(model).contains("graph"), "storymodel.json lacks the model's graph")

    val reportText = read(files.report)
    val receiptsText = read(files.receipts)
    assert(!reportText.contains("Egulac"), "the report carries source prose")
    assert(!receiptsText.contains("Egulac"), "the receipts carry source prose")
    assert(!StoryPipeline.render(summary, outDir).contains("Egulac"))

    val cursor = report.hcursor
    assertEquals(cursor.downField("validation").downField("validated").as[Boolean], Right(false))
    assertEquals(cursor.downField("validation").downField("errors").as[Int], Right(5))
    assertEquals(
      cursor.downField("coverage").downField("counts").downField("noCharts").as[Int],
      Right(47)
    )
    assertEquals(cursor.downField("parser").downField("liveCalls").as[Int], Right(0))
    assertEquals(
      cursor.downField("parser").downField("served").downField("replayedAuthored").as[Int],
      Right(3)
    )
    val gapReasons = cursor
      .downField("gaps")
      .as[Vector[Json]]
      .map(_.flatMap(_.hcursor.downField("reason").as[String].toOption).sorted)
    assertEquals(
      gapReasons,
      Right(
        Vector(
          "trajectory-inputs-unsupported:ParticipantRole,TemporalRelation",
          "unresolved:NoProposal",
          "unresolved:NoProposal",
          "unresolved:NoProposal"
        )
      )
    )
    assertEquals(
      cursor.downField("compilation").downField("fingerprint").as[String],
      Right(summary.fingerprint.hex)
    )
    assertEquals(
      cursor.downField("compilation").downField("candidateSet").as[String],
      Right(summary.candidateSet.hex)
    )
    assertEquals(
      cursor.downField("model").downField("contentChecksum").as[String],
      Right(summary.modelChecksum.hex)
    )
    assertEquals(cursor.downField("model").downField("situations").as[Int], Right(2))
    assertEquals(cursor.downField("model").downField("segments").as[Int], Right(1))

    val rows = coverageRows(report)
    assertEquals(rows.size, 50)
    assertEquals(rows.map(kindOf).take(3), Vector("abstained", "proposed", "proposed"))
    assertEquals(rows.map(kindOf).drop(3).distinct, Vector("no-chart"))
    assertEquals(
      rows.head.hcursor.downField("reason").as[String],
      Right("focus-not-predicate:Special")
    )

    val receiptCursor = receipts.hcursor
    assertEquals(receiptCursor.downField("sentences").as[Vector[Json]].map(_.size), Right(50))
    assertEquals(receiptsText.split("\"served\" : \"replayed-authored\"").length - 1, 3)
    assertEquals(receiptsText.split("\"served\" : \"unrecorded\"").length - 1, 47)
    assertEquals(
      receiptCursor.downField("buildReceiptContentChecksum").as[String],
      Right(summary.receiptChecksum.hex)
    )
    val stages = receiptCursor
      .downField("buildReceipt")
      .downField("stages")
      .as[Vector[Json]]
      .map(_.flatMap(_.hcursor.downField("stage").as[String].toOption))
    assertEquals(stages, Right(Vector(ClaudeParseDriver.Stage.value, "chart-proposal-provider")))
    val providers = receiptCursor
      .downField("calls")
      .as[Vector[Json]]
      .map(_.flatMap(_.hcursor.downField("provider").as[String].toOption).toSet)
    // The parser's call, the AMR adapter's PENMAN conversion receipt on every chart, and the
    // proposal provider's rule applications.
    assertEquals(providers, Right(Set("amr-interop", "anthropic", "chart-proposal-provider")))
    val parserStages = receiptCursor
      .downField("parser")
      .downField("stages")
      .as[Vector[Json]]
      .map(_.flatMap(_.hcursor.downField("stage").as[String].toOption))
    assertEquals(parserStages, Right(Vector(ClaudeParseDriver.Stage.value)))
  }

  test("removing one recording makes exactly that sentence NoChart and moves nothing else") {
    val dir = work("isolation")
    val textPath = wogText(dir)
    val full = build(textPath, wogRecordings, dir.resolve("full"))
    val fullRows = coverageRows(json(full.files.report))

    val outcome = parsed(textPath, wogRecordings)
    val river = outcome.batch.inputs(2)
    assertEquals(river.id.value, "s0002")
    val riverKey = outcome.recordingKeys(2)

    val copy = copyRecordings(wogRecordings, dir.resolve("recordings"))
    val store = Recordings.open(copy).fold(error => fail(error.message), identity)
    assert(store.contains(riverKey), "the third recording is not the river sentence's")
    Files.delete(store.path(riverKey))

    val mutated = build(textPath, copy, dir.resolve("mutated"))
    assertEquals(mutated.coverage, CoverageCounts(1, 1, 0, 48))
    assertEquals(mutated.charts, 2)
    assertEquals(mutated.parser.replayedAuthored, 2)
    assertEquals(mutated.parser.unrecorded, 48)
    assertEquals(mutated.parser.transportFailures, 48)
    assertEquals(mutated.liveCalls, 0)
    // The abstained anchor's three gaps survive; the trajectory gap needed two situations.
    assertEquals(mutated.gaps, 3)
    assertEquals(mutated.validated, false)

    val mutatedRows = coverageRows(json(mutated.files.report))
    assertEquals(mutatedRows.size, 50)
    val changed = fullRows.zip(mutatedRows).zipWithIndex.collect {
      case ((before, after), index) if before != after => (index, before, after)
    }
    assertEquals(changed.map(_._1), Vector(2), s"rows other than the river changed: $changed")
    val (_, before, after) = changed.head
    assertEquals(kindOf(before), "proposed")
    assertEquals(kindOf(after), "no-chart")
    assertEquals(after.hcursor.downField("sentence").as[String], Right(river.sentenceId.value))
  }

  test("a missing recordings directory is refused before any file is written") {
    val dir = work("missing")
    val absent = dir.resolve("absent")
    val outDir = dir.resolve("out")
    val outcome =
      StoryPipeline.run(DriverMode.Replay, wogText(dir), absent, outDir, Map.empty, Now)
    assertEquals(
      outcome,
      Left(
        PipelineError.NotStarted(
          DriverError.RecordingsUnavailable(RecordingsError.Missing(absent.toString))
        )
      )
    )
    assertEquals(ExitStatus.of(outcome), ExitStatus.CouldNotStart)
    assert(!Files.exists(outDir), "the output directory was created despite the refusal")
    assert(!Files.exists(absent), "replay created the recordings directory")
  }

  test("record mode without the environment opt-in is refused before touching disk") {
    val dir = work("record")
    val recordingsDir = dir.resolve("recordings")
    val outDir = dir.resolve("out")
    val outcome = StoryPipeline.run(
      DriverMode.Record,
      wogText(dir),
      recordingsDir,
      outDir,
      Map(AgentCredentials.PrimaryKeyVariable -> "not-a-real-key"),
      Now
    )
    assertEquals(
      outcome,
      Left(
        PipelineError.NotStarted(
          DriverError.LiveRefused(
            LiveRefusal.LiveFlagAbsent(AgentCredentials.LiveVariable, AgentCredentials.LiveValue)
          )
        )
      )
    )
    assertEquals(ExitStatus.of(outcome), ExitStatus.CouldNotStart)
    assert(!Files.exists(outDir), "the output directory was created despite the refusal")
    assert(!Files.exists(recordingsDir), "the recordings directory was created")
  }

  test("two replay runs write byte-identical storymodel.json with one fingerprint") {
    val dir = work("determinism")
    val textPath = wogText(dir)
    val first = build(textPath, wogRecordings, dir.resolve("one"))
    val second = build(textPath, wogRecordings, dir.resolve("two"))
    assertEquals(second.fingerprint, first.fingerprint)
    assertEquals(second.modelChecksum, first.modelChecksum)
    assertEquals(second.receiptChecksum, first.receiptChecksum)
    assert(
      java.util.Arrays.equals(
        Files.readAllBytes(first.files.model),
        Files.readAllBytes(second.files.model)
      ),
      "storymodel.json differs between two replays of the same inputs"
    )
    assertEquals(read(second.files.report), read(first.files.report))
    assertEquals(read(second.files.receipts), read(first.files.receipts))
  }

  test("the written storymodel.json decodes to the model's content checksum") {
    val dir = work("roundtrip")
    val summary = build(wogText(dir), wogRecordings, dir.resolve("out"))
    val decoded = StoryModelCodec
      .decode(read(summary.files.model))
      .fold(error => fail(error.toString), identity)
    assertEquals(StoryModelCodec.contentChecksum(decoded), summary.modelChecksum)
    assertEquals(decoded.graph.situations.size, 2)
    assertEquals(decoded.receipt.map(_.contentChecksum), Some(summary.receiptChecksum))
  }

  test("exit status: not started is 2, every other refusal is 1, a clean run is 0") {
    assertEquals(ExitStatus.CouldNotStart.code, 2)
    assertEquals(ExitStatus.Incomplete.code, 1)
    assertEquals(ExitStatus.Complete.code, 0)
    val notStarted = PipelineError.NotStarted(DriverError.UnknownMode("nope"))
    assertEquals(ExitStatus.of(Left(notStarted)), ExitStatus.CouldNotStart)
    val invariant = DomainError.InvariantViolation("pipeline-test", "synthetic")
    assertEquals(
      ExitStatus.of(Left(PipelineError.ProposalRefused(invariant))),
      ExitStatus.Incomplete
    )
    val compilerError = NarrativeCompilerError.InvalidInput(NonEmptyVector.one(invariant))
    assertEquals(
      ExitStatus.of(Left(PipelineError.InputRefused(compilerError))),
      ExitStatus.Incomplete
    )
    assertEquals(
      ExitStatus.of(Left(PipelineError.CompileRefused(compilerError))),
      ExitStatus.Incomplete
    )
    assertEquals(
      ExitStatus.of(Left(PipelineError.OutputUnwritable("x", Checksum.ofText("y")))),
      ExitStatus.Incomplete
    )
    assertEquals(DriverMode.parse("nope"), Left(DriverError.UnknownMode("nope")))
  }

  test("a run whose every sentence reached the court exits 0") {
    val dir = work("complete")
    val textPath = writeText(dir, "three.txt", threeText)
    val outDir = dir.resolve("out")
    val summary = build(textPath, threeRecordings, outDir)
    assertEquals(summary.sentences, 3)
    assertEquals(summary.charts, 3)
    assertEquals(summary.parser.transportFailures, 0)
    assertEquals(summary.parser.replayedAuthored, 3)
    assertEquals(summary.coverage, CoverageCounts(2, 1, 0, 0))
    assertEquals(summary.gaps, 4)
    assertEquals(summary.validated, false)
    assertEquals(ExitStatus.of(Right(summary)), ExitStatus.Complete)
    val line = StoryPipeline.render(summary, outDir)
    assert(line.contains("sentences=3 charts=3 proposed=2 abstained=1"), line)
    assert(line.contains("transportFailures=0"), line)
    assert(line.contains("liveCalls=0"), line)
    assert(!line.contains("Egulac"), line)
  }
