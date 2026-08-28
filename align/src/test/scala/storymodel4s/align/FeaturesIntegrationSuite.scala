package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.*

/** The `features` contract inside alignment: missing importance is excluded (never zero), an
  * abstaining semantic provider is neutral rather than decisive, and support densities materialize
  * as aligned tracks with coverage.
  */
class FeaturesIntegrationSuite extends FunSuite:
  import AnnaFixture.*

  private lazy val result: HsmmResult =
    GraphHsmm.infer(recall, view, candidates, costModel).fold(e => fail(e.message), identity)

  test("missing importance excludes a leaf from the weighted coverage instead of weighting it 0") {
    val sig = RecallSignature.compute(result, recall, view)
    val missingE3 = InMemorySourceView(
      view.nodes.map(n =>
        if n.ref == e3 then n.copy(importance = Estimate.missing(MissingReason.ProviderAbstained))
        else n
      ),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val zeroE3 = InMemorySourceView(
      view.nodes.map(n => if n.ref == e3 then n.copy(importance = Estimate.observed(0.0)) else n),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val sigMissing = RecallSignature.compute(result, recall, missingE3)
    val sigZero = RecallSignature.compute(result, recall, zeroE3)
    assertEqualsDouble(sigMissing.uniformCoverage, sig.uniformCoverage, 1e-12)
    // e3 is not recalled, so dropping it from the weighted average raises weighted coverage;
    // weighting it zero does the same thing here, but for a different reason (documented).
    assert(sigMissing.importanceWeightedCoverage >= sig.importanceWeightedCoverage - 1e-12)
    assertEqualsDouble(
      sigMissing.importanceWeightedCoverage,
      sigZero.importanceWeightedCoverage,
      1e-12
    )
  }

  test("an abstaining semantic provider is neutral: candidates come from lexical overlap") {
    val abstain = SemanticDistance.fromTableOrAbstain(Map.empty)
    val cands = CandidateGenerator(abstain, perLevel = 3).generate(recall.ordered, view)
    // dense ranking skipped, lexical hits remain; the unit is not "unranked"
    assert(cands(u2.id).nonEmpty, cands.toString)
    assert(!cands.abstained(u2.id))
    val model = DefaultLocalCostModel(semantic = abstain, missingSemantic = 0.5)
    val breakdown = model.cost(u2, view.node(e5).get, FidelityMode.Faithful, view)
    assertEqualsDouble(breakdown.term(CostTerm.Semantic), 0.5, 1e-12)
  }

  test("support densities materialize as window tracks with coverage") {
    val densities = SupportDensity.discourse(result.posterior, view, grid = 10)
    val provenance = TrackProvenance(
      Provenance.deterministic("test", Checksum.ofText("anna")),
      Some(source.canonicalChecksum)
    )
    val tracks = SupportDensity.tracks(densities, result.posterior, tokenCount = 40, provenance)
    assertEquals(tracks.size, densities.size)
    tracks.zip(densities).foreach { (t, d) =>
      assertEquals(t.observations.size, 10)
      assert(t.observations.forall(_.coverage.exists(c => c.observed <= c.eligible)))
      assert(t.derivation.nonEmpty)
      val mass = t.observations.flatMap(_.estimate.toOption).sum
      assertEqualsDouble(mass, d.mass, 1e-9)
      val ranges = t.observations.map(_.target.range)
      assert(ranges.zip(ranges.drop(1)).forall((a, b) => a.endExclusive.value == b.start.value))
    }
  }
