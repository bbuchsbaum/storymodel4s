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

  test("a weight naming a component that does not exist is an error, not a dropped term") {
    val typo = SignatureProjection("v0", Map("uniformCoverge" -> 1.0))
    typo(sig) match
      case Left(ProjectionError.UnknownComponent(n)) => assertEquals(n, "uniformCoverge")
      case other => fail(s"a typo silently dropped a whole dimension: $other")
  }

  test("a weighted component with no measurement abstains, it does not score zero") {
    // Zero is the worst possible value for a coverage-like component and the best under a negative
    // weight, so substituting it turns 'not measured' into a substantive claim in either direction.
    assume(sig.worldChronology.isEmpty || sig.causalPreservation.isEmpty, "fixture has no gap")
    val name = if sig.worldChronology.isEmpty then "worldChronology" else "causalPreservation"
    SignatureProjection("v0", Map(name -> 1.0))(sig) match
      case Left(ProjectionError.MissingComponent(n)) => assertEquals(n, name)
      case other => fail(s"a missing component was scored rather than abstained: $other")
  }

  test("a projection over present components still produces a number") {
    val p = SignatureProjection("v0", Map("uniformCoverage" -> 1.0, "intrusionMass" -> -1.0))
    assert(p(sig).isRight, p(sig).toString)
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
