package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.*

class TemporalInspectionSuite extends FunSuite:
  import TemporalQuery.*
  import SherlockShapedSource.{a, e, view, bundles, physical}
  import MappingMeasureFixture.{recall, stage, stages, ledger}
  private val inventory = MappingMeasureFixture.inventory(recall)
  private val unit = inventory.units.head.id
  private val selection = TemporalSupport.Selection.Part(a.identity)
  private val external = Destination.External(ExternalState.Intrusion)
  private val weights = Map(
    Destination.Target(e(1)) -> 0.5,
    Destination.Target(e(2)) -> 0.25,
    Destination.Target(e(4)) -> 0.125,
    external -> 0.125
  )
  private def support(
      bundle: SourceBundle,
      intervals: Vector[(Long, Long)],
      points: Vector[Long]
  ): SourceSupportStatus =
    val axis = bundle.primaryAxis
    val stream = bundle.streams.head.id
    val anchors = intervals.map((lo, hi) =>
      EvidenceAnchor.MediaTime(
        bundle.id,
        stream,
        axis.id,
        PlaybackIntervalSet.one(PlaybackInterval.on(axis, lo, hi).toOption.get)
      )
    ) ++
      points.map(p =>
        EvidenceAnchor.MediaPoint(bundle.id, stream, PlaybackInstant.on(axis, p).toOption.get)
      )
    SourceSupportStatus.located(
      TypedSupport.Anchored(EvidenceSupport.of(bundle, anchors).toOption.get)
    )
  private def source: SourceRepresentation = SourceRepresentation
    .of(
      view,
      bundles,
      None,
      physical(false)
        .updated(e(1), support(a, Vector(0L -> 2L, 8L -> 10L), Vector.empty))
        .updated(e(2), support(a, Vector.empty, Vector(4L)))
    )
    .toOption
    .get
  private def mapping(
      s: SourceRepresentation = source,
      values: Map[Destination, Double] = weights,
      failed: Boolean = false,
      abstain: Boolean = false,
      transport: Option[Double] = None,
      transportWeights: Option[Map[Destination, Double]] = None
  ): MappingResult =
    val grain = TargetGrain.Hierarchy(s.targets.map(_.level).distinct.sorted)
    val universe = DeclaredUniverse.of(s.targets.map(_.ref), grain).toOption.get
    val prior = ReferencePriorId.unsafe("synthetic")
    val policy = MappingPolicies
      .of(
        InferencePolicy.Unspecified("synthetic"),
        ContextPolicy.Unspecified("none"),
        CandidatePolicy
          .Declared(CandidatePolicyId.unsafe("fixture"), CandidateCoverage.Truncated(8)),
        ReferencePrior.Declared(prior),
        DecisionPolicy.NotApplicable("synthetic"),
        universe
      )
      .toOption
      .get
    val roles = UnitRoles
      .of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(grain)
      )
      .toOption
      .get
    val outcomes = inventory.units.map { u =>
      if failed then UnitOutcome.failed(u.id, ProcessingFailure.ProviderFailure("synthetic"))
      else
        val normalized =
          NormalizedScoreMass.of(universe.id, prior, 1.0, values, stage(Stage.Scoring)).toOption.get
        val transported = transport.map(b =>
          TransportMass
            .of(
              b,
              transportWeights.getOrElse(values.view.mapValues(_ * 2.0).toMap),
              stage(Stage.Scoring)
            )
            .toOption
            .get
        )
        val measures =
          UnitMeasures.of(Vector.empty, Some(normalized), transported, None).toOption.get
        val candidates = CandidateSetId.of(stages.candidates, u.id, values.keySet)
        val links = values.keys.toVector.map(MappingLink.ungated(_, stages, candidates))
        val request =
          if abstain then DecisionRequest.Abstain(DecisionPolicyId.unsafe("abstain"), "test")
          else DecisionRequest.RawArgmax
        UnitOutcome
          .computed(
            u.id,
            measures,
            links,
            DecisionBasis.of(MeasureKind.NormalizedScoreMass, None).toOption.get,
            request,
            stages
          )
          .toOption
          .get
    }
    MappingResult
      .checked(inventory, s, policy, roles, ledger, outcomes)
      .fold(e => fail(e.toString), identity)
  private def declaration(ref: SourceNodeRef, allocation: Allocation) =
    Declaration(ref, Assumption.SuppliedSupportContainsReferent, allocation)
  private val declarations = Vector(
    declaration(e(1), Allocation.UniformIntervals),
    declaration(e(2), Allocation.UniformPoints),
    declaration(e(4), Allocation.UniformIntervals)
  )
  private def prepared(
      m: MappingResult = mapping(),
      ds: Vector[Declaration] = declarations,
      sel: TemporalSupport.Selection = selection
  ): Prepared =
    prepare(m, unit, Measure.NormalizedScoreMass, sel, ds).fold(e => fail(e.toString), identity)
  private def region(
      p: Prepared,
      intervals: Vector[(Long, Long)],
      points: Vector[Long] = Vector.empty
  ): Region =
    Region.on(p.coordinate.axis, intervals, points).toOption.get

  private def imported(m: MappingResult = mapping()): Inspection =
    val live = prepared(m)
    inspect(m.source, m.inventory, unit, live.measure,
      live.components.map(c => c.alternative -> c.weight), live.ledger.rowBudget,
      selection, declarations).fold(e => fail(e.toString), identity)

  test("imported arithmetic agrees with live rows while authority identities remain distinct") {
    val live = prepared()
    val p = imported()
    val r = region(live, Vector(0L -> 1L))
    val expected = live.query(r).toOption.get
    val actual = p.query(r).toOption.get
    assertEquals(actual.allocatedRegionMass, 0.125)
    assertEquals(actual.allocatedRegionMass, expected.allocatedRegionMass)
    assertEquals(actual.resolvedBounds.lower, expected.resolvedBounds.lower)
    assertEquals(actual.resolvedBounds.upper, expected.resolvedBounds.upper)
    assertEquals(actual.prepared.ledger.external, Vector(ExternalState.Intrusion -> 0.125))
    assertEquals(actual.prepared.ledger.unavailableLocation, 0.125)
    assertNotEquals(actual.digest, expected.digest)
    assertNotEquals(p.digest, live.digest)
    val changed = imported(mapping(values = weights.updated(Destination.Target(e(1)), 0.25)
      .updated(external, 0.375)))
    assertNotEquals(changed.digest, p.digest)
    assertEquals(changed.query(r).toOption.get.allocatedRegionMass, 0.0625)
  }

  test("crop rebin and top-k preserve original imported mass and omitted quantities") {
    val p = imported()
    val live = prepared()
    val first = region(live, Vector(0L -> 1L))
    val second = region(live, Vector(1L -> 2L))
    val point = region(live, Vector.empty, Vector(4L))
    val grid = TemporalInspection.checked(p, Vector(first, second, point)).toOption.get
    assertEquals(grid.allocated, Map(0 -> 0.125, 1 -> 0.125, 2 -> 0.25))
    assertEquals(grid.allocatedOutside, 0.25)
    assertEquals(grid.allocationDiscrepancy, 0.0)
    val wider = grid.rebin(Vector(region(live, Vector(0L -> 10L)))).toOption.get
    assertEquals(wider.allocated, Map(0 -> 0.75))
    val ranked = p.query(first).toOption.get.topK(1).toOption.get
    assertEquals(ranked.retained.map(_.component.weight), Vector(0.5))
    assertEquals(ranked.omittedWeight, 0.5)
    assertEquals(ranked.omittedAllocatedRegionMass, 0.0)
    assertEquals(p.query(first).toOption.get.topK(0).toOption.get.omittedAllocatedRegionMass, 0.125)
    assertEquals(TemporalInspection.checked(p, Vector(first, first)), Left(TemporalInspection.Refusal.Overlap))
  }

  test("invalid imported quantities and foreign geometry refuse before querying") {
    val m = mapping()
    val values = imported().components.map(c => c.alternative -> c.weight)
    def attempt(v: Vector[(Alternative, Double)], u: RecallUnitId = unit) =
      inspect(m.source, m.inventory, u, Measure.NormalizedScoreMass, v, 1.0, selection, declarations)
    assertEquals(attempt(values :+ values.head), Left(Refusal.InvalidClaims))
    assertEquals(attempt(values.updated(0, values.head._1 -> Double.NaN)), Left(Refusal.InvalidClaims))
    assertEquals(attempt(values.updated(0, values.head._1 -> -0.5)), Left(Refusal.InvalidClaims))
    assertEquals(attempt(values.drop(1)), Left(Refusal.InvalidClaims))
    assertEquals(attempt(values, RecallUnitId.unsafe("foreign")), Left(Refusal.UnknownUnit))
    assertEquals(imported().query(Region.on(SherlockShapedSource.b.primaryAxis,
      Vector(0L -> 1L), Vector.empty).toOption.get), Left(Refusal.ForeignRegion))
  }

  test("transport inspection retains a nonunit budget and row discrepancy") {
    val m = mapping(transport = Some(3.0))
    val live = prepare(m, unit, Measure.TransportMass, selection, declarations).toOption.get
    val p = inspect(m.source, m.inventory, unit, Measure.TransportMass,
      live.components.map(c => c.alternative -> c.weight), 3.0, selection, declarations).toOption.get
    assertEquals(p.ledger.suppliedTotal, 2.0)
    assertEquals(p.ledger.rowBudget, 3.0)
    assertEquals(p.ledger.rowSumDiscrepancy, -1.0)
    assertEquals(p.query(region(prepared(), Vector(0L -> 1L))).toOption.get.allocatedRegionMass, 0.25)
  }

  test("imported exposure preserves onset-only missing and failed durations") {
    val m = mapping(failed = true)
    val clock = RecallTiming.Clock.declared(Checksum.ofText("timing"), RecallTiming.ClockKey.unsafe("seconds"),
      Checksum.ofText("descriptor"), RecallTiming.RecordingIdentity.Unestablished, RecallTiming.Origin.Unestablished)
    val basis = RecallTiming.Basis.SourceReported(Checksum.ofText("source"))
    def sec(n: Long) = ExactRational.of(n, 1).toOption.get
    val words = inventory.units.head.words
    val timing = RecallTiming.checked(inventory, clock, inventory.words.map { w =>
      val observation = if w.id == words.head then RecallTiming.Observation.Interval(sec(0), sec(2), basis)
        else if w.id == words.last then RecallTiming.Observation.OnsetOnly(sec(5), basis)
        else RecallTiming.Observation.Missing(RecallTiming.MissingReason.NotProvided)
      RecallTiming.Entry(w.id, clock, observation)
    }, RecallTiming.Provenance(Checksum.ofText("correspondence"), RecallTiming.RecordingLink.NotEstablished)).toOption.get
    val statuses = m.outcomes.map(o => o.unit -> o.processing).toMap
    val p = TemporalExposure.inspect(inventory, timing, statuses, TemporalExposure.Overlap.Refuse).toOption.get
    val result = p.query(clock, sec(0), sec(6)).toOption.get
    val live = TemporalExposure.checked(m, timing, TemporalExposure.Overlap.Refuse).toOption.get
      .query(clock, sec(0), sec(6)).toOption.get
    assertEquals(result.units, live.units)
    assertEquals(result.units.head.duration, BigInt(2) -> BigInt(1))
    assertEquals(result.units.head.onsetEvents, Vector(sec(5)))
    assertEquals(result.units.head.missingWords, words.size - 2)
    assertEquals(result.uncovered, BigInt(4) -> BigInt(1))
    assertEquals(result.uncomputedDuration, BigInt(2) -> BigInt(1))
    assertNotEquals(result.digest, live.digest)
    assertEquals(TemporalExposure.inspect(inventory, timing, statuses - unit, TemporalExposure.Overlap.Refuse),
      Left(TemporalExposure.Refusal.ForeignProcessing))
  }
