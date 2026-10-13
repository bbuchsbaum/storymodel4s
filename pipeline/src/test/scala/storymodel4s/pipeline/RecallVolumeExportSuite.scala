package storymodel4s.pipeline

import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.codec.Canonical
import storymodel4s.core.*
import storymodel4s.corpus.intake.{RecallTimingIntake, ScannerCrosswalk, ScannerCrosswalkJson}
import storymodel4s.recall.*
import storymodel4s.recall.RecallTiming.*

class RecallVolumeExportSuite extends FunSuite:
  private val columns = RecallTimingIntake.Columns(Vector("word", "onset"), 0, 1,
    ClockKey.unsafe("released-seconds"))
  private def intake(csv: String, text: String, unitEnd: Option[Int] = None) =
    val source = StorySource.fromText(text).toOption.get
    val unit = RecallUnit(RecallUnitId.unsafe("u"), 0,
      SpanSet.one(TextSpan.unsafe(0, unitEnd.getOrElse(text.length))), "synthetic",
      DiscourseFunction.EpisodicAssertion, ExpressedUncertainty.Unmarked, PropositionSketch.empty, None)
    val graph = RecallGraph.validated(source, SurfaceAnalyzer.analyze(source), Vector(unit), RecallRelations.empty).toOption.get
    val inventory = RecallInventory.of(graph, SurfaceSequence(graph.atlas).lexicalTokens.map(_.span),
      WordIdPolicy.inputArtifact(Checksum.ofText("parser"))).toOption.get
    val bytes = csv.getBytes(UTF_8)
    (RecallTimingIntake.readLexical(bytes, Checksum.ofBytes(bytes), columns, graph, inventory),
      RecallTimingIntake.read(bytes, Checksum.ofBytes(bytes), columns, graph, inventory))
  private def run(count: Int, period: ExactRational = ExactRational.One) =
    ScannerCrosswalk.Run.regular(ScannerCrosswalk.RunKey("synthetic", "v1", "p", None, "recall", "1"),
      Checksum.ofText("image"), Checksum.ofText("header"), count, period,
      ScannerCrosswalk.Origin.FirstStoredSample, ScannerCrosswalk.AppliedHistory.Unknown).toOption.get

  test("punctuation and supplementary Unicode preserve positional word correspondence") {
    val (result, old) = intake("word,onset\n😀alpha,0\nalpha.,1.5\n,2\n", "😀alpha alpha.")
    val value = result.toOption.get
    assertEquals(value.timing.inventory.words.map(_.span.start), Vector(2, 8))
    assertEquals(value.records.size, 3)
    assertEquals(value.records.last.binding, RecallTimingIntake.RecordBinding.ExcludedBlankWord)
    assertEquals(value.timing.entries.map(_.observation).collect { case Observation.OnsetOnly(t, _) => t },
      Vector(ExactRational.Zero, ExactRational.of(3, 2).toOption.get))
    assertEquals(old, Left(RecallTimingIntake.Refusal.WordSpansMismatch))
  }
  test("single lexical word recipe has a distinct correspondence even without punctuation") {
    val (a, b) = intake("word,onset\nalpha,0\n", "alpha")
    assertNotEquals(a.toOption.get.timing.provenance.correspondence, b.toOption.get.timing.provenance.correspondence)
  }
  test("balanced global counts cannot hide multiword and punctuation-only cells") {
    assertEquals(intake("word,onset\nalpha beta,0\n!,1\n", "alpha beta !")._1,
      Left(RecallTimingIntake.Refusal.WordSpansMismatch))
    assertEquals(intake("word,onset\nalpha/beta,0\n", "alpha/beta")._1,
      Left(RecallTimingIntake.Refusal.WordSpansMismatch))
    assertEquals(intake("word,onset\n!,0\n", "!")._1,
      Left(RecallTimingIntake.Refusal.WordSpansMismatch))
  }
  test("excluded blank records still validate numeric observations") {
    assert(intake("word,onset\nalpha,0\n,NaN\n", "alpha")._1.isLeft)
  }
  test("half-open bins preserve negative and exact final boundary missingness") {
    val p = ExactRational.of(3, 2).toOption.get
    assertEquals(RecallVolumeExport.index(ExactRational.Zero, p, 716), Some(0))
    assertEquals(RecallVolumeExport.index(p, p, 716), Some(1))
    assertEquals(RecallVolumeExport.index(ExactRational.of(2147, 2).toOption.get, p, 716), Some(715))
    assertEquals(RecallVolumeExport.index(ExactRational.of(1074, 1).toOption.get, p, 716), None)
    assertEquals(RecallVolumeExport.index(ExactRational.of(-1, 10).toOption.get, p, 716), None)
  }
  test("four word observations create two distinct non-additive unit attachments") {
    val text = "alpha beta gamma delta epsilon"
    val value = intake("word,onset\nalpha,0\nbeta,.1\ngamma,1.5\ndelta,3\nepsilon,\n,4\n",
      text, Some(text.indexOf(" epsilon")))._1.toOption.get
    val artifact = RecallVolumeExport.attachments(value, run(2, ExactRational.of(3, 2).toOption.get),
      ExactRational.of(3, 2).toOption.get)
    val rows = artifact.hcursor.get[Vector[Json]]("records").toOption.get
    assertEquals(rows.size, 6)
    assertEquals(rows(3).hcursor.get[String]("timing_status").toOption.get, "outside-stored-coverage")
    assertEquals(rows(4).hcursor.get[String]("timing_status").toOption.get, "missing-onset")
    assertEquals(rows(4).hcursor.downField("unit").focus.get, Json.Null)
    assertEquals(rows.last.hcursor.get[String]("correspondence").toOption.get, "excluded-blank-word")
    assertEquals(artifact.hcursor.get[Vector[Json]]("volume_units").toOption.get.size, 2)
    val unit = artifact.hcursor.get[Vector[Json]]("units").toOption.get.head
    assertEquals(unit.hcursor.get[Int]("assigned").toOption.get, 3)
    assertEquals(unit.hcursor.get[Int]("outside").toOption.get, 1)
    assertEquals(unit.hcursor.get[String]("attachment").toOption.get, "partial")
    assertEquals(artifact.hcursor.get[String]("temporal_mass_allocation").toOption.get, "unavailable-onset-only")
  }

  private[pipeline] def fixture(root: Path): Path =
    def put(name: String, value: String): Unit = { Files.writeString(root.resolve(name), value, UTF_8): Unit }
    def pin(name: String): Json = Json.obj("path" -> Json.fromString(name),
      "sha256" -> Json.fromString(Checksum.ofBytes(Files.readAllBytes(root.resolve(name))).hex))
    put("source.txt", "Anna arrived.")
    put("recall.txt", "Anna arrived.")
    put("clock.csv", "word,onset\nAnna,0\narrived.,1\n,-1\n")
    put("config.json", MappingCli.Template)
    put("study.json", """{"schemaVersion":"storymodel-study/v1","source":{"format":"text","path":"source.txt","kind":"Sentence"},"profile":"recall-baseline-lexical-words/v1","recalls":[{"participant":"p","session":"s","path":"recall.txt","timing":null,"admission":{"kind":"project-synthetic-control"}}]}""")
    val declaration = ScannerCrosswalkJson.runDeclaration(run(1))
    val job = Json.obj("schemaVersion" -> Json.fromString(RecallVolumeExport.JobSchema),
      "study" -> pin("study.json"), "config" -> pin("config.json"),
      "participant" -> Json.fromString("p"), "session" -> Json.fromString("s"),
      "csv" -> pin("clock.csv"), "columns" -> Json.obj(
        "header" -> Json.arr(Json.fromString("word"), Json.fromString("onset")),
        "word" -> Json.fromInt(0), "onset" -> Json.fromInt(1), "clock" -> Json.fromString("released-seconds")),
      "run" -> declaration, "period" -> Json.obj("numerator" -> Json.fromString("1"), "denominator" -> Json.fromString("1")),
      "recording" -> Json.obj("participant" -> Json.fromString("p"), "session" -> Json.fromString("s"),
        "csv_sha256" -> pin("clock.csv").hcursor.downField("sha256").focus.get,
        "clock" -> Json.fromString("released-seconds"), "run_key" -> declaration.hcursor.downField("key").focus.get,
        "basis" -> Json.fromString("synthetic-control"), "evidence" -> Json.arr(pin("source.txt"))))
    put("job.json", Canonical.print(job))
    root.resolve("job.json")

  test("public export retains paired exchanges and refuses overwrite and wrong binding") {
    val root = Files.createTempDirectory("recall-volumes-")
    val job = fixture(root)
    val output = root.resolve("output")
    assert(RecallVolumeExport.run(job, output).isRight)
    assert(RecallVolumeExport.run(job, output).isLeft)
    val manifest = Canonical.parse(Files.readString(output.resolve("manifest.json"))).toOption.get
    assertEquals(manifest.hcursor.get[String]("schemaVersion").toOption.get, RecallVolumeExport.Schema)
    assert(Files.exists(output.resolve("mapping-exchange-manifest.json")))
    val original = Canonical.parse(Files.readString(job)).toOption.get
    val changed = original.mapObject(o => o.add("recording", o("recording").get.mapObject(_.add("participant", Json.fromString("wrong")))))
    Files.writeString(job, Canonical.print(changed)): Unit
    assertEquals(RecallVolumeExport.run(job, root.resolve("wrong")), Left("recording-binding-mismatch"))
    assert(!Files.exists(root.resolve("wrong")))
  }

object RecallVolumeFixture:
  def main(args: Array[String]): Unit =
    val root = Path.of(args(0))
    Files.createDirectories(root): Unit
    val suite = new RecallVolumeExportSuite
    val job = suite.fixture(root)
    println(RecallVolumeExport.run(job, root.resolve("output")).map(_.hex))
