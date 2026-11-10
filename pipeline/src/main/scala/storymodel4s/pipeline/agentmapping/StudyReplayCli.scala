package storymodel4s.pipeline.agentmapping

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import storymodel4s.core.Checksum
import storymodel4s.provider.agent.{Recordings, TextExchange}

/** A study's replay inputs as exported (`storymodel4s.agentmapping.study-export/v1`): the system
  * prompt, the reply rules' parameters, and per participant-session its units and recorded prompts.
  * Nothing in it names a corpus-specific rule.
  */
object StudyExport:
  val Schema: String = "storymodel4s.agentmapping.study-export/v1"

  /** One participant-session: its units and its recorded prompts. */
  final case class Session(
      participant: String,
      session: String,
      units: Vector[StudyReplay.RecallUnit],
      chunks: Vector[StudyReplay.Chunk]
  )

  /** One participant-session as the index lists it. The index, not the session file, names the
    * participant and session, so a session file cannot rename itself or another.
    */
  final case class Entry(
      participant: String,
      session: String,
      file: String,
      hashes: Vector[String]
  ):
    def name: String = s"${participant}_$session"

  /** What every session of the export shares. The reply protocol is recorded here and never chosen
    * by whoever runs the replay: the wrong rules would run without complaint and conclude something
    * other than what was recorded.
    */
  final case class Index(
      model: String,
      condition: String,
      system: String,
      protocol: StudyReplay.ReplyProtocol,
      protocolName: String,
      attempts: Int,
      retrySuffix: String,
      extras: Vector[String],
      unitLine: String,
      chunkLine: String,
      manifestSha256: String,
      sessions: Vector[Entry]
  )

  private val Name = "[A-Za-z0-9][A-Za-z0-9_-]*".r

  private def text(path: Path): Either[String, String] =
    try Right(Files.readString(path))
    catch case NonFatal(_) => Left(s"cannot read $path")

  private def json(path: Path): Either[String, Json] =
    text(path).flatMap(io.circe.jawn.parse(_).left.map(_ => s"$path is not JSON"))

  /** Read and check an index document. */
  def parseIndex(doc: Json): Either[String, Index] =
    val c = doc.hcursor
    val read =
      for
        schema <- c.get[String]("schema")
        model <- c.get[String]("model")
        condition <- c.get[String]("condition")
        system <- c.get[String]("system")
        systemSha <- c.get[String]("systemSha256")
        protocolName <- c.get[String]("replyProtocol")
        attempts <- c.get[Int]("attempts")
        suffix <- c.get[String]("retrySuffix")
        extras <- c.get[Vector[String]]("extras")
        unitLine <- c.get[String]("unitLine")
        chunkLine <- c.get[String]("chunkLine")
        manifest <- c.get[String]("manifestSha256")
        entries <- c.get[Vector[Json]]("sessions")
        sessions <- entries.foldLeft[io.circe.Decoder.Result[Vector[Entry]]](Right(Vector.empty)) {
          (acc, e) =>
            for
              done <- acc
              participant <- e.hcursor.get[String]("participant")
              session <- e.hcursor.get[String]("session")
              file <- e.hcursor.get[String]("file")
              hashes <- e.hcursor.get[Vector[String]]("promptSha256")
            yield done :+ Entry(participant, session, file, hashes)
        }
      yield (
        schema,
        systemSha,
        protocolName,
        model,
        condition,
        system,
        attempts,
        suffix,
        extras,
        unitLine,
        chunkLine,
        manifest,
        sessions
      )
    read.left.map(_ => "index.json is malformed").flatMap {
      case (
            schema,
            systemSha,
            protocolName,
            model,
            condition,
            system,
            attempts,
            suffix,
            extras,
            unitLine,
            chunkLine,
            manifest,
            sessions
          ) =>
        val named = sessions.forall(e => Name.matches(e.participant) && Name.matches(e.session))
        if schema != Schema then Left("not a study export")
        else if Checksum.ofText(system).hex != systemSha then
          Left("the system prompt does not have its recorded hash")
        else if attempts <= 0 || !suffix.contains("{attempt}") || !unitLine.contains("{u}") ||
          !unitLine.contains("{text}") || !chunkLine.contains("{lo}") || !chunkLine.contains("{hi}")
        then Left("index.json has unusable replay parameters")
        else if !named then Left("a participant or session name is not a plain name")
        else if !Name.matches(condition) then Left("the condition is not a plain name")
        else if sessions.map(_.name).distinct.size != sessions.size then
          Left("a participant-session is listed twice")
        else
          StudyReplay.ReplyProtocol
            .named(protocolName)
            .toRight(s"unknown reply protocol $protocolName")
            .map(
              Index(
                model,
                condition,
                system,
                _,
                protocolName,
                attempts,
                suffix,
                extras,
                unitLine,
                chunkLine,
                manifest,
                sessions
              )
            )
    }

  /** The identity of a session's supplied units: their numbers, the study's row keys and their
    * texts, in order. It does not include the prompts, so it is the same in every condition.
    */
  def unitsChecksum(units: Vector[StudyReplay.RecallUnit]): Checksum =
    Checksum.ofText(
      units
        .map(u => Json.arr(Json.fromInt(u.u), u.row, Json.fromString(u.text)).noSpaces)
        .mkString("\n")
    )

  def index(dir: Path): Either[String, Index] = json(dir.resolve("index.json")).flatMap(parseIndex)

  /** Read and check one session document against its index entry: it must be the session the index
    * names, and each prompt must have the hash the index records.
    */
  def parseSession(doc: Json, entry: Entry): Either[String, Session] =
    val c = doc.hcursor
    val read =
      for
        participant <- c.get[String]("participant")
        session <- c.get[String]("session")
        rawUnits <- c.get[Vector[Json]]("units")
        units <- rawUnits.foldLeft[io.circe.Decoder.Result[Vector[StudyReplay.RecallUnit]]](
          Right(Vector.empty)
        ) { (acc, u) =>
          for
            done <- acc
            n <- u.hcursor.get[Int]("u")
            row <- u.hcursor.get[Json]("row")
            text <- u.hcursor.get[String]("text")
          yield done :+ StudyReplay.RecallUnit(n, row, text)
        }
        rawChunks <- c.get[Vector[Json]]("chunks")
        chunks <- rawChunks.foldLeft[io.circe.Decoder.Result[Vector[StudyReplay.Chunk]]](
          Right(Vector.empty)
        ) { (acc, k) =>
          for
            done <- acc
            lo <- k.hcursor.get[Int]("lo")
            hi <- k.hcursor.get[Int]("hi")
            prompt <- k.hcursor.get[String]("prompt")
          yield done :+ StudyReplay.Chunk(lo, hi, prompt)
        }
      yield Session(participant, session, units, chunks)
    val file = entry.file
    read.left.map(_ => s"$file is malformed").flatMap { value =>
      if value.participant != entry.participant || value.session != entry.session then
        Left(s"$file is not the session the index names")
      else if value.chunks.map(k => Checksum.ofText(k.prompt).hex) != entry.hashes then
        Left(s"$file: a prompt does not have its recorded hash")
      else if value.units.map(_.u).distinct.size != value.units.size then
        Left(s"$file: a unit number repeats")
      else if value.units.exists(u => u.text.isEmpty || u.text.contains('\n')) then
        Left(s"$file: a unit is empty or spans lines")
      else Right(value)
    }

  def session(dir: Path, entry: Entry): Either[String, Session] =
    json(dir.resolve(entry.file)).flatMap(parseSession(_, entry))

