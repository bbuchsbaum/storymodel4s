package storymodel4s.provider.agent

import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import storymodel4s.core.Checksum

/** Content-derived identity of one model exchange: model id, prompt package, prompt text, the
  * sentence's text checksum, and the token list. Never the caller's request id, so the same
  * sentence replays under any batch layout.
  */
object RecordingKey:
  opaque type RecordingKey = Checksum

  private[agent] def of(
      model: String,
      promptPackageChecksum: Checksum,
      promptTextChecksum: Checksum,
      textChecksum: Checksum,
      tokens: Vector[RequestToken]
  ): RecordingKey =
    AgentIdentity.digest(
      "agent-recording/v1",
      Vector(
        model,
        promptPackageChecksum.hex,
        promptTextChecksum.hex,
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
  case NotADirectory(path: String)
  case CannotCreate(path: String, reasonChecksum: Checksum)

  def message: String = this match
    case NotADirectory(path)        => s"$path exists and is not a directory"
    case CannotCreate(path, reason) => s"cannot create $path (reason ${reason.short()})"

/** A directory of model replies, one JSON file per content key. Replay re-runs the full admission
  * court on every read, so a recording is evidence, never a cached verdict.
  */
final class Recordings private (val dir: Path):
  val Schema: String = Recordings.Schema

  def path(key: RecordingKey): Path = dir.resolve(s"${key.checksum.hex}.json")

  def contains(key: RecordingKey): Boolean = Files.isRegularFile(path(key))

  /** Read one reply; the header model must equal the model the request names. */
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

  /** Write one reply atomically enough for a single writer: full content, then rename. */
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

  /** Open or create a recordings directory. */
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
    Json
      .obj(
        "schema" -> Schema.asJson,
        "model" -> reply.model.asJson,
        "stopReason" -> reply.stopReason.wire.asJson,
        "usage" -> Json.obj(
          "inputTokens" -> reply.usage.inputTokens.asJson,
          "outputTokens" -> reply.usage.outputTokens.asJson,
          "cacheReadInputTokens" -> reply.usage.cacheReadInputTokens.asJson
        ),
        "durationMillis" -> reply.durationMillis.asJson,
        "text" -> reply.text.asJson
      )
      .spaces2

  private def field[A: Decoder](cursor: HCursor, name: String): Decoder.Result[A] =
    cursor.downField(name).as[A]

  private given Decoder[ModelUsage] = Decoder.instance { cursor =>
    for
      input <- field[Long](cursor, "inputTokens")
      output <- field[Long](cursor, "outputTokens")
      cacheRead <- field[Long](cursor, "cacheReadInputTokens")
      _ <-
        if input >= 0L && output >= 0L && cacheRead >= 0L then Right(())
        else Left(DecodingFailure("negative token usage", cursor.history))
    yield ModelUsage(input, output, cacheRead)
  }

  private given Decoder[ModelReply] = Decoder.instance { cursor =>
    for
      schema <- field[String](cursor, "schema")
      _ <-
        if schema == Schema then Right(())
        else Left(DecodingFailure(s"unknown recording schema $schema", cursor.history))
      model <- field[String](cursor, "model")
      stop <- field[String](cursor, "stopReason")
      usage <- field[ModelUsage](cursor, "usage")
      duration <- field[Long](cursor, "durationMillis")
      text <- field[String](cursor, "text")
      _ <-
        if duration >= 0L then Right(())
        else Left(DecodingFailure("negative duration", cursor.history))
    yield ModelReply(model, text, ModelStopReason.fromWire(stop), usage, duration)
  }

  private[agent] def decode(raw: String): Either[String, ModelReply] =
    parse(raw).left.map(_.message).flatMap(_.as[ModelReply].left.map(_.message))
