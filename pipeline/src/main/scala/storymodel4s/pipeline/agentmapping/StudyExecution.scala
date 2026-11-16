package storymodel4s.pipeline.agentmapping

import io.circe.Json
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.core.Checksum
import storymodel4s.provider.agent.{
  ExchangeFailure,
  LiveAuthorization,
  ModelReply,
  Recordings,
  TextExchange,
  TextExecution
}

/** One validated study and one bundle construction path for legacy, live and offline execution. */
object StudyExecution:
  /** Replay has no authorization or model client; live mode admits them at the process boundary. */
  enum Mode:
    case Live, Replay

  private def str(value: String): Json = Json.fromString(value)
  private def bytes(path: Path): Array[Byte] = Files.readAllBytes(path)
  private def read(path: Path): Json =
    io.circe.jawn.parse(Files.readString(path)).fold(e => throw Refused(e.message), identity)
  private def required[A](value: Either[?, A]): A =
    value.fold(e => throw Refused(e.toString), identity)
  private final case class Refused(reason: String) extends RuntimeException(reason)

  private final class Prepared(
      val view: TimedSourceView,
      val index: StudyExport.Index,
      val clock: Option[(SceneIntervals, String)],
      val sessions: Vector[(StudyExport.Entry, StudyExport.Session, PreparedRecall)],
      val inputs: Json
  ):
    def suffix(n: Int): String =
      if n == 0 then "" else index.retrySuffix.replace("{attempt}", n.toString)
    def line(u: StudyReplay.RecallUnit): String =
      index.unitLine.replace("{u}", u.u.toString).replace("{text}", u.text)
    def asks(c: StudyReplay.Chunk): String =
      index.chunkLine.replace("{lo}", c.lo.toString).replace("{hi}", c.hi.toString)

  /** All sessions, source data and prompt bounds are checked before opening a live client. */
  private def prepare(source: Path, parts: Path, dir: Path): Prepared =
    val sourceBytes = bytes(source)
    val partsBytes = bytes(parts)
    val indexBytes = bytes(dir.resolve("index.json"))
    val index = required(
      io.circe.jawn.parseByteArray(indexBytes).left.map(_.message).flatMap(StudyExport.parseIndex)
    )
    StudyBundle.Fields.foreach { field =>
      required(
        scala.util
          .Try(StudyRecord.policy(index.condition, index.protocolName, field))
          .toEither
          .left
          .map(_ => "condition and protocol cannot form a valid mapping policy identifier")
      )
    }
    val view = required(
      storymodel4s.codec.TimedSourceArtifactCodec.prepareChecked(sourceBytes, partsBytes)
    )
    val clock = required(StudyExport.intervals(dir, index, view, partsBytes))
    val sessionBytes = index.sessions.map(e => e -> bytes(dir.resolve(e.file)))
    val sessions = sessionBytes.map { (entry, raw) =>
      val s = required(
        io.circe.jawn
          .parseByteArray(raw)
          .left
          .map(_.message)
          .flatMap(StudyExport.parseSession(_, entry))
      )
      val recall = required(
        PreparedRecall.supplied(entry.name, s.units.map(_.text), StudyExport.unitsChecksum(s.units))
      )
      (entry, s, recall)
    }
    val inputs = Json.obj(
      "sourceSha256" -> str(Checksum.ofBytes(sourceBytes).hex),
      "partsSha256" -> str(Checksum.ofBytes(partsBytes).hex),
      "indexSha256" -> str(Checksum.ofBytes(indexBytes).hex),
      "clockSha256" -> clock.fold[Json](Json.Null)(c => str(c._2)),
      "sessions" -> Json.fromValues(sessionBytes.map { (entry, raw) =>
        Json.obj("file" -> str(entry.file), "sha256" -> str(Checksum.ofBytes(raw).hex))
      })
    )
    val p = new Prepared(view, index, clock, sessions, inputs)
    sessions.foreach { (_, s, _) =>
      required(StudyReplay.validate(s.units, s.chunks, p.line, p.asks))
    }
    p

  private def render(
      p: Prepared,
      provider: String,
      serve: String => Either[ExchangeFailure, ModelReply],
      calls: () => Int,
      execution: Option[TextExecution.Profile]
  ): Vector[(String, String)] =
    p.sessions.map { (entry, s, recall) =>
      val before = calls()
      val (replies, _) = required(
        StudyReplay.session(
          s.units,
          s.chunks,
          p.index.protocol,
          p.index.attempts,
          p.suffix,
          p.line,
          p.asks,
          serve
        )
      )
      val intervals = p.clock.map(_._1)
      val producer = StudyRecord.producer(provider, p.index.model, p.index.system, p.view, recall)
      val rows = replies.zip(recall.unitIds).map { case ((u, r), id) =>
        StudyBundle.row(p.view, p.index.protocol, u, id.value, r, p.index.extras, intervals)
      }
      val records = required(
        StudyBundle.records(p.index.condition, p.index.protocolName, recall, p.view, producer, rows)
      )
      val encoded = StudyBundle.encode(
        StudyBundle.Provenance(
          entry.participant,
          entry.session,
          p.index.condition,
          p.index.model,
          provider,
          p.index.protocolName,
          Checksum.ofText(p.index.system).hex,
          p.index.manifestSha256,
          recall.segmentation,
          p.clock.map(_._2)
        ),
        p.view,
        records,
        rows,
        intervals
      )
      // Accounting belongs to this invocation, while the checked mapping records stay identical.
      val doc = execution.fold(encoded) { descriptor =>
        encoded.mapObject { o =>
          o.add(
            "provenance",
            o("provenance").get.mapObject { provenance =>
              provenance
                .add("modelCalls", Json.fromInt(calls() - before))
                .add("execution", descriptor.json)
            }
          )
        }
      }
      val text = doc.noSpaces + "\n"
      val reread = required(
        io.circe.jawn
          .parse(text)
          .left
          .map(_.message)
          .flatMap(
            StudyBundle.decode(
              _,
              p.view,
              recall,
              StudyBundle.Expected(
                entry.participant,
                entry.session,
                s.units,
                producer,
                intervals,
                execution,
                calls() - before
              )
            )
          )
      )
      if StudyBundle.Fields.exists(f => reread.records(f).digest != records(f).digest) then
        throw Refused("reloaded bundle differs")
      s"${entry.name}.bundle.json" -> text
    }

  private def guard[A](body: => A): Either[String, A] =
    try Right(body)
    catch
      case Refused(reason) => Left(reason)
      case NonFatal(e)     => Left(s"study I/O failed (${e.getClass.getSimpleName})")

  private def publish(out: Path, bundles: Vector[(String, String)], receipt: Option[Json]): Unit =
    if Files.exists(out) then throw Refused("output directory exists")
    val target = out.toAbsolutePath
    Files.createDirectories(target.getParent): Unit
    val stage = Files.createTempDirectory(target.getParent, ".study-output-")
    try
      val destination = receipt.fold(stage)(_ => Files.createDirectory(stage.resolve("bundles")))
      bundles.foreach { (name, text) => Files.writeString(destination.resolve(name), text): Unit }
      receipt.foreach { doc =>
        Files.writeString(stage.resolve("receipt.json"), doc.spaces2 + "\n"): Unit
      }
      Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE): Unit
    finally
      if Files.exists(stage) then
        val walk = Files.walk(stage)
        try walk.iterator.asScala.toVector.reverse.foreach(p => { Files.delete(p); () })
        finally walk.close()

  /** Preserve the original bundle command's v1 exchange identity and flat output directory. */
  def legacy(
      source: Path,
      parts: Path,
      exportDir: Path,
      recordingsDir: Path,
      provider: String,
      out: Path
  ): Either[String, Int] = guard {
    if Files.exists(out) then throw Refused("output directory exists")
    val p = prepare(source, parts, exportDir)
    val recordings = required(Recordings.open(recordingsDir))
    val bundles = render(
      p,
      provider,
      prompt => TextExchange.replay(recordings, provider, p.index.model, p.index.system, prompt),
      () => 0,
      None
    )
    publish(out, bundles, None)
    bundles.size
  }

  /** Execute a study against a recorded run descriptor. Live authorization is inspected only in
    * live mode. Failure receipts remain in the run directory even when no output is published.
    */
  def run(
      source: Path,
      parts: Path,
      exportDir: Path,
      runDir: Path,
      out: Path,
      mode: Mode,
      env: Map[String, String] = Map.empty,
      maxTokens: Long = 4096L,
      timeoutMillis: Long = 60000L,
      maxNewCalls: Int = 100
  ): Either[String, Json] = guard {
    if Files.exists(out) then throw Refused("output directory exists")
    if maxNewCalls <= 0 then throw Refused("new-call budget must be positive")
    val p = prepare(source, parts, exportDir)
    val manifestFile = runDir.resolve("run.json")
    val authorization = mode match
      case Mode.Replay => None
      case Mode.Live   =>
        required(LiveAuthorization.from(env)) match
          case a: LiveAuthorization.OpenAiCompatible => Some(a)
          case _ => throw Refused("this live slice supports the OpenAI-compatible backend")
    val profile = authorization match
      case Some(a) => required(TextExecution.Profile.from(a, maxTokens, timeoutMillis))
      case None    =>
        required(
          TextExecution.Profile.decode(
            read(manifestFile).hcursor.downField("execution").focus.getOrElse(Json.Null)
          )
        )
    if profile.model != p.index.model then throw Refused("export and execution models differ")
    val manifest = Json.obj(
      "schema" -> str("storymodel4s.study-execution/v1"),
      "inputs" -> p.inputs,
      "execution" -> profile.json
    )
    if Files.exists(manifestFile) then
      if read(manifestFile) != manifest then
        throw Refused("run inputs or execution configuration changed")
    else if mode == Mode.Replay then throw Refused("replay needs an existing run descriptor")
    else
      Files.createDirectories(runDir): Unit
      Files.writeString(manifestFile, manifest.spaces2 + "\n", StandardOpenOption.CREATE_NEW): Unit
    val recordings = required(mode match
      case Mode.Live   => Recordings.at(runDir.resolve("recordings"))
      case Mode.Replay => Recordings.open(runDir.resolve("recordings")))
    val receipts = runDir.resolve("invocations")
    Files.createDirectories(receipts): Unit
    val session = authorization match
      case Some(a) => required(TextExecution.live(recordings, profile, a))
      case None    => TextExecution.replay(recordings, profile)
    val invocation = UUID.randomUUID().toString
    var outcome = "failed"
    var failure: Option[String] = None
    var bundles = Vector.empty[(String, String)]
    def receipt: Json = Json.obj(
      "schema" -> str("storymodel4s.study-invocation/v1"),
      "invocation" -> str(invocation),
      "mode" -> str(mode.toString.toLowerCase),
      "status" -> str(outcome),
      "failure" -> failure.fold[Json](Json.Null)(str),
      "manifestSha256" -> str(Checksum.ofText(manifest.noSpaces).hex),
      "execution" -> profile.json,
      "exchanges" -> session.receipt,
      "newClientInvocationLimit" -> (if mode == Mode.Live then Json.fromInt(maxNewCalls)
                                     else Json.Null),
      "modelCalls" -> Json.fromInt(session.clientInvocations),
      "httpAttempts" -> Json.fromInt(session.httpAttempts),
      "cacheHits" -> Json.fromInt(session.cacheHits),
      "bundles" -> Json.fromValues(bundles.map { (name, text) =>
        Json.obj("file" -> str(name), "sha256" -> str(Checksum.ofText(text).hex))
      })
    )
    try
      bundles = render(
        p,
        profile.provider,
        prompt => {
          if mode == Mode.Live && session.clientInvocations >= maxNewCalls &&
            !recordings.contains(profile.key(p.index.system, prompt))
          then throw Refused("new-call budget exhausted; resume with the same run directory")
          session.serve(p.index.system, prompt)
        },
        () => session.clientInvocations,
        Some(profile)
      )
      session.close()
      outcome = "complete"
      val completed = receipt
      Files.writeString(receipts.resolve(s"$invocation.json"), completed.spaces2 + "\n"): Unit
      publish(out, bundles, Some(completed))
      completed
    catch
      case NonFatal(e) =>
        outcome = "failed"
        failure = Some(e match
          case Refused(reason) => reason
          case _               => s"execution failed (${e.getClass.getSimpleName})")
        try Files.writeString(receipts.resolve(s"$invocation.json"), receipt.spaces2 + "\n"): Unit
        catch
          case NonFatal(_) => throw Refused(failure.get + "; invocation receipt could not be saved")
        throw e
    finally session.close()
  }
