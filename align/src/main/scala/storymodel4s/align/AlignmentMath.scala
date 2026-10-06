/*
 * Copyright (C) 1993, 2004 by Sun Microsystems, Inc. All rights reserved.
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this software is freely
 * granted, provided that this notice is preserved.
 *
 * Scala adaptation of Netlib fdlibm __ieee754_exp and __ieee754_log.
 * Exact upstream files, hashes and independent C oracle: tools/numerical/fdlibm/.
 */
package storymodel4s.align

/** Owns exponential/logarithm rounding instead of delegating it to a target's libm.
  *
  * This is the fdlibm operation sequence, not a correctly-rounded arbitrary-precision algorithm.
  * Binary64 operations must retain their order, gradual underflow and separate multiply/add
  * rounding. NaN payloads and floating-point exception flags are outside the contract.
  */
private[align] object AlignmentMath:
  private def bits(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)
  private def value(x: Long): Double = java.lang.Double.longBitsToDouble(x)
  private def high(x: Double): Int = (bits(x) >>> 32).toInt
  private def withHigh(x: Double, word: Int): Double =
    value((word.toLong << 32) | (bits(x) & 0xffffffffL))

  private val ln2Hi = value(0x3fe62e42fee00000L)
  private val ln2Lo = value(0x3dea39ef35793c76L)
  private val invLn2 = value(0x3ff71547652b82feL)
  private val twoMinus1000 = value(0x0170000000000000L)
  private val overflow = value(0x40862e42fefa39efL)
  private val underflow = value(0xc0874910d52d3051L)
  private val p1 = value(0x3fc555555555553eL)
  private val p2 = value(0xbf66c16c16bebd93L)
  private val p3 = value(0x3f11566aaf25de2cL)
  private val p4 = value(0xbebbbd41c5d26bf1L)
  private val p5 = value(0x3e66376972bea4d0L)
  private val lg1 = value(0x3fe5555555555593L)
  private val lg2 = value(0x3fd999999997fa04L)
  private val lg3 = value(0x3fd2492494229359L)
  private val lg4 = value(0x3fcc71c51d8e78afL)
  private val lg5 = value(0x3fc7466496cb03deL)
  private val lg6 = value(0x3fc39a09d078c69fL)
  private val lg7 = value(0x3fc2f112df3e5244L)

  def exp(input: Double): Double =
    val word = high(input)
    val sign = (word >>> 31) & 1
    val magnitude = word & 0x7fffffff
    if input.isNaN then Double.NaN
    else if input == Double.PositiveInfinity then input
    else if input == Double.NegativeInfinity || input < underflow then 0.0
    else if input > overflow then Double.PositiveInfinity
    else
      var x = input
      var hi = 0.0
      var lo = 0.0
      var k = 0
      if magnitude > 0x3fd62e42 then
        if magnitude < 0x3ff0a2b2 then
          hi = x - (if sign == 0 then ln2Hi else -ln2Hi)
          lo = if sign == 0 then ln2Lo else -ln2Lo
          k = 1 - sign - sign
        else
          k = (invLn2 * x + (if sign == 0 then 0.5 else -0.5)).toInt
          val t = k.toDouble
          hi = x - t * ln2Hi
          lo = t * ln2Lo
        x = hi - lo
      else if magnitude < 0x3e300000 then return 1.0 + x
      val t = x * x
      val c = x - t * (p1 + t * (p2 + t * (p3 + t * (p4 + t * p5))))
      if k == 0 then 1.0 - ((x * c) / (c - 2.0) - x)
      else
        val y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi)
        if k >= -1021 then withHigh(y, high(y) + (k << 20))
        else withHigh(y, high(y) + ((k + 1000) << 20)) * twoMinus1000

  def log(input: Double): Double =
    if input.isNaN || input < 0.0 then Double.NaN
    else if input == 0.0 then Double.NegativeInfinity
    else if input == Double.PositiveInfinity then input
    else
      var x = input
      var hx = high(x)
      var k = 0
      if hx < 0x00100000 then
        k -= 54
        x *= value(0x4350000000000000L)
        hx = high(x)
      k += (hx >> 20) - 1023
      hx &= 0x000fffff
      val i = (hx + 0x95f64) & 0x100000
      x = withHigh(x, hx | (i ^ 0x3ff00000))
      k += i >> 20
      val f = x - 1.0
      val dk = k.toDouble
      if (0x000fffff & (2 + hx)) < 3 then
        if f == 0.0 then if k == 0 then 0.0 else dk * ln2Hi + dk * ln2Lo
        else
          val r = f * f * (0.5 - 0.33333333333333333 * f)
          if k == 0 then f - r else dk * ln2Hi - ((r - dk * ln2Lo) - f)
      else
        val s = f / (2.0 + f)
        val z = s * s
        val w = z * z
        val t1 = w * (lg2 + w * (lg4 + w * lg6))
        val t2 = z * (lg1 + w * (lg3 + w * (lg5 + w * lg7)))
        val r = t2 + t1
        if ((hx - 0x6147a) | (0x6b851 - hx)) > 0 then
          val hfsq = 0.5 * f * f
          if k == 0 then f - (hfsq - s * (hfsq + r))
          else dk * ln2Hi - ((hfsq - (s * (hfsq + r) + dk * ln2Lo)) - f)
        else if k == 0 then f - s * (f - r)
        else dk * ln2Hi - ((s * (f - r) - dk * ln2Lo) - f)
