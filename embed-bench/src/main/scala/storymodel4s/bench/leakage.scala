package storymodel4s.bench

import storymodel4s.core.{Checksum, ContentAddress}
import storymodel4s.features.{Coverage, Estimate, MissingReason}

/** Whether a channel could have memorized a story before it ever saw a recall.
  *
  * Declared, never inferred: only the caller wiring a channel knows whether its semantic side is a
  * trained provider or a deterministic function of the corpus in front of it, and guessing from a
  * fingerprint string would be exactly the silent default protocol Law I5 exists to remove.
  */
enum ChannelExposure:
  /** A trained model whose weights may encode the story or its published summaries. */
  case Memorizing

  /** A deterministic channel with no training corpus: hashed n-gram, TF-IDF fitted in-run,
    * structural kernels.
    */
  case NonMemorizing

  def render: String = this match
    case Memorizing    => "memorizing"
    case NonMemorizing => "non-memorizing"

/** One channel's fame gain on a metric, measured against the non-memorizing channels.
  *
  * `excess` is the quantity that matters: how much *more* this channel gains on famous stories than
  * channels that cannot have read them. A memorizing channel that tracks the baselines is doing
  * alignment; one that pulls ahead exactly where summaries exist is reciting.
  */
final case class LeakageFinding(
    channel: String,
    exposure: ChannelExposure,
    metric: String,
    highMean: Estimate[Double],
    lowMean: Estimate[Double],
    gain: Option[Double],
    baselineGain: Option[Double],
    excess: Option[Double],
    highStories: Int,
    lowStories: Int
):
  def render: String =
    val e = excess.map(x => f"$x%+.4f").getOrElse("n/a")
    s"$channel [${exposure.render}] $metric: excess=$e over $highStories high / $lowStories low"

/** The verdict of protocol Law I5 clause 4, run before any default is selected.
  *
  * `NotApplicable` is a first-class outcome and never silently a pass: a probe with no high-risk
  * story, or with no non-memorizing channel to compare against, has measured nothing, and saying so
  * is the difference between "no leakage" and "no evidence".
  */
enum LeakageVerdict:
  case NotApplicable(reason: String, findings: Vector[LeakageFinding])
  case Clear(findings: Vector[LeakageFinding], threshold: Double, receipt: Checksum)
  case Suspected(findings: Vector[LeakageFinding], threshold: Double, receipt: Checksum)

  def findings: Vector[LeakageFinding]

  def render: String = this match
    case NotApplicable(reason, _) => s"leakage: not applicable ($reason)"
    case Clear(fs, t, r)          =>
      s"leakage: clear at threshold $t (${fs.size} findings, receipt ${r.short()})"
    case Suspected(fs, t, r) =>
      val bad = fs.filter(_.excess.exists(_ > t)).map(_.channel).distinct
      s"leakage: SUSPECTED at threshold $t for ${bad.mkString(",")} (receipt ${r.short()})"

/** Protocol Law I5 clause 4 — the fame-by-channel leakage control.
  *
  * The risk score is triage: it proxies how much summary text about a story sits on the open web,
  * not what any provider actually trained on. This control measures the effect instead. Within a
  * channel it compares performance on `high`-risk stories against `low`-risk stories, and subtracts
  * the same contrast computed over the non-memorizing channels, so that "famous stories are simply
  * easier" cancels out and only a channel-specific advantage remains.
  *
  * Note on where this runs: Law I5 clause 3 keeps `high`-risk stories out of any set that may be
  * labelled calibrated, so this control is by construction an instrument for a *diagnostic* probe
  * deliberately mixing risk levels. Its verdict is evidence about a channel, not about a set.
  */
