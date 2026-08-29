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
    // The base view now states its importances EXPLICITLY. They used to arrive from a default of
    // observed(1.0), which asserted maximal salience for every node without anyone measuring it -
    // and made importance-weighted coverage identical to uniform coverage in every production run.
    val weighted = InMemorySourceView(
      view.nodes.map(_.copy(importance = Estimate.observed(1.0))),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val sig = RecallSignature.compute(result, recall, weighted)
    val missingE3 = InMemorySourceView(
      weighted.nodes.map(n =>
        if n.ref == e3 then n.copy(importance = Estimate.missing(MissingReason.ProviderAbstained))
        else n
      ),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val zeroE3 = InMemorySourceView(
      weighted.nodes.map(n =>
        if n.ref == e3 then n.copy(importance = Estimate.observed(0.0)) else n
      ),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    val sigMissing = RecallSignature.compute(result, recall, missingE3)
    val sigZero = RecallSignature.compute(result, recall, zeroE3)
    assertEqualsDouble(sigMissing.uniformCoverage, sig.uniformCoverage, 1e-12)
    // e3 is not recalled, so dropping it from the weighted average raises weighted coverage;
    // weighting it zero does the same thing here, but for a different reason (documented).
    assert(
      sigMissing.importanceWeightedCoverage.estimate.toOption
        .zip(sig.importanceWeightedCoverage.estimate.toOption)
        .exists((m, b) => m >= b - 1e-12),
      s"${sigMissing.importanceWeightedCoverage} vs ${sig.importanceWeightedCoverage}"
    )
    assertEquals(
      sigMissing.importanceWeightedCoverage.estimate.toOption,
      sigZero.importanceWeightedCoverage.estimate.toOption
    )
    assertEquals(sigMissing.importanceWeightedCoverage.coverage.observed, 4)
    assertEquals(sigZero.importanceWeightedCoverage.coverage.observed, 5)
    assertEqualsDouble(
      sigMissing.importanceWeightedCoverage.conditioningWeight,
      sigZero.importanceWeightedCoverage.conditioningWeight,
      1e-12
    )

    val projection = SignatureProjection
      .of("weighted-coverage-only", Map("importanceWeightedCoverage" -> 1.0))
      .fold(e => fail(e.message), identity)
    val projected = projection(sig).fold(e => fail(e.message), identity)
    assertEqualsDouble(
      projected.weakestSupport.getOrElse(fail(projected.render)),
      sig.importanceWeightedCoverage.support,
      1e-12
    )
    assertEquals(projected.weakestComponent, Some("importanceWeightedCoverage"))
    assertEquals(projected.unsupportedComponents, Vector.empty)
  }

  test("with no importances supplied, weighted coverage ABSTAINS rather than duplicating uniform") {
    // In every production run StorySourceView supplies no importances, so this was the real
    // behaviour: two named fields that were the same number, which a reader takes for two
    // measurements.
    val sig = RecallSignature.compute(result, recall, view)
    assertEquals(
      sig.importanceWeightedCoverage.estimate,
      Estimate.missing(MissingReason.AllMissing),
      "weighted coverage misclassified absent importances"
    )
    assertEquals(
      sig.importanceWeightedCoverage.estimate.toOption,
      None,
      "weighted coverage reported a figure with no importances behind it"
    )
    assertEquals(sig.importanceWeightedCoverage.coverage.eligible, view.leaves.size)
    assertEquals(sig.importanceWeightedCoverage.coverage.observed, 0)
    assertEqualsDouble(sig.importanceWeightedCoverage.conditioningWeight, 0.0, 1e-12)
    assert(sig.uniformCoverage > 0.0, "uniform coverage is still measured and reported")
  }

  test("weight conditioning and numeric value do not erase leaf-count coverage") {
    val visitation = RecallSignature.leafVisitation(result.posterior, view)
    val ordered = visitation.toVector.sortBy(_._2)
    val (lowRef, low) = ordered.head
    val (highRef, high) = ordered.last
    val (middleRef, middle) = ordered
      .find { case (_, value) => value > low + 1e-12 && value < high - 1e-12 }
      .getOrElse(fail(s"fixture needs three distinct visitation values: $ordered"))
    val lowWeight = (high - middle) / (high - low)
    val highWeight = (middle - low) / (high - low)
    assert(lowWeight > 0.0 && highWeight > 0.0)
    assertEqualsDouble(lowWeight + highWeight, 1.0, 1e-12)

    def weightedView(weights: Map[SourceNodeRef, Double]): InMemorySourceView =
      InMemorySourceView(
        view.nodes.map(n =>
          n.copy(importance =
            weights
              .get(n.ref)
              .fold[ScoreEstimate](Estimate.missing(MissingReason.ProviderAbstained))(
                Estimate.observed
              )
          )
        ),
        view.edges,
        view.worldOrder,
        view.textLength
      )

    val oneHeavy = RecallSignature
      .compute(
        result,
        recall,
        weightedView(Map(middleRef -> 1.0))
      )
      .importanceWeightedCoverage
    val twoLight = RecallSignature
      .compute(
        result,
        recall,
        weightedView(Map(lowRef -> lowWeight, highRef -> highWeight))
      )
      .importanceWeightedCoverage

    assertEquals(oneHeavy.coverage.eligible, twoLight.coverage.eligible)
    assertEquals(oneHeavy.coverage.observed, 1)
    assertEquals(twoLight.coverage.observed, 2)
    assertEqualsDouble(oneHeavy.conditioningWeight, 1.0, 1e-12)
    assertEqualsDouble(twoLight.conditioningWeight, 1.0, 1e-12)
    assertEqualsDouble(oneHeavy.estimate.toOption.getOrElse(fail(oneHeavy.render)), middle, 1e-12)
    assertEqualsDouble(
      twoLight.estimate.toOption.getOrElse(fail(twoLight.render)),
      middle,
      1e-12
    )
    assertNotEquals(oneHeavy.coverage, twoLight.coverage)
  }

  test("RecallSignature refuses a non-finite observed importance") {
    val malformed = InMemorySourceView(
      view.nodes.map(n =>
        n.copy(importance =
          if n.ref == e1 then Estimate.observed(Double.NaN)
          else Estimate.missing(MissingReason.ProviderAbstained)
        )
      ),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    intercept[IllegalArgumentException](RecallSignature.compute(result, recall, malformed))
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
