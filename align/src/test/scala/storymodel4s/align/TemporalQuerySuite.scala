package storymodel4s.align

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.*

class TemporalQuerySuite extends FunSuite:
  import TemporalQuery.*
  import SherlockShapedSource.{a, e, group, nodes, view, bundles, physical}
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

  test("disjoint bins and top-k retain cropped omitted external and unresolved mass") {
    val p = prepared()
    val bins = Vector(
      region(p, Vector(0L -> 1L)),
      region(p, Vector(1L -> 2L)),
      region(p, Vector.empty, Vector(4L))
    )
    val part = p.partition(bins).toOption.get
    assertEquals(part.bins.map(_.allocatedRegionMass), Vector(0.125, 0.125, 0.25))
    assertEquals(part.allocatedOutside, 0.25)
    assertEquals(part.allocationDiscrepancy, 0.0)
    assertEquals(part.prepared.ledger.unavailableLocation, 0.125)
    assertEquals(
      p.partition(Vector(region(p, Vector(0L -> 2L)), region(p, Vector.empty, Vector(1L)))),
      Left(Refusal.OverlappingRegions)
    )
    assertEquals(p.partition(Vector.empty).toOption.get.allocatedOutside, 0.75)
    val full = p.query(region(p, Vector(0L -> 10L))).toOption.get
    val ranked = full.topK(1).toOption.get
    assertEquals(ranked.retained.map(_.component.weight), Vector(0.5))
    assertEquals(ranked.omittedWeight, 0.5)
    assertEquals(ranked.omittedAllocatedRegionMass, 0.25)
    assertEquals(full.topK(0).toOption.get.omittedWeight, 1.0)
    assertEquals(full.topK(-1), Left(Refusal.InvalidTopK))
  }

  test("known disconnected support bounds and uniform mass retain unavailable and external mass") {
    val p = prepared()
    val r = p.query(region(p, Vector(0L -> 1L))).toOption.get
    assertEquals(r.resolvedBounds.lower, 0.0)
    assertEquals(r.resolvedBounds.upper, 0.5)
    assertEquals(r.allocatedRegionMass, 0.125)
    assertEquals(p.ledger.allocatedSource, 0.75)
    assertEquals(p.ledger.unavailableLocation, 0.125)
    assertEquals(p.ledger.external, Vector(ExternalState.Intrusion -> 0.125))
    assertEquals(p.ledger.suppliedTotal, 1.0)
    assertEquals(p.mapping.policies.candidate, mapping().policies.candidate)
    val gap = p.query(region(p, Vector(2L -> 4L))).toOption.get
    assertEquals(gap.allocatedRegionMass, 0.0)
    assertEquals(gap.resolvedBounds.upper, 0.0)
    val point = p.query(region(p, Vector.empty, Vector(4L))).toOption.get
    assertEquals(point.allocatedRegionMass, 0.25)
    assertEquals(point.resolvedBounds.lower, 0.25)
  }
  test("support-only and incompatible kernels retain mass without allocating it") {
    val ds = Vector(
      declaration(e(1), Allocation.SupportOnly),
      declaration(e(2), Allocation.UniformIntervals)
    )
    val p = prepared(ds = ds)
    val r = p.query(region(p, Vector(0L -> 10L))).toOption.get
    assertEquals(r.resolvedBounds.lower, 0.75)
    assertEquals(r.allocatedRegionMass, 0.0)
    assertEquals(p.ledger.supportWithoutAllocation, 0.75)
    assertEquals(p.ledger.unavailableLocation, 0.125)
    assert(r.contributions.filter(_.resolvedBounds.nonEmpty).forall(_.allocated.isEmpty))
  }
  test("mixture preserves atoms inside intervals and half-open partition conservation") {
    val s = SourceRepresentation
      .of(
        view,
        bundles,
        None,
        physical(false).updated(e(1), support(a, Vector(0L -> 2L), Vector(1L, 4L)))
      )
      .toOption
      .get
    val m = mapping(s, Map(Destination.Target(e(1)) -> 1.0))
    val p = prepared(m, Vector(declaration(e(1), Allocation.Mixture(0.5))))
    assertEquals(
      p.query(region(p, Vector.empty, Vector(1L))).toOption.get.allocatedRegionMass,
      0.25
    )
    assertEquals(p.query(region(p, Vector(0L -> 2L))).toOption.get.allocatedRegionMass, 0.75)
    assertEquals(p.query(region(p, Vector(2L -> 5L))).toOption.get.allocatedRegionMass, 0.25)
    assertEquals(p.query(region(p, Vector.empty)).toOption.get.resolvedBounds.upper, 0.0)
    assertEquals(
      prepared(
        m,
        Vector(declaration(e(1), Allocation.UniformIntervals))
      ).ledger.supportWithoutAllocation,
      1.0
    )
  }
  test("large adjacent ticks remain distinct before ratio arithmetic") {
    val big = 9007199254740993L
    val b = SourceBundle
      .filmEdition(
        EditionId.unsafe("large"),
        Checksum.ofText("large"),
        0L,
        big + 4L,
        RationalTimebase.of(1L, 1L).toOption.get
      )
      .toOption
      .get
    val s = SourceRepresentation
      .of(
        view.copy(nodes = Vector(nodes.head)),
        NonEmptyVector.one(BundleEntry.media(b)),
        None,
        Map(e(1) -> support(b, Vector(big -> (big + 3L)), Vector.empty))
      )
      .toOption
      .get
    val p = prepared(
      mapping(s, Map(Destination.Target(e(1)) -> 1.0)),
      Vector(declaration(e(1), Allocation.UniformIntervals)),
      TemporalSupport.Selection.Part(b.identity)
    )
    assertEquals(
      p.query(region(p, Vector(big -> (big + 1L)))).toOption.get.allocatedRegionMass,
      1.0 / 3.0
    )
    val masses = (0L to 2L).map(i =>
      p.query(region(p, Vector((big + i) -> (big + i + 1L)))).toOption.get.allocatedRegionMass
    )
    assertEquals(masses.sum, 1.0)
  }
  test("transitive missingness and descendant containment prevent group allocation") {
    val nested =
      view.copy(nodes = nodes.map(n => if n.ref == e(4) then n.copy(parent = Some(e(2))) else n))
    val s = SourceRepresentation.of(nested, bundles, None, physical(false)).toOption.get
    assert(s.target(group).get.supportCoverage.isInstanceOf[SupportCoverage.Partial])
    val coverage = s.target(group).get.supportCoverage.asInstanceOf[SupportCoverage.Partial]
    assertEquals(
      coverage.missing.toSortedSet,
      scala.collection.immutable.SortedSet(e(4))
    )
    val ds = Vector(declaration(group, Allocation.UniformIntervals))
    val p = prepared(mapping(s, Map(Destination.Target(group) -> 1.0)), ds)
    assertEquals(p.domains.head.availability.left.toOption, Some(Unavailable.MissingDescendant))
    assertEquals(p.ledger.unavailableLocation, 1.0)
    val outside = SourceRepresentation
      .of(view, bundles, None, physical(false).updated(e(4), physical(false)(e(3))))
      .toOption
      .get
    assertEquals(
      prepared(
        mapping(outside, Map(Destination.Target(group) -> 1.0)),
        ds
      ).domains.head.availability.left.toOption,
      Some(Unavailable.DescendantOutsideDomain)
    )
  }
  test("native and composed duplicates cannot imply occurrence mass") {
    val s = SherlockShapedSource.source(true)
    val m = mapping(s, Map(Destination.Target(e(8)) -> 1.0))
    val ds = Vector(declaration(e(8), Allocation.UniformPoints))
    val p = prepared(m, ds, TemporalSupport.Selection.Part(SherlockShapedSource.b.identity))
    assertEquals(p.ledger.unavailableLocation, 1.0)
    assertEquals(p.domains.head.availability.left.toOption, Some(Unavailable.OtherCoordinates))
    val q = prepared(
      m,
      ds,
      TemporalSupport.Selection
        .Occurrence(SherlockShapedSource.mappings(1).identity, OccurrenceId.unsafe("part-1"))
    )
    assertEquals(q.ledger.unavailableLocation, 1.0)
  }
  test("selecting one of repeated occurrences never reallocates a clipped target weight") {
    val axis = SourceBundle
      .editionPlaybackAxis(
        EditionId.unsafe("repeat"),
        a.streams,
        a.authorityTracks,
        0L,
        40L,
        SherlockShapedSource.timebase
      )
      .toOption
      .get
    val segments = Vector(0L, 20L).zipWithIndex.map { (lo, i) =>
      CompositionSegment
        .of(
          PlaybackInterval.on(a.primaryAxis, 0L, 20L).toOption.get,
          PlaybackInterval.on(axis, lo, lo + 20L).toOption.get,
          OccurrenceId.unsafe(s"repeat-$i")
        )
        .toOption
        .get
    }
    val receipt = SourceDerivationReceipt
      .of("synthetic-repeat/v1", "two presentations", Vector(a.identity))
      .toOption
      .get
    val transform = TrackComposition.of(a.primaryAxis.id, axis.id, segments, receipt).toOption.get
    val composed = SourceBundle
      .of(
        axis.edition,
        SourceKind.FilmEdition,
        a.streams,
        axis,
        a.authorityTracks,
        Vector(transform)
      )
      .toOption
      .get
    val source = SourceRepresentation
      .of(
        view.copy(nodes = Vector(nodes.head)),
        NonEmptyVector.one(BundleEntry.media(a)),
        Some(DeclaredComposition.of(composed, NonEmptyVector.one(a)).toOption.get),
        Map(e(1) -> support(composed, Vector(2L -> 4L, 22L -> 24L), Vector.empty))
      )
      .toOption
      .get
    val m = mapping(source, Map(Destination.Target(e(1)) -> 1.0))
    segments.foreach { segment =>
      val p = prepared(
        m,
        Vector(declaration(e(1), Allocation.UniformIntervals)),
        TemporalSupport.Selection.Occurrence(transform.identity, segment.occurrence)
      )
      assertEquals(p.domains.head.availability.left.toOption, Some(Unavailable.ClippedOccurrence))
      assertEquals(p.ledger.unavailableLocation, 1.0)
      assertEquals(p.ledger.allocatedSource, 0.0)
    }
  }
  test("admitted row discrepancy is retained and abstention does not discard measures") {
    val m = mapping(values = Map(Destination.Target(e(1)) -> (1.0 + 5e-10)), abstain = true)
    val p = prepared(m, Vector(declaration(e(1), Allocation.UniformIntervals)))
    assertEquals(p.ledger.suppliedTotal, 1.0 + 5e-10)
    assert(p.ledger.rowSumDiscrepancy > 0.0)
    assertEquals(
      p.query(region(p, Vector(0L -> 10L))).toOption.get.allocatedRegionMass,
      1.0 + 5e-10
    )
  }
  test("failed processing, invalid declarations, missing measures and foreign regions refuse") {
    assert(
      prepare(
        mapping(failed = true),
        unit,
        Measure.NormalizedScoreMass,
        selection,
        declarations
      ).left.toOption.get.isInstanceOf[Refusal.Processing]
    )
    assertEquals(
      prepare(mapping(), unit, Measure.ModelPosterior, selection, declarations),
      Left(Refusal.MissingMeasure)
    )
    assertEquals(
      prepare(
        mapping(),
        unit,
        Measure.NormalizedScoreMass,
        selection,
        declarations ++ declarations
      ),
      Left(Refusal.DuplicateDeclaration)
    )
    Vector(Double.NaN, Double.PositiveInfinity, -0.1, 1.1).foreach { share =>
      assertEquals(
        prepare(
          mapping(),
          unit,
          Measure.NormalizedScoreMass,
          selection,
          Vector(declaration(e(1), Allocation.Mixture(share)))
        ),
        Left(Refusal.InvalidMixture)
      )
    }
    val p = prepared()
    assertEquals(
      p.query(
        Region.on(SherlockShapedSource.b.primaryAxis, Vector(0L -> 2L), Vector.empty).toOption.get
      ),
      Left(Refusal.ForeignRegion)
    )
    assert(Region.on(a.primaryAxis, Vector(2L -> 2L), Vector.empty).isLeft)
    assert(Region.on(a.primaryAxis, Vector.empty, Vector(40L)).isLeft)
  }

  test("transport quantities retain their budget and never normalize to one") {
    val m = mapping(transport = Some(3.0))
    val p = prepare(m, unit, Measure.TransportMass, selection, declarations).toOption.get
    assertEquals(p.ledger.suppliedTotal, 2.0)
    assertEquals(p.ledger.rowBudget, 3.0)
    assertEquals(p.ledger.rowSumDiscrepancy, -1.0)
    assertEquals(p.ledger.allocatedSource, 1.5)
    assertEquals(p.query(region(p, Vector(0L -> 1L))).toOption.get.allocatedRegionMass, 0.25)
    assert(p.components.forall(_.alternative.isInstanceOf[Alternative.Transport]))
  }

  private val recallClock = RecallTiming.Clock.declared(
    Checksum.ofText("timing artifact"),
    RecallTiming.ClockKey.unsafe("seconds"),
    Checksum.ofText("timing descriptor"),
    RecallTiming.RecordingIdentity.Unestablished,
    RecallTiming.Origin.Unestablished
  )
  private val timingBasis = RecallTiming.Basis.SourceReported(Checksum.ofText("timing evidence"))
  private def sec(n: Long): ExactRational = ExactRational.of(n, 1L).toOption.get
  private def timed(
      observations: Map[RecallWordId, RecallTiming.Observation],
      inv: RecallInventory = inventory
  ): RecallTiming = RecallTiming
    .checked(
      inv,
      recallClock,
      inv.words.map(w =>
        RecallTiming.Entry(
          w.id,
          recallClock,
          observations.getOrElse(
            w.id,
            RecallTiming.Observation.Missing(RecallTiming.MissingReason.NotProvided)
          )
        )
      ),
      RecallTiming
        .Provenance(Checksum.ofText("correspondence"), RecallTiming.RecordingLink.NotEstablished)
    )
    .toOption
    .get
  private def interval(a: Long, b: Long): RecallTiming.Observation =
    RecallTiming.Observation.Interval(sec(a), sec(b), timingBasis)

  test(
    "recall exposure preserves separated intervals and the final onset without inventing duration"
  ) {
    val words = inventory.units.head.words
    val t = timed(
      Map(
        words(0) -> interval(0, 2),
        words(1) -> interval(8, 10),
        words.last -> RecallTiming.Observation.OnsetOnly(sec(12), timingBasis)
      )
    )
    val exposure =
      TemporalExposure.checked(mapping(), t, TemporalExposure.Overlap.Refuse).toOption.get
    val result = exposure.query(recallClock, sec(0), sec(14)).toOption.get
    assertEquals(result.units.head.duration, BigInt(4) -> BigInt(1))
    assertEquals(result.uncovered, BigInt(10) -> BigInt(1))
    assertEquals(result.units.head.onsetEvents, Vector(sec(12)))
    assertEquals(exposure.query(recallClock, sec(2), sec(8)).toOption.get.uncoveredSeconds, 6.0)
    assertEquals(exposure.unitsForWords(Vector(words(0), words(0), words(1))), Right(Vector(unit)))
    assertEquals(
      exposure.simultaneousReferents,
      Left(TemporalExposure.Refusal.DecompositionNotAssessed)
    )
    assertEquals(result.units.head.decomposition, inventory.units.head.decomposition)
    assertEquals(
      exposure,
      TemporalExposure.checked(mapping(), t, TemporalExposure.Overlap.Refuse).toOption.get
    )
    assertEquals(result, exposure.query(recallClock, sec(0), sec(14)).toOption.get)
  }

  test("overlap is refused or shared across distinct units while failed exposure survives") {
    val a = inventory.units(0).words
    val b = inventory.units(1).words
    val t = timed(Map(a(0) -> interval(0, 4), a(1) -> interval(0, 4), b(0) -> interval(2, 6)))
    assertEquals(
      TemporalExposure.checked(mapping(), t, TemporalExposure.Overlap.Refuse),
      Left(TemporalExposure.Refusal.OverlappingUnits)
    )
    val x = TemporalExposure
      .checked(mapping(failed = true), t, TemporalExposure.Overlap.EqualShare)
      .toOption
      .get
    val r = x.query(recallClock, sec(0), sec(8)).toOption.get
    assertEquals(r.units.take(2).map(_.durationSeconds), Vector(3.0, 3.0))
    assertEquals(r.uncoveredSeconds, 2.0)
    assertEquals(r.uncomputedDuration, BigInt(6) -> BigInt(1))
    assert(r.units.forall(_.processing != ProcessingStatus.Complete))
    assertEquals(r.units.head.missingWords, a.size - 2)
    assertEquals(r.units.drop(2).map(_.durationSeconds), Vector(0.0, 0.0))
  }

  test("exposure rejects foreign inventory clock words and nonpositive windows") {
    val t = timed(Map.empty)
    val x = TemporalExposure.checked(mapping(), t, TemporalExposure.Overlap.Refuse).toOption.get
    val otherInventory = RecallInventory
      .of(
        recall,
        inventory.words.map(_.span),
        WordIdPolicy.inputArtifact(Checksum.ofText("other parsing"))
      )
      .toOption
      .get
    assertEquals(
      TemporalExposure
        .checked(mapping(), timed(Map.empty, otherInventory), TemporalExposure.Overlap.Refuse),
      Left(TemporalExposure.Refusal.ForeignInventory)
    )
    val clock = RecallTiming.Clock.declared(
      Checksum.ofText("other"),
      recallClock.key,
      recallClock.descriptor,
      recallClock.recording,
      recallClock.origin
    )
    assertEquals(x.query(clock, sec(0), sec(1)), Left(TemporalExposure.Refusal.ForeignClock))
    assertEquals(x.query(recallClock, sec(1), sec(1)), Left(TemporalExposure.Refusal.InvalidWindow))
    assertEquals(
      x.unitsForWords(Vector(RecallWordId.unsafe("foreign"))),
      Left(TemporalExposure.Refusal.ForeignWord)
    )
    val empty = x.query(recallClock, sec(0), sec(10)).toOption.get
    assertEquals(empty.uncoveredSeconds, 10.0)
    assertEquals(empty.units.map(_.missingWords).sum, inventory.words.size)
  }

  test("annotation regions retain exact large ticks and their declared kind") {
    val big = 9007199254740993L
    val b = SourceBundle
      .annotationTable(
        Checksum.ofText("annotation"),
        big,
        big + 4,
        RationalTimebase.of(1, 1).toOption.get
      )
      .toOption
      .get
    val anchor = EvidenceAnchor.MediaTime(
      b.id,
      b.streams.head.id,
      b.primaryAxis.id,
      PlaybackIntervalSet.one(PlaybackInterval.onTimeAxis(b.primaryAxis, big, big + 3).toOption.get)
    )
    val located = SourceSupportStatus.located(
      TypedSupport.Anchored(EvidenceSupport.of(b, Vector(anchor)).toOption.get)
    )
    val source = SourceRepresentation
      .of(
        view.copy(nodes = Vector(nodes.head)),
        NonEmptyVector.one(BundleEntry.media(b)),
        None,
        Map(e(1) -> located)
      )
      .toOption
      .get
    val p = prepared(
      mapping(source, Map(Destination.Target(e(1)) -> 1.0)),
      Vector(declaration(e(1), Allocation.UniformIntervals)),
      TemporalSupport.Selection.Part(b.identity)
    )
    assertEquals(
      Region.on(b.primaryAxis, Vector(big -> (big + 1)), Vector.empty),
      Left(Refusal.InvalidRegion)
    )
    val r = Region.onTimeAxis(b.primaryAxis, Vector(big -> (big + 1)), Vector.empty).toOption.get
    assertEquals(r.axis.kind, AxisKind.AnnotationTimeline)
    assertEquals(p.query(r).toOption.get.allocatedRegionMass, 1.0 / 3.0)
    assertEqualsDouble(p.partition(Vector(r)).toOption.get.allocatedOutside, 2.0 / 3.0, 1e-15)
  }

  test("concentration on disconnected support declares unattainable unconditional coverage") {
    import TemporalConcentration.{Target, shortestWindow, bestWindow, disconnected}
    val p = prepared()
    val best = bestWindow(p, BigInt(1)).toOption.get
    assertEquals(best.readout.region.geometry.get.intervals.map(_.start), Vector(4L))
    assertEquals(best.readout.allocatedRegionMass, 0.25)
    assertEquals(shortestWindow(p, Target.AbsoluteQuantity(0.5)).toOption.get.width, BigInt(5))
    assertEquals(
      shortestWindow(p, Target.ShareOfAllocatedSource(1.0)).toOption.get.width,
      BigInt(10)
    )
    assertEquals(
      shortestWindow(p, Target.ShareOfSupplied(0.9)),
      Left(TemporalConcentration.Refusal.Unattainable(0.9, 0.75))
    )
    val grid = TemporalGrid.regular(p, 0, 10, BigInt(1)).toOption.get
    assertEquals(
      grid.regions.indices.map(i => grid.allocatedAt(i).get).toVector,
      Vector(0.125, 0.125, 0.0, 0.0, 0.25, 0.0, 0.0, 0.0, 0.125, 0.125)
    )
    assertEquals(grid.cells.keySet, Set(0, 1, 4, 8, 9))
    assertEquals(grid.cells.values.map(_.size).sum, 5)
    assertEquals(grid.allocatedOutside, 0.0)
    assertEquals(grid.allocationDiscrepancy, 0.0)
    val set = disconnected(grid, Target.AbsoluteQuantity(0.5)).toOption.get
    assertEquals(set.selected, Vector(0, 1, 4))
    assertEquals(set.quantity, 0.5)
    assertEquals(set.grid.prepared.ledger.unavailableLocation, 0.125)
    assertEquals(set.grid.prepared.mapping.policies.candidate, p.mapping.policies.candidate)
    assertEquals(shortestWindow(p, Target.AbsoluteQuantity(0.0)).toOption.get.width, BigInt(0))
    assertEquals(bestWindow(p, BigInt(0)), Left(TemporalConcentration.Refusal.InvalidWidth))
    assertEquals(
      shortestWindow(p, Target.ShareOfSupplied(Double.NaN)),
      Left(TemporalConcentration.Refusal.InvalidTarget)
    )
  }

  test(
    "sparse crop and rebin derive from the full measure and keep support-only cells unallocated"
  ) {
    val p = prepared()
    val crop = TemporalGrid.regular(p, 0, 2, BigInt(1)).toOption.get
    assertEquals(
      crop.regions.indices.map(i => crop.allocatedAt(i).get).toVector,
      Vector(0.125, 0.125)
    )
    assertEquals(crop.allocatedOutside, 0.5)
    val coarse = crop.rebin(Vector(region(p, Vector(0L -> 10L)))).toOption.get
    assertEquals(coarse.allocated, Map(0 -> 0.75))
    assertEquals(coarse.prepared.ledger.external, p.ledger.external)
    val support = prepared(ds = Vector(declaration(e(1), Allocation.SupportOnly)))
    val grid = TemporalGrid.regular(support, 0, 10, BigInt(1)).toOption.get
    assertEquals(grid.allocated.values.sum, 0.0)
    assertEquals(grid.cells.keySet, Set(0, 1, 8, 9))
    assert(grid.cells.values.flatten.forall(_.allocated.isEmpty))
    assertEquals(
      TemporalConcentration.bestWindow(support, BigInt(1)),
      Left(TemporalConcentration.Refusal.UnavailableAllocation)
    )
    assertEquals(
      TemporalGrid.checked(p, Vector(region(p, Vector(0L -> 2L)), region(p, Vector(1L -> 3L)))),
      Left(TemporalGrid.Refusal.Overlap)
    )
    assertEquals(
      TemporalGrid
        .checked(p, Vector(region(p, Vector(0L -> 2L)), region(p, Vector.empty, Vector(2L))))
        .isRight,
      true
    )
    assertEquals(TemporalGrid.regular(p, 0, 10, BigInt(0)), Left(TemporalGrid.Refusal.InvalidGrid))
  }

  test(
    "broad allocation and separated peaks have different shortest windows without finer precision claims"
  ) {
    def located(intervals: Vector[(Long, Long)]) = SourceRepresentation
      .of(view, bundles, None, physical(false).updated(e(1), support(a, intervals, Vector.empty)))
      .toOption
      .get
    def one(s: SourceRepresentation) = prepared(
      mapping(s, Map(Destination.Target(e(1)) -> 1.0)),
      Vector(declaration(e(1), Allocation.UniformIntervals))
    )
    val broad = one(located(Vector(0L -> 10L)))
    val narrow = one(located(Vector(4L -> 6L)))
    val peaks = one(located(Vector(0L -> 1L, 9L -> 10L)))
    import TemporalConcentration.*
    assertEquals(shortestWindow(broad, Target.AbsoluteQuantity(0.9)).toOption.get.width, BigInt(9))
    assertEquals(shortestWindow(narrow, Target.AbsoluteQuantity(0.9)).toOption.get.width, BigInt(2))
    assertEquals(shortestWindow(peaks, Target.AbsoluteQuantity(0.9)).toOption.get.width, BigInt(10))
    val cells = TemporalGrid.regular(peaks, 0, 10, BigInt(1)).toOption.get
    assertEquals(
      disconnected(cells, Target.AbsoluteQuantity(0.9)).toOption.get.selected,
      Vector(0, 9)
    )
    assertEquals(
      bestWindow(peaks, BigInt(1)).toOption.get.readout.region.geometry.get.intervals.head.start,
      0L
    )
    assertEquals(jointStatus, JointStatus.UnavailableWithoutJointEvidence)
  }

  test("half-open atom concentration uses the earliest atom-entry tick") {
    val p = prepared(
      mapping(values = Map(Destination.Target(e(2)) -> 1.0)),
      Vector(declaration(e(2), Allocation.UniformPoints))
    )
    val best = TemporalConcentration.bestWindow(p, BigInt(3)).toOption.get
    assertEquals(
      best.readout.region.geometry.get.intervals.map(i => i.start -> i.endExclusive),
      Vector(2L -> 5L)
    )
    assertEquals(best.readout.allocatedRegionMass, 1.0)
  }

  test("recall exposure subtracts exact rational coordinates before numerical conversion") {
    val big = 9007199254740993L
    val words = inventory.units.head.words
    val t = timed(Map(words.head -> interval(big, big + 1)))
    val x = TemporalExposure.checked(mapping(), t, TemporalExposure.Overlap.Refuse).toOption.get
    val r = x.query(recallClock, sec(big), sec(big + 2)).toOption.get
    assertEquals(r.units.head.duration, BigInt(1) -> BigInt(1))
    assertEquals(r.uncovered, BigInt(1) -> BigInt(1))
    val a = ExactRational.of(1, 3).toOption.get
    val b = ExactRational.of(2, 3).toOption.get
    val fractional = timed(Map(words.head -> RecallTiming.Observation.Interval(a, b, timingBasis)))
    val f = TemporalExposure
      .checked(mapping(), fractional, TemporalExposure.Overlap.Refuse)
      .toOption
      .get
      .query(recallClock, sec(0), sec(1))
      .toOption
      .get
    assertEquals(f.units.head.duration, BigInt(1) -> BigInt(3))
    assertEquals(f.uncovered, BigInt(2) -> BigInt(3))
  }

  test("zero allocatable source does not license a conditional coverage percentage") {
    val p = prepared(ds = Vector.empty)
    assertEquals(p.ledger.allocatedSource, 0.0)
    assertEquals(
      TemporalConcentration
        .shortestWindow(p, TemporalConcentration.Target.ShareOfAllocatedSource(0.5)),
      Left(TemporalConcentration.Refusal.UndefinedConditioning)
    )
    assertEquals(
      TemporalConcentration
        .shortestWindow(p, TemporalConcentration.Target.AbsoluteQuantity(0.0))
        .toOption
        .get
        .width,
      BigInt(0)
    )
  }

  test("candidate windows agree with exhaustive lattice search and earliest ties") {
    val p = prepared()
    (1L to 12L).foreach { width =>
      val exhaustive = (0L to (40L - width)).map { start =>
        val q = p.query(region(p, Vector(start -> (start + width)))).toOption.get
        start -> q.allocatedRegionMass
      }.toVector
      val expected =
        exhaustive.tail.foldLeft(exhaustive.head)((a, b) => if b._2 > a._2 then b else a)
      val actual = TemporalConcentration.bestWindow(p, BigInt(width)).toOption.get
      assertEquals(actual.readout.region.geometry.get.intervals.head.start, expected._1)
      assertEquals(actual.readout.allocatedRegionMass, expected._2)
    }
    val other =
      Region.on(SherlockShapedSource.b.primaryAxis, Vector(0L -> 1L), Vector.empty).toOption.get
    assertEquals(TemporalGrid.checked(p, Vector(other)), Left(TemporalGrid.Refusal.ForeignAxis))
    assertEquals(
      TemporalGrid.regular(p, 0, 100001, BigInt(1)),
      Left(TemporalGrid.Refusal.TooManyBins)
    )
  }

  test("finite transport entries cannot publish an overflowing temporal aggregate") {
    val m = mapping(
      transport = Some(1.0),
      transportWeights = Some(
        Map(
          Destination.Target(e(1)) -> Double.MaxValue,
          Destination.Target(e(2)) -> Double.MaxValue
        )
      )
    )
    assertEquals(
      prepare(m, unit, Measure.TransportMass, selection, declarations.filterNot(_.target == e(4))),
      Left(Refusal.NonFiniteAggregate)
    )
    assertEquals(prepared(m).ledger.suppliedTotal, 1.0)
  }
