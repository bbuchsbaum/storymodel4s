package storymodel4s.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal
import storymodel4s.codec.{DerivationArtifact, DerivationRecordCodec, StoryModelCodec}
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

  /** The compilation's receipt does not carry the parse it was fed (stage or source checksum). */
  case ReceiptMismatch(detail: String)

  /** The derivation record refused the compilation's own attempts, gaps, and coverage. */
  case RecordRefused(error: DomainError)

  /** A bundle file could not be written; the reason is digested, never echoed. */
  case OutputUnwritable(path: String, reasonChecksum: Checksum)

  def message: String = this match
    case NotStarted(error)        => error.message
    case ProposalRefused(error)   => s"proposal provider refused: ${error.message}"
    case InputRefused(error)      => s"compiler input refused: ${error.message}"
    case CompileRefused(error)    => s"compiler refused: ${error.message}"
    case ReceiptMismatch(detail)  => s"compilation receipt refused: $detail"
    case RecordRefused(error)     => s"derivation record refused: ${error.message}"
    case OutputUnwritable(p, sum) => s"cannot write $p (reason ${sum.short()})"

/** The process exit status of a story build, as a closed set with its numeric code attached.
  *
  * Why an enum and not an `Int`: the three meanings (the court never convened; it ran and the
  * bundle is not complete; complete) are decided from typed outcomes, and a caller matching on the
  * enum cannot confuse a code with a count.
  */
enum ExitStatus(val code: Int):
  /** Every sentence reached the parser court, the compiler accepted the input, and the four files
    * were written. Says nothing about validation: a partial draft is a complete bundle.
    */
  case Complete extends ExitStatus(0)

  /** The court ran but the bundle is not complete: a sentence never reached the parser court, the
    * parser's build receipt was refused after the court, the compiler refused the input, or a file
    * could not be written.
    */
  case Incomplete extends ExitStatus(1)

  /** The parser court never convened: unknown mode, refused credentials, unreadable text, refused
    * source, missing recordings, unavailable prompt package, refused transport or config.
    */
  case CouldNotStart extends ExitStatus(2)

object ExitStatus:
  /** Derive the status from the typed outcome; a run with a transport failure is incomplete even
    * though it wrote every file, because a sentence is missing from the court record. A refused
    * build receipt is incomplete, not could-not-start: in record mode it fires after the live calls
    * were made and their recordings kept.
    */
  def of(outcome: Either[PipelineError, BuildSummary]): ExitStatus = outcome match
    case Left(PipelineError.NotStarted(DriverError.ReceiptInvalid(_))) => Incomplete
    case Left(PipelineError.NotStarted(_))                             => CouldNotStart
    case Left(_)                                                       => Incomplete
    case Right(summary) if summary.parser.transportFailures > 0        => Incomplete
    case Right(_)                                                      => Complete

/** The bundle files a build writes, in write order.
  *
  * Why a value and not three strings: the layout is one decision (ADR 0009) that the writer, the
  * summary, and every test address by role, so a renamed file cannot drift between them.
  */
final case class BundleFiles(model: Path, report: Path, receipts: Path, derivation: Path):
  def all: Vector[Path] = Vector(model, report, receipts, derivation)

