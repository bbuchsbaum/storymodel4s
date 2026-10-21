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
  private def scientificProjection(record: MappingResult): io.circe.Json =
    def strip(value: io.circe.Json): io.circe.Json = value.arrayOrObject(
      value,
      rows => io.circe.Json.fromValues(rows.map(strip)),
      obj =>
        io.circe.Json.fromJsonObject(
          io.circe.JsonObject.fromIterable(
            obj.toVector
              .filterNot((key, _) =>
                Set("stages", "stage", "inference_stage_id", "candidate_set_id")(key)
              )
              .map((key, child) => key -> strip(child))
          )
        )
    )
    val json = MappingCodecs.toJson(record)
    io.circe.Json.obj(
      "policies" -> json.hcursor.downField("policies").focus.get,
      "outcomes" -> strip(json.hcursor.downField("outcomes").focus.get)
    )

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

  test("coverage reconciles retained mixed outcomes without inventing failed observation units") {
    inTemp { root =>
      val batch = right(MappingPipeline.batch(prepared(root, true), config))
      val summary = MappingPipeline.inspect(batch)
      val coverage = summary.hcursor.downField("coverage")
      val count = batch.outcomes.head.prepared.get.inventory.units.size
      assert(count > 0)
      assertEquals(right(coverage.get[Int]("prepared")), 1)
      assertEquals(right(coverage.get[Int]("runProduced")), 1)
      assertEquals(right(coverage.get[Int]("unknownUnitObservations")), 1)
      val observations = right(coverage.get[Vector[io.circe.Json]]("observations"))
      assertEquals(observations.size, 2)
      assertEquals(right(observations(1).hcursor.get[Option[Int]]("declaredUnits")), None)
      assertEquals(right(observations(1).hcursor.get[Vector[io.circe.Json]]("units")), Vector.empty)
      Vector("reference", "reconstruction").foreach { member =>
        val retained =
          observations.flatMap(o => right(o.hcursor.get[Vector[io.circe.Json]]("units")))
        val measured = retained.count(
          _.hcursor.downField(member).get[Boolean]("measureAvailable").contains(true)
        )
        val totals = coverage.downField(member)
        assertEquals(right(totals.get[Int]("declaredUnits")), count)
        assertEquals(right(totals.get[Int]("measuredUnits")), measured)
        assertEquals(right(totals.get[Int]("unavailableUnits")), count - measured)
      }
      val (_, manifest) = MappingPipeline.artifacts(batch)
      val decoded = right(io.circe.parser.parse(manifest))
      assertEquals(
        decoded.hcursor.downField("metadata").downField("summary").focus,
        Some(right(io.circe.parser.parse(storymodel4s.codec.Canonical.print(summary))))
      )
      val before = batch.outcomes.head.run.get.reference.digest
      val output = root.resolve("coverage")
      assert(MappingPipeline.publish(batch, output, false).isRight)
      assertEquals(batch.outcomes.head.run.get.reference.digest, before)
    }
  }

  test("empty and all-failed summaries keep unknown and zero denominators distinct") {
    inTemp { root =>
      val base = prepared(root)
      Vector(Vector.empty, Vector(StudyPreparation.RecallInput("p1", "s1", "missing.txt")))
        .foreach { recalls =>
          val input = right(StudyPreparation.prepare(root, base.config.copy(recalls = recalls)))
          val batch = right(MappingPipeline.batch(input, config))
          val coverage = MappingPipeline.inspect(batch).hcursor.downField("coverage")
          assertEquals(right(coverage.get[Int]("unknownUnitObservations")), recalls.size)
          assertEquals(right(coverage.downField("reference").get[Int]("declaredUnits")), 0)
          assertEquals(
            right(
              coverage.downField("reference").get[Option[Double]]("measuredFractionOfKnownUnits")
            ),
            None
          )
        }
    }
  }

  test("tie overflow remains unavailable while measured uniform nomination remains visible") {
    inTemp { root =>
      val base = prepared(root)
      write(root, "source.txt", "Anna arrived. Anna arrived.")
      val input = right(StudyPreparation.prepare(root, base.config))
      val overflow = right(MappingPipeline.batch(input, config.copy(perLevel = 1, tieBudget = 1)))
      val summary = MappingPipeline.inspect(overflow).hcursor.downField("coverage")
      val count = overflow.outcomes.head.prepared.get.inventory.units.size
      assert(count > 0)
      assertEquals(right(summary.downField("reference").get[Int]("overflowUnits")), count)
      assertEquals(right(summary.downField("reference").get[Int]("unavailableUnits")), count)
      val retained = right(summary.get[Vector[io.circe.Json]]("observations")).head
      right(retained.hcursor.get[Vector[io.circe.Json]]("units")).foreach { u =>
        assertEquals(
          right(u.hcursor.downField("reference").get[String]("unavailableReason")),
          "local-reference:TieOverflow"
        )
      }
      val measured = right(MappingPipeline.batch(input, config))
      val coverage =
        MappingPipeline.inspect(measured).hcursor.downField("coverage").downField("reference")
      assertEquals(right(coverage.get[Int]("unavailableUnits")), 0)
      assertEquals(right(coverage.get[Int]("uniformSemanticUnits")), count)
    }
  }

  test(
    "ordinary model metadata is derived from executed profiles and keeps scientific limits visible"
  ) {
    inTemp { root =>
      val batch = right(MappingPipeline.batch(prepared(root), config.copy(temperature = 0.7)))
      val run = batch.outcomes.head.run.get
      val models =
        right(MappingPipeline.inspect(batch).hcursor.get[Vector[io.circe.Json]]("models"))
      val model = models.head.hcursor
      assertEquals(right(model.downField("reference").get[Double]("temperature")), 0.7)
      assertEquals(
        right(model.downField("reference").get[Int]("level")),
        run.execution.reference.profile.level
      )
      assertEquals(
        right(model.downField("reference").get[String]("universe")),
        run.execution.reference.profile.universe.id.digest.hex
      )
      assertEquals(right(model.downField("reconstruction").get[Double]("temperature")), 0.15)
      assertEquals(right(model.downField("reconstruction").get[String]("order")), "Nondecreasing")
      assertEquals(right(model.downField("reconstruction").get[String]("fill")), "Disabled")
      assert(
        right(model.downField("interpretation").get[String]("calibration"))
          .startsWith("Unavailable")
      )
      assertEquals(
        right(model.downField("reconstruction").get[Vector[io.circe.Json]]("decisions")).size,
        run.reconstruction.outcomes.size
      )
    }
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
    assert(
      MappingPipeline
        .registeredOnnx(registration, inventory, resolved.copy(selectedLevel = 1))
        .isLeft
    )
    assert(
      MappingPipeline
        .referenceOnnx(registration, inventory, resolved.copy(selectedLevel = 1))
        .isLeft
    )
    assert(MappingPipeline.referenceOnnx(registration, inventory, resolved).isLeft)
    val referenceSettings = right(
      OnnxMappingRegistration.Config.of(
        settings.candidates,
        settings.querySensitivity,
        settings.documentSensitivity,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none,
        selectedLevel = Some(0)
      )
    )
    val referenceEncoder = OnnxMappingFixture.open()
    val referenceRegistration =
      try right(OnnxMappingRegistration.record(referenceEncoder, context, referenceSettings))
      finally referenceEncoder.close()
    val referenceOnly =
      right(MappingPipeline.referenceOnnx(referenceRegistration, inventory, resolved))
    val run = right(MappingPipeline.registeredOnnx(registration, inventory, resolved))
    assertEquals(referenceOnly.execution.reference.outcomes, run.execution.reference.outcomes)
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

  test(
    "registered reference-only context round-trips with distinct authority and no paired substitute"
  ) {
    inTemp { root =>
      val input = prepared(root, true)
      val paired = right(MappingPipeline.batch(input, config)).outcomes.head.run.get
      val batch = right(MappingPipeline.referenceBatch(input, config))
      val run = batch.outcomes.head.run.get
      assertEquals(run.execution.reference.outcomes, paired.execution.reference.outcomes)
      assertEquals(run.execution.reference.profile, paired.execution.reference.profile)
      assertEquals(scientificProjection(run.reference), scientificProjection(paired.reference))
      assertNotEquals(run.identity, paired.identity)
      val context = ExpectedMappingContext(
        run.reference.inventory,
        run.reference.source,
        referenceExecution = Some(run.execution)
      )
      val wire = MappingCodecs.encode(run.reference)
      assertEquals(right(MappingCodecs.decodeReference(wire, context)).digest, run.reference.digest)
      assert(MappingCodecs.decode(wire, context).isLeft)
      assert(MappingCodecs.decode(wire, context.copy(referenceExecution = None)).isLeft)
      assert(MappingCodecs.decodeReference(wire, context.copy(referenceExecution = None)).isLeft)
      assert(
        MappingCodecs
          .decodeReference(
            wire,
            context.copy(execution = Some(paired.execution -> MappingRun.Member.Reference))
          )
          .isLeft
      )
      assert(
        MappingCodecs
          .decodeReference(wire.replace(run.reference.digest.hex, "0" * 64), context)
          .isLeft
      )
      val exchange = right(storymodel4s.codec.MappingExchange.encode(run.execution))
      assertEquals(
        right(storymodel4s.codec.MappingExchange.decodeReference(exchange.files, context)).digest,
        run.reference.digest
      )
      assert(storymodel4s.codec.MappingExchange.decode(exchange.files, context).isLeft)
      assert(exchange.files.forall(!_._1.contains("reconstruction")))
      val coverage = MappingPipeline.inspect(batch).hcursor.downField("coverage")
      assertEquals(right(coverage.get[Int]("unknownUnitObservations")), 1)
      assertEquals(right(coverage.get[Vector[io.circe.Json]]("observations")).size, 2)
      assert(coverage.downField("reconstruction").focus.isEmpty)
      val request =
        right(OrganizationPipeline.request(paired, MeasurementCompatibility.Axis.Discourse))
      val organization = right(MappingPipeline.referenceOrganization(run, request))
      assertEquals(
        organization.result.rows,
        right(MappingPipeline.organization(paired, request)).result.rows
      )
      assertEquals(organization.result.transitions.size, run.execution.inventory.units.size - 1)
      assertEquals(
        MeasurementCompatibility.inspect(run.reference, request).verdict,
        MeasurementCompatibility.Verdict.UnknownProvenance
      )
    }
  }

  test("explicit reconstruction policies leave local evidence and reference outcomes unchanged") {
    inTemp { root =>
      val input = prepared(root)
      val base = right(MappingPipeline.batch(input, config)).outcomes.head.run.get
      val decision = right(
        StructuredReconstruction.DecisionPolicy.of(
          StructuredReconstruction.OrderPolicy.Unconstrained
        )
      )
      val profile = right(
        StructuredReconstruction.Profile.of(right(HsmmConfig.of(temperature = 0.9)), decision)
      )
      val chosen = right(
        MappingPipeline.batch(input, config.copy(reconstructionProfile = Some(profile)))
      ).outcomes.head.run.get
      assertEquals(chosen.execution.reconstruction.profile, profile)
      assertEquals(chosen.execution.reference, base.execution.reference)
      assertNotEquals(chosen.identity, base.identity)
      assert(chosen.execution.weighted(right(StructuralWeight.of(1, 2))).isLeft)
      assert(
        MappingPipeline
          .referenceBatch(input, config.copy(reconstructionProfile = Some(profile)))
          .isLeft
      )
      val invalid = config.copy(groupingRoots =
        Some(Vector(SourceNodeRef.Situation(SituationId.unsafe("foreign")) -> 1))
      )
      assert(right(MappingPipeline.batch(input, invalid)).outcomes.head.run.isEmpty)
      val local = right(MappingPipeline.referenceBatch(input, config)).outcomes.head.run.get
      assert(local.reference.outcomes.nonEmpty)
    }
  }

  test("reference settings and ordinary CLI publish only one member with safe resume") {
    inTemp { root =>
      val _ = prepared(root)
      write(
        root,
        "study.json",
        StudyCli.Template.replace(
          "\"timing\":null",
          "\"timing\":null,\"admission\":{\"kind\":\"project-synthetic-control\"}"
        )
      )
      write(
        root,
        "mapping.json",
        MappingCli.Template.dropRight(1) + ",\"mode\":\"reference-only\"}"
      )
      val batch = right(
        MappingPipeline.analyzeReference(root.resolve("study.json"), root.resolve("mapping.json"))
      )
      assert(
        MappingPipeline.analyze(root.resolve("study.json"), root.resolve("mapping.json")).isLeft
      )
      val output = root.resolve("reference-export")
      val args = List(
        "exchange",
        root.resolve("study.json").toString,
        root.resolve("mapping.json").toString,
        output.toString
      )
      val errors = scala.collection.mutable.ArrayBuffer.empty[String]
      assertEquals(
        MappingCli.run(args, _ => (), e => { val _ = errors += e; () }),
        0,
        errors.mkString
      )
      assertEquals(
        MappingCli.run(args :+ "--resume", _ => (), e => { val _ = errors += e; () }),
        0,
        errors.mkString
      )
      val members = Files.list(output)
      val names =
        try members.iterator().asScala.toVector.map(_.getFileName.toString)
        finally members.close()
      assert(names.exists(_.contains("mapping.json")))
      assert(!names.exists(_.contains("reconstruction")))
      val emitted =
        names.map(name => name -> io.circe.Json.fromString(Files.readString(output.resolve(name))))
      println("REFERENCE_MAPPING_FIXTURE\t" + io.circe.Json.obj(emitted*).noSpaces)
      val run = batch.outcomes.head.run.get
      write(output, s"${run.identity.hex}--mapping.json", "corrupt")
      assertEquals(MappingCli.run(args :+ "--resume", _ => (), e => { val _ = errors += e; () }), 2)
    }
  }

  test("nonzero complete cut is nominated before lexical pricing and reference needs no grouping") {
    inTemp { root =>
      val input = prepared(root)
      val recall = input.outcomes.head.result.toOption.get
      val nodes = input.source.nodes
      val parent = nodes.head.copy(level = 0, parent = None)
      val children = nodes.zipWithIndex.map((n, i) =>
        n.copy(
          ref = SourceNodeRef.Situation(SituationId.unsafe(s"selected-child-$i")),
          level = 1,
          parent = Some(parent.ref)
        )
      )
      val view = InMemorySourceView(parent +: children, Map.empty, None, input.source.scoringLength)
      val source = right(
        SourceRepresentation.of(
          view,
          cats.data.NonEmptyVector.fromVector(input.source.representation.bundles.toVector).get,
          None,
          view.nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
        )
      )
      val selected = config.copy(selectedLevel = 1)
      val run =
        right(MappingPipeline.lexical(recall.graph, view, recall.inventory, source, selected))
      assertEquals(
        run.execution.reference.profile.universe.targets.toSet,
        children.map(_.ref).toSet
      )
      assertEquals(run.execution.reference.profile.level, 1)
      assert(run.execution.reference.evidence.nominated.flatten.nonEmpty)
      assert(run.execution.reference.evidence.nominated.flatten.forall(children.map(_.ref).toSet))
      assert(
        !run.execution.reference.evidence.breakdowns
          .flatMap(_.keys)
          .exists(_.anchor.contains(parent.ref))
      )
      val local = right(
        MappingPipeline.referenceLexical(recall.graph, view, recall.inventory, source, selected)
      )
      assertEquals(local.execution.reference.outcomes, run.execution.reference.outcomes)
      assertEquals(scientificProjection(local.reference), scientificProjection(run.reference))
      val admitted = right(
        StudyPreparation.prepare(
          root,
          input.config.copy(recalls =
            input.config.recalls.map(
              _.copy(admission = StudyPreparation.Admission.ProjectSyntheticControl)
            )
          )
        )
      )
      val refBatch = right(MappingPipeline.referenceBatch(admitted, config.copy(selectedLevel = 1)))
      val (_, refManifest) = right(MappingPipeline.referenceArtifacts(refBatch, exchange = true))
      val declared = right(io.circe.parser.parse(refManifest)).hcursor
        .downField("metadata")
        .downField("config")
        .focus
        .get
      assert(!declared.noSpaces.contains("reconstruction"))
      assert(!declared.noSpaces.contains("grouping"))
      assertEquals(right(declared.hcursor.get[Int]("selectedLevel")), 1)
      val invalidGrouping =
        selected.copy(groupingRoots = Some(Vector(parent.ref -> 0, parent.ref -> 1)))
      assert(
        MappingPipeline
          .lexical(recall.graph, view, recall.inventory, source, invalidGrouping)
          .isLeft
      )
      val context =
        ExpectedMappingContext(recall.inventory, source, referenceExecution = Some(local.execution))
      assertEquals(
        right(MappingCodecs.decodeReference(MappingCodecs.encode(local.reference), context)).digest,
        local.reference.digest
      )
    }
  }

  test("executed grouping ranks and order policy produce distinct reconstruction decisions") {
    inTemp { root =>
      val first = prepared(root)
      write(root, "recall.txt", "Bob left. Anna arrived.")
      val input = right(StudyPreparation.prepare(root, first.config))
      val inference = StructuredReconstruction.Profile.sherlock().inference
      def settings(order: StructuredReconstruction.OrderPolicy) =
        config.copy(reconstructionProfile =
          Some(
            right(
              StructuredReconstruction.Profile
                .of(inference, right(StructuredReconstruction.DecisionPolicy.of(order)))
            )
          )
        )
      val free = right(
        MappingPipeline.batch(input, settings(StructuredReconstruction.OrderPolicy.Unconstrained))
      ).outcomes.head.run.get
      val ordered = right(
        MappingPipeline.batch(input, settings(StructuredReconstruction.OrderPolicy.Nondecreasing))
      ).outcomes.head.run.get
      assertEquals(free.execution.reference, ordered.execution.reference)
      assertNotEquals(
        free.execution.reconstruction.decisions.map(_.anchor),
        ordered.execution.reconstruction.decisions.map(_.anchor)
      )
      val roots = input.source.nodes
        .filter(_.parent.isEmpty)
        .sortBy(_.discoursePosition)
        .reverse
        .zipWithIndex
        .map((n, i) => n.ref -> i)
      val reversed = right(
        MappingPipeline.batch(
          input,
          settings(StructuredReconstruction.OrderPolicy.Nondecreasing).copy(groupingRoots =
            Some(roots)
          )
        )
      ).outcomes.head.run.get
      assertEquals(reversed.execution.reconstruction.grouping.roots, roots)
      assertEquals(reversed.execution.reference, ordered.execution.reference)
      assertNotEquals(
        reversed.execution.reconstruction.decisions.map(_.anchor),
        ordered.execution.reconstruction.decisions.map(_.anchor)
      )
    }
  }

  test(
    "reference-only empty all-failed and partial publications preserve every attempted denominator"
  ) {
    inTemp { root =>
      val base = prepared(root)
      Vector(
        Vector.empty,
        Vector(StudyPreparation.RecallInput("p1", "s1", "missing.txt"))
      ).zipWithIndex.foreach { (recalls, index) =>
        val input = right(StudyPreparation.prepare(root, base.config.copy(recalls = recalls)))
        val batch = right(MappingPipeline.referenceBatch(input, config))
        val summary = MappingPipeline.inspect(batch)
        assertEquals(right(summary.hcursor.get[Int]("attempts")), recalls.size)
        assertEquals(right(summary.hcursor.get[Int]("completed")), 0)
        assertEquals(
          right(summary.hcursor.downField("coverage").get[Int]("unknownUnitObservations")),
          recalls.size
        )
        val output = root.resolve(s"empty-$index")
        val receipt = right(MappingPipeline.publishReference(batch, output, false, exchange = true))
        assertEquals(
          right(MappingPipeline.publishReference(batch, output, true, exchange = true)),
          receipt
        )
        write(output, "manifest.json", "interrupted")
        assertEquals(
          MappingPipeline.publishReference(batch, output, true, exchange = true),
          Left("Corrupt")
        )
      }
      val mixed = right(MappingPipeline.referenceBatch(prepared(root, true), config))
      assertEquals(mixed.outcomes.count(_.run.nonEmpty), 1)
      assertEquals(mixed.outcomes.count(_.failure.nonEmpty), 1)
    }
  }

  test("reference-only temporal projection consumes the checked local record and original clock") {
    inTemp { root =>
      val paired = TemporalPipelineFixture.setup(root, timed = true)
      val reference =
        right(MappingPipeline.referenceBatch(paired.prepared, paired.config)).outcomes.head.run.get
      val original = paired.outcomes.head.run.get
      val timing = paired.outcomes.head.prepared.flatMap(_.timing)
      val request = TemporalPipelineFixture.request(paired, window = true)
      val localProjection = right(MappingPipeline.temporal(reference, request, timing))
      val pairedProjection = right(MappingPipeline.temporal(original, request, timing))._1
      assertEquals(localProjection.mapping.digest, reference.reference.digest)
      assertNotEquals(localProjection.mapping.digest, pairedProjection.mapping.digest)
      assertEquals(localProjection.measure, TemporalQuery.Measure.NormalizedScoreMass)
      assertEquals(localProjection.rows.map(_.unit), pairedProjection.rows.map(_.unit))
      assertEquals(
        localProjection.rows.map(_.grid.map(_.allocated)),
        pairedProjection.rows.map(_.grid.map(_.allocated))
      )
      assertEquals(
        localProjection.rows.map(_.durationWeighted),
        pairedProjection.rows.map(_.durationWeighted)
      )
    }
  }

  test("source reference retains conditioning through exchange and refuses paired reconstruction") {
    inTemp { root =>
      val input = prepared(root)
      val conditional = config.copy(profile = MappingPipeline.LexicalSourceProfile)
      val batch = right(MappingPipeline.referenceBatch(input, conditional))
      val run = batch.outcomes.head.run.getOrElse(fail(batch.outcomes.head.failure.toString))
      val record = run.reference
      val context = ExpectedMappingContext(
        record.inventory,
        record.source,
        referenceExecution = Some(run.execution)
      )
      val encoded = MappingCodecs.encode(record)
      assert(encoded.contains("unit/nominated-admitted-source-targets"))
      assert(encoded.contains("AdmittedSourceTargets"))
      val decoded = right(MappingCodecs.decodeReference(encoded, context))
      assertEquals(decoded.digest, record.digest)
      assertEquals(MappingCodecs.encode(decoded), encoded)
      decoded.outcomes.flatMap(_.measures.normalized).foreach { m =>
        assertEquals(m.population, NormalizationPopulation.AdmittedSourceTargets)
        assert(m.mass.keys.forall { case Destination.Target(_) => true; case _ => false })
      }
      val exchange = right(storymodel4s.codec.MappingExchange.encode(run.execution))
      assertEquals(
        right(storymodel4s.codec.MappingExchange.decodeReference(exchange.files, context)).digest,
        record.digest
      )
      assert(exchange.files.exists(_._2.contains("unit/nominated-admitted-source-targets")))
      assertEquals(
        TemporalQuery.prepare(
          record,
          record.outcomes.head.unit,
          TemporalQuery.Measure.NormalizedScoreMass,
          TemporalSupport.Selection.Part(Checksum.ofText("unneeded")),
          Vector.empty
        ),
        Left(TemporalQuery.Refusal.ConditionalMeasureUnsupported)
      )
      val stripped =
        encoded.replace("unit/nominated-admitted-source-targets", "unit/supplied-destinations")
      assert(MappingCodecs.decodeReference(stripped, context).isLeft)
      val wrong = encoded.replace("AdmittedSourceTargets", "SuppliedDestinations")
      assert(MappingCodecs.decodeReference(wrong, context).isLeft)
      val paired = right(MappingPipeline.batch(input, conditional))
      assert(paired.outcomes.head.run.isEmpty)
      assert(paired.outcomes.head.failure.nonEmpty)
    }
  }

  test("organization refuses source conditioning while historical reference remains supported") {
    inTemp { root =>
      val input = prepared(root)
      val conditional = right(
        MappingPipeline.referenceBatch(
          input,
          config.copy(profile = MappingPipeline.LexicalSourceProfile)
        )
      ).outcomes.head.run.get
      val historical = right(MappingPipeline.referenceBatch(input, config)).outcomes.head.run.get
      val request = right(
        MeasurementCompatibility.Request.of(
          MeasurementCompatibility.Axis.Discourse,
          conditional.reference.inventory.segmentation,
          0
        )
      )
      assertEquals(
        MappingPipeline.referenceOrganization(conditional, request),
        Left(MeasurementCompatibility.Reason.ConditionalMeasureUnsupported)
      )
      assert(MappingPipeline.referenceOrganization(historical, request).isRight)
      assertEquals(
        MeasurementCompatibility.inspect(conditional.reference, request).verdict,
        MeasurementCompatibility.Verdict.Incompatible
      )
    }
  }
