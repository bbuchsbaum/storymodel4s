package storymodel4s.pipeline

import java.nio.file.Files
import munit.FunSuite
import storymodel4s.core.Checksum

/** Diagnostic-only secret markers are synthetic controls, never participant text. */
class FacadeFailureSuite extends FunSuite:
  test("typed read failures retain path and class while log diagnostics redact filenames") {
    val root = Files.createTempDirectory("facade-failure-")
    try
      val missing = root.resolve("PRIVATE_MARKER.json")
      val result = FacadeFailure.read(missing)
      assertEquals(
        result,
        Left(
          FacadeFailure.Io(
            FacadeFailure.Operation.Read,
            missing,
            "java.nio.file.NoSuchFileException"
          )
        )
      )
      val error = result.swap.toOption.get
      assertEquals(
        error.diagnostic.hcursor.get[String]("path_sha256").toOption,
        Some(Checksum.ofText(missing.toString).hex)
      )
      assert(!error.diagnostic.noSpaces.contains("PRIVATE_MARKER"))
      val present = root.resolve("present")
      val _ = Files.writeString(present, "control")
      assert(FacadeFailure.read(present).isRight)
      Files.delete(present)
    finally Files.delete(root)
  }

  test("invalid path and malformed JSON never echo configuration prose") {
    var errors = Vector.empty[String]
    assertEquals(
      MappingCli
        .run(List("run", "unused", "PRIVATE_MARKER\u0000", "unused"), _ => (), s => errors :+= s),
      2
    )
    assert(!errors.mkString.contains("PRIVATE_MARKER"))
    assertEquals(MappingCli.config("{PRIVATE_MARKER"), Left("invalid configuration"))
  }

  test("mapping export read failure is typed and CLI redacts the private filename") {
    val root = Files.createTempDirectory("facade-export-")
    try
      val input = root.resolve("PRIVATE_MARKER.json")
      val id = storymodel4s.view.ArtifactId.from("policy").toOption.get
      val result = MappingExchangeBuild.exportWorkspace(input, id, root.resolve("out"))
      assertEquals(
        result,
        Left(
          MappingExchangeBuild.Error.Io(
            FacadeFailure.Io(
              FacadeFailure.Operation.Read,
              input,
              "java.nio.file.NoSuchFileException"
            )
          )
        )
      )
      var errors = Vector.empty[String]
      assertEquals(
        MappingExchangeCli.run(
          List(input.toString, "policy", root.resolve("out").toString),
          _ => fail("unexpected success"),
          s => errors :+= s
        ),
        2
      )
      assert(errors.mkString.contains("NoSuchFileException"))
      assert(!errors.mkString.contains("PRIVATE_MARKER"))
      assert(!Files.exists(root.resolve("out")))
    finally Files.delete(root)
  }

  test("story build messages digest free text and preserve typed refusal kinds") {
    val marker = "PRIVATE_MARKER"
    val errors = Vector(
      PipelineError.NotStarted(storymodel4s.provider.agent.DriverError.UnknownMode(marker)),
      PipelineError.ReceiptMismatch(marker),
      PipelineError.FeatureRefused(marker),
      PipelineError.OutputUnwritable(marker, Checksum.ofText(marker), "java.io.IOException"),
      PipelineError.ProposalRefused(
        storymodel4s.core.DomainError.InvariantViolation(marker, marker)
      )
    )
    errors.foreach(e => assert(!e.message.contains(marker), e.message))
    assert(errors.head.message.contains("UnknownMode"))
    assert(errors(3).message.contains("java.io.IOException"))
  }
