package storymodel4s.corpus

import munit.FunSuite

/** The Friends `RecallType`/`WhichEvent` relation, used as the worked example throughout because it
  * is measured: `WhichEvent` is populated if and only if `RecallType == 1` (20,735 of 20,735), and
  * exactly two rows violate it (s2, SecondsOfRecall 384-385, RecallType 4 carrying event 35).
  */
class CodedSuite extends FunSuite:
  private val art = ArtifactId.unsafe("FriendsRecallScoring.xlsx")
  private def at(row: Int, col: String): SourceCoordinate =
    SourceCoordinate.at(art, "s2", row, col).fold(e => fail(e.message), identity)

  private val onlyVeridical = Applicability.WhenColumnEquals("RecallType", "1")

  private val events: CodeBook[Int, String] = CodeBook
    .of(
      CodeBookId.unsafe("friends.whichEvent.em56"),
      Citation("Antony et al. 2024", "scoring legend"),
      Map(1 -> "Riffs restaurant", 35 -> "Monica's apartment", 37 -> "Interlude, door action"),
      onlyVeridical
    )
    .fold(e => fail(e.message), identity)

  private def rowWith(recallType: String) = RowContext.of(Map("RecallType" -> recallType))

  private def decode(recallType: String, cell: Option[Int], literal: String) =
    val c = at(10, "WhichEvent")
    Coded.decode(
      events,
      cell.map(v => Raw.of(v, c, literal)),
      Raw.of(literal, c, literal),
      rowWith(recallType)
    )

  test("a defined code on an applicable row is Known, and carries the book that defined it") {
    decode("1", Some(35), "35") match
      case Right(k: Known[?]) =>
        assertEquals(k.raw.value, "Monica's apartment")
        assertEquals(k.raw.literal, "35")
        assertEquals(k.book, CodeBookId.unsafe("friends.whichEvent.em56"))
      case other => fail(s"expected Known, got $other")
  }

  test("an undefined code on an applicable row is Unmapped, never silently dropped") {
    decode("1", Some(99), "99") match
      case Right(u: Unmapped) => assertEquals(u.raw.literal, "99")
      case other              => fail(s"expected Unmapped, got $other")
  }

  test("a blank cell on an applicable row is Absent, NOT Unmapped") {
    decode("1", None, "") match
      case Right(a: Absent) => assertEquals(a.raw.literal, "")
      case other            => fail(s"expected Absent, got $other")
  }

  test("a blank cell on an inapplicable row is NotApplicable, NOT missingness") {
    decode("4", None, "") match
      case Right(n: NotApplicable) => assertEquals(n.condition, onlyVeridical)
      case other                   => fail(s"expected NotApplicable, got $other")
  }

  test("a value on an inapplicable row is REFUSED -- this is what makes 'iff' an iff") {
    decode("4", Some(35), "35") match
      case Left(CodeRefusal.InapplicableValuePresent(_, literal, condition)) =>
        assertEquals(literal, "35")
        assertEquals(condition, onlyVeridical)
      case other => fail(s"expected a refusal, got $other")
  }

  test("Absent and NotApplicable are distinguishable, and neither is Unmapped") {
    val absent = decode("1", None, "").toOption.get
    val na = decode("4", None, "").toOption.get
    assertNotEquals(absent.getClass.getName, na.getClass.getName)
    assert(!absent.isInstanceOf[Unmapped])
    assert(!na.isInstanceOf[Unmapped])
    assertEquals(absent.at, na.at)
  }

  test("applicability compares normalized values, not literals") {
    // the same code arrives as '1' and 1.0 in one Friends column; both normalize to "1"
    assert(onlyVeridical.holdsIn(RowContext.of(Map("RecallType" -> "1"))))
    assert(!onlyVeridical.holdsIn(RowContext.of(Map("RecallType" -> "1.0"))))
    assert(!onlyVeridical.holdsIn(RowContext.of(Map.empty)))
    assert(Applicability.Always.holdsIn(RowContext.of(Map.empty)))
  }

  test("an empty code book is refused rather than turning every value into Unmapped") {
    assert(
      CodeBook
        .of(
          CodeBookId.unsafe("empty"),
          Citation("x", "y"),
          Map.empty[Int, String],
          Applicability.Always
        )
        .isLeft
    )
  }
