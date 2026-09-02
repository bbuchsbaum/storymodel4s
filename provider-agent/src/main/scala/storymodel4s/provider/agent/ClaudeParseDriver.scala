package storymodel4s.provider.agent

import cats.Id
import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal
import storymodel4s.acquire.{
  BuildReceiptBuilder,
  ExtendedBuildReceipt,
  StageCacheKey,
  StageLocalConfig,
  StageRecord
}
import storymodel4s.amr.schema.StarterLexicon
import storymodel4s.core.*
import storymodel4s.proposition as p
import storymodel4s.provider.parser.*

/** Whether the driver may call the model. `Replay` needs no credentials and cannot spend. */
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
  case LiveRefused(refusal: LiveRefusal)
  case RecordingsUnavailable(error: RecordingsError)
  case TextUnreadable(path: String, reasonChecksum: Checksum)
  case SourceInvalid(detail: String)
  case InputInvalid(detail: String)
  case PromptUnavailable(error: PromptPackageLoadError)
  case TransportRefused(error: TransportSetupError)
  case ConfigInvalid(detail: String)
  case OutputUnwritable(path: String, reasonChecksum: Checksum)
  case ReceiptInvalid(detail: String)

  def message: String = this match
    case UnknownMode(raw)             => s"unknown mode '$raw'; expected replay or record"
    case LiveRefused(refusal)         => refusal.message
    case RecordingsUnavailable(error) => error.message
    case TextUnreadable(path, reason) => s"cannot read $path (reason ${reason.short()})"
    case SourceInvalid(detail)        => s"story source refused: $detail"
    case InputInvalid(detail)         => s"parser input refused: $detail"
    case PromptUnavailable(error)     => error.message
    case TransportRefused(error)      => s"transport refused: ${error.message}"
    case ConfigInvalid(detail)        => s"parser config refused: $detail"
    case OutputUnwritable(path, sum)  => s"cannot write $path (reason ${sum.short()})"
    case ReceiptInvalid(detail)       => s"build receipt refused: $detail"

/** How one sentence's reply was obtained, derived from the recordings store before and after the
  * run rather than from the mode argument.
  */
enum RecordingService:
  /** A hand-written recording that existed before the run. */
  case ReplayedAuthored

  /** A provider-captured recording that existed before the run. */
  case ReplayedCaptured

  /** No recording before the run; a captured one after it: this run called the provider. */
  case CapturedLive

  /** No recording after the run: a replay miss, or a live call that produced nothing to keep. */
  case Unrecorded

  /** An authored recording that appeared during the run; this run cannot have written it. */
  case Foreign

  /** A recording present but refused on read; no call was made for it. */
  case Corrupt

  /** True only for a recording that existed before the run and was admitted on read. */
  def servedFromPresentRecording: Boolean = this match
    case ReplayedAuthored | ReplayedCaptured           => true
    case CapturedLive | Unrecorded | Foreign | Corrupt => false

  def render: String = this match
    case ReplayedAuthored => "replayed-authored"
    case ReplayedCaptured => "replayed-captured"
    case CapturedLive     => "captured-live"
    case Unrecorded       => "unrecorded"
    case Foreign          => "foreign"
    case Corrupt          => "corrupt"

/** Counts a run publishes. Privately constructed because the counts stand in relation to one
  * another and to the batch; only `derive` establishes them from a result and a ledger.
  */
final class DriverSummary private (
    val mode: DriverMode,
    val storyId: StoryId,
    val sourceChecksum: Checksum,
    val sentences: Int,
    val proposed: Int,
    val failed: Int,
    val abstained: Int,
    val transportFailures: Int,
    val replayedAuthored: Int,
    val replayedCaptured: Int,
    val capturedLive: Int,
    val unrecorded: Int,
    val foreign: Int,
    val corrupt: Int,
    val receiptChecksum: Checksum
):
  /** Provider calls this run attempted: zero under replay by construction of `Recorded`; under
    * record, every key that was absent before the run (captured, or attempted and not kept). A
    * corrupt recording is refused without a call and never counts.
    */
  def liveCalls: Int = mode match
    case DriverMode.Replay => 0
    case DriverMode.Record => capturedLive + unrecorded

  private def parts = (
    mode,
    storyId,
    sourceChecksum,
    sentences,
    proposed,
    failed,
    abstained,
    transportFailures,
    replayedAuthored,
    replayedCaptured,
    capturedLive,
    unrecorded,
    foreign,
    corrupt,
    receiptChecksum
  )

  override def equals(other: Any): Boolean = other match
    case that: DriverSummary => parts == that.parts
    case _                   => false

  override def hashCode(): Int = parts.hashCode

  override def toString: String =
    s"DriverSummary(${mode.render}, ${storyId.value}, sentences=$sentences, proposed=$proposed)"

