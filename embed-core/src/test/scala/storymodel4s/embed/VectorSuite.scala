package storymodel4s.embed

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import storymodel4s.features.{Estimate, MissingReason}

class VectorSuite extends ScalaCheckSuite:
  private val d4 = Dimension.unsafe(4)

  test("dimension, finiteness and normalization are checked at construction") {
    assert(ValidatedVector.of(d4, Normalization.Unnormalized, Vector(1.0, 2.0, 3.0)).isLeft)
    assert(
      ValidatedVector.of(d4, Normalization.Unnormalized, Vector(1.0, Double.NaN, 3.0, 4.0)).isLeft
    )
    assert(
      ValidatedVector
        .of(d4, Normalization.Unnormalized, Vector(1.0, Double.PositiveInfinity, 3.0, 4.0))
        .isLeft
    )
    assert(ValidatedVector.of(d4, Normalization.L2, Vector(1.0, 2.0, 3.0, 4.0)).isLeft)
    assert(ValidatedVector.of(d4, Normalization.L2, Vector(1.0, 0.0, 0.0, 0.0)).isRight)
    assert(ValidatedVector.of(d4, Normalization.L2, Vector(1.0 + 5e-7, 0.0, 0.0, 0.0)).isRight)
    assert(ValidatedVector.l2(d4, Vector(0.0, 0.0, 0.0, 0.0)).isLeft)
    assert(Dimension.of(0).isLeft && Dimension.of(-3).isLeft && Dimension.of(1).isRight)
  }

  test("ValidatedVector retains structural value semantics without exposing coordinates") {
    val first =
      ValidatedVector.of(d4, Normalization.Unnormalized, Vector(1.0, 2.0, 3.0, 4.0)).toOption.get
    val same =
      ValidatedVector.of(d4, Normalization.Unnormalized, Vector(1.0, 2.0, 3.0, 4.0)).toOption.get
    val different =
      ValidatedVector.of(d4, Normalization.Unnormalized, Vector(4.0, 3.0, 2.0, 1.0)).toOption.get

    assertEquals(first, same)
    assertEquals(first.hashCode, same.hashCode)
    assertNotEquals(first, different)
    assertEquals(
      first.toString,
      "ValidatedVector(dimension=4, normalization=unnormalized, size=4)"
    )
  }

  property("l2 produces unit vectors within tolerance") {
    forAll(Gens.dimension.flatMap(d => Gens.unit(d))) { v =>
      math.abs(v.norm - 1.0) <= ValidatedVector.NormTolerance && v.normalization == Normalization.L2
    }
  }

  property("cosine distance is symmetric, in [0, 2], zero on self") {
    forAll(Gens.dimension.flatMap(d => Gens.unit(d).flatMap(a => Gens.unit(d).map(b => (a, b))))) {
      case (a, b) =>
        val ab = Distances.cosine(a, b).toOption.get.value
        val ba = Distances.cosine(b, a).toOption.get.value
        val aa = Distances.cosine(a, a).toOption.get.value
        math.abs(ab - ba) < 1e-12 && ab >= 0.0 && ab <= 2.0 + 1e-12 && aa < 1e-9
    }
  }

  property(
    "euclidean distance is symmetric and non-negative; mismatched dimensions are typed errors"
  ) {
    forAll(
      Gens.dimension.flatMap(d =>
        Gens.unnormalized(d).flatMap(a => Gens.unnormalized(d).map(b => (a, b)))
      )
    ) { case (a, b) =>
      val ab = Distances.euclidean(a, b).toOption.get.value
      val ba = Distances.euclidean(b, a).toOption.get.value
      val other = ValidatedVector
        .of(
          Dimension.unsafe(a.dimension.value + 1),
          Normalization.Unnormalized,
          Vector.fill(a.dimension.value + 1)(0.0)
        )
        .toOption
        .get
      math.abs(ab - ba) < 1e-9 && ab >= 0.0 && Distances.euclidean(a, other).isLeft && a
        .dot(other)
        .isLeft
    }
  }

  test("distance over estimates propagates Missing rather than substituting a number") {
    val a = ValidatedVector.l2(d4, Vector(1.0, 0.0, 0.0, 0.0)).toOption.get
    val missing: Estimate[ValidatedVector] = Estimate.missing(MissingReason.ProviderAbstained)
    assertEquals(
      Distances.cosineEstimate(Estimate.observed(a), missing),
      Estimate.Missing(MissingReason.ProviderAbstained)
    )
    assertEquals(
      Distances.cosineEstimate(missing, Estimate.observed(a)),
      Estimate.Missing(MissingReason.ProviderAbstained)
    )
    assert(Distances.cosineEstimate(Estimate.observed(a), Estimate.observed(a)).isObserved)
  }

  test("truncation re-normalizes and rejects non-shrinking targets") {
    val v = ValidatedVector.l2(d4, Vector(3.0, 4.0, 0.0, 0.0)).toOption.get
    val t = v.truncated(Dimension.unsafe(2)).toOption.get
    assertEquals(t.dimension.value, 2)
    assert(math.abs(t.norm - 1.0) < 1e-9)
    assert(v.truncated(d4).isLeft)
    assert(
      ValidatedVector
        .l2(d4, Vector(0.0, 0.0, 1.0, 0.0))
        .toOption
        .get
        .truncated(Dimension.unsafe(2))
        .isLeft
    )
  }

  test("ValidatedDistance rejects negative and non-finite values") {
    assert(ValidatedDistance.of(-0.1).isLeft)
    assert(ValidatedDistance.of(Double.NaN).isLeft)
    assert(ValidatedDistance.of(0.0).isRight)
  }
