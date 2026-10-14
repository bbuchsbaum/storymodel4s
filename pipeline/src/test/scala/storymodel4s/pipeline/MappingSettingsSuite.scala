package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.core.SurfaceUnitKind
import storymodel4s.embed.Sensitivity
import storymodel4s.embed.onnx.OnnxMappingFixture

/** Native fixture execution separates a local file configuration from claimed provider authority.
  */
class MappingSettingsSuite extends FunSuite:
  private def right[A, E](value: Either[E, A]): A =
    value.fold(e => fail(s"fixture refused: $e"), identity)

  private def inputs(): Path =
    val root = Files.createTempDirectory("mapping-settings-")
    for name <- Vector("model.onnx", "tokenizer.json") do
      val source = Path.of(OnnxMappingFixture.getClass.getResource("/fixture/" + name).toURI)
      Files.copy(source, root.resolve(name)): Unit
    Files.write(root.resolve("embedding.key"), Array.tabulate[Byte](32)(_.toByte)): Unit
    Files.writeString(root.resolve("story.txt"), "hello world"): Unit
    Files.writeString(root.resolve("recall.txt"), "Anna arrived."): Unit
    root

  private def prepared(root: Path, failedSibling: Boolean = false) =
    val good = StudyPreparation.RecallInput(
      "synthetic",
      "one",
      "recall.txt",
      admission = StudyPreparation.Admission.ProjectSyntheticControl
    )
    val recalls =
      if failedSibling then Vector(good, good.copy(session = "two", path = "absent.txt"))
      else Vector(good)
    right(
      StudyPreparation.prepare(
        root,
        StudyPreparation.Config(
          StudyPreparation.SourceInput.Text("story.txt", SurfaceUnitKind.Clause),
          recalls,
          StudyPreparation.RecallProfile
        )
      )
    )

  private def changed(f: Json => Json): String =
    f(right(io.circe.parser.parse(MappingCli.OnnxTemplate))).noSpaces

  test(
    "ordinary configuration executes keyed ONNX attempts and both members with failed siblings"
  ) {
    val root = inputs()
    val settings = right(MappingSettings.decode(MappingCli.OnnxTemplate, root))
    val batch = right(settings.execute(prepared(root, true), OnnxMappingFixture.model(32)))
    assertEquals(batch.outcomes.size, 2)
    assertEquals(batch.outcomes.count(_.run.nonEmpty), 1)
    assert(batch.outcomes(1).failure.nonEmpty)
    val run = batch.outcomes.head.run.get
    val registration = run.onnx.get
    assertEquals(registration.config.querySensitivity, Sensitivity.Sensitive)
    assertEquals(registration.config.documentSensitivity, Sensitivity.Sensitive)
    assertEquals(registration.queryRecord.result.receipt.providerCalls.size, 1)
    assertEquals(registration.documentRecord.result.receipt.providerCalls.size, 1)
    assert(run.execution.reference.evidence eq registration.evidence)
    assert(run.execution.reconstruction.evidence eq registration.evidence)
    assertEquals(run.reference.inventory, run.reconstruction.inventory)
    val (files, manifest) = right(MappingPipeline.exchangeArtifacts(batch))
    assert(files.nonEmpty)
    assert(!manifest.contains(root.toString))
    assert(!manifest.contains("embedding.key"))
  }

  test("key rotation changes execution and batch identity while relocation preserves them") {
    val first = inputs()
    val second = inputs()
    def run(root: Path) = right(
      right(MappingSettings.decode(MappingCli.OnnxTemplate, root))
        .execute(prepared(root), OnnxMappingFixture.model(32))
    )
    val original = run(first)
    assertEquals(run(second).identity, original.identity)
    Files.write(second.resolve("embedding.key"), Array.fill[Byte](32)(42)): Unit
    val rotated = run(second)
    assertNotEquals(rotated.identity, original.identity)
    assertNotEquals(
      rotated.outcomes.head.run.get.registration,
      original.outcomes.head.run.get.registration
    )
  }

  test("ordinary timed annotations reach actual ONNX registration with original physical support") {
    val root = inputs()
    Files.writeString(
      root.resolve("parts.json"),
      """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
    ): Unit
    Files.writeString(
      root.resolve("annotations.csv"),
      TimedAnnotationInput.Header.mkString(",") + "\n" +
        "1,hello world,a,10,20,1,scene,,\n" +
        "2,hello,a,30,,1,scene,,\n"
    ): Unit
    val original = prepared(root)
    val study = right(
      StudyPreparation.prepare(
        root,
        original.config.copy(
          source = StudyPreparation.SourceInput.TimedAnnotations("annotations.csv", "parts.json")
        )
      )
    )
    val settings = right(MappingSettings.decode(MappingCli.OnnxTemplate, root))
    val batch = right(settings.execute(study, OnnxMappingFixture.model(32)))
    val run = batch.outcomes.head.run.getOrElse(fail(batch.outcomes.head.failure.toString))
    val context = run.onnx.get.context
    assertEquals(context.policy, storymodel4s.align.SurfaceRendering.TimedPolicy)
    assertEquals(context.documents.map(_._2.text), Vector("hello world", "hello", "scene"))
    assertEquals(run.onnx.get.config.selectedLevel, Some(0))
    assertEquals(context.representation, study.source.representation)
    assert(context.binds(study.outcomes.head.result.toOption.get.graph, study.source))
    assertEquals(run.reference.inventory, run.reconstruction.inventory)
  }

  test("production catalog refuses fixture artifacts instead of accepting caller model identity") {
    val root = inputs()
    val settings = right(MappingSettings.decode(MappingCli.OnnxTemplate, root))
    val result = settings.execute(prepared(root))
    assertEquals(
      result,
      Left(
        FacadeFailure.Mapping(
          MappingPipeline.Refusal.MissingCapability("onnx-startup:ArtifactChecksumMismatch")
        )
      )
    )
  }

  test("missing short and external keys refuse and diagnostics never echo secret text") {
    val root = inputs()
    val settings = right(MappingSettings.decode(MappingCli.OnnxTemplate, root))
    Files.write(root.resolve("embedding.key"), Array.fill[Byte](31)(9)): Unit
    assertEquals(
      settings.execute(prepared(root), OnnxMappingFixture.model(32)),
      Left(FacadeFailure.InvalidConfiguration)
    )
    Files.delete(root.resolve("embedding.key"))
    assert(MappingSettings.decode(MappingCli.OnnxTemplate, root).isLeft)
    val outside = inputs().resolve("embedding.key")
    val absolute = changed(j =>
      j.mapObject(
        _.add(
          "onnx",
          j.hcursor
            .downField("onnx")
            .focus
            .get
            .mapObject(_.add("keyFile", Json.fromString(outside.toString)))
        )
      )
    )
    assertEquals(MappingSettings.decode(absolute, root), Left(FacadeFailure.InvalidConfiguration))
    Files.createSymbolicLink(root.resolve("embedding.key"), outside): Unit
    assertEquals(
      MappingSettings.decode(MappingCli.OnnxTemplate, root),
      Left(FacadeFailure.InvalidConfiguration)
    )
    assert(!FacadeFailure.InvalidConfiguration.diagnostic.noSpaces.contains(outside.toString))
  }

  test("duplicate unknown or profile-incompatible resource fields fail closed") {
    val root = inputs()
    val variants = Vector(
      MappingCli.OnnxTemplate.replace("\"model\":", "\"model\":\"duplicate\",\"model\":"),
      changed(_.mapObject(_.add("unknown", Json.True))),
      changed(j =>
        j.mapObject(
          _.add(
            "onnx",
            j.hcursor
              .downField("onnx")
              .focus
              .get
              .mapObject(_.add("checksum", Json.fromString("self-asserted")))
          )
        )
      ),
      MappingCli.OnnxTemplate.replace(MappingPipeline.OnnxProfile, MappingPipeline.LexicalProfile),
      MappingCli.Template.replace(MappingPipeline.LexicalProfile, MappingPipeline.OnnxProfile)
    )
    variants.foreach(v => assert(MappingSettings.decode(v, root).isLeft))
    assert(MappingSettings.decode(MappingCli.Template, root).isRight)
  }
