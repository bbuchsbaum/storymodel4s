package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.proposition.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Production-path witnesses for canonical gates, controlled adapters and empty nominations. */
class StrictContentWiringSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(s"$e"), identity)
  private val config = right(StrictCandidateConfig.of(2, TieBudgetRequest.Unbounded, false, None))
  private def model(channel: StrictSemanticChannel) = right(StrictCostModel.of(channel))
  private def candidates(
      channel: StrictSemanticChannel,
      recall: RecallGraph[Checked] = AnnaFixture.recall,
      view: SourceView = AnnaFixture.view
  ) =
    right(StrictCandidateGenerator.canonical(channel, config, recall, view))
  private def evidence(
      channel: StrictSemanticChannel,
      recall: RecallGraph[Checked] = AnnaFixture.recall,
      view: SourceView = AnnaFixture.view
  ) =
    right(LocalEvidence.compute(recall, view, candidates(channel, recall, view), model(channel)))

  private val segment = AnnaFixture.seg("canonical-segment")
  private val first = AnnaFixture.sit("canonical-first")
  private val second = AnnaFixture.sit("canonical-second")
  private val template =
    AnnaFixture.nodes.head.copy(participants = Vector.empty, evidence = None, lemmas = Set("run"))
  private val nodes = Vector(
    template.copy(ref = segment, level = 1, parent = None, predicate = Some("run")),
    template.copy(
      ref = first,
      level = 0,
      parent = Some(segment),
      predicate = Some("run"),
      modality = ModalityTag.Intended
    ),
    template.copy(
      ref = second,
      level = 0,
      parent = Some(segment),
      predicate = Some("run"),
      polarity = PolarityTag.Negative
    )
  )
  private val run = AnnaFixture.u0.copy(
    proposition = PropositionSketch.empty.copy(
      predicate = Some("run"),
      polarity = PolarityTag.Positive,
      modality = ModalityTag.Asserted,
      lemmas = Set("run")
    ),
    evidence = None
  )
  private val recalled = right(
    RecallGraph
      .validated(AnnaFixture.transcript, AnnaFixture.rAtlas, Vector(run), RecallRelations.empty)
      .toEither
  )
  private val forward = InMemorySourceView(nodes, Map.empty, None, AnnaFixture.storyText.length)
  private val backward = forward.copy(nodes = nodes.head +: nodes.tail.reverse)

  test("canonical generation, evidence and HSMM share the content gate across storage orders") {
    val a = evidence(StrictSemanticChannel.Lexical, recalled, forward)
    val b = evidence(StrictSemanticChannel.Lexical, recalled, backward)
    val expected = Vector(Contradiction.PolarityConflict, Contradiction.ModalityConflict)
    assertEquals(a.admissibility.head(segment).contradictions, expected)
    assertEquals(b.admissibility, a.admissibility)
    assertEquals(b.breakdowns, a.breakdowns)
    assertEquals(a.gateSemantics, GateSemantics.CanonicalContent)
    // The historical control can do this comparison and actually differs on the named property.
    assertEquals(
      ModeGate.assess(run, forward.node(segment).get, forward).contradictions,
      expected.reverse
    )
    val inferred = right(GraphHsmm.infer(recalled, forward, a, HsmmConfig.default))
    val reverse = right(GraphHsmm.infer(recalled, backward, b, HsmmConfig.default))
    assertEquals(inferred.admissibility(run.id)(segment).contradictions, expected)
    assertEquals(reverse.posterior, inferred.posterior)
    assertEquals(inferred.gateSemantics, GateSemantics.CanonicalContent)
    val wrongGate = HsmmResult.validated(
      recalled,
      forward,
      inferred.candidateAnchors,
      inferred.posterior,
      inferred.flow,
      inferred.viterbi,
      inferred.logLikelihood,
      inferred.costs,
      inferred.refinementPasses,
      Some(inferred.admissibilityEcho)
    )
    assert(wrongGate.left.exists(_.isInstanceOf[AlignError.GateDrift]), wrongGate)
  }

  test(
    "unavailable, table abstention, ineligible and observed zero retain distinct generation outcomes"
  ) {
    val abstained = right(StrictSemanticChannel.ContentTable.of(Vector.empty))
    val ineligible = right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.Ineligible))
    val zero = right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.observed(0.0)))
    val channels = Vector(StrictSemanticChannel.Unavailable, abstained, ineligible, zero)
    val generated = channels.map(candidates(_))
    val unit = AnnaFixture.recall.ordered.head.id
    val summaries = generated.map(_.get(unit).get.semanticOutcomes)
    assertEquals(summaries(0).flatMap(_.missing.keys).toSet, Set(MissingReason.ChannelUnavailable))
    assertEquals(summaries(1).flatMap(_.missing.keys).toSet, Set(MissingReason.ProviderAbstained))
    assertEquals(summaries(2).map(_.ineligible).sum, AnnaFixture.view.nodes.size)
    assertEquals(summaries(3).map(_.observed).sum, AnnaFixture.view.nodes.size)
    generated.take(3).foreach(g => assertEquals(g.get(unit).get.set, CandidateSet.unranked))
    assert(generated(3).get(unit).get.set.nominations.forall(_.rawScore.contains(0.0)))
    val records = channels.map(evidence(_))
    assertEquals(records.map(_.identity).distinct.size, 4)
    records.take(3).foreach(e => assertEquals(e.states.head, Vector(AlignState.unranked)))
    records.zip(summaries).foreach { (record, expected) =>
      assertEquals(
        record.provenance.asInstanceOf[CandidateProvenance.Strict].semanticOutcomes.head,
        expected
      )
    }
  }

  test(
    "unavailable never supplies lexical semantic scores; explicit lexical nominations stay named"
  ) {
    val lexicalConfig = right(StrictCandidateConfig.of(2, TieBudgetRequest.Unbounded, true, None))
    val g = right(
      StrictCandidateGenerator.canonical(
        StrictSemanticChannel.Unavailable,
        lexicalConfig,
        AnnaFixture.recall,
        AnnaFixture.view
      )
    )
    val nominations = g.byUnit.values.toVector.flatMap(_.set.nominations)
    assert(
      nominations.nonEmpty,
      "fixture must actually nominate through the explicit lexical channel"
    )
    assert(nominations.forall(n => n.channel == Channels.lexical && n.rawScore.isEmpty))
    val e = right(
      LocalEvidence.compute(
        AnnaFixture.recall,
        AnnaFixture.view,
        g,
        model(StrictSemanticChannel.Unavailable)
      )
    )
    val anchored =
      e.breakdowns.flatMap(_.toVector.filter(!_._1.isExternal).map(_._2)).filterNot(_.excluded)
    assert(anchored.nonEmpty)
    assert(
      anchored.forall(
        _.imputedTerms.get(CostTerm.Semantic).contains(MissingReason.ChannelUnavailable)
      )
    )
    assert(
      anchored.forall(b =>
        b.support match
          case a: SupportAssessment.Assessed      => !a.measuredTerms.contains(CostTerm.Semantic)
          case u: SupportAssessment.Unestablished =>
            !u.basis.measuredTerms.contains(CostTerm.Semantic)
          case _: SupportAssessment.NotApplicable => false
      )
    )
  }

  test("strict evidence refuses historical callbacks and mismatched controlled channels") {
    val historical = right(
      StrictCandidateGenerator(AnnaFixture.semantic, config)
        .generate(AnnaFixture.recall, AnnaFixture.view)
    )
    val canonical = candidates(StrictSemanticChannel.Lexical)
    assert(
      LocalEvidence
        .compute(
          AnnaFixture.recall,
          AnnaFixture.view,
          historical,
          model(StrictSemanticChannel.Lexical)
        )
        .isLeft
    )
    assert(
      LocalEvidence
        .compute(
          AnnaFixture.recall,
          AnnaFixture.view,
          canonical,
          model(StrictSemanticChannel.Unavailable)
        )
        .isLeft
    )
    assert(
      LocalEvidence
        .compute(AnnaFixture.recall, AnnaFixture.view, canonical, AnnaFixture.costModel)
        .isLeft
    )
    val first = right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.observed(0.1)))
    val different =
      right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.observed(0.2)))
    val same = right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.observed(0.1)))
    assert(
      LocalEvidence
        .compute(AnnaFixture.recall, AnnaFixture.view, candidates(first), model(different))
        .isLeft
    )
    assert(
      LocalEvidence
        .compute(AnnaFixture.recall, AnnaFixture.view, candidates(first), model(same))
        .isRight
    )
  }

  test(
    "checked table refuses nonfinite distances, fabricated unavailable and equal-content conflicts"
  ) {
    Vector(Double.NaN, Double.PositiveInfinity, -0.1, 1.1).foreach { d =>
      assert(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.observed(d)).isLeft)
    }
    assert(
      StrictSemanticChannel.ContentTable
        .of(Vector.empty, Estimate.missing(MissingReason.ChannelUnavailable))
        .isLeft
    )
    val (u, t, _) = right(ContentProjection.canonical(run, forward.node(segment).get, forward))
    val pair = (u, t)
    val conflicting = Vector(pair -> Estimate.observed(0.1), pair -> Estimate.observed(0.2))
    assertEquals(
      StrictSemanticChannel.ContentTable.of(conflicting).left.map(_.message),
      Left("content table: equal content pairs carry conflicting outcomes")
    )
    assert(
      StrictSemanticChannel.ContentTable.of(Vector.fill(2)(pair -> Estimate.observed(0.1))).isRight
    )
  }

  test("strict cost configuration refuses nonfinite prices and invalid function priors") {
    assert(StrictCostModel.of(StrictSemanticChannel.Lexical, missingSemantic = Double.NaN).isLeft)
    assert(
      StrictCostModel
        .of(StrictSemanticChannel.Lexical, externalFloor = Double.PositiveInfinity)
        .isLeft
    )
    assert(
      StrictCostModel
        .of(
          StrictSemanticChannel.Lexical,
          functionPrior = FunctionPrior(Map(DiscourseFunction.Summary -> -1.0))
        )
        .isLeft
    )
    assert(StrictCostModel.of(StrictSemanticChannel.Lexical, distortionPenalty = 0.0).isRight)
  }

  private def chart(patient: String, reversed: Boolean = false): PropositionEvidence =
    val (p, a, b) = (ConceptId.unsafe("p"), ConceptId.unsafe("a"), ConceptId.unsafe("b"))
    val unchecked = PropositionChart.unchecked(
      Some(p),
      Map(
        p -> Concept.predicate("find"),
        a -> Concept.entity(if reversed then patient else "anna"),
        b -> Concept.entity(if reversed then "anna" else patient)
      ),
      Vector(
        PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a)),
        PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(b))
      ),
      polarity = Map(p -> Polarity.Positive)
    )
    PropositionEvidence.hand(ChartValidator.check(unchecked).fold(e => fail(s"$e"), identity))

  test(
    "span and discourse-coordinate changes alter bindings without altering canonical prices or gates"
  ) {
    import storymodel4s.core.{SpanSet, TextSpan, TypedSupport}
    val shifted = forward.copy(nodes =
      forward.nodes.map(n =>
        n.copy(
          support = TypedSupport.Text(SpanSet.one(TextSpan.unsafe(10, 11))),
          discoursePosition = n.discoursePosition + 20,
          scoringPosition = None
        )
      )
    )
    val a = evidence(StrictSemanticChannel.Lexical, recalled, forward)
    val b = evidence(StrictSemanticChannel.Lexical, recalled, shifted)
    assertEquals(b.nominated, a.nominated)
    assertEquals(b.admissibility, a.admissibility)
    assertEquals(b.breakdowns, a.breakdowns)
    assertNotEquals(
      b.identity,
      a.identity,
      "the full evidence still binds its actual coordinate-bearing inputs"
    )
    val values = Vector((run.id, segment) -> Estimate.observed(0.1))
    assertEquals(
      right(StrictSemanticChannel.ContentTable.projected(recalled, shifted, values)),
      right(StrictSemanticChannel.ContentTable.projected(recalled, forward, values))
    )
  }

  test("canonical production reduction re-keys exact, partial and contradicted chart members") {
    val straight = chart("brother")
    val partial = chart("friend")
    val reversed = chart("brother", reversed = true)
    val third = AnnaFixture.sit("canonical-third")
    val parent = template.copy(
      ref = segment,
      level = 1,
      parent = None,
      predicate = Some("find"),
      lemmas = Set("find")
    )
    def leaf(ref: SourceNodeRef, evidence: PropositionEvidence) =
      parent.copy(ref = ref, level = 0, parent = Some(segment), evidence = Some(evidence))
    val v = forward.copy(nodes =
      Vector(parent, leaf(first, straight), leaf(second, partial), leaf(third, reversed))
    )
    val u = AnnaFixture.u0.copy(
      proposition = PropositionSketch.empty.copy(predicate = Some("find"), lemmas = Set("find")),
      evidence = Some(straight)
    )
    val r = right(
      RecallGraph
        .validated(AnnaFixture.transcript, AnnaFixture.rAtlas, Vector(u), RecallRelations.empty)
        .toEither
    )
    val e = evidence(StrictSemanticChannel.Lexical, r, v)
    val cost = e.breakdowns.head(AlignState.Source(segment))
    val receipt = cost.reductions(CostTerm.Chart)
    assertEquals(cost.terms(CostTerm.Chart), 0.0)
    assertEquals(receipt.members.map(_.member), Vector(first, second))
    assertEquals(
      receipt.members.map(_.estimate),
      Vector(Estimate.observed(0.0), Estimate.observed(0.19999999999999996))
    )
    assertEquals(receipt.excludedMembers.map(_.member), Vector(third))
    assertEquals(receipt.excludedMembers.head.contradictions, Set(Contradiction.RoleReversal))
    assertEquals(receipt.sourceChartCoverage.coverage, storymodel4s.features.Coverage.unsafe(3, 3))
    assertEquals(receipt.observedEstimateCoverage, storymodel4s.features.Coverage.unsafe(2, 2))
    val permuted = v.copy(nodes = v.nodes.reverse)
    assertEquals(evidence(StrictSemanticChannel.Lexical, r, permuted).breakdowns, e.breakdowns)
    assert(GraphHsmm.infer(r, v, e, HsmmConfig.default).isRight)
  }

  test("canonical pricing preserves last-positive-weight exclusion and all-configured-zero prior") {
    val channel = right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.Ineligible))
    val lexicalConfig = right(StrictCandidateConfig.of(2, TieBudgetRequest.Unbounded, true, None))
    val g = right(StrictCandidateGenerator.canonical(channel, lexicalConfig, recalled, forward))
    val positive = right(CostWeights.of(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    val zero = right(CostWeights.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    val excluded = right(
      LocalEvidence.compute(
        recalled,
        forward,
        g,
        right(StrictCostModel.of(channel, weights = positive))
      )
    )
    val retained = right(
      LocalEvidence.compute(
        recalled,
        forward,
        g,
        right(StrictCostModel.of(channel, weights = zero))
      )
    )
    val excludedCosts = excluded.breakdowns.head.toVector.filter(!_._1.isExternal).map(_._2)
    val retainedCosts = retained.breakdowns.head.toVector.filter(!_._1.isExternal).map(_._2)
    assert(excludedCosts.nonEmpty && retainedCosts.nonEmpty)
    assert(excludedCosts.forall(_.exclusion.contains(Exclusion.Unassessable)))
    assert(retainedCosts.forall(!_.excluded))
    assert(retainedCosts.forall(_.total == 0.0))
  }

  private def ambiguousCharts: (PropositionEvidence, PropositionEvidence) =
    def id(s: String) = ConceptId.unsafe(s)
    val run1 = Some(FrameRef("propbank", "run-01", None))
    val run2 = Some(FrameRef("propbank", "run-02", None))
    def checked(c: PropositionChart[Unchecked]) =
      PropositionEvidence.hand(ChartValidator.check(c).fold(e => fail(s"$e"), identity))
    val a = checked(
      PropositionChart.unchecked(
        Some(id("pa")),
        Map(
          id("pa") -> Concept.predicate("run", run2),
          id("pa2") -> Concept.predicate("run", run1),
          id("man") -> Concept.entity("man"),
          id("dog") -> Concept.entity("dog")
        ),
        Vector(
          PropositionRelation(id("pa"), RoleAssignment.arg(0), ConceptTarget.Node(id("man"))),
          PropositionRelation(id("pa"), RoleAssignment.arg(1), ConceptTarget.Node(id("dog")))
        ),
        polarity = Map(id("pa") -> Polarity.Positive)
      )
    )
    val b = checked(
      PropositionChart.unchecked(
        Some(id("neg")),
        Map(
          id("neg") -> Concept.predicate("run", run1),
          id("rev") -> Concept.predicate("run", run1),
          id("cat") -> Concept.entity("cat"),
          id("bird") -> Concept.entity("bird"),
          id("dog") -> Concept.entity("dog"),
          id("man") -> Concept.entity("man")
        ),
        Vector(
          PropositionRelation(id("neg"), RoleAssignment.arg(0), ConceptTarget.Node(id("cat"))),
          PropositionRelation(id("neg"), RoleAssignment.arg(1), ConceptTarget.Node(id("bird"))),
          PropositionRelation(id("rev"), RoleAssignment.arg(0), ConceptTarget.Node(id("dog"))),
          PropositionRelation(id("rev"), RoleAssignment.arg(1), ConceptTarget.Node(id("man")))
        ),
        polarity = Map(id("neg") -> Polarity.Negative)
      )
    )
    (a, b)

  test(
    "strict evidence refuses downstream inference while retaining exact measured gate alternatives"
  ) {
    val (a, b) = ambiguousCharts
    val u = run.copy(evidence = Some(a))
    val r = right(
      RecallGraph
        .validated(AnnaFixture.transcript, AnnaFixture.rAtlas, Vector(u), RecallRelations.empty)
        .toEither
    )
    val n = template.copy(ref = first, parent = None, level = 0, evidence = Some(b))
    val v = forward.copy(nodes = Vector(n))
    val g = candidates(StrictSemanticChannel.Lexical, r, v)
    val refused = LocalEvidence.compute(r, v, g, model(StrictSemanticChannel.Lexical))
    assertEquals(
      refused.left.map {
        case AlignError.StrictScoring(_, _, StrictScoringRefusal.AmbiguousGates(readings)) =>
          readings
        case other => fail(s"lost measured ambiguity: $other")
      },
      Left(Set(GateReading(false, true, false), GateReading(true, false, false)))
    )
  }

  test(
    "canonical-budget exhaustion reaches production generation as a typed refusal without fallback"
  ) {
    val p = ConceptId.unsafe("budget-p")
    val cs = Vector.tabulate(15)(i => ConceptId.unsafe(s"budget-$i"))
    val cycles = (0 until 5).toVector.flatMap { i =>
      val ring = cs.slice(3 * i, 3 * i + 3)
      ring
        .zip(ring.tail :+ ring.head)
        .map((a, b) => PropositionRelation(a, RoleAssignment.named("next"), ConceptTarget.Node(b)))
    }
    val links =
      cs.map(c => PropositionRelation(p, RoleAssignment.named("mod"), ConceptTarget.Node(c)))
    val chart = ChartValidator
      .check(
        PropositionChart.unchecked(
          Some(p),
          (cs.map(_ -> Concept.entity("thing")) :+ (p -> Concept.predicate("list"))).toMap,
          cycles ++ links
        )
      )
      .fold(e => fail(s"$e"), identity)
    val u = run.copy(evidence = Some(PropositionEvidence.hand(chart)))
    val r = right(
      RecallGraph
        .validated(AnnaFixture.transcript, AnnaFixture.rAtlas, Vector(u), RecallRelations.empty)
        .toEither
    )
    val v = forward.copy(nodes = Vector(template.copy(ref = first, parent = None, level = 0)))
    val refused =
      StrictCandidateGenerator.canonical(StrictSemanticChannel.Unavailable, config, r, v)
    assertEquals(
      refused.left.map {
        case CandidateRefusal.StrictScoring(_, _, StrictScoringRefusal.Projection(reason)) => reason
        case other => fail(s"projection refusal was lost: $other")
      },
      Left(ProjectionRefusal.CanonicalBudgetExhausted(Canonical.MaxLeaves))
    )
  }