/** CLI: `agentStudyReplay EXPORT_DIR RECORDINGS_DIR PROVIDER OUTPUT_DIR`.
  *
  * Replays every participant-session of an exported study from text-exchange recordings, with no
  * model client, and writes one reply file per participant-session in the form the scorer reads.
  * The reply rules are the ones the export names. An exchange the replay needs and cannot find
  * refuses the run. Reply files hold no recall text, but the export does; keep both outside the
  * repository.
  */
@main def agentStudyReplay(
    exportDir: String,
    recordingsDir: String,
    provider: String,
    outputDir: String
): scala.Unit =
  def refuse(msg: String): Nothing =
    System.err.println(s"refused: $msg")
    sys.exit(2)
  val out = Path.of(outputDir)
  if Files.exists(out) then refuse("output directory exists")
  val dir = Path.of(exportDir)
  val index = StudyExport.index(dir).fold(refuse, identity)
  val protocol = index.protocol
  val recordings = Recordings.open(Path.of(recordingsDir)).fold(e => refuse(e.message), identity)
  def suffix(n: Int): String =
    if n == 0 then "" else index.retrySuffix.replace("{attempt}", n.toString)
  def line(u: StudyReplay.RecallUnit): String =
    index.unitLine.replace("{u}", u.u.toString).replace("{text}", u.text)
  def asks(c: StudyReplay.Chunk): String =
    index.chunkLine.replace("{lo}", c.lo.toString).replace("{hi}", c.hi.toString)
  val results = index.sessions.map { entry =>
    val s = StudyExport.session(dir, entry).fold(refuse, identity)
    val (replies, receipts) = StudyReplay
      .session(
        s.units,
        s.chunks,
        protocol,
        index.attempts,
        suffix,
        line,
        asks,
        p => TextExchange.replay(recordings, provider, index.model, index.system, p)
      )
      .fold(f => refuse(s"${s.participant} ${s.session}: $f"), identity)
    val doc = Json.obj(
      "participant" -> Json.fromString(s.participant),
      "session" -> Json.fromString(s.session),
      "model" -> Json.fromString(index.model),
      "condition" -> Json.fromString(index.condition),
      "manifestSha256" -> Json.fromString(index.manifestSha256),
      "protocol" -> Json.fromString(index.protocolName),
      "modelCalls" -> Json.fromInt(0),
      "chunks" -> Json.fromValues(receipts.map { r =>
        Json.obj(
          "chunk" -> Json.fromInt(r.chunk),
          "attempts" -> Json.fromInt(r.attempts),
          "accepted" -> Json.fromBoolean(r.accepted),
          "itemsOutsideChunk" -> Json.fromInt(r.itemsOutsideChunk),
          "repeatedUnits" -> Json.fromInt(r.repeatedUnits)
        )
      }),
      "units" -> Json.fromValues(
        replies.map((u, r) => StudyReplay.unitRow(protocol, u, r, index.extras))
      )
    )
    (s"${entry.name}.json", doc, replies.size, receipts.map(_.attempts).sum)
  }
  Files.createDirectories(out): scala.Unit
  results.foreach { (name, doc, _, _) =>
    Files.write(
      out.resolve(name),
      (doc.spaces2 + "\n").getBytes(StandardCharsets.UTF_8)
    ): scala.Unit
  }
  println(
    s"replayed sessions=${results.size} units=${results.map(_._3).sum} " +
      s"exchanges=${results.map(_._4).sum} modelCalls=0"
  )

