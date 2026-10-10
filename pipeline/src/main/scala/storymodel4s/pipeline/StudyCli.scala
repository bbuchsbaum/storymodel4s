package storymodel4s.pipeline

import cats.syntax.all.*
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileAlreadyExistsException, Files, LinkOption, Path, StandardOpenOption}
import scala.util.control.NonFatal
import storymodel4s.codec.Canonical
import storymodel4s.core.Checksum

/** Shared completion-marker publication; a retry verifies all bytes before reusing a run. */
private[pipeline] object StudyArtifacts:
  enum Refusal:
    case OutputExists, OutputWrite, Incomplete, Corrupt
  private def writeNew(path: Path, bytes: Array[Byte]): Unit =
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE): Unit
  def manifest(schema: String, files: Vector[(String, String)], metadata: Json): String =
    Canonical.print(
      Json.obj(
        "schemaVersion" -> Json.fromString(schema),
        "metadata" -> metadata,
        "files" -> Json.fromValues(files.map { (name, content) =>
          Json.obj(
            "path" -> Json.fromString(name),
            "sha256" -> Json.fromString(Checksum.ofBytes(content.getBytes(UTF_8)).hex)
          )
        })
      )
    )
  private def safeName(name: String): Boolean = name.matches("[a-zA-Z0-9._-]+") &&
    name != "." && name != ".." && name != "manifest.json"

  def verify(output: Path, expectedManifest: String): Either[Refusal, Unit] =
    try
      val completion = output.resolve("manifest.json")
      if !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(
          completion,
          LinkOption.NOFOLLOW_LINKS
        )
      then Left(Refusal.Incomplete)
      else
        for
          _ <- Either.cond(
            Files.readString(completion, UTF_8) == expectedManifest,
            (),
            Refusal.Corrupt
          )
          json <- Canonical.parse(expectedManifest).left.map(_ => Refusal.Corrupt)
          entries <- json.hcursor.get[Vector[Json]]("files").left.map(_ => Refusal.Corrupt)
          _ <- entries.traverse_ { entry =>
            for
              name <- entry.hcursor.get[String]("path").left.map(_ => Refusal.Corrupt)
              hash <- entry.hcursor.get[String]("sha256").left.map(_ => Refusal.Corrupt)
              _ <- Either.cond(safeName(name), (), Refusal.Corrupt)
              path = output.resolve(name)
              _ <- Either.cond(
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
                (),
                Refusal.Corrupt
              )
              _ <- Either.cond(
                Checksum.ofBytes(Files.readAllBytes(path)).hex == hash,
                (),
                Refusal.Corrupt
              )
            yield ()
          }
        yield ()
    catch case NonFatal(_) => Left(Refusal.Corrupt)

  def publish(
      output: Path,
      files: Vector[(String, String)],
      completion: String,
      resume: Boolean,
      write: (Path, Array[Byte]) => Unit = writeNew
  ): Either[Refusal, Checksum] =
    if files.map(_._1).distinct.size != files.size || !files.forall(f => safeName(f._1)) then
      Left(Refusal.OutputWrite)
    else if Files.exists(output, LinkOption.NOFOLLOW_LINKS) then
      if resume then verify(output, completion).map(_ => Checksum.ofText(completion))
      else Left(Refusal.OutputExists)
    else
      try
        Files.createDirectory(output): Unit
        files.foreach { (name, content) => write(output.resolve(name), content.getBytes(UTF_8)) }
        // Verify the bytes actually written before exposing the completion marker.
        if !files.forall { (name, content) =>
            Files.readAllBytes(output.resolve(name)).sameElements(content.getBytes(UTF_8))
          }
        then Left(Refusal.Corrupt)
        else
          val pending = output.resolve(".manifest.pending")
          write(pending, completion.getBytes(UTF_8))
          if !Files.readAllBytes(pending).sameElements(completion.getBytes(UTF_8)) then
            Left(Refusal.Corrupt)
          else
            Files.createLink(output.resolve("manifest.json"), pending): Unit
            try Files.deleteIfExists(pending): Unit
            catch case NonFatal(_) => ()
            verify(output, completion).map(_ => Checksum.ofText(completion))
      catch
        case _: FileAlreadyExistsException => Left(Refusal.OutputExists)
        case NonFatal(_)                   => Left(Refusal.OutputWrite)

