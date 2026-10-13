package storymodel4s.pipeline

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.{OrganizationCodecs, Canonical}

/** Source: project-authored Anna/Bob synthetic controls, 2026, Toronto, MappingPipelineSuite.
  * Basis: original project contribution under repository Apache-2.0; no participant material.
  * Modifications: reverse the same two sentences for backward recall; omit Bob for the singleton
  * foreign-segmentation control. All versions remain explicitly synthetic fiction. Tradition: none;
  * fictional diagnostic sentences, no ethnographic material. Admitted by: Codex independent
  * reviewer /root/organization_review, 2026-10-13, checked against organization-language/v1 and the
  * original project controls; no narrator.
  */
class OrganizationPipelineSuite extends FunSuite:
  private def right[E, A](v: Either[E, A]): A = v.fold(e => fail(e.toString), identity)
  private val config = right(MappingCli.config(MappingCli.Template))
  private def inTemp(f: Path => Unit): Unit =
    val root = Files.createTempDirectory("organization-pipeline-")
    try f(root)
    finally
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(p => { Files.deleteIfExists(p): Unit })
      finally stream.close()
  private def write(root: Path, name: String, text: String): Unit = {
    Files.writeString(root.resolve(name), text): Unit
  }
  private def prepared(root: Path): MappingPipeline.Batch =
    write(root, "source.txt", "Anna arrived. Bob left.")
    write(root, "recall.txt", "Bob left. Anna arrived.")
    write(root, "mapping.json", MappingCli.Template)
    write(
      root,
      "study.json",
      StudyCli.Template.replace(
        "\"timing\":null",
        "\"timing\":null,\"admission\":{\"kind\":\"project-synthetic-control\"}"
      )
    )
    val batch = right(MappingPipeline.analyze(root.resolve("study.json"), config))
    assert(batch.outcomes.head.run.nonEmpty, batch.outcomes.head.failure.toString)
    batch
  private def result(run: MappingPipeline.Run): MappingPipeline.Organization =
    right(
      MappingPipeline.organization(
        run,
        right(OrganizationPipeline.request(run, MeasurementCompatibility.Axis.Discourse))
      )
    )
  private def files(root: Path): Vector[(String, String)] =
    val stream = Files.list(root)
    try stream.iterator().asScala.toVector.map(p => p.getFileName.toString -> Files.readString(p))
    finally stream.close()

  test("genuine lexical producer certifies original units while lower-level record stays unknown") {
    inTemp { root =>
      val run = prepared(root).outcomes.head.run.get
      val value = result(run)
      assertEquals(value.verdict, MeasurementCompatibility.Verdict.CompatibleWithDeclaredPolicy)
      assertEquals(
        MeasurementCompatibility.inspect(run.reference, value.request).verdict,
        MeasurementCompatibility.Verdict.UnknownProvenance
      )
      assertEquals(
        MeasurementCompatibility.inspect(run.reconstruction, value.request).verdict,
        MeasurementCompatibility.Verdict.ModelDependentOnly
      )
      assertEquals(value.result.rows.map(_.unit), run.execution.inventory.units.map(_.id))
      assertEquals(value.result.transitions.size, run.execution.inventory.units.size - 1)
      assertEquals(value.stages.map(_.stage), Stage.values.toVector)
      assert(value.stages.forall(_.inputs.contains(run.registration)))
      assertEquals(
        right(OrganizationCodecs.decode(OrganizationCodecs.encode(value.result), value.result)),
        value.result
      )
      assert(
        OrganizationCodecs
          .decode(
            OrganizationCodecs.encode(value.result).replace("conditional mass", "calibrated"),
            value.result
          )
          .isLeft
      )
    }
  }
  test("exact zero succeeds every positive structural weight and reconstruction refuse") {
    inTemp { root =>
      val run = prepared(root).outcomes.head.run.get
      val request = result(run).request
      val zero = right(run.execution.weighted(right(StructuralWeight.of(0L, 1L))))
      assert(MappingPipeline.organization(run, request, MappingRun.Member.Weighted(zero)).isRight)
      Vector((1L, 1000000000000L), (1L, 1L)).foreach { (n, d) =>
        val selection = right(run.execution.weighted(right(StructuralWeight.of(n, d))))
        assertEquals(
          MappingPipeline.organization(run, request, MappingRun.Member.Weighted(selection)),
          Left(MeasurementCompatibility.Reason.StructuredInference)
        )
      }
      assertEquals(
        MappingPipeline.organization(run, request, MappingRun.Member.Reconstruction),
        Left(MeasurementCompatibility.Reason.StructuredInference)
      )
    }
  }
  test("foreign segmentation and wrong target resolution refuse without relabeling") {
    inTemp { root =>
      val run = prepared(root).outcomes.head.run.get
      write(root, "recall.txt", "Anna arrived.")
      val other =
        right(MappingPipeline.analyze(root.resolve("study.json"), config)).outcomes.head.run.get
      assertEquals(
        MappingPipeline.organization(run, result(other).request),
        Left(MeasurementCompatibility.Reason.ForeignSegmentation)
      )
      val bad = right(
        MeasurementCompatibility.Request
          .of(MeasurementCompatibility.Axis.Discourse, run.execution.inventory.segmentation, 1)
      )
      assertEquals(
        MappingPipeline.organization(run, bad),
        Left(MeasurementCompatibility.Reason.WrongResolution)
      )
    }
  }
  test("unknown story-world order is incomparable and zero denominator stays unavailable") {
    inTemp { root =>
      val run = prepared(root).outcomes.head.run.get
      val r = right(
        MappingPipeline.organization(
          run,
          right(OrganizationPipeline.request(run, MeasurementCompatibility.Axis.StoryWorld))
        )
      ).result
      assert(r.counts(OrganizationReadout.Category.Incomparable) > 0.0)
      assertEquals(r.order.positions, Map.empty[SourceNodeRef, Double])
      assert(r.counts(OrganizationReadout.Category.SameTarget) >= 0.0)
    }
  }
  test(
    "contextual export preserves pair and refuses origin verdict member axis and data tampering"
  ) {
    inTemp { root =>
      val run = prepared(root).outcomes.head.run.get
      val value = result(run)
      val output = root.resolve("export")
      val digest = right(OrganizationPipeline.publish(value, output))
      assertEquals(right(OrganizationPipeline.publish(value, output, true)), digest)
      val original = files(output)
      assertEquals(right(OrganizationPipeline.verify(value, original)), value)
      assert(original.exists(_._1 == "mapping--manifest.json"))
      val replacements = Vector(
        "CompatibleWithDeclaredPolicy" -> "UnknownProvenance",
        "Discourse" -> "StoryWorld",
        "Reference" -> "Reconstruction",
        run.registration.hex -> ("0" * 64)
      )
      replacements.foreach { (old, replacement) =>
        val mutant = original.map((name, text) =>
          name -> (if name == "compatibility.json" then text.replace(old, replacement) else text)
        )
        assert(mutant != original)
        assert(OrganizationPipeline.verify(value, mutant).isLeft)
      }
      assert(
        OrganizationPipeline.verify(value, original.filterNot(_._1 == "transitions.tsv")).isLeft
      )
      assert(OrganizationPipeline.verify(value, original :+ original.head).isLeft)
      write(output, "transitions.tsv", "corrupt")
      assert(OrganizationPipeline.publish(value, output, true).isLeft)
    }
  }
  test(
    "ordinary CLI has offline deterministic replay and explicit partial observation accounting"
  ) {
    inTemp { root =>
      val _ = prepared(root)
      val args = List(
        root.resolve("study.json").toString,
        root.resolve("mapping.json").toString,
        "Discourse",
        root.resolve("cohort").toString
      )
      var error = ""
      assertEquals(OrganizationPipeline.run(args, _ => (), e => error = e), 0, error)
      assertEquals(OrganizationPipeline.run(args :+ "--resume", _ => (), _ => ()), 0)
      assertEquals(OrganizationPipeline.run(args, _ => (), _ => ()), 2)
      assertEquals(OrganizationPipeline.run(args.updated(2, "other"), _ => (), _ => ()), 2)
      write(root, "recall.txt", "Anna arrived.")
      assertEquals(OrganizationPipeline.run(args :+ "--resume", _ => (), _ => ()), 2)
      write(root, "study.json", StudyCli.Template.replace("recall.txt", "missing.txt"))
      val partial = args.updated(3, root.resolve("failed").toString)
      assertEquals(OrganizationPipeline.run(partial, _ => (), _ => ()), 3)
      val manifest = right(Canonical.parse(Files.readString(root.resolve("failed/manifest.json"))))
      assertEquals(manifest.hcursor.downField("metadata").get[Int]("attempts").toOption, Some(1))
      assertEquals(
        manifest.hcursor
          .downField("metadata")
          .get[Vector[io.circe.Json]]("outcomes")
          .toOption
          .get
          .size,
        1
      )
    }
  }
  test("public publication honors recall disclosure and interrupted outputs refuse resume") {
    inTemp { root =>
      val _ = prepared(root)
      write(root, "study.json", StudyCli.Template)
      val batch = right(MappingPipeline.analyze(root.resolve("study.json"), config))
      assert(
        OrganizationPipeline
          .publish(batch, MeasurementCompatibility.Axis.Discourse, root.resolve("refused"), false)
          .isLeft
      )
      assert(!Files.exists(root.resolve("refused")))
      val value = result(batch.outcomes.head.run.get)
      val incomplete = root.resolve("incomplete")
      Files.createDirectory(incomplete): Unit
      write(incomplete, "transitions.tsv", "partial")
      assert(OrganizationPipeline.publish(value, incomplete, true).isLeft)
    }
  }
  test("actual ONNX registration certifies origin and identifies provider in scoring receipt") {
    import storymodel4s.embed.onnx.{OnnxMappingFixture, OnnxMappingRegistration}
    import storymodel4s.embed.Sensitivity
    import storymodel4s.recall.*
    import storymodel4s.core.{Checksum, SurfaceSequence}
    val context = OnnxMappingFixture.context()
    val candidates = right(StrictCandidateConfig.of(2, TieBudgetRequest.AtMost(2), false, None))
    val settings = right(
      OnnxMappingRegistration.Config.of(
        candidates,
        Sensitivity.Public,
        Sensitivity.Public,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none
      )
    )
    val encoder = OnnxMappingFixture.open()
    val registration =
      try right(OnnxMappingRegistration.record(encoder, context, settings))
      finally encoder.close()
    val inventory = right(
      RecallInventory.of(
        context.recall,
        SurfaceSequence(context.recall.atlas).lexicalTokens.map(_.span),
        WordIdPolicy.inputArtifact(Checksum.ofText("organization-onnx-control"))
      )
    )
    val run = right(
      MappingPipeline.registeredOnnx(
        registration,
        inventory,
        config.copy(profile = MappingPipeline.OnnxProfile, perLevel = 2, tieBudget = 2)
      )
    )
    val value = result(run)
    val scoring = value.stages.find(_.stage == Stage.Scoring).get
    assert(scoring.provider.isInstanceOf[ProviderStatus.Identified])
    assertEquals(
      scoring.provider.asInstanceOf[ProviderStatus.Identified].identity.artifact,
      run.registration
    )
    assert(value.stages.find(_.stage == Stage.Refinement).get.policy.startsWith("not-run"))
    val replay = right(
      registration.replay(
        context,
        settings,
        registration.queryRecord.model,
        registration.queryRecord.provider
      )
    )
    val replayed = right(
      MappingPipeline.registeredOnnx(
        replay,
        inventory,
        config.copy(profile = MappingPipeline.OnnxProfile, perLevel = 2, tieBudget = 2)
      )
    )
    assertEquals(result(replayed).result.counts, value.result.counts)
    assertNotEquals(result(replayed).identity, value.identity)
  }
  test("synthetic disclosure admits only exact reviewed packet bytes") {
    inTemp { root =>
      val _ = prepared(root)
      val exact = Files.readString(root.resolve("recall.txt"))
      assert(RecallDisclosure.projectSynthetic(exact).isRight)
      assertEquals(
        RecallDisclosure.projectSynthetic(exact + " "),
        Left(RecallDisclosure.Refusal.SyntheticTextMismatch)
      )
    }
  }
