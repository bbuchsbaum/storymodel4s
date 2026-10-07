package storymodel4s.features

import cats.Functor
import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

/** Distinguish an inapplicable target from missing evidence without changing support geometry. */
class EligibilitySuite extends FunSuite:
  private val missing: Estimate[Double] = Estimate.missing(MissingReason.NotInLexicon)
  private val reducers = Vector(
    ScalarReducer.Sum,
    ScalarReducer.Mean,
    ScalarReducer.WeightedMean,
    ScalarReducer.Maximum,
    ScalarReducer.Variance,
    ScalarReducer.Slope,
    ScalarReducer.Kernel(KernelShape.Rectangular(1.0))
  )
  private def reduce(samples: Vector[Sample[Double]], policy: MissingValuePolicy) =
    Reduction.reduce(samples, WindowReducer.scalar(ScalarReducer.Mean), policy)

  test("eligibility, fold and Functor preserve all three outcomes and observed credence") {
    val credence = Some(Credence.unsafeRaw(0.4, ScorerId.unsafe("eligibility-court")))
    val observed: Estimate[Double] = Estimate.Observed(0.0, credence)
    assertEquals(
      Vector(observed, missing, Estimate.Ineligible).map(_.isEligible),
      Vector(true, true, false)
    )
    assertEquals(
      Vector(observed, missing, Estimate.Ineligible).map(_.isObserved),
      Vector(true, false, false)
    )
    assertEquals(Estimate.Ineligible.toOption, None)
    assertEquals(observed.fold(None, (v, c) => Some((v, c)), _ => None), Some((0.0, credence)))
    assertEquals(missing.fold("ineligible", (_, _) => "observed", _.toString), "NotInLexicon")
    assertEquals(
      Estimate.Ineligible.fold("ineligible", (_, _) => "observed", _.toString),
      "ineligible"
    )
    var calls = 0
    def mapped(v: Double): Double = { calls += 1; v + 2.0 }
    assertEquals(Functor[Estimate].map(missing)(mapped), missing)
    assertEquals(
      Functor[Estimate].map(Estimate.Ineligible: Estimate[Double])(mapped),
      Estimate.Ineligible
    )
    assertEquals(calls, 0)
    assertEquals(observed.map(mapped), Estimate.Observed(2.0, credence))
    assertEquals(calls, 1)
  }

  test("coverage and missing policies exclude only ineligible targets") {
    val samples = Vector(
      Sample(0, Estimate.Ineligible, 1.0),
      Sample(2, Estimate.observed(0.0), 1.0),
      Sample(4, missing, 1.0)
    )
    assertEquals(
      reduce(samples, MissingValuePolicy.IgnoreMissing),
      Right((Estimate.observed(0.0), Coverage.unsafe(2, 1)))
    )
    assert(reduce(samples, MissingValuePolicy.Fail).isLeft)
    assertEquals(
      reduce(samples, MissingValuePolicy.RequireMinCoverage(0.5)),
      Right((Estimate.observed(0.0), Coverage.unsafe(2, 1)))
    )
    assertEquals(
      reduce(samples, MissingValuePolicy.RequireMinCoverage(0.75)),
      Right((Estimate.missing(MissingReason.Excluded), Coverage.unsafe(2, 1)))
    )
    val track = FeatureTrack.raw(
      Fixtures.imageabilitySpace,
      samples.map(s =>
        FeatureObservation(
          FeatureTarget.Token(TokenIndex.unsafe(s.position)),
          s.estimate,
          None,
          None
        )
      ),
      Fixtures.provenance
    )
    assertEquals(track.coverage, Coverage.unsafe(2, 1))
  }

  test("nonempty all-ineligible support differs from actually empty and all-missing support") {
    val ss = Vector(Sample(0, Estimate.Ineligible: Estimate[Double], 1.0))
    Vector(
      MissingValuePolicy.IgnoreMissing,
      MissingValuePolicy.Fail,
      MissingValuePolicy.RequireMinCoverage(1.0)
    ).foreach { policy =>
      assertEquals(reduce(ss, policy), Right((Estimate.Ineligible, Coverage.empty)))
      assertEquals(
        reduce(Vector.empty, policy),
        Right((Estimate.missing(MissingReason.AllMissing), Coverage.empty))
      )
    }
    reducers.foreach { r =>
      assertEquals(
        WindowReducer.scalar(r).reduce(NonEmptyVector.fromVectorUnsafe(ss)),
        Estimate.Ineligible,
        r.toString
      )
      assertEquals(
        WindowReducer.scalar(r).reduce(NonEmptyVector.one(Sample(0, missing, 1.0))),
        Estimate.missing(MissingReason.AllMissing),
        r.toString
      )
    }
    assertEquals(
      WindowReducer
        .kernelAt(KernelShape.Rectangular(1.0), _.position.toDouble)
        .reduce(NonEmptyVector.fromVectorUnsafe(ss)),
      Estimate.Ineligible
    )
  }

  test("invalid policies and weights are refused before the ineligible shortcut") {
    val ss = Vector(Sample(0, Estimate.Ineligible: Estimate[Double], 1.0))
    assert(reduce(ss, MissingValuePolicy.RequireMinCoverage(Double.NaN)).isLeft)
    val bad = ss.map(_.copy(weight = Double.NaN))
    assert(reduce(bad, MissingValuePolicy.IgnoreMissing).isLeft)
    assertEquals(
      WindowReducer.scalar(ScalarReducer.Mean).reduce(NonEmptyVector.fromVectorUnsafe(bad)),
      Estimate.missing(
        MissingReason.Undefined(UndefinedReason.Custom("features", "invalid-sample-weight"))
      )
    )
  }

  test("ineligible samples retain the original kernel centre and slope positions") {
    val ss = NonEmptyVector.of(
      Sample(0, Estimate.Ineligible, 1.0),
      Sample(2, Estimate.observed(0.0), 1.0),
      Sample(4, Estimate.observed(10.0), 1.0)
    )
    val kernel = WindowReducer.scalar(ScalarReducer.Kernel(KernelShape.Rectangular(1.0)))
    assertEquals(kernel.reduce(ss), Estimate.observed(0.0))
    val prematurelyFiltered =
      NonEmptyVector.fromVectorUnsafe(ss.toVector.filter(_.estimate.isEligible))
    assertEquals(kernel.reduce(prematurelyFiltered), Estimate.observed(5.0))
    assertEquals(WindowReducer.scalar(ScalarReducer.Slope).reduce(ss), Estimate.observed(5.0))
    assertEquals(
      Reduction.reduce(ss.toVector, kernel, MissingValuePolicy.Fail),
      Right((Estimate.observed(0.0), Coverage.unsafe(2, 2)))
    )
  }

  test("undefined numeric operations remain eligible missing, never ineligible") {
    val zero = NonEmptyVector.one(Sample(0, Estimate.observed(1.0), 0.0))
    assertEquals(
      WindowReducer.scalar(ScalarReducer.WeightedMean).reduce(zero),
      Estimate.missing(MissingReason.Undefined(UndefinedReason.ZeroTotalWeight))
    )
    val nonfinite = NonEmptyVector.one(Sample(0, Estimate.Observed(Double.NaN, None), 1.0))
    assertEquals(
      WindowReducer.scalar(ScalarReducer.Mean).reduce(nonfinite),
      Estimate.missing(MissingReason.Undefined(UndefinedReason.NotFinite))
    )
  }
