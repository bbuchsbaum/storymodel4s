package storymodel4s.bench

import munit.FunSuite

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
      // eligibility counts every unit of every case; observations exist exactly for the units whose
      // gold groundedness is association/intrusion/uninterpretable (here: the association paraphrase
      // and its unit(s) inside the full recall), never for a source-anchored unit
      val expected = WogDiagnostic.cases
        .flatMap(_.gold.byUnit.values)
        .count(g =>
          g.groundedness == Groundedness.Association || g.groundedness == Groundedness.Intrusion ||
            g.groundedness == Groundedness.Uninterpretable
        )
      assert(expected >= 2, expected)
      assertEquals(rule.coverage.observed, expected, rule.render)
      assertEquals(rule.coverage.eligible, WogDiagnostic.cases.map(_.recall.size).sum, rule.render)
    }
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

  test("the rendered report carries no story or recall text") {
    val rendered = report.render
    val sentences = WarOfTheGhostsText.text
      .split("(?<=[.!?])\\s+")
      .map(_.trim)
      .filter(_.length > 12)
    sentences.foreach(s => assert(!rendered.contains(s), s"story sentence leaked: ${s.take(20)}…"))
    WarOfTheGhostsExpectations.recallParaphrases.foreach { p =>
      assert(!rendered.contains(p.text), s"recall text leaked for ${p.kind}")
    }
    assert(rendered.contains("DIAGNOSTIC"))
    assert(rendered.contains("three clocks"))
  }
