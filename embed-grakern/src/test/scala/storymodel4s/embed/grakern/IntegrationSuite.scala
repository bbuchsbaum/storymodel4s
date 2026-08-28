package storymodel4s.embed.grakern

import cats.data.NonEmptySet
import munit.FunSuite

import storymodel4s.align.*
import storymodel4s.features.Estimate
import storymodel4s.proposition.*
import storymodel4s.recall.RecallUnit

/** The structural channel inside alignment: `d_wl` lowers the cost of the true target relative to a
  * role-reversed foil, while the fidelity mode still comes from the ModeGate — structure grades, it
  * never adjudicates (ADR 0001 §D5, L1/L3).
  */
class IntegrationSuite extends FunSuite:
  import AnnaFixture.*
  import Charts.*

  private val straight = transitive("find", "anna", "brother")
  private val reversed = transitive("find", "brother", "anna")

  private def viewWith(evidence: Map[SourceNodeRef, PropositionEvidence]): InMemorySourceView =
    InMemorySourceView(
      view.nodes.map(n => n.copy(evidence = evidence.get(n.ref))),
      view.edges,
      view.worldOrder,
      view.textLength
    )

  private val unitChart: RecallUnit = u2.copy(evidence = Some(PropositionEvidence.hand(straight)))

  test("d_wl grades: straight source chart is closer than the reversed one under grakern") {
    val provider = GrakernStructuralDistance
      .prepare(Vector(straight, reversed))
      .fold(e => fail(e.message), identity)
    val vStraight = viewWith(Map(e5 -> PropositionEvidence.hand(straight)))
    val vReversed = viewWith(Map(e5 -> PropositionEvidence.hand(reversed)))
    val model = costModel.copy(structural = provider)
    val cStraight = model.cost(unitChart, vStraight.node(e5).get, FidelityMode.Faithful, vStraight)
    val cReversed = model.cost(
      unitChart,
      vReversed.node(e5).get,
      FidelityMode.Distorted(NonEmptySet.one(Facet.RoleReversal)),
      vReversed
    )
    assert(!cStraight.missingTerms.contains(CostTerm.Structural), cStraight.missingTerms)
    assert(cStraight.total < cReversed.total, s"${cStraight.total} vs ${cReversed.total}")
    assert(provider.receipts.nonEmpty)
  }

  test(
    "mode comes from the gate, not from d_wl: reversed source is Distorted(RoleReversal) with any provider"
  ) {
    val grakern = GrakernStructuralDistance
      .prepare(Vector(straight, reversed))
      .fold(e => fail(e.message), identity)
    val vReversed = viewWith(Map(e5 -> PropositionEvidence.hand(reversed)))
    val recallR = recall.copy(units = recall.units.map(u => if u.id == u2.id then unitChart else u))
    def facetsOnE5(provider: StructuralDistance): Set[NonEmptySet[Facet]] =
      val cands = CandidateGenerator(semantic, perLevel = 2).generate(recallR.ordered, vReversed)
      val res = GraphHsmm
        .infer(recallR, vReversed, cands, costModel.copy(structural = provider))
        .toOption
        .get
      val row = res.posterior.row(u2.id).get
      // (e5, Faithful) is inadmissible whatever the structural provider says …
      assertEquals(row.faithfulMassOn(e5), 0.0)
      // … while the anchor itself survives as a distorted state with positive mass.
      assert(row.distortedMassOn(e5) > 0.0, row.mass)
      row.mass.collect {
        case (AlignState.Distorted(ref, facets), m) if ref == e5 && m > 0.0 => facets
      }.toSet
    val expected = Set(NonEmptySet.one(Facet.RoleReversal))
    assertEquals(facetsOnE5(grakern), expected)
    assertEquals(facetsOnE5(StructuralDistance.of((_, _) => 0.0)), expected)
    assertEquals(facetsOnE5(StructuralDistance.missing), expected)
  }

  test("straight source chart stays Faithful on e5 with the grakern provider (no regression)") {
    val grakern = GrakernStructuralDistance
      .prepare(Vector(straight))
      .fold(e => fail(e.message), identity)
    val v = viewWith(Map(e5 -> PropositionEvidence.hand(straight)))
    val recallV = recall.copy(units = recall.units.map(u => if u.id == u2.id then unitChart else u))
    val cands = CandidateGenerator(semantic, perLevel = 2).generate(recallV.ordered, v)
    val res = GraphHsmm.infer(recallV, v, cands, costModel.copy(structural = grakern)).toOption.get
    val row = res.posterior.row(u2.id).get
    assert(row.faithfulMassOn(e5) > 0.5, row.mass)
    assertEquals(
      grakern(PropositionEvidence.hand(straight), PropositionEvidence.hand(straight)),
      Estimate.observed(0.0)
    )
  }
