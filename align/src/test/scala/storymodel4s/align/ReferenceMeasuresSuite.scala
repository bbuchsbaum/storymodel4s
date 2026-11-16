package storymodel4s.align

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import storymodel4s.core.*

/** ADR 0031 §1 laws and typed unavailability for reference measures. */
class ReferenceMeasuresSuite extends ScalaCheckSuite:
  import ReferenceMeasureFixture.*
  import ProvisionalReference.{Declaration, Member, Refusal, Shape, WeightCoverage, WeightProblem}
  import ProvisionalReference.{Hypothesis, Outcome, Weighted}
  import ReferenceMeasures.{ExtentUnavailable, UnassignedUnavailable, Unavailable, UnitUnavailable}

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(60)

  private def extentOf(m: ReferenceMeasures.Measures, member: Int = 0, entry: Int = 0) =
    m.members.toOption.get(member).entries.toOption.get.toVector(entry).extent

  private def measured(m: ReferenceMeasures.Measures): ReferenceMeasures.Extent =
    extentOf(m).fold(e => fail(s"unexpected unavailable extent $e"), identity)

  private val genInterval: Gen[(Long, Long)] =
    for
      start <- Gen.choose(0L, Ticks - 2L)
      end <- Gen.choose(start + 1L, (start + 120L).min(Ticks))
    yield start -> end

  private def shuffled[A](values: Vector[A]): Gen[Vector[A]] =
    Gen.choose(0L, Long.MaxValue).map(seed => new scala.util.Random(seed).shuffle(values))

  // ---- Law: alternatives present and member count are invariant to member order ----

  private val genAlternative: Gen[(SourceRepresentation, Vector[Weighted])] =
    for
      count <- Gen.choose(1, 3)
      externals <- Gen.someOf(ExternalState.values.toVector).map(_.toVector.take(2))
      intervals <- Gen.listOfN(count, genInterval).map(_.toVector)
      weighted <- Gen.oneOf(true, false)
      total = count + externals.size
      if total >= 2
      factors <- Gen.listOfN(total, Gen.choose(0.1, 1.0)).map(_.toVector)
    yield
      val members =
        intervals.indices.toVector.map(i => located(single(u(i)))) ++
          externals.map(Member.External(_))
      val weights = factors.map(f => Option.when(weighted)(f / (total + 1).toDouble))
      source(intervals.map((a, b) => interval(a, b))) ->
        members.zip(weights).map((m, w) => Weighted(m, w))

  property("alternatives present and member count are invariant to member order") {
    forAll(genAlternative.flatMap(a => shuffled(a._2).map((a, _)))) {
      case ((value, members), permuted) =>
        val coverage = WeightCoverage.Exhaustive
        val before = measures(value, Declaration.Alternative(members), coverage)
        val after = measures(value, Declaration.Alternative(permuted), coverage)
        assertEquals(after.alternativesPresent, Right(true))
        assertEquals(after.alternativesPresent, before.alternativesPresent)
        assertEquals(after.memberCount, Right(members.size))
        assertEquals(after.memberCount, before.memberCount)
        assertEquals(after.unassignedShare, before.unassignedShare)
        after.unassignedShare.foreach(s => assert(s >= 0.0 && s <= 1.0, s"share $s"))
        assertEquals(after.members.map(_.toSet), before.members.map(_.toSet))
    }
  }

  // ---- Law: each extent entry is invariant to splitting an interval ----

  private val genSplit: Gen[(Long, Long, Long)] =
    for
      (start, end) <- genInterval.suchThat((a, b) => b - a >= 2L)
      cut <- Gen.choose(start + 1L, end - 1L)
    yield (start, cut, end)

  property("extent entry is invariant to splitting an interval across units") {
    forAll(genSplit) { (start, cut, end) =>
      val whole = measured(measures(source(Vector(interval(start, end))), definite(u(0))))
      val split = measured(
        measures(
          source(Vector(interval(start, cut), interval(cut, end))),
          Declaration.Definite(single(u(0), u(1)), None)
        )
      )
      assertEquals(split.measure, whole.measure)
      assertEquals(split.hull, whole.hull)
      assertEquals(split.isolatedPoints, whole.isolatedPoints)
    }
  }

  property("extent entry is invariant to splitting an interval within one unit") {
    forAll(genSplit) { (start, cut, end) =>
      val whole = measured(measures(source(Vector(interval(start, end))), definite(u(0))))
      val split = measured(
        measures(
          source(Vector(UnitSupport.Film(Vector(start -> cut, cut -> end), Vector()))),
          definite(u(0))
        )
      )
      assertEquals(split, whole)
    }
  }

  // ---- Law: each extent entry counts duplicated overlapping intervals once ----

  private val genOverlap: Gen[(Vector[(Long, Long)], Int, (Long, Long))] =
    for
      n <- Gen.choose(1, 4)
      intervals <- Gen.listOfN(n, genInterval).map(_.toVector)
      pick <- Gen.choose(0, n - 1)
      (a, b) = intervals(pick)
      start <- Gen.choose(a, b - 1L)
      end <- Gen.choose(start + 1L, b)
    yield (intervals, pick, start -> end)

  private def oracle(intervals: Vector[(Long, Long)]): Long =
    (0L until Ticks).count(t => intervals.exists((a, b) => t >= a && t < b)).toLong

  property("extent entry counts duplicated overlapping intervals once") {
    forAll(genOverlap) { (intervals, pick, inner) =>
      val base = intervals.map((a, b) => interval(a, b))
      val units = intervals.indices.toVector.map(u)
      val plain = measured(
        measures(source(base), Declaration.Definite(single(units*), None))
      )
      // Add an exact duplicate of one interval and an interval contained in it, as new units.
      val doubled = base ++ Vector(base(pick), interval(inner._1, inner._2))
      val dup = measured(
        measures(
          source(doubled),
          Declaration.Definite(single(doubled.indices.toVector.map(u)*), None)
        )
      )
      assertEquals(plain.measure.ticks, oracle(intervals))
      assertEquals(dup.measure, plain.measure)
      assertEquals(dup.hull, plain.hull)
    }
  }

  // ---- Law: extent ignores other members ----

  private val genMembers: Gen[((Long, Long), Vector[(Long, Long)])] =
    for
      start <- Gen.choose(0L, 400L)
      end <- Gen.choose(start + 1L, 500L)
      others <- Gen.choose(1, 3)
      rest <- Gen.listOfN(others, genInterval).map(_.toVector)
      far <- Gen.choose(500L, Ticks - 1L)
    yield (start -> end, (far -> (far + 1L)) +: rest)

  property("extent ignores other members") {
    forAll(genMembers) { (own, others) =>
      val value = source((own +: others).map((a, b) => interval(a, b)))
      val alone = measures(value, definite(u(0)))
      val members = (0 to others.size).toVector.map(i => Weighted(located(single(u(i))), None))
      val among = measures(value, Declaration.Alternative(members))
      assertEquals(
        among.members.toOption.get.head.entries,
        alone.members.toOption.get.head.entries
      )
      assertEquals(measured(among).measure.ticks, own._2 - own._1)
    }
  }

  // ---- Concrete extent, occurrence and grain ----

  private def definite(units: SourceNodeRef*): Declaration =
    Declaration.Definite(single(units*), None)

  private def seconds(n: Long, d: Long): ExactRational = ExactRational.of(n, d).toOption.get

  test("a single-row reference measures in seconds and sits at the annotation floor") {
    val extent = measured(measures(source(Vector(interval(10L, 30L))), definite(u(0))))
    assertEquals(extent.measure.ticks, 20L)
    assertEquals(extent.measure.seconds, seconds(2L, 1L))
    assertEquals(extent.grain.longest.seconds, seconds(2L, 1L))
    assertEquals(extent.grain.boundingUnits, 1)
    assert(extent.atAnnotationFloor)
  }

  test("a broad reference reports union, hull and grain separately and leaves the floor") {
    val value = source(Vector(interval(0L, 10L), interval(10L, 25L), interval(30L, 40L)))
    val broad = Hypothesis(Shape.Broad, NonEmptyVector.one(one(u(0), u(1), u(2))))
    val extent = measured(measures(value, Declaration.Definite(broad, None)))
    assertEquals(extent.measure.seconds, seconds(35L, 10L))
    assertEquals(extent.hull.seconds, seconds(4L, 1L))
    assertEquals(extent.grain.shortest.ticks, 10L)
    assertEquals(extent.grain.longest.ticks, 15L)
    assertEquals(extent.grain.boundingUnits, 3)
    assert(!extent.atAnnotationFloor)
  }

  test("joint moments and repeated occurrences are one entry each; occurrences are counted") {
    val value = source(Vector(interval(0L, 10L), interval(100L, 130L)))
    val moments = NonEmptyVector.of(one(u(0)), one(u(1)))
    val joint = measures(value, Declaration.Definite(Hypothesis(Shape.Joint, moments), None))
    val repeated = measures(value, Declaration.Definite(Hypothesis(Shape.Single, moments), None))
    for m <- Vector(joint, repeated) do
      assertEquals(
        m.members.toOption.get.head.entries.toOption.get.toVector
          .map(_.extent.map(_.measure.ticks)),
        Vector(Right(10L), Right(30L))
      )
    assertEquals(joint.members.toOption.get.head.occurrenceCount, Right(1))
    assertEquals(repeated.members.toOption.get.head.occurrenceCount, Right(2))
    assertEquals(joint.alternativesPresent, Right(false))
    assertEquals(joint.memberCount, Right(1))
  }

  test("points inside an interval are support, points outside are counted, never measured") {
    val value = source(Vector(UnitSupport.Film(Vector(10L -> 20L), Vector(15L, 50L, 60L))))
    val extent = measured(measures(value, definite(u(0))))
    assertEquals(extent.measure.ticks, 10L)
    assertEquals(extent.hull.ticks, 10L)
    assertEquals(extent.isolatedPoints, 2)
  }

  // ---- Every unavailable case is a typed value ----

  test("point-only, text-only, unlocated and off-axis support are typed unavailable extents") {
    val value = source(
      Vector(
        UnitSupport.Film(Vector(), Vector(5L, 7L)),
        UnitSupport.TextOnly,
        UnitSupport.Unlocated,
        UnitSupport.OtherPart(0L, 10L),
        interval(0L, 10L)
      )
    )
    assertEquals(
      extentOf(measures(value, definite(u(0)))),
      Left(ExtentUnavailable.PointSupport(2))
    )
    assertEquals(
      extentOf(measures(value, definite(u(1)))),
      Left(
        ExtentUnavailable.Support(
          NonEmptyVector.one(
            u(1) -> UnitUnavailable.NoTemporalSupport(TemporalSupport.State.TextOnly)
          )
        )
      )
    )
    assertEquals(
      extentOf(measures(value, definite(u(2)))),
      Left(
        ExtentUnavailable.Support(
          NonEmptyVector.one(
            u(2) -> UnitUnavailable.NoTemporalSupport(TemporalSupport.State.Unlocated)
          )
        )
      )
    )
    // A partly missing constituent is never measured from its available part.
    assertEquals(
      extentOf(measures(value, definite(u(3), u(4)))),
      Left(
        ExtentUnavailable.Support(
          NonEmptyVector.one(
            u(3) -> UnitUnavailable.OffAxis(TemporalSupport.State.NoSupportOnAxis)
          )
        )
      )
    )
  }

  test("support outside the selected occurrence is a typed off-axis extent") {
    import SherlockShapedSource.*
    val value = SherlockShapedSource.source(true)
    val selected =
      TemporalSupport.Selection.Occurrence(mappings(1).identity, OccurrenceId.unsafe("part-1"))
    val m = ReferenceMeasures.of(
      ProvisionalReference
        .of(value, selected, definite(e(1)), WeightCoverage.RemainderUnknown)
        .toOption
        .get
    )
    assertEquals(
      extentOf(m),
      Left(
        ExtentUnavailable.Support(
          NonEmptyVector.one(
            e(1) -> UnitUnavailable.OffAxis(TemporalSupport.State.OutsideOccurrence)
          )
        )
      )
    )
  }

  test("support partly outside the selected occurrence is a typed clipped extent") {
    import SherlockShapedSource.*
    val value = SourceRepresentation
      .of(crossView, bundles, Some(composition), Map(cross -> crossSupport))
      .toOption
      .get
    val selected =
      TemporalSupport.Selection.Occurrence(mappings(0).identity, OccurrenceId.unsafe("part-0"))
    val m = ReferenceMeasures.of(
      ProvisionalReference
        .of(value, selected, definite(cross), WeightCoverage.RemainderUnknown)
        .toOption
        .get
    )
    assertEquals(
      extentOf(m),
      Left(
        ExtentUnavailable.Support(NonEmptyVector.one(cross -> UnitUnavailable.ClippedByOccurrence))
      )
    )
  }

  test("support inside the selected occurrence is measured on the composition axis") {
    import SherlockShapedSource.*
    val selected =
      TemporalSupport.Selection.Occurrence(mappings(0).identity, OccurrenceId.unsafe("part-0"))
    val m = ReferenceMeasures.of(
      ProvisionalReference
        .of(SherlockShapedSource.source(true), selected, definite(e(1)), WeightCoverage.Exhaustive)
        .toOption
        .get
    )
    val extent = measured(m)
    assertEquals(extent.measure.ticks, 10L)
    assertEquals(extent.measure.seconds, seconds(1L, 1L))
  }

  // An axis without a timebase or a tick count whose seconds overflow cannot be reached through
  // the fixture's film parts, so the measuring step is exercised directly on such axes.
  test("an axis without a timebase gives a typed NoTimebase extent") {
    val stream = film.streams.head
    val clock = PresentationAxis
      .streamClock(film.id, film.sourceKind, stream.id, stream.kind, stream.extent, None)
      .toOption
      .get
    assertEquals(clock.timebase, None)
    val support = PlaybackSupport
      .of(clock.id, Vector(PlaybackInterval.onTimeAxis(clock, 0L, 10L).toOption.get), Vector())
      .toOption
      .get
    assertEquals(
      ReferenceMeasures.measure(clock, Vector(support)),
      Left(ExtentUnavailable.NoTimebase)
    )
  }

  test("seconds that overflow exact arithmetic give a typed Overflow extent") {
    val coarse = RationalTimebase.of(1000L, 1L).toOption.get
    val huge = SourceBundle
      .filmEdition(EditionId.unsafe("huge"), Checksum.ofText("huge"), 0L, Long.MaxValue, coarse)
      .toOption
      .get
    val axis = huge.primaryAxis
    val support = PlaybackSupport
      .of(
        axis.id,
        Vector(PlaybackInterval.on(axis, 0L, 10_000_000_000_000_000L).toOption.get),
        Vector()
      )
      .toOption
      .get
    assertEquals(ReferenceMeasures.measure(axis, Vector(support)), Left(ExtentUnavailable.Overflow))
  }

  test("an external member has typed external extent and occurrence count") {
    val value = source(Vector(interval(0L, 10L)))
    val m = measures(
      value,
      Declaration.Alternative(
        Vector(
          Weighted(located(single(u(0))), None),
          Weighted(Member.External(ExternalState.Commentary), None)
        )
      )
    )
    val external = m.members.toOption.get(1)
    assertEquals(external.entries, Left(ExtentUnavailable.External(ExternalState.Commentary)))
    assertEquals(
      external.occurrenceCount,
      Left(ExtentUnavailable.External(ExternalState.Commentary))
    )
    assertEquals(m.alternativesPresent, Right(true))
    assertEquals(m.memberCount, Right(2))
    assertEquals(m.unassignedShare, Left(UnassignedUnavailable.NoWeights))
    val destination = measures(value, Declaration.Destination(ExternalState.Intrusion))
    assertEquals(destination.alternativesPresent, Right(false))
    assertEquals(
      destination.members.map(_.map(_.entries)),
      Right(Vector(Left(ExtentUnavailable.External(ExternalState.Intrusion))))
    )
  }

  test("unresolved and processing outcomes make every measure a typed unavailable value") {
    val value = source(Vector(interval(0L, 10L)))
    val outcomes = Vector(
      Declaration.Unresolved -> Unavailable.Unresolved,
      Declaration.NotResolved(Outcome.SearchLimited("candidate cap")) ->
        Unavailable.NotResolved(Outcome.SearchLimited("candidate cap")),
      Declaration.NotResolved(Outcome.Failed(ProcessingFailure.ProviderFailure("timeout"))) ->
        Unavailable.NotResolved(Outcome.Failed(ProcessingFailure.ProviderFailure("timeout"))),
      Declaration.NotResolved(Outcome.NotAttempted("discourse")) ->
        Unavailable.NotResolved(Outcome.NotAttempted("discourse"))
    )
    outcomes.foreach { (declaration, reason) =>
      val m = measures(value, declaration, WeightCoverage.Exhaustive)
      assertEquals(m.alternativesPresent, Left(reason))
      assertEquals(m.memberCount, Left(reason))
      assertEquals(m.members, Left(reason))
      assertEquals(m.unassignedShare, Left(UnassignedUnavailable.Outcome(reason)))
    }
  }

  test("an unassigned share is known only for exhaustive weights and never redistributed") {
    val value = source(Vector(interval(0L, 10L), interval(20L, 30L)))
    val members = Vector(
      Weighted(located(single(u(0))), Some(0.5)),
      Weighted(located(single(u(1))), Some(0.25))
    )
    val exhaustive = measures(value, Declaration.Alternative(members), WeightCoverage.Exhaustive)
    assertEquals(exhaustive.unassignedShare, Right(0.25))
    assertEquals(exhaustive.members.map(_.map(_.weight)), Right(Vector(Some(0.5), Some(0.25))))
    assertEquals(
      measures(value, Declaration.Alternative(members)).unassignedShare,
      Left(UnassignedUnavailable.RemainderUnknown(0.75))
    )
    val confident = measures(
      value,
      Declaration.Definite(single(u(0)), Some(0.8)),
      WeightCoverage.Exhaustive
    )
    assertEqualsDouble(confident.unassignedShare.toOption.get, 0.2, 1e-15)
    // The declared coverage governs a confidence weight too: no known share without exhaustiveness.
    assertEquals(
      measures(value, Declaration.Definite(single(u(0)), Some(0.8))).unassignedShare,
      Left(UnassignedUnavailable.RemainderUnknown(0.8))
    )
    assertEquals(
      measures(value, definite(u(0)), WeightCoverage.Exhaustive).unassignedShare,
      Left(UnassignedUnavailable.NoWeights)
    )
  }

  // ---- Refusals ----

  private def refusal(
      declaration: Declaration,
      supports: Vector[UnitSupport] = Vector(interval(0L, 10L), interval(20L, 30L))
  ): Either[Refusal, ProvisionalReference] =
    ProvisionalReference.of(source(supports), selection, declaration, WeightCoverage.Exhaustive)

  private def alternative(weights: Option[Double]*): Declaration =
    Declaration.Alternative(
      weights.toVector.zipWithIndex.map((w, i) => Weighted(located(single(u(i))), w))
    )

  test("a one-member or empty alternative is refused") {
    assertEquals(refusal(alternative(Some(0.5))), Left(Refusal.OneMemberAlternative))
    assertEquals(refusal(Declaration.Alternative(Vector())), Left(Refusal.OneMemberAlternative))
  }

  test("duplicate members are refused, whatever their constituent or unit order") {
    val joint = (a: SourceNodeRef, b: SourceNodeRef) =>
      located(Hypothesis(Shape.Joint, NonEmptyVector.of(one(a), one(b))))
    assertEquals(
      refusal(
        Declaration.Alternative(
          Vector(Weighted(joint(u(0), u(1)), None), Weighted(joint(u(1), u(0)), None))
        )
      ),
      Left(Refusal.DuplicateMember(0, 1))
    )
    assertEquals(
      refusal(
        Declaration.Alternative(
          Vector(
            Weighted(located(single(u(0), u(1))), None),
            Weighted(Member.External(ExternalState.Intrusion), None),
            Weighted(located(single(u(1), u(0))), None)
          )
        )
      ),
      Left(Refusal.DuplicateMember(0, 2))
    )
    assertEquals(
      refusal(
        Declaration.Alternative(
          Vector(
            Weighted(Member.External(ExternalState.Intrusion), None),
            Weighted(Member.External(ExternalState.Intrusion), None)
          )
        )
      ),
      Left(Refusal.DuplicateMember(0, 1))
    )
  }

  test("zero, negative, non-finite and above-one weights are refused with typed reasons") {
    Vector(
      0.0 -> WeightProblem.NonPositive,
      -0.1 -> WeightProblem.NonPositive,
      Double.NaN -> WeightProblem.NonFinite,
      Double.PositiveInfinity -> WeightProblem.NonFinite,
      Double.NegativeInfinity -> WeightProblem.NonFinite,
      1.5 -> WeightProblem.AboveOne
    ).foreach { (w, problem) =>
      assertEquals(
        refusal(alternative(Some(0.1), Some(w))),
        Left(Refusal.InvalidWeight(1, problem))
      )
      assertEquals(
        refusal(Declaration.Definite(single(u(0)), Some(w))),
        Left(Refusal.InvalidConfidence(problem))
      )
    }
  }

  test("a weight sum above one is refused; within the shape tolerance it is kept unscaled") {
    assertEquals(
      refusal(alternative(Some(0.6), Some(0.5))).left.map(_.productPrefix),
      Left("WeightSumExceedsOne")
    )
  }

  test(
    "a share within the shape tolerance of zero is exactly 0.0 on either side; raw weights kept"
  ) {
    Vector(0.5 + 1e-12, 0.5 - 1e-12).foreach { w =>
      val near = ReferenceMeasures.of(refusal(alternative(Some(0.5), Some(w))).toOption.get)
      assertEquals(near.unassignedShare, Right(0.0))
      assertEquals(near.members.map(_.map(_.weight)), Right(Vector(Some(0.5), Some(w))))
    }
  }

  test("a small positive share beyond the shape tolerance is kept, unscaled") {
    val m = ReferenceMeasures.of(refusal(alternative(Some(0.5), Some(0.5 - 1e-6))).toOption.get)
    val share = m.unassignedShare.toOption.get
    assert(share > MappingTolerance.RowSum)
    assertEqualsDouble(share, 1e-6, 1e-15)
  }

  test("partial weights are accepted; only the unassigned share is unavailable(partial weights)") {
    Vector(WeightCoverage.Exhaustive, WeightCoverage.RemainderUnknown).foreach { coverage =>
      val m = measures(
        source(Vector(interval(0L, 10L), interval(20L, 30L))),
        alternative(Some(0.5), None),
        coverage
      )
      assertEquals(m.unassignedShare, Left(UnassignedUnavailable.PartialWeights))
      assertEquals(m.alternativesPresent, Right(true))
      assertEquals(m.memberCount, Right(2))
      assertEquals(m.members.map(_.map(_.weight)), Right(Vector(Some(0.5), None)))
      assertEquals(m.members.map(_.map(_.occurrenceCount)), Right(Vector(Right(1), Right(1))))
      assertEquals(
        m.members.map(_.map(_.entries.map(_.head.extent.map(_.measure.ticks)))),
        Right(Vector(Right(Right(10L)), Right(Right(10L))))
      )
    }
    val three = Vector(interval(0L, 10L), interval(20L, 30L), interval(40L, 50L))
    assertEquals(
      refusal(alternative(Some(0.5), None, Some(-1.0)), three),
      Left(Refusal.InvalidWeight(2, WeightProblem.NonPositive))
    )
    assertEquals(
      refusal(alternative(Some(0.7), None, Some(0.4)), three).left.map(_.productPrefix),
      Left("WeightSumExceedsOne")
    )
  }

  // Weights whose left-to-right Double sum depends on order: for (a, c, b) 1 - Σw falls below
  // -RowSum while the sorted order stays within it, so an unsorted sum refuses some orders.
  test("the weight-sum refusal does not depend on member order (sorted summation)") {
    val weights = Vector(0.11794748919172388, 0.12637737568514906, 0.755675136123127)
    val three = Vector(interval(0L, 10L), interval(20L, 30L), interval(40L, 50L))
    weights.permutations.foreach { order =>
      val members = order.map(w => Weighted(located(single(u(weights.indexOf(w)))), Some(w)))
      val admitted = refusal(Declaration.Alternative(members), three)
      assert(admitted.isRight, s"order ${order.mkString(",")} refused")
      assertEquals(ReferenceMeasures.of(admitted.toOption.get).unassignedShare, Right(0.0))
    }
  }

  // Σw = 1.000000001 (sorted) satisfies Σw <= 1.0 + RowSum, because 1.0 + 1e-9 rounds up, but
  // 1 - Σw = -1.0000000827e-9 < -RowSum: admitting it would publish a negative share.
  test("an admitted total never yields a share below zero (refusal matches the snap)") {
    val weights = Vector(0.13254750615351513, 0.6551378054206148, 0.2123146894258702)
    val three = Vector(interval(0L, 10L), interval(20L, 30L), interval(40L, 50L))
    val members = weights.zipWithIndex.map((w, i) => Weighted(located(single(u(i))), Some(w)))
    refusal(Declaration.Alternative(members), three) match
      case Left(r)          => assertEquals(r.productPrefix, "WeightSumExceedsOne")
      case Right(reference) =>
        val share = ReferenceMeasures.of(reference).unassignedShare
        fail(s"admitted with share $share")
  }

  // Weights whose remainder 1 - Σw differs in the last bit across summation orders.
  test("the unassigned share is exactly invariant to member order (sorted summation)") {
    val weights = Vector(0.28900856797231234, 0.28695687176483736, 0.06413784193170217)
    val three = Vector(interval(0L, 10L), interval(20L, 30L), interval(40L, 50L))
    val shares = weights.permutations.map { order =>
      val members = order.map(w => Weighted(located(single(u(weights.indexOf(w)))), Some(w)))
      ReferenceMeasures
        .of(refusal(Declaration.Alternative(members), three).toOption.get)
        .unassignedShare
    }.toSet
    assertEquals(shares.size, 1)
    assert(shares.head.exists(s => s >= 0.0 && s <= 1.0))
  }

  test("malformed hypotheses, unknown units and an invalid axis are refused") {
    assertEquals(
      refusal(Declaration.Definite(single(u(0), u(0)), None)),
      Left(Refusal.DuplicateUnit(u(0)))
    )
    assertEquals(
      refusal(
        Declaration
          .Definite(Hypothesis(Shape.Single, NonEmptyVector.of(one(u(0)), one(u(0)))), None)
      ),
      Left(Refusal.DuplicateConstituent)
    )
    assertEquals(
      refusal(Declaration.Definite(Hypothesis(Shape.Joint, NonEmptyVector.one(one(u(0)))), None)),
      Left(Refusal.SingleMomentJoint)
    )
    assertEquals(refusal(definite(u(9))), Left(Refusal.UnknownUnit(u(9))))
    assertEquals(
      ProvisionalReference.of(
        source(Vector(interval(0L, 10L))),
        TemporalSupport.Selection.Part(Checksum.ofText("no such part")),
        definite(u(0)),
        WeightCoverage.Exhaustive
      ),
      Left(Refusal.Axis(TemporalSupport.Refusal.UnknownPart))
    )
  }

  test("checked references compare structurally") {
    val value = source(Vector(interval(0L, 10L)))
    assertEquals(reference(value, definite(u(0))), reference(value, definite(u(0))))
    assertEquals(
      reference(value, definite(u(0))).hashCode,
      reference(value, definite(u(0))).hashCode
    )
    assertNotEquals(
      reference(value, definite(u(0))),
      reference(value, definite(u(0)), WeightCoverage.Exhaustive)
    )
    assertEquals(reference(value, definite(u(0))).units, Vector(u(0)))
  }