object DriverSummary:
  private def isAbstained(attempt: ParserAttempt): Boolean = attempt.result match
    case Left(ParserFailure.ProviderAbstained(_)) => true
    case _                                        => false

  private def isTransportFailure(attempt: ParserAttempt): Boolean = attempt.result match
    case Left(
          ParserFailure.Timeout(_) | ParserFailure.NonZeroExit(_, _) |
          ParserFailure.MaterializationFailed(_) | ParserFailure.TransportIo(_)
        ) =>
      true
    case _ => false

  private[agent] def status(attempt: ParserAttempt): String =
    if attempt.result.isRight then "proposed"
    else if isAbstained(attempt) then "abstained"
    else "failed"

  private[agent] def derive(
      mode: DriverMode,
      storyId: StoryId,
      sourceChecksum: Checksum,
      result: ParserBatchResult,
      services: Vector[RecordingService],
      receiptChecksum: Checksum
  ): DriverSummary =
    val attempts = result.attempts
    new DriverSummary(
      mode,
      storyId,
      sourceChecksum,
      sentences = attempts.size,
      proposed = attempts.count(_.result.isRight),
      failed = attempts.count(attempt => attempt.result.isLeft && !isAbstained(attempt)),
      abstained = attempts.count(isAbstained),
      transportFailures = attempts.count(isTransportFailure),
      replayedAuthored = services.count(_ == RecordingService.ReplayedAuthored),
      replayedCaptured = services.count(_ == RecordingService.ReplayedCaptured),
      capturedLive = services.count(_ == RecordingService.CapturedLive),
      unrecorded = services.count(_ == RecordingService.Unrecorded),
      foreign = services.count(_ == RecordingService.Foreign),
      corrupt = services.count(_ == RecordingService.Corrupt),
      receiptChecksum
    )

/** Where the driver obtains its client for `record` mode: the SDK behind the environment court, or
  * an offline scripted client so the record path can be exercised without spend. Both pass the
  * environment court first; the source decides only what answers once the court has admitted.
  */
sealed trait ExchangeSource

object ExchangeSource:
  /** The Anthropic SDK client, built only from an admitted `LiveAuthorization`. */
  case object Anthropic extends ExchangeSource

  /** An offline client with fixed replies; for tests and dry runs. */
  final case class Scripted(client: ScriptedModelClient) extends ExchangeSource

/** Everything one text-to-charts run established, before any file is written.
  *
  * Why a separate value from [[DriverSummary]]: a downstream orchestrator (the `pipeline` module)
  * needs the story, atlas, admitted charts, and receipt to feed the narrative compiler, while the
  * summary is the reportable count set. The constructor is private because the fields stand in
  * relation (one attempt and one service per batch input, a receipt whose stage names this driver);
  * only [[ClaudeParseDriver.parse]] establishes them.
  */
