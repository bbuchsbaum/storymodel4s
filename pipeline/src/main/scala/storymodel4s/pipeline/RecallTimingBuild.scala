package storymodel4s.pipeline

import cats.syntax.all.*
import io.circe.{Decoder, Json, Printer}
import io.circe.jawn.JawnParser
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}
import scala.util.control.NonFatal
import storymodel4s.codec.{Canonical, RecallTimingCodecs}
import storymodel4s.codec.RecallCodecs.given
import storymodel4s.core.*
import storymodel4s.corpus.intake.RecallTimingIntake
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.recall.RecallTiming.*

/** Offline intake orchestrator. Job files declare context; the portable constructors check it. */
object RecallTimingBuild:
  val JobSchema: String = "recall-timing-intake-job/v0.1"
  val ReceiptSchema: String = "recall-timing-intake-receipt/v0.1"
  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
  private val printer = Printer.noSpaces.copy(sortKeys = true, escapeNonAscii = true)

  enum Refusal:
    case JobRead, InvalidJob, InputRead, InputIdentity, InvalidGraph, InvalidInventory
    case Intake(reason: RecallTimingIntake.Refusal)
    case OutputExists, OutputWrite

  final case class Written(timing: Checksum, inventory: Checksum, words: Int, records: Int)
  private final case class Input(path: String, sha256: Checksum)
  private final case class Job(
      csv: Input,
      graph: Input,
      parserArtifact: Checksum,
      inventory: Checksum,
      spans: Vector[TextSpan],
      columns: RecallTimingIntake.Columns
  )

  private def fields(json: Json, names: String*): Either[Refusal, Unit] =
    Either.cond(
      json.asObject.exists(_.keys.toSet == names.toSet),
      (),
      Refusal.InvalidJob
    )
  private def field[A: Decoder](json: Json, key: String): Either[Refusal, A] =
    json.hcursor.get[A](key).left.map(_ => Refusal.InvalidJob)
  private def checksum(json: Json, key: String): Either[Refusal, Checksum] =
    field[String](json, key).flatMap(s => Checksum.from(s).left.map(_ => Refusal.InvalidJob))
  private def input(json: Json): Either[Refusal, Input] =
    for
      _ <- fields(json, "path", "sha256")
      path <- field[String](json, "path")
      _ <- Either.cond(path.nonEmpty, (), Refusal.InvalidJob)
      pin <- checksum(json, "sha256")
    yield Input(path, pin)

  private def job(bytes: Array[Byte]): Either[Refusal, Job] =
    for
      text <- utf8(bytes).left.map(_ => Refusal.InvalidJob)
      json <- parser.parse(text).left.map(_ => Refusal.InvalidJob)
      _ <- fields(
        json,
        "schemaVersion",
        "csv",
        "graph",
        "parserArtifact",
        "inventoryDigest",
        "wordSpans",
        "columns"
      )
      version <- field[String](json, "schemaVersion")
      _ <- Either.cond(version == JobSchema, (), Refusal.InvalidJob)
      csv <- field[Json](json, "csv").flatMap(input)
      graph <- field[Json](json, "graph").flatMap(input)
      artifact <- checksum(json, "parserArtifact")
      inventory <- checksum(json, "inventoryDigest")
      spansJson <- field[Vector[Json]](json, "wordSpans")
      spans <- spansJson.traverse { span =>
        for
          _ <- fields(span, "start", "endExclusive")
          start <- field[Int](span, "start")
          end <- field[Int](span, "endExclusive")
          value <- TextSpan.of(start, end).left.map(_ => Refusal.InvalidJob)
        yield value
      }
      columns <- field[Json](json, "columns")
      _ <- fields(columns, "header", "word", "onset", "clock")
      header <- field[Vector[String]](columns, "header")
      word <- field[Int](columns, "word")
      onset <- field[Int](columns, "onset")
      key <- field[String](columns, "clock")
      clock <- ClockKey.from(key).left.map(_ => Refusal.InvalidJob)
    yield Job(
      csv,
      graph,
      artifact,
      inventory,
      spans,
      RecallTimingIntake.Columns(header, word, onset, clock)
    )

  private def utf8(bytes: Array[Byte]): Either[Refusal, String] =
    try
      Right(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch case NonFatal(_) => Left(Refusal.InputRead)

  private def read(path: Path): Either[Refusal, Array[Byte]] =
    try Right(Files.readAllBytes(path))
    catch case NonFatal(_) => Left(Refusal.InputRead)

  private def readInput(base: Path, value: Input): Either[Refusal, Array[Byte]] =
    try
      for
        bytes <- read(base.resolve(value.path))
        _ <- Either.cond(Checksum.ofBytes(bytes) == value.sha256, (), Refusal.InputIdentity)
      yield bytes
    catch case NonFatal(_) => Left(Refusal.InputRead)

  /** Output directory must be new. The manifest is written last and is the completion marker; any
    * failed write retains its partial directory for inspection and cannot be mistaken for a
    * complete bundle. Neither a retry nor concurrent execution can overwrite it.
    */
  def run(jobPath: Path, output: Path): Either[Refusal, Written] =
    for
      rawJob <- read(jobPath).left.map(_ => Refusal.JobRead)
      config <- job(rawJob)
      base = jobPath.toAbsolutePath.getParent
      graphBytes <- readInput(base, config.graph)
      graphText <- utf8(graphBytes)
      graphJson <- parser.parse(graphText).left.map(_ => Refusal.InvalidGraph)
      graph <- Canonical
        .decodeJson[RecallGraph[Checked]](graphJson)
        .left
        .map(_ => Refusal.InvalidGraph)
      _ <- Either.cond(
        Canonical.parse(Canonical.encode(graph)).contains(graphJson),
        (),
        Refusal.InvalidGraph
      )
      inventory <- RecallInventory
        .of(graph, config.spans, WordIdPolicy.inputArtifact(config.parserArtifact))
        .left
        .map(_ => Refusal.InvalidInventory)
      _ <- Either.cond(inventory.digest == config.inventory, (), Refusal.InvalidInventory)
      csv <- readInput(base, config.csv)
      result <- RecallTimingIntake
        .read(csv, config.csv.sha256, config.columns, graph, inventory)
        .left
        .map(Refusal.Intake(_))
      timing = RecallTimingCodecs.encode(result.timing)
      receipt = printer.print(receiptJson(result, Checksum.ofBytes(rawJob), config.graph.sha256))
      _ <- publish(output, timing, receipt)
    yield Written(result.timing.digest, inventory.digest, inventory.words.size, result.records.size)

  private def str(s: String): Json = Json.fromString(s)
  private def rat(value: ExactRational): Json = Json.obj(
    "numerator" -> str(value.numerator.toString),
    "denominator" -> str(value.denominator.toString)
  )
  private def observation(value: Observation): Json = value match
    case Observation.Missing(reason) =>
      Json.obj("status" -> str("missing"), "reason" -> str(reason.toString))
    case Observation.OnsetOnly(seconds, _) =>
      Json.obj("status" -> str("onset-only"), "seconds" -> rat(seconds))
    case Observation.Interval(_, _, _) =>
      throw new IllegalStateException("onset intake produced an interval")

  private def receiptJson(
      result: RecallTimingIntake.Result,
      jobHash: Checksum,
      graphHash: Checksum
  ): Json =
    val timing = result.timing
    Json.obj(
      "schemaVersion" -> str(ReceiptSchema),
      "jobSha256" -> str(jobHash.hex),
      "graphSha256" -> str(graphHash.hex),
      "csvSha256" -> str(timing.clock.artifact.hex),
      "csvByteLength" -> Json.fromInt(result.byteLength),
      "parserArtifact" -> str(timing.inventory.idPolicy.artifact.hex),
      "inventoryDigest" -> str(timing.inventory.digest.hex),
      "timingDigest" -> str(timing.digest.hex),
      "correspondenceDigest" -> str(timing.provenance.correspondence.hex),
      "columnsDigest" -> str(result.columns.digest.hex),
      "selectedColumn" -> Json.fromInt(result.columns.onset),
      "clock" -> str(timing.clock.key.value),
      "recipe" -> str(RecallTimingIntake.Recipe),
      "parser" -> str(RecallTimingIntake.Parser),
      "recordingIdentityBinding" -> str("not-established"),
      "scannerAlignment" -> str("not-established"),
      "records" -> Json.fromValues(result.records.map { record =>
        val binding = record.binding match
          case RecallTimingIntake.RecordBinding.Matched(word) =>
            Json.obj("status" -> str("matched"), "word" -> str(word.value))
          case RecallTimingIntake.RecordBinding.ExcludedBlankWord =>
            Json.obj("status" -> str("excluded"), "reason" -> str("blank-word-field-after-trim"))
        Json.obj(
          "record" -> Json.fromInt(record.number),
          "binding" -> binding,
          "observation" -> observation(record.observation)
        )
      })
    )

  private def writeNew(path: Path, bytes: Array[Byte]): Unit =
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE): Unit

  private[pipeline] def publish(
      output: Path,
      timing: String,
      receipt: String,
      write: (Path, Array[Byte]) => Unit = writeNew
  ): Either[Refusal, Unit] =
    try
      Files.createDirectory(output): Unit
      val files = Vector("recall-timing.json" -> timing, "intake-receipt.json" -> receipt)
      val entries = files.map { (name, text) =>
        val bytes = (text + "\n").getBytes(StandardCharsets.UTF_8)
        write(output.resolve(name), bytes)
        Json.obj("path" -> str(name), "sha256" -> str(Checksum.ofBytes(bytes).hex))
      }
      val manifest = Json.obj(
        "schemaVersion" -> str("recall-timing-intake-bundle/v0.1"),
        "files" -> Json.fromValues(entries)
      )
      val pending = output.resolve(".manifest.pending")
      write(pending, (printer.print(manifest) + "\n").getBytes(StandardCharsets.UTF_8))
      // Same-directory hard link publishes only complete bytes, atomically and without replacement.
      Files.createLink(output.resolve("manifest.json"), pending): Unit
      // A leftover staging name cannot invalidate an already published, complete bundle.
      try Files.delete(pending)
      catch case NonFatal(_) => ()
      Right(())
    catch
      case _: FileAlreadyExistsException => Left(Refusal.OutputExists)
      case NonFatal(_)                   => Left(Refusal.OutputWrite)

@main def recallTimingIntake(job: String, out: String): Unit =
  val result =
    try RecallTimingBuild.run(Path.of(job), Path.of(out))
    catch case NonFatal(_) => Left(RecallTimingBuild.Refusal.InvalidJob)
  result match
    case Right(done) =>
      println(
        Json
          .obj(
            "timingDigest" -> Json.fromString(done.timing.hex),
            "inventoryDigest" -> Json.fromString(done.inventory.hex),
            "words" -> Json.fromInt(done.words),
            "records" -> Json.fromInt(done.records)
          )
          .noSpaces
      )
    case Left(error) =>
      System.err.println(Json.obj("error" -> Json.fromString(error.toString)).noSpaces)
      sys.exit(2)
