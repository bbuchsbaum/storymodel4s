package storymodel4s.bench

import storymodel4s.align.*
import storymodel4s.core.{Checksum, ContentAddress}
import storymodel4s.features.Coverage

/** Why a report is only diagnostic. Every reason is typed so a reader can tell "no frozen set
  * exists yet" from "the protocol changed under the set".
  */
enum DiagnosticReason:
  case NoCases
  case DiagnosticOrigin(caseIds: Vector[String])
  case MixedSets(setIds: Vector[String])
  case ProtocolDrift(setId: String, recorded: Checksum, current: Checksum)
  case ProtocolVersion(setId: String, recorded: Int, expected: Int)

  /** Law I5 clause 3: a set containing a memorized story cannot back a calibrated claim. */
  case ContaminatedSet(setId: String, storyIds: Vector[String])

  /** Law I5 clause 2: `medium` in the partition that selects a default needs a justification. */
  case UnjustifiedMedium(setId: String, storyIds: Vector[String])

  def render: String = this match
    case NoCases                => "no cases"
    case DiagnosticOrigin(ids)  => s"diagnostic-origin cases: ${ids.mkString(",")}"
    case MixedSets(ids)         => s"cases from more than one frozen set: ${ids.mkString(",")}"
    case ProtocolDrift(s, r, c) =>
      s"protocol drift on $s: frozen under ${r.short()}, candidate has ${c.short()}"
    case ProtocolVersion(s, r, e) => s"protocol version on $s: frozen under v$r, bench expects v$e"
    case ContaminatedSet(s, ids)  =>
      s"contaminated set $s (Law I5): high-risk stories ${ids.mkString(",")}"
    case UnjustifiedMedium(s, ids) =>
      s"unjustified medium-risk stories in the untouched-test partition of $s " +
        s"(Law I5): ${ids.mkString(",")}"

/** The three narrative clocks of one case (spec: sequential and three-clock panels), read from the
  * recall signature of the proven result.
  */
final case class ClockPanel(
    caseId: String,
    discourseChronology: Double,
    worldChronology: Option[Double],
    compression: Double,
    backwardMass: Double,
    worldBackwardMass: Option[Double]
):
  def render: String =
    f"$caseId: discourse=$discourseChronology%.3f world=${worldChronology.map(w => f"$w%.3f").getOrElse("n/a")} compression=$compression%.3f backward=$backwardMass%.3f worldBackward=${worldBackwardMass.map(w => f"$w%.3f").getOrElse("n/a")}"

/** One channel run over one case: the proof, its fingerprints, and the observations. */
final case class CaseRun(
    caseId: String,
    channel: String,
    viewFingerprint: ViewFingerprint,
    recallChecksum: Checksum,
    observations: CaseObservations,
    clocks: ClockPanel
)

/** Everything a channel produced over the case set. */
final case class ChannelReport(
    channel: Channel,
    runs: Vector[CaseRun],
    failures: Vector[(String, AlignError)],
    metrics: Vector[MetricValue],
    openWorld: Vector[MetricValue]
)

/** A bench report. `Calibrated` has a private constructor: it exists only through
  * [[BenchReport.label]], which checks that every case is from one verified frozen set whose
  * recorded protocol checksum equals the protocol document at the candidate under test.
  *
  * No default selection is offered in this slice — a `Defaults` API would need the untouched-test
  * partition of a frozen set (spec pass/fail rules), and none is frozen; offering the API before
  * the evidence exists would invite benchmark-tuned numbers into `align` under a calibrated name.
  */
