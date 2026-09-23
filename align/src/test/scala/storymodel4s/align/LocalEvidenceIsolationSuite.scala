package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.{SegmentId, SituationId}

/** Laws for mote bd-01M2TACM78289S4TECE91GT5K2 AC3, measured before any API change: local evidence
  * content must not depend on behavioural source features, storage order, or target identifiers.
  * Each law compares evidence CONTENT (nominations and priced states per unit), not
  * [[LocalEvidenceId]], which binds the view on purpose.
  *
  * Admitted structure is held fixed and declared: containment (`parent`, hence `leavesUnder`) and
  * `level` (the granularity term) are content grain, not chronology.
  */
class LocalEvidenceIsolationSuite extends FunSuite:
  import AnnaFixture.{costModel, recall, table, view}

  private type Content = Vector[(Set[SourceNodeRef], Map[AlignState, CostBreakdown])]

  private def content(
      v: SourceView,
      distances: Map[(storymodel4s.recall.RecallUnitId, SourceNodeRef), Double] = table,
      model: Option[LocalCostModel] = None
  ): Content =
    val semantic = SemanticDistance.fromTable(distances)
    val candidates = CandidateGenerator(semantic, perLevel = 2).generate(recall.ordered, v)
    // Pricing reads the same distance table as nomination, so a renamed table prices renamed refs.
    val pricing = model.getOrElse(costModel.copy(semantic = semantic))
    val e = LocalEvidence
      .compute(recall, v, candidates, pricing, gate = true)
      .fold(err => fail(err.message), identity)
    e.nominated.map(_.toSet).zip(e.breakdowns)

  private def rebuilt(
      nodes: Vector[NodeSummary],
      edges: Map[RelationLayer, Vector[(SourceNodeRef, SourceNodeRef, Double)]],
      worldOrder: Option[Map[SourceNodeRef, Int]]
  ): InMemorySourceView =
    InMemorySourceView(nodes, edges, worldOrder, view.scoringLength)

  /** Every behavioural field perturbed at once; containment and level untouched. */
  private lazy val behaviourallyPerturbed: InMemorySourceView =
    val nodes = view.nodes
    val maxPos = nodes.map(_.discoursePosition).max
    val scoring = nodes.map(_.scoringPosition).reverse
    val supports = nodes.map(_.support).reverse
    val perturbed = nodes.zipWithIndex.map { (n, i) =>
      n.copy(
        discoursePosition = maxPos - n.discoursePosition,
        scoringPosition = scoring(i),
        support = supports(i)
      )
    }
    val edges = view.edges.map { (layer, es) =>
      layer -> (if layer == RelationLayer.Hierarchy then es
                else es.map((a, b, w) => (b, a, w * 0.5 + 0.25)))
    }
    val worldOrder = view.worldOrder.map { m =>
      val top = m.values.max
      m.map((r, o) => r -> (top - o))
    }
    rebuilt(perturbed, edges, worldOrder)

  test("capacity to fail: the perturbation really changes behavioural fields") {
    assertNotEquals(
      behaviourallyPerturbed.nodes.map(_.discoursePosition),
      view.nodes.map(_.discoursePosition)
    )
    assertNotEquals(behaviourallyPerturbed.worldOrder, view.worldOrder)
    assertNotEquals(behaviourallyPerturbed.edges, view.edges)
    assertEquals(behaviourallyPerturbed.nodes.map(_.parent), view.nodes.map(_.parent))
  }

  test(
    "(a) evidence content ignores discourse/scoring position, support, world order and adjacency"
  ) {
    assertEquals(content(behaviourallyPerturbed), content(view))
  }

  test("(a) capacity to fail: a scorer that reads discourse position is caught") {
    val leaky = new LocalCostModel:
      def cost(u: storymodel4s.recall.RecallUnit, n: NodeSummary, m: FidelityMode, v: SourceView) =
        val base = costModel.cost(u, n, m, v)
        CostBreakdown
          .checked(
            base.terms,
            base.mode,
            base.exclusion,
            base.total + 1e-3 * n.discoursePosition,
            base.missingTerms,
            base.sourceChartCoverage,
            base.reductions,
            base.support,
            base.imputedTerms
          )
          .fold(e => fail(e.toString), identity)
      def externalFloor = costModel.externalFloor
      def externalCost(u: storymodel4s.recall.RecallUnit, s: ExternalState) =
        costModel.externalCost(u, s)
    assertNotEquals(
      content(behaviourallyPerturbed, model = Some(leaky)),
      content(view, model = Some(leaky))
    )
  }

  test("(b) evidence content ignores source storage order") {
    val reordered =
      rebuilt(view.nodes.reverse, view.edges.map((l, es) => l -> es.reverse), view.worldOrder)
    assertEquals(content(reordered), content(view))
  }

  // KNOWN DEFECT, recorded as an expected failure (munit `.fail`): CandidateGenerator.forUnit cuts
  // each level with `.sortBy((d, r.key)).take(perLevel)`, so when distances tie at the cut, WHICH
  // targets are nominated depends on their ids. Measured 2026-09-23: with a tie-complete cut this
  // law passes (5/5). The fix is a declared candidate tie policy (mote bd-01M2TACM78289S4TECE91GT5K2
  // AC3; reference AC4 "never ID-truncated"), keeping the historical cut by name for the frozen
  // preset. When it lands, this test starts passing and `.fail` must be removed.
  test("(c) a bijective rename of target ids commutes with evidence".fail) {
    // Rename so that key order REVERSES within each kind: any id-dependent selection must change.
    val refs = view.nodes.map(_.ref).sortBy(_.key)
    val rename: Map[SourceNodeRef, SourceNodeRef] = refs.zipWithIndex.map { (r, i) =>
      val tag = f"n${99 - i}%02d"
      r -> (r match
        case SourceNodeRef.Situation(_) => SourceNodeRef.Situation(SituationId.unsafe(tag))
        case SourceNodeRef.Segment(_)   => SourceNodeRef.Segment(SegmentId.unsafe(tag)))
    }.toMap
    val back = rename.map(_.swap)
    val renamedView = rebuilt(
      view.nodes.map(n => n.copy(ref = rename(n.ref), parent = n.parent.map(rename))),
      view.edges.map((l, es) => l -> es.map((a, b, w) => (rename(a), rename(b), w))),
      view.worldOrder.map(_.map((r, o) => rename(r) -> o))
    )
    val renamedTable = table.map { case ((u, r), d) => (u, rename(r)) -> d }
    def unrename(s: AlignState): AlignState = s match
      case AlignState.Source(r)       => AlignState.Source(back(r))
      case AlignState.Distorted(r, f) => AlignState.Distorted(back(r), f)
      case other                      => other
    val mappedBack = content(renamedView, renamedTable).map { (noms, costs) =>
      (noms.map(back), costs.map((s, c) => unrename(s) -> c))
    }
    assertEquals(mappedBack, content(view))
  }

  test("(c) under the strict TieComplete policy, a bijective rename commutes with evidence") {
    val refs = view.nodes.map(_.ref).sortBy(_.key)
    val rename: Map[SourceNodeRef, SourceNodeRef] = refs.zipWithIndex.map { (r, i) =>
      val tag = f"n${99 - i}%02d"
      r -> (r match
        case SourceNodeRef.Situation(_) => SourceNodeRef.Situation(SituationId.unsafe(tag))
        case SourceNodeRef.Segment(_)   => SourceNodeRef.Segment(SegmentId.unsafe(tag)))
    }.toMap
    val back = rename.map(_.swap)
    val renamedView = rebuilt(
      view.nodes.map(n => n.copy(ref = rename(n.ref), parent = n.parent.map(rename))),
      view.edges.map((l, es) => l -> es.map((a, b, w) => (rename(a), rename(b), w))),
      view.worldOrder.map(_.map((r, o) => rename(r) -> o))
    )
    val renamedTable = table.map { case ((u, r), d) => (u, rename(r)) -> d }
    val config = StrictCandidateConfig
      .of(2, TieBudgetRequest.Unbounded, lexicalOverlap = true, space = None)
      .fold(e => fail(e.message), identity)
    def strictContent(
        v: SourceView,
        t: Map[(storymodel4s.recall.RecallUnitId, SourceNodeRef), Double]
    ) =
      val sem = SemanticDistance.fromTable(t)
      val strict = StrictCandidateGenerator(sem, config)
        .generate(recall.ordered, v)
        .fold(e => fail(e.message), identity)
      val e = LocalEvidence
        .compute(recall, v, strict, costModel.copy(semantic = sem))
        .fold(err => fail(err.message), identity)
      e.nominated.map(_.toSet).zip(e.breakdowns)
    def unrename(s: AlignState): AlignState = s match
      case AlignState.Source(r)       => AlignState.Source(back(r))
      case AlignState.Distorted(r, f) => AlignState.Distorted(back(r), f)
      case other                      => other
    val mappedBack = strictContent(renamedView, renamedTable).map { (noms, costs) =>
      (noms.map(back), costs.map((s, c) => unrename(s) -> c))
    }
    assertEquals(mappedBack, strictContent(view, table))
  }
