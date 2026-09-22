package storymodel4s.codec

import cats.data.NonEmptyVector
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Two authored events and a real local HSMM run; no external provider or corpus. */
object MappingCodecFixture:
  val text = StorySource.fromText("Anna arrived. Bob left.").toOption.get
  private val atlas = SurfaceAnalyzer.analyze(text)
  val refs = Vector("arrive", "leave").map(id => SourceNodeRef.Situation(SituationId.unsafe(id)))
  val names = Vector("Anna", "Bob")
  val view = InMemorySourceView(
    atlas.sentences.zipWithIndex.map { (sentence, i) =>
      NodeSummary(
        refs(i),
        0,
        None,
        i,
        SpanSet.one(SpanRef(Some(sentence.id), sentence.span)),
        Some(Vector("arrive", "leave")(i)),
        Vector(ParticipantSummary(SketchRole.Agent, names(i), Set(names(i).toLowerCase))),
        ContextTag.NarratedWorld,
        PolarityTag.Positive,
        ModalityTag.Asserted,
        Vector.empty,
        Set(names(i).toLowerCase),
        propositional = PropositionalScope.Declared
      )
    },
    Map.empty,
    None,
    text.canonicalText.length
  )
  val recall: RecallGraph[Checked] = RecallGraph
    .validated(
      text,
      atlas,
      atlas.sentences.zipWithIndex.map { (sentence, i) =>
        RecallUnit(
          RecallUnitId.unsafe(s"u$i"),
          i,
          SpanSet.one(SpanRef(Some(sentence.id), sentence.span)),
          atlas.text(sentence),
          DiscourseFunction.EpisodicAssertion,
          ExpressedUncertainty.Unmarked,
          PropositionSketch(
            Some(Vector("arrive", "leave")(i)),
            Vector(SketchParticipant(SketchRole.Agent, None, names(i))),
            PolarityTag.Positive,
            ModalityTag.Asserted,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Set(names(i).toLowerCase)
          ),
          None
        )
      },
      RecallRelations.empty
    )
    .toOption
    .get
  def inventoryFor(recalled: RecallGraph[Checked]): RecallInventory = RecallInventory
    .of(
      recalled,
      "\\S+".r
        .findAllMatchIn(recalled.transcript.canonicalText)
        .map(m => TextSpan.unsafe(m.start, m.end))
        .toVector,
      WordIdPolicy.inputArtifact(Checksum.ofText("synthetic-parser-input"))
    )
    .toOption
    .get
  val inventory = inventoryFor(recall)
  def sourceFor(v: SourceView): SourceRepresentation = SourceRepresentation
    .of(
      v,
      NonEmptyVector.one(BundleEntry.text(text.canonicalChecksum)),
      None,
      v.nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
    )
    .toOption
    .get
  val source = sourceFor(view)
  lazy val result = GraphHsmm
    .infer(
      recall,
      view,
      Candidates.of(recall.ordered.zip(refs).map((u, r) => u.id -> Vector(r)).toMap),
      DefaultLocalCostModel(semantic = SemanticDistance.lexicalJaccard)
    )
    .toOption
    .get
  def record(
      r: HsmmResult = result,
      v: SourceView = view,
      source: SourceRepresentation = source,
      decode: HistoricalDecode = HistoricalDecode.ArgmaxOnly
  ): MappingResult =
    HistoricalMapping.of(r, recall, v, inventory, source, decode).toOption.get
  def context(
      r: HsmmResult = result,
      v: SourceView = view,
      source: SourceRepresentation = source
  ): ExpectedMappingContext =
    ExpectedMappingContext(inventory, source, Some(DerivationContext(r, recall, v)))
  lazy val variant: HsmmResult =
    val unit = recall.ordered.head.id
    val state = result.costs(unit).keys.toVector.sorted.head
    val c = result.costs(unit)(state)
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
    HsmmResult
      .validated(
        recall,
        view,
        result.candidateAnchors,
        result.posterior,
        result.flow,
        result.viterbi,
        result.logLikelihood,
        result.costs.updated(unit, result.costs(unit).updated(state, changed)),
        result.refinementPasses
      )
      .toOption
      .get
