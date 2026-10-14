package storymodel4s.pipeline

import java.nio.file.{Files, Path}
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.codec.{Canonical, OrganizationCodecs}
import storymodel4s.core.{Checksum, StorySource, SurfaceAnalyzer}
import storymodel4s.embed.Sensitivity
import storymodel4s.embed.onnx.*

/** Records conditional renderings without selecting an identity or changing original recall. */
object EntityHypothesisWitness:
  private def right[E, A](value: Either[E, A]): A =
    value.fold(e => throw new AssertionError(e.toString), identity)

  private def token(text: String): Option[String] = "^[A-Z][a-z]+\\b".r.findFirstIn(text)

  private[pipeline] def candidates(source: Vector[String], sentence: String): Vector[String] =
    val names = source.flatMap(token).distinct.sorted
    token(sentence).fold(Vector.empty[String]) { name =>
      if names.contains(name) then Vector(name)
      else
        names.filter(n =>
          OrganizationChallengeWitness.distance(name, n) <= 1 ||
            (n.startsWith(name) && name.length * 2 >= n.length)
        )
    }

  def main(args: Array[String]): Unit =
    require(args.length == 7, "MANIFEST PLAN OUTPUT MODEL TOKENIZER PRODUCER PLAN_COMMIT")
    val manifestBytes = Files.readAllBytes(Path.of(args(0)))
    val planBytes = Files.readAllBytes(Path.of(args(1)))
    val manifest = right(Canonical.parse(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8)))
    val plan = right(Canonical.parse(new String(planBytes, java.nio.charset.StandardCharsets.UTF_8)))
    require(Checksum.ofBytes(manifestBytes).hex == right(plan.hcursor.get[String]("manifest_sha256")))
    require(right(plan.hcursor.get[String]("split")) == "development")
    val groups = right(manifest.hcursor.get[Vector[Json]]("groups")).filter(g =>
      right(g.hcursor.get[String]("split")) == "development"
    )
    require(groups.size == 4)
    val output = Path.of(args(2))
    Files.createDirectory(output): Unit
    val model = OnnxSentenceModel.AllMiniLmL6V2
    val encoder = right(OnnxSentenceEmbedder.open(model, OnnxSentenceArtifacts(Path.of(args(3)), Path.of(args(4)))))
    val files = Vector.newBuilder[(String, String)]
    var index = 0
    try
      for group <- groups; item <- right(group.hcursor.get[Vector[Json]]("cases")) do
        val source = right(group.hcursor.get[Vector[String]]("source"))
        val original = right(item.hcursor.get[Vector[String]]("recall"))
        require(source.size == 4 && original.size == 4)
        val variants = (Vector(original) ++ original.zipWithIndex.flatMap { (sentence, row) =>
          candidates(source, sentence).map { name =>
            original.updated(row, name + sentence.drop(token(sentence).get.length))
          }
        }).distinct
        for processed <- variants do
          val sourceText = source.mkString(" ")
          val recallText = processed.mkString(" ")
          val input = output.resolve(s"input-$index")
          Files.createDirectory(input): Unit
          Files.writeString(input.resolve("source.txt"), sourceText): Unit
          Files.writeString(input.resolve("recall.txt"), recallText): Unit
          val attempted = scala.util.Try {
            val study = right(StudyPreparation.prepare(input, right(StudyPreparation.config(StudyCli.Template))))
            val recall = right(study.outcomes.head.result)
            require(recall.graph.ordered.map(_.text.trim.stripSuffix(".")) == processed.map(_.trim.stripSuffix(".")),
              "sentence to unit correspondence changed")
            val context = right(SurfaceRendering.prepare(recall.graph, study.source, study.source.representation,
              SurfaceAnalyzer.analyze(right(StorySource.fromText(sourceText)))))
            val base = right(MappingCli.config(MappingCli.Template))
            val settings = right(OnnxMappingRegistration.Config.of(
              right(StrictCandidateConfig.of(base.perLevel, TieBudgetRequest.AtMost(base.tieBudget), false, None)),
              Sensitivity.Public, Sensitivity.Public, weights = CostWeights.semanticOnly,
              functionPrior = FunctionPrior.none, externalMismatch = 0.0, distortionPenalty = 0.0))
            val registration = right(OnnxMappingRegistration.record(encoder, context, settings))
            val run = right(MappingPipeline.registeredOnnx(registration, recall.inventory,
              base.copy(temperature = 0.15, profile = MappingPipeline.OnnxProfile)))
            val result = right(MappingPipeline.organization(run,
              right(OrganizationPipeline.request(run, MeasurementCompatibility.Axis.Discourse))))
            require(result.result.rows.map(_.unit) == recall.graph.ordered.map(_.id), "unit order changed")
            Json.obj(
              "status" -> Json.fromString("complete"),
              "nativeResult" -> OrganizationCodecs.toJson(result.result),
              "nativeCertificate" -> Json.fromString(result.identity.hex),
              "registration" -> Json.fromString(run.registration.hex),
              "unitCorrespondence" -> Json.fromValues(recall.graph.ordered.zipWithIndex.map { (unit, i) =>
                Json.obj("index" -> Json.fromInt(i), "unit" -> Json.fromString(unit.id.value),
                  "originalClauseSha256" -> Json.fromString(Checksum.ofText(original(i)).hex),
                  "processedClauseSha256" -> Json.fromString(Checksum.ofText(processed(i)).hex),
                  "unitClauseSha256" -> Json.fromString(Checksum.ofText(unit.text.trim.stripSuffix(".")).hex))
              }),
              "costs" -> Json.fromValues(run.execution.reference.outcomes.map(row =>
                Json.obj("unit" -> Json.fromString(row.unit.value),
                  "states" -> Json.fromValues(row.costs.toVector.sortBy(_._1.key).map { (state, cost) =>
                    Json.obj("state" -> Json.fromString(state.key),
                      "destination" -> Json.fromString(Destination.of(state).key),
                      "cost" -> Json.fromDoubleOrNull(cost.total), "excluded" -> Json.fromBoolean(cost.excluded))
                  })))))
          }.toEither
          val payload = attempted.fold(e => Json.obj("status" -> Json.fromString("failed"),
            "reasonSha256" -> Json.fromString(Checksum.ofText(e.getClass.getSimpleName + ":" + Option(e.getMessage).getOrElse("")).hex)), identity)
          val artifact = payload.deepMerge(Json.obj(
            "group" -> Json.fromString(right(group.hcursor.get[String]("id"))),
            "condition" -> Json.fromString(right(item.hcursor.get[String]("condition"))),
            "sourceSha256" -> Json.fromString(Checksum.ofText(sourceText).hex),
            "originalSha256" -> Json.fromString(Checksum.ofText(original.mkString(" ")).hex),
            "processedSha256" -> Json.fromString(Checksum.ofText(recallText).hex),
            "processedClauseSha256" -> Json.fromValues(processed.map(s => Json.fromString(Checksum.ofText(s).hex)))))
          val name = s"variant-$index.json"
          val text = Canonical.print(artifact)
          Files.writeString(output.resolve(name), text): Unit
          files += name -> text: Unit
          index += 1
    finally encoder.close()
    val receipt = Json.obj(
      "schemaVersion" -> Json.fromString("entity-hypothesis-native/v1"),
      "metadata" -> Json.obj("producerCommit" -> Json.fromString(args(5)), "planCommit" -> Json.fromString(args(6)),
        "planSha256" -> Json.fromString(Checksum.ofBytes(planBytes).hex),
        "manifestSha256" -> Json.fromString(Checksum.ofBytes(manifestBytes).hex),
        "modelSha256" -> Json.fromString(model.modelChecksum.hex),
        "tokenizerSha256" -> Json.fromString(model.tokenizerChecksum.hex), "cases" -> Json.fromInt(index)),
      "files" -> Json.fromValues(files.result().map { (name, contents) =>
        Json.obj("path" -> Json.fromString(name), "sha256" -> Json.fromString(Checksum.ofText(contents).hex))
      }))
    Files.writeString(output.resolve("manifest.json"), Canonical.print(receipt)): Unit

/** Ensures the native producer preserves exact identity and every eligible alternative. */
class EntityHypothesisWitnessSuite extends munit.FunSuite:
  test("exact names keep one identity while ambiguous prefixes keep both candidates") {
    val source = Vector("Anna", "Anya")
    assertEquals(EntityHypothesisWitness.candidates(source, "Anna"), Vector("Anna"))
    assertEquals(EntityHypothesisWitness.candidates(source, "An"), Vector("Anna", "Anya"))
  }

  test("candidate order is content derived and failed retrieval stays empty") {
    val source = Vector("Anya", "Anna")
    assertEquals(EntityHypothesisWitness.candidates(source, "An"), Vector("Anna", "Anya"))
    assertEquals(EntityHypothesisWitness.candidates(Vector("Clara", "Clive"), "Cl"), Vector.empty)
  }