/** CLI: `agentStudyRecords SOURCE_CSV PARTS_JSON EXPORT_DIR RECORDINGS_DIR PROVIDER OUTPUT_FILE`.
  *
  * Replays every participant-session of an exported study, with no model client, and builds its two
  * checked categorical records (narrative, referent) over the timed source and the supplied recall
  * units. It writes a receipt of counts by judgement and each record's digest; it writes no bundle
  * yet. A record that does not construct refuses the run. The receipt holds no text.
  */
@main def agentStudyRecords(
    sourceCsv: String,
    partsJson: String,
    exportDir: String,
    recordingsDir: String,
    provider: String,
    outputFile: String
): scala.Unit =
  def refuse(msg: String): Nothing =
    System.err.println(s"refused: $msg")
    sys.exit(2)
  val out = Path.of(outputFile)
  if Files.exists(out) then refuse("output exists")
  def bytes(p: String): Array[Byte] =
    try Files.readAllBytes(Path.of(p))
    catch case NonFatal(_) => refuse(s"cannot read $p")
  val view = storymodel4s.codec.TimedSourceArtifactCodec
    .prepareChecked(bytes(sourceCsv), bytes(partsJson))
    .fold(r => refuse(s"source: $r"), identity)
  val dir = Path.of(exportDir)
  val index = StudyExport.index(dir).fold(refuse, identity)
  val recordings = Recordings.open(Path.of(recordingsDir)).fold(e => refuse(e.message), identity)
  def suffix(n: Int): String =
    if n == 0 then "" else index.retrySuffix.replace("{attempt}", n.toString)
  def line(u: StudyReplay.RecallUnit): String =
    index.unitLine.replace("{u}", u.u.toString).replace("{text}", u.text)
  def asks(c: StudyReplay.Chunk): String =
    index.chunkLine.replace("{lo}", c.lo.toString).replace("{hi}", c.hi.toString)
  val fields = Vector("narrative", "referent")
  def tally(xs: Vector[String]): Json =
    Json.fromFields(
      xs.groupBy(identity).toVector.sortBy(_._1).map((k, v) => k -> Json.fromInt(v.size))
    )
  val sessions = index.sessions.map { entry =>
    val s = StudyExport.session(dir, entry).fold(refuse, identity)
    val (replies, _) = StudyReplay
      .session(
        s.units,
        s.chunks,
        index.protocol,
        index.attempts,
        suffix,
        line,
        asks,
        p => TextExchange.replay(recordings, provider, index.model, index.system, p)
      )
      .fold(f => refuse(s"${entry.name}: $f"), identity)
    // The recall's identity is its units alone, not the prompts beside them, so the same units
    // have the same inventory under every condition.
    val artifact = StudyExport.unitsChecksum(s.units)
    val recall = PreparedRecall
      .supplied(entry.name, s.units.map(_.text), artifact)
      .fold(e => refuse(s"${entry.name}: $e"), identity)
    val producer = StudyRecord.producer(provider, index.model, index.system, view, recall)
    val perField = fields.map { field =>
      val judgements = replies.map((_, r) => StudyRecord.judge(view, index.protocol, r, field))
      val policy = StudyRecord.policy(index.condition, index.protocolName, field)
      val record = StudyRecord
        .record(field, policy, recall, view, producer, judgements)
        .fold(e => refuse(s"${entry.name} $field: ${e.message}"), identity)
      (field, judgements.map(_.kind), record.digest.hex)
    }
    (entry, s.units.size, perField)
  }
  val doc = Json.obj(
    "schema" -> Json.fromString("storymodel4s.agentmapping.study-records-receipt/v1"),
    "model" -> Json.fromString(index.model),
    "condition" -> Json.fromString(index.condition),
    "protocol" -> Json.fromString(index.protocolName),
    "sourceArtifact" -> Json.fromString(view.artifact.hex),
    "sourceRepresentation" -> Json.fromString(view.representation.digest.hex),
    "modelCalls" -> Json.fromInt(0),
    "units" -> Json.fromInt(sessions.map(_._2).sum),
    "judgements" -> Json.fromFields(fields.map { field =>
      field -> tally(sessions.flatMap(_._3.filter(_._1 == field).flatMap(_._2)))
    }),
    "sessions" -> Json.fromValues(sessions.map { (entry, units, perField) =>
      Json.obj(
        "participant" -> Json.fromString(entry.participant),
        "session" -> Json.fromString(entry.session),
        "units" -> Json.fromInt(units),
        "records" -> Json.fromFields(perField.map { (field, kinds, digest) =>
          field -> Json.obj("digest" -> Json.fromString(digest), "judgements" -> tally(kinds))
        })
      )
    })
  )
  Files.write(out, (doc.spaces2 + "\n").getBytes(StandardCharsets.UTF_8)): scala.Unit
  println(
    s"records sessions=${sessions.size} units=${sessions.map(_._2).sum} modelCalls=0 " +
      doc.hcursor.downField("judgements").focus.fold("")(_.noSpaces)
  )

