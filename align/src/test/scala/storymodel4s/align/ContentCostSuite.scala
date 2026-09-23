package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.RecallUnitId

/** Literal price controls for the content-only part of the default local cost model. */
class ContentCostSuite extends FunSuite:
  import AnnaFixture.*

  private val node = view.node(e5).getOrElse(fail("missing e5"))
  private val unit =
    u2.copy(proposition = u2.proposition.copy(sensoryTerms = Vector("cellar", "unseen")))

  private def canonical(u: storymodel4s.recall.RecallUnit) =
    ContentProjection.canonical(u, node, view).fold(r => fail(s"refused: $r"), identity)

  private def score(semantic: Estimate[Double], u: storymodel4s.recall.RecallUnit = unit) =
    val (contentUnit, contentNode, grain) = canonical(u)
    ContentCostScoring.terms(
      contentUnit,
      contentNode,
      grain,
      FidelityMode.Faithful,
      semantic,
      Estimate.missing(MissingReason.ProviderAbstained),
      Estimate.missing(MissingReason.ProviderAbstained),
      structuralConfigured = false,
      missingSemantic = 0.5,
      distortionPenalty = 0.3
    )

  test("content terms retain literal semantic and sensory prices without inventing chart support") {
    val result = score(Estimate.observed(0.25))
    assertEquals(
      result.values,
      Map(
        CostTerm.Semantic -> 0.25,
        CostTerm.Propositional -> 0.0,
        CostTerm.Entity -> 0.0,
        CostTerm.Granularity -> 0.0,
        CostTerm.Distortion -> 0.0,
        CostTerm.Sensory -> 0.5
      )
    )
    assertEquals(result.missing, Set(CostTerm.Chart, CostTerm.Structural))
    assertEquals(result.semanticImputed, Map.empty[CostTerm, MissingReason])
    assertEquals(result.eligible, result.values.keySet)
  }

  test("a missing semantic measurement has the same price but a distinct imputation record") {
    val measured = score(Estimate.observed(0.5))
    val missing = score(Estimate.missing(MissingReason.ProviderAbstained))
    assertEquals(missing.values, measured.values)
    assertEquals(missing.eligible, measured.eligible)
    assertEquals(missing.semanticImputed, Map(CostTerm.Semantic -> MissingReason.ProviderAbstained))
    assertEquals(measured.semanticImputed, Map.empty[CostTerm, MissingReason])
  }

  test("content prices do not move with unit identity or participant storage order") {
    val renamed = unit.copy(
      id = RecallUnitId.unsafe("cost-renamed"),
      ordinal = 99,
      proposition = unit.proposition.copy(participants = unit.proposition.participants.reverse)
    )
    assertEquals(score(Estimate.observed(0.25), renamed), score(Estimate.observed(0.25)))
  }
