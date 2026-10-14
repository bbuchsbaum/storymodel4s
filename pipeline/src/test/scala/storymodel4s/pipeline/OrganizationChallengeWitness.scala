package storymodel4s.pipeline

import java.nio.file.{Files, Path}
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.codec.{Canonical, OrganizationCodecs}
import storymodel4s.core.{Checksum, StorySource, SurfaceAnalyzer}
import storymodel4s.embed.Sensitivity
import storymodel4s.embed.onnx.*

/** Exercises a frozen content challenge through genuine local registrations, preserving input
  * edits.
  */
object OrganizationChallengeWitness:
  private def right[E, A](value: Either[E, A]): A =
    value.fold(e => throw new AssertionError(e.toString), identity)

  private def token(text: String): Option[String] = "^[A-Z][a-z]+\\b".r.findFirstIn(text)

  private[pipeline] def distance(a: String, b: String): Int =
    var previous = (0 to b.length).toVector
    for i <- a.indices do
      val current = Array.fill(b.length + 1)(0)
      current(0) = i + 1
      for j <- b.indices do
        current(j + 1) = math.min(
          math.min(current(j) + 1, previous(j + 1) + 1),
          previous(j) + (if a(i) == b(j) then 0 else 1)
        )
      previous = current.toVector
    previous.last

  private[pipeline] def normalize(
      source: Vector[String],
      recall: Vector[String]
  ): Vector[(String, String)] =
    val names = source.flatMap(token).distinct.sorted
    recall.map { sentence =>
      token(sentence).fold(sentence -> "no-token") { name =>
        if names.contains(name) then sentence -> "exact-name"
        else
          val candidates = names.filter(n =>
            distance(name, n) <= 1 || (n.startsWith(name) && name.length * 2 >= n.length)
          )
          if candidates.size == 1 then
            (candidates.head + sentence.drop(name.length)) -> "unique-match"
          else sentence -> (if candidates.isEmpty then "no-match" else "ambiguous-match")
      }
    }

  private[pipeline] def repair(source: Vector[String], recall: Vector[String]): Vector[String] =
    normalize(source, recall).map(_._1)

  def main(args: Array[String]): Unit =
    require(
      args.length == 10,
      "MANIFEST PLAN OUTPUT MODEL TOKENIZER PRODUCER PLAN_COMMIT SPLIT FIT FIT_COMMIT"
    )
    val manifestBytes = Files.readAllBytes(Path.of(args(0)))
    val planBytes = Files.readAllBytes(Path.of(args(1)))
    val manifest = right(
      Canonical.parse(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8))
    )
    val plan = right(
      Canonical.parse(new String(planBytes, java.nio.charset.StandardCharsets.UTF_8))
    )
    require(
      Checksum.ofBytes(manifestBytes).hex == right(plan.hcursor.get[String]("manifest_sha256"))
    )
    val split = args(7)
    require(Set("development", "heldout")(split))
    val fitBytes =
      if split == "heldout" then Files.readAllBytes(Path.of(args(8))) else Array.emptyByteArray
    val fit =
      if split == "heldout" then
        right(Canonical.parse(new String(fitBytes, java.nio.charset.StandardCharsets.UTF_8)))
      else Json.Null
    if split == "heldout" then
      require(right(fit.hcursor.get[String]("plan_sha256")) == Checksum.ofBytes(planBytes).hex)
      require(right(fit.hcursor.get[String]("producer_commit")) == args(5))
    val stock = right(plan.hcursor.get[Double]("stock_temperature"))
    val channels = right(plan.hcursor.get[Vector[String]]("channels"))
    require(channels == Vector("lexical", "minilm", "repaired-minilm"))
    val groups = right(manifest.hcursor.get[Vector[Json]]("groups")).filter(g =>
      right(g.hcursor.get[String]("split")) == split
    )
    require(groups.size == 4)
    val output = Path.of(args(2))
    Files.createDirectory(output): Unit
    val model = OnnxSentenceModel.AllMiniLmL6V2
    require(
      model.modelChecksum.hex == right(plan.hcursor.downField("model").get[String]("model_sha256"))
    )
    require(
      model.tokenizerChecksum.hex == right(
        plan.hcursor.downField("model").get[String]("tokenizer_sha256")
      )
    )
    val encoder = right(
      OnnxSentenceEmbedder.open(model, OnnxSentenceArtifacts(Path.of(args(3)), Path.of(args(4))))
    )
    val files = Vector.newBuilder[(String, String)]
    var index = 0
    try
      for
        group <- groups; item <- right(group.hcursor.get[Vector[Json]]("cases"));
        channel <- channels
      do
        val source = right(group.hcursor.get[Vector[String]]("source"))
        val original = right(item.hcursor.get[Vector[String]]("recall"))
        val normalized =
          if channel == "repaired-minilm" then normalize(source, original)
          else original.map(_ -> "unprocessed")
        val processed = normalized.map(_._1)
        require(processed.size == original.size)
        val edits = original.zip(processed).zipWithIndex.collect {
          case ((before, after), i) if before != after =>
            Json.obj(
              "index" -> Json.fromInt(i),
              "beforeSha256" -> Json.fromString(Checksum.ofText(before).hex),
              "afterSha256" -> Json.fromString(Checksum.ofText(after).hex)
            )
        }
        val sourceText = source.mkString(" ")
        val recallText = processed.mkString(" ")
        val input = output.resolve(s"input-$index")
        Files.createDirectory(input): Unit
        Files.writeString(input.resolve("source.txt"), sourceText): Unit
        Files.writeString(input.resolve("recall.txt"), recallText): Unit
        val temperatures =
          if split == "development" then Vector(stock)
          else
            Vector(
              stock,
              right(fit.hcursor.downField("temperatures").get[Double](channel))
            ).distinct
        val prepared = scala.util.Try {
          val study = right(
            StudyPreparation.prepare(input, right(StudyPreparation.config(StudyCli.Template)))
          )
          val recall = right(study.outcomes.head.result)
          require(
            recall.graph.ordered.map(_.text.trim.stripSuffix(".")) == processed.map(
              _.trim.stripSuffix(".")
            ),
            "sentence to unit correspondence changed"
          )
          val context = right(
            SurfaceRendering.prepare(
              recall.graph,
              study.source,
              study.source.representation,
              SurfaceAnalyzer.analyze(right(StorySource.fromText(sourceText)))
            )
          )
          val base = right(MappingCli.config(MappingCli.Template))
          val settings = right(
            OnnxMappingRegistration.Config.of(
              right(
                StrictCandidateConfig
                  .of(base.perLevel, TieBudgetRequest.AtMost(base.tieBudget), false, None)
              ),
              Sensitivity.Public,
              Sensitivity.Public,
              weights = CostWeights.semanticOnly,
              functionPrior = FunctionPrior.none,
              externalMismatch = 0.0,
              distortionPenalty = 0.0
            )
          )
          val registration =
            if channel == "lexical" then None
            else Some(right(OnnxMappingRegistration.record(encoder, context, settings)))
          (study, recall, base, registration)
        }.toEither
        for temperature <- temperatures do
          val attempted = prepared.flatMap { case (study, recall, base, registration) =>
            scala.util.Try {
              val config = base.copy(
                temperature = temperature,
                profile =
                  if channel == "lexical" then MappingPipeline.LexicalProfile
                  else MappingPipeline.OnnxProfile
              )
              val run = registration match
                case None =>
                  right(
                    MappingPipeline.lexical(
                      recall.graph,
                      study.source,
                      recall.inventory,
                      study.source.representation,
                      config
                    )
                  )
                case Some(value) =>
                  right(MappingPipeline.registeredOnnx(value, recall.inventory, config))
              val result = right(
                MappingPipeline.organization(
                  run,
                  right(OrganizationPipeline.request(run, MeasurementCompatibility.Axis.Discourse))
                )
              )
              require(
                result.result.rows.map(_.unit) == recall.graph.ordered.map(_.id),
                "unit order changed"
              )
              require(result.result.rows.size == original.size, "unit correspondence changed")
              Json.obj(
                "status" -> Json.fromString("complete"),
                "result" -> OrganizationCodecs.toJson(result.result),
                "certificate" -> Json.fromString(result.identity.hex),
                "unitCorrespondence" -> Json.fromValues(recall.graph.ordered.zipWithIndex.map {
                  (unit, i) =>
                    Json.obj(
                      "index" -> Json.fromInt(i),
                      "unit" -> Json.fromString(unit.id.value),
                      "originalClauseSha256" -> Json.fromString(Checksum.ofText(original(i)).hex),
                      "processedClauseSha256" -> Json.fromString(Checksum.ofText(processed(i)).hex),
                      "unitClauseSha256" -> Json.fromString(
                        Checksum.ofText(unit.text.trim.stripSuffix(".")).hex
                      )
                    )
                }),
                "registration" -> Json.fromString(run.registration.hex),
                "costs" -> Json.fromValues(
                  run.execution.reference.outcomes.map(row =>
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
                  )
                )
              )
            }.toEither
          }
          val payload = attempted.fold(
            e =>
              Json.obj(
                "status" -> Json.fromString("failed"),
                "reason" -> Json.fromString(
                  e.getClass.getSimpleName + ":" + Checksum
                    .ofText(Option(e.getMessage).getOrElse(""))
                    .hex
                )
              ),
            identity
          )
          val artifact = payload.deepMerge(
            Json.obj(
              "group" -> Json.fromString(right(group.hcursor.get[String]("id"))),
              "condition" -> Json.fromString(right(item.hcursor.get[String]("condition"))),
              "split" -> Json.fromString(split),
              "channel" -> Json.fromString(channel),
              "temperature" -> Json.fromDoubleOrNull(temperature),
              "sourceSha256" -> Json.fromString(Checksum.ofText(sourceText).hex),
              "originalSha256" -> Json.fromString(Checksum.ofText(original.mkString(" ")).hex),
              "processedSha256" -> Json.fromString(Checksum.ofText(recallText).hex),
              "edits" -> Json.fromValues(edits),
              "normalizationReasons" -> Json.fromValues(normalized.map(v => Json.fromString(v._2)))
            )
          )
          val name = s"challenge-$index.json"
          val text = Canonical.print(artifact)
          Files.writeString(output.resolve(name), text): Unit
          files += name -> text: Unit
          index += 1
    finally encoder.close()
    val contents = files.result()
    val receipt = StudyArtifacts.manifest(
      "organization-content-challenge-results/v1",
      contents,
      Json.obj(
        "producerCommit" -> Json.fromString(args(5)),
        "planCommit" -> Json.fromString(args(6)),
        "planSha256" -> Json.fromString(Checksum.ofBytes(planBytes).hex),
        "manifestSha256" -> Json.fromString(Checksum.ofBytes(manifestBytes).hex),
        "split" -> Json.fromString(split),
        "fitCommit" -> Json.fromString(args(9)),
        "fitSha256" -> Json.fromString(Checksum.ofBytes(fitBytes).hex),
        "cases" -> Json.fromInt(contents.size),
        "modelSha256" -> Json.fromString(model.modelChecksum.hex),
        "tokenizerSha256" -> Json.fromString(model.tokenizerChecksum.hex)
      )
    )
    Files.writeString(output.resolve("manifest.json"), receipt): Unit
    println(s"wrote ${contents.size} $split challenge artifacts")

