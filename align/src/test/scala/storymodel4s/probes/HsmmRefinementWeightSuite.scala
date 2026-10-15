package hsmmconfigprobe

import storymodel4s.align.*

/** The public configuration boundary admits finite weights, even before refinement is enabled. */
class HsmmRefinementWeightSuite extends munit.FunSuite:
  test("positive infinity is refused at the public refinement-weight boundary") {
    Vector(0, 1).foreach { passes =>
      assert(
        HsmmConfig.of(refinementPasses = passes, refinementWeight = Double.PositiveInfinity).isLeft
      )
    }
  }

  test("NaN, negative infinity and negative finite weights remain refused") {
    Vector(Double.NaN, Double.NegativeInfinity, -1.0, -Double.MinPositiveValue).foreach { weight =>
      assert(HsmmConfig.of(refinementWeight = weight).isLeft)
    }
  }

  test("zero and finite positive weights preserve their supplied values") {
    Vector(0.0, Double.MinPositiveValue, 0.3, Double.MaxValue).foreach { weight =>
      val config = HsmmConfig.of(refinementPasses = 1, refinementWeight = weight).toOption.get
      assertEquals(config.refinementPasses, 1)
      assertEquals(config.refinementWeight, weight)
      assertEquals(config.layerUse, LayerUse.of(config.transitions))
    }
  }

  test("negative zero has the same normalized bits and identity as positive zero") {
    val minus = HsmmConfig.of(refinementWeight = -0.0).toOption.get
    val plus = HsmmConfig.of(refinementWeight = 0.0).toOption.get
    assertEquals(java.lang.Double.doubleToRawLongBits(minus.refinementWeight), 0L)
    assertEquals(minus, plus)
    assertEquals(minus.fingerprint, plus.fingerprint)
  }
