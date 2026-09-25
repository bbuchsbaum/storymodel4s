package storymodel4s.align

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.proposition.*
import storymodel4s.proposition.CheckState.Checked
import storymodel4s.recall.*

/** Behavioural laws for the strict S2a-4 chart reduction.
  *
  * The historical-order control is intentionally in [[ContentScoringSuite]]. These witnesses use
  * only the canonical projection and change coordinates, node names, chart ids, and storage order
  * independently of chart content.
  */
class ContentBehaviorLawsSuite extends FunSuite:
  private val p = ConceptId.unsafe("p")
  private val a = ConceptId.unsafe("a")
  private val b = ConceptId.unsafe("b")

  private def spans(start: Int): SpanSet =
    SpanSet.one(SpanRef(TextSpan.unsafe(start, start + 1)))

  private def alignment(id: ConceptId, start: Int): PropositionAlignment =
    val evidence = Evidence(
      EvidenceId.unsafe(s"e-$start"),
      Some(spans(start)),
      Set.empty,
      Fingerprint.unsafe("test:content-behaviour:0"),
      StageId.unsafe("test")
    )
    val meta = ClaimMeta.unsafe(
      ClaimId.unsafe(s"c-$start"),
      EpistemicStatus.SurfaceExplicit,
      Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
      NonEmptyVector.one(evidence),
      Provenance.deterministic("test", Checksum.ofText(s"alignment-$start"))
    )
    PropositionAlignment(
      AlignmentTarget.Concepts(NonEmptySet.one(id)),
      spans(start),
      Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
      meta
    )

  private def checked(chart: PropositionChart[Unchecked]): PropositionChart[Checked] =
    ChartValidator.check(chart).fold(v => fail(s"invalid chart: $v"), identity)

  private def chart(
      ids: (ConceptId, ConceptId, ConceptId),
      patient: String,
      alignmentStart: Int
  ): PropositionEvidence =
    val (predicate, agent, target) = ids
    PropositionEvidence.hand(
      checked(
        PropositionChart.unchecked(
          Some(predicate),
          Map(
            predicate -> Concept.predicate("find"),
            agent -> Concept.entity("anna"),
            target -> Concept.entity(patient)
          ),
          Vector(
            PropositionRelation(predicate, RoleAssignment.arg(0), ConceptTarget.Node(agent)),
            PropositionRelation(predicate, RoleAssignment.arg(1), ConceptTarget.Node(target))
          ),
          polarity = Map(predicate -> Polarity.Positive),
          alignments = Vector(alignment(predicate, alignmentStart))
        )
      )
    )

  private val originalIds = (p, a, b)
  private val renamedIds =
    (
      ConceptId.unsafe("predicate-renamed"),
      ConceptId.unsafe("agent-renamed"),
      ConceptId.unsafe("patient-renamed")
    )

  private def unit(
      evidence: Option[PropositionEvidence],
      spanStart: Int = 1,
      renamed: Boolean = false
  ): RecallUnit =
    RecallUnit(
      if renamed then RecallUnitId.unsafe("renamed-unit") else RecallUnitId.unsafe("unit"),
      0,
      spans(spanStart),
      "synthetic recall",
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None,
      evidence
    )

  private def view(
      segment: SourceNodeRef,
      first: SourceNodeRef,
      second: SourceNodeRef,
      equal: PropositionEvidence,
      other: PropositionEvidence,
      shifted: Boolean = false,
      reverseStorage: Boolean = false
  ): InMemorySourceView =
    val segmentNode = NodeSummary(
      ref = segment,
      level = 1,
      parent = None,
      discoursePosition = 0,
      support = spans(if shifted then 20 else 2),
      predicate = None,
      participants = Vector.empty,
      context = ContextTag.NarratedWorld,
      polarity = PolarityTag.Positive,
      modality = ModalityTag.Asserted,
      locations = Vector.empty,
      lemmas = Set.empty
    )
    def leaf(ref: SourceNodeRef, evidence: PropositionEvidence, position: Int) =
      NodeSummary(
        ref = ref,
        level = 0,
        parent = Some(segment),
        discoursePosition = position,
        support = spans(if shifted then 30 + position else 3 + position),
        predicate = Some("find"),
        participants = Vector.empty,
        context = ContextTag.NarratedWorld,
        polarity = PolarityTag.Positive,
        modality = ModalityTag.Asserted,
        locations = Vector.empty,
        lemmas = Set("find"),
        evidence = Some(evidence)
      )
    val leaves = Vector(leaf(first, equal, 0), leaf(second, other, 1))
    InMemorySourceView(
      if reverseStorage then segmentNode +: leaves.reverse else segmentNode +: leaves,
      Map.empty,
      None,
      100
    )

  private def reduction(unit: RecallUnit, target: SourceNodeRef, source: SourceView) =
    ContentProjection
      .canonical(unit, source.node(target).getOrElse(fail(s"missing target $target")), source)
      .fold(
        r => fail(s"canonical projection refused: $r"),
        { case (u, t, _) =>
          ContentScoring.chartReduction(u, t).fold(g => fail(s"ambiguous gates: $g"), identity)
        }
      )

  private val segment = SourceNodeRef.Segment(SegmentId.unsafe("segment"))
  private val first = SourceNodeRef.Situation(SituationId.unsafe("first"))
  private val second = SourceNodeRef.Situation(SituationId.unsafe("second"))
  private val exact = chart(originalIds, "brother", alignmentStart = 4)
  private val partial = chart(originalIds, "friend", alignmentStart = 8)

  private def baseline =
    reduction(unit(Some(exact)), segment, view(segment, first, second, exact, partial))

  test("a charted partial leaf has its exact nonzero structural distance") {
    val singleLeaf = SourceNodeRef.Situation(SituationId.unsafe("partial-leaf"))
    val source = view(segment, singleLeaf, second, partial, exact)
    val score = reduction(unit(Some(exact)), singleLeaf, source)
    // `find` and arg0 match, arg1 does not: 1 - (0.45 * 1 + 0.4 * 0.5 + 0.15) = 0.2.
    assertEquals(
      score,
      Estimate.observed(1.0 - (0.45 * 1.0 + 0.4 * 0.5 + 0.15 * 1.0))
    )
  }

  test("the strict minimum reduction retains an exact charted leaf") {
    assertEquals(baseline, Estimate.observed(0.0))
  }

  test("the strict chart reduction ignores recall and chart-alignment span shifts") {
    val shiftedExact = chart(originalIds, "brother", alignmentStart = 44)
    val shiftedPartial = chart(originalIds, "friend", alignmentStart = 48)
    val shifted = reduction(
      unit(Some(shiftedExact), spanStart = 40),
      segment,
      view(segment, first, second, shiftedExact, shiftedPartial, shifted = true)
    )
    assertEquals(shifted, baseline)
  }

  test("the strict chart reduction ignores source and chart identifier renames") {
    val renamedSegment = SourceNodeRef.Segment(SegmentId.unsafe("renamed-segment"))
    val renamedFirst = SourceNodeRef.Situation(SituationId.unsafe("renamed-first"))
    val renamedSecond = SourceNodeRef.Situation(SituationId.unsafe("renamed-second"))
    val renamedExact = chart(renamedIds, "brother", alignmentStart = 4)
    val renamedPartial = chart(renamedIds, "friend", alignmentStart = 8)
    val renamed = reduction(
      unit(Some(renamedExact), renamed = true),
      renamedSegment,
      view(
        renamedSegment,
        renamedFirst,
        renamedSecond,
        renamedExact,
        renamedPartial
      )
    )
    assertEquals(renamed, baseline)
  }

  test("the strict chart reduction ignores source storage permutation") {
    val permuted = reduction(
      unit(Some(exact)),
      segment,
      view(segment, first, second, exact, partial, reverseStorage = true)
    )
    assertEquals(permuted, baseline)
  }

  test("a missing chart remains distinct from an observed empty chart") {
    val segment = SourceNodeRef.Segment(SegmentId.unsafe("empty-segment"))
    val leaf = SourceNodeRef.Situation(SituationId.unsafe("empty-leaf"))
    val otherLeaf = SourceNodeRef.Situation(SituationId.unsafe("other-empty-leaf"))
    val empty = PropositionEvidence.hand(checked(PropositionChart.empty))
    val source = view(segment, leaf, otherLeaf, empty, empty)

    assertEquals(
      reduction(unit(None), segment, source),
      Estimate.missing(MissingReason.ProviderAbstained)
    )
    // Empty graphs are present graphs. CompareCore's no-head reading is (0, 0, 0), so the
    // structural score is 0.15 and the chart distance is 0.85 rather than missing.
    assertEquals(reduction(unit(Some(empty)), segment, source), Estimate.observed(0.85))
  }
