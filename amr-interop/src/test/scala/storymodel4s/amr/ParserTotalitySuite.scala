package storymodel4s.amr

import munit.FunSuite
import storymodel4s.amr.graph.*
import storymodel4s.amr.penman.*

/** Parser totality and edge cases (review #39): nothing escapes `parse` as an exception. */
class ParserTotalitySuite extends FunSuite:

  test("huge alignment indices are a typed syntax error, not an exception") {
    PenmanParser.parse("(b / boy~e.99999999999)") match
      case Left(PenmanError.Syntax(_, _)) => ()
      case other                          => fail(s"unexpected $other")
    assert(PenmanParser.parse("(b / boy~e.123456789)").isRight, "nine digits are fine")
  }

  test("nesting beyond MaxDepth is TooDeep, never a stack overflow") {
    val depth = PenmanParser.MaxDepth + 5
    val deep = (1 to depth).map(i => s"(v$i / x :ARG0 ").mkString + "(z / y)" + ")" * depth
    PenmanParser.parse(deep) match
      case Left(PenmanError.TooDeep(_, limit)) => assertEquals(limit, PenmanParser.MaxDepth)
      case other                               => fail(s"unexpected $other")
    val shallow = (1 to 50).map(i => s"(v$i / x :ARG0 ").mkString + "(z / y)" + ")" * 50
    assert(PenmanParser.parse(shallow).isRight)
  }

  test(":: inside a header value stays inside the value") {
    val t = PenmanParser.parse("# ::snt a :: b ::id x1\n(b / boy)").toOption.get
    assertEquals(t.headers, Vector(Header.Meta("snt", "a :: b"), Header.Meta("id", "x1")))
    val t2 = PenmanParser.parse("# ::snt The ratio is 2::1.\n(b / boy)").toOption.get
    assertEquals(t2.headers, Vector(Header.Meta("snt", "The ratio is 2::1.")))
  }

  test("corpus-style three-letter variables are variables; longer bare symbols are literals") {
    val t = PenmanParser.parse("(s / see-01 :ARG0 (iii / i) :ARG1 iii :mode imperative)")
    val root = t.fold(e => fail(e.message), identity).root
    assertEquals(root.branches(1).target, Target.VarRef(Variable("iii"), None))
    root.branches(2).target match
      case Target.LiteralTarget(PenmanLiteral.Sym("imperative"), None) => ()
      case other                                                       => fail(s"unexpected $other")
    PenmanParser.parse("(s / see-01 :ARG0 iii)") match
      case Left(PenmanError.UndefinedVariable(Variable("iii"), _)) => ()
      case other                                                   => fail(s"unexpected $other")
  }

  test("a self-loop is reported as a cycle once") {
    val g = Decoder.graphFromPenman("(a / and :op1 a)").fold(fail(_), identity)
    assertEquals(Cycles.find(g.edges).map(_.map(_.value)), Some(Vector("a", "a")))
    val two = Decoder.graphFromPenman("(a / and :op1 (b / boy :op1 a))").fold(fail(_), identity)
    assertEquals(Cycles.find(two.edges).map(_.size), Some(3))
  }

  test("frame-shaped tokens: two-letter heads with a two-digit sense; f-16 is not a frame") {
    assert(FrameId.looksLikeFrame("want-01"))
    assert(FrameId.looksLikeFrame("be-located-at-91"))
    assert(!FrameId.looksLikeFrame("f-16"))
    assert(!FrameId.looksLikeFrame("want-001"))
    assert(!FrameId.looksLikeFrame("x1-01"))
    assertEquals(Concept.parse("f-16").map(_.render), Right("f-16"))
    assert(Concept.parse("f-16").exists(_.isInstanceOf[Concept.Lexical]))
  }

  test("AmrCandidates never throws on adversarial inputs") {
    val inputs = Vector(
      "(b / boy~e.99999999999)",
      (1 to 600).map(i => s"(v$i / x :ARG0 ").mkString + "(z / y)" + ")" * 600,
      "((((",
      "(b / boy :ARG0 \"unterminated)",
      "# ::only header"
    )
    val out = storymodel4s.amr.interop.AmrCandidates.toChartCandidates(
      inputs,
      storymodel4s.amr.schema.StarterLexicon.lexicon,
      None
    )
    assertEquals(out.size, inputs.size)
    assert(out.forall(_.isLeft))
  }