/** Checks the diagnostic repair rule's refusal boundaries independently of model scores. */
class OrganizationChallengeWitnessSuite extends munit.FunSuite:
  test("exact names survive, a unique typo repairs, and ambiguous candidates remain unchanged") {
    val source = Vector("Anna", "Anya")
    assertEquals(
      OrganizationChallengeWitness.normalize(source, Vector("Anna", "Azna", "An")),
      Vector("Anna" -> "exact-name", "Anna" -> "unique-match", "An" -> "ambiguous-match")
    )
  }

  test("short prefixes and absent matches remain unchanged") {
    assertEquals(
      OrganizationChallengeWitness.normalize(Vector("Clara", "Clive"), Vector("Cl", "Wind", "A")),
      Vector("Cl" -> "no-match", "Wind" -> "no-match", "A" -> "no-token")
    )
  }

  test("ordinary Levenshtein does not silently treat transposition as one edit") {
    assertEquals(OrganizationChallengeWitness.distance("Nora", "Nroa"), 2)
    assertEquals(
      OrganizationChallengeWitness.repair(Vector("Nora", "Noah"), Vector("Nroa")),
      Vector("Nroa")
    )
  }

  test("rule depends on source content, not its storage order") {
    val source = Vector("Clara", "Clive")
    assertEquals(
      OrganizationChallengeWitness.repair(source, Vector("Clar", "Cliv")),
      OrganizationChallengeWitness.repair(source.reverse, Vector("Clar", "Cliv"))
    )
  }
