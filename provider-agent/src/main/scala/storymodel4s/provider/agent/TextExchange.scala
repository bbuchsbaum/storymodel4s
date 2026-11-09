package storymodel4s.provider.agent

import io.circe.HCursor
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import storymodel4s.core.Checksum

/** Content identity of one free-text model exchange: a system prompt and a user prompt sent to one
  * provider and model, with nothing else on the wire.
  *
  * Why a separate key domain (`agent-text-exchange/v1`) rather than the parser's
  * `agent-recording/v2`: a parse request is keyed by its sentence and token list, a text exchange
  * by its whole prompt, so the two key materials are different shapes and must never share a
  * preimage. Both live in one `Recordings` directory type because the reply on disk is the same
  * evidence (`ModelReply`).
  *
  * Why the key takes the prompt texts and hashes them itself: a caller who supplied the hashes
  * could key a reply under a prompt it never sent. Only the harness importer, which has hashes and
  * no texts, uses the hash form, inside this package.
  */
object TextExchange:
  val KeyDomain: String = "agent-text-exchange/v1"

  /** The provider label for a model reached through the owner's subscription command-line client.
    * The model is the alias that client was asked for (for example `opus`); the model it reported
    * is kept separately on a captured reply.
    */
  val SubscriptionCliProvider: String = "claude-code-cli"

  private[agent] def keyOfHashes(
      provider: String,
      model: String,
      systemSha256: Checksum,
      promptSha256: Checksum
  ): RecordingKey =
    RecordingKey.fromChecksum(
      AgentIdentity.digest(KeyDomain, Vector(provider, model, systemSha256.hex, promptSha256.hex))
    )

  /** The recording key of sending `prompt` under `system` to `provider`/`model`. */
  def key(provider: String, model: String, system: String, prompt: String): RecordingKey =
    keyOfHashes(provider, model, Checksum.ofText(system), Checksum.ofText(prompt))

  /** Serve one exchange from recordings. Replay holds no client, so it cannot spend. */
  def replay(
      recordings: Recordings,
      provider: String,
      model: String,
      system: String,
      prompt: String
  ): Either[ExchangeFailure, ModelReply] =
    recordings.read(key(provider, model, system, prompt), model)

/** Imports the research harness's content-addressed reply cache as text-exchange recordings.
  *
  * Why: the harness (`tools/sherlock_mapper/llm.py`) recorded every subscription-CLI reply with the
  * SHA-256 of its system prompt and prompt, its model alias, the reported model, token usage and
  * wall time. Importing them lets the library replay those exact replies. The import trusts the
  * harness's own hashes, because the harness kept no prompt text; a replay is only served when the
  * library's freshly rendered prompt hashes to the same value, so a wrong hash can make a reply
  * unreachable but never attach it to a different prompt. The harness did not record a stop reason,
  * so it is imported as `unrecorded`, never as a guessed `end_turn`.
  */
object HarnessCacheImport:
  /** Why one cache record was refused. */
  enum Refusal:
    case Unreadable(file: String)
    case MissingField(file: String, field: String)
    case BadHash(file: String, field: String)
    case Negative(file: String, field: String)
    case CacheMissing
    case Unwritable(key: String)

  final case class Imported(records: Int, models: Map[String, Int])

  private def hash(c: HCursor, file: String, field: String): Either[Refusal, Checksum] =
    c.get[String](field)
      .left
      .map(_ => Refusal.MissingField(file, field))
      .flatMap(h =>
        Either.cond(h.matches("[0-9a-f]{64}"), Checksum.unsafe(h), Refusal.BadHash(file, field))
      )

  private def count(c: HCursor, file: String, field: String): Either[Refusal, Long] =
    c.downField("usage")
      .get[Long](field)
      .left
      .map(_ => Refusal.MissingField(file, s"usage.$field"))
      .flatMap(n => Either.cond(n >= 0L, n, Refusal.Negative(file, s"usage.$field")))

  /** One cache record as a recording key and a captured reply. */
  private[agent] def record(
      file: String,
      raw: String
  ): Either[Refusal, (RecordingKey, ModelReply)] =
    for
      doc <- parse(raw).left.map(_ => Refusal.Unreadable(file))
      c = doc.hcursor
      model <- c.get[String]("model").left.map(_ => Refusal.MissingField(file, "model"))
      system <- hash(c, file, "systemSha")
      prompt <- hash(c, file, "promptSha")
      text <- c.get[String]("result").left.map(_ => Refusal.MissingField(file, "result"))
      seconds <- c.get[Double]("seconds").left.map(_ => Refusal.MissingField(file, "seconds"))
      _ <- Either.cond(seconds >= 0.0 && seconds.isFinite, (), Refusal.Negative(file, "seconds"))
      input <- count(c, file, "input_tokens")
      output <- count(c, file, "output_tokens")
      cacheRead = c.downField("usage").get[Long]("cache_read_input_tokens").toOption
      reported = c.get[Vector[String]]("modelUsage").toOption.collect { case Vector(one) => one }
    yield TextExchange.keyOfHashes(TextExchange.SubscriptionCliProvider, model, system, prompt) ->
      ModelReply(
        model,
        text,
        ModelStopReason.Other("unrecorded"),
        ReplyEvidence.Captured(
          reported,
          ModelUsage(input, output, cacheRead),
          math.round(seconds * 1000.0)
        )
      )

  /** Import every `*.json` record in `cache` into `recordings`; any refused record refuses all. */
  def run(cache: Path, recordings: Recordings): Either[Refusal, Imported] =
    val listed =
      try
        val listing = Files.list(cache)
        try
          Right(listing.iterator.asScala.filter(_.getFileName.toString.endsWith(".json")).toVector)
        finally listing.close()
      catch case NonFatal(_) => Left(Refusal.CacheMissing)
    for
      files <- listed
      parsed <- files
        .sortBy(_.getFileName.toString)
        .foldLeft[Either[Refusal, Vector[(RecordingKey, ModelReply)]]](
          Right(Vector.empty)
        ) { (acc, f) =>
          acc.flatMap { done =>
            val name = f.getFileName.toString
            val raw =
              try Right(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))
              catch case NonFatal(_) => Left(Refusal.Unreadable(name))
            raw.flatMap(record(name, _)).map(done :+ _)
          }
        }
      _ <- parsed.foldLeft[Either[Refusal, Unit]](Right(())) { case (acc, (key, reply)) =>
        acc.flatMap(_ =>
          recordings
            .write(key, reply)
            .left
            .map(_ => Refusal.Unwritable(key.checksum.hex))
        )
      }
    yield Imported(parsed.size, parsed.groupBy(_._2.model).view.mapValues(_.size).toMap)
