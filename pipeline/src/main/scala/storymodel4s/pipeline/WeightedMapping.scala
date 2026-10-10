package storymodel4s.pipeline

import cats.syntax.all.*
import io.circe.Json
import java.nio.file.Path
import storymodel4s.align.StructuralWeight
import storymodel4s.codec.{Canonical, WeightedMappingExchange}
import storymodel4s.core.Checksum

/** A declared sweep executes interior profiles once per successful run and preserves every attempt.
  */
object WeightedMapping:
  val SchemaVersion: String = "weighted-mapping-batch/v1"

  private[pipeline] def artifacts(
      batch: MappingPipeline.Batch,
      weights: Vector[StructuralWeight]
  ): Either[String, (Vector[(String, String)], String)] =
    for
      _ <- Either.cond(
        weights.nonEmpty && weights.distinct.size == weights.size,
        (),
        "empty or duplicate weight sweep"
      )
      _ <- Either.cond(
        batch.config.requested == Vector(MappingPipeline.Capability.Mapping),
        (),
        "MissingCapability: weighted organization/order readouts are unavailable"
      )
      base <- MappingPipeline.exchangeArtifacts(batch)
      members <- batch.outcomes
        .flatMap(_.run)
        .traverse { run =>
          weights.traverse { weight =>
            for
              selection <- run.execution.weighted(weight).left.map(_.toString)
              bundle <- WeightedMappingExchange.encode(selection).left.map(_.toString)
            yield
              val prefix = s"${run.identity.hex}--${weight.numerator}-${weight.denominator}--"
              val descriptor = Json.obj(
                "run" -> Json.fromString(run.identity.hex),
                "lambda" -> Json.fromString(weight.toString),
                "prefix" -> Json.fromString(prefix),
                "manifest" -> Json.fromString(bundle.digest.hex)
              )
              descriptor -> bundle.files.map((name, content) => (prefix + name) -> content)
          }
        }
        .map(_.flatten)
      (baseFiles, baseManifest) = base
      files = (("manifest.json" -> baseManifest) +: baseFiles).map((name, content) =>
        ("base--" + name) -> content
      ) ++
        members.flatMap(_._2)
      metadata = Json.obj(
        "baseManifest" -> Json.fromString(Checksum.ofText(baseManifest).hex),
        "weights" -> Json.fromValues(weights.map(w => Json.fromString(w.toString))),
        "members" -> Json.fromValues(members.map(_._1))
      )
    yield files -> StudyArtifacts.manifest(SchemaVersion, files, metadata)

  def publish(
      batch: MappingPipeline.Batch,
      weights: Vector[StructuralWeight],
      output: Path,
      resume: Boolean
  ): Either[String, Checksum] =
    artifacts(batch, weights).flatMap { (files, manifest) =>
      StudyArtifacts.publish(output, files, manifest, resume).left.map(_.toString)
    }

/** Exact fractions in a comma-separated sweep are the only accepted weight syntax. */
object MappingWeightCli:
  val Usage: String = "mappingWeight STUDY.json CONFIG.json N/D[,N/D...] OUTPUT [--resume]"

  def run(args: List[String], out: String => Unit, err: String => Unit): Int =
    def refuse(reason: String): Int = { err(reason); 2 }
    def execute(
        study: String,
        config: String,
        declared: String,
        destination: String,
        resume: Boolean
    ): Int =
      val result = for
        weights <- declared
          .split(",", -1)
          .toVector
          .traverse(StructuralWeight.parse)
          .left
          .map(_.toString)
        path <- scala.util
          .Try(Path.of(config).toAbsolutePath)
          .toEither
          .left
          .map(_ => "invalid config path")
        bytes <- StudyPreparation
          .read(path.getParent, path.getFileName.toString)
          .left
          .map(_.toString)
        text <- StudyPreparation.utf8(bytes).left.map(_.toString)
        settings <- MappingCli.config(text)
        prepared <- StudyCli.load(study).left.map(_.toString)
        batch <- MappingPipeline.batch(prepared, settings).left.map(_.toString)
        output <- scala.util.Try(Path.of(destination)).toEither.left.map(_ => "invalid output path")
        receipt <- WeightedMapping.publish(batch, weights, output, resume)
      yield (batch, receipt)
      result match
        case Left(reason)            => refuse(reason)
        case Right((batch, receipt)) =>
          out(
            Canonical.print(
              Json.obj(
                "manifest" -> Json.fromString(receipt.hex),
                "attempts" -> Json.fromInt(batch.outcomes.size),
                "completed" -> Json.fromInt(batch.outcomes.count(_.run.nonEmpty)),
                "readouts" -> Json.arr(Json.fromString("mapping"))
              )
            )
          )
          if batch.outcomes.exists(_.failure.nonEmpty) then 3 else 0
    args match
      case List("--help")                       => out(Usage); 0
      case List(study, config, weights, output) => execute(study, config, weights, output, false)
      case List(study, config, weights, output, "--resume") =>
        execute(study, config, weights, output, true)
      case _ => refuse(Usage)

object mappingWeight:
  def main(args: Array[String]): Unit =
    val status = MappingWeightCli.run(args.toList, println, System.err.println)
    if status != 0 then sys.exit(status)
