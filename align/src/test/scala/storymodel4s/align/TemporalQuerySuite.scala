package storymodel4s.align

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

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
      abstain: Boolean = false
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
        val measures = UnitMeasures.of(Vector.empty, Some(normalized), None, None).toOption.get
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
    assert(s.target(group).get.supportCoverage.isInstanceOf[SupportCoverage.Complete])
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
