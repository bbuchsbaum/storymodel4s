package storymodel4s.pipeline

import java.nio.file.{Files, Path}
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.codec.{Canonical, OrganizationCodecs}
import storymodel4s.core.{Checksum, SituationId}
import storymodel4s.recall.RecallUnitId

/** Source: Codex-authored fictional Anna/Bob diagnostic clauses, 2026-10-13, Toronto. Basis:
  * original project synthetic contribution under repository Apache-2.0, not a translation.
  * Modifications: declared forward/reverse/jump/revisit order, lexical paraphrase and name
  * truncation to separate true organization from mapping quality; exact transformations are frozen
  * below. Narrator: none, machine-authored fiction. Participant provenance: none; no human recall
  * or REB. Tradition: none; no Indigenous/community material or protocol restrictions. Admitted by:
  * Codex independent reviewer /root/organization_review, 2026-10-13, checked against
  * organization-language/v1 (eight clauses and declared transformations).
  */
object OrganizationRecoveryWitness:
  private def right[E, A](v: Either[E, A]): A =
    v.fold(e => throw new AssertionError(e.toString), identity)
  private def target(n: Int): Destination =
    Destination.Target(SourceNodeRef.Situation(SituationId.unsafe(s"synthetic-rank-$n")))
  private val categories = Vector(
    OrganizationReadout.Category.Backward,
    OrganizationReadout.Category.Forward,
    OrganizationReadout.Category.SameTarget
  )
  private def pointTruth(path: Vector[Int]): Vector[Int] =
    val pairs = path.zip(path.drop(1))
    Vector(
      pairs.count((a, b) => b < a),
      pairs.count((a, b) => b > a),
      pairs.count((a, b) => b == a)
    )
  def main(args: Array[String]): Unit =
    require(args.length == 4, "MANIFEST OUTPUT FREEZE_COMMIT PRODUCER_COMMIT")
    val manifestPath = Path.of(args(0))
    val manifestBytes = Files.readAllBytes(manifestPath)
    val manifest = right(
      Canonical.parse(new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8))
    )
    val output = Path.of(args(1))
    Files.createDirectories(output): Unit
    val files = Vector.newBuilder[(String, String)]
    def retain(name: String, text: String): Unit =
      Files.writeString(output.resolve(name), text): Unit
      files += name -> text: Unit
    right(manifest.hcursor.get[Vector[Json]]("cases")).foreach { fixture =>
      val id = right(fixture.hcursor.get[String]("id"))
      val sets = right(fixture.hcursor.get[Vector[Option[Vector[Int]]]]("rows"))
      val rows = sets.zipWithIndex.map { (entry, i) =>
        entry match
          case None     => OrganizationReadout.Row.unavailable(RecallUnitId.unsafe(s"u$i"))
          case Some(ns) =>
            right(
              OrganizationReadout.Row.of(
                RecallUnitId.unsafe(s"u$i"),
                ns.map(n => target(n) -> (1.0 / ns.size)).toMap,
                ns.map(target)
              )
            )
      }
      val order = right(
        OrganizationReadout.Order.declared(
          sets.flatten.flatten.distinct.map(n =>
            SourceNodeRef.Situation(SituationId.unsafe(s"synthetic-rank-$n")) -> n.toDouble
          )
        )
      )
      val result = right(
        OrganizationReadout.compute(
          rows,
          order,
          OrganizationReadout.Dependence.DeclaredIndependentRows
        )
      )
      retain(
        s"case-$id.json",
        Canonical.print(
          Json.obj("fixture" -> fixture, "result" -> OrganizationCodecs.toJson(result))
        )
      )
    }
    val experiments = manifest.hcursor.downField("experiments")
    val paths = right(experiments.get[Vector[Vector[Int]]]("paths"))
    val qualities = right(experiments.get[Vector[Double]]("correct_mass"))
    val omitted = right(experiments.get[Vector[Option[Int]]]("omitted_indices"))
    val orders = right(experiments.get[Vector[Vector[Int]]]("target_storage_orders"))
    val displays = right(experiments.get[Vector[Int]]("displays_per_unit"))
    var index = 0
    for
      path <- paths; quality <- qualities; missing <- omitted; storage <- orders; copies <- displays
    do
      val rows = path.zipWithIndex.map { (truth, i) =>
        if missing.contains(i) then OrganizationReadout.Row.unavailable(RecallUnitId.unsafe(s"u$i"))
        else
          val mass = storage
            .map(n =>
              target(n) -> (if n == truth then quality else (1.0 - quality) / (storage.size - 1))
            )
            .toMap
          right(OrganizationReadout.Row.of(RecallUnitId.unsafe(s"u$i"), mass, storage.map(target)))
      }
      val order = right(
        OrganizationReadout.Order.declared(
          storage.map(n =>
            SourceNodeRef.Situation(SituationId.unsafe(s"synthetic-rank-$n")) -> n.toDouble
          )
        )
      )
      val result = right(
        OrganizationReadout.compute(
          rows,
          order,
          OrganizationReadout.Dependence.DeclaredIndependentRows
        )
      )
      retain(
        s"experiment-$index.json",
        Canonical.print(
          Json.obj(
            "truth" -> Json.fromValues(path.map(Json.fromInt)),
            "truthCounts" -> Json.fromValues(pointTruth(path).map(Json.fromInt)),
            "correctMass" -> Json.fromDoubleOrNull(quality),
            "omitted" -> missing.fold(Json.Null)(Json.fromInt),
            "displayCopies" -> Json.fromInt(copies),
            "storage" -> Json.fromValues(storage.map(Json.fromInt)),
            "result" -> OrganizationCodecs.toJson(result)
          )
        )
      )
      index += 1
    val language = manifest.hcursor.downField("language")
    val sentences = right(language.get[Vector[String]]("sentences"))
    val paraphrases = right(language.get[Vector[String]]("paraphrases"))
    val languagePaths = right(language.get[Vector[Vector[Int]]]("paths"))
    val temperatures = right(language.get[Vector[Double]]("temperatures"))
    var languageIndex = 0
    for
      path <- languagePaths; quality <- Vector("exact", "paraphrase", "transcription");
      temperature <- temperatures
    do
      val attempted = scala.util.Try {
        val input = output.resolve(s"language-input-$languageIndex")
        Files.createDirectory(input): Unit
        val recallSentences =
          path.map(i => if quality == "paraphrase" then paraphrases(i) else sentences(i))
        val recall = recallSentences.mkString(" ")
        val transformed =
          if quality == "transcription" then recall.replace("Anna", "An").replace("Bob", "Bo")
          else recall
        Files.writeString(input.resolve("source.txt"), sentences.mkString(" ")): Unit
        Files.writeString(input.resolve("recall.txt"), transformed): Unit
        Files.writeString(
          input.resolve("study.json"),
          StudyCli.Template.replace(
            "\"timing\":null",
            "\"timing\":null,\"admission\":{\"kind\":\"project-synthetic-control\"}"
          )
        ): Unit
        val config = right(MappingCli.config(MappingCli.Template)).copy(temperature = temperature)
        val batch = right(MappingPipeline.analyze(input.resolve("study.json"), config))
        val run =
          batch.outcomes.head.run.getOrElse(throw new AssertionError(batch.outcomes.head.failure))
        val request =
          right(OrganizationPipeline.request(run, MeasurementCompatibility.Axis.Discourse))
        val result = right(MappingPipeline.organization(run, request))
        val refs =
          run.execution.view.nodes.filter(_.level == 0).sortBy(_.discoursePosition).map(_.ref)
        require(result.result.rows.size == path.size, "frozen language unit count changed")
        val trueMass = result.result.rows
          .zip(path)
          .map((row, i) => row.mass.getOrElse(Destination.Target(refs(i)), 0.0))
        val bias = categories.zip(pointTruth(path)).map((c, n) => result.result.counts(c) - n)
        val payload = Canonical.print(
          Json.obj(
            "truth" -> Json.fromValues(path.map(Json.fromInt)),
            "truthCounts" -> Json.fromValues(pointTruth(path).map(Json.fromInt)),
            "quality" -> Json.fromString(quality),
            "temperature" -> Json.fromDoubleOrNull(temperature),
            "trueTargetMass" -> Json.fromValues(trueMass.map(Json.fromDoubleOrNull)),
            "countBias" -> Json.fromValues(bias.map(Json.fromDoubleOrNull)),
            "result" -> OrganizationCodecs.toJson(result.result),
            "certificate" -> Json.fromString(result.identity.hex)
          )
        )
        if languageIndex == 0 then {
          right(OrganizationPipeline.publish(result, output.resolve("public-example"))): Unit
        }
        payload
      }.toEither
      val payload = attempted.fold(
        error =>
          Canonical.print(
            Json.obj(
              "status" -> Json.fromString("failed"),
              "truth" -> Json.fromValues(path.map(Json.fromInt)),
              "quality" -> Json.fromString(quality),
              "temperature" -> Json.fromDoubleOrNull(temperature),
              "reason" -> Json.fromString(error.getClass.getSimpleName)
            )
          ),
        identity
      )
      retain(s"language-$languageIndex.json", payload)
      languageIndex += 1
    val contents = files.result()
    val receipt = StudyArtifacts.manifest(
      "organization-recovery-results/v1",
      contents,
      Json.obj(
        "freezeCommit" -> Json.fromString(args(2)),
        "producerCommit" -> Json.fromString(args(3)),
        "frozenManifestSha256" -> Json.fromString(Checksum.ofBytes(manifestBytes).hex),
        "packetExperiments" -> Json.fromInt(index),
        "languageExperiments" -> Json.fromInt(languageIndex)
      )
    )
    Files.writeString(output.resolve("manifest.json"), receipt): Unit
    println(
      s"wrote ${contents.size} frozen recovery artifacts; packet=$index language=$languageIndex"
    )
