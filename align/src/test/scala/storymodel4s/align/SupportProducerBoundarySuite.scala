package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.RecallUnit

/** Package-visible producers must obey the same boundary as wire consumers. */
class SupportProducerBoundarySuite extends FunSuite:
  import AnnaFixture.{candidates, costModel, recall, view}

  private def refused(value: Either[AlignError, ?]): Unit = value match
    case Left(AlignError.MalformedRecord("CostBreakdown", _)) => ()
    case other => fail(s"expected a CostBreakdown refusal, got $other")

  test("a producer cannot publish priced terms outside its declared eligibility") {
    refused(
      CostBreakdown.derived(
        Map(CostTerm.Semantic -> 0.2, CostTerm.Sensory -> 0.3),
        FidelityMode.Faithful,
        Set.empty,
        None,
        Map.empty,
        Map.empty,
        Set(CostTerm.Semantic),
        CostWeights.default,
        1.0
      )
    )
    val valid = CostBreakdown
      .derived(
        Map(CostTerm.Semantic -> 0.2),
        FidelityMode.Faithful,
        Set.empty,
        None,
        Map.empty,
        Map.empty,
        Set(CostTerm.Semantic),
        CostWeights.default,
        1.0
      )
      .fold(e => fail(e.message), identity)
    assertEquals(valid.terms, Map(CostTerm.Semantic -> 0.2))
    assertEquals(valid.total, 1.0)
  }

  test("a producer cannot publish a nonfinite or negative external total") {
    Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, -1.0)
      .foreach(x => refused(CostBreakdown.external(x)))
    Vector(0.0, 1.25, Double.MaxValue).foreach { x =>
      val valid = CostBreakdown.external(x).fold(e => fail(e.message), identity)
      assertEquals(valid.total, x)
      assertEquals(valid.support, SupportAssessment.externalState)
    }
  }

  test("zero assessed support excludes the cell without evaluating its total") {
    val value = CostBreakdown
      .derived(
        Map.empty,
        FidelityMode.Faithful,
        Set.empty,
        None,
        Map.empty,
        Map.empty,
        Set(CostTerm.Semantic),
        CostWeights.default,
        fail("excluded total was evaluated")
      )
      .fold(e => fail(e.message), identity)
    assertEquals(value.exclusion, Some(Exclusion.Unassessable))
    value.support match
      case a: SupportAssessment.Assessed => assertEquals(a.share, 0.0)
      case other                         => fail(s"expected zero assessed support, got $other")
  }

  test("invalid custom external costs receive a typed refusal in both inference paths") {
    Vector(Double.NaN, Double.PositiveInfinity, -1.0).foreach { bad =>
      val model = new LocalCostModel:
        def cost(u: RecallUnit, n: NodeSummary, m: FidelityMode, v: SourceView): CostBreakdown =
          costModel.cost(u, n, m, v)
        def externalFloor: Double = bad
        def externalCost(u: RecallUnit, s: ExternalState): Double = bad
      refused(GraphHsmm.infer(recall, view, candidates, model))
      refused(GraphHsmm.ablationUngated(recall, view, candidates, model))
    }
    assert(GraphHsmm.infer(recall, view, candidates, costModel).isRight)
    assert(GraphHsmm.ablationUngated(recall, view, candidates, costModel).isRight)
  }
