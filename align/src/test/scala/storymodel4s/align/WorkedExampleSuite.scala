package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.*

/** Design record §13 reproduced as executable expectations, plus the adversarial foils of §14.4. */
class WorkedExampleSuite extends FunSuite:
  import AnnaFixture.*

  private lazy val result: HsmmResult = GraphHsmm.infer(recall, view, candidates, costModel)
  private lazy val p = result.posterior

  private def row(u: RecallUnit): AlignmentRow = p.row(u.id).get

  test("unit 1 anchors to event 1 or its scene (coarse anchor is fine)") {
    val r = row(u0)
    val mass = r.sourceMassOn(e1) + r.sourceMassOn(sc1)
    assert(mass > 0.6, s"e1+sc1 mass = $mass; row = ${r.topK(4)}")
  }

  test("'Stephen King' goes to the Association external state, not to a source node") {
    val r = row(u1)
    assert(r.sourceMass < 0.2, s"source mass = ${r.sourceMass}")
    assertEquals(r.argmax, Some(AlignState.External(ExternalState.Association)))
  }

  test("'finds somebody downstairs' anchors to event 5 with the patient unspecified") {
    val r = row(u2)
    assertEquals(r.mapSource, Some(e5))
    assert(r.sourceMassOn(e5) > 0.6, s"e5 mass = ${r.sourceMassOn(e5)}")
    val facets = FidelityFacets.assess(u2.proposition, view.node(e5).get)
    assertEquals(facets(Facet.Object), FacetVerdict.Unspecified)
    assertEquals(facets(Facet.Actor), FacetVerdict.Correct)
    assertEquals(facets(Facet.Action), FacetVerdict.Correct)
    assertEquals(facets(Facet.Location), FacetVerdict.Correct)
  }

  test("'before that there was some kind of noise' is hedged, anchors to event 2, backward move") {
    val r = row(u3)
    assert(u3.expressedUncertainty.isMarked)
    assertEquals(r.mapSource, Some(e2))
    val step = result.flow.steps(2)
    val backward = step.sourceMass((a, b) => view.relativePosition(b) < view.relativePosition(a))
    assert(backward > 0.5, s"backward mass = $backward")
    assert(r.localizability < 1.0)
  }

  test("Viterbi path matches the intended trajectory") {
    val path = result.viterbi
    assert(path(0) == AlignState.Source(e1) || path(0) == AlignState.Source(sc1), path(0).toString)
    assertEquals(path(1), AlignState.External(ExternalState.Association))
    assertEquals(path(2), AlignState.Source(e5))
    assertEquals(path(3), AlignState.Source(e2))
  }

  test("flow marginals agree with the posterior rows") {
    result.flow.steps.zipWithIndex.foreach { case (step, i) =>
      val from = step.fromMarginal
      val to = step.toMarginal
      p.rows(i).mass.foreach { case (s, m) => assertEqualsDouble(from.getOrElse(s, 0.0), m, 1e-9) }
      p.rows(i + 1).mass.foreach { case (s, m) =>
        assertEqualsDouble(to.getOrElse(s, 0.0), m, 1e-9)
      }
    }
  }

  private def foilResult(f: Foil): HsmmResult =
    GraphHsmm.infer(f.recall, view, f.candidates, f.costModel)

  test("role-reversed paraphrase is gated: external beats every source candidate") {
    val r = foilResult(roleReversed).posterior.rows.head
    assert(r.externalMass > r.sourceMass, s"row = ${r.topK(5)}")
    val breakdown = foilResult(roleReversed).costs(roleReversed.unit.id)(AlignState.Source(e5))
    assert(breakdown.contradictions.contains(Contradiction.RoleReversal))
    assert(breakdown.gated)
  }

  test("negated paraphrase is gated by polarity conflict") {
    val res = foilResult(negated)
    val r = res.posterior.rows.head
    assert(r.externalMass > r.sourceMass, s"row = ${r.topK(5)}")
    val breakdown = res.costs(negated.unit.id)(AlignState.Source(e5))
    assert(breakdown.contradictions.contains(Contradiction.PolarityConflict))
  }

  test("a blended unit shows bimodal mass over the two blended events") {
    val r = foilResult(blended).posterior.rows.head
    assert(r.sourceMassOn(e2) > 0.25, s"e2 = ${r.sourceMassOn(e2)}; row = ${r.topK(5)}")
    assert(r.sourceMassOn(e5) > 0.25, s"e5 = ${r.sourceMassOn(e5)}; row = ${r.topK(5)}")
  }

  test("a summary lands on the scene, not a leaf") {
    val r = foilResult(summary).posterior.rows.head
    assertEquals(r.mapSource, Some(sc2), r.topK(5).toString)
  }

  test("ablation: the embedding-only transport baseline accepts the foils the HSMM rejects") {
    Vector(roleReversed, negated).foreach { f =>
      val baseline = BaselineAligner.align(f.recall, view, f.candidates, f.semantic)
      val hsmm = foilResult(f).posterior
      val b = baseline.rows.head.sourceMassOn(e5)
      val h = hsmm.rows.head.sourceMassOn(e5)
      assert(b > 0.5, s"${f.name}: baseline e5 mass = $b")
      assert(h < b, s"${f.name}: hsmm e5 mass $h should be below baseline $b")
    }
  }

  test("recall signature decomposes the outcome") {
    val sig = RecallSignature.compute(result, recall, view)
    assert(sig.associationMass > 0.15, s"association = ${sig.associationMass}")
    assert(sig.intrusionMass < 0.15, s"intrusion = ${sig.intrusionMass}")
    assert(
      sig.uniformCoverage > 0.25 && sig.uniformCoverage < 0.85,
      s"coverage = ${sig.uniformCoverage}"
    )
    assert(sig.backwardMass > 0.1, s"backward = ${sig.backwardMass}")
    assert(sig.discourseChronology < 1.0)
    assert(sig.fidelity.exists(_ > 0.5), s"fidelity = ${sig.fidelity}")
    assert(sig.perUnitFidelity.contains(u2.id))
    val scalar =
      SignatureProjection("v0", Map("uniformCoverage" -> 1.0, "intrusionMass" -> -1.0))(sig)
    assert(scalar > 0.0)
  }

  test("relation preservation: the recalled 'before' is preserved in source world time") {
    val diag = RelationPreservation.diagnostic(p, recall, view)
    assert(diag(RelationLayer.WorldTime) > 0.5, diag.toString)
  }

  test("refinement passes keep the anchors and do not lower the temporal anchor") {
    val refined =
      GraphHsmm.infer(recall, view, candidates, costModel, HsmmConfig(refinementPasses = 2))
    val before = row(u3).sourceMassOn(e2)
    val after = refined.posterior.row(u3.id).get.sourceMassOn(e2)
    assert(after >= before - 1e-9, s"before = $before after = $after")
    assertEquals(refined.posterior.row(u2.id).get.mapSource, Some(e5))
  }

  test("support density: a sharp anchor is narrow, an external unit carries little mass") {
    val dens = SupportDensity.discourse(p, view)
    val d2 = dens.find(_.unit == u2.id).get
    val d1 = dens.find(_.unit == u1.id).get
    assert(d1.mass < 0.2)
    assert(d2.width < 0.15, s"width = ${d2.width}")
    assert(SupportDensity.worldTime(p, view).nonEmpty)
  }
