package storymodel4s.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal
import storymodel4s.codec.StoryModelCodec
import storymodel4s.core.*
import storymodel4s.document.{
  ChartProposalProvider,
  ChartProposals,
  CoverageCounts,
  NarrativeCompilation,
  NarrativeCompiler,
  NarrativeCompilerError
}
import storymodel4s.provider.agent.{
  ClaudeParseDriver,
  DriverError,
  DriverMode,
  DriverSummary,
  ExchangeSource,
  ParseOutcome
}

/** Why a story build produced no bundle; every case is reportable without source prose.
  *
  * Why typed: the exit status is derived from the case, not from a message, so a caller can tell a
  * court that never convened from one that ran and refused.
  */
enum PipelineError:
  /** The parse driver refused before the court convened (mode, credentials, text, recordings). */
  case NotStarted(error: DriverError)

  /** The proposal provider refused the admitted charts. */
  case ProposalRefused(error: DomainError)

  /** The compiler input court refused what the proposal provider emitted. */
  case InputRefused(error: NarrativeCompilerError)

  /** The compiler refused the checked input. */
  case CompileRefused(error: NarrativeCompilerError)

  /** A bundle file could not be written; the reason is digested, never echoed. */
  case OutputUnwritable(path: String, reasonChecksum: Checksum)

  def message: String = this match
    case NotStarted(error)        => error.message
    case ProposalRefused(error)   => s"proposal provider refused: ${error.message}"
    case InputRefused(error)      => s"compiler input refused: ${error.message}"
    case CompileRefused(error)    => s"compiler refused: ${error.message}"
    case OutputUnwritable(p, sum) => s"cannot write $p (reason ${sum.short()})"

/** The process exit status of a story build, as a closed set with its numeric code attached.
  *
  * Why an enum and not an `Int`: the three meanings (the court never convened; it ran and the
  * bundle is not complete; complete) are decided from typed outcomes, and a caller matching on the
  * enum cannot confuse a code with a count.
  */
enum ExitStatus(val code: Int):
  /** Every sentence reached the parser court, the compiler accepted the input, and the three files
    * were written. Says nothing about validation: a partial draft is a complete bundle.
    */
  case Complete extends ExitStatus(0)

  /** The court ran but the bundle is not complete: a sentence never reached the parser court, the
    * compiler refused the input, or a file could not be written.
    */
  case Incomplete extends ExitStatus(1)

  /** Nothing ran: unknown mode, refused credentials, unreadable text, missing recordings. */
  case CouldNotStart extends ExitStatus(2)

object ExitStatus:
  /** Derive the status from the typed outcome; a run with a transport failure is incomplete even
    * though it wrote every file, because a sentence is missing from the court record.
    */
  def of(outcome: Either[PipelineError, BuildSummary]): ExitStatus = outcome match
    case Left(PipelineError.NotStarted(_))                      => CouldNotStart
    case Left(_)                                                => Incomplete
    case Right(summary) if summary.parser.transportFailures > 0 => Incomplete
    case Right(_)                                               => Complete

/** The bundle files a build writes, in write order. */
final case class BundleFiles(model: Path, report: Path, receipts: Path):
  def all: Vector[Path] = Vector(model, report, receipts)

/** Counts and checksums a build publishes. Privately constructed because every field is derived
  * from one parse outcome and one compilation; only [[BuildSummary.derive]] establishes them.
  */
final class BuildSummary private (
    val mode: DriverMode,
    val storyId: StoryId,
    val sourceChecksum: Checksum,
    val sentences: Int,
    val charts: Int,
    val parser: DriverSummary,
    val coverage: CoverageCounts,
    val gaps: Int,
    val errors: Int,
    val warnings: Int,
    val validated: Boolean,
    val fingerprint: Checksum,
    val candidateSet: Checksum,
    val modelChecksum: Checksum,
    val receiptChecksum: Checksum,
    val files: BundleFiles
):
  def liveCalls: Int = parser.liveCalls

  override def toString: String =
    s"BuildSummary(${mode.render}, ${storyId.value}, sentences=$sentences, charts=$charts, " +
      s"validated=$validated, fingerprint=${fingerprint.short()})"

object BuildSummary:
  private[pipeline] def derive(
      parsed: ParseOutcome,
      proposals: ChartProposals,
      compilation: NarrativeCompilation,
      files: BundleFiles
  ): BuildSummary =
    val report = compilation.validation.report
    new BuildSummary(
      parsed.mode,
      parsed.story.id,
      parsed.story.canonicalChecksum,
      sentences = parsed.atlas.sentences.size,
      charts = parsed.charts.size,
      parser = parsed.summary,
      coverage = proposals.counts,
      gaps = compilation.derivation.gaps.size,
      errors = report.errors.size,
      warnings = report.warnings.size,
      validated = compilation.validated.isDefined,
      fingerprint = compilation.fingerprint,
      candidateSet = compilation.derivation.candidateSet,
      modelChecksum = StoryModelCodec.contentChecksum(compilation.draft),
      receiptChecksum = compilation.receipt.contentChecksum,
      files
    )

