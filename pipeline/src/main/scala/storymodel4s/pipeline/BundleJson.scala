package storymodel4s.pipeline

import io.circe.Json
import io.circe.syntax.*
import storymodel4s.codec.CoreCodecs.given
import storymodel4s.codec.OutputAcquireCodecs.given
import storymodel4s.core.*
import storymodel4s.document.{
  ChartProposals,
  CoordinatedBranch,
  CoverageCounts,
  DerivationGap,
  FillerCounts,
  NarrativeCompilation,
  SentenceCoverage,
  SummaryCoverage
}
import storymodel4s.provider.agent.ParseOutcome
import storymodel4s.story.{Severity, Violation}

/** JSON renderings of the compilation report and the receipts file.
  *
  * Why here and not in `codec`: these documents are this orchestrator's own account of a run
  * (coverage rows, gaps, violations, ledger), not interchange artifacts any other module decodes. A
  * `codec → document` edge for a report nobody reads back would widen the portable graph for no
  * consumer (ADR 0009). The canonical `ProviderCall`, `BuildReceipt`, and `ExtendedBuildReceipt`
  * encoders are reused from `codec`, so every receipt keeps its interchange shape.
  */
private[pipeline] object BundleJson:
  val ReportSchemaVersion: String = "storymodel4s.pipeline.compilation-report/v1"
  val ReceiptsSchemaVersion: String = "storymodel4s.pipeline.receipts/v1"

  private def counts(c: CoverageCounts): Json = Json.obj(
    "proposed" -> c.proposed.asJson,
    "coordinated" -> c.coordinated.asJson,
    "abstained" -> c.abstained.asJson,
    "emptyCharts" -> c.emptyCharts.asJson,
    "noCharts" -> c.noCharts.asJson,
    "sentences" -> c.sentences.asJson
  )

  /** Where a root's entity-kind fillers went. `fillers` keeps its old name and meaning — the
    * fillers that became participants — and the three new counters say where the rest went, so the
    * four sum to every filler the provider saw.
    */
  private def fillerFields(counts: FillerCounts): Vector[(String, Json)] = Vector(
    "fillers" -> counts.referents.asJson,
    "circumstances" -> counts.circumstances.asJson,
    "nonReferential" -> counts.nonReferential.asJson,
    "unlicensed" -> counts.unlicensed.asJson,
    "seen" -> counts.seen.asJson
  )

  /** One branch of a coordinated row. Every branch appears, admitted or not, so a reader can see
    * how much of a coordinating sentence became situations and why the rest did not.
    */
  private def branchRow(branch: CoordinatedBranch): Json = branch match
    case CoordinatedBranch.Admitted(root, role, counts) =>
      Json.obj(
        Vector(
          "kind" -> "admitted".asJson,
          "root" -> root.key.asJson,
          "role" -> role.render.asJson
        ) ++ fillerFields(counts)*
      )
    case CoordinatedBranch.Abstained(root, role, reason) =>
      Json.obj(
        "kind" -> "abstained".asJson,
        "root" -> root.key.asJson,
        "role" -> role.render.asJson,
        "reason" -> reason.render.asJson
      )

  private def coverageRow(ordinals: Map[SurfaceUnitId, Int])(row: SentenceCoverage): Json =
    val base = Vector(
      "sentence" -> row.sentence.value.asJson,
      "ordinal" -> ordinals.get(row.sentence).asJson
    )
    val rest = row match
      case SentenceCoverage.Proposed(root, counts) =>
        Vector("kind" -> "proposed".asJson, "root" -> root.key.asJson) ++ fillerFields(counts)
      case SentenceCoverage.Coordinated(coordinator, branches) =>
        Vector(
          "kind" -> "coordinated".asJson,
          "coordinator" -> coordinator.key.asJson,
          "admitted" -> branches.count(_.isInstanceOf[CoordinatedBranch.Admitted]).asJson,
          "branches" -> branches.map(branchRow).asJson
        )
      case SentenceCoverage.Abstained(anchor, reason) =>
        Vector(
          "kind" -> "abstained".asJson,
          "anchor" -> anchor.key.asJson,
          "reason" -> reason.render.asJson
        )
      case SentenceCoverage.EmptyChart(_) => Vector("kind" -> "empty-chart".asJson)
      case SentenceCoverage.NoChart(_)    => Vector("kind" -> "no-chart".asJson)
    Json.obj((base ++ rest)*)

  /** The provenance is written alongside the kind: a proposed summary that does not say what
    * entitled it is the shape this row exists to make impossible to publish quietly.
    */
  private def summaryRow(coverage: SummaryCoverage): Json = coverage match
    case SummaryCoverage.Proposed(_, provenance) =>
      Json.obj("kind" -> "proposed".asJson, "titleProvenance" -> provenance.render.asJson)
    case SummaryCoverage.NoTitle            => Json.obj("kind" -> "no-title".asJson)
    case SummaryCoverage.TitleUnestablished =>
      Json.obj("kind" -> "title-provenance-unrecorded".asJson)

  private def gapRow(gap: DerivationGap): Json = Json.obj(
    "stage" -> gap.stage.value.asJson,
    "family" -> gap.family.toString.asJson,
    "target" -> gap.target.render.asJson,
    "reason" -> gap.reason.render.asJson,
    "upstreamClaims" -> gap.upstreamClaims.size.asJson,
    "evidence" -> gap.evidence.size.asJson
  )

  private def severity(s: Severity): String = s match
    case Severity.Error   => "error"
    case Severity.Warning => "warning"

  private def violationRow(v: Violation): Json = Json.obj(
    "law" -> v.law.asJson,
    "severity" -> severity(v.severity).asJson,
    "path" -> v.path.asJson,
    "reason" -> v.reason.asJson
  )

  private def parserBlock(parsed: ParseOutcome): Json =
    val s = parsed.summary
    Json.obj(
      "stage" -> parsed.parserStage._1.value.asJson,
      "checksum" -> parsed.parserStage._2.hex.asJson,
      "proposed" -> s.proposed.asJson,
      "failed" -> s.failed.asJson,
      "abstained" -> s.abstained.asJson,
      "transportFailures" -> s.transportFailures.asJson,
      "served" -> Json.obj(
        "replayedAuthored" -> s.replayedAuthored.asJson,
        "replayedCaptured" -> s.replayedCaptured.asJson,
        "capturedLive" -> s.capturedLive.asJson,
        "unrecorded" -> s.unrecorded.asJson,
        "foreign" -> s.foreign.asJson,
        "corrupt" -> s.corrupt.asJson
      ),
      "liveCalls" -> s.liveCalls.asJson
    )

  /** The run's account: what the parser served, what the provider covered, what the compiler left
    * as gaps, and what the validator said. Carries no source prose: rows name unit ids, chart node
    * keys, law names, and renders.
    */
  def report(
      parsed: ParseOutcome,
      proposals: ChartProposals,
      compilation: NarrativeCompilation,
      summary: BuildSummary
  ): Json =
    val ordinals = parsed.atlas.sentences.map(unit => unit.id -> unit.ordinal).toMap
    val report = compilation.validation.report
    val draft = compilation.draft
    Json.obj(
      "schemaVersion" -> ReportSchemaVersion.asJson,
      "mode" -> summary.mode.render.asJson,
      "source" -> Json.obj(
        "storyId" -> summary.storyId.value.asJson,
        "checksum" -> summary.sourceChecksum.hex.asJson,
        "sentences" -> summary.sentences.asJson
      ),
      "parser" -> parserBlock(parsed),
      "coverage" -> Json.obj(
        "counts" -> counts(proposals.counts),
        "summary" -> summaryRow(proposals.summaryCoverage),
        "rows" -> proposals.coverage.map(coverageRow(ordinals)).asJson
      ),
      "gaps" -> compilation.derivation.gaps.map(gapRow).asJson,
      "validation" -> Json.obj(
        "validated" -> summary.validated.asJson,
        "errors" -> report.errors.size.asJson,
        "warnings" -> report.warnings.size.asJson,
        "violations" -> report.violations.map(violationRow).asJson
      ),
      "compilation" -> Json.obj(
        "fingerprint" -> summary.fingerprint.hex.asJson,
        "candidateSet" -> summary.candidateSet.hex.asJson,
        "attempts" -> compilation.derivation.attempts.size.asJson,
        "emittedClaims" -> compilation.derivation.emittedClaims.size.asJson,
        "gaps" -> summary.gaps.asJson,
        "partial" -> compilation.isPartial.asJson
      ),
      "model" -> Json.obj(
        "file" -> StoryPipeline.ModelFile.asJson,
        "encodingDigest" -> Json.obj(
          "checksum" -> summary.encodingDigest.hex.asJson,
          "timestampBearing" -> true.asJson,
          "bears" -> "receipt.createdAtEpochMillis".asJson
        ),
        "schemaVersion" -> draft.schemaVersion.asJson,
        "entities" -> draft.graph.entities.size.asJson,
        "situations" -> draft.graph.situations.size.asJson,
        "segments" -> draft.graph.segments.size.asJson,
        "contexts" -> draft.graph.contexts.size.asJson,
        "claims" -> draft.claims.size.asJson
      )
    )

  private def sentenceRows(parsed: ParseOutcome): Json =
    val rows = parsed.batch.inputs
      .zip(parsed.result.attempts)
      .zip(parsed.recordingKeys.zip(parsed.services))
      .map { case ((input, attempt), (key, service)) =>
        Json.obj(
          "id" -> input.id.value.asJson,
          "sentenceId" -> input.sentenceId.value.asJson,
          "recordingKey" -> key.checksum.hex.asJson,
          "served" -> service.render.asJson,
          "status" -> (if attempt.isCovered then "proposed" else "failed").asJson,
          "failure" -> attempt.result.swap.toOption.map(_.render).asJson,
          "proposalDigest" -> attempt.result.toOption.map(_.canonicalDigest.hex).asJson,
          "requestChecksum" -> attempt.receipt.requestChecksum.hex.asJson,
          "receiptDigest" -> attempt.receipt.digest.hex.asJson
        )
      }
    rows.asJson

  /** Every receipt the bundle rests on: the parser's extended receipt, the per-sentence ledger,
    * every provider call the compilation's provenance retained (the parser's and the proposal
    * provider's), and the build receipt with both stages.
    */
  def receipts(
      parsed: ParseOutcome,
      compilation: NarrativeCompilation,
      summary: BuildSummary
  ): Json =
    Json.obj(
      "schemaVersion" -> ReceiptsSchemaVersion.asJson,
      "mode" -> summary.mode.render.asJson,
      "parser" -> parsed.receipt.asJson,
      "sentences" -> sentenceRows(parsed),
      "calls" -> compilation.provenance.calls.asJson,
      "provenance" -> Json.obj(
        "softwareVersion" -> compilation.provenance.softwareVersion.asJson,
        "configHash" -> compilation.provenance.configHash.hex.asJson
      ),
      "buildReceipt" -> compilation.receipt.asJson,
      "buildReceiptContentChecksum" -> summary.receiptChecksum.hex.asJson
    )