/** Thin local command for preparation and preview; configuration is resolved exactly once. */
object StudyCli:
  val Usage: String =
    "studyPrepare template | validate STUDY.json | prepare STUDY.json OUTPUT [--resume]"
  val Template: String = """{
    "schemaVersion": "storymodel-study/v1",
    "source": {"format":"text", "path":"source.txt", "kind":"Sentence"},
    "profile":"recall-baseline-lexical-words/v1",
    "recalls":[{"participant":"p1", "session":"s1", "path":"recall.txt", "timing":null}]
  }"""

  private[pipeline] def artifacts(
      value: StudyPreparation.Prepared
  ): (Vector[(String, String)], String) =
    val source = "source.json" -> value.source.artifact
    val recalls = value.outcomes
      .flatMap(_.result.toOption)
      .map(r => s"recall-${r.observation.hex}.json" -> StudyPreparation.encode(r))
    val files = source +: recalls
    val metadata = preview(value)
    files -> StudyArtifacts.manifest("prepared-study/v1", files, metadata)

  private def preview(value: StudyPreparation.Prepared): Json = Json.obj(
    "sourceEdition" -> Json.fromString(StudyPreparation.edition(value.source).hex),
    "sourceRepresentation" -> Json.fromString(value.source.representation.digest.hex),
    "sourceTargets" -> Json.fromInt(value.source.nodes.size),
    "profile" -> Json.fromString(value.config.profile),
    "network" -> Json.fromString("offline"),
    "runtime" -> Json.fromString("JVM"),
    "requiredModelAssets" -> Json.arr(),
    "contentPolicy" -> Json.fromString("local files only; no provider calls"),
    "sourceCoverage" -> Json.fromString(
      "declared source inputs; no completeness of visual descriptions asserted"
    ),
    "outcomes" -> Json.fromValues(value.outcomes.map { o =>
      val fields = Vector(
        "attempt" -> Json.fromString(o.attempt.hex),
        "participant" -> Json.fromString(o.input.participant),
        "session" -> Json.fromString(o.input.session)
      )
      val result = o.result match
        case Left(e) =>
          Vector("status" -> Json.fromString("refused"), "reason" -> Json.fromString(e.toString))
        case Right(r) =>
          Vector(
            "status" -> Json.fromString("prepared"),
            "observation" -> Json.fromString(r.observation.hex),
            "inventory" -> Json.fromString(r.inventory.digest.hex),
            "words" -> Json.fromInt(r.inventory.words.size),
            "units" -> Json.fromInt(r.inventory.units.size),
            "timing" -> Json.fromBoolean(r.timing.nonEmpty)
          )
      Json.obj((fields ++ result)*)
    })
  )

  private[pipeline] def load(
      path: String
  ): Either[StudyPreparation.Refusal, StudyPreparation.Prepared] =
    try
      val input = Path.of(path).toAbsolutePath
      for
        bytes <- StudyPreparation.read(input.getParent, input.getFileName.toString)
        text <- StudyPreparation.utf8(bytes)
        config <- StudyPreparation.config(text)
        prepared <- StudyPreparation.prepare(input.getParent, config)
      yield prepared
    catch case NonFatal(_) => Left(StudyPreparation.Refusal.InputRead)

  /** Exit 0 means all requested inputs prepared; 3 preserves a valid partial batch; 2 refuses. */
  def run(args: List[String], out: String => Unit, err: String => Unit): Int =
    def refused(reason: String): Int =
      err(
        Canonical.print(
          Json.obj("status" -> Json.fromString("refused"), "reason" -> Json.fromString(reason))
        )
      )
      2
    def process(path: String, output: Option[String], resume: Boolean): Int = load(path) match
      case Left(e)      => refused(e.toString)
      case Right(value) =>
        val result = output match
          case None              => Right(())
          case Some(destination) =>
            val (files, manifest) = artifacts(value)
            scala.util
              .Try(Path.of(destination))
              .toEither
              .left
              .map(_ => StudyArtifacts.Refusal.OutputWrite)
              .flatMap(path => StudyArtifacts.publish(path, files, manifest, resume))
              .map(_ => ())
        result match
          case Left(e)  => refused(e.toString)
          case Right(_) =>
            out(Canonical.print(preview(value)))
            if value.outcomes.exists(_.result.isLeft) then 3 else 0
    args match
      case List("template")                          => out(Template); 0
      case List("--help")                            => out(Usage); 0
      case List("validate", path)                    => process(path, None, false)
      case List("prepare", path, output)             => process(path, Some(output), false)
      case List("prepare", path, output, "--resume") => process(path, Some(output), true)
      case _                                         => refused(Usage)

object studyPrepare:
  def main(args: Array[String]): Unit =
    val status = StudyCli.run(args.toList, println, System.err.println)
    if status != 0 then sys.exit(status)
