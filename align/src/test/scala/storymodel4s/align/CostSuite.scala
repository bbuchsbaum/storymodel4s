package storymodel4s.align

import munit.FunSuite

/** The local cost blend, pinned by literal expected values.
  *
  * Every other assertion in align that touches a total compares one computed total to another —
  * `withProvider.total == base.total`, `c.total > b.total`, `totalFlow > 0`. All of those survive a
  * systematic change to the blend, because both sides of the comparison move together. The numbers
  * below are hand-computed and written as literals; recomputing them through `blend` would prove
  * only that the function equals itself.
  */
class CostSuite extends FunSuite:

  private val eps = 1e-12

  /** Distinct weights so that swapping any two terms changes the result. */
  private val w = CostWeights
    .of(
      semantic = 0.5,
      propositional = 0.25,
      entity = 0.125,
      sensory = 2.0,
      granularity = 4.0,
      contradiction = 8.0,
      chart = 16.0,
      structural = 32.0
    )
    .fold(e => throw new IllegalStateException(e.message), identity)

  test("weighted sum: each term is multiplied by its own weight, then summed") {
    // 0.5*1 + 0.25*2 + 0.125*4 = 0.5 + 0.5 + 0.5 = 1.5
    val terms = Map(
      CostTerm.Semantic -> 1.0,
      CostTerm.Propositional -> 2.0,
      CostTerm.Entity -> 4.0
    )
    assertEqualsDouble(DefaultLocalCostModel.blend(terms, w, 0.0), 1.5, eps)
  }

  test("the function prior is ADDED after weighting, never weighted itself") {
    val terms = Map(CostTerm.Semantic -> 1.0) // 0.5 * 1 = 0.5
    assertEqualsDouble(DefaultLocalCostModel.blend(terms, w, 0.3), 0.8, eps)
    // If the prior were folded in before weighting it would scale: 0.5 * (1 + 0.3) = 0.65.
    assert(math.abs(DefaultLocalCostModel.blend(terms, w, 0.3) - 0.65) > 0.1)
  }

  test("an absent term contributes nothing — it is not a zero-valued term") {
    val withChart = Map(CostTerm.Semantic -> 1.0, CostTerm.Chart -> 0.25) // 0.5 + 16*0.25 = 4.5
    val without = Map(CostTerm.Semantic -> 1.0) // 0.5
    assertEqualsDouble(DefaultLocalCostModel.blend(withChart, w, 0.0), 4.5, eps)
    assertEqualsDouble(DefaultLocalCostModel.blend(without, w, 0.0), 0.5, eps)
  }

  test("every CostTerm is reachable and carries its own weight") {
    // One term at a time, value 1.0, so the total IS that term's weight. A term dropped from the
    // enum iteration, or two terms sharing a weight, fails here.
    val expected = Map(
      CostTerm.Semantic -> 0.5,
      CostTerm.Propositional -> 0.25,
      CostTerm.Entity -> 0.125,
      CostTerm.Sensory -> 2.0,
      CostTerm.Granularity -> 4.0,
      CostTerm.Distortion -> 8.0,
      CostTerm.Chart -> 16.0,
      CostTerm.Structural -> 32.0
    )
    assertEquals(
      CostTerm.values.toSet,
      expected.keySet,
      "a new CostTerm exists with no pinned weight in this test"
    )
    for (term, weight) <- expected do
      assertEqualsDouble(
        DefaultLocalCostModel.blend(Map(term -> 1.0), w, 0.0),
        weight,
        eps,
        s"$term does not carry its own weight"
      )
  }

  test("all terms together: the full sum is exactly the sum of the parts") {
    // Every term at 1.0: 0.5 + 0.25 + 0.125 + 2 + 4 + 8 + 16 + 32 = 62.875, plus a 0.6 prior.
    val all = CostTerm.values.toVector.map(_ -> 1.0).toMap
    assertEqualsDouble(DefaultLocalCostModel.blend(all, w, 0.6), 63.475, eps)
  }

  test("no terms: the cost is the function prior alone, and an empty blend is not zero") {
    assertEqualsDouble(DefaultLocalCostModel.blend(Map.empty, w, 0.6), 0.6, eps)
    assertEqualsDouble(DefaultLocalCostModel.blend(Map.empty, w, 0.0), 0.0, eps)
  }

  test("summation is deterministic in enum order, not in map iteration order") {
    // Values chosen so that a different summation order would round differently in binary floating
    // point: the same terms inserted in two orders must give bit-identical totals.
    val a = Map(
      CostTerm.Semantic -> 0.1,
      CostTerm.Propositional -> 0.2,
      CostTerm.Entity -> 0.3,
      CostTerm.Sensory -> 0.7
    )
    val b = Map(
      CostTerm.Sensory -> 0.7,
      CostTerm.Entity -> 0.3,
      CostTerm.Propositional -> 0.2,
      CostTerm.Semantic -> 0.1
    )
    assertEquals(
      java.lang.Double.doubleToLongBits(DefaultLocalCostModel.blend(a, w, 0.0)),
      java.lang.Double.doubleToLongBits(DefaultLocalCostModel.blend(b, w, 0.0))
    )
  }

  test("the default weights are the ones the model actually ships with") {
    // A silent change to a default weight rescales every cost in the library; pin them as literals.
    val d = CostWeights.default
    assertEqualsDouble(d(CostTerm.Semantic), 1.0, eps)
    assertEqualsDouble(d(CostTerm.Propositional), 0.5, eps)
    assertEqualsDouble(d(CostTerm.Entity), 0.4, eps)
    assertEqualsDouble(d(CostTerm.Sensory), 0.15, eps)
    assertEqualsDouble(d(CostTerm.Granularity), 0.3, eps)
    assertEqualsDouble(d(CostTerm.Distortion), 1.0, eps)
    assertEqualsDouble(d(CostTerm.Chart), 0.5, eps)
    assertEqualsDouble(d(CostTerm.Structural), 0.5, eps)
  }