/** Text to a three-file pre-bundle: charts through `provider-agent`, proposals through
  * `ChartProposalProvider`, a draft through `NarrativeCompiler`, and the model, report, and
  * receipts on disk.
  *
  * Why a separate module: every stage it calls is pure or already courted; this object owns only
  * I/O, receipt composition, and file layout (ADR 0009). Everything is computed before the first
  * write, so a refusal anywhere leaves `outDir` untouched.
  */
object StoryPipeline:
  val ModelFile: String = "storymodel.json"
  val ReportFile: String = "compilation-report.json"
  val ReceiptsFile: String = "receipts.json"

  def files(outDir: Path): BundleFiles =
    BundleFiles(outDir.resolve(ModelFile), outDir.resolve(ReportFile), outDir.resolve(ReceiptsFile))

  /** Build one text. `replay` never calls the model and refuses a missing recordings directory;
    * `record` passes the same environment court as the parse driver before any read or write.
    */
  def run(
      mode: DriverMode,
      textPath: Path,
      recordingsDir: Path,
      outDir: Path,
      env: Map[String, String],
      nowEpochMillis: Long,
      source: ExchangeSource = ExchangeSource.Anthropic
  ): Either[PipelineError, BuildSummary] =
    for
      parsed <- ClaudeParseDriver
        .parse(mode, textPath, recordingsDir, env, nowEpochMillis, source)
        .left
        .map(PipelineError.NotStarted(_))
      charts = parsed.charts
      proposals <- ChartProposalProvider
        .propose(parsed.story, parsed.atlas, charts)
        .left
        .map(PipelineError.ProposalRefused(_))
      input <- ChartProposalProvider
        .input(parsed.story, parsed.atlas, charts, Some(parsed.parserStage), nowEpochMillis)
        .left
        .map(PipelineError.InputRefused(_))
      compilation <- NarrativeCompiler.compile(input).left.map(PipelineError.CompileRefused(_))
      bundle = files(outDir)
      summary = BuildSummary.derive(parsed, proposals, compilation, bundle)
      model = StoryModelCodec.encode(compilation.draft)
      report = BundleJson.report(parsed, proposals, compilation, summary).spaces2 + "\n"
      receipts = BundleJson.receipts(parsed, compilation, summary).spaces2 + "\n"
      _ <- write(bundle.model, model)
      _ <- write(bundle.report, report)
      _ <- write(bundle.receipts, receipts)
    yield summary

  private def describe(error: Throwable): String =
    s"${error.getClass.getName}: ${Option(error.getMessage).getOrElse("")}"

  private def write(path: Path, content: String): Either[PipelineError, Unit] =
    try
      Option(path.getParent).foreach(parent => Files.createDirectories(parent))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      Right(())
    catch
      case NonFatal(error) =>
        Left(PipelineError.OutputUnwritable(path.toString, Checksum.ofText(describe(error))))

  /** One line of counts, checksums, and the output directory; never source prose. */
  def render(summary: BuildSummary, outDir: Path): String =
    val c = summary.coverage
    val p = summary.parser
    s"mode=${summary.mode.render} story=${summary.storyId.value} " +
      s"source=${summary.sourceChecksum.short()} sentences=${summary.sentences} " +
      s"charts=${summary.charts} proposed=${c.proposed} abstained=${c.abstained} " +
      s"emptyCharts=${c.emptyCharts} noCharts=${c.noCharts} " +
      s"transportFailures=${p.transportFailures} replayedAuthored=${p.replayedAuthored} " +
      s"replayedCaptured=${p.replayedCaptured} capturedLive=${p.capturedLive} " +
      s"unrecorded=${p.unrecorded} corrupt=${p.corrupt} liveCalls=${summary.liveCalls} " +
      s"gaps=${summary.gaps} errors=${summary.errors} warnings=${summary.warnings} " +
      s"validated=${summary.validated} fingerprint=${summary.fingerprint.short()} " +
      s"model=${summary.modelChecksum.short()} receipt=${summary.receiptChecksum.short()} " +
      s"out=$outDir"

/** Usage: `storyBuild <replay|record> <text-path> <recordings-dir> <out-dir>`.
  *
  * `replay` reads an existing recordings directory and never calls the model. `record` needs
  * `STORYMODEL4S_AGENT_LIVE=1` and a nonblank `STORYMODEL4S_ANTHROPIC_API_KEY` (or
  * `ANTHROPIC_API_KEY`), exactly as `claudeParse` does. Writes `storymodel.json`,
  * `compilation-report.json`, and `receipts.json` under `<out-dir>`. Only counts, checksums, and
  * paths are printed. Exit status: 2 when the run could not start, 1 when any sentence never
  * reached the parser court or the compiler refused the input, 0 otherwise.
  */
@main def storyBuild(mode: String, textPath: String, recordingsDir: String, outDir: String): Unit =
  val out = Paths.get(outDir)
  val outcome = DriverMode
    .parse(mode)
    .left
    .map(PipelineError.NotStarted(_))
    .flatMap { parsed =>
      StoryPipeline.run(
        parsed,
        Paths.get(textPath),
        Paths.get(recordingsDir),
        out,
        sys.env,
        System.currentTimeMillis()
      )
    }
  outcome match
    case Left(error)    => System.err.println(s"storyBuild: ${error.message}")
    case Right(summary) => println(StoryPipeline.render(summary, out))
  ExitStatus.of(outcome) match
    case ExitStatus.Complete => ()
    case failed              => sys.exit(failed.code)
