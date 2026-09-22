package storymodel4s.align

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

object MappingMeasureFixture:
  val recall: RecallGraph[Checked] = AnnaFixture.recall
  val view: InMemorySourceView = AnnaFixture.view
  def inventory(r: RecallGraph[Checked]): RecallInventory = RecallInventory
    .of(
      r,
      "\\S+".r
        .findAllMatchIn(r.transcript.canonicalText)
        .map(m => TextSpan.unsafe(m.start, m.end))
        .toVector,
      WordIdPolicy.inputArtifact(Checksum.ofText("synthetic-measure-parsing/v1"))
    )
    .toOption
    .get
  def source(v: SourceView): SourceRepresentation = SourceRepresentation
    .of(
      v,
      NonEmptyVector.one(BundleEntry.text(AnnaFixture.source.canonicalChecksum)),
      None,
      v.nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
    )
    .toOption
    .get
  lazy val result: HsmmResult =
    GraphHsmm.infer(recall, view, AnnaFixture.candidates, AnnaFixture.costModel).toOption.get
  def bind(
      r: HsmmResult = result,
      recalled: RecallGraph[Checked] = recall,
      v: SourceView = view
  ): DerivationBinding =
    DerivationBinding.of(r, recalled, inventory(recalled), v, source(v)).toOption.get
  val ledger: StageLedger = StageLedger
    .of(
      Stage.values.toVector.map(s =>
        StageEntry
          .of(s, StageProvenance.unknown(UnknownProvenanceReason.HistoricalArtifact, None))
          .toOption
          .get
      )
    )
    .toOption
    .get
  def stage(value: Stage): StageEntryId = ledger.entries.find(_.stage == value).get.id
  val stages: UnitStageRefs = UnitStageRefs
    .of(ledger, stage(Stage.Inference), stage(Stage.Candidates), stage(Stage.Decision))
    .toOption
    .get
  def candidate(unit: RecallUnitId, r: HsmmResult = result): CandidateSetId = CandidateSetId.of(
    stages.candidates,
    unit,
    r.posterior.row(unit).get.mass.keySet.map(Destination.of)
  )
  def link(
      unit: RecallUnitId,
      state: AlignState,
      r: HsmmResult = result,
      binding: DerivationBinding = bind(),
      recalled: RecallGraph[Checked] = recall,
      v: SourceView = view
  ): Either[MappingRefusal, MappingLink] =
    MappingLink.fromResult(
      r,
      binding,
      recalled,
      v,
      source(v),
      unit,
      state,
      stages,
      candidate(unit, r)
    )
  def revalidate(
      costs: Map[RecallUnitId, Map[AlignState, CostBreakdown]],
      posterior: AlignmentMatrix = result.posterior,
      flow: TransitionFlow = result.flow
  ): HsmmResult = HsmmResult
    .validated(
      recall,
      view,
      result.candidateAnchors,
      posterior,
      flow,
      result.viterbi,
      result.logLikelihood,
      costs,
      result.refinementPasses
    )
    .toOption
    .get
  lazy val costVariant: HsmmResult =
    val u = recall.ordered.head.id
    val state = result.costs(u).keys.toVector.sorted.head
    val c = result.costs(u)(state)
    val changed = AlignWire
      .costBreakdown(
        c.terms,
        c.mode,
        c.exclusion,
        c.total + 0.125,
        c.missingTerms,
        c.sourceChartCoverage,
        c.reductions,
        c.support,
        c.imputedTerms
      )
      .toOption
      .get
    revalidate(result.costs.updated(u, result.costs(u).updated(state, changed)))
  lazy val posteriorVariant: HsmmResult =
    val rows = result.posterior.rows.map(row =>
      AlignmentRow.of(row.unit, row.mass.keys.map(_ -> (1.0 / row.mass.size)).toMap).toOption.get
    )
    val matrix = AlignmentMatrix.of(rows).toOption.get
    val flow = TransitionFlow(
      rows
        .sliding(2)
        .collect { case Vector(a, b) =>
          FlowStep(
            a.unit,
            b.unit,
            (for (sa, pa) <- a.mass.toVector; (sb, pb) <- b.mass.toVector
            yield (sa -> sb) -> (pa * pb)).toMap
          )
        }
        .toVector
    )
    revalidate(result.costs, matrix, flow)
