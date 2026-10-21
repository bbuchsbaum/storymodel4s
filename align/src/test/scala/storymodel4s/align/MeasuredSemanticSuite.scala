package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.Estimate
import storymodel4s.recall.*

/** Literal oracles separate unavailable evidence, nuisance priors and conditional localization. */
class MeasuredSemanticSuite extends FunSuite:
  import ReferenceFixture.*
  private def model(channel: StrictSemanticChannel, external: Double = 1.0): StrictCostModel =
    right(
      StrictCostModel.of(
        channel,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none,
        externalFloor = external,
        externalMismatch = 0.0,
        distortionPenalty = 0.0,
        contentPolicy = ContentCostPolicy.MeasuredSemanticV1
      )
    )
  private def measured(channel: StrictSemanticChannel, external: Double = 1.0): LocalEvidence =
    val candidates = right(
      StrictCandidateGenerator.canonicalLevel(channel, config(), recall, view, 0)
    )
    right(LocalEvidence.compute(recall, view, candidates, model(channel, external)))
  private val conditional = right(
    LocalReference.Profile.sourceTargets(profile().universe, 1.0 / math.log(2.0))
  )

  test("measured semantic cost has exactly one measured basis and no placeholder support") {
    val price = right(
      model(table(distance = (_, _) => 0.25))
        .cost(unit, view.nodes.head, FidelityMode.Faithful, view)
    )
    assertEquals(price.total, 0.25)
    assertEquals(price.terms, Map(CostTerm.Semantic -> 0.25))
    assertEquals(price.missingTerms, Set.empty[CostTerm])
    assertEquals(price.imputedTerms.size, 0)
    val basis = price.support.asInstanceOf[SupportAssessment.Assessed].basis
    assertEquals(basis.measuredTerms.toSet, Set(CostTerm.Semantic))
    assertEquals(basis.eligibleTerms.toSet, Set(CostTerm.Semantic))
    assertEquals(basis.measuredWeight, 1.0)
    assertEquals(basis.eligibleWeight, 1.0)
  }

  test("empty sketch and source declaration cannot impose a grain or entity penalty") {
    val u = unit.copy(proposition = PropositionSketch.empty.copy(lemmas = Set("run")))
    val node = view.nodes.head.copy(
      level = 1,
      predicate = None,
      participants = Vector.empty,
      propositional = PropositionalScope.unstated
    )
    val v = InMemorySourceView(Vector(node), Map.empty, None, AnnaFixture.storyText.length)
    val channel = right(
      StrictSemanticChannel.ContentTable
        .projected(graph(Vector(u)), v, Vector((u.id -> node.ref) -> Estimate.observed(0.25)))
    )
    val price = right(model(channel).cost(u, node, FidelityMode.Faithful, v))
    assertEquals(price.total, 0.25)
    assertEquals(price.terms, Map(CostTerm.Semantic -> 0.25))
  }

  test("missing and ineligible semantics are excluded without a fallback price") {
    val channels = Vector(
      StrictSemanticChannel.Unavailable,
      right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.Ineligible))
    )
    channels.foreach { channel =>
      val price = right(model(channel).cost(unit, view.nodes.head, FidelityMode.Faithful, view))
      assertEquals(price.exclusion, Some(Exclusion.Unassessable))
      assertEquals(price.terms.size, 0)
      assertEquals(price.imputedTerms.size, 0)
      assertEquals(
        unavailable(run(measured(channel), conditional)).reason,
        LocalReference.NotComputedReason.Unranked
      )
    }
  }

  test("checked measured policy refuses heterogeneous weighted and prior bases") {
    assert(
      StrictCostModel
        .of(StrictSemanticChannel.Lexical, contentPolicy = ContentCostPolicy.MeasuredSemanticV1)
        .isLeft
    )
    assert(
      StrictCostModel
        .of(
          StrictSemanticChannel.Lexical,
          weights = CostWeights.semanticOnly,
          functionPrior = FunctionPrior.none,
          distortionPenalty = 0.1,
          contentPolicy = ContentCostPolicy.MeasuredSemanticV1
        )
        .isLeft
    )
  }

  test("source conditional analytical odds do not depend on external prices") {
    val rows =
      Vector(0.0, 1.0, 100.0).map(floor => computed(run(measured(table(), floor), conditional)))
    rows.foreach { row =>
      val mass = row.mass.toVector.groupMapReduce(_._1.anchor.get)(_._2)(_ + _)
      assertEqualsDouble(mass(a), 2.0 / 3.0, 1e-12)
      assertEqualsDouble(mass(b), 1.0 / 3.0, 1e-12)
      assert(row.mass.keys.forall(_.isSource))
      assertEquals(row.costs.size, 7)
    }
    assertEquals(rows.map(_.mass).distinct.size, 1)
    assertEquals(conditional.population, NormalizationPopulation.AdmittedSourceTargets)
    assertNotEquals(
      conditional.fingerprint,
      profile(temperature = conditional.temperature).fingerprint
    )
  }

  test("source conditional comparison refuses legacy placeholder bases") {
    assertEquals(
      unavailable(run(evidence(table()), conditional)).reason,
      LocalReference.NotComputedReason.IncomparableTargetCosts
    )
  }

  test("target prior does not count equal-cost fidelity modes twice") {
    val faithful = AlignState.Source(b)
    val distorted = evidence(table()).states.head.find(_.anchor.contains(b)).get
    assertNotEquals(faithful, distorted)
    val single = LocalReference.sourceTargetMass(
      Vector(Vector(AlignState.Source(a) -> 0.0), Vector(faithful -> 0.0)),
      1.0
    )
    val doubled = LocalReference.sourceTargetMass(
      Vector(Vector(AlignState.Source(a) -> 0.0), Vector(faithful -> 0.0, distorted -> 0.0)),
      1.0
    )
    assertEquals(single(AlignState.Source(a)), 0.5)
    assertEquals(doubled(AlignState.Source(a)), 0.5)
    assertEquals(doubled(faithful), 0.25)
    assertEquals(doubled(distorted), 0.25)
  }

  test("adding a better-scoring coarse candidate cannot change detail nomination or mass") {
    val coarse = view.nodes.head.copy(
      ref = AnnaFixture.sit("coarse-control"),
      level = 1,
      lemmas = Set("coarse")
    )
    val expanded =
      InMemorySourceView(view.nodes :+ coarse, Map.empty, None, AnnaFixture.storyText.length)
    val channel = table(
      v = expanded,
      distance = (_, ref) => if ref == coarse.ref then 0.0 else if ref == a then 0.25 else 0.75
    )
    val candidates =
      right(StrictCandidateGenerator.canonicalLevel(channel, config(), recall, expanded, 0))
    val e = right(LocalEvidence.compute(recall, expanded, candidates, model(channel)))
    val actual = computed(run(e, conditional, v = expanded))
    val expected = computed(
      run(measured(table(distance = (_, ref) => if ref == a then 0.25 else 0.75)), conditional)
    )
    assertEquals(e.nominated.head.toSet, Set(a, b))
    assertEquals(actual.mass, expected.mass)
  }
