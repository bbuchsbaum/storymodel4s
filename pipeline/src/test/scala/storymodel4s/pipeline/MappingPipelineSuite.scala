package storymodel4s.pipeline

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.{ExpectedMappingContext, MappingCodecs}
import storymodel4s.core.*

/** Reuses the admitted Anna/Bob synthetic parser control; no participant inputs. */
class MappingPipelineSuite extends FunSuite:
  private def right[E, A](v: Either[E, A]): A = v.fold(e => fail(e.toString), identity)
  private val config = MappingPipeline.Config(
    MappingPipeline.LexicalProfile,
    1.0,
    20,
    200,
    Vector(MappingPipeline.Capability.Mapping)
  )
  private def inTemp(f: Path => Unit): Unit =
    val root = Files.createTempDirectory("mapping-pipeline-")
    try f(root)
    finally
      val paths = Files.walk(root)
      try
        paths
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(p => { Files.deleteIfExists(p): Unit })
      finally paths.close()
  private def write(root: Path, name: String, text: String): Unit = {
    Files.writeString(root.resolve(name), text): Unit
  }
  private def prepared(root: Path, extra: Boolean = false): StudyPreparation.Prepared =
    write(root, "source.txt", "Anna arrived. Bob left.")
    write(root, "recall.txt", "Anna arrived.")
    right(
      StudyPreparation.prepare(
        root,
        StudyPreparation.Config(
          StudyPreparation.SourceInput.Text("source.txt", SurfaceUnitKind.Sentence),
          Vector(StudyPreparation.RecallInput("p1", "s1", "recall.txt")) ++
            Option.when(extra)(StudyPreparation.RecallInput("p2", "s1", "missing.txt")),
          StudyPreparation.RecallProfile
        )
      )
    )

  test("actual execution publishes two distinct contextual canonical records") {
    inTemp { root =>
      val outcomes = right(MappingPipeline.batch(prepared(root), config))
      val run = outcomes.outcomes.head.run.getOrElse(fail(outcomes.outcomes.head.failure.toString))
      assertEquals(run.reference.outcomes.size, run.execution.inventory.units.size)
      assert(run.execution.reference.evidence eq run.execution.reconstruction.evidence)
      assert(run.reference.outcomes.forall(_.measures.posterior.isEmpty))
      assert(run.reconstruction.outcomes.forall(_.measures.posterior.nonEmpty))
      Vector(
        MappingRun.Member.Reference -> run.reference,
        MappingRun.Member.Reconstruction -> run.reconstruction
      ).foreach { (member, record) =>
        val context = ExpectedMappingContext(
          record.inventory,
          record.source,
          execution = Some(run.execution -> member)
        )
        assertEquals(
          right(MappingCodecs.decode(MappingCodecs.encode(record), context)).digest,
          record.digest
        )
        assert(
          MappingCodecs.decode(MappingCodecs.encode(record), context.copy(execution = None)).isLeft
        )
        val wire = MappingCodecs.toJson(record).mapObject(_.add("unrecognized", io.circe.Json.True))
        assert(MappingCodecs.decode(wire.noSpaces, context).isLeft)
      }
    }
  }

  test("resume verifies every output and never silently overwrites historical runs") {
    inTemp { root =>
      val outcomes = right(MappingPipeline.batch(prepared(root), config))
      val output = root.resolve("run")
      val receipt = right(MappingPipeline.publish(outcomes, output, false))
      assertEquals(right(MappingPipeline.publish(outcomes, output, true)), receipt)
      assertEquals(MappingPipeline.publish(outcomes, output, false), Left("OutputExists"))
      val run = outcomes.outcomes.head.run.get
      write(output, s"${run.identity.hex}-reference.json", "corrupt")
      assertEquals(MappingPipeline.publish(outcomes, output, true), Left("Corrupt"))
    }
  }

  test("failed participant and missing requested capability have independent readouts") {
    inTemp { root =>
      val results = right(
        MappingPipeline.batch(
          prepared(root, true),
          config.copy(
            requested =
              Vector(MappingPipeline.Capability.Mapping, MappingPipeline.Capability.Organization)
          )
        )
      )
      assertEquals(results.outcomes.size, 2)
      assert(results.outcomes(1).failure.nonEmpty)
      assertEquals(
        results.outcomes.head.run.get.readiness,
        MappingPipeline.Readiness.Missing(Vector(MappingPipeline.Capability.Organization))
      )
      val output = root.resolve("partial")
      assert(MappingPipeline.publish(results, output, false).isRight)
      assert(Files.readString(output.resolve("manifest.json")).contains("p2"))
    }
  }

  test("explicit profile and budgets reject invalid settings without environment fallback") {
    assert(MappingPipeline.validate(config.copy(temperature = Double.NaN)).isLeft)
    assert(MappingPipeline.validate(config.copy(networkRequests = 1)).isLeft)
    assert(MappingPipeline.validate(config.copy(retries = 1)).isLeft)
    assert(MappingPipeline.validate(config.copy(profile = "custom")).isLeft)
    assert(MappingCli.config(MappingCli.Template.dropRight(1) + ",\"profile\":\"custom\"}").isLeft)
  }

  test("public offline CLI preserves partial publication and verifies replay") {
    inTemp { root =>
      val _ = prepared(root)
      write(root, "study.json", StudyCli.Template)
      write(root, "mapping.json", MappingCli.Template)
      val args = List(
        "run",
        root.resolve("study.json").toString,
        root.resolve("mapping.json").toString,
        root.resolve("run").toString
      )
      var errors = Vector.empty[String]
      assertEquals(MappingCli.run(args, _ => (), s => errors :+= s), 0, errors.mkString)
      assertEquals(MappingCli.run(args :+ "--resume", _ => (), _ => ()), 0)
      assertEquals(MappingCli.run(args, _ => (), _ => ()), 2)
    }
  }

  test("actual ONNX registration executes both members and checked replay retains origin") {
    import storymodel4s.embed.onnx.{OnnxMappingFixture, OnnxMappingRegistration}
    import storymodel4s.embed.Sensitivity
    import storymodel4s.recall.*
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
        WordIdPolicy.inputArtifact(Checksum.ofText("synthetic-onnx-facade"))
      )
    )
    val resolved = config.copy(profile = MappingPipeline.OnnxProfile, perLevel = 2, tieBudget = 2)
    val run = right(MappingPipeline.registeredOnnx(registration, inventory, resolved))
    assert(run.execution.reference.evidence eq registration.evidence)
    assert(run.execution.reconstruction.evidence eq registration.evidence)
    val replay = right(
      registration.replay(
        context,
        settings,
        registration.queryRecord.model,
        registration.queryRecord.provider
      )
    )
    val replayed = right(MappingPipeline.registeredOnnx(replay, inventory, resolved))
    assertEquals(run.reference.digest, replayed.reference.digest)
    assertEquals(run.reconstruction.digest, replayed.reconstruction.digest)
    assertNotEquals(run.identity, replayed.identity)
    assertEquals(replayed.onnx, Some(replay))
    inTemp { root =>
      val output = root.resolve("registered")
      val digest = right(MappingPipeline.publish(run, output, false))
      assertEquals(right(MappingPipeline.publish(run, output, true)), digest)
      val manifest = Files.readString(output.resolve("manifest.json"))
      Vector(
        run.registration,
        run.execution.identity,
        run.execution.reference.evidence.identity.checksum,
        run.reference.digest,
        run.reconstruction.digest
      ).foreach(d => assert(manifest.contains(d.hex)))
      assert(manifest.contains("NormalizedScoreMass"))
      assert(manifest.contains("ModelPosterior"))
      write(output, s"${run.identity.hex}-reference.json", "corrupt")
      assertEquals(MappingPipeline.publish(run, output, true), Left("Corrupt"))
    }
  }

  test("all-failed batches bind configuration source and attempted input bytes") {
    inTemp { root =>
      val base = prepared(root)
      val missingConfig =
        base.config.copy(recalls = Vector(StudyPreparation.RecallInput("p1", "s1", "missing.txt")))
      val first = right(StudyPreparation.prepare(root, missingConfig))
      val a = right(MappingPipeline.batch(first, config))
      val b = right(MappingPipeline.batch(first, config.copy(temperature = 0.5)))
      assert(a.outcomes.forall(_.run.isEmpty))
      assertNotEquals(a.identity, b.identity)
      val output = root.resolve("failed")
      assert(MappingPipeline.publish(a, output, false).isRight)
      assertEquals(MappingPipeline.publish(b, output, true), Left("Corrupt"))
      write(root, "source.txt", "Bob left. Anna arrived.")
      val changed =
        right(MappingPipeline.batch(right(StudyPreparation.prepare(root, missingConfig)), config))
      assertNotEquals(changed.identity, a.identity)
      assertEquals(MappingPipeline.publish(changed, output, true), Left("Corrupt"))
      Files.write(root.resolve("missing.txt"), Array(0xc0.toByte)): Unit
      val malformed =
        right(MappingPipeline.batch(right(StudyPreparation.prepare(root, missingConfig)), config))
      Files.write(root.resolve("missing.txt"), Array(0xc1.toByte)): Unit
      val otherMalformed =
        right(MappingPipeline.batch(right(StudyPreparation.prepare(root, missingConfig)), config))
      assertNotEquals(malformed.identity, otherMalformed.identity)
    }
  }

  test("newcomer analyze inspect export uses ordinary files and preserves paired identities") {
    inTemp { root =>
      val _ = prepared(root)
      write(root, "study.json", StudyCli.Template)
      val local = right(MappingPipeline.analyze(root.resolve("study.json"), config))
      assertEquals(
        MappingPipeline.exportResults(local, root.resolve("refused")),
        Left(FacadeFailure.Publication("DisclosureRefused", root.resolve("refused")))
      )
      assert(!Files.exists(root.resolve("refused")))
      write(
        root,
        "study.json",
        StudyCli.Template.replace(
          "\"timing\":null",
          "\"timing\":null,\"admission\":{\"kind\":\"project-synthetic-control\"}"
        )
      )
      val batch = right(MappingPipeline.analyze(root.resolve("study.json"), config))
      val summary = MappingPipeline.inspect(batch)
      assertEquals(summary.hcursor.get[Int]("attempts").toOption, Some(1))
      assertEquals(summary.hcursor.get[Int]("completed").toOption, Some(1))
      assert(!summary.noSpaces.contains("Anna"))
      val output = root.resolve("exchange")
      val receipt = right(MappingPipeline.exportResults(batch, output))
      assertEquals(right(MappingPipeline.exportResults(batch, output, resume = true)), receipt)
      assert(MappingPipeline.exportResults(batch, output).isLeft)
      val run = batch.outcomes.head.run.get
      val allFiles = Files.walk(output)
      val bytes =
        try
          allFiles
            .iterator()
            .asScala
            .filter(Files.isRegularFile(_))
            .map(Files.readString(_))
            .mkString
        finally allFiles.close()
      assert(bytes.contains(run.reference.digest.hex))
      assert(bytes.contains(run.reconstruction.digest.hex))
    }
  }

  test("CLI capability missing and partial observation have advertised nonzero statuses") {
    inTemp { root =>
      val _ = prepared(root)
      write(root, "study.json", StudyCli.Template)
      write(
        root,
        "mapping.json",
        MappingCli.Template.replace("[\"Mapping\"]", "[\"Mapping\",\"Organization\"]")
      )
      def run(name: String): Int = MappingCli.run(
        List(
          "run",
          root.resolve("study.json").toString,
          root.resolve("mapping.json").toString,
          root.resolve(name).toString
        ),
        _ => (),
        _ => ()
      )
      assertEquals(run("capability"), 4)
      write(root, "study.json", StudyCli.Template.replace("recall.txt", "absent.txt"))
      assertEquals(run("partial"), 3)
      write(
        root,
        "mapping.json",
        MappingCli.Template.replace("mapping-lexical-pair/v1", "mapping-onnx-pair/v1")
      )
      assertEquals(run("unregistered"), 2)
      assert(!Files.exists(root.resolve("unregistered")))
    }
  }

  test("zero and all-refused batches retain requested missing capabilities and CLI status") {
    inTemp { root =>
      val base = prepared(root)
      val requested = config.copy(requested =
        Vector(
          MappingPipeline.Capability.Mapping,
          MappingPipeline.Capability.Organization,
          MappingPipeline.Capability.Order
        )
      )
      val empty = right(StudyPreparation.prepare(root, base.config.copy(recalls = Vector.empty)))
      val failed = right(
        StudyPreparation.prepare(
          root,
          base.config.copy(recalls = Vector(StudyPreparation.RecallInput("p1", "s1", "absent.txt")))
        )
      )
      Vector(empty -> 0, failed -> 1).foreach { (input, count) =>
        val batch = right(MappingPipeline.batch(input, requested))
        val fields = MappingPipeline.inspect(batch).hcursor
        assertEquals(fields.get[Int]("attempts").toOption, Some(count))
        assertEquals(fields.get[Int]("completed").toOption, Some(0))
        assertEquals(
          fields.get[Vector[String]]("missingCapabilities").toOption,
          Some(Vector("Organization", "Order"))
        )
      }
      val template = io.circe.parser.parse(StudyCli.Template).toOption.get
      write(root, "study.json", template.mapObject(_.add("recalls", io.circe.Json.arr())).noSpaces)
      write(
        root,
        "mapping.json",
        MappingCli.Template.replace("[\"Mapping\"]", "[\"Mapping\",\"Organization\"]")
      )
      var receipt = ""
      assertEquals(
        MappingCli.run(
          List(
            "run",
            root.resolve("study.json").toString,
            root.resolve("mapping.json").toString,
            root.resolve("empty-run").toString
          ),
          s => receipt = s,
          _ => ()
        ),
        4
      )
      assertEquals(
        io.circe.parser
          .parse(receipt)
          .toOption
          .get
          .hcursor
          .get[Vector[String]]("missingCapabilities")
          .toOption,
        Some(Vector("Organization"))
      )
      write(root, "study.json", StudyCli.Template.replace("recall.txt", "absent.txt"))
      assertEquals(
        MappingCli.run(
          List(
            "run",
            root.resolve("study.json").toString,
            root.resolve("mapping.json").toString,
            root.resolve("failed-run").toString
          ),
          s => receipt = s,
          _ => ()
        ),
        3
      )
      assertEquals(
        io.circe.parser
          .parse(receipt)
          .toOption
          .get
          .hcursor
          .get[Vector[String]]("missingCapabilities")
          .toOption,
        Some(Vector("Organization"))
      )
    }
  }