final class ParseOutcome private (
    val mode: DriverMode,
    val story: StorySource,
    val atlas: SurfaceAtlas,
    val batch: ParserBatch,
    val result: ParserBatchResult,
    val services: Vector[RecordingService],
    val recordingKeys: Vector[RecordingKey],
    val receipt: ExtendedBuildReceipt,
    val parserStage: (StageId, Checksum),
    val summary: DriverSummary,
    private[agent] val recordings: Recordings,
    private[agent] val runtime: RemoteRuntime,
    private[agent] val prompt: AgentPromptPackage
):
  /** Admitted charts in atlas sentence order: exactly the covered attempts, keyed by the atlas
    * sentence each input was cut from. A failed or abstained sentence has no entry, so a consumer
    * counting charts against `atlas.sentences` sees the shortfall rather than a placeholder.
    */
  def charts: Vector[(SurfaceUnitId, p.PropositionEvidence)] =
    batch.inputs.zip(result.attempts).flatMap { (input, attempt) =>
      attempt.result.toOption.map(proposal => input.sentenceId -> proposal.evidence)
    }

  private def parts = (
    mode,
    story,
    atlas,
    batch,
    result,
    services,
    recordingKeys,
    receipt,
    parserStage,
    summary,
    recordings.dir,
    runtime,
    prompt
  )

  override def equals(other: Any): Boolean = other match
    case that: ParseOutcome => parts == that.parts
    case _                  => false

  override def hashCode(): Int = parts.hashCode

  override def toString: String =
    s"ParseOutcome(${mode.render}, ${story.id.value}, covered=${result.covered}/${result.total})"

object ParseOutcome:
  private[agent] def derive(
      mode: DriverMode,
      story: StorySource,
      atlas: SurfaceAtlas,
      batch: ParserBatch,
      result: ParserBatchResult,
      services: Vector[RecordingService],
      recordingKeys: Vector[RecordingKey],
      receipt: ExtendedBuildReceipt,
      summary: DriverSummary,
      recordings: Recordings,
      runtime: RemoteRuntime,
      prompt: AgentPromptPackage
  ): Either[DriverError, ParseOutcome] =
    receipt.receipt.stages
      .find(_._1 == ClaudeParseDriver.Stage)
      .toRight(DriverError.ReceiptInvalid(s"receipt lacks stage ${ClaudeParseDriver.Stage.value}"))
      .map { stage =>
        new ParseOutcome(
          mode,
          story,
          atlas,
          batch,
          result,
          services,
          recordingKeys,
          receipt,
          stage,
          summary,
          recordings,
          runtime,
          prompt
        )
      }

/** Text to charts through the real admission court, with a content-keyed record/replay store so
  * reruns and tests never touch the network.
  *
  * Why a driver and not a library entry point: the outputs are files a person reads, and the
  * environment court for spend belongs at the process boundary, not inside a provider.
  */