object LeakageControl:

  /** Excess above which a channel is reported as suspected, on metrics in [0, 1]. */
  val DefaultThreshold: Double = 0.05

  /** Assess one metric across the channels of a run.
    *
    * `riskOf` reports a case's story risk; cases whose risk is unknown are excluded and counted in
    * neither arm, because an unscored story cannot support a claim in either direction.
    */
  def assess(
      channels: Vector[ChannelReport],
      riskOf: String => Option[ContaminationRisk],
      metric: String,
      threshold: Double = DefaultThreshold
  ): LeakageVerdict =
    val findings = channels.map(c => finding(c, riskOf, metric)).sortBy(_.channel)
    val measured = findings.filter(f => f.highStories > 0 && f.lowStories > 0)
    val baselines = measured.filter(_.exposure == ChannelExposure.NonMemorizing)
    val memorizing = measured.filter(_.exposure == ChannelExposure.Memorizing)

    if measured.isEmpty then
      LeakageVerdict.NotApplicable(
        "no channel has both a high-risk and a low-risk story with observations",
        findings
      )
    else if baselines.isEmpty then
      LeakageVerdict.NotApplicable(
        "no non-memorizing channel to compare against; a gain with nothing to subtract is not evidence",
        findings
      )
    else if memorizing.isEmpty then
      LeakageVerdict.NotApplicable("no memorizing channel under test", findings)
    else
      val baselineGains = baselines.flatMap(_.gain)
      if baselineGains.isEmpty then
        // Baselines are present but none produced a usable contrast, so there is nothing to
        // subtract. Falling through would report Clear — "measured, found nothing" — when nothing
        // was measured, which is the one thing this verdict must never say.
        LeakageVerdict.NotApplicable(
          "baselines present but none has a computable gain; nothing to subtract",
          findings
        )
      else
        val baselineGain = Some(baselineGains.sum / baselineGains.size)
        val withExcess = findings.map { f =>
          if f.highStories > 0 && f.lowStories > 0 then
            f.copy(
              baselineGain = baselineGain,
              excess = for g <- f.gain; b <- baselineGain yield g - b
            )
          else f
        }
        val receipt = ContentAddress.digest(
          Vector("leakage/v1", metric, threshold.toString) ++
            withExcess.flatMap(f =>
              Vector(
                f.channel,
                f.exposure.render,
                f.highStories.toString,
                f.lowStories.toString,
                f.excess
                  .map(x => java.lang.Double.doubleToLongBits(x).toHexString)
                  .getOrElse("none")
              )
            )
        )
        val suspect = withExcess.exists(f =>
          f.exposure == ChannelExposure.Memorizing && f.excess.exists(_ > threshold)
        )
        if suspect then LeakageVerdict.Suspected(withExcess, threshold, receipt)
        else LeakageVerdict.Clear(withExcess, threshold, receipt)

  private def finding(
      report: ChannelReport,
      riskOf: String => Option[ContaminationRisk],
      metric: String
  ): LeakageFinding =
    val byRisk = report.runs.groupBy(r => riskOf(r.caseId))
    def arm(risk: ContaminationRisk): (Estimate[Double], Int) =
      val runs = byRisk.getOrElse(Some(risk), Vector.empty)
      val means = runs.flatMap { r =>
        val obs = r.observations.byMetric.getOrElse(metric, Vector.empty).flatMap(_.value)
        if obs.isEmpty then None else Some(obs.sum / obs.size)
      }
      if means.isEmpty then (Estimate.missing(MissingReason.AllMissing), 0)
      else (Estimate.observed(means.sum / means.size), means.size)

    val (high, highN) = arm(ContaminationRisk.High)
    val (low, lowN) = arm(ContaminationRisk.Low)
    val gain = (high, low) match
      case (Estimate.Observed(h, _), Estimate.Observed(l, _)) => Some(h - l)
      case _                                                  => None
    LeakageFinding(
      report.channel.name,
      report.channel.exposure,
      metric,
      high,
      low,
      gain,
      None,
      None,
      highN,
      lowN
    )

  /** Coverage of the control itself: how many of a run's cases carried a usable risk score. */
  def coverage(
      channels: Vector[ChannelReport],
      riskOf: String => Option[ContaminationRisk]
  ): Coverage =
    val ids = channels.flatMap(_.runs.map(_.caseId)).distinct
    Coverage.of(ids.size, ids.count(id => riskOf(id).isDefined)).getOrElse(Coverage.empty)
