package storymodel4s.provider.agent

import io.circe.{Json, parser}
import io.circe.syntax.*
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.collection.mutable
import scala.util.control.NonFatal
import storymodel4s.core.Checksum

/** Checked live/replay execution of a whole-text model exchange. It owns the small journal which
  * makes a process interruption a refusal rather than an invisible second provider call.
  */
object TextExecution:
  private val ProfileSchema = "storymodel4s.provider.agent.text-profile/v2"
  private val KeyDomain = "agent-text-exchange/v2"
  private val TextWireVersion = "openai-chat-completions/v1"
  private val RetryPolicy = "retry-429-5xx-max-3"
  private val JournalSchema = "storymodel4s.provider.agent.text-journal/v2"
  private val JournalName = ".text-execution-v2.journal.json"
  private val LockName = ".text-execution-v2.lock"

  /** Credential-free descriptor of one exact OpenAI-compatible wire configuration. */
  final class Profile private[TextExecution] (
      val provider: String,
      val model: String,
      private val endpointDigest: Checksum,
      val maxTokens: Long,
      val timeoutMillis: Long
  ):
    val digest: Checksum = AgentIdentity.digest(
      "agent-text-execution-profile/v2",
      Vector(
        provider,
        model,
        endpointDigest.hex,
        KeyDomain,
        TextWireVersion,
        RetryPolicy,
        maxTokens.toString,
        timeoutMillis.toString
      )
    )

    val json: Json = Json.obj(
      "schema" -> ProfileSchema.asJson,
      "provider" -> provider.asJson,
      "model" -> model.asJson,
      "endpointSha256" -> endpointDigest.hex.asJson,
      "keyDomain" -> KeyDomain.asJson,
      "wireVersion" -> TextWireVersion.asJson,
      "retryPolicy" -> RetryPolicy.asJson,
      "maxTokens" -> maxTokens.asJson,
      "timeoutMillis" -> timeoutMillis.asJson
    )

    def key(system: String, prompt: String): RecordingKey =
      RecordingKey.fromChecksum(
        AgentIdentity.digest(
          KeyDomain,
          Vector(
            ProfileSchema,
            provider,
            model,
            endpointDigest.hex,
            TextWireVersion,
            RetryPolicy,
            maxTokens.toString,
            system,
            prompt
          )
        )
      )

    private[agent] def matches(authorization: LiveAuthorization.OpenAiCompatible): Boolean =
      provider == authorization.backend.provider &&
        model == authorization.backend.model &&
        endpointDigest == endpointOf(authorization)

    override def equals(other: Any): Boolean = other match
      case that: Profile =>
        provider == that.provider && model == that.model && endpointDigest == that.endpointDigest &&
        maxTokens == that.maxTokens && timeoutMillis == that.timeoutMillis
      case _ => false

    override def hashCode(): Int =
      (provider, model, endpointDigest, maxTokens, timeoutMillis).hashCode

    override def toString: String =
      s"TextExecution.Profile($provider, $model, endpointSha256=${endpointDigest.short()}, " +
        s"maxTokens=$maxTokens, timeoutMillis=$timeoutMillis)"

  object Profile:
    def from(
        authorization: LiveAuthorization.OpenAiCompatible,
        maxTokens: Long,
        timeoutMillis: Long
    ): Either[String, Profile] =
      fromParts(
        authorization.backend.provider,
        authorization.backend.model,
        endpointOf(authorization),
        maxTokens,
        timeoutMillis
      )

    def decode(json: Json): Either[String, Profile] =
      val c = json.hcursor
      for
        schema <- c.get[String]("schema").left.map(_ => "profile schema is missing")
        _ <- Either.cond(schema == ProfileSchema, (), "profile schema is unsupported")
        provider <- c.get[String]("provider").left.map(_ => "profile provider is missing")
        model <- c.get[String]("model").left.map(_ => "profile model is missing")
        endpoint <- c
          .get[String]("endpointSha256")
          .left
          .map(_ => "profile endpoint digest is missing")
        _ <- Either.cond(endpoint.matches("[0-9a-f]{64}"), (), "profile endpoint digest is invalid")
        domain <- c.get[String]("keyDomain").left.map(_ => "profile key domain is missing")
        _ <- Either.cond(domain == KeyDomain, (), "profile key domain is unsupported")
        wire <- c.get[String]("wireVersion").left.map(_ => "profile wire version is missing")
        _ <- Either.cond(wire == TextWireVersion, (), "profile wire version is unsupported")
        retry <- c.get[String]("retryPolicy").left.map(_ => "profile retry policy is missing")
        _ <- Either.cond(retry == RetryPolicy, (), "profile retry policy is unsupported")
        tokens <- c.get[Long]("maxTokens").left.map(_ => "profile maxTokens is missing")
        timeout <- c.get[Long]("timeoutMillis").left.map(_ => "profile timeoutMillis is missing")
        profile <- fromParts(provider, model, Checksum.unsafe(endpoint), tokens, timeout)
      yield profile

    private def fromParts(
        provider: String,
        model: String,
        endpoint: Checksum,
        tokens: Long,
        timeout: Long
    ): Either[String, Profile] =
      if !ModelBackend.identityScalarIsSafe(provider) || !ModelBackend.identityScalarIsSafe(model)
      then Left("profile provider and model must be identity-safe")
      else if tokens <= 0L then Left("maxTokens must be positive")
      else if timeout <= 0L then Left("timeoutMillis must be positive")
      else Right(new Profile(provider, model, endpoint, tokens, timeout))

  /** A live session holds the writer lock; replay deliberately has no client and no lock. */
  sealed trait Session:
    def serve(system: String, prompt: String): Either[ExchangeFailure, ModelReply]
    def clientInvocations: Int
    def httpAttempts: Int
    def cacheHits: Int
    def receipt: Json
    def close(): Unit

  def live(
      recordings: Recordings,
      profile: Profile,
      authorization: LiveAuthorization.OpenAiCompatible
  ): Either[String, Session] =
    if !profile.matches(authorization) then Left("profile does not match live authorization")
    else
      lock(recordings.dir).flatMap { acquired =>
        try Right(new LiveSession(
          recordings,
          profile,
          OpenAiCompatibleModelClient.from(authorization),
          acquired
        ))
        catch
          case NonFatal(_) =>
            acquired.close()
            Left("cannot construct the live text client")
      }

  def replay(recordings: Recordings, profile: Profile): Session =
    new ReplaySession(recordings, profile)

  private def endpointOf(authorization: LiveAuthorization.OpenAiCompatible): Checksum =
    Checksum.ofText(authorization.chatCompletions.normalize.toASCIIString)

  private final case class HeldLock(channel: FileChannel, lock: FileLock):
    def close(): Unit =
      try lock.release()
      finally channel.close()

  private def lock(dir: Path): Either[String, HeldLock] =
    var opened: Option[FileChannel] = None
    try
      val channel = FileChannel.open(
        dir.resolve(LockName),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.WRITE
      )
      opened = Some(channel)
      try
        Option(channel.tryLock()) match
          case Some(value) =>
            opened = None
            Right(HeldLock(channel, value))
          case None =>
            channel.close()
            Left("a live text session already owns this recordings directory")
      catch
        case _: OverlappingFileLockException =>
          channel.close()
          Left("a live text session already owns this recordings directory")
    catch
      case NonFatal(_) =>
        opened.foreach { channel =>
          try channel.close()
          catch case NonFatal(_) => ()
        }
        Left("cannot acquire the live text session lock")

  private enum JournalState:
    case Started
    case Failed(code: String, httpAttempts: Int)

  private abstract class BaseSession(profile: Profile) extends Session:
    protected val events = mutable.ArrayBuffer.empty[Json]
    protected var calls = 0
    protected var hits = 0
    protected var attempts = 0

    final def clientInvocations: Int = synchronized(calls)
    final def httpAttempts: Int = synchronized(attempts)
    final def cacheHits: Int = synchronized(hits)
    final def receipt: Json = synchronized {
      Json.obj(
        "profileSha256" -> profile.digest.hex.asJson,
        "clientInvocations" -> calls.asJson,
        "httpAttempts" -> attempts.asJson,
        "cacheHits" -> hits.asJson,
        "exchanges" -> Json.fromValues(events.toVector)
      )
    }

    protected def event(key: RecordingKey, status: String, reply: Option[ModelReply]): Unit =
      val providerEvidence = reply.collect {
        case ModelReply(_, _, _, ReplyEvidence.Captured(model, usage, _)) =>
          Json.obj(
            "reportedModel" -> model.asJson,
            "usage" -> Json.obj(
              "inputTokens" -> usage.inputTokens.asJson,
              "outputTokens" -> usage.outputTokens.asJson,
              "cacheReadInputTokens" -> usage.cacheReadInputTokens.asJson
            )
          )
      }
      events += Json.obj(
        "key" -> key.checksum.hex.asJson,
        "status" -> status.asJson,
        "clientInvocations" -> calls.asJson,
        "httpAttempts" -> attempts.asJson,
        "provider" -> providerEvidence.getOrElse(Json.obj())
      )

  private def readRecording(recordings: Recordings, request: ModelRequest): Either[ExchangeFailure, ModelReply] =
    val path = recordings.path(request.key)
    if Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path) then
      Left(ExchangeFailure.RecordingCorrupt(request.key, Checksum.ofText("not a regular recording file")))
    else recordings.read(request.key, request.model)

  private final class ReplaySession(recordings: Recordings, profile: Profile)
      extends BaseSession(profile):
    def serve(system: String, prompt: String): Either[ExchangeFailure, ModelReply] = synchronized {
      val request = ModelRequest.text(profile, system, prompt)
      readRecording(recordings, request) match
        case Right(reply) =>
          hits += 1
          event(request.key, "replayed", Some(reply))
          Right(reply)
        case Left(failure) =>
          event(request.key, "refused", None)
          Left(failure)
    }
    def close(): Unit = ()

  private final class LiveSession(
      recordings: Recordings,
      profile: Profile,
      client: OpenAiCompatibleModelClient,
      held: HeldLock
  ) extends BaseSession(profile):
    private val journal = recordings.dir.resolve(JournalName)
    private var closed = false

    private def states: Either[Unit, Map[String, JournalState]] =
      val temporary = recordings.dir.resolve(s"$JournalName.tmp")
      if Files.exists(temporary) || (Files.exists(journal) && !Files.isRegularFile(journal)) then
        Left(())
      else if !Files.isRegularFile(journal) then Right(Map.empty)
      else
        for
          raw <- scala.util
            .Try(Files.readString(journal, StandardCharsets.UTF_8))
            .toEither
            .left
            .map(_ => ())
          json <- parser.parse(raw).left.map(_ => ())
          schema <- json.hcursor.get[String]("schema").left.map(_ => ())
          _ <- Either.cond(schema == JournalSchema, (), ())
          rows <- json.hcursor.get[Vector[Json]]("entries").left.map(_ => ())
          entries <- rows.foldLeft[Either[Unit, Vector[(String, JournalState)]]](
            Right(Vector.empty)
          ) { case (acc, row) =>
            for
              done <- acc
              key <- row.hcursor.get[String]("key").left.map(_ => ())
              _ <- Either.cond(key.matches("[0-9a-f]{64}") && !done.exists(_._1 == key), (), ())
              status <- row.hcursor.get[String]("status").left.map(_ => ())
              state <- status match
                case "started" =>
                  for
                    attempts <- row.hcursor.get[String]("httpAttemptsStarted").left.map(_ => ())
                    usage <- row.hcursor.get[String]("usage").left.map(_ => ())
                    _ <- Either.cond(attempts == "unknown" && usage == "unknown", (), ())
                  yield JournalState.Started
                case "failed" =>
                  for
                    code <- row.hcursor.get[String]("failureCode").left.map(_ => ())
                    attempts <- row.hcursor.get[Int]("httpAttempts").left.map(_ => ())
                    usage <- row.hcursor.get[String]("usage").left.map(_ => ())
                    _ <- Either.cond(code.nonEmpty && attempts >= 0 && usage == "absent", (), ())
                  yield JournalState.Failed(code, attempts)
                case _ => Left(())
            yield done :+ (key -> state)
          }
        yield entries.toMap

    private def persist(entries: Map[String, JournalState]): Either[ExchangeFailure, Unit] =
      val encoded = Json
        .obj(
          "schema" -> JournalSchema.asJson,
          "entries" -> Json.fromValues(entries.toVector.sortBy(_._1).map { case (key, state) =>
            state match
              case JournalState.Started =>
                Json.obj(
                  "key" -> key.asJson,
                  "status" -> "started".asJson,
                  "httpAttemptsStarted" -> "unknown".asJson,
                  "usage" -> "unknown".asJson
                )
              case JournalState.Failed(code, attempts) =>
                Json.obj(
                  "key" -> key.asJson,
                  "status" -> "failed".asJson,
                  "failureCode" -> code.asJson,
                  "httpAttempts" -> attempts.asJson,
                  "usage" -> "absent".asJson
                )
          })
        )
        .noSpaces
      val temporary = recordings.dir.resolve(s"$JournalName.tmp")
      try
        Files.writeString(temporary, encoded, StandardCharsets.UTF_8)
        Files.move(
          temporary,
          journal,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
        Right(())
      catch
        case NonFatal(error) =>
          Left(
            ExchangeFailure.RecordingUnwritable(
              RecordingKey.fromChecksum(profile.digest),
              Checksum.ofText(error.getClass.getName)
            )
          )

    def serve(system: String, prompt: String): Either[ExchangeFailure, ModelReply] = synchronized {
      if closed then
        Left(ExchangeFailure.PreviousAttemptUnknown(RecordingKey.fromChecksum(profile.digest)))
      else
        val request = ModelRequest.text(profile, system, prompt)
        readRecording(recordings, request) match
          case Right(reply) =>
            hits += 1
            event(request.key, "cached", Some(reply))
            Right(reply)
          case Left(ExchangeFailure.RecordingMissing(_)) =>
            states match
              case Left(_) =>
                event(request.key, "unknown", None)
                Left(ExchangeFailure.PreviousAttemptUnknown(request.key))
              case Right(existing) =>
                existing.get(request.key.checksum.hex) match
                  case Some(JournalState.Started) =>
                    event(request.key, "unknown", None)
                    Left(ExchangeFailure.PreviousAttemptUnknown(request.key))
                  case Some(JournalState.Failed(_, _)) =>
                    event(request.key, "previous-failure", None)
                    Left(ExchangeFailure.PreviousAttemptFailed(request.key))
                  case None =>
                    persist(existing.updated(request.key.checksum.hex, JournalState.Started))
                      .flatMap { _ =>
                        calls += 1
                        val before = client.httpAttempts
                        client.complete(request) match
                          case Right(reply) =>
                            attempts += client.httpAttempts - before
                            recordings.write(request.key, reply) match
                              case Right(_) =>
                                event(request.key, "recorded", Some(reply))
                                Right(reply)
                              case Left(failure) =>
                                event(request.key, "write-failed", Some(reply))
                                Left(failure)
                          case Left(failure) =>
                            attempts += client.httpAttempts - before
                            val callAttempts = client.httpAttempts - before
                            persist(
                              existing.updated(
                                request.key.checksum.hex,
                                JournalState.Failed(failure.code.value, callAttempts)
                              )
                            ) match
                              case Right(_) =>
                                event(request.key, "failed", None)
                                Left(failure)
                              case Left(journalFailure) => Left(journalFailure)
                      }
          case Left(failure) =>
            event(request.key, "corrupt", None)
            Left(failure)
    }

    def close(): Unit = synchronized {
      if !closed then
        closed = true
        held.close()
    }
