package storymodel4s.pipeline

import cats.syntax.all.*
import io.circe.Json
import java.nio.file.{Files, LinkOption, Path}
import scala.util.control.NonFatal
import storymodel4s.codec.{Canonical, SurfaceWorkspaceCodecs}
import storymodel4s.core.Checksum
import storymodel4s.view.WorkspaceContentGrant

/** One ordinary-file workflow publishes checked local inspection packets and complete cohort
  * outcomes.
  */
object StudyWorkspaceProducer:
  private def kind(source: StudyPreparation.SourceInput): SurfaceWorkspaceCodecs.SourceKind =
    source match
      case StudyPreparation.SourceInput.Text(_, k) => SurfaceWorkspaceCodecs.SourceKind.Text(k)
      case StudyPreparation.SourceInput.AdmittedText(_, k) =>
        SurfaceWorkspaceCodecs.SourceKind.Text(k)
      case StudyPreparation.SourceInput.CheckedText(_, k) =>
        SurfaceWorkspaceCodecs.SourceKind.Text(k)
      case StudyPreparation.SourceInput.SurfaceAtlas(_, _, k) =>
        SurfaceWorkspaceCodecs.SourceKind.Text(k)
      case StudyPreparation.SourceInput.StoryModel(_) =>
        SurfaceWorkspaceCodecs.SourceKind.NarrativeModel
      case StudyPreparation.SourceInput.CheckedAV(_, _, _, _, _) =>
        SurfaceWorkspaceCodecs.SourceKind.AnnotationVideo
      case StudyPreparation.SourceInput.CheckedVideo(_) =>
        SurfaceWorkspaceCodecs.SourceKind.VideoCaptions
      case StudyPreparation.SourceInput.TimedAnnotations(_, _) |
          StudyPreparation.SourceInput.CheckedTimed(_) =>
        SurfaceWorkspaceCodecs.SourceKind.TimedAnnotations
      case StudyPreparation.SourceInput.Video(_) => SurfaceWorkspaceCodecs.SourceKind.VideoCaptions

  /** Check every prepared disclosure before producing any packet or creating an output directory.
    */
  private[pipeline] def artifacts(
      batch: MappingPipeline.Batch,
      revision: String
  ): Either[String, (Vector[(String, String)], String)] =
    for
      _ <- Either.cond(revision.matches("[0-9a-f]{40}"), (), "invalid producer revision")
      _ <- batch.outcomes
        .flatMap(_.prepared)
        .traverse_(StudyPreparation.publishRecall(_).left.map(_.toString))
      packets <- batch.outcomes.traverse { outcome =>
        (outcome.prepared, outcome.run) match
          case (Some(recall), Some(run)) =>
            SurfaceWorkspaceCodecs
              .create(
                batch.prepared.source.artifact,
                kind(batch.prepared.config.source),
                recall.graph,
                recall.inventory,
                run.reference,
                run.reconstruction,
                RecallDisclosure.receiptDescription(recall.disclosure),
                revision,
                WorkspaceContentGrant.Granted,
                WorkspaceContentGrant.Granted
              )
              .flatMap(w =>
                w.exportBytes.map(bytes =>
                  Some(s"${recall.observation.hex}-workspace.json" -> bytes)
                )
              )
              .left
              .map(_.toString)
          case _ => Right(None)
      }
      files = packets.flatten
      metadata = Json.obj(
        "plan" -> Json.fromString(batch.identity.hex),
        "sourceEdition" -> Json.fromString(batch.prepared.source.edition.hex),
        "sourceRepresentation" -> Json.fromString(batch.prepared.source.representation.digest.hex),
        "producerRevision" -> Json.fromString(revision),
        "qualification" -> Json.fromString("local-imported-inspection"),
        "outcomes" -> Json.fromValues(batch.outcomes.zip(packets).map { (o, packet) =>
          Json.obj(
            "participant" -> Json.fromString(o.participant),
            "session" -> Json.fromString(o.session),
            "observation" -> o.observation.fold(Json.Null)(h => Json.fromString(h.hex)),
            "workspace" -> packet.fold(Json.Null)(p => Json.fromString(p._1)),
            "failure" -> o.failure.fold(Json.Null)(Json.fromString),
            "readiness" -> o.run
              .fold(Json.fromString("not-executed"))(r => Json.fromString(r.readiness.toString)),
            "units" -> Json.fromValues(
              o.prepared.toVector.flatMap(_.inventory.units).map(u => Json.fromString(u.id.value))
            )
          )
        })
      )
    yield files -> StudyArtifacts.manifest("study-workspaces/v1", files, metadata)

  /** The content-addressed source cache is validated on reuse and never overwrites an old edition.
    */
  def publish(
      batch: MappingPipeline.Batch,
      revision: String,
      cache: Path,
      output: Path,
      resume: Boolean
  ): Either[String, Checksum] =
    for
      bundle <- artifacts(batch, revision)
      (files, manifest) = bundle
      _ <-
        try
          if Files.exists(cache, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(
              cache,
              LinkOption.NOFOLLOW_LINKS
            )
          then Left("invalid source cache")
          else { Files.createDirectories(cache): Unit; Right(()) }
        catch case NonFatal(_) => Left("source cache unavailable")
      sourceFiles = Vector("source.json" -> batch.prepared.source.artifact)
      sourceManifest = StudyArtifacts.manifest(
        "study-source-cache/v1",
        sourceFiles,
        Json.obj("edition" -> Json.fromString(batch.prepared.source.edition.hex))
      )
      _ <- StudyArtifacts
        .publish(
          cache.resolve(batch.prepared.source.edition.hex),
          sourceFiles,
          sourceManifest,
          true
        )
        .left
        .map(_.toString)
      receipt <- StudyArtifacts.publish(output, files, manifest, resume).left.map(_.toString)
    yield receipt

/** Executable study entry point; all science is delegated to preparation and the existing facade.
  */
object StudyWorkspaceCli:
  val Usage =
    "studyWorkspace validate STUDY.json CONFIG.json | run STUDY.json CONFIG.json CACHE OUTPUT PRODUCER_SHA [--resume]"
  private def load(study: String, settings: String): Either[String, MappingPipeline.Batch] = for
    path <- scala.util
      .Try(Path.of(settings).toAbsolutePath)
      .toEither
      .left
      .map(_ => "invalid config path")
    bytes <- StudyPreparation.read(path.getParent, path.getFileName.toString).left.map(_.toString)
    text <- StudyPreparation.utf8(bytes).left.map(_.toString)
    config <- MappingCli.config(text)
    prepared <- StudyCli.load(study).left.map(_.toString)
    batch <- MappingPipeline.batch(prepared, config).left.map(_.toString)
  yield batch

  def run(args: List[String], out: String => Unit, err: String => Unit): Int =
    def refuse(reason: String): Int = { err(reason); 2 }
    def execute(
        study: String,
        settings: String,
        destination: Option[(String, String, String, Boolean)]
    ): Int =
      val result = for
        batch <- load(study, settings)
        receipt <- destination.traverse { (cache, output, revision, resume) =>
          for
            paths <- scala.util
              .Try(Path.of(cache) -> Path.of(output))
              .toEither
              .left
              .map(_ => "invalid output path")
            receipt <- StudyWorkspaceProducer.publish(batch, revision, paths._1, paths._2, resume)
          yield receipt
        }
      yield (batch, receipt)
      result match
        case Left(e)                 => refuse(e)
        case Right((batch, receipt)) =>
          out(
            Canonical.print(
              Json.obj(
                "sourceEdition" -> Json.fromString(batch.prepared.source.edition.hex),
                "attempts" -> Json.fromInt(batch.outcomes.size),
                "completed" -> Json.fromInt(batch.outcomes.count(_.run.nonEmpty)),
                "disclosureReady" -> Json.fromBoolean(
                  batch.outcomes
                    .flatMap(_.prepared)
                    .forall(r => StudyPreparation.publishRecall(r).isRight)
                ),
                "manifest" -> receipt.fold(Json.Null)(r => Json.fromString(r.hex))
              )
            )
          )
          if batch.outcomes.exists(_.failure.nonEmpty) then 3
          else if batch.outcomes
              .flatMap(_.run)
              .exists(_.readiness != MappingPipeline.Readiness.Ready)
          then 4
          else 0
    args match
      case List("--help")                                        => out(Usage); 0
      case List("validate", study, settings)                     => execute(study, settings, None)
      case List("run", study, settings, cache, output, revision) =>
        execute(study, settings, Some((cache, output, revision, false)))
      case List("run", study, settings, cache, output, revision, "--resume") =>
        execute(study, settings, Some((cache, output, revision, true)))
      case _ => refuse(Usage)

object studyWorkspace:
  def main(args: Array[String]): Unit =
    val status = StudyWorkspaceCli.run(args.toList, println, System.err.println)
    if status != 0 then sys.exit(status)