enum BenchReport:
  case Diagnostic(reason: DiagnosticReason, channels: Vector[ChannelReport], seed: Long)
  private[bench] case Calibrated(
      setId: String,
      protocolChecksum: Checksum,
      channels: Vector[ChannelReport],
      seed: Long
  )

  def channelReports: Vector[ChannelReport] = this match
    case Diagnostic(_, cs, _)    => cs
    case Calibrated(_, _, cs, _) => cs

  def label: String = this match
    case Diagnostic(_, _, _)    => "diagnostic"
    case Calibrated(_, _, _, _) => "calibrated"

  /** A plain-text rendering with ids, fingerprints, and numbers only — never story or recall text.
    */
  def render: String =
    val header = this match
      case Diagnostic(reason, _, seed) =>
        Vector(s"embed-bench report: DIAGNOSTIC (${reason.render}) seed=$seed")
      case Calibrated(set, p, _, seed) =>
        Vector(s"embed-bench report: CALIBRATED set=$set protocol=${p.short()} seed=$seed")
    val body = channelReports.flatMap { cr =>
      Vector(
        s"channel ${cr.channel.render}",
        s"  identity=${cr.channel.identityChecksum.short()}"
      ) ++
        cr.failures.map { case (id, e) => s"  FAILED $id: ${e.message}" } ++
        cr.runs.map(r =>
          s"  run ${r.caseId} view=${r.viewFingerprint.checksum.short()} recall=${r.recallChecksum.short()}"
        ) ++
        Vector("  source-anchor metrics:") ++ cr.metrics.map(m => s"    ${m.render}") ++
        Vector("  open-world metrics (never pooled):") ++ cr.openWorld.map(m =>
          s"    ${m.render}"
        ) ++
        Vector("  three clocks:") ++ cr.runs.map(r => s"    ${r.clocks.render}")
    }
    (header ++ body).mkString("\n")

object BenchReport:
  /** Decide the label from the cases' origins and the protocol document at the candidate. */
  def label(
      cases: Vector[BenchCase],
      channels: Vector[ChannelReport],
      currentProtocol: Checksum,
      seed: Long
  ): BenchReport =
    val diagnostic = cases.collect { case c if !c.origin.isFrozen => c.id }
    val frozen = cases.collect { case BenchCase(_, _, _, o: Origin.Frozen, _, _, _, _) => o }
    if cases.isEmpty then Diagnostic(DiagnosticReason.NoCases, channels, seed)
    else if diagnostic.nonEmpty then
      Diagnostic(DiagnosticReason.DiagnosticOrigin(diagnostic.sorted), channels, seed)
    else
      val sets = frozen.map(_.setId).distinct.sorted
      if sets.size != 1 then Diagnostic(DiagnosticReason.MixedSets(sets), channels, seed)
      else
        val o = frozen.head
        if o.protocolVersion != ProtocolDocument.version then
          Diagnostic(
            DiagnosticReason.ProtocolVersion(o.setId, o.protocolVersion, ProtocolDocument.version),
            channels,
            seed
          )
        else if o.protocolChecksum != currentProtocol then
          Diagnostic(
            DiagnosticReason.ProtocolDrift(o.setId, o.protocolChecksum, currentProtocol),
            channels,
            seed
          )
        else if o.highRiskStories.nonEmpty then
          Diagnostic(DiagnosticReason.ContaminatedSet(o.setId, o.highRiskStories), channels, seed)
        else if o.unjustifiedMediumInTest.nonEmpty then
          Diagnostic(
            DiagnosticReason.UnjustifiedMedium(o.setId, o.unjustifiedMediumInTest),
            channels,
            seed
          )
        else Calibrated(o.setId, o.protocolChecksum, channels, seed)

/** Inference settings shared by every channel in a run, so channels differ only in their distances.
  */
final case class BenchConfig(
    weights: CostWeights = CostWeights.default,
    externalFloor: Double = 1.0,
    perLevel: Int = 5,
    hsmm: HsmmConfig = HsmmConfig.default,
    seed: Long = 0L,
    resamples: Int = 200
)

/** Runs channels over cases and scores them. Channels are built per case (a semantic table is a
  * function of one case's texts), so the caller supplies a channel factory.
  */
