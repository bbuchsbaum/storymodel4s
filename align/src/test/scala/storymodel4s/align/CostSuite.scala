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

  test("LAW 1: a cell whose eligible terms were all measured is bit-identical") {
    // The guarantee that makes scaling safe to adopt. If every eligible term was measured, the
    // factor is exactly 1 and the cost is the number it was before the scale existed - so the
    // calibrated weights, external floor, mismatch and priors all keep their meaning.
    val terms = Map(
      CostTerm.Semantic -> 1.0,
      CostTerm.Propositional -> 2.0,
      CostTerm.Entity -> 4.0
    )
    val eligible = terms.keySet
    val scaled = DefaultLocalCostModel.blend(terms, w, 0.3, eligible)
    val unscaled = DefaultLocalCostModel.blend(terms, w, 0.3)
    assertEqualsDouble(
      DefaultLocalCostModel.scaleToEligible(terms.keySet, eligible, w),
      1.0,
      0.0,
      "full support must scale by EXACTLY one, not approximately"
    )
    assertEqualsDouble(scaled, unscaled, 0.0, "a fully measured cell must not move at all")

    // And the control: it is only bit-identical because present == eligible. Widen eligible and
    // the same terms scale up, or the law above would hold for any input and prove nothing.
    val wider = eligible + CostTerm.Sensory
    assert(
      DefaultLocalCostModel.blend(terms, w, 0.3, wider) > scaled,
      "a cell missing an ELIGIBLE term must scale up; otherwise LAW 1 is vacuous"
    )
  }

  test("LAW 2: an INELIGIBLE term is inert - it cannot change the source/external balance") {
    // The law as I can defensibly state it. A term that could never have been measured for this
    // cell must not affect the outcome at all: including it in the eligible set or leaving it out
    // must give the same source-versus-external comparison.
    //
    // NOTE THIS IS A REGRESSION GUARD, NOT A POSITIVE CONTROL, and I want that on the record rather
    // than implied. Today's code has no eligibility concept at all, so it passes this trivially -
    // it cannot fail on the unfixed model. What it protects is the fix: if a later change lets
    // ineligible terms into the scale denominator, the balance moves and this fails.
    val model = DefaultLocalCostModel()
    val unit = AnnaFixture.recall.ordered.head
    val node = AnnaFixture.view.nodes.head
    val b = model.cost(unit, node, FidelityMode.Faithful, AnnaFixture.view)

    // Chart is ineligible here: neither side carries a chart. Adding it to the eligible set is
    // exactly the "invent a dimension the data cannot have" error that collapsed every row when I
    // scaled over all terms.
    val honest = DefaultLocalCostModel.blend(b.terms, CostWeights.default, 0.0, b.terms.keySet)
    val inflated =
      DefaultLocalCostModel.blend(
        b.terms,
        CostWeights.default,
        0.0,
        b.terms.keySet + CostTerm.Chart
      )
    assert(
      inflated > honest,
      "counting an ineligible term as eligible must inflate the cost; if it does not, the scale is inert and LAW 2 proves nothing"
    )

    // And the cost the model actually produces must be the honest one, not the inflated one.
    assertEqualsDouble(b.total, honest, 1e-9, "the model counted an ineligible term as eligible")
  }

  test("LAW 3: zero-support weights infer to a Right whose source states are all excluded") {
    // The boundary my first two laws could not see. LAW 1 is about cells with FULL support and
    // LAW 2 about cells with an INELIGIBLE term; both assume something was measured. A cell where
    // every eligible term carries zero weight falls between them, and it is where the whole change
    // nearly reintroduced the defect it removes: with no weighted evidence the cost falls to the
    // function prior, BELOW the external floor, so the cell that measured nothing would win.
    //
    // Found by codex-storymodel4s-scout on a candidate that had already passed ten modules.
    val w = CostWeights.of(0.0, 0.0, 0.0, 1.0, 0.0, 0.0).fold(e => fail(e.message), identity)
    val model = DefaultLocalCostModel(weights = w, semantic = SemanticDistance.lexicalJaccard)
    val stripped = storymodel4s.recall.RecallGraph
      .validated(AnnaFixture.recall.copy(units = AnnaFixture.recall.units.map { u =>
        u.copy(proposition = u.proposition.copy(sensoryTerms = Vector.empty))
      }))
      .fold(e => fail(e.toString), identity)

    val r = GraphHsmm
      .infer(stripped, AnnaFixture.view, AnnaFixture.candidates, model)
      .fold(e => fail(s"zero-support weights must still infer: ${e.message}"), identity)

    // INFERENCE SUCCEEDS. Before the fix this was a Left - the excluded breakdowns stayed in
    // `costs` and validated rejected every one, so a lawful weight vector broke the aligner
    // outright.
    val excluded = r.costs.values.flatMap(_.values).filter(_.excluded)
    assert(excluded.nonEmpty, "nothing was excluded, so this fixture does not reach the boundary")
    assert(
      excluded.forall(_.exclusion.contains(Exclusion.Unassessable)),
      "a zero-support cell must be Unassessable, not Unreachable - the node IS in the view"
    )

    // NO SOURCE STATE SURVIVES INTO THE POSTERIOR, which is the point: with no weighted evidence
    // we have no basis to rank any anchor, so the unit goes external rather than to the cheapest
    // unmeasured cell.
    r.posterior.rows.foreach { row =>
      assert(
        row.mass.keys.forall(_.isExternal),
        s"a zero-support unit kept a source state: ${row.mass.keys.map(_.key).mkString(",")}"
      )
    }

    // AND THE AUDIT TRAIL SURVIVES. Dropping the excluded records would make an exclusion
    // indistinguishable from a candidate that was never nominated.
    assert(
      excluded.forall(_.supportWeight == 0.0),
      "an excluded zero-support record must carry supportWeight 0, not a fabricated 1.0"
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
