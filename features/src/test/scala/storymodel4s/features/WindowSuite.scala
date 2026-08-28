package storymodel4s.features

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import storymodel4s.core.*

class WindowSuite extends ScalaCheckSuite:
  import Fixtures.*

  private def red(r: ScalarReducer) = WindowReducer.scalar(r)
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
