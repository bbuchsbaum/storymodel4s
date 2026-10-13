package storymodel4s.pipeline

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.jawn.JawnParser
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import storymodel4s.codec.{Canonical, RecallTimingCodecs}
import storymodel4s.core.*
import storymodel4s.corpus.intake.{RecallTimingIntake, ScannerCrosswalk, ScannerCrosswalkJson}
import storymodel4s.recall.*

/** Attach onset observations to declared raw-volume bins while keeping unit measures undivided. */
object RecallVolumeExport:
  val Schema: String = "recall-volume-export/v1"
  val JobSchema: String = "recall-volume-job/v1"
  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
  private type Result[A] = Either[String, A]
  private def str(s: String): Json = Json.fromString(s)
  private def field[A: Decoder](j: Json, key: String): Result[A] =
    j.hcursor.get[A](key).left.map(_ => "invalid-job-field:" + key)
  private def keys(j: Json, expected: String*): Result[Unit] =
    Either.cond(j.asObject.exists(_.keys.toSet == expected.toSet), (), "unknown-or-missing-fields")
  private def rat(v: ExactRational): Json = Json.obj(
    "numerator" -> str(v.numerator.toString), "denominator" -> str(v.denominator.toString))
  private def rational(j: Json): Result[ExactRational] = for
    _ <- keys(j, "numerator", "denominator")
    n <- field[String](j, "numerator").flatMap(_.toLongOption.toRight("invalid-rational"))
    d <- field[String](j, "denominator").flatMap(_.toLongOption.toRight("invalid-rational"))
    r <- ExactRational.of(n, d).left.map(_ => "invalid-rational")
    _ <- Either.cond(rat(r) == j, (), "noncanonical-rational")
  yield r
  private def parse(bytes: Array[Byte]): Result[Json] =
    StudyPreparation.utf8(bytes).left.map(_ => "invalid-utf8")
      .flatMap(parser.parse(_).left.map(_ => "invalid-json"))
  private def load(base: Path, pin: Json): Result[Array[Byte]] = for
    _ <- keys(pin, "path", "sha256")
    name <- field[String](pin, "path")
    hash <- field[String](pin, "sha256")
    bytes <- try Right(Files.readAllBytes(base.resolve(name)))
      catch case NonFatal(_) => Left("input-read")
    _ <- Either.cond(Checksum.ofBytes(bytes).hex == hash, (), "input-identity")
  yield bytes

  /** Bin support is explicitly declared; it is not an inferred acquisition exposure window. */
  private[pipeline] def index(at: ExactRational, period: ExactRational, count: Int): Option[Int] =
    val n = BigInt(at.numerator) * period.denominator
    val d = BigInt(at.denominator) * period.numerator
    if n < 0 || n >= d * count then None else Some((n / d).toInt)

  private[pipeline] def attachments(
      intake: RecallTimingIntake.Result,
      run: ScannerCrosswalk.Run,
      period: ExactRational
  ): Json =
    import RecallTiming.Observation
    val inventory = intake.timing.inventory
    val records = intake.records.map { r =>
      val word = r.binding match
        case RecallTimingIntake.RecordBinding.Matched(w) => Some(w)
        case RecallTimingIntake.RecordBinding.ExcludedBlankWord => None
      val unit = word.flatMap(w => inventory.membership(w) match
        case WordMembership.Member(u) => Some(u)
        case _ => None)
      val (onset, volume, state) = r.observation match
        case Observation.OnsetOnly(at, _) =>
          val i = index(at, period, run.sampleCount)
          (rat(at), i, if i.isDefined then "assigned" else "outside-stored-coverage")
        case Observation.Missing(_) => (Json.Null, None, "missing-onset")
        case Observation.Interval(_, _, _) => (Json.Null, None, "unsupported-interval")
      Json.obj(
        "record" -> Json.fromInt(r.number),
        "word" -> word.fold(Json.Null)(w => str(w.value)),
        "unit" -> unit.fold(Json.Null)(u => str(u.value)),
        "correspondence" -> str(if word.isDefined then "matched" else "excluded-blank-word"),
        "onset" -> onset, "timing_status" -> str(state),
        "volume" -> volume.fold(Json.Null)(Json.fromInt))
    }
    def matching(unit: RecallUnitId): Vector[Json] =
      records.filter(_.hcursor.get[String]("unit").contains(unit.value))
    val links = records.flatMap { r => for
      volume <- r.hcursor.get[Int]("volume").toOption
      unit <- r.hcursor.get[String]("unit").toOption
      word <- r.hcursor.get[String]("word").toOption
    yield (volume, unit, word) }.groupMap(v => v._1 -> v._2)(_._3)
    Json.obj(
      "schemaVersion" -> str("recall-onset-attachments/v1"),
      "inventory_digest" -> str(inventory.digest.hex),
      "timing_digest" -> str(intake.timing.digest.hex),
      "run_digest" -> str(run.digest.hex), "period" -> rat(period),
      "sample_count" -> Json.fromInt(run.sampleCount),
      "index_base" -> Json.fromInt(0),
      "semantics" -> str("non-additive-original-unit-onset-attachments"),
      "temporal_mass_allocation" -> str("unavailable-onset-only"),
      "records" -> Json.fromValues(records),
      "volume_units" -> Json.fromValues(links.toVector.sortBy(_._1).map { case ((v, u), words) =>
        Json.obj("volume" -> Json.fromInt(v), "unit" -> str(u),
          "words" -> Json.fromValues(words.map(str)))
      }),
      "units" -> Json.fromValues(inventory.units.map { unit =>
        val rows = matching(unit.id)
        def count(status: String): Int = rows.count(_.hcursor.get[String]("timing_status").contains(status))
        val assigned = count("assigned")
        Json.obj("unit" -> str(unit.id.value), "words" -> Json.fromInt(unit.words.size),
          "assigned" -> Json.fromInt(assigned), "outside" -> Json.fromInt(count("outside-stored-coverage")),
          "missing" -> Json.fromInt(count("missing-onset")),
          "attachment" -> str(if assigned == 0 then "none" else if assigned == unit.words.size then "complete" else "partial"))
      })
    )

  /** Execute the public paired mapper and publish its unchanged exchange with checked timing. */
  def run(jobPath: Path, output: Path): Result[Checksum] =
    for
      bytes <- try Right(Files.readAllBytes(jobPath)) catch case NonFatal(_) => Left("job-read")
      job <- parse(bytes)
      _ <- keys(job, "schemaVersion", "study", "config", "participant", "session", "csv", "columns", "run", "period", "recording")
      schema <- field[String](job, "schemaVersion")
      _ <- Either.cond(schema == JobSchema, (), "unsupported-job")
      base = jobPath.toAbsolutePath.getParent
      studyPin <- field[Json](job, "study")
      studyBytes <- load(base, studyPin)
      studyText <- StudyPreparation.utf8(studyBytes).left.map(_ => "invalid-study")
      study <- StudyPreparation.config(studyText).left.map(_.toString)
      studyName <- field[String](studyPin, "path")
      configPin <- field[Json](job, "config")
      configBytes <- load(base, configPin)
      configText <- StudyPreparation.utf8(configBytes).left.map(_ => "invalid-config")
      config <- MappingCli.config(configText)
      participant <- field[String](job, "participant")
      session <- field[String](job, "session")
      _ <- Either.cond(study.recalls.size == 1 && study.recalls.head.participant == participant &&
        study.recalls.head.session == session, (), "observation-mismatch")
      csvPin <- field[Json](job, "csv")
      csv <- load(base, csvPin)
      csvHash = Checksum.ofBytes(csv)
      columnsJson <- field[Json](job, "columns")
      _ <- keys(columnsJson, "header", "word", "onset", "clock")
      header <- field[Vector[String]](columnsJson, "header")
      word <- field[Int](columnsJson, "word")
      onset <- field[Int](columnsJson, "onset")
      clockName <- field[String](columnsJson, "clock")
      clock <- RecallTiming.ClockKey.from(clockName).left.map(_ => "invalid-clock")
      columns = RecallTimingIntake.Columns(header, word, onset, clock)
      runJson <- field[Json](job, "run")
      run <- ScannerCrosswalkJson.admitRunDeclaration(runJson).left.map(_.toString)
      period <- field[Json](job, "period").flatMap(rational)
      regular <- ScannerCrosswalk.Run.regular(run.key, run.image, run.header, run.sampleCount,
        period, run.origin, run.history).left.map(_.toString)
      _ <- Either.cond(run.digest == regular.digest && run.origin == ScannerCrosswalk.Origin.FirstStoredSample,
        (), "nonregular-or-foreign-origin")
      recording <- field[Json](job, "recording")
      _ <- keys(recording, "participant", "session", "csv_sha256", "clock", "run_key", "basis", "evidence")
      _ <- Either.cond(recording.hcursor.get[String]("participant").contains(participant) &&
        recording.hcursor.get[String]("session").contains(session) &&
        recording.hcursor.get[String]("csv_sha256").contains(csvHash.hex) &&
        recording.hcursor.get[String]("clock").contains(clockName) &&
        recording.hcursor.get[Json]("run_key").contains(runJson.hcursor.downField("key").focus.get),
        (), "recording-binding-mismatch")
      basis <- field[String](recording, "basis")
      _ <- Either.cond(Set("published-release-definition", "synthetic-control")(basis), (), "invalid-basis")
      pins <- field[Vector[Json]](recording, "evidence")
      _ <- Either.cond(pins.nonEmpty, (), "missing-recording-evidence")
      evidence <- pins.traverse(load(base, _).map(Checksum.ofBytes))
      prepared <- StudyPreparation.prepare(base.resolve(studyName).getParent, study).left.map(_.toString)
      recall <- prepared.outcomes.head.result.left.map(_.toString)
      intake <- RecallTimingIntake.readLexical(csv, csvHash, columns, recall.graph, recall.inventory).left.map(_.toString)
      batch <- MappingPipeline.batch(prepared, config).left.map(_.toString)
      _ <- Either.cond(batch.outcomes.forall(_.run.nonEmpty), (), "mapping-failed")
      exchange <- MappingPipeline.exchangeArtifacts(batch)
      (exchangeFiles, exchangeManifest) = exchange
      reference = ScannerCrosswalk.Reference.recall(intake.timing.clock)
      end <- period.*(ExactRational.of(run.sampleCount.toLong, 1).toOption.get).left.map(_ => "extent-overflow")
      domain <- ScannerCrosswalk.Window.of(ExactRational.Zero, end).left.map(_.toString)
      declaration = Checksum.ofText(Canonical.print(recording))
      binding <- ScannerCrosswalk.Binding.declared(reference, run, domain, ExactRational.One, ExactRational.Zero,
        ScannerCrosswalk.Evidence(reference.digest, run.digest, declaration, evidence)).left.map(_.toString)
      attach = attachments(intake, run, period)
      files = exchangeFiles ++ Vector(
        "mapping-exchange-manifest.json" -> exchangeManifest,
        "recall-timing.json" -> RecallTimingCodecs.encode(intake.timing),
        "scanner-run.json" -> Canonical.print(ScannerCrosswalkJson.runToJson(run)),
        "scanner-binding.json" -> ScannerCrosswalkJson.encodeBinding(binding),
        "onset-attachments.json" -> Canonical.print(attach))
      metadata = Json.obj("job_sha256" -> str(Checksum.ofBytes(bytes).hex),
        "participant" -> str(participant), "session" -> str(session),
        "recording" -> recording, "recipe" -> str(RecallTimingIntake.LexicalRecipe),
        "columns" -> columnsJson, "csv_sha256" -> str(csvHash.hex),
        "correspondence_digest" -> str(intake.timing.provenance.correspondence.hex),
        "mapping_plan" -> str(batch.identity.hex),
        "encoding_binding" -> str("unavailable"), "hrf_shift" -> str("not-applied"),
        "crop" -> str("not-applied"), "authority" -> str(ScannerCrosswalkJson.Authority))
      manifest = StudyArtifacts.manifest(Schema, files, metadata)
      result <- StudyArtifacts.publish(output, files, manifest, resume = false).left.map(_.toString)
    yield result

@main def recallVolumes(job: String, out: String): Unit =
  val result = try RecallVolumeExport.run(Path.of(job), Path.of(out))
    catch case NonFatal(_) => Left("invalid-job-or-input")
  result match
    case Right(hash) => println(Json.obj("manifest_sha256" -> Json.fromString(hash.hex)).noSpaces)
    case Left(error) =>
      System.err.println(Json.obj("error" -> Json.fromString(error)).noSpaces)
      sys.exit(2)
