package storymodel4s.pipeline

import cats.syntax.all.*
import io.circe.Json
import java.nio.file.Path
import storymodel4s.align.*
import storymodel4s.codec.{Canonical, MappingExchange, OrganizationCodecs}
import storymodel4s.core.Checksum

/** The additive 1.1 journey publishes certified reference readouts beside both original mappings.
  */
object OrganizationPipeline:
  val SchemaVersion: String = "organization-exchange/v1"
  private def stageJson(r: StageReceipt): Json =
    val provider = r.provider match
      case ProviderStatus.Identified(p) =>
        Json.obj(
          "status" -> Json.fromString("Identified"),
          "provider" -> Json.fromString(p.provider),
          "model" -> Json.fromString(p.model),
          "artifact" -> Json.fromString(p.artifact.hex)
        )
      case ProviderStatus.NotApplicable(reason) =>
        Json.obj("status" -> Json.fromString("NotApplicable"), "reason" -> Json.fromString(reason))
    Json.obj(
      "stage" -> Json.fromString(r.stage.toString),
      "policy" -> Json.fromString(r.policy),
      "config" -> Json.fromString(r.config.hex),
      "inputs" -> Json.fromValues(r.inputs.map(i => Json.fromString(i.hex))),
      "provider" -> provider
    )
  def request(
      run: MappingPipeline.Run,
      axis: MeasurementCompatibility.Axis
  ): Either[String, MeasurementCompatibility.Request] =
    MeasurementCompatibility.Request
      .of(axis, run.execution.inventory.segmentation, run.execution.reference.profile.level)
      .left
      .map(_.toString)

  private def metadata(value: MappingPipeline.Organization): Json =
    val run = value.run
    val universe = run.execution.reference.profile.universe.targets.toSet
    val evidence = run.execution.reference.evidence
    Json.obj(
      "schemaVersion" -> Json.fromString(SchemaVersion),
      "organization" -> Json.fromString(value.identity.hex),
      "run" -> Json.fromString(run.identity.hex),
      "registration" -> Json.fromString(run.registration.hex),
      "execution" -> Json.fromString(run.execution.identity.hex),
      "reference" -> Json.fromString(run.reference.digest.hex),
      "inventory" -> Json.fromString(run.execution.inventory.digest.hex),
      "segmentation" -> Json.fromString(run.execution.inventory.segmentation.digest.hex),
      "source" -> Json.fromString(run.execution.source.digest.hex),
      "member" -> Json.fromString("Reference"),
      "measure" -> Json.fromString(MeasureKind.NormalizedScoreMass.toString),
      "verdict" -> Json.fromString(value.verdict.toString),
      "reasons" -> Json.fromValues(value.reasons.map(r => Json.fromString(r.toString))),
      "request" -> Json.obj(
        "digest" -> Json.fromString(value.request.digest.hex),
        "axis" -> Json.fromString(value.request.axis.toString),
        "level" -> Json.fromInt(value.request.level),
        "quantity" -> Json.fromString(value.request.quantity.toString),
        "unknown" -> Json.fromString(value.request.unknown.toString)
      ),
      "calibration" -> Json.fromString("not-established"),
      "candidateCoverage" -> Json.fromValues(evidence.units.zipWithIndex.map { (unit, i) =>
        val nominated = evidence.nominated(i).toSet
        Json.obj(
          "unit" -> Json.fromString(unit.value),
          "universe" -> Json.fromInt(universe.size),
          "nominated" -> Json.fromInt(nominated.size),
          "complete" -> Json.fromBoolean(nominated == universe),
          "admissible" -> Json.fromValues(
            value.result.rows(i).admissible.map(d => Json.fromString(d.key))
          )
        )
      }),
      "stages" -> Json.fromValues(value.stages.map(stageJson)),
      "support" -> Json.fromString("original mapping pair retains exact support and term receipts")
    )

  private def files(value: MappingPipeline.Organization): Either[String, Vector[(String, String)]] =
    MappingExchange.encode(value.run.execution).left.map(_.toString).map { pair =>
      pair.files.map((n, t) => s"mapping--$n" -> t) ++ Vector(
        "organization.json" -> OrganizationCodecs.encode(value.result),
        "transitions.tsv" -> OrganizationCodecs.transitions(value.result),
        "compatibility.json" -> Canonical.print(metadata(value))
      )
    }

  def publish(
      value: MappingPipeline.Organization,
      output: Path,
      resume: Boolean = false
  ): Either[String, Checksum] =
    files(value).flatMap { contents =>
      val manifest = StudyArtifacts.manifest(SchemaVersion, contents, metadata(value))
      StudyArtifacts.publish(output, contents, manifest, resume).left.map(_.toString)
    }

  /** A producer-owned context restores authority by recomputing, never by trusting wire verdicts.
    */
  def verify(
      value: MappingPipeline.Organization,
      supplied: Vector[(String, String)]
  ): Either[String, MappingPipeline.Organization] =
    files(value).flatMap { contents =>
      val expected = contents :+ ("manifest.json" -> StudyArtifacts.manifest(
        SchemaVersion,
        contents,
        metadata(value)
      ))
      Either.cond(
        supplied.map(_._1).distinct.size == supplied.size && supplied.toMap == expected.toMap,
        value,
        "organization exchange context or contents differ"
      )
    }

  /** Failed observations remain in the cohort manifest while successful siblings are exported. */
  def publish(
      batch: MappingPipeline.Batch,
      axis: MeasurementCompatibility.Axis,
      output: Path,
      resume: Boolean
  ): Either[String, Checksum] =
    for
      _ <- batch.outcomes
        .flatMap(_.prepared)
        .traverse(r => StudyPreparation.publishRecall(r).left.map(_.toString))
      outputs <- batch.outcomes.traverse { observation =>
        observation.run match
          case None =>
            Right(
              Vector.empty[(String, String)] -> Json.obj(
                "participant" -> Json.fromString(observation.participant),
                "session" -> Json.fromString(observation.session),
                "status" -> Json.fromString("failed"),
                "reason" -> Json.fromString(observation.failure.getOrElse("not-executed"))
              )
            )
          case Some(run) =>
            for
              r <- request(run, axis)
              result <- MappingPipeline.organization(run, r).left.map(_.toString)
              contents <- files(result)
            yield contents.map((name, text) => s"${run.identity.hex}--$name" -> text) ->
              Json.obj(
                "participant" -> Json.fromString(observation.participant),
                "session" -> Json.fromString(observation.session),
                "status" -> Json.fromString("complete"),
                "run" -> Json.fromString(run.identity.hex),
                "organization" -> Json.fromString(result.identity.hex),
                "metadata" -> metadata(result)
              )
      }
      contents = outputs.flatMap(_._1)
      manifest = StudyArtifacts.manifest(
        "organization-cohort/v1",
        contents,
        Json.obj(
          "batch" -> Json.fromString(batch.identity.hex),
          "axis" -> Json.fromString(axis.toString),
          "attempts" -> Json.fromInt(batch.outcomes.size),
          "outcomes" -> Json.fromValues(outputs.map(_._2))
        )
      )
      receipt <- StudyArtifacts.publish(output, contents, manifest, resume).left.map(_.toString)
    yield receipt

  /** Ordinary input files use the existing offline preparer, disclosure and atomic publication. */
  def run(args: List[String], out: String => Unit, err: String => Unit): Int =
    def refuse(reason: String): Int = { err(reason); 2 }
    args match
      case List("--help") =>
        out("organizationMap STUDY MAPPING_CONFIG Discourse|StoryWorld OUTPUT [--resume]"); 0
      case study :: config :: axisName :: output :: tail
          if tail.isEmpty || tail == List("--resume") =>
        val result = for
          axis <- MeasurementCompatibility.Axis.values
            .find(_.toString == axisName)
            .toRight("unknown source axis")
          configPath <- scala.util
            .Try(Path.of(config))
            .toEither
            .left
            .map(_ => "invalid config path")
          resolved <- MappingSettings.load(configPath).left.map(_.diagnostic.noSpaces)
          _ <- Either.cond(
            resolved.config.requested == Vector(MappingPipeline.Capability.Mapping),
            (),
            "the 1.1 organization command owns its explicit readout request; mapping config must request Mapping"
          )
          studyPath <- scala.util.Try(Path.of(study)).toEither.left.map(_ => "invalid study path")
          outputPath <- scala.util
            .Try(Path.of(output))
            .toEither
            .left
            .map(_ => "invalid output path")
          prepared <- StudyCli.load(studyPath.toString).left.map(_.toString)
          batch <- resolved.execute(prepared).left.map(_.diagnostic.noSpaces)
          receipt <- publish(batch, axis, outputPath, tail.nonEmpty)
        yield batch -> receipt
        result match
          case Left(reason)            => refuse(reason)
          case Right((batch, receipt)) =>
            out(
              Canonical.print(
                Json.obj(
                  "schemaVersion" -> Json.fromString("organization-cli-receipt/v1"),
                  "manifest" -> Json.fromString(receipt.hex),
                  "attempts" -> Json.fromInt(batch.outcomes.size),
                  "completed" -> Json.fromInt(batch.outcomes.count(_.run.nonEmpty))
                )
              )
            )
            if batch.outcomes.exists(_.failure.nonEmpty) then 3
            else if batch.outcomes.isEmpty then 4
            else 0
      case _ =>
        refuse("organizationMap STUDY MAPPING_CONFIG Discourse|StoryWorld OUTPUT [--resume]")

object organizationMap:
  def main(args: Array[String]): Unit =
    val status = OrganizationPipeline.run(args.toList, println, System.err.println)
    if status != 0 then sys.exit(status)
