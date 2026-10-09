package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Synthetic content changes reuse the admitted fixture; no new transcript is introduced. */
private[align] object ReferenceFixture:
  def right[E, A](value: Either[E, A]): A =
    value.fold(error => throw new AssertionError(error.toString), identity)
  val a = AnnaFixture.sit("reference-alpha")
  val b = AnnaFixture.sit("reference-beta")
  val unit = AnnaFixture.u0.copy(
    function = DiscourseFunction.EpisodicAssertion,
    proposition = PropositionSketch.empty.copy(
      predicate = Some("run"),
      lemmas = Set("run"),
      polarity = PolarityTag.Positive,
      modality = ModalityTag.Asserted
    )
  )
  def graph(units: Vector[RecallUnit] = Vector(unit)): RecallGraph[Checked] =
    right(
      RecallGraph
        .validated(AnnaFixture.transcript, AnnaFixture.rAtlas, units, RecallRelations.empty)
        .toEither
    )
  val recall = graph()
  private val template = AnnaFixture.nodes.head.copy(
    level = 0,
    parent = None,
    predicate = Some("run"),
    participants = Vector.empty,
    evidence = None,
    propositional = PropositionalScope.Declared
  )
  val view = InMemorySourceView(
    Vector(
      template.copy(ref = a, lemmas = Set("run", "alpha")),
      template.copy(ref = b, lemmas = Set("run", "beta"), polarity = PolarityTag.Negative)
    ),
    Map.empty,
    None,
    AnnaFixture.storyText.length
  )
  def config(
      k: Int = 2,
      budget: TieBudgetRequest = TieBudgetRequest.Unbounded,
      lexical: Boolean = false
  ): StrictCandidateConfig =
    right(StrictCandidateConfig.of(k, budget, lexical, None))
  def table(
      r: RecallGraph[Checked] = recall,
      v: SourceView = view,
      distance: (RecallUnitId, SourceNodeRef) => Double = (_, ref) => if ref == a then 0.0 else 1.0
  ): StrictSemanticChannel.ContentTable =
    right(
      StrictSemanticChannel.ContentTable.projected(
        r,
        v,
        r.ordered.flatMap(u =>
          v.nodes.map(n => (u.id -> n.ref) -> Estimate.observed(distance(u.id, n.ref)))
        )
      )
    )
  def evidence(
      channel: StrictSemanticChannel,
      r: RecallGraph[Checked] = recall,
      v: SourceView = view,
      cfg: StrictCandidateConfig = config(),
      weights: CostWeights = CostWeights.semanticOnly,
      external: Double = 1.0,
      distortion: Double = 0.0
  ): LocalEvidence =
    val generated = right(StrictCandidateGenerator.canonical(channel, cfg, r, v))
    val model = right(
      StrictCostModel.of(
        channel,
        weights = weights,
        functionPrior = FunctionPrior.none,
        externalFloor = external,
        externalMismatch = 0.0,
        distortionPenalty = distortion
      )
    )
    right(LocalEvidence.compute(r, v, generated, model))
  def profile(v: SourceView = view, temperature: Double = 1.0): LocalReference.Profile =
    right(
      LocalReference.Profile
        .of(right(DeclaredUniverse.of(v.nodes.map(_.ref), TargetGrain.SingleLevel(0))), temperature)
    )
  def run(
      e: LocalEvidence,
      p: LocalReference.Profile = profile(),
      r: RecallGraph[Checked] = recall,
      v: SourceView = view
  ): LocalReference.Result =
    right(LocalReference.compute(r, v, e, p))
  def computed(result: LocalReference.Result): LocalReference.Computed =
    result.outcomes.head.asInstanceOf[LocalReference.Computed]
  def unavailable(result: LocalReference.Result): LocalReference.NotComputed =
    result.outcomes.head.asInstanceOf[LocalReference.NotComputed]

