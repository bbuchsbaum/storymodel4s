package storymodel4s.provider.agent

import cats.Id
import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal
import storymodel4s.acquire.{BuildReceiptBuilder, StageCacheKey, StageLocalConfig, StageRecord}
import storymodel4s.amr.schema.StarterLexicon
import storymodel4s.core.*
import storymodel4s.proposition as p
import storymodel4s.provider.parser.*

/** Whether the driver may call the model. `Replay` is the default and cannot spend. */
enum DriverMode:
  case Replay
  case Record

  def render: String = this match
    case Replay => "replay"
    case Record => "record"

object DriverMode:
  def parse(raw: String): Either[DriverError, DriverMode] = raw match
    case "replay" => Right(Replay)
    case "record" => Right(Record)
    case other    => Left(DriverError.UnknownMode(other))

/** Why a driver run produced no outputs; every case is reportable without source prose. */
enum DriverError:
  case UnknownMode(raw: String)
  case TextUnreadable(path: String, reasonChecksum: Checksum)
  case SourceInvalid(detail: String)
  case InputInvalid(detail: String)
  case PromptUnavailable(error: PromptPackageLoadError)
  case RuntimeInvalid(detail: String)
  case ConfigInvalid(detail: String)
  case RecordingsUnavailable(error: RecordingsError)
  case LiveRefused(refusal: LiveRefusal)
  case OutputUnwritable(path: String, reasonChecksum: Checksum)
  case ReceiptInvalid(detail: String)

  def message: String = this match
    case UnknownMode(raw)             => s"unknown mode '$raw'; expected replay or record"
    case TextUnreadable(path, reason) => s"cannot read $path (reason ${reason.short()})"
    case SourceInvalid(detail)        => s"story source refused: $detail"
    case InputInvalid(detail)         => s"parser input refused: $detail"
    case PromptUnavailable(error)     => error.message
    case RuntimeInvalid(detail)       => s"remote runtime refused: $detail"
    case ConfigInvalid(detail)        => s"parser config refused: $detail"
    case RecordingsUnavailable(error) => error.message
    case LiveRefused(refusal)         => refusal.message
    case OutputUnwritable(path, sum)  => s"cannot write $path (reason ${sum.short()})"
    case ReceiptInvalid(detail)       => s"build receipt refused: $detail"

/** Counts a run publishes; no sentence text, only what the receipts already carry. */
final case class DriverSummary(
    mode: DriverMode,
    storyId: StoryId,
    sourceChecksum: Checksum,
    sentences: Int,
    proposed: Int,
    failed: Int,
    abstained: Int,
    recordingHits: Int,
    liveCalls: Int,
    receiptChecksum: Checksum
)

/** Text to charts through the real admission court, with a content-keyed record/replay store so
  * reruns and tests never touch the network.
  *
  * Why a driver and not a library entry point: the outputs are files a person reads, and the
  * environment court for spend belongs at the process boundary, not inside a provider.
  */
