package storymodel4s.pipeline

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.corpus.intake.{ScannerCrosswalk, ScannerCrosswalkJson}

object ScannerSampleFixture:
  import ScannerCrosswalk.*
  val evidence = Checksum.ofText("synthetic-sample-job")
  val run: Run = Run
    .regular(
      RunKey("synthetic", "v1", "p1", None, "recall", "1"),
      evidence,
      evidence,
      8,
      ExactRational.of(3, 2).toOption.get,
      Origin.FirstStoredSample,
      AppliedHistory.Unknown
    )
    .toOption
    .get
  val layout: Layout = Layout
    .declared(
      run,
      evidence,
      5,
      Vector(
        Slot.Padding("leading"),
        Slot.Padding("leading"),
        Slot.Acquired(2, Censoring.Included),
        Slot.Acquired(4, Censoring.Censored(evidence)),
        Slot.Acquired(7, Censoring.Unknown)
      ),
      evidence
    )
    .toOption
    .get
  def job: Json = Json.obj(
    "schemaVersion" -> Json.fromString(ScannerSampleBuild.JobSchema),
    "run" -> ScannerCrosswalkJson.runDeclaration(run),
    "layout" -> Json.fromJsonObject(
      ScannerCrosswalkJson.layoutDeclaration(layout).asObject.get.remove("run_digest")
    )
  )
  def main(args: Array[String]): Unit =
    require(args.length == 1, "output job path required")
    Files.writeString(Path.of(args(0)), job.spaces2 + "\n", StandardOpenOption.CREATE_NEW): Unit

class ScannerSampleBuildSuite extends FunSuite:
  test("offline sample command emits exact original indices and never overwrites output") {
    val root = Files.createTempDirectory("scanner-sample-test-")
    val input = root.resolve("job.json")
    val output = root.resolve("samples.json")
    Files.writeString(input, ScannerSampleFixture.job.noSpaces): Unit
    val written = ScannerSampleBuild.run(input, output).toOption.get
    val bytes = Files.readAllBytes(output)
    assertEquals(written.artifact, Checksum.ofBytes(bytes))
    val json = io.circe.parser.parse(new String(bytes, StandardCharsets.UTF_8)).toOption.get
    val run =
      ScannerCrosswalkJson.decodeRun(json.hcursor.downField("run").focus.get.noSpaces).toOption.get
    val layout = ScannerCrosswalkJson
      .decodeLayout(json.hcursor.downField("layout").focus.get.noSpaces, run)
      .toOption
      .get
    assertEquals(layout.sample(0), Right(None))
    assertEquals(layout.sample(2).toOption.get.get.seconds, ExactRational.integer(3L))
    assertEquals(
      json.hcursor.downField("scanner_binding").get[String]("status"),
      Right("unestablished")
    )
    assertEquals(
      ScannerSampleBuild.run(input, output),
      Left(ScannerSampleBuild.Refusal.OutputExists)
    )
    assertEquals(Files.readAllBytes(output).toVector, bytes.toVector)
  }
  test("malformed jobs and partial output cannot publish an artifact") {
    val root = Files.createTempDirectory("scanner-sample-refusal-")
    val input = root.resolve("job.json")
    val output = root.resolve("samples.json")
    val valid = ScannerSampleFixture.job.noSpaces
    Vector(
      "{\"schemaVersion\":\"wrong\"," + valid.drop(1),
      valid.replace("\"sample_count\":8", "\"sample_count\":7"),
      valid.dropRight(1) + ",\"unknown\":null}"
    ).foreach { content =>
      Files.writeString(input, content): Unit
      assert(ScannerSampleBuild.run(input, output).isLeft)
      assert(!Files.exists(output))
    }
    val result = ScannerSampleBuild.publish(
      output,
      "{}".getBytes(StandardCharsets.UTF_8),
      (path, _) => {
        Files.writeString(path, "{"): Unit
        throw new java.io.IOException("injected")
      }
    )
    assertEquals(result, Left(ScannerSampleBuild.Refusal.OutputWrite))
    assert(!Files.exists(output))
  }