/** CLI: `agentStudyBundle SOURCE_CSV PARTS_JSON EXPORT_DIR RECORDINGS_DIR PROVIDER OUTPUT_DIR`.
  *
  * Replays every participant-session of an exported study, with no model client, and writes one
  * bundle per participant-session (see `StudyBundle`). Each bundle is read back and re-checked
  * before the command reports. Bundles hold source text and unit ids; keep the output outside the
  * repository.
  */
@main def agentStudyBundle(
    sourceCsv: String,
    partsJson: String,
    exportDir: String,
    recordingsDir: String,
    provider: String,
    outputDir: String
): scala.Unit =
  def refuse(msg: String): Nothing =
    System.err.println(s"refused: $msg")
    sys.exit(2)
  val out = Path.of(outputDir)
  if Files.exists(out) then refuse("output directory exists")
  def bytes(p: String): Array[Byte] =
    try Files.readAllBytes(Path.of(p))
    catch case NonFatal(_) => refuse(s"cannot read $p")
  val view = storymodel4s.codec.TimedSourceArtifactCodec
    .prepareChecked(bytes(sourceCsv), bytes(partsJson))
    .fold(r => refuse(s"source: $r"), identity)
  val dir = Path.of(exportDir)
  val index = StudyExport.index(dir).fold(refuse, identity)
  val recordings = Recordings.open(Path.of(recordingsDir)).fold(e => refuse(e.message), identity)
  def suffix(n: Int): String =
    if n == 0 then "" else index.retrySuffix.replace("{attempt}", n.toString)
  def line(u: StudyReplay.RecallUnit): String =
    index.unitLine.replace("{u}", u.u.toString).replace("{text}", u.text)
  def asks(c: StudyReplay.Chunk): String =
    index.chunkLine.replace("{lo}", c.lo.toString).replace("{hi}", c.hi.toString)
  val bundles = index.sessions.map { entry =>
    val s = StudyExport.session(dir, entry).fold(refuse, identity)
    val (replies, _) = StudyReplay
      .session(
        s.units,
        s.chunks,
        index.protocol,
        index.attempts,
        suffix,
        line,
        asks,
        p => TextExchange.replay(recordings, provider, index.model, index.system, p)
      )
      .fold(f => refuse(s"${entry.name}: $f"), identity)
    val recall = PreparedRecall
      .supplied(entry.name, s.units.map(_.text), StudyExport.unitsChecksum(s.units))
      .fold(e => refuse(s"${entry.name}: $e"), identity)
    val producer = StudyRecord.producer(provider, index.model, index.system, view, recall)
    val rows = replies.zip(recall.unitIds).map { case ((u, r), id) =>
      StudyBundle.row(view, index.protocol, u, id.value, r, index.extras)
    }
    val records = StudyBundle.Fields.map { f =>
      f -> StudyRecord
        .record(
          f,
          StudyRecord.policy(index.condition, index.protocolName, f),
          recall,
          view,
          producer,
          rows.map(_.judgement(f))
        )
        .fold(e => refuse(s"${entry.name} $f: ${e.message}"), identity)
    }.toMap
    val doc = StudyBundle.encode(
      StudyBundle.Provenance(
        entry.participant,
        entry.session,
        index.condition,
        index.model,
        provider,
        index.protocolName,
        Checksum.ofText(index.system).hex,
        index.manifestSha256,
        recall.segmentation
      ),
      view,
      records,
      rows
    )
    val text = doc.spaces2 + "\n"
    val reread = io.circe.jawn
      .parse(text)
      .left
      .map(_.message)
      .flatMap(StudyBundle.decode(_, view, recall))
      .fold(e => refuse(s"${entry.name}: bundle does not reload: $e"), identity)
    if StudyBundle.Fields.exists(f => reread.records(f).digest != records(f).digest) then
      refuse(s"${entry.name}: reloaded bundle differs")
    (entry.name, text, rows)
  }
  Files.createDirectories(out): scala.Unit
  bundles.foreach { (name, text, _) =>
    Files.write(out.resolve(s"$name.bundle.json"), text.getBytes(StandardCharsets.UTF_8)): scala.Unit
  }
  val kinds = StudyBundle.Fields.map { f =>
    val xs = bundles.flatMap(_._3.map(_.judgement(f).kind))
    f -> Json.fromFields(xs.groupBy(identity).toVector.sortBy(_._1).map((k, v) => k -> Json.fromInt(v.size)))
  }
  println(
    s"bundles=${bundles.size} units=${bundles.map(_._3.size).sum} modelCalls=0 " +
      Json.fromFields(kinds).noSpaces
  )
