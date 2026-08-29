package storymodel4s.bench

import munit.FunSuite

import storymodel4s.align.{CandidateGenerator, DefaultLocalCostModel, GraphHsmm, SemanticDistance}
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.fixtures.wog.{WarOfTheGhostsExpectations, WarOfTheGhostsText}

/** End-to-end: the WOG diagnostic cases through the free channels. The numbers are regression
  * material, not evidence (WOG never selects); what the suite pins is that the harness runs the
  * proven pipeline, labels honestly, reports absence honestly, and leaks no text.
  */
class WogDiagnosticSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private lazy val report: BenchReport =
    Bench
      .run(
        WogDiagnostic.cases,
        WogDiagnostic.factories(dimension = 256, seed = 7L),
        ProtocolDocument.pinned,
        BenchConfig(seed = 11L, resamples = 50)
      )
      .fold(e => fail(e.message), identity)

  test("WOG is wired as diagnostic cases: one per paraphrase plus one full recall") {
    val cases = WogDiagnostic.cases
    assertEquals(cases.size, WarOfTheGhostsExpectations.recallParaphrases.size + 1)
    assert(cases.forall(!_.origin.isFrozen))
    assert(
      WogDiagnostic.fullRecallCase.recall.size >= WarOfTheGhostsExpectations.recallParaphrases.size
    )
    assert(WogDiagnostic.fullRecallCase.gold.size == WogDiagnostic.fullRecallCase.recall.size)
  }

  test("the report is Diagnostic because of the cases' origin, even under the pinned protocol") {
    report match
      case BenchReport.Diagnostic(DiagnosticReason.DiagnosticOrigin(ids), channels, _) =>
        assertEquals(ids.size, WogDiagnostic.cases.size)
        assertEquals(channels.size, 2)
      case other => fail(s"expected DiagnosticOrigin, got ${other.label}")
  }

  test("every case ran through the proof: no failures, fingerprints recorded, metrics observed") {
    report.channelReports.foreach { cr =>
      assertEquals(cr.failures, Vector.empty, cr.failures.toString)
      assertEquals(cr.runs.size, WogDiagnostic.cases.size)
      cr.runs.foreach(r =>
        assertEquals(r.viewFingerprint, storymodel4s.align.ViewFingerprint.of(WogDiagnostic.view))
      )
      val observed = (cr.metrics ++ cr.openWorld).filter(_.value.isObserved).map(_.name).toSet
      Metrics.Ks.foreach(k => assert(observed.contains(Metrics.Names.strictRecall(k)), observed))
      assert(observed.contains(Metrics.Names.mrr))
      assert(observed.contains(Metrics.Names.candidateBurden))
      assert(observed.contains(Metrics.Names.falseGating))
      assert(observed.contains(Metrics.Names.externalRule))
      assert(observed.contains(Metrics.Names.blendCoverage))
    }
  }

  test(
    "absence is reported, not scored: WOG carries no charts, so the structural term has zero coverage"
  ) {
    report.channelReports.foreach { cr =>
      assert(
        cr.channel.structuralIdentity.isInstanceOf[StructuralIdentity.Absent],
        cr.channel.render
      )
      val structural = cr.metrics.find(_.name == Metrics.Names.structuralTermCoverage).get
      assertEquals(structural.value.toOption, Some(0.0))
      val semantic = cr.metrics.find(_.name == Metrics.Names.semanticTermCoverage).get
      assert(semantic.value.toOption.exists(_ > 0.0), semantic.render)
    }
  }

  test("open-world metrics are never pooled with source-anchor metrics") {
    report.channelReports.foreach { cr =>
      assert(cr.openWorld.forall(m => Metrics.Names.openWorld.contains(m.name)))
      assert(cr.metrics.forall(m => !Metrics.Names.openWorld.contains(m.name)))
      val rule = cr.openWorld.find(_.name == Metrics.Names.externalRule).get
      // Eligibility is exactly the units whose gold groundedness this rule can score (here: the
      // association paraphrase and its unit(s) inside the full recall), never every recall unit.
      val expected = WogDiagnostic.cases
        .flatMap(_.gold.byUnit.values)
        .count(g =>
          g.groundedness == Groundedness.Association || g.groundedness == Groundedness.Intrusion ||
            g.groundedness == Groundedness.Uninterpretable
        )
      assert(expected >= 2, expected)
      assertEquals(rule.coverage.observed, expected, rule.render)
      assertEquals(rule.coverage.eligible, expected, rule.render)
    }
  }

  private def unrankedObservations(c: BenchCase): CaseObservations =
    val semantic = SemanticDistance.abstaining
    val candidates = CandidateGenerator(semantic, lexicalOverlap = false)
      .generate(c.recall.ordered, c.view)
    assert(c.recall.ordered.forall(u => candidates.abstained(u.id)))
    val result = GraphHsmm
      .infer(c.recall, c.view, candidates, DefaultLocalCostModel(semantic = semantic))
      .fold(e => fail(e.message), identity)
    Metrics.observe(c, result)

  private def caseWith(groundedness: Groundedness): BenchCase =
    WogDiagnostic.paraphraseCases
      .find(_.gold.byUnit.values.exists(_.groundedness == groundedness))
      .getOrElse(fail(s"no $groundedness diagnostic case"))

  private def assertMissing(c: BenchCase, observations: CaseObservations, metric: String): Unit =
    val values = observations.byMetric(metric)
    assertEquals(values.size, 1)
    assertEquals(
      values.head.observation,
      MetricObservation.Missing(MissingReason.ProviderAbstained),
      metric
    )
    val aggregate = Metrics.aggregate(metric, Vector(observations), Vector(c.inputChecksum), 1L)
    assertEquals(aggregate.value, Estimate.missing(MissingReason.ProviderAbstained), metric)
    assertEquals(aggregate.coverage.eligible, 1, metric)
    assertEquals(aggregate.coverage.observed, 0, metric)

  test("unrankable open-world units abstain from every eligible metric") {
    val association = caseWith(Groundedness.Association)
    val associationObs = unrankedObservations(association)
    assertMissing(association, associationObs, Metrics.Names.externalRule)
    assertMissing(association, associationObs, Metrics.Names.externalSubtype)

    val inference = caseWith(Groundedness.Inference)
    val inferenceObs = unrankedObservations(inference)
    assertMissing(inference, inferenceObs, Metrics.Names.externalSubtype)
    assertMissing(inference, inferenceObs, Metrics.Names.inferenceMass)
    assertEquals(
      inferenceObs.byMetric(Metrics.Names.externalRule).head.observation,
      MetricObservation.Ineligible
    )
  }

  test(
    "the free channels recover the precise paraphrase's anchor within the top 10 (regression floor)"
  ) {
    report.channelReports.foreach { cr =>
      val r10 = cr.metrics.find(_.name == Metrics.Names.strictRecall(10)).get
      assert(r10.value.toOption.exists(_ > 0.0), s"${cr.channel.name}: ${r10.render}")
      val fg = cr.metrics.find(_.name == Metrics.Names.falseGating).get
      assertEquals(fg.value.toOption, Some(0.0), s"${cr.channel.name}: ${fg.render}")
    }
  }

  test("determinism: the same seed gives an identical rendered report") {
    val again = Bench
      .run(
        WogDiagnostic.cases,
        WogDiagnostic.factories(dimension = 256, seed = 7L),
        ProtocolDocument.pinned,
        BenchConfig(seed = 11L, resamples = 50)
      )
      .fold(e => fail(e.message), identity)
    assertEquals(again.render, report.render)
  }

  /** Sentences and shorter phrases of the source, so a partial leak is caught too: a report that
    * printed half a sentence would pass a whole-sentence check.
    */
  private lazy val storyProbes: Vector[String] =
    val sentences = WarOfTheGhostsText.text
      .split("(?<=[.!?])\\s+")
      .map(_.trim)
      .filter(_.length > 12)
      .toVector
    // Windows over the WHOLE word stream, not per sentence: a fragment that straddles a sentence
    // boundary is still the story's text, and a per-sentence probe set cannot see it. That gap was
    // real — a six-word slice spanning the title and the opening line passed the earlier version.
    val words = WarOfTheGhostsText.text.split("\\s+").toVector.filter(_.nonEmpty)
    val windows =
      if words.size < 6 then Vector.empty else words.sliding(6).map(_.mkString(" ")).toVector
    sentences ++ windows

  private def leaks(text: String): Option[String] =
    storyProbes
      .find(text.contains)
      .orElse(WarOfTheGhostsExpectations.recallParaphrases.map(_.text).find(text.contains))

  test("the rendered report carries no story or recall text") {
    val rendered = report.render
    // The check must be capable of firing: a vacuous probe set, or a render that produced nothing,
    // would make every assertion below pass while looking rigorous.
    assert(storyProbes.size > 20, s"probe set is too small to be evidence: ${storyProbes.size}")
    assert(rendered.length > 200, s"render is too short to have been checked: ${rendered.length}")
    // Positive control: the detector finds a leak when there is one to find.
    assert(
      leaks(rendered + " " + storyProbes.head).contains(storyProbes.head),
      "the canary cannot detect a leak it is shown, so its silence means nothing"
    )
    assertEquals(leaks(rendered), None, s"leaked: ${leaks(rendered).map(_.take(30))}")
    assert(rendered.contains("DIAGNOSTIC"))
    assert(rendered.contains("three clocks"))
  }

  test("the route metric actually fires on the multi-unit case, and abstains elsewhere") {
    // A metric that is always Missing is not a measurement. The full-recall case is the only one
    // with transitions, so it is the only place a route can exist.
    val runs = report.channelReports.flatMap(_.runs)
    val full = runs.filter(_.caseId == WogDiagnostic.fullRecallCase.id)
    assert(full.nonEmpty, "the full-recall case produced no run")
    val observed =
      full.flatMap(_.observations.byMetric(Metrics.Names.routeSupportMidpointDirection))
    assert(observed.nonEmpty, "route metric absent from the full-recall case")
    assert(
      observed.exists(_.observation.isInstanceOf[MetricObservation.Observed]),
      "route metric is Missing on every unit of the only case that has a route"
    )
    // The first unit of any case has no predecessor, so it must abstain rather than score.
    full.foreach { r =>
      val first = r.observations.byMetric(Metrics.Names.routeSupportMidpointDirection).head
      assertEquals(
        first.observation,
        MetricObservation.Ineligible,
        "the first unit cannot have a transition"
      )
    }
    // Single-unit paraphrase cases have no transition at all.
    val single = runs.filter(_.caseId != WogDiagnostic.fullRecallCase.id)
    single.foreach { r =>
      val vs = r.observations.byMetric(Metrics.Names.routeSupportMidpointDirection)
      assert(
        vs.forall(_.observation == MetricObservation.Ineligible),
        s"a single-unit case scored a route: ${r.caseId}"
      )
    }
  }

  test("the route metric uses the source-support midpoint axis on live WOG transitions") {
    val hashed = report.channelReports.find(_.channel.name.startsWith("hashed-ngram:")).getOrElse {
      fail("the deterministic hashed n-gram channel is absent")
    }
    val full = hashed.runs.find(_.caseId == WogDiagnostic.fullRecallCase.id).getOrElse {
      fail("the hashed n-gram channel did not run the full-recall case")
    }
    val route = full.observations.byMetric(Metrics.Names.routeSupportMidpointDirection)

    // These three deterministic transitions agree on the source-support midpoint axis. The retired
    // per-level-rank metric scored every one as disagreement: its gold and inferred anchors drew
    // their positions from independently restarting level scales.
    Vector(2, 4, 8).foreach { laterUnitIndex =>
      route(laterUnitIndex).observation match
        case MetricObservation.Observed(value) => assertEquals(value, 1.0)
        case other => fail(s"transition at index $laterUnitIndex was not observed: $other")
    }
  }