class ReferenceMeasurementSuite extends FunSuite:
  import ReferenceFixture.*
  private val tolerance = 1e-12

  test("analytical odds preserve faithful distorted and all five external states") {
    val e = evidence(table())
    val result = run(e, profile(temperature = 1.0 / math.log(2.0)))
    val row = computed(result)
    assertEquals(row.mass.keySet, e.states.head.toSet)
    val faithful = AlignState.Source(a)
    val distorted = e.states.head.find(s => s.anchor.contains(b)).get
    assert(distorted.isDistorted)
    assertEquals(e.breakdowns.head(faithful).total, 0.0)
    assertEquals(e.breakdowns.head(distorted).total, 1.0)
    // Independent odds: weights1 and1/2, plus five external weights1/2, denominator4.
    assertEqualsDouble(row.mass(faithful), 0.25, tolerance)
    assertEqualsDouble(row.mass(distorted), 0.125, tolerance)
    AlignState.externals.foreach(s => assertEqualsDouble(row.mass(s), 0.125, tolerance))
    assertEquals(row.kind, MeasureKind.NormalizedScoreMass)
    assertEquals(row.costs, e.breakdowns.head)
    assert(result.evidence eq e)
    assertEquals(result.evidenceId, e.identity)
    assertEquals(result.profile.prior, LocalReference.Prior.UniformAdmittedStates)
  }

  test("observed uniform costs are computed and retain uniform semantic accounting") {
    val channel = table(distance = (_, _) => 0.0)
    val row = computed(run(evidence(channel, external = 0.0)))
    assertEquals(row.information, LocalReference.Information.UniformCosts)
    assertEquals(row.mass.size, 7)
    row.mass.values.foreach(m => assertEqualsDouble(m, 1.0 / 7.0, tolerance))
    assertEquals(row.semanticOutcomes.head.observed, 2)
    assertEquals(row.uniformSemantic.head.scoredCount, 2)
  }

  test("missing unavailable and ineligible rows keep their distinct accounting without mass") {
    val channels = Vector(
      StrictSemanticChannel.Unavailable,
      right(StrictSemanticChannel.ContentTable.of(Vector.empty)),
      right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.Ineligible))
    )
    val results = channels.map(c => run(evidence(c)))
    results.foreach { result =>
      val row = unavailable(result)
      assertEquals(row.reason, LocalReference.NotComputedReason.Unranked)
      assertEquals(row.costs.keySet, Set(AlignState.unranked))
      assertEquals(result.outcomes.size, recall.ordered.size)
    }
    val outcomes = results.map(unavailable(_).semanticOutcomes.head)
    assertEquals(outcomes(0).missing, Map(MissingReason.ChannelUnavailable -> 2))
    assertEquals(outcomes(1).missing, Map(MissingReason.ProviderAbstained -> 2))
    assertEquals(outcomes(2).ineligible, 2)
  }

  test("prior-only prices are not promoted to observed uniform mass") {
    val weights = right(CostWeights.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    val e = evidence(table(distance = (_, _) => 0.0), weights = weights, external = 0.0)
    assertEquals(e.states.head.size, 7)
    assert(e.breakdowns.head.values.forall(_.total == 0.0))
    assertEquals(unavailable(run(e)).reason, LocalReference.NotComputedReason.PriorOnlyTargetCost)
  }

  test("unassessable targets retain exclusions rather than normalizing external-only prices") {
    val channel = right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.Ineligible))
    val e = evidence(channel, cfg = config(lexical = true))
    assertEquals(e.states.head.toSet, AlignState.externals.toSet)
    val excluded = e.breakdowns.head.filter(_._1.isSource)
    assertEquals(excluded.size, 2)
    assert(excluded.values.forall(_.exclusion.contains(Exclusion.Unassessable)))
    val row = unavailable(run(e))
    assertEquals(row.reason, LocalReference.NotComputedReason.NoAdmittedTargets)
    assertEquals(row.costs, e.breakdowns.head)
  }

  test("overflow refuses the full row even when finite external prices remain") {
    val e = evidence(table(distance = (_, _) => 0.0), cfg = config(1, TieBudgetRequest.AtMost(1)))
    assertEquals(e.states.head.toSet, AlignState.externals.toSet)
    val row = unavailable(run(e))
    assertEquals(row.reason, LocalReference.NotComputedReason.TieOverflow)
    assertEquals(row.overflow.head.unionSize, 2)
    assertEquals(row.overflow.head.budget, 1)
  }

  test("profile checks finite positive temperature nonempty universe and single level") {
    val universe = right(DeclaredUniverse.of(view.nodes.map(_.ref), TargetGrain.SingleLevel(0)))
    Vector(0.0, -1.0, Double.NaN, Double.PositiveInfinity).foreach(t =>
      assert(LocalReference.Profile.of(universe, t).isLeft)
    )
    assert(
      LocalReference.Profile
        .of(right(DeclaredUniverse.of(Vector.empty, TargetGrain.SingleLevel(0))), 1.0)
        .isLeft
    )
    assert(
      LocalReference.Profile
        .of(
          right(DeclaredUniverse.of(view.nodes.map(_.ref), TargetGrain.Hierarchy(Vector(0)))),
          1.0
        )
        .isLeft
    )
    assert(DeclaredUniverse.of(Vector(a, a), TargetGrain.SingleLevel(0)).isLeft)
    assertNotEquals(profile(temperature = 1.0).fingerprint, profile(temperature = 2.0).fingerprint)
  }

  test("a foreign or incomplete universe cannot silently condition the local denominator") {
    val e = evidence(table())
    val foreign = right(
      LocalReference.Profile.of(
        right(
          DeclaredUniverse.of(Vector(a, AnnaFixture.sit("foreign")), TargetGrain.SingleLevel(0))
        ),
        1.0
      )
    )
    val subset = right(
      LocalReference.Profile
        .of(right(DeclaredUniverse.of(Vector(a), TargetGrain.SingleLevel(0))), 1.0)
    )
    assert(LocalReference.compute(recall, view, e, foreign).isLeft)
    assert(LocalReference.compute(recall, view, e, subset).isLeft)
  }

  test("mixed-grain nominations refuse instead of filtering already-priced states") {
    val mixed = view.copy(nodes =
      view.nodes :+ view.nodes.head
        .copy(ref = AnnaFixture.seg("other-level"), level = 1, lemmas = Set("different"))
    )
    val e = evidence(table(v = mixed), v = mixed)
    assert(e.nominated.head.exists(ref => mixed.node(ref).exists(_.level == 1)))
    assert(LocalReference.compute(recall, mixed, e, profile()).isLeft)
  }

  test("unattested and callback-generated evidence cannot become strict reference") {
    val semantic = SemanticDistance.lexicalJaccard
    val plain = CandidateGenerator(semantic, 2).generate(recall.ordered, view)
    val historical = right(
      LocalEvidence.compute(
        recall,
        view,
        plain,
        DefaultLocalCostModel(semantic = semantic),
        gate = true
      )
    )
    val generated = right(StrictCandidateGenerator(semantic, config()).generate(recall, view))
    val callback = right(
      LocalEvidence.compute(recall, view, generated, DefaultLocalCostModel(semantic = semantic))
    )
    assert(LocalReference.compute(recall, view, historical, profile()).isLeft)
    assert(LocalReference.compute(recall, view, callback, profile()).isLeft)
    assert(LocalReference.compute(recall, view, evidence(table()), profile()).isRight)
  }

  test("foreign recall source and ungated evidence refuse before deriving rows") {
    val e = evidence(table())
    val other =
      graph(Vector(unit.copy(proposition = unit.proposition.copy(predicate = Some("walk")))))
    assert(LocalReference.compute(other, view, e, profile()).isLeft)
    assert(
      LocalReference
        .compute(
          recall,
          view
            .copy(nodes = view.nodes.map(n => n.copy(discoursePosition = n.discoursePosition + 1))),
          e,
          profile()
        )
        .isLeft
    )
    val semantic = SemanticDistance.lexicalJaccard
    val ungated = right(
      LocalEvidence.compute(
        recall,
        view,
        CandidateGenerator(semantic, 2).generate(recall.ordered, view),
        DefaultLocalCostModel(semantic = semantic),
        gate = false
      )
    )
    assert(LocalReference.compute(recall, view, ungated, profile()).isLeft)
  }

  test("the exact originating evidence subsequently feeds HSMM without repricing or mode loss") {
    val e = evidence(table())
    val local = run(e)
    val attempted = scala.util.Try(
      GraphHsmm.infer(
        recall,
        view,
        local.evidence,
        right(HsmmConfig.of(temperature = local.profile.temperature))
      )
    )
    assert(
      attempted.toOption.exists(_.isRight),
      "actual HSMM invocation is the trap's positive control"
    )
    val hsmm = right(attempted.get)
    assertEquals(hsmm.costs(unit.id), computed(local).costs)
    assertEquals(hsmm.posterior.row(unit.id).get.mass.keySet, computed(local).mass.keySet)
    assertEquals(hsmm.candidateAnchors(unit.id).toSet, e.nominated.head.toSet)
    assert(local.evidence eq e)
  }

  test("reference produces mass before any sequence result is requested") {
    val result = run(evidence(table()))
    assertEquals(computed(result).mass.size, 7)
    assertEquals(result.outcomes.map(_.unit), result.evidence.units)
    assertEquals(result.evidenceId, result.evidence.identity)
  }