/** Counts and checksums a build publishes. Privately constructed because every field is derived
  * from one parse outcome and one compilation that [[BuildSummary.derive]] has checked belong
  * together (the compilation's receipt carries the parser stage and the source checksum of the
  * parse it was fed).
  *
  * Three identities, two of them stable: `fingerprint` (the compiler's content fingerprint) and
  * `receiptChecksum` (`BuildReceipt.contentChecksum`, which excludes the timestamp) are equal
  * across runs over the same inputs at any time; `encodingDigest` digests the canonical model
  * encoding, which carries `receipt.createdAtEpochMillis`, so it moves with the clock.
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
    val encodingDigest: Checksum,
    val receiptChecksum: Checksum,
    val files: BundleFiles
):
  def liveCalls: Int = parser.liveCalls

  private def parts = (
    mode,
    storyId,
    sourceChecksum,
    sentences,
    charts,
    parser,
    coverage,
    gaps,
    errors,
    warnings,
    validated,
    fingerprint,
    candidateSet,
    encodingDigest,
    receiptChecksum,
    files
  )

  override def equals(other: Any): Boolean = other match
    case that: BuildSummary => parts == that.parts
    case _                  => false

  override def hashCode(): Int = parts.hashCode

  override def toString: String =
    s"BuildSummary(${mode.render}, ${storyId.value}, sentences=$sentences, charts=$charts, " +
      s"validated=$validated, fingerprint=${fingerprint.short()})"

object BuildSummary:
  /** Refuse a compilation whose receipt does not carry this parse: its stage list must name the
    * parser stage `parse` established, carrying the digest of exactly the charts that were
    * compiled, and its source checksum must be the parsed story's.
    *
    * Why the digest is re-derived here rather than compared to the driver's: since the provider
    * derives the parser stage's digest from the charts themselves, comparing the driver's own
    * receipt digest would compare two different things. Re-deriving keeps the check load-bearing.
    */
  private[pipeline] def derive(
      parsed: ParseOutcome,
      proposals: ChartProposals,
      compilation: NarrativeCompilation,
      files: BundleFiles
  ): Either[PipelineError, BuildSummary] =
    val receipt = compilation.receipt
    val report = compilation.validation.report
    val expectedParserStage =
      (parsed.parserStage._1, ChartProposalProvider.chartsDigest(parsed.charts))
    if !receipt.stages.contains(expectedParserStage) then
      Left(
        PipelineError.ReceiptMismatch(
          s"receipt lacks parser stage ${expectedParserStage._1.value}=" +
            expectedParserStage._2.short()
        )
      )
    else if receipt.sourceChecksum != parsed.story.canonicalChecksum then
      Left(
        PipelineError.ReceiptMismatch(
          s"receipt source ${receipt.sourceChecksum.short()} is not the parsed source " +
            parsed.story.canonicalChecksum.short()
        )
      )
    else
      Right(
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
          encodingDigest = StoryModelCodec.contentChecksum(compilation.draft),
          receiptChecksum = receipt.contentChecksum,
          files
        )
      )

/** Text to a four-file pre-bundle: charts through `provider-agent`, proposals through
  * `ChartProposalProvider`, a draft through `NarrativeCompiler`, and the model, report, receipts,
  * and derivation record on disk.
  *
  * Why a separate module: every stage it calls is pure or already courted; this object owns only
  * I/O, receipt composition, and file layout (ADR 0009). Everything is computed before the first
  * write, so a refusal anywhere leaves `outDir` untouched.
  */
