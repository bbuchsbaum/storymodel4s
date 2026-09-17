package storymodel4s.corpus

import munit.FunSuite

/** Every case below is a hazard measured on real corpus bytes, not an invented edge. */
class CellSuite extends FunSuite:
  private val art = ArtifactId.unsafe("friendsStoryBoard.xlsx")
  private def at(col: String): SourceCoordinate =
    SourceCoordinate.at(art, "FriendsNarrComb", 3, col).fold(e => fail(e.message), identity)

  private def secs(lit: String, enc: CellEncoding) =
    Cell.seconds(at("Time"), "Time", lit, Some(enc))
  private def int(lit: String, enc: CellEncoding) =
    Cell.integer(at("WhichStoryline"), "WhichStoryline", lit, Some(enc))

  test("no declared encoding is an UNCONDITIONAL refusal, even when one encoding fits") {
    // '35' parses under exactly one encoding; the reader still refuses, because inference is the
    // defect, not ambiguity.
    Cell.integer(at("WhichEvent"), "WhichEvent", "35", None) match
      case Left(CellRefusal.NoDeclaredEncoding(_, c)) => assertEquals(c, "WhichEvent")
      case other => fail(s"expected NoDeclaredEncoding, got $other")
    assert(Cell.seconds(at("Time"), "Time", "1.5", None).isLeft)
    assert(Cell.text(at("Place"), "Place", "Central Perk", None).isLeft)
  }

  test("'1' and 1.0 are ONE code under IntegerOrWholeDecimalText") {
    // measured: every Friends WhichStoryline code appears as both a string and a float
    assertEquals(
      int("1", CellEncoding.IntegerOrWholeDecimalText).map(_.map(_.value)),
      Right(Some(1L))
    )
    assertEquals(
      int("1.0", CellEncoding.IntegerOrWholeDecimalText).map(_.map(_.value)),
      Right(Some(1L))
    )
    // and a genuine fraction is still refused
    assert(int("1.5", CellEncoding.IntegerOrWholeDecimalText).isLeft)
  }

  test("IntegerText admits only digits, so a mixed column must declare which it is") {
    assertEquals(int("1", CellEncoding.IntegerText).map(_.map(_.value)), Right(Some(1L)))
    assert(int("1.0", CellEncoding.IntegerText).isLeft)
  }

  test("Excel serial days read as whole seconds from the sheet's own origin") {
    // measured from FriendsNarrComb: event 1 sits at Time 1.0 (0 s), event 2 at 38 s, and
    // TimeOrig for event 2 is 85 s
    assertEquals(secs("1.0", CellEncoding.ExcelSerialDays(1)).map(_.map(_.value)), Right(Some(0L)))
    assertEquals(
      secs("1.000439814814815", CellEncoding.ExcelSerialDays(1)).map(_.map(_.value)),
      Right(Some(38L))
    )
    assertEquals(
      secs("1.000983796296296", CellEncoding.ExcelSerialDays(1)).map(_.map(_.value)),
      Right(Some(85L))
    )
  }

  test("min.sec is split, not read as a number") {
    // 6.31 is 6:31 = 391 s. Read as a number it would be 6.31 s -- a 385-second error.
    assertEquals(secs("6.31", CellEncoding.MinuteDotSecond).map(_.map(_.value)), Right(Some(391L)))
    // the measured dirty-float form of a Film Festival time
    assertEquals(
      secs("6.1000000000000005", CellEncoding.MinuteDotSecond).map(_.map(_.value)),
      Right(Some(370L))
    )
    // 6.5 and 6.50 are the same time under this encoding, which a numeric read would not give
    assertEquals(
      secs("6.5", CellEncoding.MinuteDotSecond).map(_.map(_.value)),
      secs("6.50", CellEncoding.MinuteDotSecond).map(_.map(_.value))
    )
  }

  test("a min.sec value whose seconds exceed 59 is refused, not silently carried") {
    secs("6.75", CellEncoding.MinuteDotSecond) match
      case Left(CellRefusal.Malformed(_, _, CellEncoding.MinuteDotSecond, r)) =>
        assert(r.contains("75"))
      case other => fail(s"expected Malformed, got $other")
  }

  test("a blank cell is absence, not a refusal") {
    assertEquals(int("", CellEncoding.IntegerText), Right(None))
    assertEquals(int("   ", CellEncoding.IntegerText), Right(None))
    assertEquals(secs("", CellEncoding.ExcelSerialDays(1)), Right(None))
    assertEquals(Cell.text(at("Place"), "Place", "", Some(CellEncoding.PlainText)), Right(None))
  }

  test("an encoding is refused for a shape it does not describe") {
    assert(Cell.integer(at("Place"), "Place", "x", Some(CellEncoding.PlainText)).isLeft)
    assert(Cell.seconds(at("Place"), "Place", "x", Some(CellEncoding.PlainText)).isLeft)
    assert(Cell.text(at("Time"), "Time", "1.0", Some(CellEncoding.ExcelSerialDays(1))).isLeft)
  }

  test("a non-numeric literal under a numeric encoding is refused with its coordinate") {
    Cell.integer(at("WhichEvent"), "WhichEvent", "n/a", Some(CellEncoding.IntegerText)) match
      case Left(r: CellRefusal.NotAnInteger) =>
        assertEquals(r.coordinate.column, "WhichEvent")
        assert(r.message.contains("n/a"))
      case other => fail(s"expected NotAnInteger, got $other")
  }

  test("a decoded cell keeps the literal the source wrote") {
    val r = int("1.0", CellEncoding.IntegerOrWholeDecimalText).toOption.flatten.get
    assertEquals(r.value, 1L)
    assertEquals(r.literal, "1.0")
    assertEquals(r.at.column, "WhichStoryline")
  }
