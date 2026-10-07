package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.{Coverage, Estimate, MissingReason}

/** Explicit provider ineligibility removes evidence dimensions without inventing a low price. */
class EstimateEligibilitySuite extends FunSuite:
  import AnnaFixture.*
  private val node = view.node(e5).get
  private val semanticOnly = CostWeights.unsafe(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
  private def model(outcome: Estimate[Double], weights: CostWeights = semanticOnly) =
    costModel.copy(semantic = SemanticDistance((_, _) => outcome), weights = weights)

  test(
    "an ineligible semantic provider contributes no price, imputation, missing term or support"
  ) {
    val (u, t, grain) = ContentProjection.source(u2, node, view)
    val price = ContentCostScoring.terms(
      u,
      t,
      grain,
      FidelityMode.Faithful,
      Estimate.Ineligible,
      Estimate.missing(MissingReason.ProviderAbstained),
      Estimate.missing(MissingReason.ProviderAbstained),
      false,
      0.5,
      0.3
    )
    assert(!price.values.contains(CostTerm.Semantic))
    assert(!price.eligible.contains(CostTerm.Semantic))
    assert(!price.missing.contains(CostTerm.Semantic))
    assert(!price.semanticImputed.contains(CostTerm.Semantic))
    val cell = model(Estimate.Ineligible).cost(u2, node, FidelityMode.Faithful, view)
    assertEquals(cell.exclusion, Some(Exclusion.Unassessable))
    val basis = cell.support.asInstanceOf[SupportAssessment.Unestablished]
    assertEquals(basis.reason, SupportUnestablishedReason.ZeroEligibleWeight)
    assert(!basis.basis.eligibleTerms.contains(CostTerm.Semantic))
    assertEquals(cell.terms, Map.empty[CostTerm, Double])
    assertEquals(cell.total, Double.MaxValue / 4)
  }

  test("the ineligible last-positive-weight guard never evaluates the by-name total") {
    var evaluated = false
    val cell = CostBreakdown
      .derived(
        Map(CostTerm.Entity -> 0.0),
        FidelityMode.Faithful,
        Set.empty,
        None,
        Map.empty,
        Map.empty,
        Set(CostTerm.Entity),
        semanticOnly, {
          evaluated = true; 0.0
        },
        Map(CostTerm.Semantic -> Estimate.Ineligible)
      )
      .toOption
      .get
    assertEquals(evaluated, false)
    assertEquals(cell.exclusion, Some(Exclusion.Unassessable))
  }

  test("eligible Missing and Observed retain their existing distinct support and price") {
    val missing = model(Estimate.missing(MissingReason.ProviderAbstained))
      .cost(u2, node, FidelityMode.Faithful, view)
    assertEquals(missing.exclusion, Some(Exclusion.Unassessable))
    assertEquals(missing.support.asInstanceOf[SupportAssessment.Assessed].share, 0.0)
    val observed = model(Estimate.observed(0.25)).cost(u2, node, FidelityMode.Faithful, view)
    assertEquals(observed.exclusion, None)
    assertEquals(observed.terms(CostTerm.Semantic), 0.25)
    assertEquals(observed.total, 0.25 + FunctionPrior.default(u2.function))
  }

  test("other measured weight can rank an ineligible semantic cell; configured-zero policy stays") {
    val propositional = CostWeights.unsafe(1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val remaining =
      model(Estimate.Ineligible, propositional).cost(u2, node, FidelityMode.Faithful, view)
    assertEquals(remaining.exclusion, None)
    assertEquals(remaining.support.asInstanceOf[SupportAssessment.Assessed].share, 1.0)
    assertEquals(remaining.total, FunctionPrior.default(u2.function))
    val zero = CostWeights.unsafe(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val historical = model(Estimate.Ineligible, zero).cost(u2, node, FidelityMode.Faithful, view)
    assertEquals(historical.exclusion, None)
    assertEquals(historical.total, FunctionPrior.default(u2.function))
  }

  test("HSMM cannot rank anchors whose only configured channel is explicitly ineligible") {
    val result = GraphHsmm.infer(recall, view, candidates, model(Estimate.Ineligible)).toOption.get
    val cells = result.costs.values.toVector.flatMap(_.toVector.filter(!_._1.isExternal))
    assert(cells.nonEmpty)
    assert(cells.forall(_._2.exclusion.contains(Exclusion.Unassessable)))
    assert(result.viterbi.forall(_.isExternal))
    assert(result.posterior.rows.forall(_.mass.keys.forall(_.isExternal)))
  }

  test("wire receipts count eligible provider outcomes and refuse a forged aggregate state") {
    val chartCoverage = StructuralCoverage.of(0, 1, 1).toOption.get
    val member = AlignWire.memberEstimate(e5, Estimate.Ineligible).toOption.get
    val receipt = AlignWire
      .reductionReceipt(
        StructuralReducer.Minimum,
        Vector(member),
        Vector.empty,
        chartCoverage,
        Coverage.empty
      )
      .toOption
      .get
    assertEquals(receipt.observedEstimateCoverage, Coverage.empty)
    assert(
      AlignWire
        .reductionReceipt(
          StructuralReducer.Minimum,
          Vector(member),
          Vector.empty,
          chartCoverage,
          Coverage.unsafe(1, 0)
        )
        .isLeft
    )
    assert(AlignWire.structuralReduction(Estimate.Ineligible, receipt).isRight)
    assert(
      AlignWire
        .structuralReduction(Estimate.missing(MissingReason.ProviderAbstained), receipt)
        .isLeft
    )
    assert(AlignWire.structuralReduction(Estimate.observed(0.0), receipt).isLeft)
    assertNotEquals(
      Render.estimate(Estimate.Ineligible),
      Render.estimate(Estimate.missing(MissingReason.Excluded))
    )
  }

  test("fixed source-leaf importance cannot silently exclude a leaf from its denominator") {
    assert(ImportanceWeight.from(Estimate.Ineligible).isLeft)
    assert(ImportanceWeight.from(Estimate.missing(MissingReason.AllMissing)).isRight)
    assert(ImportanceWeight.from(Estimate.observed(0.0)).isRight)
  }
