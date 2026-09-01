package storymodel4s.provider.agent

import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import storymodel4s.core.Checksum

/** Content-derived identity of one model exchange: the user-message template version, model id,
  * prompt package, prompt text, token budget, the sentence's text checksum, and the token list.
  * Never the caller's request id, so the same sentence replays under any batch layout.
  */
object RecordingKey:
  opaque type RecordingKey = Checksum

  private[agent] def of(
      model: String,
      promptPackageChecksum: Checksum,
      promptTextChecksum: Checksum,
      maxTokens: Long,
      textChecksum: Checksum,
      tokens: Vector[RequestToken]
  ): RecordingKey =
    AgentIdentity.digest(
      "agent-recording/v1",
      Vector(
        ModelRequest.TemplateVersion,
        model,
        promptPackageChecksum.hex,
        promptTextChecksum.hex,
        maxTokens.toString,
        textChecksum.hex,
        tokens.size.toString
      ) ++ tokens.flatMap { token =>
        Vector(token.id, token.start.toString, token.endExclusive.toString, token.text)
      }
    )

  extension (key: RecordingKey) def checksum: Checksum = key

type RecordingKey = RecordingKey.RecordingKey

/** Why a recordings directory could not be opened. */
enum RecordingsError:
  case Missing(path: String)
  case NotADirectory(path: String)
  case CannotCreate(path: String, reasonChecksum: Checksum)

  def message: String = this match
    case Missing(path)              => s"$path does not exist"
    case NotADirectory(path)        => s"$path exists and is not a directory"
    case CannotCreate(path, reason) => s"cannot create $path (reason ${reason.short()})"

/** A directory of model replies, one JSON file per content key. Replay re-runs the full admission
  * court on every read, so a recording is evidence, never a cached verdict; its header says whether
  * that evidence was captured from the provider or authored by hand.
  */
