package storymodel4s.align

import munit.FunSuite

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
    ExternalMassReport(attributed, unranked)

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
      importanceWeightedCoverage = 0.0,
      fidelity = None,
      specificity = None,
      compression = 0.0,
      discourseChronology = 0.0,
      worldChronology = None,
      causalPreservation = None,
      semanticFlowCoherence = 0.0,
      associationMass = associationMass,
      intrusionMass = intrusionMass,
      commentaryMass = commentaryMass,
      sourceConsistentInferenceMass = sourceConsistentInferenceMass,
      uninterpretableMass = uninterpretableMass,
      unrankedMass = unrankedMass,
      distortedMass = 0.0,
      distortedMassByFacet = Map.empty,
      backwardMass = 0.0,
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
      !scala.compiletime.testing.typeChecks("ExternalMassReport(0.1, 0.2).total"),
      "ExternalMassReport.total exists; it re-creates the conflation this type prevents"
    )
    assert(
      !scala.compiletime.testing.typeChecks("ExternalMassReport(0.1, 0.2).externalMass"),
      "an accessor named externalMass on the report would invite the same misreading"
    )
    // The control: the accessors that SHOULD exist do.
    assert(scala.compiletime.testing.typeChecks("ExternalMassReport(0.1, 0.2).attributed"))
    assert(scala.compiletime.testing.typeChecks("ExternalMassReport(0.1, 0.2).unranked"))
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
    assertEqualsDouble(p(sig).fold(e => fail(e.message), identity), expected, 1e-12)
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
