package storymodel4s.pipeline

import io.circe.{Json, Printer}
import io.circe.jawn.JawnParser
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}
import scala.util.control.NonFatal
import storymodel4s.core.Checksum
import storymodel4s.corpus.intake.ScannerCrosswalkJson

/** Offline preparation of declared scanner sample/index metadata, without opening image arrays. */
object ScannerSampleBuild:
  val JobSchema: String = "scanner-samples-job/v0.1"
  val BundleSchema: String = "scanner-samples/v0.1"
  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
  private val printer = Printer.noSpaces.copy(sortKeys = true, escapeNonAscii = true)
  enum Refusal:
    case JobRead, InvalidJob, OutputExists, OutputWrite
    case Declaration(reason: ScannerCrosswalkJson.Error)
  final case class Written(run: Checksum, layout: Checksum, artifact: Checksum)

  def run(job: Path, output: Path): Either[Refusal, Written] =
    val input =
      try Right(Files.readAllBytes(job))
      catch case NonFatal(_) => Left(Refusal.JobRead)
    for
      bytes <- input
      text <-
        try
          Right(
            StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString
          )
        catch case NonFatal(_) => Left(Refusal.InvalidJob)
      json <- parser.parse(text).left.map(_ => Refusal.InvalidJob)
      _ <- Either.cond(
        json.asObject.exists(_.keys.toSet == Set("schemaVersion", "run", "layout")) &&
          json.hcursor.get[String]("schemaVersion").contains(JobSchema),
        (),
        Refusal.InvalidJob
      )
      runJson <- json.hcursor.get[Json]("run").left.map(_ => Refusal.InvalidJob)
      run <- ScannerCrosswalkJson.admitRunDeclaration(runJson).left.map(Refusal.Declaration(_))
      layoutJson <- json.hcursor.get[Json]("layout").left.map(_ => Refusal.InvalidJob)
      fields <- layoutJson.asObject.toRight(Refusal.InvalidJob)
      _ <- Either.cond(!fields.contains("run_digest"), (), Refusal.InvalidJob)
      layout <- ScannerCrosswalkJson
        .admitLayoutDeclaration(
          Json.fromJsonObject(fields.add("run_digest", Json.fromString(run.digest.hex))),
          run
        )
        .left
        .map(Refusal.Declaration(_))
      result = Json.obj(
        "schemaVersion" -> Json.fromString(BundleSchema),
        "job_sha256" -> Json.fromString(Checksum.ofBytes(bytes).hex),
        "run" -> ScannerCrosswalkJson.runToJson(run),
        "layout" -> ScannerCrosswalkJson.layoutToJson(layout),
        "scanner_binding" -> Json.obj("status" -> Json.fromString("unestablished"))
      )
      outputBytes = (printer.print(result) + "\n").getBytes(StandardCharsets.UTF_8)
      _ <- publish(output, outputBytes)
    yield Written(run.digest, layout.digest, Checksum.ofBytes(outputBytes))

  private[pipeline] def publish(
      output: Path,
      bytes: Array[Byte],
      write: (Path, Array[Byte]) => Unit = (path, content) => {
        Files.write(path, content, StandardOpenOption.WRITE): Unit
      }
  ): Either[Refusal, Unit] =
    var pending = Option.empty[Path]
    try
      val path = output.toAbsolutePath
      val staging = Files.createTempFile(path.getParent, ".scanner-samples-", ".pending")
      pending = Some(staging)
      write(staging, bytes)
      Files.createLink(path, staging): Unit
      Right(())
    catch
      case _: FileAlreadyExistsException => Left(Refusal.OutputExists)
      case NonFatal(_)                   => Left(Refusal.OutputWrite)
    finally
      pending.foreach { path =>
        try { Files.deleteIfExists(path): Unit }
        catch case NonFatal(_) => ()
      }

@main def scannerSamples(job: String, out: String): Unit =
  val result =
    try ScannerSampleBuild.run(Path.of(job), Path.of(out))
    catch case NonFatal(_) => Left(ScannerSampleBuild.Refusal.InvalidJob)
  result match
    case Right(done) =>
      println(
        Json
          .obj(
            "run_digest" -> Json.fromString(done.run.hex),
            "layout_digest" -> Json.fromString(done.layout.hex),
            "artifact_sha256" -> Json.fromString(done.artifact.hex)
          )
          .noSpaces
      )
    case Left(error) =>
      System.err.println(Json.obj("error" -> Json.fromString(error.toString)).noSpaces)
      sys.exit(2)