final class Recordings private (val dir: Path):
  def path(key: RecordingKey): Path = dir.resolve(s"${key.checksum.hex}.json")

  def contains(key: RecordingKey): Boolean = Files.isRegularFile(path(key))

  /** Read one reply; the requested model in the header must equal the model the request names. */
  def read(key: RecordingKey, expectedModel: String): Either[ExchangeFailure, ModelReply] =
    val file = path(key)
    if !Files.isRegularFile(file) then Left(ExchangeFailure.RecordingMissing(key))
    else
      val raw =
        try Right(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
        catch
          case NonFatal(error) =>
            Left(ExchangeFailure.RecordingCorrupt(key, Checksum.ofText(describe(error))))
      raw.flatMap { text =>
        Recordings.decode(text) match
          case Left(reason) => Left(ExchangeFailure.RecordingCorrupt(key, Checksum.ofText(reason)))
          case Right(reply) if reply.model != expectedModel =>
            Left(
              ExchangeFailure.RecordingCorrupt(
                key,
                Checksum.ofText(s"recorded model ${reply.model} is not $expectedModel")
              )
            )
          case Right(reply) => Right(reply)
      }

  /** Write one reply: full content to a sibling file, then an atomic rename. */
  def write(key: RecordingKey, reply: ModelReply): Either[ExchangeFailure, Unit] =
    val file = path(key)
    val temporary = dir.resolve(s"${key.checksum.hex}.json.tmp")
    try
      Files.write(temporary, Recordings.encode(reply).getBytes(StandardCharsets.UTF_8))
      Files.move(
        temporary,
        file,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE
      )
      Right(())
    catch
      case NonFatal(error) =>
        Left(ExchangeFailure.RecordingUnwritable(key, Checksum.ofText(describe(error))))

  private def describe(error: Throwable): String =
    s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"

object Recordings:
  val Schema: String = "storymodel4s.provider.agent.recording/v1"

  /** Open an existing recordings directory; a missing one is refused, never created. */
  def open(dir: Path): Either[RecordingsError, Recordings] =
    if !Files.exists(dir) then Left(RecordingsError.Missing(dir.toString))
    else if !Files.isDirectory(dir) then Left(RecordingsError.NotADirectory(dir.toString))
    else Right(new Recordings(dir))

  /** Open or create a recordings directory for a run that may record. */
  def at(dir: Path): Either[RecordingsError, Recordings] =
    if Files.exists(dir) && !Files.isDirectory(dir) then
      Left(RecordingsError.NotADirectory(dir.toString))
    else
      try
        Files.createDirectories(dir)
        Right(new Recordings(dir))
      catch
        case NonFatal(error) =>
          Left(
            RecordingsError.CannotCreate(
              dir.toString,
              Checksum.ofText(
                s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"
              )
            )
          )

  private[agent] def encode(reply: ModelReply): String =
    val evidence = reply.evidence match
      case ReplyEvidence.Captured(reportedModel, usage, durationMillis) =>
        Vector(
          "reportedModel" -> reportedModel.asJson,
          "usage" -> Json.obj(
            "inputTokens" -> usage.inputTokens.asJson,
            "outputTokens" -> usage.outputTokens.asJson,
            "cacheReadInputTokens" -> usage.cacheReadInputTokens.asJson
          ),
          "durationMillis" -> durationMillis.asJson
        )
      case ReplyEvidence.Authored => Vector.empty
    Json
      .obj(
        (Vector(
          "schema" -> Schema.asJson,
          "model" -> reply.model.asJson,
          "origin" -> reply.evidence.wire.asJson,
          "stopReason" -> reply.stopReason.wire.asJson
        ) ++ evidence :+ ("text" -> reply.text.asJson))*
      )
      .spaces2

  private def field[A: Decoder](cursor: HCursor, name: String): Decoder.Result[A] =
    cursor.downField(name).as[A]

  private given Decoder[ModelUsage] = Decoder.instance { cursor =>
    for
      input <- field[Long](cursor, "inputTokens")
      output <- field[Long](cursor, "outputTokens")
      cacheRead <- field[Option[Long]](cursor, "cacheReadInputTokens")
      _ <-
        if input >= 0L && output >= 0L && cacheRead.forall(_ >= 0L) then Right(())
        else Left(DecodingFailure("negative token usage", cursor.history))
    yield ModelUsage(input, output, cacheRead)
  }

  private def evidence(cursor: HCursor, origin: String): Decoder.Result[ReplyEvidence] =
    val accounting = Vector("reportedModel", "usage", "durationMillis")
    origin match
      case "captured" =>
        for
          reportedModel <- field[Option[String]](cursor, "reportedModel")
          usage <- field[ModelUsage](cursor, "usage")
          duration <- field[Long](cursor, "durationMillis")
          _ <-
            if duration >= 0L then Right(())
            else Left(DecodingFailure("negative duration", cursor.history))
        yield ReplyEvidence.Captured(reportedModel, usage, duration)
      case "authored" =>
        accounting.find(name => cursor.downField(name).succeeded) match
          case Some(name) =>
            Left(DecodingFailure(s"authored recording carries $name", cursor.history))
          case None => Right(ReplyEvidence.Authored)
      case other => Left(DecodingFailure(s"unknown recording origin $other", cursor.history))

  private given Decoder[ModelReply] = Decoder.instance { cursor =>
    for
      schema <- field[String](cursor, "schema")
      _ <-
        if schema == Schema then Right(())
        else Left(DecodingFailure(s"unknown recording schema $schema", cursor.history))
      model <- field[String](cursor, "model")
      origin <- field[String](cursor, "origin")
      stop <- field[String](cursor, "stopReason")
      text <- field[String](cursor, "text")
      admitted <- evidence(cursor, origin)
    yield ModelReply(model, text, ModelStopReason.fromWire(stop), admitted)
  }

  private[agent] def decode(raw: String): Either[String, ModelReply] =
    parse(raw).left.map(_.message).flatMap(_.as[ModelReply].left.map(_.message))
