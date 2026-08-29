package storymodel4s.align

import munit.FunSuite

import storymodel4s.features.{Estimate, MissingReason}

/** External mass must not let our failure to align be read as the participant's behaviour.
  *
  * `unrankedMass` is the destination of a unit the aligner could not rank at all. The other five
  * external terms are claims about the person. Summing them produces a plausible number that moves
  * in one direction exactly when alignment is hardest — which is exactly when a study's groups
  * differ.
  */
class SignatureSuite extends FunSuite:
  import AnnaFixture.*

  private lazy val sig: RecallSignature =
    val result = GraphHsmm
      .infer(recall, view, candidates, costModel)
      .fold(e => fail(e.message), identity)
    RecallSignature.compute(result, recall, view)

  private val eps = 1e-12

  private def report(attributed: Double, unranked: Double) =
    ExternalMassReport.of(attributed, unranked).fold(e => fail(e.message), identity)

  test("RecallSignature.externalMass computes the exact production split") {
    val associationMass = 0.01
    val intrusionMass = 0.02
    val commentaryMass = 0.03
    val sourceConsistentInferenceMass = 0.04
    val uninterpretableMass = 0.05
    val unrankedMass = 0.06
    val participantMasses = Vector(
      associationMass,
      intrusionMass,
      commentaryMass,
      sourceConsistentInferenceMass,
      uninterpretableMass
    )
    val allExternalMasses = participantMasses :+ unrankedMass
    val expectedAttributed = 0.15
    val expectedConflated = 0.21

    // Capacity to fail: every term is nonzero and distinct, and folding unranked into the
    // attributed result would observably change the expected value.
    assertEquals(allExternalMasses.distinct.size, 6)
    assert(allExternalMasses.forall(_ > 0.0))
    assertEqualsDouble(participantMasses.sum, expectedAttributed, eps)
    assertEqualsDouble(allExternalMasses.sum, expectedConflated, eps)
    assert(math.abs(expectedAttributed - expectedConflated) > eps)

    val signature = RecallSignature(
      uniformCoverage = 0.0,
      importanceWeightedCoverage = Estimate.missing(MissingReason.AllMissing),
      fidelityMass = MassRatio.of(0.0, 0.0, 0.0).fold(e => fail(e.message), identity),
      fidelityByFacet = Map.empty,
      specificity = None,
      compression = MassRatio.of(0.0, 0.0, 0.0).fold(e => fail(e.message), identity),
      discourseChronology = Some(0.0),
      worldChronology = None,
      causalPreservation = None,
      semanticFlowCoherence = MassRatio.of(0.0, 0.0, 0.0).fold(e => fail(e.message), identity),
      associationMass = associationMass,
      intrusionMass = intrusionMass,
      commentaryMass = commentaryMass,
      sourceConsistentInferenceMass = sourceConsistentInferenceMass,
      uninterpretableMass = uninterpretableMass,
      unrankedMass = unrankedMass,
      distortedMass = 0.0,
      distortedMassByFacet = Map.empty,
      backwardMass = Some(StepMass.of(0.0, 0, 1).fold(e => fail(e.message), identity)),
      worldBackwardMass = None,
      perUnitLocalizability = Map.empty,
      perUnitFidelity = Map.empty,
      perUnitMode = Map.empty
    )

    val actual = signature.externalMass
    assertEqualsDouble(actual.attributed, expectedAttributed, eps)
    assertEqualsDouble(actual.unranked, unrankedMass, eps)
    assertEqualsDouble(actual.rankedMass, 1.0 - unrankedMass, eps)
  }

  test("attributed mass and unranked mass are reported separately") {
    val r = report(0.2, 0.5)
    assertEqualsDouble(r.attributed, 0.2, eps)
    assertEqualsDouble(r.unranked, 0.5, eps)
  }

  test("two accounts with identical participant behaviour report the same attributed mass") {
    // The only difference is how much the aligner could rank. If unranked mass were folded in, the
    // harder-to-align account would look like it produced more external content, which is the
    // manufactured group difference this split exists to prevent.
    val easy = report(attributed = 0.2, unranked = 0.0)
    val hard = report(attributed = 0.2, unranked = 0.6)
    assertEqualsDouble(easy.attributed, hard.attributed, eps)
    assert(easy.unranked < hard.unranked)
    assert(easy.rankedMass > hard.rankedMass)
  }

  test("ranked mass is the coverage of the attributed number") {
    assertEqualsDouble(report(0.1, 0.0).rankedMass, 1.0, eps)
    assertEqualsDouble(report(0.1, 0.25).rankedMass, 0.75, eps)
    assertEqualsDouble(report(0.0, 1.0).rankedMass, 0.0, eps)
  }

  test("the render carries the caveat with the number, never the number alone") {
    val r = report(0.2, 0.6).render
    assert(r.contains("unranked"), r)
    assert(r.contains("ranked"), r)
  }

  test("there is no accessor that returns the conflated sum") {
    // A structural tripwire, not a comment: if a `total` (or any accessor handing back
    // attributed + unranked) is ever added, the number becomes quotable without its caveat again
    // and this assertion fails. Checked at compile time so it holds on every platform.
    //
    // Caveat, learned the hard way: a compile-time check only re-evaluates when THIS FILE is
    // recompiled. Adding `total` to signature.scala alone will not trip it in an incremental loop
    // (touching the file is not enough - sbt tracks content, not mtime). A clean build or any edit
    // here does. Verified by mutation: with `total` present and this file recompiled, the check
    // reports true and the test fails.
    assert(
      !scala.compiletime.testing.typeChecks("ExternalMassReport.of(0.1, 0.2).toOption.get.total"),
      "ExternalMassReport.total exists; it re-creates the conflation this type prevents"
    )
    assert(
      !scala.compiletime.testing
        .typeChecks("ExternalMassReport.of(0.1, 0.2).toOption.get.externalMass"),
      "an accessor named externalMass on the report would invite the same misreading"
    )
    // The control: the accessors that SHOULD exist do.
    assert(
      scala.compiletime.testing.typeChecks(
        "ExternalMassReport.of(0.1, 0.2).toOption.get.attributed"
      )
    )
    assert(
      scala.compiletime.testing.typeChecks("ExternalMassReport.of(0.1, 0.2).toOption.get.unranked")
    )
  }

  // --- the projection abstains instead of inventing (chief/scout estimand audit) ---

  test("a weight naming a component that does not exist is rejected at construction") {
    // Previously the typo survived construction and silently deleted that dimension of the score.
    SignatureProjection.of("v0", Map("uniformCoverge" -> 1.0)) match
      case Left(ProjectionError.UnknownComponent(n)) => assertEquals(n, "uniformCoverge")
      case other => fail(s"a typo was accepted as a projection: $other")
  }

  test("a weight set that cannot define a projection is refused") {
    // Empty weights used to produce Right(0.0): a scalar summary computed from nothing.
    assert(SignatureProjection.of("v0", Map.empty).isLeft, "empty weights accepted")
    assert(
      SignatureProjection.of("v0", Map("uniformCoverage" -> Double.NaN)).isLeft,
      "NaN weight accepted"
    )
    assert(
      SignatureProjection.of("v0", Map("uniformCoverage" -> Double.PositiveInfinity)).isLeft,
      "infinite weight accepted"
    )
    assert(SignatureProjection.of("", Map("uniformCoverage" -> 1.0)).isLeft, "empty version")
  }

  test("a weighted component with no measurement abstains, it does not score zero") {
    // Zero is the worst possible value for a coverage-like component and the best under a negative
    // weight, so substituting it turns 'not measured' into a substantive claim in either direction.
    // Deterministic, not `assume`: an assume turns into a green skip the moment the fixture gains
    // a world order, and a test that can silently stop running is not a guard. AnnaFixture's view
    // has no world order, so worldChronology is Missing by construction here.
    val name =
      if sig.worldChronology.isEmpty then "worldChronology"
      else if sig.causalPreservation.isEmpty then "causalPreservation"
      else if sig.backwardMass.isEmpty then "backwardMass"
      else fail("this fixture measures every component; the test needs one that is Missing")
    val p = SignatureProjection.of("v0", Map(name -> 1.0)).fold(e => fail(e.message), identity)
    p(sig) match
      case Left(ProjectionError.MissingComponent(n)) => assertEquals(n, name)
      case other => fail(s"a missing component was scored rather than abstained: $other")
  }

  test("a projection over present components still produces a number") {
    val p = SignatureProjection
      .of("v0", Map("uniformCoverage" -> 1.0, "intrusionMass" -> -1.0))
      .fold(e => fail(e.message), identity)
    // Pin the exact scalar: asserting only isRight lets any arithmetic mutation through.
    val expected = sig.uniformCoverage - sig.intrusionMass
    assertEqualsDouble(p(sig).fold(e => fail(e.message), _.value), expected, 1e-12)
  }

  test("a recall with no comparable transition has NO chronology, not a perfect one") {
    // The failure this replaces: `ordered(...)` already abstained when no step carried directional
    // mass, and the caller substituted 1.0 - PERFECT forward chronology - for a recall we could not
    // place at all. The best possible score, published from no evidence whatsoever.
    val transcript =
      storymodel4s.core.StorySource.fromText("nothing here.", Some("probe")).toOption.get
    val silent = storymodel4s.recall.RecallGraph(
      transcript,
      storymodel4s.core.SurfaceAnalyzer.analyze(transcript),
      Vector.empty,
      storymodel4s.recall.RecallRelations.empty
    )
    val proof = HsmmResult
      .validated(
        silent,
        view,
        Map.empty,
        AlignmentMatrix.of(Vector.empty).toOption.get,
        TransitionFlow(Vector.empty),
        Vector.empty,
        -1.0,
        Map.empty,
        0
      )
      .fold(e => fail(e.message), identity)
    val s = RecallSignature.compute(proof, silent, view)
    assertEquals(s.discourseChronology, None, "no eligible step must not report perfect chronology")
    assertEquals(s.backwardMass, None, "no eligible step must not report zero backward movement")
    assertEquals(s.worldBackwardMass, None)
  }

  test("per-step mass keeps every step in the denominator, comparable or not") {
    // The capacity gap the second review found. My first version used an EMPTY recall, which only
    // exercises the empty-vector branch: a mutant that renormalized onto the comparable steps
    // survived it, because in AnnaFixture every step happens to be comparable. This construction
    // makes the first unit external, so step 0 carries no source-to-source mass while the rest do -
    // the mixed case where renormalization and honest averaging give different answers.
    val real = GraphHsmm
      .infer(recall, view, candidates, costModel)
      .fold(e => fail(e.message), identity)
    val units = recall.ordered.map(_.id)
    assert(units.size >= 3, "this construction needs at least three units")
    val ext = AlignState.unranked
    // Keep the REAL route, which already contains a backward move, and make only the FIRST unit
    // external. Step 0 then carries no source-to-source mass while the rest do, so the route is
    // mixed and its backward mass is nonzero - the case where renormalizing onto the comparable
    // steps gives a different answer from averaging over the route.
    val realState = (u: storymodel4s.recall.RecallUnitId) =>
      real.posterior.row(u).flatMap(_.argmax).getOrElse(fail(s"no argmax for $u"))
    val rows = units.zipWithIndex.map { case (u, i) =>
      val st = if i == 0 then ext else realState(u)
      AlignmentRow.of(u, Map(st -> 1.0)).fold(e => fail(e.message), identity)
    }
    val flow = TransitionFlow(
      units.zip(units.tail).zipWithIndex.map { case ((a, b), i) =>
        val from = if i == 0 then ext else realState(a)
        FlowStep(a, b, Map((from, realState(b)) -> 1.0))
      }
    )
    val costs = units.zipWithIndex.map { case (u, i) =>
      val st = if i == 0 then ext else realState(u)
      u -> real.costs.getOrElse(u, Map.empty).filter { case (k, _) => k == st }
    }.toMap
    val mixed = HsmmResult
      .validated(
        recall,
        view,
        real.candidateAnchors,
        AlignmentMatrix.of(rows).fold(e => fail(e.message), identity),
        flow,
        rows.map(_.mass.head._1),
        real.logLikelihood,
        costs,
        0
      )
      .fold(e => fail(s"mixed construction rejected: ${e.message}"), identity)
    val s = RecallSignature.compute(mixed, recall, view)
    s.backwardMass match
      case None    => fail("a route with steps must report a per-step mass")
      case Some(m) =>
        assertEquals(m.totalSteps, units.size - 1, "denominator must be EVERY step")
        assert(m.comparableSteps < m.totalSteps, s"construction is not mixed: ${m.render}")
        assert(m.comparableSteps > 0, s"construction has no comparable step: ${m.render}")
        assert(m.perStep > 0.0, s"construction has no backward mass to compare: ${m.render}")
        // Every step in this construction carries exactly 1.0 of mass on a single state pair, so
        // the total backward mass is at most comparableSteps and the honest per-step mean is at
        // most comparableSteps/totalSteps. Renormalizing onto the comparable steps lifts it to at
        // most 1.0, which breaks this bound whenever some step is not comparable.
        //
        // The bound is deliberately NOT computed from m.perStep. An earlier version of this test
        // asserted perStep < perStep * total / comparable, which is invariant under the very
        // mutation it was meant to catch - the expectation must not come from the code under test.
        val bound = m.comparableSteps.toDouble / m.totalSteps
        assert(m.perStep <= bound + 1e-12, s"per-step mass was renormalized: ${m.render}")
  }

  // --- the report types cannot be constructed in invalid states (bd-01M161EEPDV8NMC932KA8AY0QC) ---

  test("an external mass report refuses masses that are not fractions of the whole") {
    assert(ExternalMassReport.of(-0.1, 0.2).isLeft, "negative attributed accepted")
    assert(ExternalMassReport.of(0.2, Double.NaN).isLeft, "NaN unranked accepted")
    assert(ExternalMassReport.of(1.2, 0.0).isLeft, "attributed above 1 accepted")
    // The pair is a split of the same whole, so together they cannot exceed it.
    assert(ExternalMassReport.of(0.7, 0.7).isLeft, "attributed + unranked above 1 accepted")
    assert(ExternalMassReport.of(0.4, 0.6).isRight)
  }

  test("a per-step mass refuses a comparable count that is not a sub-count of the total") {
    assert(StepMass.of(0.5, 4, 3).isLeft, "more comparable steps than steps accepted")
    assert(StepMass.of(0.5, -1, 3).isLeft, "negative comparable accepted")
    assert(StepMass.of(0.5, 0, 0).isLeft, "a route with no steps reported a per-step mass")
    assert(StepMass.of(Double.NaN, 1, 3).isLeft, "NaN per-step accepted")
    assert(StepMass.of(1.5, 1, 3).isLeft, "per-step above 1 accepted")
    assert(StepMass.of(0.5, 1, 3).isRight)
  }

  test("none of the report types derives a Mirror, so none can be forged past its constructor") {
    // The same forge closed on PlacementResolution: a private constructor does not suppress
    // Mirror.ProductOf, whose fromProduct rebuilds the value without consulting `of`.
    assert(
      !scala.compiletime.testing.typeChecks(
        "summon[scala.deriving.Mirror.ProductOf[ExternalMassReport]]"
      )
    )
    assert(
      !scala.compiletime.testing.typeChecks(
        "summon[scala.deriving.Mirror.ProductOf[StepMass]]"
      )
    )
  }

  // --- ratio-of-sums estimands (bd-01M162FEGPSY50MFHTYH3C3RHF) ---

  test("a mass ratio is None when there is no conditioning mass to divide by") {
    val nothing = MassRatio.of(0.0, 0.0, 5.0).fold(e => fail(e.message), identity)
    assertEquals(nothing.value, None, "a ratio with no denominator reported a number")
    assertEqualsDouble(nothing.support, 0.0, eps)
    assert(nothing.render.contains("n/a"), nothing.render)
  }

  test("support says what fraction of the whole the value rests on") {
    val r = MassRatio.of(1.0, 2.0, 8.0).fold(e => fail(e.message), identity)
    assertEquals(r.value, Some(0.5))
    assertEqualsDouble(r.support, 0.25, eps)
  }

  test("a mass ratio refuses sums that cannot be a ratio") {
    assert(MassRatio.of(3.0, 2.0, 8.0).isLeft, "numerator above its conditioning mass accepted")
    assert(MassRatio.of(1.0, 9.0, 8.0).isLeft, "conditioning above the total accepted")
    assert(MassRatio.of(-1.0, 2.0, 8.0).isLeft, "negative numerator accepted")
    assert(MassRatio.of(Double.NaN, 2.0, 8.0).isLeft, "NaN accepted")
    assert(
      !scala.compiletime.testing.typeChecks(
        "summon[scala.deriving.Mirror.ProductOf[MassRatio]]"
      )
    )
  }

  test("ratio-of-sums: a barely-placed unit cannot outvote a fully-placed one") {
    // This is the whole reason for the shape. Mean-of-ratios gives every unit one vote regardless
    // of the mass behind it, so a unit carrying 0.01 of source mass with an extreme per-unit ratio
    // moves the published figure as much as a unit carrying all of its mass.
    //
    // Two units: one fully placed contributing 0 of the quantity, one barely placed contributing
    // all of its 0.01. Ratio-of-sums = 0.01/1.01 ~ 0.0099. Mean-of-ratios would be (0 + 1)/2 = 0.5,
    // fifty times larger, driven entirely by a unit we hardly placed.
    val ratioOfSums = MassRatio.of(0.01, 1.01, 1.01).fold(e => fail(e.message), identity)
    assert(ratioOfSums.value.exists(_ < 0.02), ratioOfSums.render)
    val meanOfRatios = (0.0 + 1.0) / 2
    assert(
      ratioOfSums.value.exists(v => math.abs(v - meanOfRatios) > 0.4),
      s"ratio-of-sums collapsed onto mean-of-ratios: ${ratioOfSums.render}"
    )
  }

  test("compression conditions on SOURCE MASS, not on a count of units") {
    // The distinguishing assertion between ratio-of-sums and mean-of-ratios: the denominator is
    // the source mass actually placed, not the number of rows. Under mean-of-ratios the
    // conditioning mass would be the row count, which is what gives a 0.01-mass unit a full vote.
    val result = GraphHsmm
      .infer(recall, view, candidates, costModel)
      .fold(e => fail(e.message), identity)
    val s = RecallSignature.compute(result, recall, view)
    val sourceMassSum = result.posterior.rows.map(_.sourceMass).sum
    assertEqualsDouble(s.compression.conditioningMass, sourceMassSum, 1e-9)
    assertNotEquals(
      s.compression.conditioningMass,
      result.posterior.rows.size.toDouble,
      "conditioning mass equals the row count; that is mean-of-ratios"
    )
    val totalRowMass = result.posterior.rows.map(_.mass.values.sum).sum
    assertEqualsDouble(s.compression.totalMass, totalRowMass, 1e-9)
  }

  test("compression and coherence carry their conditioning mass, and abstain without it") {
    assertEquals(sig.compression.value.isDefined, sig.compression.conditioningMass > 0.0)
    assertEquals(
      sig.semanticFlowCoherence.value.isDefined,
      sig.semanticFlowCoherence.conditioningMass > 0.0
    )
    assert(sig.compression.support >= 0.0 && sig.compression.support <= 1.0 + eps)
  }

  test("the estimand version is derived from the code, never supplied by a caller") {
    assertEquals(sig.estimandVersion, RecallSignature.EstimandVersion)
    assert(sig.estimandVersion.startsWith("recall-signature/"), sig.estimandVersion)
    // A caller-set version would be an ungrounded assertion about what produced the numbers.
    assert(!scala.compiletime.testing.typeChecks("sig.copy(estimandVersion = \"forged\")"))
  }

  // --- mass-weighted fidelity over the anchor distribution ---

  test("fidelity conditions on assessed MASS, not on a count of units past a threshold") {
    // The distinguishing assertion, written before the abstention one this time. The old formula
    // admitted a unit only when sourceMass > externalMass - a cliff at 0.5 - and then counted its
    // MAP verdict at full weight. So its denominator was a COUNT of surviving units. The new one
    // conditions on the mass whose facets were actually specified, which is a continuous quantity
    // and cannot equal a unit count except by coincidence.
    val result = GraphHsmm
      .infer(recall, view, candidates, costModel)
      .fold(e => fail(e.message), identity)
    val s = RecallSignature.compute(result, recall, view)
    val units = result.posterior.rows.size.toDouble
    assertNotEquals(
      s.fidelityMass.conditioningMass,
      units,
      "fidelity conditions on a unit count; that is the cliff formula"
    )
    assert(s.fidelityMass.conditioningMass > 0.0, s.fidelityMass.render)
    // The mass it rests on cannot exceed the source mass it was drawn from.
    val sourceMass = result.posterior.rows.map(_.sourceMass).sum
    assert(s.fidelityMass.conditioningMass <= sourceMass + eps, s.fidelityMass.render)
    // Recompute the DENOMINATOR independently: the mass of every anchored state whose assessment
    // specified at least one facet. A different expression over the same inputs, so it pins the
    // mass-versus-count distinction exactly rather than by inequality.
    val expectedA = recall.ordered.map { u =>
      result.posterior
        .row(u.id)
        .map(
          _.mass.toVector
            .map { case (st, m) =>
              val specified = for
                ref <- st.anchor
                mode <- st.mode
                node <- view.node(ref)
              yield FidelityFacets.assess(u.proposition, node, mode).specified > 0
              if m > 0.0 && specified.contains(true) then m else 0.0
            }
            .sum
        )
        .getOrElse(0.0)
    }.sum
    assertEqualsDouble(s.fidelityMass.conditioningMass, expectedA, 1e-9)
    // The bare `fidelity: Option[Double]` twin is gone: a supported figure and an unsupported
    // copy of it side by side is the convenience leak ADR 0003 clause 2 forbids, and it is the
    // same thing externalMass prevents by refusing to expose a sum.
    assert(!scala.compiletime.testing.typeChecks("s.fidelity"))
  }

  test("every anchor of a split unit contributes, not only its MAP") {
    // A unit split 0.51/0.49 across two anchors used to be scored as though the first were
    // certain. Both anchors now contribute in proportion to their mass, so the assessed mass of a
    // split unit exceeds the mass of its MAP alone.
    val result = GraphHsmm
      .infer(recall, view, candidates, costModel)
      .fold(e => fail(e.message), identity)
    val split = result.posterior.rows.filter(_.mass.count { case (st, m) =>
      st.isSource && m > 0
    } > 1)
    assume(split.nonEmpty, "this fixture has no unit with mass on two anchors")
    val s = RecallSignature.compute(result, recall, view)
    val mapMassOnly =
      result.posterior.rows.flatMap(r => r.mass.filter(_._1.isSource).values.maxOption).sum
    assert(
      s.fidelityMass.conditioningMass > mapMassOnly * 0.5,
      s"assessed mass looks like MAP-only: ${s.fidelityMass.render}"
    )
  }

  test("an unspecified facet leaves the denominator rather than counting as wrong") {
    // UNPROVEN BY MUTATION, stated rather than implied: every anchored assessment in AnnaFixture
    // specifies all four facets, so the Unspecified branch is unreachable from this corpus and a
    // mutant that counts Unspecified into the denominator survives. What follows are bounds, not a
    // discrimination. Proving it needs a unit whose proposition omits a facet.
    // Unspecified means the recall did not commit to the facet. Counting it against the unit would
    // punish a participant for what they did not say.
    for (facet, ratio) <- sig.fidelityByFacet do
      assert(
        ratio.conditioningMass <= sig.fidelityMass.totalMass + eps,
        s"$facet: ${ratio.render}"
      )
      assert(ratio.value.forall(v => v >= 0.0 && v <= 1.0), s"$facet: ${ratio.render}")
  }

  test("a projection publishes the WEAKEST support among its components, and names it") {
    // A chain is no better supported than its thinnest link. compression rests on every unit's
    // source mass; coherence on source-to-source step mass. Whichever is thinner sets the figure,
    // and the reader is told which one so the number is actionable rather than merely qualified.
    val p = SignatureProjection
      .of("v0", Map("compression" -> 1.0, "semanticFlowCoherence" -> 1.0))
      .fold(e => fail(e.message), identity)
    val r = p(sig).fold(e => fail(e.message), identity)
    val expected = math.min(sig.compression.support, sig.semanticFlowCoherence.support)
    assertEqualsDouble(r.weakestSupport.getOrElse(fail(r.render)), expected, 1e-12)
    assert(
      r.weakestComponent.exists(Set("compression", "semanticFlowCoherence").contains),
      r.render
    )
    assertEquals(r.unsupportedComponents, Vector.empty, r.render)
  }

  test("weighted components with no support notion are named, not silently ignored") {
    // uniformCoverage is a bare Double from the ADR 0003 backlog. A minimum taken over only the
    // supported components would OVERSTATE what is known unless the others are visible.
    val p = SignatureProjection
      .of("v0", Map("uniformCoverage" -> 1.0, "compression" -> 1.0))
      .fold(e => fail(e.message), identity)
    val r = p(sig).fold(e => fail(e.message), identity)
    assertEquals(r.unsupportedComponents, Vector("uniformCoverage"), r.render)
    assertEqualsDouble(r.weakestSupport.getOrElse(fail(r.render)), sig.compression.support, 1e-12)
    assert(r.render.contains("carry no support"), r.render)
  }

  test("a weighted component with NO VALUE still refuses; low support is not the same failure") {
    // The distinction the bead's option (a) would have erased: dropping a Missing component
    // computes a different linear functional under the same name and weights.
    val name =
      if sig.worldChronology.isEmpty then "worldChronology"
      else if sig.causalPreservation.isEmpty then "causalPreservation"
      else fail("this fixture measures every component; the test needs one that is Missing")
    val p = SignatureProjection
      .of("v0", Map(name -> 1.0, "compression" -> 1.0))
      .fold(e => fail(e.message), identity)
    p(sig) match
      case Left(ProjectionError.MissingComponent(n)) => assertEquals(n, name)
      case other => fail(s"a Missing component was dropped rather than refused: $other")
  }
