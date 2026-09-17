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
  private val noRecallType = RowContext.of(Map.empty)

  private def decode(recallType: String, cell: Option[Int], literal: String) =
    val c = at(10, "WhichEvent")
    Coded.decode(events, Raw.of(literal, c, literal), cell, rowWith(recallType))

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

  test("a condition is tri-state: holds, does not hold, undetermined, or undeclared") {
    assertEquals(onlyVeridical.evaluate(rowWith("1")), ConditionOutcome.Holds)
    assertEquals(onlyVeridical.evaluate(rowWith("4")), ConditionOutcome.DoesNotHold)
    // present but blank: the condition cannot be evaluated, which is NOT "it does not apply"
    assertEquals(onlyVeridical.evaluate(rowWith("")), ConditionOutcome.Undetermined)
    // the profile never supplied the column at all: a configuration error
    assertEquals(onlyVeridical.evaluate(noRecallType), ConditionOutcome.Undeclared)
    assertEquals(Applicability.Always.evaluate(noRecallType), ConditionOutcome.Holds)
  }

  test("set membership is expressible, because real columns need it") {
    // measured: WhichStoryline is populated under RecallType 1 AND 2; Detail under 1 and 2;
    // 'False memory?' under 1, 2 and 3. Equality alone forces a wrong declaration either way.
    val recallOrGist = Applicability.WhenColumnIn("RecallType", Set("1", "2"))
    assertEquals(recallOrGist.evaluate(rowWith("1")), ConditionOutcome.Holds)
    assertEquals(recallOrGist.evaluate(rowWith("2")), ConditionOutcome.Holds)
    assertEquals(recallOrGist.evaluate(rowWith("4")), ConditionOutcome.DoesNotHold)
    assertEquals(recallOrGist.render, "RecallType in {1, 2}")
  }

  test("a blank condition column yields Undetermined, never a false NotApplicable") {
    // measured: 4,751 Friends rows carry no RecallType; 165 of them carry a transcript,
    // storyline or notes -- unscored recall seconds. Calling those NotApplicable asserts that
    // RecallType is something other than 1, which is false: it is absent.
    decode("", None, "") match
      case Right(u: Undetermined) => assertEquals(u.condition, onlyVeridical)
      case other                  => fail(s"expected Undetermined, got $other")
  }

  test("an undeclared condition column fails fast rather than becoming a row outcome") {
    val c = at(10, "WhichEvent")
    Coded.decode(events, Raw.of("", c, ""), None, noRecallType) match
      case Left(CodeRefusal.ConditionColumnUndeclared(_, col)) => assertEquals(col, "RecallType")
      case other => fail(s"expected ConditionColumnUndeclared, got $other")
  }

  test("a value on an undetermined row is refused, citing a condition that was not evaluated") {
    decode("", Some(35), "35") match
      case Left(CodeRefusal.UndeterminedValuePresent(_, l, _)) => assertEquals(l, "35")
      case other => fail(s"expected UndeterminedValuePresent, got $other")
  }

  test("every status carries the code book it was decided against") {
    val id = CodeBookId.unsafe("friends.whichEvent.em56")
    assertEquals(decode("1", Some(35), "35").toOption.get.book, id)
    assertEquals(decode("1", Some(99), "99").toOption.get.book, id)
    assertEquals(decode("1", None, "").toOption.get.book, id)
    assertEquals(decode("4", None, "").toOption.get.book, id)
    assertEquals(decode("", None, "").toOption.get.book, id)
  }

  test("every outcome takes its provenance from the ONE cell it was given") {
    val c = at(10, "WhichEvent")
    val cell = Raw.of("35", c, "35")
    val k = Coded.decode(events, cell, Some(35), rowWith("1")).toOption.get
    assertEquals(k.at, c)
    k match
      case kn: Known[?] => assertEquals(kn.raw.literal, "35")
      case other        => fail(s"expected Known, got $other")
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
