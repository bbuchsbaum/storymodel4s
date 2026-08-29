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

  /** One valid hand chart, shared by the eligibility laws below. Suite-level so the chartless and
    * charted views are built from the same evidence and differ only in where it is attached.
    */
  private val costChartEvidence: storymodel4s.proposition.PropositionEvidence =
    import storymodel4s.proposition.*
    val predicate = ConceptId.unsafe("p")
    val actor = ConceptId.unsafe("a")
    val target = ConceptId.unsafe("b")
    val unchecked = PropositionChart.unchecked(
      Some(predicate),
      Map(
        predicate -> Concept.predicate("find"),
        actor -> Concept.entity("anna"),
        target -> Concept.entity("brother")
      ),
      Vector(
        PropositionRelation(predicate, RoleAssignment.arg(0), ConceptTarget.Node(actor)),
        PropositionRelation(predicate, RoleAssignment.arg(1), ConceptTarget.Node(target))
      ),
      polarity = Map(predicate -> Polarity.Positive)
    )
    PropositionEvidence.hand(
      ChartValidator.check(unchecked).fold(v => fail(s"invalid chart: $v"), identity)
    )

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

  /** A segment cell must not be discounted for having measured its members' charts.
    *
    * A segment node never has a chart of its own — `StorySourceView` is explicit that "segments
    * never get a fabricated chart" — but the Chart term is produced by `ChartDistance.reduction`,
    * which measures over `view.structuralMembers(node.ref)`, the LEAVES. `chartEligible` used to
    * test `node.evidence`, which is the contract of `ChartDistance.report` — a different function
    * from the one that actually runs. The two predicates coincide on a leaf and diverge on a
    * segment, so Chart came out present-but-not-eligible.
    *
    * The consequence was compound and both halves pointed the same way. `wPresent` exceeded
    * `wEligible`, so `scaleToEligible` fell to 0.9054 and multiplied the cost DOWN — the cell was
    * made to look better BECAUSE it had measured more — while `supportOf`, which clamps with
    * `math.min(1.0, _)`, reported the ratio above one as a flat 1.0: full support, nothing assumed,
    * on a cell that had in fact assumed Sensory. Measured against the correct scaling of 1.0405 the
    * cell was priced 14.9% too low.
    *
    * It lands only on segment cells and only once charts exist, which is a systematic thumb on the
    * scale for coarse anchors over leaf anchors — recall made to look more scene-level than it is.
    * WOG carries no charts, so no fixture in this suite could have caught it.
    */
  test("a segment cell is not discounted for measuring its members' charts") {
    import AnnaFixture.*
    val v = InMemorySourceView(
      view.nodes.map(n => n.copy(evidence = if n.ref == e4 then Some(costChartEvidence) else None)),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val segment = v.node(sc2).get
    // Preconditions, asserted rather than assumed: the law is vacuous if the fixture stops being a
    // chartless segment over a charted leaf, and a vacuous law is how the last two defects survived.
    assert(!segment.isLeaf, "fixture: sc2 must be a segment")
    assert(segment.evidence.isEmpty, "fixture: a segment must carry no chart of its own")
    assert(
      v.structuralMembers(sc2).exists(_.hasEvidence),
      "fixture: a leaf under sc2 must be charted"
    )
    val b = DefaultLocalCostModel().cost(
      u2.copy(evidence = Some(costChartEvidence)),
      segment,
      FidelityMode.Faithful,
      v
    )
    assert(b.terms.contains(CostTerm.Chart), "fixture: the Chart term must actually be measured")
    // Sensory is eligible and absent here, so honest support is below one. Before the fix this read
    // exactly 1.0 — the clamp turning an over-unity ratio into a claim that nothing was assumed.
    assert(
      b.supportWeight < 1.0,
      s"support ${b.supportWeight} claims nothing was assumed, but Sensory was"
    )
    assertEqualsDouble(b.supportWeight, 0.961038961038961, 1e-9)
  }

  /** Configuring a structural provider must be inert on a cell with no source chart to compare.
    *
    * The mirror of the Chart defect above, found by scout on the candidate that fixed Chart.
    * `structuralReduction` reads the same `structuralMembers` population, but `structuralEligible`
    * asked only whether a provider was configured and the unit had evidence — never whether the
    * source had a chart at all. On a chartless cell the Structural term is absent either way, so
    * configuration alone moved `(supportWeight, total)` from `(0.9552, 1.37045)` to
    * `(0.8312, 1.575)`: the cell paid a 15% inflation for a measurement it could never have had,
    * which biases chartless cells toward External.
    *
    * Eligibility is "could this cell have been measured", not "is a provider switched on". A
    * contradiction that excludes every charted member is a genuine missed measurement and must
    * still lower support; a source with no charts is a dimension the cell does not have.
    */
  test("a configured structural provider is inert on a chartless cell") {
    import AnnaFixture.*
    val chartless = InMemorySourceView(
      view.nodes.map(_.copy(evidence = None)),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val segment = chartless.node(sc2).get
    assert(
      !chartless.structuralMembers(sc2).exists(_.hasEvidence),
      "fixture: every structural member must be chartless"
    )
    val withUnitChart = u2.copy(evidence = Some(costChartEvidence))
    val configured = DefaultLocalCostModel(structural = StructuralDistance.of((_, _) => 0.0))
      .cost(withUnitChart, segment, FidelityMode.Faithful, chartless)
    val absent = DefaultLocalCostModel(structural = StructuralDistance.missing)
      .cost(withUnitChart, segment, FidelityMode.Faithful, chartless)
    assert(
      !configured.terms.contains(CostTerm.Structural),
      "fixture: the Structural term must be absent on a chartless cell"
    )
    assertEqualsDouble(configured.supportWeight, absent.supportWeight, eps)
    assertEqualsDouble(configured.total, absent.total, eps)
  }

  /** Positive control for the law above: where the source IS charted, configuration must matter.
    *
    * Without this, "a configured provider is inert" would be satisfiable by ignoring the provider
    * everywhere, which is a worse bug than the one being fixed.
    */
  test("a configured structural provider is not inert where the source is charted") {
    import AnnaFixture.*
    val charted = InMemorySourceView(
      view.nodes.map(n => n.copy(evidence = if n.ref == e4 then Some(costChartEvidence) else None)),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val segment = charted.node(sc2).get
    val withUnitChart = u2.copy(evidence = Some(costChartEvidence))
    val configured = DefaultLocalCostModel(structural = StructuralDistance.of((_, _) => 0.0))
      .cost(withUnitChart, segment, FidelityMode.Faithful, charted)
    val absent = DefaultLocalCostModel(structural = StructuralDistance.missing)
      .cost(withUnitChart, segment, FidelityMode.Faithful, charted)
    assert(configured.terms.contains(CostTerm.Structural), "the provider was ignored entirely")
    assert(!absent.terms.contains(CostTerm.Structural))
    assertNotEquals(configured.total, absent.total)
    // The literals above this line are NOT a control on eligibility, and scout proved it: term
    // production happens BEFORE eligibility is consulted, so the independent mutation
    // `val structuralEligible = false` leaves the Structural term present and the two totals
    // different, and every assertion up to here stays green. Under that mutation the configured
    // cell reads (1.0, 1.2) - full support, because nothing eligible went unmeasured once the
    // eligible set stopped containing Structural. Pinning the true tuple is what kills it.
    assertEqualsDouble(configured.supportWeight, 0.9655172413793105, eps)
    assertEqualsDouble(configured.total, 1.3558441558441556, eps)
    assertEqualsDouble(absent.supportWeight, 0.961038961038961, eps)
    assertEqualsDouble(absent.total, 1.3621621621621622, eps)
  }
