package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.proposition.*
import storymodel4s.proposition.CheckState.Checked
import storymodel4s.recall.*

/** W2 (ADR 0001 rev 3 §D4b): proposition evidence is an optional channel; `d_chart` and `d_wl` are
  * separate terms that are `Missing` without it; chart gates take precedence over sketch
  * heuristics; segments report coverage and never get a fabricated chart.
  */
class EvidenceSuite extends FunSuite:
  import AnnaFixture.*

  // ---- charts -------------------------------------------------------------------------------

  private def chart(
      agent: String,
      patient: String,
      negated: Boolean = false
  ): PropositionChart[Checked] =
    val p = ConceptId.unsafe("p")
    val a = ConceptId.unsafe("a")
    val b = ConceptId.unsafe("b")
    val unchecked = PropositionChart.unchecked(
      Some(p),
      Map(p -> Concept.predicate("find"), a -> Concept.entity(agent), b -> Concept.entity(patient)),
      Vector(
        PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a)),
        PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(b))
      ),
      polarity = Map(p -> (if negated then Polarity.Negative else Polarity.Positive))
    )
    ChartValidator.check(unchecked).fold(v => fail(s"invalid chart: $v"), identity)

  private val straight = PropositionEvidence.hand(chart("anna", "brother"))
  private val reversed = PropositionEvidence.hand(chart("brother", "anna"))
  private val negated = PropositionEvidence.hand(chart("anna", "brother", negated = true))

  private val e5Node: NodeSummary = view.node(e5).get

  /** The Anna view with evidence attached to the given leaves only. */
  private def viewWith(evidence: Map[SourceNodeRef, PropositionEvidence]): InMemorySourceView =
    InMemorySourceView(
      view.nodes.map(n => n.copy(evidence = evidence.get(n.ref))),
      view.edges,
      view.worldOrder,
      view.textLength
    )

  private val u2Chart: RecallUnit = u2.copy(evidence = Some(straight))

  // ---- Missing behaviour --------------------------------------------------------------------

  test("d_chart is Missing(ProviderAbstained) when the unit has no chart") {
    val v = viewWith(Map(e5 -> straight))
    assertEquals(
      ChartDistance(u2, v.node(e5).get, v),
      Estimate.missing(MissingReason.ProviderAbstained)
    )
  }

  test("d_chart is Missing(Excluded) when no member under the node has a chart") {
    assertEquals(ChartDistance(u2Chart, e5Node, view), Estimate.missing(MissingReason.Excluded))
    assertEquals(
      ChartDistance(u2Chart, view.node(sc2).get, view),
      Estimate.missing(MissingReason.Excluded)
    )
  }

  test("d_wl is Missing without a provider, and Missing without evidence even with one") {
    val v = viewWith(Map(e5 -> straight))
    val provider = StructuralDistance.of((_, _) => 0.2)
    assertEquals(
      ChartDistance.structural(StructuralDistance.missing, u2Chart, v.node(e5).get, v),
      Estimate.missing(MissingReason.ProviderAbstained)
    )
    assertEquals(
      ChartDistance.structural(provider, u2, v.node(e5).get, v),
      Estimate.missing(MissingReason.ProviderAbstained)
    )
    assertEquals(
      ChartDistance.structural(provider, u2Chart, v.node(e5).get, v),
      Estimate.observed(0.2)
    )
  }

  test("identical charts give d_chart 0; reversed charts are excluded before reduction") {
    val v = viewWith(Map(e5 -> straight))
    assertEquals(ChartDistance(u2Chart, v.node(e5).get, v), Estimate.observed(0.0))
    val r = viewWith(Map(e5 -> reversed))
    val reduction = ChartDistance.reduction(u2Chart, r.node(e5).get, r)
    assertEquals(reduction.estimate, Estimate.missing(MissingReason.Excluded))
    assertEquals(
      reduction.receipt.excludedMembers.map(_.contradictions),
      Vector(Set(Contradiction.RoleReversal))
    )
    assert(ChartDistance.between(straight, reversed) > 0.0)
  }

  // ---- chart gates take precedence over sketch heuristics -----------------------------------

  test("sketches agree but charts reverse roles: the gate refuses Faithful with RoleReversal") {
    // sketch-level: "she finds somebody" vs source "anna finds brother" — no sketch contradiction
    assertEquals(ContradictionDetector.detect(u2.proposition, e5Node), Vector.empty)
    val r = viewWith(Map(e5 -> reversed))
    val adm = ModeGate.assess(u2Chart, r.node(e5).get, r)
    assert(!adm.faithful)
    assert(adm.facets.contains(Facet.RoleReversal), adm)
    // and the distorted mode is admissible: the event is still recalled
    assertEquals(adm.modes, Vector(FidelityMode.Distorted(adm.distortion.get)))
  }

  test("straight charts on both sides keep Faithful admissible") {
    val v = viewWith(Map(e5 -> straight))
    val adm = ModeGate.assess(u2Chart, v.node(e5).get, v)
    assert(adm.faithful, adm)
  }

  test("chart polarity conflict yields the Polarity facet") {
    val v = viewWith(Map(e5 -> negated))
    val adm = ModeGate.assess(u2Chart, v.node(e5).get, v)
    assert(adm.facets.contains(Facet.Polarity), adm)
  }

  // ---- cost composition ---------------------------------------------------------------------

  test("without evidence the optional terms are absent, recorded, and inert") {
    val base = costModel.cost(u2, e5Node, FidelityMode.Faithful, view)
    assertEquals(base.missingTerms, Set(CostTerm.Chart, CostTerm.Structural))
    assert(!base.has(CostTerm.Chart) && !base.has(CostTerm.Structural))
    val withProvider =
      costModel
        .copy(structural = StructuralDistance.of((_, _) => 0.0))
        .cost(u2, e5Node, FidelityMode.Faithful, view)
    assertEquals(withProvider.total, base.total)
    assertEquals(withProvider.missingTerms, base.missingTerms)
  }

  test("with evidence the chart term is present and lowers the cost of the matching node") {
    val v = viewWith(Map(e5 -> straight))
    val b = costModel.cost(u2Chart, v.node(e5).get, FidelityMode.Faithful, v)
    assert(b.has(CostTerm.Chart))
    assertEquals(b.missingTerms, Set(CostTerm.Structural))
    assertEquals(b.term(CostTerm.Chart), 0.0)
    val provided = costModel.copy(structural = StructuralDistance.of((_, _) => 0.25))
    val c = provided.cost(u2Chart, v.node(e5).get, FidelityMode.Faithful, v)
    assert(c.has(CostTerm.Structural))
    assertEquals(c.missingTerms, Set.empty[CostTerm])
    assertEquals(c.term(CostTerm.Structural), 0.25)
    assert(c.total > b.total)
  }

  // ---- segments: coverage and reducer -------------------------------------------------------

  test("segment coverage counts charted members; leaf coverage is 0/1 or 1/1") {
    val v = viewWith(Map(e5 -> straight))
    val members = v.leavesUnder(sc2).size
    assert(members >= 2, members)
    assertEquals(v.structuralCoverage(sc2), StructuralCoverage(1, 1, members))
    assertEquals(v.structuralCoverage(e5), StructuralCoverage(0, 1, 1))
    assertEquals(v.structuralCoverage(e4), StructuralCoverage(0, 0, 1))
    assertEquals(v.segmentEvidence(sc2).members, Vector(straight))
    assert(v.node(sc2).get.evidence.isEmpty, "a segment never carries a chart of its own")
  }

  test("segment d_chart is the minimum observed compatible member without coverage imputation") {
    val v = viewWith(Map(e5 -> straight))
    assertEquals(ChartDistance(u2Chart, v.node(sc2).get, v), Estimate.observed(0.0))
    val full = viewWith(v.leavesUnder(sc2).map(_ -> straight).toMap)
    assertEquals(ChartDistance(u2Chart, full.node(sc2).get, full), Estimate.observed(0.0))
    val breakdown = costModel.cost(u2Chart, v.node(sc2).get, FidelityMode.Faithful, v)
    assertEquals(
      breakdown.sourceChartCoverage,
      Some(StructuralCoverage(1, 1, v.leavesUnder(sc2).size))
    )
  }

  // ---- end to end ---------------------------------------------------------------------------

  test(
    "end to end: reversed source chart anchors u2 on e5 as Distorted(RoleReversal), never Faithful"
  ) {
    val r = viewWith(Map(e5 -> reversed))
    val recallR = recall.copy(units = recall.units.map(u => if u.id == u2.id then u2Chart else u))
    val cands = CandidateGenerator(semantic, perLevel = 2).generate(recallR.ordered, r)
    val res = GraphHsmm.infer(recallR, r, cands, costModel).toOption.get
    val row = res.posterior.row(u2.id).get
    assertEquals(row.faithfulMassOn(e5), 0.0)
    assert(row.distortedMassOn(e5) > 0.5, row.mass)
    assertEquals(
      row.mapMode,
      Some(FidelityMode.Distorted(cats.data.NonEmptySet.one(Facet.RoleReversal)))
    )
  }

  test("end to end: straight source chart keeps u2 Faithful on e5 (no regression against M0)") {
    val v = viewWith(Map(e5 -> straight))
    val recallV = recall.copy(units = recall.units.map(u => if u.id == u2.id then u2Chart else u))
    val cands = CandidateGenerator(semantic, perLevel = 2).generate(recallV.ordered, v)
    val res = GraphHsmm.infer(recallV, v, cands, costModel).toOption.get
    val row = res.posterior.row(u2.id).get
    assert(row.faithfulMassOn(e5) > 0.5, row.mass)
  }
