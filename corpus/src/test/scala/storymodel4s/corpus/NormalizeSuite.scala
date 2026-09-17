package storymodel4s.corpus

import munit.FunSuite

/** `normalize` is the contract `Applicability` rests on: two literals meaning the same value must
  * render identically, and two different values must never collide. If either fails, a condition
  * holds on some rows and not others for no reason in the data -- the exact defect the declared
  * encoding exists to remove.
  */
class NormalizeSuite extends FunSuite:
  private val art = ArtifactId.unsafe("s.xlsx")
  private def at = SourceCoordinate.at(art, "S", 1, "C").fold(e => fail(e.message), identity)

  private def norm(lit: String, enc: CellEncoding) =
    Cell.normalize(at, "C", lit, Some(enc))

  test("equal integers under IntegerOrWholeDecimalText render identically") {
    val forms = Vector("1", "1.0", "1.00", " 1 ", "+1")
    val rendered = forms.map(f => norm(f, CellEncoding.IntegerOrWholeDecimalText))
    assert(rendered.forall(_.isRight), rendered.toString)
    assertEquals(rendered.map(_.toOption.flatten).distinct.size, 1, rendered.toString)
  }

  test("DecimalText: equal decimals render identically") {
    val equal = Vector("1.1", "1.10", "1.100")
    val rendered = equal.map(f => norm(f, CellEncoding.DecimalText).toOption.flatten)
    assertEquals(rendered.distinct.size, 1, rendered.toString)
  }

  test("DecimalText: scientific and plain forms of one value must not diverge") {
    val sci = norm("1E+2", CellEncoding.DecimalText).toOption.flatten
    val plain = norm("100", CellEncoding.DecimalText).toOption.flatten
    assertEquals(sci, plain, s"1E+2 rendered $sci but 100 rendered $plain")
  }

  test("DecimalText: zero has ONE rendering") {
    val zeros = Vector("0", "0.0", "0.00", "-0", "-0.0")
      .map(f => norm(f, CellEncoding.DecimalText).toOption.flatten)
    assertEquals(zeros.distinct.size, 1, zeros.toString)
  }

  test("DecimalText: different values never collide") {
    val a = norm("1.1", CellEncoding.DecimalText).toOption.flatten
    val b = norm("1.2", CellEncoding.DecimalText).toOption.flatten
    assertNotEquals(a, b)
  }

  test("an integer beyond Long range is REFUSED, not silently wrapped") {
    val huge = "92233720368547758080"
    assert(norm(huge, CellEncoding.IntegerOrWholeDecimalText).isLeft, "overflow was not refused")
    assert(norm(huge, CellEncoding.IntegerText).isLeft, "overflow was not refused")
  }

  test("normalize and decode agree: what normalizes must decode, and conversely") {
    val cases = Vector(
      ("1.0", CellEncoding.IntegerOrWholeDecimalText),
      ("1.000439814814815", CellEncoding.ExcelSerialDays(1)),
      ("6.31", CellEncoding.MinuteDotSecond)
    )
    cases.foreach { (lit, enc) =>
      val n = Cell.normalize(at, "C", lit, Some(enc))
      val d =
        if enc == CellEncoding.IntegerOrWholeDecimalText then Cell.integer(at, "C", lit, Some(enc))
        else Cell.seconds(at, "C", lit, Some(enc))
      assertEquals(n.isRight, d.isRight, s"$lit under ${enc.render}")
      assertEquals(n.toOption.flatten, d.toOption.flatten.map(_.value.toString), s"$lit")
    }
  }
