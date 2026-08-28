package storymodel4s.align

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import storymodel4s.features.{Coverage, Estimate, MissingReason}
import storymodel4s.proposition.*
import storymodel4s.proposition.CheckState.Checked
import storymodel4s.recall.RecallUnit

/** Adversarial laws for the declared segment structural estimand and its audit receipt. */
class StructuralReductionSuite extends ScalaCheckSuite:
  import AnnaFixture.*

  private def chart(agent: String, patient: String): PropositionChart[Checked] =
    val predicate = ConceptId.unsafe("p")
    val actor = ConceptId.unsafe("a")
    val target = ConceptId.unsafe("b")
    val unchecked = PropositionChart.unchecked(
      Some(predicate),
      Map(
        predicate -> Concept.predicate("find"),
        actor -> Concept.entity(agent),
        target -> Concept.entity(patient)
      ),
      Vector(
        PropositionRelation(predicate, RoleAssignment.arg(0), ConceptTarget.Node(actor)),
        PropositionRelation(predicate, RoleAssignment.arg(1), ConceptTarget.Node(target))
      ),
      polarity = Map(predicate -> Polarity.Positive)
    )
    ChartValidator.check(unchecked).fold(v => fail(s"invalid chart: $v"), identity)

  private val straight = PropositionEvidence.hand(chart("anna", "brother"))
  private val reversed = PropositionEvidence.hand(chart("brother", "anna"))
  private val compatibleOther = PropositionEvidence.hand(chart("anna", "friend"))
  private val unit: RecallUnit = u2.copy(evidence = Some(straight))

  private def viewWith(
      evidence: Map[SourceNodeRef, PropositionEvidence],
      orderedNodes: Vector[NodeSummary] = view.nodes
  ): InMemorySourceView =
    InMemorySourceView(
      orderedNodes.map(node => node.copy(evidence = evidence.get(node.ref))),
      view.edges,
      view.worldOrder,
      view.textLength
    )

  property("an incompatible chart with a flattering estimate cannot lower segment cost") {
    forAll(Gen.choose(0.1, 1.0), Gen.choose(0.0, 0.09)) { (compatible, flattering) =>
      val provider = StructuralDistance((_, member) =>
        if member == reversed then Estimate.observed(flattering)
        else Estimate.observed(compatible)
      )
      val baselineView = viewWith(Map(e4 -> straight))
      val adversarialView = viewWith(Map(e4 -> straight, e5 -> reversed))
      val baseline =
        ChartDistance.structuralReduction(provider, unit, baselineView.node(sc2).get, baselineView)
      val adversarial =
        ChartDistance.structuralReduction(
          provider,
          unit,
          adversarialView.node(sc2).get,
          adversarialView
        )
      adversarial.estimate == baseline.estimate
    }
  }

  test("an incompatible input remains visible as an exclusion but never becomes a member") {
    val v = viewWith(Map(e4 -> straight, e5 -> reversed))
    val reduction = ChartDistance.reduction(unit, v.node(sc2).get, v)
    assertEquals(reduction.receipt.members.map(_.member), Vector(e4))
    assertEquals(reduction.receipt.excludedMembers.map(_.member), Vector(e5))
    assert(
      reduction.receipt.excludedMembers.head.contradictions.contains(Contradiction.RoleReversal)
    )
  }

  property("provider abstention cannot lower the minimum over observed compatible members") {
    forAll(Gen.choose(0.0, 1.0)) { observed =>
      val provider = StructuralDistance((_, member) =>
        if member == compatibleOther then Estimate.missing(MissingReason.ProviderAbstained)
        else Estimate.observed(observed)
      )
      val baselineView = viewWith(Map(e4 -> straight))
      val partialView = viewWith(Map(e4 -> straight, e5 -> compatibleOther))
      val baseline =
        ChartDistance.structuralReduction(provider, unit, baselineView.node(sc2).get, baselineView)
      val partial =
        ChartDistance.structuralReduction(provider, unit, partialView.node(sc2).get, partialView)
      partial.estimate == baseline.estimate
    }
  }

  test("source-chart coverage and provider-observed coverage are separate receipt fields") {
    val provider = StructuralDistance((_, member) =>
      if member == compatibleOther then Estimate.missing(MissingReason.ProviderAbstained)
      else Estimate.observed(0.4)
    )
    val v = viewWith(Map(e4 -> straight, e5 -> compatibleOther))
    val reduction = ChartDistance.structuralReduction(provider, unit, v.node(sc2).get, v)
    assertEquals(reduction.receipt.sourceChartCoverage, StructuralCoverage(1, 2, 3))
    assertEquals(reduction.receipt.observedEstimateCoverage, Coverage.unsafe(2, 1))
    assertEquals(reduction.receipt.reducer, StructuralReducer.Minimum)
    assertEquals(reduction.receipt.members.map(_.member), Vector(e4, e5))
  }

  test("local costs retain both typed per-term reduction receipts") {
    val provider = StructuralDistance((_, member) =>
      if member == compatibleOther then Estimate.missing(MissingReason.ProviderAbstained)
      else Estimate.observed(0.4)
    )
    val v = viewWith(Map(e4 -> straight, e5 -> compatibleOther))
    val breakdown =
      costModel
        .copy(structural = provider)
        .cost(unit, v.node(sc2).get, FidelityMode.Faithful, v)
    val chartReceipt = breakdown.reduction(CostTerm.Chart).getOrElse(fail("missing chart receipt"))
    val providerReceipt =
      breakdown.reduction(CostTerm.Structural).getOrElse(fail("missing structural receipt"))
    assertEquals(breakdown.sourceChartCoverage, Some(StructuralCoverage(1, 2, 3)))
    assertEquals(chartReceipt.members.map(_.member), Vector(e4, e5))
    assertEquals(chartReceipt.observedEstimateCoverage, Coverage.unsafe(2, 2))
    assertEquals(providerReceipt.members.map(_.member), Vector(e4, e5))
    assertEquals(providerReceipt.observedEstimateCoverage, Coverage.unsafe(2, 1))
    assertEquals(breakdown.reduction(CostTerm.Semantic), None)
  }

  property("permuting source storage cannot change the structural estimand") {
    forAll(Gen.long) { seed =>
      val shuffled = new scala.util.Random(seed).shuffle(view.nodes).toVector
      val permutation =
        if shuffled == view.nodes then view.nodes.tail :+ view.nodes.head else shuffled
      val provider = StructuralDistance((_, member) =>
        if member == straight then Estimate.observed(0.2) else Estimate.observed(0.7)
      )
      val evidence = Map(e4 -> straight, e5 -> compatibleOther)
      val canonical = viewWith(evidence)
      val permuted = viewWith(evidence, permutation.toVector)
      val expected =
        ChartDistance.structuralReduction(provider, unit, canonical.node(sc2).get, canonical)
      val actual =
        ChartDistance.structuralReduction(provider, unit, permuted.node(sc2).get, permuted)
      permutation != view.nodes && actual == expected
    }
  }

  property("a one-member leaf reduction equals its direct pair estimate") {
    forAll(Gen.choose(0.0, 1.0)) { distance =>
      val provider = StructuralDistance.of((_, _) => distance)
      val v = viewWith(Map(e5 -> straight))
      val reduction = ChartDistance.structuralReduction(provider, unit, v.node(e5).get, v)
      reduction.estimate == Estimate.observed(distance) &&
      reduction.receipt.members.map(_.member) == Vector(e5) &&
      reduction.receipt.observedEstimateCoverage == Coverage.unsafe(1, 1)
    }
  }

  test("uncovered leaves never contribute a neutral constant to chart distance") {
    val v = viewWith(Map(e5 -> straight))
    val reduction = ChartDistance.reduction(unit, v.node(sc2).get, v)
    assertEquals(reduction.estimate, Estimate.observed(0.0))
    assertEquals(reduction.receipt.sourceChartCoverage, StructuralCoverage(1, 1, 3))
    assertEquals(reduction.receipt.observedEstimateCoverage, Coverage.unsafe(1, 1))
  }

  test("all-provider abstention is Missing rather than an imputed neutral score") {
    val v = viewWith(Map(e4 -> straight, e5 -> compatibleOther))
    val reduction =
      ChartDistance.structuralReduction(
        StructuralDistance.missing,
        unit,
        v.node(sc2).get,
        v
      )
    assertEquals(reduction.estimate, Estimate.missing(MissingReason.ProviderAbstained))
    assertEquals(reduction.receipt.observedEstimateCoverage, Coverage.unsafe(2, 0))
  }