object ClaudeParseDriver:
  val Provider: String = "anthropic"
  val Model: String = "claude-sonnet-5"
  val SdkVersion: String = "anthropic-java/2.34.0"
  val StageSchemaVersion: String = "storymodel4s.provider.agent.claude-parse/v1"
  val Stage: StageId = StageId.unsafe("provider-agent/claude-parse")
  val TimeoutMillis: Long = 120000L
  val MaxTokens: Long = ClaudeParserTransport.DefaultMaxTokens

  /** The remote runtime the driver runs under; identity moves with the prompt package and text. */
  def runtime(prompt: AgentPromptPackage): Either[DriverError, RemoteRuntime] =
    RemoteRuntime
      .from(
        Provider,
        Model,
        SdkVersion,
        prompt.ref,
        prompt.promptTextChecksum,
        ParserEnvelope.ResultSchema
      )
      .left
      .map(error => DriverError.RuntimeInvalid(error.message))

  /** No seed: live reruns are not fingerprint-identical; replay from recordings is the claim. */
  def config(prompt: AgentPromptPackage): Either[DriverError, ParserConfig] =
    ParserConfig
      .from(
        Map(
          "prompt-package" -> prompt.ref.checksum.hex,
          "prompt-text" -> prompt.promptTextChecksum.hex,
          "max-tokens" -> MaxTokens.toString
        ),
        seed = None,
        timeoutMillis = TimeoutMillis
      )
      .left
      .map(error => DriverError.ConfigInvalid(error.message))

  /** Build the provider stack for one exchange: court, then per-sentence isolation. */
  def provider(
      runtime: RemoteRuntime,
      config: ParserConfig,
      prompt: AgentPromptPackage,
      exchange: ModelExchange
  ): AmrCandidateProvider[Id] =
    SentenceIsolatingParserProvider[Id](
      JsonAmrCandidateProvider[Id](
        ParserRuntime.Remote(runtime),
        config,
        StarterLexicon.lexicon,
        ClaudeParserTransport(runtime, prompt, exchange, MaxTokens)
      )
    )

  /** One parser input per atlas sentence, identified by sentence ordinal. */
  def inputs(atlas: SurfaceAtlas): Either[DriverError, ParserBatch] =
    atlas.sentences
      .foldLeft[Either[DriverError, Vector[ParserSentenceInput]]](Right(Vector.empty)) {
        (acc, sentence) =>
          acc.flatMap { done =>
            ParserRequestId
              .from(f"s${sentence.ordinal}%04d")
              .left
              .map(error => DriverError.InputInvalid(error.message))
              .flatMap { id =>
                ParserSentenceInput
                  .fromAtlas(id, atlas, sentence.id)
                  .left
                  .map(error => DriverError.InputInvalid(error.message))
              }
              .map(done :+ _)
          }
      }
      .flatMap(all =>
        ParserBatch.validated(all).left.map(error => DriverError.InputInvalid(error.message))
      )

  def run(
      mode: DriverMode,
      textPath: Path,
      recordingsDir: Path,
      outDir: Path,
      env: Map[String, String],
      nowEpochMillis: Long
  ): Either[DriverError, DriverSummary] =
    for
      text <- readText(textPath)
      source <- StorySource
        .fromText(text, Some(textPath.getFileName.toString))
        .left
        .map(error => DriverError.SourceInvalid(error.message))
      atlas = SurfaceAnalyzer.analyze(source)
      batch <- inputs(atlas)
      prompt <- AgentPromptPackage.load().left.map(DriverError.PromptUnavailable(_))
      remote <- runtime(prompt)
      parserConfig <- config(prompt)
      recordings <- Recordings.at(recordingsDir).left.map(DriverError.RecordingsUnavailable(_))
      exchange <- exchangeFor(mode, recordings, env)
      keys = recordingKeys(remote, prompt, batch)
      present = keys.count(recordings.contains)
      result = provider(remote, parserConfig, prompt, exchange).parse(batch)
      afterwards = keys.count(recordings.contains)
      _ <- writeOutputs(outDir, batch, result, recordings, keys)
      receipt <- buildReceipt(source, remote, parserConfig, prompt, result, mode, nowEpochMillis)
      summary = DriverSummary(
        mode,
        source.id,
        source.canonicalChecksum,
        batch.size,
        result.attempts.count(_.result.isRight),
        result.attempts.count(attempt => isFailed(attempt)),
        result.attempts.count(attempt => isAbstained(attempt)),
        present,
        afterwards - present,
        receipt.contentChecksum
      )
      _ <- writeSummary(outDir, summary, remote, prompt, receipt)
    yield summary

  private def isAbstained(attempt: ParserAttempt): Boolean = attempt.result match
    case Left(ParserFailure.ProviderAbstained(_)) => true
    case _                                        => false

  private def isFailed(attempt: ParserAttempt): Boolean =
    attempt.result.isLeft && !isAbstained(attempt)

  private def exchangeFor(
      mode: DriverMode,
      recordings: Recordings,
      env: Map[String, String]
  ): Either[DriverError, ModelExchange] = mode match
    case DriverMode.Replay => Right(new ModelExchange.Recorded(recordings))
    case DriverMode.Record =>
      AgentCredentials
        .liveAuthorization(env)
        .left
        .map(DriverError.LiveRefused(_))
        .map(authorization =>
          new ModelExchange.RecordingLive(LiveModelClient.from(authorization), recordings)
        )

  private def recordingKeys(
      runtime: RemoteRuntime,
      prompt: AgentPromptPackage,
      batch: ParserBatch
  ): Vector[RecordingKey] =
    batch.inputs.map { input =>
      RecordingKey.of(
        runtime.model,
        prompt.ref.checksum,
        prompt.promptTextChecksum,
        input.textChecksum,
        input.tokens.map(token =>
          RequestToken(token.id.value, token.span.start, token.span.endExclusive, token.text)
        )
      )
    }

  private def readText(path: Path): Either[DriverError, String] =
    try Right(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
    catch
      case NonFatal(error) =>
        Left(DriverError.TextUnreadable(path.toString, Checksum.ofText(describe(error))))

  private def describe(error: Throwable): String =
    s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"

  private def write(path: Path, content: String): Either[DriverError, Unit] =
    try
      Option(path.getParent).foreach(parent => Files.createDirectories(parent))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      Right(())
    catch
      case NonFatal(error) =>
        Left(DriverError.OutputUnwritable(path.toString, Checksum.ofText(describe(error))))

  private def writeOutputs(
      outDir: Path,
      batch: ParserBatch,
      result: ParserBatchResult,
      recordings: Recordings,
      keys: Vector[RecordingKey]
  ): Either[DriverError, Unit] =
    val perSentence = batch.inputs
      .zip(result.attempts)
      .zip(keys)
      .foldLeft[Either[DriverError, Unit]](
        Right(())
      ) { case (acc, ((input, attempt), key)) =>
        acc.flatMap { _ =>
          val penman = recordings
            .read(key, Model)
            .toOption
            .map(reply => ReplyInterpretation.normalize(reply.text))
          val penmanWrite = penman.fold[Either[DriverError, Unit]](Right(()))(text =>
            write(outDir.resolve(s"${input.id.value}.penman"), text + "\n")
          )
          penmanWrite.flatMap { _ =>
            attempt.result match
              case Right(proposal) =>
                val chart = p.Canonical.form(proposal.evidence.chart)
                write(
                  outDir.resolve(s"${input.id.value}.chart.txt"),
                  p.Canonical.serialization(chart) + "\n"
                )
              case Left(_) => Right(())
          }
        }
      }
    perSentence.flatMap { _ =>
      write(outDir.resolve("receipts.json"), receiptsJson(batch, result).spaces2 + "\n")
    }

  private def callJson(call: ProviderCall): Json = Json.obj(
    "provider" -> call.provider.asJson,
    "model" -> call.model.asJson,
    "version" -> call.version.asJson,
    "promptTemplateVersion" -> call.promptTemplateVersion.map(_.value).asJson,
    "inputChecksum" -> call.inputChecksum.hex.asJson,
    "outputChecksum" -> call.outputChecksum.hex.asJson,
    "params" -> Json.obj(call.params.toVector.sortBy(_._1).map((k, v) => k -> v.asJson)*),
    "seed" -> call.seed.asJson,
    "cached" -> call.cached.asJson
  )

  private def decisionJson(decision: ParserAttemptDecision): Json = decision match
    case ParserAttemptDecision.CacheHit(cached) =>
      Json.obj("kind" -> "cache-hit".asJson, "checksum" -> cached.checksum.hex.asJson)
    case ParserAttemptDecision.CacheMiss(key) =>
      Json.obj("kind" -> "cache-miss".asJson, "key" -> key.checksum.hex.asJson)
    case ParserAttemptDecision.RuntimeUnavailable(reason) =>
      Json.obj("kind" -> "runtime-unavailable".asJson, "checksum" -> reason.checksum.hex.asJson)
    case ParserAttemptDecision.TransportFailed(reason) =>
      Json.obj("kind" -> "transport-failed".asJson, "reason" -> reason.render.asJson)
    case ParserAttemptDecision.ModelInputObserved(observation) =>
      Json.obj("kind" -> "model-input".asJson, "checksum" -> observation.checksum.hex.asJson)
    case ParserAttemptDecision.AlignmentSidecarAccepted(sidecar) =>
      Json.obj(
        "kind" -> "alignment-sidecar".asJson,
        "dialect" -> sidecar.dialect.wireName.asJson,
        "rows" -> sidecar.providerNodeIds.size.asJson,
        "checksum" -> sidecar.checksum.hex.asJson
      )
    case ParserAttemptDecision.ResultRejected(reason) =>
      Json.obj("kind" -> "result-rejected".asJson, "reason" -> reason.render.asJson)

  private def status(attempt: ParserAttempt): String =
    if attempt.result.isRight then "proposed"
    else if isAbstained(attempt) then "abstained"
    else "failed"

  private def receiptsJson(batch: ParserBatch, result: ParserBatchResult): Json =
    Json.arr(batch.inputs.zip(result.attempts).map { (input, attempt) =>
      Json.obj(
        "id" -> input.id.value.asJson,
        "sentenceId" -> input.sentenceId.value.asJson,
        "tokens" -> input.tokens.size.asJson,
        "status" -> status(attempt).asJson,
        "failure" -> attempt.result.swap.toOption.map(_.render).asJson,
        "proposalDigest" -> attempt.result.toOption.map(_.canonicalDigest.hex).asJson,
        "requestChecksum" -> attempt.receipt.requestChecksum.hex.asJson,
        "receiptDigest" -> attempt.receipt.digest.hex.asJson,
        "call" -> attempt.receipt.call.map(callJson).asJson,
        "decisions" -> attempt.receipt.decisions.map(decisionJson).asJson
      )
    }*)

  private def buildReceipt(
      source: StorySource,
      runtime: RemoteRuntime,
      config: ParserConfig,
      prompt: AgentPromptPackage,
      result: ParserBatchResult,
      mode: DriverMode,
      nowEpochMillis: Long
  ): Either[DriverError, BuildReceipt] =
    val local = StageLocalConfig(Vector(prompt.ref), config.params, config.seed)
    val key = StageCacheKey.of(
      source.canonicalChecksum,
      StageSchemaVersion,
      config.checksum,
      runtime.fingerprint,
      local
    )
    val record = StageRecord(
      Stage,
      key,
      inputs = Vector(source.canonicalChecksum),
      outputs = result.attempts.map(_.receipt.digest),
      calls = result.attempts.flatMap(_.receipt.call),
      cached = mode == DriverMode.Replay
    )
    BuildReceiptBuilder
      .start(source.id, source.canonicalChecksum, StageSchemaVersion)
      .record(record)
      .buildChecked(nowEpochMillis)
      .left
      .map(error => DriverError.ReceiptInvalid(error.message))
      .map(_.receipt)

  private def writeSummary(
      outDir: Path,
      summary: DriverSummary,
      runtime: RemoteRuntime,
      prompt: AgentPromptPackage,
      receipt: BuildReceipt
  ): Either[DriverError, Unit] =
    val json = Json.obj(
      "mode" -> summary.mode.render.asJson,
      "source" -> Json.obj(
        "storyId" -> summary.storyId.value.asJson,
        "checksum" -> summary.sourceChecksum.hex.asJson,
        "sentences" -> summary.sentences.asJson
      ),
      "counts" -> Json.obj(
        "proposed" -> summary.proposed.asJson,
        "failed" -> summary.failed.asJson,
        "abstained" -> summary.abstained.asJson,
        "recordingHits" -> summary.recordingHits.asJson,
        "liveCalls" -> summary.liveCalls.asJson
      ),
      "runtime" -> Json.obj(
        "provider" -> runtime.provider.asJson,
        "model" -> runtime.model.asJson,
        "sdkVersion" -> runtime.sdkVersion.asJson,
        "weightsPinned" -> runtime.weightsPinned.asJson,
        "fingerprint" -> runtime.fingerprint.value.asJson,
        "promptPackage" -> Json.obj(
          "name" -> prompt.ref.name.asJson,
          "version" -> prompt.ref.version.asJson,
          "checksum" -> prompt.ref.checksum.hex.asJson
        ),
        "promptTextChecksum" -> prompt.promptTextChecksum.hex.asJson
      ),
      "buildReceipt" -> Json.obj(
        "storyId" -> receipt.storyId.value.asJson,
        "sourceChecksum" -> receipt.sourceChecksum.hex.asJson,
        "schemaVersion" -> receipt.schemaVersion.asJson,
        "stages" -> receipt.stages
          .map((stage, sum) =>
            Json.obj("stage" -> stage.value.asJson, "checksum" -> sum.hex.asJson)
          )
          .asJson,
        "createdAtEpochMillis" -> receipt.createdAtEpochMillis.asJson,
        "contentChecksum" -> receipt.contentChecksum.hex.asJson
      )
    )
    write(outDir.resolve("summary.json"), json.spaces2 + "\n")

/** Usage: `claudeParse <replay|record> <text-path> <recordings-dir> <out-dir>`.
  *
  * `replay` (the default expectation) reads recordings only. `record` needs
  * `STORYMODEL4S_AGENT_LIVE=1` and a nonblank `STORYMODEL4S_ANTHROPIC_API_KEY` (or
  * `ANTHROPIC_API_KEY`), serves any recording already present, and records new replies. Only counts
  * and checksums are printed; source prose never reaches stdout.
  */
@main def claudeParse(mode: String, textPath: String, recordingsDir: String, outDir: String): Unit =
  val outcome = DriverMode.parse(mode).flatMap { parsed =>
    ClaudeParseDriver.run(
      parsed,
      Paths.get(textPath),
      Paths.get(recordingsDir),
      Paths.get(outDir),
      sys.env,
      System.currentTimeMillis()
    )
  }
  outcome match
    case Left(error) =>
      System.err.println(s"claudeParse: ${error.message}")
      sys.exit(2)
    case Right(summary) =>
      println(
        s"mode=${summary.mode.render} story=${summary.storyId.value} " +
          s"source=${summary.sourceChecksum.short()} sentences=${summary.sentences} " +
          s"proposed=${summary.proposed} failed=${summary.failed} " +
          s"abstained=${summary.abstained} recordingHits=${summary.recordingHits} " +
          s"liveCalls=${summary.liveCalls} receipt=${summary.receiptChecksum.short()}"
      )
