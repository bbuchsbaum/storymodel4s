package storymodel4s.amr

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.amr.penman.*

class PenmanSuite extends ScalaCheckSuite:

  private def parseOk(s: String): PenmanTree =
    PenmanParser.parse(s).fold(e => fail(e.message), identity)

  test("parses nested reentrancy and inverse roles") {
    val t = parseOk("(b / boy :ARG0-of (s / sing-01 :ARG1 b))")
    assertEquals(t.root.variable, Variable("b"))
    assertEquals(t.root.branches.head.role.text, "ARG0-of")
    val inner = t.root.branches.head.target match
      case Target.NodeTarget(n) => n
      case other                => fail(s"expected node, got $other")
    assertEquals(inner.branches.head.target, Target.VarRef(Variable("b"), None))
  }

  test("parses literals: strings with escapes, negative numbers, polarity symbol") {
    val t = parseOk("""(n / name :op1 "O\"Brien" :quant -5 :polarity - :value 1.5e3)""")
    val lits = t.root.branches.map(_.target).collect { case Target.LiteralTarget(l, _) => l }
    assertEquals(
      lits,
      Vector(
        PenmanLiteral.Str("O\"Brien"),
        PenmanLiteral.Num("-5"),
        PenmanLiteral.Sym("-"),
        PenmanLiteral.Num("1.5e3")
      )
    )
  }

  test("parses metadata lines, multiple pairs per line, and comments") {
    val t = parseOk("# ::id x1 ::snt The boy.\n# plain comment\n(b / boy)")
    assertEquals(
      t.headers,
      Vector(
        Header.Meta("id", "x1"),
        Header.Meta("snt", "The boy."),
        Header.Comment("plain comment")
      )
    )
  }

  test("parses alignment markers on concept, role, and target") {
    val t = parseOk("(b / boy~e.1 :ARG0-of~e.2 (s / sing-01~e.2,3) :mode imperative~4)")
    assertEquals(t.root.concept.get.alignment, Some(AlignmentMarker(Some("e"), Vector(1))))
    assertEquals(t.root.branches.head.role.alignment, Some(AlignmentMarker(Some("e"), Vector(2))))
    t.root.branches(1).target match
      case Target.LiteralTarget(PenmanLiteral.Sym("imperative"), Some(m)) =>
        assertEquals(m, AlignmentMarker(None, Vector(4)))
      case other => fail(s"unexpected $other")
  }

  test("node without concept is accepted") {
    val t = parseOk("(b :ARG0 (c / cat))")
    assertEquals(t.root.concept, None)
  }

  test("unbalanced parentheses are reported with an offset") {
    PenmanParser.parse("(b / boy :ARG0 (c / cat)") match
      case Left(PenmanError.UnbalancedParentheses(_)) => ()
      case other                                      => fail(s"unexpected $other")
    PenmanParser.parse("(b / boy))") match
      case Left(PenmanError.UnbalancedParentheses(9)) => ()
      case other                                      => fail(s"unexpected $other")
  }

  test("duplicate variable definition is an error naming the variable") {
    PenmanParser.parse("(b / boy :ARG0 (b / boy))") match
      case Left(PenmanError.DuplicateVariable(Variable("b"), off)) => assert(off > 0)
      case other                                                   => fail(s"unexpected $other")
  }

  test("undefined variable-shaped symbol is an error in strict mode, a symbol when lenient") {
    PenmanParser.parse("(w / want-01 :ARG0 b2)") match
      case Left(PenmanError.UndefinedVariable(Variable("b2"), _)) => ()
      case other                                                  => fail(s"unexpected $other")
    val lenient = PenmanParser.parse("(w / want-01 :ARG0 b2)", PenmanParser.Options.lenient)
    assert(lenient.isRight)
  }

  test("empty input and garbage are typed errors") {
    assertEquals(PenmanParser.parse("   "), Left(PenmanError.Empty))
    PenmanParser.parse("boy") match
      case Left(PenmanError.Syntax(_, _)) => ()
      case other                          => fail(s"unexpected $other")
  }

  test("printer output re-parses to the same tree for the golden fixtures") {
    PenmanGolden.cases.foreach { c =>
      val t = parseOk(c.input)
      assertEquals(parseOk(PenmanPrinter.print(t)), t, c.name)
      assertEquals(parseOk(PenmanPrinter.print(t, PenmanPrinter.Options.oneLine)), t, c.name)
    }
  }

  property("law: parse(print(t)) == t") {
    forAll(Gens.penmanTree) { t =>
      val printed = PenmanPrinter.print(t)
      assertEquals(PenmanParser.parse(printed), Right(t), printed)
      true
    }
  }

  property("law: parse(print(t)) == t in one-line layout") {
    forAll(Gens.penmanTree) { t =>
      val printed = PenmanPrinter.print(t, PenmanPrinter.Options.oneLine)
      assertEquals(PenmanParser.parse(printed), Right(t), printed)
      true
    }
  }

  test("parseMany splits documents on blank lines") {
    val docs = PenmanParser.parseMany("(a / a1)\n\n# ::id two\n(b / b1)\n\n\n(c / c1)")
    assertEquals(docs.map(_.size), Right(3))
  }