object StoryPipeline:
  val ModelFile: String = "storymodel.json"
  val ReportFile: String = "compilation-report.json"
  val ReceiptsFile: String = "receipts.json"

  /** The typed derivation record (`derivation-record/v1`): every attempt, gap, and coverage row the
    * compilation produced, bound to `storymodel.json` by the checksum of its bytes. It is what
    * `compilation-report.json` is not: readable back. The report keeps its counts and renders for a
    * person; a viewer reads this.
    */
  val DerivationFile: String = "derivation.json"

  def files(outDir: Path): BundleFiles =
    BundleFiles(
      outDir.resolve(ModelFile),
      outDir.resolve(ReportFile),
      outDir.resolve(ReceiptsFile),
      outDir.resolve(DerivationFile)
    )

  /** Build one text. `replay` never calls the model and refuses a missing recordings directory;
    * `record` passes the same environment court as the parse driver before any read or write.
    *
    * `title` is the caller's claim about the work, or nothing. Nothing is the honest default for a
    * bare text file: the pipeline no longer derives a title from the input file's name, so with no
    * title the summary family is unresolved and the model carries a summary gap rather than an
    * assertion that the narrative is called after its file.
    */
  def run(
      mode: DriverMode,
      textPath: Path,
      recordingsDir: Path,
      outDir: Path,
      env: Map[String, String],
      nowEpochMillis: Long,
      source: ExchangeSource = ExchangeSource.Court,
      title: Option[StoryTitle] = None
  ): Either[PipelineError, BuildSummary] =
    for
      parsed <- ClaudeParseDriver
        .parse(mode, textPath, recordingsDir, env, nowEpochMillis, source, title)
        .left
        .map(PipelineError.NotStarted(_))
      charts = parsed.charts
      proposals <- ChartProposalProvider
        .propose(parsed.story, parsed.atlas, charts)
        .left
        .map(PipelineError.ProposalRefused(_))
      input <- ChartProposalProvider
        .input(parsed.story, parsed.atlas, charts, Some(parsed.parserStage._1), nowEpochMillis)
        .left
        .map(PipelineError.InputRefused(_))
      compilation <- NarrativeCompiler.compile(input).left.map(PipelineError.CompileRefused(_))
      record <- DerivationArtifact
        .from(compilation, proposals)
        .left
        .map(PipelineError.RecordRefused(_))
      bundle = files(outDir)
      summary <- BuildSummary.derive(parsed, proposals, compilation, bundle)
      model = StoryModelCodec.encode(compilation.draft)
      report = BundleJson.report(parsed, proposals, compilation, summary).spaces2 + "\n"
      receipts = BundleJson.receipts(parsed, compilation, summary).spaces2 + "\n"
      derivation = DerivationRecordCodec.encode(record)
      _ <- write(bundle.model, model)
      _ <- write(bundle.report, report)
      _ <- write(bundle.receipts, receipts)
      _ <- write(bundle.derivation, derivation)
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
      s"charts=${summary.charts} proposed=${c.proposed} coordinated=${c.coordinated} " +
      s"abstained=${c.abstained} emptyCharts=${c.emptyCharts} noCharts=${c.noCharts} " +
      s"transportFailures=${p.transportFailures} replayedAuthored=${p.replayedAuthored} " +
      s"replayedCaptured=${p.replayedCaptured} capturedLive=${p.capturedLive} " +
      s"unrecorded=${p.unrecorded} corrupt=${p.corrupt} liveCalls=${summary.liveCalls} " +
      s"gaps=${summary.gaps} errors=${summary.errors} warnings=${summary.warnings} " +
      s"validated=${summary.validated} fingerprint=${summary.fingerprint.short()} " +
      s"encodingDigest(timestamp-bearing)=${summary.encodingDigest.short()} " +
      s"receipt=${summary.receiptChecksum.short()} " +
      s"out=$outDir"

/** Usage: `storyBuild <replay|record> <text-path> <recordings-dir> <out-dir> [title]`.
  *
  * `replay` reads an existing recordings directory and never calls the model. `record` needs
  * `STORYMODEL4S_AGENT_LIVE=1` and a nonblank `STORYMODEL4S_ANTHROPIC_API_KEY` (or
  * `ANTHROPIC_API_KEY`), exactly as `claudeParse` does. Writes `storymodel.json`,
  * `compilation-report.json`, `receipts.json`, and `derivation.json` under `<out-dir>`. Only
  * counts, checksums, and paths are printed. Exit status: 2 when the run could not start, 1 when
  * any sentence never reached the parser court or the compiler refused the input, 0 otherwise.
  *
  * The fifth argument, when given, is the story's title as the *caller's* claim, recorded with
  * caller-supplied provenance. Omit it and the story has no title, the summary family resolves to a
  * gap, and the model does not promote to validated. That is the honest outcome for a bare text
  * file: the fix for it is a summary rule that reads the story, not the input file's name.
  */
@main def storyBuild(
    mode: String,
    textPath: String,
    recordingsDir: String,
    outDir: String,
    title: String*
): Unit =
  val out = Paths.get(outDir)
  val outcome = for
    parsed <- DriverMode.parse(mode).left.map(PipelineError.NotStarted(_))
    supplied <- ClaudeParseDriver.titleArgument(title).left.map(PipelineError.NotStarted(_))
    summary <- StoryPipeline.run(
      parsed,
      Paths.get(textPath),
      Paths.get(recordingsDir),
      out,
      sys.env,
      System.currentTimeMillis(),
      title = supplied
    )
  yield summary
  outcome match
    case Left(error)    => System.err.println(s"storyBuild: ${error.message}")
    case Right(summary) => println(StoryPipeline.render(summary, out))
  ExitStatus.of(outcome) match
    case ExitStatus.Complete => ()
    case failed              => sys.exit(failed.code)
