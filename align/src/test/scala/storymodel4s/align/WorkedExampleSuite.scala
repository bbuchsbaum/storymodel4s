package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.*

/** Design record §13 reproduced as executable expectations, plus the adversarial foils of §14.4. */
class WorkedExampleSuite extends FunSuite:
  import AnnaFixture.*

  private def infer(
      r: RecallGraph,
      c: Candidates,
      m: LocalCostModel,
      cfg: HsmmConfig = HsmmConfig.default
  ): HsmmResult =
    GraphHsmm.infer(r, view, c, m, cfg).fold(e => fail(e.message), identity)

  private lazy val result: HsmmResult = infer(recall, candidates, costModel)
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
    assertEquals(facets(Facet.Context), FacetVerdict.Correct)
  }

  test("'before that there was some kind of noise' is hedged, anchors to event 2, backward move") {
    val r = row(u3)
    assert(u3.expressedUncertainty.isMarked)
    assertEquals(r.mapSource, Some(e2))
    val step = result.flow.steps(2)
    val backward = step.sourceMass((a, b) => view.relativePosition(b) < view.relativePosition(a))
    assert(backward > 0.5, s"backward mass = $backward")
    assert(r.localizability(view.sourceNodeCount).exists(_ < 1.0))
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

  private def foilResult(f: Foil): HsmmResult = infer(f.recall, f.candidates, f.costModel)

  test(
    "role-reversed paraphrase is anchored to event 5 as Distorted(RoleReversal), never Faithful"
  ) {
    val res = foilResult(roleReversed)
    val r = res.posterior.rows.head
    val adm = res.admissibility(roleReversed.unit.id)(e5)
    assert(adm.contradictions.contains(Contradiction.RoleReversal), adm.toString)
    assert(adm.gated && !adm.faithful)
    assertEquals(adm.facets, Set(Facet.RoleReversal))
    // the event is still recalled: distortion is anchored recall, not omission + intrusion
    assertEqualsDouble(r.faithfulMassOn(e5), 0.0, 0.0)
    assert(r.distortedMassOn(e5) > 0.5, s"row = ${r.topK(5)}")
    assert(r.sourceMass > r.externalMass, s"row = ${r.topK(5)}")
    assertEquals(r.mapSource, Some(e5))
    assertEquals(r.mapMode.map(_.facetSet), Some(Set(Facet.RoleReversal)))
    assert(!res.costs(roleReversed.unit.id).contains(AlignState.Source(e5)))
    val sig = RecallSignature.compute(res, roleReversed.recall, view)
    assert(sig.distortedMassByFacet.getOrElse(Facet.RoleReversal, 0.0) > 0.5, sig.toString)
    assert(sig.perUnitFidelity(roleReversed.unit.id)(Facet.RoleReversal) == FacetVerdict.Wrong)
    assert(sig.intrusionMass < 0.3, sig.toString)
  }

  test("negated paraphrase is anchored as Distorted(Polarity), never Faithful") {
    val res = foilResult(negated)
    val r = res.posterior.rows.head
    val adm = res.admissibility(negated.unit.id)(e5)
    assert(adm.contradictions.contains(Contradiction.PolarityConflict))
    assertEquals(adm.facets, Set(Facet.Polarity))
    assertEqualsDouble(r.faithfulMassOn(e5), 0.0, 0.0)
    assert(r.distortedMassOn(e5) > 0.5, s"row = ${r.topK(5)}")
    assert(r.sourceMass > r.externalMass, s"row = ${r.topK(5)}")
  }

  test("the mode gate is exclusion of the faithful mode, whatever the fan-out") {
    // every leaf shares the recalled predicate; the sketch contradicts all of them by polarity
    val allFind = InMemorySourceView(
      view.nodes.map(n => if n.isLeaf then n.copy(predicate = Some("find")) else n),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val sem = SemanticDistance.of((_, _) => 0.1)
    val cands = CandidateGenerator(sem, perLevel = 10).generate(negated.recall.ordered, allFind)
    assert(cands(negated.unit.id).size >= 5, cands.toString)
    val res = GraphHsmm
      .infer(negated.recall, allFind, cands, DefaultLocalCostModel(semantic = sem))
      .fold(e => fail(e.message), identity)
    val r = res.posterior.rows.head
    assertEqualsDouble(r.faithfulMass, 0.0, 0.0)
    assert(r.distortedMass > 0.0)
    assertEqualsDouble(r.sourceMass + r.externalMass, 1.0, 1e-9)
    // and the ungated ablation is a different type that cannot feed a signature
    val abl = GraphHsmm
      .ablationUngated(negated.recall, allFind, cands, DefaultLocalCostModel(semantic = sem))
      .fold(e => fail(e.message), identity)
    assert(abl.massOn(negated.unit.id, AlignState.Source(e5)) > 0.0)
  }

  test("a scene is a gist target unless every engaged leaf under it is contradicted") {
    // sc2 contains e3, e4, e5. In the Anna source only e5 shares the recalled predicate "find"
    // and it is contradicted by "she didn't find anyone", so the scene's faithful mode is refused
    // (its only engaged leaf is contradicted). Give e4 a compatible "find" reading too — the
    // reviewer's "he did not go … he went" scene — and the scene must stay faithful.
    val onlyE5 = ModeGate.assess(negated.unit, view.node(sc2).get, view)
    assert(onlyE5.gated, onlyE5.toString)
    assertEquals(onlyE5.facets, Set(Facet.Polarity))
    val withCompatibleSibling = InMemorySourceView(
      view.nodes.map(n =>
        if n.ref == e4 then n.copy(predicate = Some("find"), polarity = PolarityTag.Negative) else n
      ),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val b =
      ModeGate.assess(negated.unit, withCompatibleSibling.node(sc2).get, withCompatibleSibling)
    assert(!b.gated, b.toString)
    assertEquals(b.modes, Vector(FidelityMode.Faithful))
  }

  test(
    "recalling reported content as fact anchors as Distorted(Context), never an external state"
  ) {
    val speechE5 = InMemorySourceView(
      view.nodes.map(n => if n.ref == e5 then n.copy(context = ContextTag.Speech) else n),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val adm = ModeGate.assess(u2, speechE5.node(e5).get, speechE5)
    assert(adm.contradictions.contains(Contradiction.ContextConflict))
    assertEquals(adm.facets, Set(Facet.Context))
    val model = DefaultLocalCostModel(semantic = semantic)
    val cands = CandidateGenerator(semantic, perLevel = 2).generate(recall.ordered, speechE5)
    val res = GraphHsmm.infer(recall, speechE5, cands, model).fold(e => fail(e.message), identity)
    val r = res.posterior.row(u2.id).get
    assertEquals(r.mapSource, Some(e5))
    assertEqualsDouble(r.faithfulMassOn(e5), 0.0, 0.0)
    assert(r.distortedMassOn(e5) > 0.5, r.topK(5).toString)
    val facets = FidelityFacets.assess(u2.proposition, speechE5.node(e5).get, r.mapMode.get)
    assertEquals(facets(Facet.Context), FacetVerdict.Wrong)
    assertEquals(facets(Facet.Action), FacetVerdict.Correct)
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
      val baseline = BaselineAligner
        .align(f.recall, view, f.candidates, f.semantic)
        .fold(e => fail(e.message), identity)
      val hsmm = foilResult(f).posterior
      val b = baseline.rows.head.faithfulMassOn(e5)
      val h = hsmm.rows.head.faithfulMassOn(e5)
      assert(b > 0.5, s"${f.name}: baseline faithful e5 mass = $b")
      assertEqualsDouble(h, 0.0, 0.0)
      assert(hsmm.rows.head.distortedMassOn(e5) > 0.0, s"${f.name}: distorted anchor expected")
    }
  }

  test("an unrankable unit goes to Unranked, never to Intrusion") {
    val text = "Zxqv plorth wibble."
    val src = StorySource.fromText(text).toOption.get
    val at = SurfaceAnalyzer.analyze(src)
    val s = at.sentences.head
    val u = RecallUnit(
      RecallUnitId.unsafe("unrankable"),
      0,
      SpanSet.one(SpanRef(Some(s.id), s.span)),
      text,
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty.copy(lemmas = Set("zxqv", "plorth", "wibble")),
      None
    )
    val rg = RecallGraph(src, at, Vector(u), RecallRelations.empty)
    val cands = CandidateGenerator(SemanticDistance.abstaining).generate(Vector(u), view)
    assert(cands.abstained(u.id))
    val res = GraphHsmm
      .infer(rg, view, cands, DefaultLocalCostModel(semantic = SemanticDistance.abstaining))
      .fold(e => fail(e.message), identity)
    val r = res.posterior.rows.head
    assertEqualsDouble(r.externalMass(ExternalState.Unranked), 1.0, 1e-9)
    assertEqualsDouble(r.externalMass(ExternalState.Intrusion), 0.0, 0.0)
    val sig = RecallSignature.compute(res, rg, view)
    assertEqualsDouble(sig.unrankedMass, 1.0, 1e-9)
    assertEqualsDouble(sig.intrusionMass, 0.0, 0.0)
    assertEquals(sig.specificity, None)
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
    assert(sig.worldBackwardMass.exists(_ > 0.1), s"world backward = ${sig.worldBackwardMass}")
    assert(sig.discourseChronology < 1.0)
    assert(sig.fidelity.exists(_ > 0.5), s"fidelity = ${sig.fidelity}")
    assert(sig.perUnitFidelity.contains(u2.id))
    assert(sig.specificity.exists(s => s > 0.0 && s <= 1.0), sig.specificity.toString)
    assertEqualsDouble(sig.unrankedMass, 0.0, 0.0)
    val scalar =
      SignatureProjection("v0", Map("uniformCoverage" -> 1.0, "intrusionMass" -> -1.0))(sig)
    assert(scalar > 0.0)
  }

  test("causal preservation needs two distinct recalled units linked by a recall causal edge") {
    // e4 → e5 is a source causal edge; recalled by u2 (e5) alone: not preserved
    val sig = RecallSignature.compute(result, recall, view)
    assert(sig.causalPreservation.forall(_ == 0.0), sig.causalPreservation.toString)
    // add a unit anchoring e4 and a recall causal edge u4 → u2
    val extraText = recallText + " She went down to the cellar."
    val src = StorySource.fromText(extraText).toOption.get
    val at = SurfaceAnalyzer.analyze(src)
    val s4 = at.sentences(4)
    val u4 = RecallUnit(
      RecallUnitId.unsafe("u4"),
      4,
      SpanSet.one(SpanRef(Some(s4.id), s4.span)),
      at.text(s4),
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch(
        Some("enter"),
        Vector(she),
        PolarityTag.Positive,
        ModalityTag.Asserted,
        Vector("cellar"),
        Vector.empty,
        Vector.empty,
        Set("go", "down", "cellar", "enter")
      ),
      None
    )
    val rg = RecallGraph(
      src,
      at,
      Vector(u0, u1, u2, u3, u4),
      RecallRelations(
        Vector(RecallTemporalEdge(u3.id, RecallTemporalRelation.Before, u2.id, None)),
        Vector(RecallCausalEdge(u4.id, u2.id, None)),
        Vector.empty,
        Vector.empty
      )
    )
    val sem = SemanticDistance.fromTable(table ++ Map((u4.id, e4) -> 0.1, (u4.id, sc2) -> 0.4))
    val cands = CandidateGenerator(sem, perLevel = 2).generate(rg.ordered, view)
    val res = GraphHsmm
      .infer(rg, view, cands, DefaultLocalCostModel(semantic = sem))
      .fold(e => fail(e.message), identity)
    val sig2 = RecallSignature.compute(res, rg, view)
    assert(sig2.causalPreservation.exists(_ > 0.0), sig2.causalPreservation.toString)
  }

  test("relation preservation: the recalled 'before' is preserved in source world time") {
    val diag = RelationPreservation.diagnostic(p, recall, view)
    assert(diag(RelationLayer.WorldTime) > 0.5, diag.toString)
  }

  test("refinement passes keep the anchors, never resurrect a refused mode, and drive Viterbi") {
    val refined = infer(recall, candidates, costModel, HsmmConfig.unsafe(refinementPasses = 2))
    val before = row(u3).sourceMassOn(e2)
    val after = refined.posterior.row(u3.id).get.sourceMassOn(e2)
    assert(after >= before - 1e-9, s"before = $before after = $after")
    assertEquals(refined.posterior.row(u2.id).get.mapSource, Some(e5))
    assertEquals(refined.refinementPasses, 2)
    assertEquals(refined.viterbi, refined.posterior.rows.map(_.argmax.get))
    val gatedRefined =
      infer(
        negated.recall,
        negated.candidates,
        negated.costModel,
        HsmmConfig.unsafe(refinementPasses = 3)
      )
    assertEqualsDouble(gatedRefined.posterior.rows.head.faithfulMassOn(e5), 0.0, 0.0)
    assert(gatedRefined.posterior.rows.head.distortedMassOn(e5) > 0.0)
  }

  test("configs are validated, not asserted") {
    assert(HsmmConfig.of(temperature = 0.0).isLeft)
    assert(HsmmConfig.of(refinementPasses = -1).isLeft)
    assert(CostWeights.of(1, -1, 0, 0, 0, 0).isLeft)
    assert(CostWeights.of(1, Double.NaN, 0, 0, 0, 0).isLeft)
    val empty = RecallGraph(transcript, rAtlas, Vector.empty, RecallRelations.empty)
    assertEquals(GraphHsmm.infer(empty, view, candidates, costModel), Left(AlignError.EmptyRecall))
  }

  test("support density: a sharp anchor is narrow, a summary is wide, an external unit is light") {
    val dens = SupportDensity.discourse(p, view)
    val d2 = dens.find(_.unit == u2.id).get
    val d1 = dens.find(_.unit == u1.id).get
    assert(d1.mass < 0.2)
    assert(d2.width < 0.15, s"width = ${d2.width}")
    assertEqualsDouble(d2.mass, row(u2).sourceMass, 1e-6)
    val sum = foilResult(summary)
    val ds = SupportDensity.discourse(sum.posterior, view).head
    assert(ds.width > d2.width, s"summary width ${ds.width} vs leaf width ${d2.width}")
    val wt = SupportDensity.worldTime(p, view)
    assert(wt.nonEmpty)
    wt.get.zip(p.rows).foreach((d, r) => assertEqualsDouble(d.mass, r.sourceMass, 1e-6))
  }
