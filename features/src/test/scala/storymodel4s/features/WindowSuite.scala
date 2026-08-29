package storymodel4s.features

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import storymodel4s.core.*

class WindowSuite extends ScalaCheckSuite:
  import Fixtures.*

  private def red(r: ScalarReducer) = WindowReducer.scalar(r)

  /** Coverage of a raw token track counted the way derived tracks count: lexical tokens only. */
  private def lexicalCoverage(raw: FeatureTrack[FeatureTarget.Token, Double]): Coverage =
    Coverage.unsafe(sequence.lexicalSize, raw.observed.size)
  private def obs(vs: Double*): NonEmptyVector[Sample[Double]] =
    NonEmptyVector.fromVectorUnsafe(
      vs.zipWithIndex.map((v, i) => Sample(i, Estimate.observed(v), 1.0)).toVector
    )

  test("reducers over all-missing samples yield Missing, never zero") {
    val allMissing = NonEmptyVector.of(
      Sample(0, Estimate.Missing[Double](MissingReason.NotInLexicon), 1.0),
      Sample(1, Estimate.Missing[Double](MissingReason.Excluded), 1.0)
    )
    val plain: Vector[ScalarReducer] = Vector(
      ScalarReducer.Sum,
      ScalarReducer.Mean,
      ScalarReducer.WeightedMean,
      ScalarReducer.Maximum,
      ScalarReducer.Variance,
      ScalarReducer.Slope
    )
    plain.foreach { r =>
      assertEquals(
        red(r).reduce(allMissing),
        Estimate.Missing(MissingReason.AllMissing),
        r.toString
      )
    }
    assertEquals(
      red(ScalarReducer.Kernel(KernelShape.Gaussian(1.0))).reduce(allMissing),
      Estimate.Missing(MissingReason.AllMissing)
    )
  }

  property("mean ≤ max, and sum = mean × observed count") {
    forAll(samples) { ss =>
      val nev = NonEmptyVector.fromVector(ss)
      nev.forall { s =>
        (
          red(ScalarReducer.Mean).reduce(s).toOption,
          red(ScalarReducer.Maximum).reduce(s).toOption,
          red(ScalarReducer.Sum).reduce(s).toOption
        ) match
          case (Some(m), Some(mx), Some(sum)) =>
            val n = s.toVector.count(_.estimate.isObserved)
            m <= mx + 1e-9 && math.abs(sum - m * n) < 1e-6
          case (None, None, None) => true
          case _                  => false
      }
    }
  }

  property("sum scales with count; mean does not") {
    forAll(Gen.chooseNum(0.5, 5.0), Gen.chooseNum(1, 20)) { (v, n) =>
      val once = obs(v)
      val many = NonEmptyVector.fromVectorUnsafe(
        Vector.fill(n)(v).zipWithIndex.map((x, i) => Sample(i, Estimate.observed(x), 1.0))
      )
      val s1 = red(ScalarReducer.Sum).reduce(once).toOption.get
      val sn = red(ScalarReducer.Sum).reduce(many).toOption.get
      val m1 = red(ScalarReducer.Mean).reduce(once).toOption.get
      val mn = red(ScalarReducer.Mean).reduce(many).toOption.get
      math.abs(sn - n * s1) < 1e-9 && math.abs(mn - m1) < 1e-9
    }
  }

  property("slope is antisymmetric under reversal of positions") {
    forAll(Gen.listOfN(5, Gen.chooseNum(-5.0, 5.0))) { vs =>
      val fwd = obs(vs*)
      val rev = NonEmptyVector.fromVectorUnsafe(
        vs.zipWithIndex.map((v, i) => Sample(vs.size - 1 - i, Estimate.observed(v), 1.0)).toVector
      )
      val a = red(ScalarReducer.Slope).reduce(fwd).toOption.get
      val b = red(ScalarReducer.Slope).reduce(rev).toOption.get
      math.abs(a + b) < 1e-9
    }
  }

  test("slope needs two distinct positions; variance of one value is zero") {
    assertEquals(
      red(ScalarReducer.Slope).reduce(obs(3.0)),
      Estimate.Missing(MissingReason.Undefined(UndefinedReason.SlopeNeedsTwoPositions))
    )
    assertEquals(red(ScalarReducer.Variance).reduce(obs(3.0)).toOption, Some(0.0))
    assertEquals(red(ScalarReducer.Variance).reduce(obs(1.0, 3.0)).toOption, Some(1.0))
  }

  test("gaussian kernel with zero bandwidth is the identity at the centre") {
    val s = obs(1.0, 9.0, 2.0)
    assertEquals(red(ScalarReducer.Kernel(KernelShape.Gaussian(0.0))).reduce(s).toOption, Some(9.0))
    val wide = red(ScalarReducer.Kernel(KernelShape.Gaussian(1000.0))).reduce(s).toOption.get
    assertEqualsDouble(wide, 4.0, 1e-3)
    val rect = red(ScalarReducer.Kernel(KernelShape.Rectangular(5.0))).reduce(s).toOption.get
    assertEqualsDouble(rect, 4.0, 1e-9)
  }

  test("kernel with positive bandwidth never substitutes a sample outside its support") {
    val s = NonEmptyVector.fromVectorUnsafe(
      (0 to 10).toVector.map { i =>
        val e: Estimate[Double] =
          if i == 0 || i == 10 then Estimate.observed(i.toDouble)
          else Estimate.Missing(MissingReason.NotInLexicon)
        Sample(i, e, 1.0)
      }
    )
    assertEquals(
      red(ScalarReducer.Kernel(KernelShape.Rectangular(1.0))).reduce(s),
      Estimate.Missing(MissingReason.Undefined(UndefinedReason.OutsideKernelSupport))
    )
    // a declared point mass (zero bandwidth) may fall back to the nearest observed sample
    assertEquals(
      red(ScalarReducer.Kernel(KernelShape.Rectangular(0.0))).reduce(s).toOption,
      Some(0.0)
    )
    // a wide enough kernel sees both samples
    assertEqualsDouble(
      red(ScalarReducer.Kernel(KernelShape.Rectangular(5.0))).reduce(s).toOption.get,
      5.0,
      1e-9
    )
  }

  test("non-finite observations are never measurements") {
    assertEquals(
      Estimate.score(Double.NaN),
      Estimate.Missing(MissingReason.Undefined(UndefinedReason.NotFinite))
    )
    val nan = NonEmptyVector.of(Sample(0, Estimate.Observed(Double.NaN, None), 1.0))
    assertEquals(
      red(ScalarReducer.Mean).reduce(nan),
      Estimate.Missing(MissingReason.Undefined(UndefinedReason.NotFinite))
    )
    val mixed = NonEmptyVector.of(
      Sample(0, Estimate.Observed(Double.PositiveInfinity, None), 1.0),
      Sample(1, Estimate.observed(2.0), 1.0)
    )
    assertEquals(red(ScalarReducer.Sum).reduce(mixed).toOption, Some(2.0))
    val raw = imageabilityTrack()
    val bad = raw.copy(observations =
      raw.observations
        .updated(0, raw.observations(0).copy(estimate = Estimate.Observed(Double.NaN, None)))
    )
    assert(FeatureTrack.validatedScores(raw).isRight)
    assert(FeatureTrack.validatedScores(bad).isLeft)
  }

  test("negative sample weights are rejected by reduction") {
    val ss = Vector(Sample(0, Estimate.observed(1.0), -1.0))
    assert(
      Reduction.reduce(ss, red(ScalarReducer.WeightedMean), MissingValuePolicy.IgnoreMissing).isLeft
    )
  }

  test("weighted mean honours sample weights") {
    val s = NonEmptyVector.of(
      Sample(0, Estimate.observed(1.0), 3.0),
      Sample(1, Estimate.observed(5.0), 1.0)
    )
    assertEqualsDouble(red(ScalarReducer.WeightedMean).reduce(s).toOption.get, 2.0, 1e-9)
  }

  test("Reduction applies the missing-value policy with correct coverage") {
    val ss = Vector(
      Sample(0, Estimate.observed(2.0), 1.0),
      Sample(1, Estimate.Missing[Double](MissingReason.NotInLexicon), 1.0)
    )
    val (e1, c1) =
      Reduction.reduce(ss, red(ScalarReducer.Mean), MissingValuePolicy.IgnoreMissing).toOption.get
    assertEquals(c1, Coverage.unsafe(2, 1))
    assertEquals(e1.toOption, Some(2.0))
    val (e2, _) = Reduction
      .reduce(ss, red(ScalarReducer.Mean), MissingValuePolicy.RequireMinCoverage(0.75))
      .toOption
      .get
    assertEquals(e2, Estimate.Missing(MissingReason.Excluded))
    assert(Reduction.reduce(ss, red(ScalarReducer.Mean), MissingValuePolicy.Fail).isLeft)
    val (e3, c3) = Reduction
      .reduce(
        Vector.empty[Sample[Double]],
        red(ScalarReducer.Mean),
        MissingValuePolicy.IgnoreMissing
      )
      .toOption
      .get
    assertEquals(c3, Coverage.empty)
    assertEquals(e3, Estimate.Missing(MissingReason.AllMissing))
  }

  test(
    "imageability demo: windowed 20-by-5 retains coverage; sum grows with words, mean does not"
  ) {
    val raw = imageabilityTrack()
    assert(raw.isRaw)
    // punctuation never receives a value; unknown words are NotInLexicon, not zero
    assert(
      raw.observations
        .filter(o => !sequence.at(o.target.index).get.isLexical)
        .forall(_.estimate == Estimate.Missing(MissingReason.Excluded))
    )
    assert(raw.observations.exists(_.estimate == Estimate.Missing(MissingReason.NotInLexicon)))

    val plan = WindowPlan.words(20, 5)
    val sums = Windowed(
      raw,
      sequence,
      plan,
      ScalarReducer.Sum,
      MissingValuePolicy.IgnoreMissing
    ).toOption.get
    val means = Windowed(
      raw,
      sequence,
      plan,
      ScalarReducer.Mean,
      MissingValuePolicy.IgnoreMissing
    ).toOption.get
    assert(sums.observations.nonEmpty)
    assertEquals(sums.targets, means.targets)
    sums.observations.foreach { o =>
      val cov = o.coverage.get
      assert(cov.eligible <= 20 && cov.observed <= cov.eligible)
      assert(o.support.isDefined)
    }
    // the sum of a full window exceeds the sum of the last, shorter window; the mean need not
    val full = sums.observations.filter(_.coverage.get.eligible == 20)
    val partial = sums.observations.filter(_.coverage.get.eligible < 20)
    assert(full.nonEmpty && partial.nonEmpty)
    val fullSum = full.head.estimate.toOption.get
    val partialSum = partial.last.estimate.toOption.get
    assert(fullSum > partialSum)
    val fullMean = means.byTarget(full.head.target).estimate.toOption.get
    val partialMean = means.byTarget(partial.last.target).estimate.toOption.get
    assert(math.abs(fullMean - partialMean) < math.abs(fullSum - partialSum))
    // ratio check: sum/observed equals mean for every window
    sums.observations.foreach { o =>
      val m = means.byTarget(o.target).estimate.toOption.get
      assertEqualsDouble(o.estimate.toOption.get / o.coverage.get.observed, m, 1e-9)
    }
    // derived track records its recipe and a distinct, deterministic space id
    assert(sums.derivation.exists(_.window.contains(plan)))
    assertNotEquals(sums.space.id, means.space.id)
    assertEquals(
      sums.space.id,
      Windowed(
        raw,
        sequence,
        plan,
        ScalarReducer.Sum,
        MissingValuePolicy.IgnoreMissing
      ).toOption.get.space.id
    )
  }

  test("coverage counts only lexical tokens as eligible under a sentence basis") {
    val raw = imageabilityTrack()
    val plan = WindowPlan.sentences(1, 1)
    val lexical = Windowed(
      raw,
      sequence,
      plan,
      ScalarReducer.Mean,
      MissingValuePolicy.IgnoreMissing
    ).toOption.get
    val all = Windowed(
      raw,
      sequence,
      plan,
      ScalarReducer.Mean,
      MissingValuePolicy.IgnoreMissing,
      Eligibility.AllTokens
    ).toOption.get
    lexical.observations.zip(all.observations).foreach { (l, a) =>
      val lexicalCount = sequence.slice(l.target.range).count(_.isLexical)
      assertEquals(l.coverage.get.eligible, lexicalCount)
      assert(a.coverage.get.eligible >= lexicalCount)
    }
    assert(
      lexical.observations.exists(o =>
        o.coverage.get.eligible < all.byTarget(o.target).coverage.get.eligible
      )
    )
    assertNotEquals(lexical.space.id, all.space.id)
    assertEquals(lexical.derivation.get.eligibility, Eligibility.LexicalTokens)
    assertEquals(lexical.derivation.get.targetFamily, Some(TargetFamily.Window))
    assertEquals(lexical.provenance.basisId, None)
  }

  test("aggregate over discontinuous supports") {
    val raw = imageabilityTrack()
    val s0 = atlas.sentences(0).span
    val s3 = atlas.sentences(3).span
    val disc = SpanSet.of(Vector(SpanRef(s0), SpanRef(s3))).get
    val sid = SituationId.unsafe("sit-1")
    val agg = Aggregate
      .overTargets(
        raw,
        sequence,
        Vector((FeatureTarget.Situation(sid), disc)),
        ScalarReducer.Mean,
        MissingValuePolicy.IgnoreMissing
      )
      .toOption
      .get
    val o = agg.observations.head
    assertEquals(o.target, FeatureTarget.Situation(sid))
    assert(!disc.isContiguous)
    val eligible = sequence.covering(disc).count(_.isLexical)
    assertEquals(o.coverage.get.eligible, eligible)
    assert(o.estimate.isObserved)
    assert(o.estimate.toOption.get > 4.0) // concrete sentences
    // lexicalOnly is part of the recipe: the two variants have distinct ids
    val aggAll = Aggregate
      .overTargets(
        raw,
        sequence,
        Vector((FeatureTarget.Situation(sid), disc)),
        ScalarReducer.Mean,
        MissingValuePolicy.IgnoreMissing,
        lexicalOnly = false
      )
      .toOption
      .get
    assertNotEquals(agg.space.id, aggAll.space.id)
    assertEquals(agg.derivation.get.targetFamily, Some(TargetFamily.Situation))
    assert(
      DerivationGraph.empty
        .add(agg.space.id, agg.derivation.get)
        .toOption
        .get
        .add(aggAll.space.id, aggAll.derivation.get)
        .isRight
    )
  }

  test("track validation: duplicate or unordered targets rejected; restrict and zip work") {
    val raw = imageabilityTrack()
    assert(FeatureTrack.validated(raw).isRight)
    val dup = raw.copy(observations = raw.observations :+ raw.observations.head)
    assert(FeatureTrack.validated(dup).isLeft)
    val unordered = raw.copy(observations = raw.observations.reverse)
    assert(FeatureTrack.validated(unordered).isLeft)
    val resolver = SupportResolver(sequence)
    val r = raw.restrict(SpanSet.one(atlas.sentences(0).span), resolver)
    assertEquals(r.size, atlas.childrenOf(atlas.sentences(0).id).size)
    assertEquals(raw.zip(raw).size, raw.size)
    assertEquals(raw.coverage.eligible, sequence.size)
    assertEquals(raw.coverage.observed, raw.observed.size)

    val basis = BasisId
      .of(
        TargetFamily.Situation,
        Vector(FeatureTarget.Situation(SituationId.unsafe("validation")))
      )
      .toOption
      .get
    assert(
      FeatureTrack
        .validated(raw.copy(provenance = raw.provenance.copy(basisId = Some(basis))))
        .isLeft
    )

    val derived = Aggregate
      .overTargets(
        raw,
        sequence,
        Vector(
          (
            FeatureTarget.Situation(SituationId.unsafe("validation")),
            SpanSet.one(atlas.sentences.head.span)
          )
        ),
        ScalarReducer.Mean,
        MissingValuePolicy.IgnoreMissing
      )
      .toOption
      .get
    val wrongBasis = BasisId
      .of(
        TargetFamily.Situation,
        Vector(FeatureTarget.Situation(SituationId.unsafe("different")))
      )
      .toOption
      .get
    assert(
      FeatureTrack
        .validated(derived.copy(provenance = derived.provenance.copy(basisId = Some(wrongBasis))))
        .isLeft
    )
  }

  test("support resolver resolves surface targets and delegates narrative targets") {
    val resolver = SupportResolver(
      sequence,
      situation = id => if id.value == "x" then Some(SpanSet.one(atlas.sentences(1).span)) else None
    )
    assertEquals(
      resolver.support(FeatureTarget.Token(TokenIndex.Zero)).map(_.minSpan),
      Some(sequence.tokens(0).span)
    )
    assertEquals(
      resolver.support(FeatureTarget.Sentence(atlas.sentences(1).id)).map(_.minSpan),
      Some(atlas.sentences(1).span)
    )
    assertEquals(
      resolver.support(FeatureTarget.Situation(SituationId.unsafe("x"))).map(_.minSpan),
      Some(atlas.sentences(1).span)
    )
    assertEquals(resolver.support(FeatureTarget.Situation(SituationId.unsafe("y"))), None)
    val b = resolver.support(FeatureTarget.Boundary(atlas.sentences(0).id)).get.minSpan
    assert(b.isEmpty && b.start == atlas.sentences(0).span.endExclusive)
    assertEquals(
      resolver.support(FeatureTarget.Window(TokenRange.unsafe(0, 3))).map(_.minSpan),
      Some(sequence.tokens(0).span.hull(sequence.tokens(2).span))
    )
  }

  test("surface-unit targets resolve paragraphs, and share support with the sentence case") {
    val para = atlas.paragraphs.head
    assertEquals(
      resolver.support(FeatureTarget.SurfaceUnit(para.id)).map(_.minSpan),
      Some(para.span)
    )
    val s1 = atlas.sentences(1).id
    assertEquals(
      resolver.support(FeatureTarget.SurfaceUnit(s1)),
      resolver.support(FeatureTarget.Sentence(s1))
    )
    assertNotEquals(FeatureTarget.SurfaceUnit(s1): FeatureTarget, FeatureTarget.Sentence(s1))
    assertEquals(resolver.support(FeatureTarget.SurfaceUnit(SurfaceUnitId.unsafe("nope"))), None)
    // a paragraph-scale aggregate: family SurfaceUnit, coverage over the paragraph's lexical tokens
    val raw = imageabilityTrack()
    val agg = Aggregate
      .overTargets(
        raw,
        sequence,
        Vector((FeatureTarget.SurfaceUnit(para.id), SpanSet.one(para.span))),
        ScalarReducer.Mean,
        MissingValuePolicy.IgnoreMissing
      )
      .toOption
      .get
    assertEquals(agg.derivation.get.targetFamily, Some(TargetFamily.SurfaceUnit))
    assertEquals(agg.observations.head.coverage.get.eligible, sequence.lexicalSize)
    assert(FeatureTrack.validatedScores(agg).isRight)
  }

  test("narrative basis: order kept, unresolved ids and duplicates rejected") {
    val basis = NarrativeBasis.situations(situationOrder, resolver).toOption.get
    assertEquals(basis.family, TargetFamily.Situation)
    assertEquals(basis.targets, situationOrder.map(FeatureTarget.Situation.apply))
    assertEquals(basis.size, atlas.sentences.size)
    val reversed = NarrativeBasis.situations(situationOrder.reverse, resolver).toOption.get
    assertEquals(reversed.targets, situationOrder.reverse.map(FeatureTarget.Situation.apply))
    assertNotEquals(basis.basisId, reversed.basisId)
    NarrativeBasis.situations(situationOrder :+ SituationId.unsafe("ghost"), resolver) match
      case Left(DomainError.InvariantViolation(_, reason)) => assert(reason.contains("ghost"))
      case other => fail(s"expected an unresolved-id violation, got $other")
    NarrativeBasis.situations(situationOrder :+ situationOrder.head, resolver) match
      case Left(DomainError.DuplicateId("FeatureTarget", id)) => assert(id.contains("sit-0"))
      case other => fail(s"expected a duplicate-id error, got $other")
    val segs = NarrativeBasis.segments(segmentOrder, resolver).toOption.get
    assertEquals(segs.family, TargetFamily.Segment)
    assert(NarrativeBasis.segments(Vector(SegmentId.unsafe("x")), resolver).isLeft)
    val empty = NarrativeBasis.situations(Vector.empty, resolver).toOption.get
    assert(empty.isEmpty)
    val emptySegments = NarrativeBasis.segments(Vector.empty, resolver).toOption.get
    assertNotEquals(empty.basisId, emptySegments.basisId)
  }

  test("caller-supplied aggregate bases determine output identity and provenance") {
    val raw = imageabilityTrack()
    val alpha: FeatureTarget.Situation =
      FeatureTarget.Situation(SituationId.unsafe("alpha"))
    val beta: FeatureTarget.Situation = FeatureTarget.Situation(SituationId.unsafe("beta"))
    val targets: Vector[(FeatureTarget.Situation, SpanSet)] = Vector(
      alpha -> SpanSet.one(atlas.sentences(0).span),
      beta -> SpanSet.one(atlas.sentences(1).span)
    )
    def aggregate(ts: Vector[(FeatureTarget.Situation, SpanSet)]) =
      Aggregate
        .overTargets(
          raw,
          sequence,
          ts,
          ScalarReducer.Mean,
          MissingValuePolicy.IgnoreMissing
        )
        .toOption
        .get

    val forward = aggregate(targets)
    val reversed = aggregate(targets.reverse)
    assertEquals(forward.derivation.map(_.derivationId), reversed.derivation.map(_.derivationId))
    assertNotEquals(forward.provenance.basisId, reversed.provenance.basisId)
    assertNotEquals(forward.space.id, reversed.space.id)
    assertEquals(
      forward.provenance.basisId.map(basis => forward.derivation.get.outputSpaceId(basis)),
      Some(forward.space.id)
    )
    assertEquals(
      DerivationGraph.empty
        .add(forward.space.id, forward.derivation.get)
        .flatMap(_.add(reversed.space.id, reversed.derivation.get))
        .map(_.size),
      Right(2)
    )

    val emptySituations = Aggregate
      .overTargets(
        raw,
        sequence,
        TargetFamily.Situation,
        Vector.empty[(FeatureTarget.Situation, SpanSet)],
        ScalarReducer.Mean,
        MissingValuePolicy.IgnoreMissing
      )
      .toOption
      .get
    val emptySegments = Aggregate
      .overTargets(
        raw,
        sequence,
        TargetFamily.Segment,
        Vector.empty[(FeatureTarget.Segment, SpanSet)],
        ScalarReducer.Mean,
        MissingValuePolicy.IgnoreMissing
      )
      .toOption
      .get
    assertNotEquals(emptySituations.provenance.basisId, emptySegments.provenance.basisId)
    assertNotEquals(emptySituations.space.id, emptySegments.space.id)
    assert(
      Aggregate
        .overTargets(
          raw,
          sequence,
          Vector.empty[(FeatureTarget.Situation, SpanSet)],
          ScalarReducer.Mean,
          MissingValuePolicy.IgnoreMissing
        )
        .isLeft
    )

    val mixed: Vector[(FeatureTarget, SpanSet)] = Vector(
      alpha -> SpanSet.one(atlas.sentences(0).span),
      FeatureTarget.Segment(SegmentId.unsafe("scene")) -> SpanSet.one(atlas.sentences(1).span)
    )
    assert(
      Aggregate
        .overTargets(
          raw,
          sequence,
          TargetFamily.Situation,
          mixed,
          ScalarReducer.Mean,
          MissingValuePolicy.IgnoreMissing
        )
        .isLeft
    )
  }

  test("per-situation and per-segment aggregation carry exact support and coverage") {
    val raw = imageabilityTrack()
    val events = Aggregate
      .overSituations(
        raw,
        resolver,
        situationOrder,
        ScalarReducer.Mean,
        MissingValuePolicy.IgnoreMissing
      )
      .toOption
      .get
    assertEquals(events.targets, situationOrder.map(FeatureTarget.Situation.apply))
    events.observations.zip(atlas.sentences).foreach { (o, sent) =>
      val idx = sequence.coveringIndices(SpanSet.one(sent.span))
      assertEquals(o.support, Some(SpanSet.one(sent.span)))
      assertEquals(o.coverage.get.eligible, idx.count(i => sequence.tokens(i.value).isLexical))
      assertEquals(
        o.coverage.get.observed,
        idx.count(i =>
          sequence.tokens(i.value).isLexical &&
            raw.get(FeatureTarget.Token(i)).exists(_.isObserved)
        )
      )
      assert(o.estimate.isObserved)
    }
    val d = events.derivation.get
    assertEquals(d.targetFamily, Some(TargetFamily.Situation))
    assertEquals(d.window, None)
    assertEquals(d.narrativeWindow, None)
    assertEquals(
      events.provenance.basisId,
      Some(NarrativeBasis.situations(situationOrder, resolver).toOption.get.basisId)
    )
    assert(FeatureTrack.validatedScores(events).isRight)
    // scenes: the two segments partition the sentences, so their coverages sum to the whole
    val scenes = Aggregate
      .overSegments(
        raw,
        resolver,
        segmentOrder,
        ScalarReducer.Sum,
        MissingValuePolicy.IgnoreMissing
      )
      .toOption
      .get
    assertEquals(scenes.targets, segmentOrder.map(FeatureTarget.Segment.apply))
    assertEquals(scenes.observations.map(_.coverage.get).reduce(_ + _), lexicalCoverage(raw))
    assertEquals(scenes.derivation.get.targetFamily, Some(TargetFamily.Segment))
    assertEqualsDouble(
      scenes.observations.map(_.estimate.toOption.get).sum,
      raw.observed.map(_._2).sum,
      1e-9
    )
    // an empty basis still records its family
    val none = Aggregate
      .overSituations(
        raw,
        resolver,
        Vector.empty,
        ScalarReducer.Mean,
        MissingValuePolicy.IgnoreMissing
      )
      .toOption
      .get
    assertEquals(none.size, 0)
    assertEquals(none.derivation.get.targetFamily, Some(TargetFamily.Situation))
    assert(
      Aggregate
        .overSituations(
          raw,
          resolver,
          Vector(SituationId.unsafe("ghost")),
          ScalarReducer.Mean,
          MissingValuePolicy.IgnoreMissing
        )
        .isLeft
    )
  }

  test("centred narrative windows: per-unit at half-width 0, pooled neighbours at half-width 1") {
    val raw = imageabilityTrack()
    val basis = NarrativeBasis.situations(situationOrder, resolver).toOption.get
    def windowed[T <: FeatureTarget](
        plan: NarrativeWindowPlan,
        b: NarrativeBasis[T]
    ): FeatureTrack[T, Double] =
      Windowed
        .overBasis(raw, sequence, b, plan, ScalarReducer.Mean, MissingValuePolicy.IgnoreMissing)
        .toOption
        .get
    val perUnit = windowed(NarrativeWindowPlan.perUnit, basis)
    val agg = Aggregate
      .overBasis(raw, sequence, basis, ScalarReducer.Mean, MissingValuePolicy.IgnoreMissing)
      .toOption
      .get
    assertEquals(perUnit.targets, agg.targets)
    perUnit.observations.zip(agg.observations).foreach { (w, a) =>
      assertEquals(w.estimate, a.estimate)
      assertEquals(w.coverage, a.coverage)
      assertEquals(w.support, a.support)
    }
    // same values, different recipe: the window slot is part of the id
    assertNotEquals(perUnit.space.id, agg.space.id)
    assertEquals(perUnit.derivation.get.narrativeWindow, Some(NarrativeWindowPlan.perUnit))
    assertEquals(perUnit.derivation.get.targetFamily, Some(TargetFamily.Situation))
    assertEquals(perUnit.provenance.basisId, Some(basis.basisId))
    assert(perUnit.derivation.get.hasSingleWindow)

    val one = NarrativeWindowPlan.of(1).toOption.get
    val smooth = windowed(one, basis)
    assertEquals(smooth.targets, basis.targets)
    assertNotEquals(smooth.space.id, perUnit.space.id)
    // the middle unit pools three sentences: support is their union, coverage counts each token once
    val mid = smooth.observations(2)
    val trio = Vector(1, 2, 3).map(i => atlas.sentences(i).span)
    assertEquals(mid.support, SpanSet.of(trio.map(SpanRef(_))))
    val idx =
      sequence.coveringIndices(mid.support.get).filter(i => sequence.tokens(i.value).isLexical)
    assertEquals(mid.coverage.get.eligible, idx.size)
    val pooled = idx.flatMap(i => raw.get(FeatureTarget.Token(i)).flatMap(_.toOption))
    assertEqualsDouble(mid.estimate.toOption.get, pooled.sum / pooled.size, 1e-9)
    assertEquals(mid.coverage.get.observed, pooled.size)
    // the first unit is clipped: it pools sentences 0 and 1 only
    val first = smooth.observations.head
    assertEquals(first.support, SpanSet.of(Vector(0, 1).map(i => SpanRef(atlas.sentences(i).span))))
    assertEquals(
      first.coverage.get,
      agg.observations(0).coverage.get + agg.observations(1).coverage.get
    )
    assert(FeatureTrack.validatedScores(smooth).isRight)
    // half-width is part of the recipe
    val wider = windowed(NarrativeWindowPlan.of(2).toOption.get, basis)
    assertNotEquals(wider.space.id, smooth.space.id)
    assertEquals(wider.observations(2).coverage.get, lexicalCoverage(raw))
    // scenes smooth the same way, targeted at segments
    val scenes = windowed(one, NarrativeBasis.segments(segmentOrder, resolver).toOption.get)
    assertEquals(scenes.targets, segmentOrder.map(FeatureTarget.Segment.apply))
    assertEquals(scenes.derivation.get.targetFamily, Some(TargetFamily.Segment))
    scenes.observations.foreach(o => assertEquals(o.coverage.get, lexicalCoverage(raw)))
  }

  test("narrative kernels measure bandwidth in units and never reach outside their support") {
    val raw = imageabilityTrack()
    // a middle situation whose only token is not in the lexicon
    val nobody = sequence.tokens.indexWhere(_.normalized.contains("nobody"))
    assert(nobody >= 0)
    // ids sort a < b < c, so observation 1 is the gap
    val ids = Vector("a", "b", "c").map(SituationId.unsafe)
    val supports: Map[SituationId, SpanSet] = Map(
      ids(0) -> SpanSet.one(atlas.sentences(0).span),
      ids(1) -> SpanSet.one(sequence.tokens(nobody).span),
      ids(2) -> SpanSet.one(atlas.sentences(3).span)
    )
    val r = SupportResolver(sequence, situation = supports.get)
    val basis = NarrativeBasis.situations(ids, r).toOption.get
    val one = NarrativeWindowPlan.of(1).toOption.get
    def kernel(shape: KernelShape) =
      Windowed
        .overBasis(
          raw,
          sequence,
          basis,
          one,
          ScalarReducer.Kernel(shape),
          MissingValuePolicy.IgnoreMissing
        )
        .toOption
        .get
    val perUnit = Aggregate
      .overBasis(raw, sequence, basis, ScalarReducer.Mean, MissingValuePolicy.IgnoreMissing)
      .toOption
      .get
    // a point mass at the centre unit reproduces the per-unit mean where the unit is observed
    val point = kernel(KernelShape.Gaussian(0.0))
    assertEquals(point.observations(0).estimate, perUnit.observations(0).estimate)
    assertEquals(point.observations(2).estimate, perUnit.observations(2).estimate)
    // ... and falls back to the nearest observed unit where it is not
    assertEquals(perUnit.observations(1).estimate, Estimate.Missing(MissingReason.AllMissing))
    assert(point.observations(1).estimate.isObserved)
    // a rectangular kernel narrower than one unit sees only the centre: the gap is outside support
    val narrow = kernel(KernelShape.Rectangular(0.5))
    assertEquals(
      narrow.observations(1).estimate,
      Estimate.Missing(MissingReason.Undefined(UndefinedReason.OutsideKernelSupport))
    )
    assertEquals(narrow.observations(0).estimate, perUnit.observations(0).estimate)
    // a rectangular kernel of one unit pools the neighbours uniformly
    val wide = kernel(KernelShape.Rectangular(1.0))
    val pooled = Vector(0, 2).flatMap(i =>
      sequence
        .coveringIndices(supports(ids(i)))
        .flatMap(j => raw.get(FeatureTarget.Token(j)).flatMap(_.toOption))
    )
    assertEqualsDouble(wide.observations(1).estimate.toOption.get, pooled.sum / pooled.size, 1e-9)
    // the point-mass fallback averages every observed sample of the nearest units (here the two
    // neighbours at distance 1, several samples each), never a single sample
    assert(pooled.size >= 2)
    assertEqualsDouble(point.observations(1).estimate.toOption.get, pooled.sum / pooled.size, 1e-9)
    // the recipe distinguishes kernels by shape and bandwidth
    assertNotEquals(narrow.space.id, wide.space.id)
    assertNotEquals(point.space.id, narrow.space.id)
    assertEquals(wide.derivation.get.weighting, WeightingPolicy.Kernel("rectangular", 1.0))
    // the strict missing policy refuses the gap
    assert(
      Windowed
        .overBasis(
          raw,
          sequence,
          basis,
          NarrativeWindowPlan.perUnit,
          ScalarReducer.Mean,
          MissingValuePolicy.Fail
        )
        .isLeft
    )
  }