object Bench:
  final case class ChannelFactory(name: String, build: BenchCase => Either[ChannelError, Channel])

  def run(
      cases: Vector[BenchCase],
      factories: Vector[ChannelFactory],
      currentProtocol: Checksum,
      config: BenchConfig = BenchConfig()
  ): Either[ChannelError, BenchReport] =
    val reports =
      factories.foldLeft[Either[ChannelError, Vector[ChannelReport]]](Right(Vector.empty)) {
        (acc, factory) =>
          acc.flatMap { done =>
            runChannel(cases, factory, config).map(done :+ _)
          }
      }
    reports.map(rs => BenchReport.label(cases, rs, currentProtocol, config.seed))

  private def runChannel(
      cases: Vector[BenchCase],
      factory: ChannelFactory,
      config: BenchConfig
  ): Either[ChannelError, ChannelReport] =
    val built =
      cases.foldLeft[Either[ChannelError, Vector[(BenchCase, Channel)]]](Right(Vector.empty)) {
        (acc, c) => acc.flatMap(done => factory.build(c).map(ch => done :+ (c, ch)))
      }
    built.map { pairs =>
      val outcomes = pairs.map { case (c, ch) => (c, ch, infer(c, ch, config)) }
      val runs = outcomes.collect { case (c, ch, Right(r)) =>
        val sig = RecallSignature.compute(r, c.recall, c.view)
        CaseRun(
          c.id,
          ch.name,
          r.viewFingerprint,
          r.recallChecksum,
          Metrics.observe(c, r),
          ClockPanel(
            c.id,
            sig.discourseChronology,
            sig.worldChronology,
            sig.compression,
            sig.backwardMass,
            sig.worldBackwardMass
          )
        )
      }
      val failures = outcomes.collect { case (c, _, Left(e)) => (c.id, e) }
      val inputs = pairs.map { case (c, ch) =>
        ContentAddress.digest(Vector(c.inputChecksum.hex, ch.identityChecksum.hex))
      }
      val observations = runs.map(_.observations)
      val all = Metrics.Names.all.map(n =>
        Metrics.aggregate(n, observations, inputs, config.seed, config.resamples)
      )
      val (open, source) = all.partition(m => Metrics.Names.openWorld.contains(m.name))
      ChannelReport(
        pairs.headOption.map(_._2).getOrElse(emptyChannel(factory.name)),
        runs,
        failures,
        source,
        open
      )
    }

  private def emptyChannel(name: String): Channel =
    Channel(
      name,
      SemanticDistance.abstaining,
      SemanticIdentity(
        storymodel4s.embed.ProviderFingerprint.of("none", "none", "none", "none"),
        storymodel4s.embed.GeometryId.unsafe("none"),
        storymodel4s.embed.GeometryId.unsafe("none"),
        storymodel4s.embed.GeometryPairRule.IdenticalModelling,
        0
      ),
      StructuralDistance.missing,
      StructuralIdentity.Absent("no case"),
      ChannelExposure.NonMemorizing
    )

  private def infer(
      c: BenchCase,
      ch: Channel,
      config: BenchConfig
  ): Either[AlignError, HsmmResult] =
    val model = DefaultLocalCostModel(
      weights = config.weights,
      semantic = ch.semantic,
      externalFloor = config.externalFloor,
      structural = ch.structural
    )
    val candidates =
      CandidateGenerator(ch.semantic, perLevel = config.perLevel).generate(c.recall.ordered, c.view)
    GraphHsmm.infer(c.recall, c.view, candidates, model, config.hsmm)

  /** Coverage of a metric family across a report, for quick sanity checks. */
  def coverageOf(report: BenchReport, metric: String): Coverage =
    report.channelReports
      .flatMap(cr => (cr.metrics ++ cr.openWorld).filter(_.name == metric).map(_.coverage))
      .foldLeft(Coverage.empty)(_ + _)

  /** Every metric value in a report is an [[Estimate]]; this is the set that is observed. */
  def observedMetrics(report: BenchReport): Vector[MetricValue] =
    report.channelReports.flatMap(cr => cr.metrics ++ cr.openWorld).filter(_.value.isObserved)
