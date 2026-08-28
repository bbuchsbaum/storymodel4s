package storymodel4s.proposition

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

class GlossSuite extends ScalaCheckSuite:
  import ChartGens.given

  private val p = ConceptId.unsafe("p")
  private val s = ConceptId.unsafe("s")
  private val w = ConceptId.unsafe("w")
  private val m = ConceptId.unsafe("m")
  private val r = ConceptId.unsafe("r")

  private def rel(from: ConceptId, role: RoleAssignment, to: ConceptId) =
    PropositionRelation(from, role, ConceptTarget.Node(to))

  /** say(warriors, strike(warriors, young-man) :location river), strike negated. */
  private val chart = ChartValidator
    .validate(
      PropositionChart.unchecked(
        Some(s),
        Map(
          s -> Concept.predicate("say"),
          p -> Concept.predicate("strike"),
          w -> Concept.entity("warrior"),
          m -> Concept.entity("young-man"),
          r -> Concept.entity("river")
        ),
        Vector(
          rel(s, RoleAssignment.arg(0), w),
          rel(s, RoleAssignment.arg(1), p),
          rel(p, RoleAssignment.arg(0), w),
          rel(p, RoleAssignment.arg(1), m),
          rel(p, RoleAssignment.named(":location"), r)
        ),
        polarity = Map(p -> Polarity.Negative),
        embedded = Vector(EmbeddedProposition(s, EmbeddingKind.Speech, p))
      )
    )
    .toOption
    .get

  test("fragment rooted at an inner predicate keeps only reachable structure") {
    val frag = ChartFragment.rooted(chart, p, 1).get
    assertEquals(frag.conceptIds.toSet, Set(p, w, m, r))
    assertEquals(frag.focus, Some(p))
    assert(frag.embedded.isEmpty)
    assertEquals(frag.polarity, Map(p -> Polarity.Negative))
    assert(ChartValidator.validate(frag).isValid)
  }

  test("fragment follows embedded content") {
    val frag = ChartFragment.rooted(chart, s, 2).get
    assertEquals(frag.conceptIds.toSet, chart.conceptIds.toSet)
    assertEquals(frag.embedded, chart.embedded)
  }

  test("fragment of an absent root is None; depth 0 keeps only the root") {
    assertEquals(ChartFragment.rooted(chart, ConceptId.unsafe("zz"), 3), None)
    assertEquals(ChartFragment.rooted(chart, p, 0).get.conceptIds, Vector(p))
  }

  test("gloss renders predicate, numbered arguments, named modifiers, and negation") {
    assertEquals(Gloss.predicate(chart, p), Some("not strike warrior young-man (location: river)"))
    assertEquals(Gloss.conservative(chart), "say warrior strike")
  }

  property("every content word of a gloss traces to a lemma or literal in the chart") {
    forAll { (chart: PropositionChart[Checked]) =>
      val lex = Lexicalizer.identity
      val allowed: Set[String] =
        chart.concepts.values.map(_.lemma.value).toSet ++ lex.functionWords ++
          chart.relations.flatMap(_.to match
            case ConceptTarget.Literal(LiteralValue.Text(v))   => v.split("\\s+").toVector
            case ConceptTarget.Literal(LiteralValue.Number(v)) => Vector(v.toString)
            case ConceptTarget.Literal(LiteralValue.Symbol(v)) => Vector(v)
            case _                                             => Vector.empty) ++
          chart.relations.flatMap(r => lex.role(r.role))
      val words = Gloss
        .conservative(chart, lex)
        .split("[\\s;]+")
        .map(_.stripPrefix("(").stripSuffix(")").stripSuffix(":"))
        .filter(_.nonEmpty)
      val stray = words.filterNot(allowed)
      (stray.isEmpty :| s"stray words: ${stray.toVector}")
    }
  }
