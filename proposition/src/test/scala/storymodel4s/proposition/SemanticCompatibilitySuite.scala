package storymodel4s.proposition

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*

/** Laws for the S2a-1 semantic graph (mote bd-01M379KVFH4YD0WT6S3K1ZS1J1).
  *
  *   - Source-order parity: two `sourceOrder` graphs compare exactly as `ChartCompatibility`
  *     compares their charts. The historical scorers can therefore move onto graphs without
  *     changing a bit.
  *   - Canonical invariance: renaming concept ids and permuting relation storage leaves the
  *     canonical graph equal. The teeth control shows that the chart path does move on the same
  *     inputs.
  */
class SemanticCompatibilitySuite extends ScalaCheckSuite:
  private def id(s: String) = ConceptId.unsafe(s)

  private def checked(u: PropositionChart[Unchecked]): PropositionChart[Checked] =
    ChartValidator.check(u).fold(v => fail(s"invalid chart: $v"), identity)

  private def canon(c: PropositionChart[Checked]): SemanticGraph[GraphOrder.Canonical] =
    SemanticProjection.canonical(c).fold(r => fail(s"refused: $r"), identity)

  /** Every field of a chart report, with matched pairs reduced to whether any exist. */
  private def fields(r: CompatibilityReport) =
    (
      r.conceptMatch,
      r.argumentMatch,
      r.partialityPenalty,
      r.roleReversal,
      r.polarityConflict,
      r.embeddingConflict,
      r.matchedPredicates.nonEmpty,
      r.structuralScore
    )

  private def fields(r: ContentCompatibilityReport) =
    (
      r.conceptMatch,
      r.argumentMatch,
      r.partialityPenalty,
      r.roleReversal,
      r.polarityConflict,
      r.embeddingConflict,
      r.matched,
      r.structuralScore
    )

  private val glosses = Vector("pet", "wild", "small")

  /** Valid charts, some with glosses: the shared generator never sets one, and `compare` reads it.
    */
  private val chart: Gen[PropositionChart[Checked]] =
    for
      c <- ChartGens.validChart
      pick <- Gen.listOfN(c.conceptIds.size, Gen.option(Gen.oneOf(glosses)))
      glossed = c.unchecked.copy[Unchecked](concepts =
        c.conceptIds
          .zip(pick)
          .map { (i, g) =>
            i -> c.concepts(i).copy(gloss = g)
          }
          .toMap
      )
    yield checked(glossed)

  /** The same chart under renamed ids, with relations and embeddings stored in another order. */
  private def disguised(c: PropositionChart[Checked], salt: Int): PropositionChart[Checked] =
    val r = ChartGens.renamed(c, salt)
    val rot = if r.relations.isEmpty then 0 else salt % r.relations.size
    r.reordered(
      (r.relations.drop(rot) ++ r.relations.take(rot)).reverse,
      r.embedded.reverse
    )

  property("sourceOrder graphs compare exactly as their charts do") {
    forAll(chart, chart) { (a, b) =>
      val viaGraph = SemanticCompatibility.compare(
        SemanticProjection.sourceOrder(a),
        SemanticProjection.sourceOrder(b)
      )
      fields(viaGraph) == fields(ChartCompatibility.compare(a, b))
    }
  }

  property("canonical graphs ignore concept ids and relation storage order") {
    forAll(chart, Gen.chooseNum(1, 10000)) { (c, salt) =>
      SemanticProjection.canonical(c) match
        case Left(_)  => Prop.undecided
        case Right(g) => Prop(canon(disguised(c, salt)) == g)
    }
  }

  // --- greedy relation assignment: the chart path depends on storage order -------------------

  /** `b`'s predicate has two arg0 fillers that tie for `a`'s first relation (gloss 0.8 each), and
    * only one of them suits `a`'s second relation. Greedy assignment therefore picks the earlier
    * stored tie, and the argument score depends on that order.
    */
  private val greedyA = checked(
    PropositionChart.unchecked(
      Some(id("p")),
      Map(
        id("p") -> Concept.predicate("keep"),
        id("x") -> Concept(Lemma.unsafe("beast"), Some("pet"), None, ConceptKind.Entity),
        id("y") -> Concept.entity("dog")
      ),
      Vector(
        PropositionRelation(id("p"), RoleAssignment.arg(0), ConceptTarget.Node(id("x"))),
        PropositionRelation(id("p"), RoleAssignment.arg(0), ConceptTarget.Node(id("y")))
      )
    )
  )

  private def greedyB(dogFirst: Boolean) =
    val toCat = PropositionRelation(id("q"), RoleAssignment.arg(0), ConceptTarget.Node(id("c")))
    val toDog = PropositionRelation(id("q"), RoleAssignment.arg(0), ConceptTarget.Node(id("d")))
    checked(
      PropositionChart.unchecked(
        Some(id("q")),
        Map(
          id("q") -> Concept.predicate("keep"),
          id("c") -> Concept(Lemma.unsafe("cat"), Some("pet"), None, ConceptKind.Entity),
          id("d") -> Concept(Lemma.unsafe("dog"), Some("pet"), None, ConceptKind.Entity)
        ),
        if dogFirst then Vector(toDog, toCat) else Vector(toCat, toDog)
      )
    )

  test("teeth: the chart path's argument score depends on relation storage order") {
    val catFirst = ChartCompatibility.compare(greedyA, greedyB(dogFirst = false))
    val dogFirst = ChartCompatibility.compare(greedyA, greedyB(dogFirst = true))
    // a->b: x ties c/d at 0.8 and takes the first stored; y then gets the other (1.0 or 0.0).
    // b->a: c takes x (0.8), d takes y (1.0), whatever the order. Mean of the two directions.
    assertEquals(catFirst.argumentMatch, ((0.8 + 1.0) / 2 + (0.8 + 1.0) / 2) / 2)
    assertEquals(dogFirst.argumentMatch, ((0.8 + 0.0) / 2 + (0.8 + 1.0) / 2) / 2)
  }

  test("canonical graphs of the two storage orders are equal, and so are their reports") {
    val g1 = canon(greedyB(dogFirst = false))
    val g2 = canon(greedyB(dogFirst = true))
    assertEquals(g1, g2)
    assertEquals(
      SemanticCompatibility.compare(canon(greedyA), g1),
      SemanticCompatibility.compare(canon(greedyA), g2)
    )
  }

  test("sourceOrder parity holds where the chart path depends on storage order") {
    // The generated corpus rarely ties at the greedy step, so parity alone did not kill a
    // source-order projection that reversed relations (mutant S5). These fixtures do.
    for dogFirst <- Vector(false, true) do
      val b = greedyB(dogFirst)
      assertEquals(
        fields(
          SemanticCompatibility.compare(
            SemanticProjection.sourceOrder(greedyA),
            SemanticProjection.sourceOrder(b)
          )
        ),
        fields(ChartCompatibility.compare(greedyA, b))
      )
  }

  // --- known defects owned by S2a-1b (mote bd-01M37EKWYZ1973TGK7JSTZC28C) ---------------------
  // The canonical order is invariant under ids and storage, but CompareCore still breaks ties by
  // view order, and canonical order is a content-hash order that also reads focus. These are
  // named expected failures: when S2a-1b makes tie resolution order-free they pass, and munit
  // reports them, so the `.fail` marker has to be removed then.

  private def refocused(c: PropositionChart[Checked], f: Option[ConceptId]) =
    checked(c.unchecked.copy[Unchecked](focus = f))

  test("KNOWN DEFECT: canonical compare depends on focus, which the graph does not hold".fail) {
    val b = canon(greedyB(dogFirst = false))
    assertEquals(
      SemanticCompatibility.compare(canon(refocused(greedyA, Some(id("p")))), b),
      SemanticCompatibility.compare(canon(refocused(greedyA, None)), b)
    )
  }

  test("KNOWN DEFECT: canonical compare depends on an unmatched lemma's hash".fail) {
    // `c` scores 0.8 against x (gloss) and 0.0 against y under either lemma, so no concept score
    // changes. Only the canonical colour order does.
    def withLemma(l: String) =
      val b = greedyB(dogFirst = false)
      checked(
        b.unchecked.copy[Unchecked](concepts =
          b.concepts.updated(
            id("c"),
            Concept(Lemma.unsafe(l), Some("pet"), None, ConceptKind.Entity)
          )
        )
      )
    val a = canon(greedyA)
    assertEquals(
      SemanticCompatibility.compare(a, canon(withLemma("cat"))),
      SemanticCompatibility.compare(a, canon(withLemma("feline")))
    )
  }

  // --- the chart path's gate flags can depend on ids (corrects the S2a-0 note) ----------------

  private val run1 = Some(FrameRef("propbank", "run-01", None))
  private val run2 = Some(FrameRef("propbank", "run-02", None))

  /** a: `pa` (run-02, positive, ARG0 man, ARG1 dog) and `pa2` (run-01, no arguments). */
  private val gateA = checked(
    PropositionChart.unchecked(
      Some(id("pa")),
      Map(
        id("pa") -> Concept.predicate("run", run2),
        id("pa2") -> Concept.predicate("run", run1),
        id("man") -> Concept.entity("man"),
        id("dog") -> Concept.entity("dog")
      ),
      Vector(
        PropositionRelation(id("pa"), RoleAssignment.arg(0), ConceptTarget.Node(id("man"))),
        PropositionRelation(id("pa"), RoleAssignment.arg(1), ConceptTarget.Node(id("dog")))
      ),
      polarity = Map(id("pa") -> Polarity.Positive)
    )
  )

  /** b: a negated run-01 about other fillers (`neg`), and a run-01 with a's fillers reversed
    * (`rev`). Against `pa` both score 0.6 with no argument agreement and exactly one gate each.
    */
  private def gateB(neg: String, rev: String) = checked(
    PropositionChart.unchecked(
      Some(id(neg)),
      Map(
        id(neg) -> Concept.predicate("run", run1),
        id(rev) -> Concept.predicate("run", run1),
        id("cat") -> Concept.entity("cat"),
        id("bird") -> Concept.entity("bird"),
        id("dog") -> Concept.entity("dog"),
        id("man") -> Concept.entity("man")
      ),
      Vector(
        PropositionRelation(id(neg), RoleAssignment.arg(0), ConceptTarget.Node(id("cat"))),
        PropositionRelation(id(neg), RoleAssignment.arg(1), ConceptTarget.Node(id("bird"))),
        PropositionRelation(id(rev), RoleAssignment.arg(0), ConceptTarget.Node(id("dog"))),
        PropositionRelation(id(rev), RoleAssignment.arg(1), ConceptTarget.Node(id("man")))
      ),
      polarity = Map(id(neg) -> Polarity.Negative)
    )
  )

  test("teeth: on a head tie, the chart path's ids decide which gate flag is raised") {
    val gates = (r: CompatibilityReport) => (r.polarityConflict, r.roleReversal)
    // The tie goes to the larger ConceptId; the reverse direction raises no gate.
    assertEquals(gates(ChartCompatibility.compare(gateA, gateB("b1", "b2"))), (false, true))
    assertEquals(gates(ChartCompatibility.compare(gateA, gateB("b2", "b1"))), (true, false))
  }

  // --- gloss twins: the default identity cannot order them, the canonical graph must ----------

  /** Two same-lemma fillers told apart only by gloss, under ids chosen by the caller. */
  private def glossTwins(pet: String, wild: String): PropositionChart[Checked] =
    checked(
      PropositionChart.unchecked(
        Some(id("p")),
        Map(
          id("p") -> Concept.predicate("see"),
          id(pet) -> Concept(Lemma.unsafe("animal"), Some("pet"), None, ConceptKind.Entity),
          id(wild) -> Concept(Lemma.unsafe("animal"), Some("wild"), None, ConceptKind.Entity)
        ),
        Vector(
          PropositionRelation(id("p"), RoleAssignment.arg(0), ConceptTarget.Node(id(pet))),
          PropositionRelation(id("p"), RoleAssignment.arg(0), ConceptTarget.Node(id(wild)))
        )
      )
    )

  test("gloss twins: the default identity treats them as one class, the canonical graph does not") {
    val (a, b) = (glossTwins("c", "d"), glossTwins("d", "c"))
    assertEquals(Canonical.checksum(a), Canonical.checksum(b), "precondition: glosses are ignored")
    assertEquals(canon(a), canon(b))
  }

  // --- refusal, and missing versus empty -------------------------------------------------------

  /** `k` disjoint directed 3-cycles of identical concepts under one predicate: 1-WL cannot split
    * them, so individualization branches on every node.
    */
  private def triangles(k: Int): PropositionChart[Checked] =
    val p = id("p")
    val nodes = (0 until 3 * k).map(i => id(s"n$i")).toVector
    val edges = (0 until k).toVector.flatMap { t =>
      val m = Vector(3 * t, 3 * t + 1, 3 * t + 2)
      m.zip(m.tail :+ m.head)
        .map((x, y) =>
          PropositionRelation(nodes(x), RoleAssignment.named("next"), ConceptTarget.Node(nodes(y)))
        )
    }
    val fromP =
      nodes.map(n => PropositionRelation(p, RoleAssignment.named("mod"), ConceptTarget.Node(n)))
    checked(
      PropositionChart.unchecked(
        Some(p),
        (nodes.map(_ -> Concept.entity("thing")) :+ (p -> Concept.predicate("list"))).toMap,
        edges ++ fromP
      )
    )

  test("an exhausted canonical budget is refused, never ordered by id") {
    val c = triangles(5)
    assert(!Canonical.isExact(c), "precondition: this chart must exhaust the leaf budget")
    assertEquals(
      SemanticProjection.canonical(c),
      Left(ProjectionRefusal.CanonicalBudgetExhausted(Canonical.MaxLeaves))
    )
  }

  test("an empty chart projects to an empty graph, which is a real graph that matches nothing") {
    val empty = checked(PropositionChart.unchecked(None, Map.empty, Vector.empty))
    val g = canon(empty)
    assert(g.isEmpty)
    val r = SemanticCompatibility.compare(g, canon(greedyA))
    assertEquals(fields(r), fields(ChartCompatibility.compare(empty, greedyA)))
    assert(!r.matched)
  }
