package storymodel4s.align

import munit.FunSuite

class AlignmentMathSuite extends FunSuite:
  private def bits(x: Double): Long = java.lang.Double.doubleToLongBits(x)
  private def value(x: Long): Double = java.lang.Double.longBitsToDouble(x)

  test("historical first divergent exp input has the independently frozen reference bits") {
    val row = AlignmentMathOracle.rows.find(_._1 == 0xc01764ec3fc51648L).get
    assertEquals(bits(AlignmentMath.exp(value(row._1))), row._2)
  }

  test("posterior and flow exponents have the independently frozen reference bits") {
    Vector(0xc017cc3a9d9858b8L, 0xc02254b1cb8d139aL).foreach { input =>
      val row = AlignmentMathOracle.rows.find(_._1 == input).get
      assertEquals(bits(AlignmentMath.exp(value(input))), row._2)
    }
  }

  test("exp matches every frozen original-C vector exactly") {
    AlignmentMathOracle.rows.foreach { (input, expected, _) =>
      assertEquals(bits(AlignmentMath.exp(value(input))), expected, s"input bits: $input")
    }
  }

  test("log matches every frozen original-C vector exactly") {
    AlignmentMathOracle.rows.foreach { (input, _, expected) =>
      assertEquals(bits(AlignmentMath.log(value(input))), expected, s"input bits: $input")
    }
  }

  test("signed zero, infinity and NaN preserve the stated value-class contract") {
    assertEquals(bits(AlignmentMath.exp(-0.0)), bits(1.0))
    assertEquals(bits(AlignmentMath.exp(Double.NegativeInfinity)), bits(0.0))
    assertEquals(AlignmentMath.exp(Double.PositiveInfinity), Double.PositiveInfinity)
    assertEquals(AlignmentMath.log(-0.0), Double.NegativeInfinity)
    assertEquals(AlignmentMath.log(Double.PositiveInfinity), Double.PositiveInfinity)
    assert(AlignmentMath.log(-1.0).isNaN)
    assert(AlignmentMath.exp(Double.NaN).isNaN)
    assert(AlignmentMath.log(Double.NaN).isNaN)
  }
