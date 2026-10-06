package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.CanonicalDouble
import storymodel4s.proposition.*
import storymodel4s.proposition.CheckState.Checked

/** Regression guard, not a positive control: pins today's CHARTED scoring path before the S2a port
  * (mote bd-01M379K5TYZVCC5YMWX2F242CA). The existing parity pins use a chart-free fixture, so
  * chart compatibility, chart-derived contradictions and structural reductions had no byte witness.
  * These unchanged historical Mac JVM pins now qualify the owned producer on every target.
  *
  * The fixture includes a member chart with two same-lemma predicates (one negated, one embedded
  * under speech), and a segment whose leaves both carry charts.
  *
  * Measured 2026-09-23: `compare` breaks equal head keys by `ConceptId`, and renaming the tied
  * predicates in THIS fixture did not change the report, because the reverse direction raised the
  * same flags. That is not true in general: when the tied heads carry different gates and the
  * reverse direction raises none, the ids choose the flag (`SemanticCompatibilitySuite`, "teeth: on
  * a head tie"). `twin` witnesses the gate-count key in `matchedPredicates`.
  */
class ChartedScoringParityProbe extends FunSuite:
  import AnnaFixture.*

  private def checked(u: PropositionChart[CheckState.Unchecked]): PropositionChart[Checked] =
    ChartValidator.check(u).fold(v => fail(s"invalid chart: $v"), identity)

  private def rel(from: ConceptId, arg: Int, to: ConceptId) =
    PropositionRelation(from, RoleAssignment.arg(arg), ConceptTarget.Node(to))

  private val p = ConceptId.unsafe("p")
  private val a = ConceptId.unsafe("a")
  private val b = ConceptId.unsafe("b")

  private def find(agent: String, patient: String, pol: Polarity = Polarity.Positive) =
    checked(
      PropositionChart.unchecked(
        Some(p),
        Map(
          p -> Concept.predicate("find"),
          a -> Concept.entity(agent),
          b -> Concept.entity(patient)
        ),
        Vector(rel(p, 0, a), rel(p, 1, b)),
        polarity = Map(p -> pol)
      )
    )

  /** Two same-lemma predicates: x is negated, y is embedded under speech. */
  private def tie(xName: String, yName: String) =
    val x = ConceptId.unsafe(xName)
    val y = ConceptId.unsafe(yName)
    val s = ConceptId.unsafe("s")
    checked(
      PropositionChart.unchecked(
        Some(x),
        Map(
          x -> Concept.predicate("find"),
          y -> Concept.predicate("find"),
          s -> Concept.predicate("say"),
          a -> Concept.entity("anna"),
          b -> Concept.entity("brother")
        ),
        Vector(rel(x, 0, a), rel(x, 1, b), rel(y, 0, a), rel(y, 1, b), rel(s, 0, a)),
        polarity = Map(x -> Polarity.Negative, y -> Polarity.Positive, s -> Polarity.Positive),
        embedded = Vector(EmbeddedProposition(s, EmbeddingKind.Speech, y))
      )
    )

  /** Two same-lemma, same-argument predicates, one clean and one negated. They tie on concept and
    * argument ratio, so only `compare`'s gate-count key decides the head: it witnesses head
    * selection.
    */
  private def twin(xName: String, yName: String) =
    val x = ConceptId.unsafe(xName)
    val y = ConceptId.unsafe(yName)
    checked(
      PropositionChart.unchecked(
        Some(x),
        Map(
          x -> Concept.predicate("find"),
          y -> Concept.predicate("find"),
          a -> Concept.entity("anna"),
          b -> Concept.entity("brother")
        ),
        Vector(rel(x, 0, a), rel(x, 1, b), rel(y, 0, a), rel(y, 1, b)),
        polarity = Map(x -> Polarity.Positive, y -> Polarity.Negative)
      )
    )

  private val straight = find("anna", "brother")
  private val reversed = find("brother", "anna")
  private val negated = find("anna", "brother", Polarity.Negative)

  private def num(d: Double) = CanonicalDouble.render(d)

  private def report(r: CompatibilityReport): String =
    Vector(
      num(r.structuralScore),
      r.toString.replaceAll("\\d+\\.\\d+(E-?\\d+)?", "#")
    ).mkString("|")

  private def reportDigest(pairs: Vector[(PropositionChart[Checked], PropositionChart[Checked])]) =
    MappingRender.digest(pairs.map((x, y) => report(ChartCompatibility.compare(x, y)))).hex

  private val chartPairs = Vector(
    straight -> straight,
    straight -> reversed,
    straight -> negated,
    straight -> tie("a1", "a2"),
    straight -> tie("a2", "a1"),
    tie("a1", "a2") -> straight,
    straight -> twin("a1", "a2"),
    straight -> twin("a2", "a1")
  )

  private def charted: InMemorySourceView =
    val ev = Map(
      e4 -> PropositionEvidence.hand(straight),
      e5 -> PropositionEvidence.hand(tie("a1", "a2")),
      e2 -> PropositionEvidence.hand(reversed)
    )
    InMemorySourceView(
      view.nodes.map(n => n.copy(evidence = ev.get(n.ref))),
      view.edges,
      view.worldOrder,
      view.scoringLength
    )

  private def chartedRecall =
    val ev = Map(
      u0.id -> PropositionEvidence.hand(negated),
      u2.id -> PropositionEvidence.hand(straight)
    )
    storymodel4s.recall.RecallGraph
      .validated(
        recall.transcript,
        recall.atlas,
        recall.units.map(u => ev.get(u.id).fold(u)(e => u.copy(evidence = Some(e)))),
        recall.relations
      )
      .fold(e => fail(s"invalid recall: $e"), identity)

  private def pins: (String, String, String) =
    val v = charted
    val r = chartedRecall
    val ev = LocalEvidence
      .compute(r, v, candidates, costModel, gate = true)
      .fold(e => fail(e.message), identity)
    val res = GraphHsmm.infer(r, v, candidates, costModel).fold(e => fail(e.message), identity)
    (reportDigest(chartPairs), ev.identity.checksum.hex, MappingBindingRender.result(res).hex)

  test("chart compatibility over the pinned pair corpus is byte-identical to the pre-port base") {
    assertEquals(pins._1, "a6ca0861c4e3a5a7c9d2008ba8357e67477dc40f7f38a3fa0e2c36ac4e04a3ee")
  }

  test("charted local evidence identity is byte-identical to the pre-port base") {
    assertEquals(pins._2, "b31d1255af80f12100c1a65564ad853975799bfc316301383a8b8198180e2a99")
  }

  test("charted HSMM result digest is byte-identical to the pre-port base") {
    assertEquals(pins._3, "4e4360866f91515849464c730fa2eb5ad2aef3565a0443552cd88ad51d3dc31d")
  }

  test("renaming the tied predicates does not change the report (measured, pinned)") {
    assertEquals(
      report(ChartCompatibility.compare(straight, tie("a1", "a2"))),
      report(ChartCompatibility.compare(straight, tie("a2", "a1")))
    )
  }

  test("a clean same-lemma head is preferred over a gated one, whatever the ids") {
    // `compare` is symmetric: the b->a direction evaluates every head of `twin`, so the negated
    // one legitimately raises polarityConflict. Head choice is visible only in the a->b pairing.
    for (x, y) <- Vector(("a1", "a2"), ("a2", "a1")) do
      val r = ChartCompatibility.compare(straight, twin(x, y))
      assertEquals(r.matchedPredicates, Vector(p -> ConceptId.unsafe(x)))
      assert(r.polarityConflict, s"reverse direction lost the negated head: $r")
  }