object ClaudeParseDriver:
  val StageSchemaVersion: String = "storymodel4s.provider.agent.claude-parse/v1"
  val Stage: StageId = StageId.unsafe("provider-agent/claude-parse")
  val TimeoutMillis: Long = 120000L
  val MaxTokens: Long = ClaudeParserTransport.DefaultMaxTokens

  /** No seed: live reruns are not fingerprint-identical; replay from recordings is the claim. */
  def config(prompt: AgentPromptPackage): Either[DriverError, ParserConfig] =
    ParserConfig
      .from(
        ClaudeParserTransport.configParams(prompt, MaxTokens),
        seed = None,
        timeoutMillis = TimeoutMillis
      )
      .left
      .map(error => DriverError.ConfigInvalid(error.message))

  def transport(
      prompt: AgentPromptPackage,
      exchange: ModelExchange
  ): Either[DriverError, ClaudeParserTransport] =
    ClaudeParserTransport
      .from(prompt, exchange, MaxTokens)
      .left
      .map(DriverError.TransportRefused(_))

  /** Build the provider stack for one exchange: court, then per-sentence isolation. */
  def provider(
      transport: ClaudeParserTransport,
      config: ParserConfig
  ): AmrCandidateProvider[Id] =
    SentenceIsolatingParserProvider[Id](
      JsonAmrCandidateProvider[Id](
        ParserRuntime.Remote(transport.runtime),
        config,
        StarterLexicon.lexicon,
        transport
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

  /** Run one text through the court and write the per-sentence artifacts, ledger, and summary. The
    * ordering guarantees of [[parse]] hold; the receipt is established before the first write, so a
    * refused receipt leaves `outDir` untouched.
    */
  def run(
      mode: DriverMode,
      textPath: Path,
      recordingsDir: Path,
      outDir: Path,
      env: Map[String, String],
      nowEpochMillis: Long,
      source: ExchangeSource = ExchangeSource.Anthropic
  ): Either[DriverError, DriverSummary] =
    for
      outcome <- parse(mode, textPath, recordingsDir, env, nowEpochMillis, source)
      _ <- writeOutputs(
        outDir,
        outcome.batch,
        outcome.result,
        outcome.recordings,
        outcome.runtime,
        outcome.recordingKeys,
        outcome.services
      )
      _ <- writeSummary(outDir, outcome.summary, outcome.runtime, outcome.prompt, outcome.receipt)
    yield outcome.summary

  /** Run one text through the court without writing anything. Order matters: the environment court
    * for `record` comes before any read, the text is read before the recordings directory may be
    * created, and `replay` never creates anything.
    */
  def parse(
      mode: DriverMode,
      textPath: Path,
      recordingsDir: Path,
      env: Map[String, String],
      nowEpochMillis: Long,
      source: ExchangeSource = ExchangeSource.Anthropic
  ): Either[DriverError, ParseOutcome] =
    for
      client <- mode match
        case DriverMode.Replay => Right(None)
        case DriverMode.Record =>
          LiveAuthorization
            .from(env)
            .left
            .map(DriverError.LiveRefused(_))
            .map { admitted =>
              source match
                case ExchangeSource.Anthropic         => Some(LiveModelClient.from(admitted))
                case ExchangeSource.Scripted(offline) => Some(offline)
            }
      text <- readText(textPath)
      story <- StorySource
        .fromText(text, Some(textPath.getFileName.toString))
        .left
        .map(error => DriverError.SourceInvalid(error.message))
      atlas = SurfaceAnalyzer.analyze(story)
      batch <- inputs(atlas)
      prompt <- AgentPromptPackage.load().left.map(DriverError.PromptUnavailable(_))
      parserConfig <- config(prompt)
      recordings <- (mode match
        case DriverMode.Replay => Recordings.open(recordingsDir)
        case DriverMode.Record => Recordings.at(recordingsDir)
      ).left.map(DriverError.RecordingsUnavailable(_))
      exchange = client.fold[ModelExchange](new ModelExchange.Recorded(recordings))(admitted =>
        new ModelExchange.RecordingLive(admitted, recordings)
      )
      claude <- transport(prompt, exchange)
      keys = recordingKeys(claude.runtime, prompt, batch)
      present = keys.map(recordings.contains)
      result = provider(claude, parserConfig).parse(batch)
      services = keys.zip(present).map { (key, wasPresent) =>
        service(recordings, claude.runtime, key, wasPresent)
      }
      receipt <- buildReceipt(
        story,
        claude.runtime,
        parserConfig,
        prompt,
        result,
        cached = services.forall(_.servedFromPresentRecording),
        nowEpochMillis
      )
      summary = DriverSummary.derive(
        mode,
        story.id,
        story.canonicalChecksum,
        result,
        services,
        receipt.receipt.contentChecksum
      )
      outcome <- ParseOutcome.derive(
        mode,
        story,
        atlas,
        batch,
        result,
        services,
        keys,
        receipt,
        summary,
        recordings,
        claude.runtime,
        prompt
      )
    yield outcome

  /** Classify how one key was served from what the store holds before and after the run. */
  private[agent] def service(
      recordings: Recordings,
      runtime: RemoteRuntime,
      key: RecordingKey,
      wasPresent: Boolean
  ): RecordingService =
    recordings.read(key, runtime.model) match
      case Right(reply) =>
        reply.evidence match
          case ReplyEvidence.Authored if wasPresent          => RecordingService.ReplayedAuthored
          case ReplyEvidence.Captured(_, _, _) if wasPresent => RecordingService.ReplayedCaptured
          case ReplyEvidence.Captured(_, _, _)               => RecordingService.CapturedLive
          case ReplyEvidence.Authored                        => RecordingService.Foreign
      case Left(ExchangeFailure.RecordingMissing(_)) => RecordingService.Unrecorded
      case Left(_)                                   => RecordingService.Corrupt

  /** The keys the transport will use, derived by the same rendering it performs. */
  private[agent] def recordingKeys(
      runtime: RemoteRuntime,
      prompt: AgentPromptPackage,
      batch: ParserBatch
  ): Vector[RecordingKey] =
    batch.inputs.map { input =>
      ModelRequest
        .render(runtime.model, prompt, RequestItem.fromInput(input), MaxTokens, TimeoutMillis)
        .key
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
      runtime: RemoteRuntime,
      keys: Vector[RecordingKey],
      services: Vector[RecordingService]
  ): Either[DriverError, Unit] =
    val rows = batch.inputs.zip(result.attempts).zip(keys)
    val perSentence = rows.foldLeft[Either[DriverError, Unit]](Right(())) {
      case (acc, ((input, attempt), key)) =>
        acc.flatMap { _ =>
          val penmanWrite = recordings.read(key, runtime.model) match
            case Right(reply) =>
              write(
                outDir.resolve(s"${input.id.value}.penman"),
                ReplyInterpretation.normalize(reply.text) + "\n"
              )
            case Left(_) => Right(())
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
      write(
        outDir.resolve("receipts.json"),
        receiptsJson(batch, result, keys, services).spaces2 + "\n"
      )
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

  private def receiptsJson(
      batch: ParserBatch,
      result: ParserBatchResult,
      keys: Vector[RecordingKey],
      services: Vector[RecordingService]
  ): Json =
    Json.arr(batch.inputs.zip(result.attempts).zip(keys.zip(services)).map {
      case ((input, attempt), (key, service)) =>
        Json.obj(
          "id" -> input.id.value.asJson,
          "sentenceId" -> input.sentenceId.value.asJson,
          "tokens" -> input.tokens.size.asJson,
          "recordingKey" -> key.checksum.hex.asJson,
          "served" -> service.render.asJson,
          "status" -> DriverSummary.status(attempt).asJson,
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
      cached: Boolean,
      nowEpochMillis: Long
  ): Either[DriverError, ExtendedBuildReceipt] =
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
      cached = cached
    )
    BuildReceiptBuilder
      .start(source.id, source.canonicalChecksum, StageSchemaVersion)
      .record(record)
      .buildChecked(nowEpochMillis)
      .left
      .map(error => DriverError.ReceiptInvalid(error.message))

  private def stageJson(record: StageRecord): Json = Json.obj(
    "stage" -> record.stage.value.asJson,
    "key" -> record.key.checksum.hex.asJson,
    "inputs" -> record.inputs.map(_.hex).asJson,
    "outputs" -> record.outputs.size.asJson,
    "outputChecksum" -> record.outputChecksum.hex.asJson,
    "calls" -> record.calls.size.asJson,
    "cached" -> record.cached.asJson
  )

  private def writeSummary(
      outDir: Path,
      summary: DriverSummary,
      runtime: RemoteRuntime,
      prompt: AgentPromptPackage,
      extended: ExtendedBuildReceipt
  ): Either[DriverError, Unit] =
    val receipt = extended.receipt
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
        "transportFailures" -> summary.transportFailures.asJson,
        "replayedAuthored" -> summary.replayedAuthored.asJson,
        "replayedCaptured" -> summary.replayedCaptured.asJson,
        "capturedLive" -> summary.capturedLive.asJson,
        "unrecorded" -> summary.unrecorded.asJson,
        "foreign" -> summary.foreign.asJson,
        "corrupt" -> summary.corrupt.asJson,
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
      "stages" -> extended.stages.map(stageJson).asJson,
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
  * `replay` reads an existing recordings directory and never calls the model. `record` needs
  * `STORYMODEL4S_AGENT_LIVE=1` and a nonblank `STORYMODEL4S_ANTHROPIC_API_KEY` (or
  * `ANTHROPIC_API_KEY`), serves any recording already present, and records new replies. Only counts
  * and checksums are printed; source prose never reaches stdout. The exit status is 2 when the run
  * could not start, 1 when any sentence never reached the admission court (a transport failure such
  * as a missing recording), and 0 otherwise.
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
          s"abstained=${summary.abstained} transportFailures=${summary.transportFailures} " +
          s"replayedAuthored=${summary.replayedAuthored} " +
          s"replayedCaptured=${summary.replayedCaptured} capturedLive=${summary.capturedLive} " +
          s"unrecorded=${summary.unrecorded} corrupt=${summary.corrupt} " +
          s"liveCalls=${summary.liveCalls} " +
          s"receipt=${summary.receiptChecksum.short()}"
      )
      if summary.transportFailures > 0 then sys.exit(1)
