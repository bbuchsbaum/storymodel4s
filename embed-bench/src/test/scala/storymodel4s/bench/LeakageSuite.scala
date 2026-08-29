package storymodel4s.bench

import munit.FunSuite

import storymodel4s.align.{AlignWire, SemanticDistance, StructuralDistance, contentFingerprint}
import storymodel4s.features.Estimate

/** Protocol Law I5 clause 4. The control has to separate "famous stories are easier for everyone"
  * from "this channel is reciting", so every test here fixes the baseline gain and varies only the
  * channel under test.
  */
class LeakageSuite extends FunSuite:

  private val metric = "strict-recall"

  private val risks: Map[String, ContaminationRisk] = Map(
    "famous-1" -> ContaminationRisk.High,
    "famous-2" -> ContaminationRisk.High,
    "obscure-1" -> ContaminationRisk.Low,
    "obscure-2" -> ContaminationRisk.Low,
    "unscored-1" -> ContaminationRisk.Medium
  )
  private def riskOf(caseId: String): Option[ContaminationRisk] = risks.get(caseId)

  /** A channel report whose per-case metric values are exactly what the test dictates. */
  private def channelReport(
      name: String,
      exposure: ChannelExposure,
      scores: Map[String, Double]
  ): ChannelReport =
    val template = WogDiagnostic.paraphraseCases.head
    val unit = template.recall.ordered.head.id
    val fingerprint = template.view.contentFingerprint
    val checksum = AlignWire.recallChecksum(template.recall)
    val runs = scores.toVector.sortBy(_._1).map { case (caseId, value) =>
      CaseRun(
        caseId = caseId,
        channel = name,
        viewFingerprint = fingerprint,
        recallChecksum = checksum,
        observations =
          CaseObservations(caseId, Map(metric -> Vector(UnitObservation(unit, Some(value))))),
        clocks = ClockPanel(caseId, 1.0, None, 1.0, 0.0, None)
      )
    }
    ChannelReport(
      Channel(
        name,
        SemanticDistance.abstaining,
        SemanticIdentity(
          storymodel4s.embed.ProviderFingerprint.of(name, "test", "test", "test"),
          storymodel4s.embed.GeometryId.unsafe(s"$name-q"),
          storymodel4s.embed.GeometryId.unsafe(s"$name-d"),
          storymodel4s.embed.GeometryPairRule.IdenticalModelling,
          0
        ),
        StructuralDistance.missing,
        StructuralIdentity.Absent("test"),
        exposure
      ),
      runs,
      failures = Vector.empty,
      metrics = Vector.empty,
      openWorld = Vector.empty
    )

  /** Everyone gains the same 0.20 on famous stories: easier stories, not leakage. */
  private def flat(name: String, exposure: ChannelExposure, base: Double): ChannelReport =
    channelReport(
      name,
      exposure,
      Map(
        "famous-1" -> (base + 0.20),
        "famous-2" -> (base + 0.20),
        "obscure-1" -> base,
        "obscure-2" -> base
      )
    )

  test("a uniform fame advantage is not leakage: it cancels against the baselines") {
    val reports = Vector(
      flat("tfidf", ChannelExposure.NonMemorizing, 0.40),
      flat("ngram", ChannelExposure.NonMemorizing, 0.35),
      flat("provider", ChannelExposure.Memorizing, 0.60)
    )
    LeakageControl.assess(reports, riskOf, metric) match
      case LeakageVerdict.Clear(findings, _, _) =>
        val p = findings.find(_.channel == "provider").get
        assert(p.gain.exists(g => math.abs(g - 0.20) < 1e-9), p.render)
        assert(p.excess.exists(math.abs(_) < 1e-9), p.render)
      case other => fail(s"expected Clear, got ${other.render}")
  }

  test("a channel that gains only where summaries exist is SUSPECTED and named") {
    val leaking = channelReport(
      "provider",
      ChannelExposure.Memorizing,
      Map(
        "famous-1" -> 0.95,
        "famous-2" -> 0.93,
        "obscure-1" -> 0.55,
        "obscure-2" -> 0.53
      )
    )
    val reports = Vector(
      flat("tfidf", ChannelExposure.NonMemorizing, 0.40),
      flat("ngram", ChannelExposure.NonMemorizing, 0.35),
      leaking
    )
    LeakageControl.assess(reports, riskOf, metric) match
      case LeakageVerdict.Suspected(findings, threshold, _) =>
        val p = findings.find(_.channel == "provider").get
        assert(p.excess.exists(_ > threshold), p.render)
        assert(
          LeakageControl.assess(reports, riskOf, metric).render.contains("provider"),
          "the verdict must name the suspected channel"
        )
      case other => fail(s"expected Suspected, got ${other.render}")
  }

  test("a baseline that gains hugely does not implicate itself: only memorizing channels do") {
    val oddBaseline = channelReport(
      "tfidf",
      ChannelExposure.NonMemorizing,
      Map(
        "famous-1" -> 0.95,
        "famous-2" -> 0.95,
        "obscure-1" -> 0.40,
        "obscure-2" -> 0.40
      )
    )
    val reports = Vector(
      oddBaseline,
      flat("ngram", ChannelExposure.NonMemorizing, 0.35),
      flat("provider", ChannelExposure.Memorizing, 0.60)
    )
    val verdict = LeakageControl.assess(reports, riskOf, metric)
    assert(verdict.isInstanceOf[LeakageVerdict.Clear], verdict.render)
  }

  test("no non-memorizing channel: NotApplicable, never a silent pass") {
    val reports = Vector(flat("provider", ChannelExposure.Memorizing, 0.60))
    LeakageControl.assess(reports, riskOf, metric) match
      case LeakageVerdict.NotApplicable(reason, _) => assert(reason.contains("non-memorizing"))
      case other => fail(s"expected NotApplicable, got ${other.render}")
  }

  test("no high-risk story: NotApplicable — the probe measured nothing") {
    val onlyLow = Map("obscure-1" -> 0.5, "obscure-2" -> 0.5)
    val reports = Vector(
      channelReport("tfidf", ChannelExposure.NonMemorizing, onlyLow),
      channelReport("provider", ChannelExposure.Memorizing, onlyLow)
    )
    LeakageControl.assess(reports, riskOf, metric) match
      case LeakageVerdict.NotApplicable(_, findings) =>
        assert(findings.forall(_.highStories == 0), findings.map(_.render).mkString("; "))
      case other => fail(s"expected NotApplicable, got ${other.render}")
  }

  test("medium and unscored cases enter neither arm") {
    val withNoise = Map(
      "famous-1" -> 0.9,
      "obscure-1" -> 0.5,
      "unscored-1" -> 0.99,
      "not-in-manifest" -> 0.99
    )
    val reports = Vector(
      channelReport("tfidf", ChannelExposure.NonMemorizing, withNoise),
      channelReport("provider", ChannelExposure.Memorizing, withNoise)
    )
    val findings = LeakageControl.assess(reports, riskOf, metric).findings
    assert(findings.forall(f => f.highStories == 1 && f.lowStories == 1), findings.head.render)
    assertEquals(
      LeakageControl.coverage(reports, riskOf).observed,
      3
    )
  }

  test("the verdict is deterministic and its receipt commits to the findings") {
    val reports = Vector(
      flat("tfidf", ChannelExposure.NonMemorizing, 0.40),
      flat("provider", ChannelExposure.Memorizing, 0.60)
    )
    val a = LeakageControl.assess(reports, riskOf, metric)
    val b = LeakageControl.assess(reports.reverse, riskOf, metric)
    (a, b) match
      case (LeakageVerdict.Clear(_, _, ra), LeakageVerdict.Clear(_, _, rb)) => assertEquals(ra, rb)
      case _ => fail(s"expected two Clear verdicts, got ${a.render} and ${b.render}")

    val shifted = Vector(
      flat("tfidf", ChannelExposure.NonMemorizing, 0.40),
      channelReport(
        "provider",
        ChannelExposure.Memorizing,
        Map(
          "famous-1" -> 0.90,
          "famous-2" -> 0.90,
          "obscure-1" -> 0.60,
          "obscure-2" -> 0.60
        )
      )
    )
    (a, LeakageControl.assess(shifted, riskOf, metric)) match
      case (LeakageVerdict.Clear(_, _, ra), LeakageVerdict.Suspected(_, _, rs)) =>
        assertNotEquals(ra, rs)
      case (x, y) => fail(s"expected Clear then Suspected, got ${x.render} and ${y.render}")
  }

  test("estimates are Missing, not zero, when an arm has no observation") {
    val reports = Vector(
      channelReport("tfidf", ChannelExposure.NonMemorizing, Map("obscure-1" -> 0.5)),
      channelReport("provider", ChannelExposure.Memorizing, Map("obscure-1" -> 0.9))
    )
    val f = LeakageControl.assess(reports, riskOf, metric).findings.head
    assert(f.highMean.isInstanceOf[Estimate.Missing[?]], f.render)
    assertEquals(f.gain, None)
  }
