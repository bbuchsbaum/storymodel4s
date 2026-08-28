package storymodel4s.proposition

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

/** Structural foils from the design record: role swap, polarity flip, reported-vs-asserted. */
class CompatSuite extends ScalaCheckSuite:
  import ChartGens.given

  private val strike = Some(FrameRef("propbank", "strike-01", None))
  private val p = ConceptId.unsafe("p")
  private val w = ConceptId.unsafe("w")
  private val m = ConceptId.unsafe("m")
  private val s = ConceptId.unsafe("s")

  private def rel(from: ConceptId, arg: Int, to: ConceptId) =
    PropositionRelation(from, RoleAssignment.arg(arg), ConceptTarget.Node(to))

  /** "the warriors struck the young man" */
  private val warriorsStruckMan = ChartValidator
    .validate(
      PropositionChart.unchecked(
        Some(p),
        Map(
          p -> Concept.predicate("strike", strike),
          w -> Concept.entity("warrior"),
          m -> Concept.entity("young-man")
        ),
        Vector(rel(p, 0, w), rel(p, 1, m)),
        polarity = Map(p -> Polarity.Positive)
      )
    )
    .toOption
    .get

  /** "the young man struck the warriors" */
  private val manStruckWarriors =
    warriorsStruckMan.unchecked.copy[Unchecked](relations = Vector(rel(p, 0, m), rel(p, 1, w)))

  /** "the warriors did not strike the young man" */
  private val negated =
    warriorsStruckMan.unchecked.copy[Unchecked](polarity = Map(p -> Polarity.Negative))

  /** "the warriors said that the young man was struck" */
  private val reported = warriorsStruckMan.unchecked.copy[Unchecked](
    concepts = warriorsStruckMan.concepts + (s -> Concept.predicate("say")),
    relations = warriorsStruckMan.relations :+ rel(s, 0, w),
    embedded = Vector(EmbeddedProposition(s, EmbeddingKind.Speech, p)),
    focus = Some(s)
  )

  /** Paraphrase with a frameless lemma and an unknown agent: "someone struck the young man". */
  private val partial = PropositionChart.unchecked(
    Some(p),
    Map(p -> Concept.predicate("strike"), m -> Concept.entity("young-man")),
    Vector(
      PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Unknown),
      rel(p, 1, m)
    ),
    polarity = Map(p -> Polarity.Positive)
  )

  test("identical charts: score 1, no gates") {
    val r = ChartCompatibility.compare(warriorsStruckMan, warriorsStruckMan)
    assertEqualsDouble(r.structuralScore, 1.0, 1e-9)
    assert(!r.gated)
  }

  test("role reversal is detected and gated, while lexical content still matches") {
    val r = ChartCompatibility.compare(warriorsStruckMan, manStruckWarriors)
    assert(r.roleReversal, r.toString)
    assert(!r.polarityConflict)
    assertEqualsDouble(r.conceptMatch, 1.0, 1e-9)
  }

  test("polarity flip is detected and gated") {
    val r = ChartCompatibility.compare(warriorsStruckMan, negated)
    assert(r.polarityConflict)
    assert(!r.roleReversal)
    assertEqualsDouble(r.argumentMatch, 1.0, 1e-9)
  }

  test("asserted vs reported (embedded under speech) is an embedding conflict") {
    val r = ChartCompatibility.compare(warriorsStruckMan, reported)
    assert(r.embeddingConflict)
  }

  test("partial paraphrase is compatible but penalized, never gated") {
    val r = ChartCompatibility.compare(warriorsStruckMan, partial)
    assert(!r.gated)
    assert(r.partialityPenalty > 0.0)
    assert(r.structuralScore > 0.5 && r.structuralScore < 1.0, r.structuralScore)
  }

  test("sense mismatch with equal lemma is weak evidence, not a gate") {
    val other = warriorsStruckMan.unchecked.copy[Unchecked](
      concepts = warriorsStruckMan.concepts.updated(
        p,
        Concept.predicate("strike", Some(FrameRef("propbank", "strike-02", None)))
      )
    )
    val r = ChartCompatibility.compare(warriorsStruckMan, other)
    assert(!r.gated)
    assert(r.conceptMatch > 0.5 && r.conceptMatch < 1.0)
  }

  test("gates are reported by name and excluded from the score") {
    val r = ChartCompatibility.compare(warriorsStruckMan, negated)
    assertEquals(r.gates, Set("polarity-conflict"))
    assert(r.structuralScore > 0.9)
  }

  property("comparison is symmetric") {
    forAll { (a: PropositionChart[Checked], b: PropositionChart[Checked]) =>
      val ab = ChartCompatibility.compare(a, b)
      val ba = ChartCompatibility.compare(b, a)
      math.abs(ab.structuralScore - ba.structuralScore) < 1e-12 &&
      ab.gates == ba.gates
    }
  }

  property("self-comparison has score 1 and no gates") {
    forAll { (a: PropositionChart[Checked]) =>
      val r = ChartCompatibility.compare(a, a)
      ((math.abs(r.structuralScore - 1.0) < 1e-9) :| s"score ${r.structuralScore}") && !r.gated
    }
  }

  property("score is bounded in [0, 1]") {
    forAll { (a: PropositionChart[Checked], b: PropositionChart[Checked]) =>
      val sc = ChartCompatibility.compare(a, b).structuralScore
      sc >= 0.0 && sc <= 1.0
    }
  }
