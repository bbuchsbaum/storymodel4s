package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** External construction courts keep a checked content table and cost model unforgeable. */
class StrictContentBoundarySuite extends FunSuite:
  private val tableControl = typeCheckErrors("""
    import storymodel4s.align.*
    import storymodel4s.features.Estimate
    import storymodel4s.proposition.GraphOrder.Canonical
    case class TableControl(entries: Map[(UnitContent[Canonical], TargetContent[Canonical]), Estimate[Double]], otherwise: Estimate[Double])
    val value = TableControl(Map.empty, Estimate.observed(0.0))
    value.copy(otherwise = Estimate.Ineligible)
    summon[scala.deriving.Mirror.ProductOf[TableControl]].fromProduct((Map.empty, Estimate.observed(0.0)))
    summon[scala.deriving.Mirror.ProductOf[TableControl]]
  """)
  private val modelControl = typeCheckErrors("""
    import storymodel4s.align.*
    case class ModelControl(semantic: StrictSemanticChannel, weights: CostWeights, functionPrior: FunctionPrior, externalFloor: Double, externalMismatch: Double, missingSemantic: Double, distortionPenalty: Double)
    val value = ModelControl(StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3)
    value.copy(missingSemantic = 0.0)
    summon[scala.deriving.Mirror.ProductOf[ModelControl]].fromProduct((StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3))
    summon[scala.deriving.Mirror.ProductOf[ModelControl]]
  """)

  test("checked issuance and same-shape controls compile") {
    assertEquals(tableControl, Nil)
    assertEquals(modelControl, Nil)
    // Direct companions acquire this compiler-synthesized method only after their defining run.
    assertEquals(typeCheckErrors("storymodel4s.align.FunctionPrior.fromProduct(???)"), Nil)
    assertEquals(
      typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.observed(0.0))
      StrictCostModel.of(StrictSemanticChannel.Lexical)
    """),
      Nil
    )
  }

  test("content table constructor is closed outside align") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      new StrictSemanticChannel.ContentTable(Map.empty, Estimate.observed(0.0))
    """).nonEmpty)
  }
  test("content table apply is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      StrictSemanticChannel.ContentTable(Map.empty, Estimate.observed(0.0))
    """).nonEmpty)
  }
  test("content table copy is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      StrictSemanticChannel.ContentTable.of(Vector.empty).toOption.get.copy(otherwise = Estimate.Ineligible)
    """).nonEmpty)
  }
  test("content table fromProduct is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      StrictSemanticChannel.ContentTable.fromProduct((Map.empty, Estimate.observed(0.0)))
    """).nonEmpty)
  }
  test("content table Mirror is closed") {
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.StrictSemanticChannel.ContentTable]]"
      ).nonEmpty
    )
  }
  test("strict cost constructor is closed outside align") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      new StrictCostModel(StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3)
    """).nonEmpty)
  }
  test("strict cost apply is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      StrictCostModel(StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3)
    """).nonEmpty)
  }
  test("strict cost copy is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      StrictCostModel.of(StrictSemanticChannel.Lexical).toOption.get.copy(missingSemantic = 0.0)
    """).nonEmpty)
  }
  test("strict cost fromProduct is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      StrictCostModel.fromProduct((StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3))
    """).nonEmpty)
  }
  test("strict cost Mirror is closed") {
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.StrictCostModel]]"
      ).nonEmpty
    )
  }
  test("an arbitrary semantic callback cannot be a strict adapter") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      StrictCostModel.of(SemanticDistance.lexicalJaccard)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      import storymodel4s.proposition.GraphOrder.Canonical
      new StrictSemanticChannel {
        val kind = "captured-coordinates"
        def score(u: UnitContent[Canonical], t: TargetContent[Canonical]): Estimate[Double] = Estimate.observed(0.0)
      }
    """).nonEmpty)
  }
