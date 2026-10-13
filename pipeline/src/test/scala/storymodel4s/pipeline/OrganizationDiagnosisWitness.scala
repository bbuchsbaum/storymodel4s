package storymodel4s.pipeline

import java.nio.file.{Files, Path}
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.codec.{Canonical, OrganizationCodecs}
import storymodel4s.core.{Checksum, StorySource, SurfaceAnalyzer}
import storymodel4s.embed.Sensitivity
import storymodel4s.embed.onnx.*

/** Compare existing local channels on already admitted frozen text without selecting a default. */
object OrganizationDiagnosisWitness:
  private def right[E, A](v: Either[E, A]): A =
    v.fold(e => throw new AssertionError(e.toString), identity)

  def main(args: Array[String]): Unit =
    require(args.length == 7, "MANIFEST PLAN OUTPUT MODEL TOKENIZER PRODUCER_COMMIT PLAN_COMMIT")
    val manifestBytes = Files.readAllBytes(Path.of(args(0)))
    val planBytes = Files.readAllBytes(Path.of(args(1)))
    val manifest = right(
      Canonical.parse(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8))
    )
    val plan = right(
      Canonical.parse(new String(planBytes, java.nio.charset.StandardCharsets.UTF_8))
    )
    require(
      Checksum.ofBytes(manifestBytes).hex == right(
        plan.hcursor.get[String]("frozen_manifest_sha256")
      )
    )
    val output = Path.of(args(2))
    Files.createDirectory(output): Unit
    val language = manifest.hcursor.downField("language")
    val sentences = right(language.get[Vector[String]]("sentences"))
    val paraphrases = right(language.get[Vector[String]]("paraphrases"))
    val paths = right(language.get[Vector[Vector[Int]]]("paths"))
    val qualities = right(language.get[Vector[String]]("quality"))
    val replacements = right(language.get[Map[String, String]]("transcription"))
    val temperatures = right(plan.hcursor.get[Vector[Double]]("temperatures"))
    val channels = right(plan.hcursor.get[Vector[String]]("channels"))
    require(channels == Vector("lexical", "minilm"))
    val model = OnnxSentenceModel.AllMiniLmL6V2
    val modelPlan = plan.hcursor.downField("model")
    require(model.modelChecksum.hex == right(modelPlan.get[String]("model_sha256")))
    require(model.tokenizerChecksum.hex == right(modelPlan.get[String]("tokenizer_sha256")))
    val encoder = right(
      OnnxSentenceEmbedder.open(model, OnnxSentenceArtifacts(Path.of(args(3)), Path.of(args(4))))
    )
    val files = Vector.newBuilder[(String, String)]
    var index = 0
    try
      for path <- paths; quality <- qualities do
        val input = output.resolve(s"input-${index}")
        Files.createDirectory(input): Unit
        val raw = path
          .map(i => if quality == "paraphrase" then paraphrases(i) else sentences(i))
          .mkString(" ")
        val recallText =
          if quality == "transcription" then
            replacements.toVector.sortBy(_._1).foldLeft(raw) { case (s, (a, b)) => s.replace(a, b) }
          else raw
        val sourceText = sentences.mkString(" ")
        Files.writeString(input.resolve("source.txt"), sourceText): Unit
        Files.writeString(input.resolve("recall.txt"), recallText): Unit
        val study = StudyCli.Template.replace(
          "\"timing\":null",
          "\"timing\":null,\"admission\":{\"kind\":\"project-synthetic-control\"}"
        )
        val prepared = right(StudyPreparation.prepare(input, right(StudyPreparation.config(study))))
        val recall = right(prepared.outcomes.head.result)
        val context = right(
          SurfaceRendering.prepare(
            recall.graph,
            prepared.source,
            prepared.source.representation,
            SurfaceAnalyzer.analyze(right(StorySource.fromText(sourceText)))
          )
        )
        val base = right(MappingCli.config(MappingCli.Template))
        val candidates = right(
          StrictCandidateConfig
            .of(base.perLevel, TieBudgetRequest.AtMost(base.tieBudget), false, None)
        )
        val settings = right(
          OnnxMappingRegistration.Config.of(
            candidates,
            Sensitivity.Public,
            Sensitivity.Public,
            weights = CostWeights.semanticOnly,
            functionPrior = FunctionPrior.none,
            externalMismatch = 0.0,
            distortionPenalty = 0.0
          )
        )
        val registration = OnnxMappingRegistration.record(encoder, context, settings)
        for channel <- channels; temperature <- temperatures do
          val config = base.copy(
            temperature = temperature,
            profile =
              if channel == "lexical" then MappingPipeline.LexicalProfile
              else MappingPipeline.OnnxProfile
          )
          val attempted = scala.util.Try {
            val run =
              if channel == "lexical" then
                right(
                  MappingPipeline.lexical(
                    recall.graph,
                    prepared.source,
                    recall.inventory,
                    prepared.source.representation,
                    config
                  )
                )
              else
                right(MappingPipeline.registeredOnnx(right(registration), recall.inventory, config))
            val request =
              right(OrganizationPipeline.request(run, MeasurementCompatibility.Axis.Discourse))
            val result = right(MappingPipeline.organization(run, request))
            val costs = run.execution.reference.outcomes.map { row =>
              Json.obj(
                "unit" -> Json.fromString(row.unit.value),
                "states" -> Json.fromValues(row.costs.toVector.sortBy(_._1.key).map {
                  (state, cost) =>
                    Json.obj(
                      "state" -> Json.fromString(state.key),
                      "destination" -> Json.fromString(Destination.of(state).key),
                      "cost" -> Json.fromDoubleOrNull(cost.total),
                      "excluded" -> Json.fromBoolean(cost.excluded)
                    )
                })
              )
            }
            Json.obj(
              "status" -> Json.fromString("complete"),
              "result" -> OrganizationCodecs.toJson(result.result),
              "costs" -> Json.fromValues(costs),
              "certificate" -> Json.fromString(result.identity.hex),
              "registration" -> Json.fromString(run.registration.hex)
            )
          }.toEither
          val payload = attempted.fold(
            e =>
              Json.obj(
                "status" -> Json.fromString("failed"),
                "reason" -> Json.fromString(e.getClass.getSimpleName)
              ),
            identity
          )
          val artifact = payload.deepMerge(
            Json.obj(
              "path" -> Json.fromValues(path.map(Json.fromInt)),
              "quality" -> Json.fromString(quality),
              "channel" -> Json.fromString(channel),
              "temperature" -> Json.fromDoubleOrNull(temperature)
            )
          )
          val name = s"diagnosis-$index.json"
          val text = Canonical.print(artifact)
          Files.writeString(output.resolve(name), text): Unit
          files += name -> text: Unit
          index += 1
    finally encoder.close()
    val contents = files.result()
    val receipt = StudyArtifacts.manifest(
      "organization-diagnosis-results/v1",
      contents,
      Json.obj(
        "producerCommit" -> Json.fromString(args(5)),
        "planCommit" -> Json.fromString(args(6)),
        "planSha256" -> Json.fromString(Checksum.ofBytes(planBytes).hex),
        "frozenManifestSha256" -> Json.fromString(Checksum.ofBytes(manifestBytes).hex),
        "cases" -> Json.fromInt(contents.size),
        "modelSha256" -> Json.fromString(model.modelChecksum.hex),
        "tokenizerSha256" -> Json.fromString(model.tokenizerChecksum.hex)
      )
    )
    Files.writeString(output.resolve("manifest.json"), receipt): Unit
    println(s"wrote ${contents.size} diagnostic artifacts")
