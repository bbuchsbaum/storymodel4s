package storymodel4s.proposition

import munit.FunSuite

/** Adversarial identity cases from the review: large symmetric tie classes, WL-indistinguishable
  * cycles, escaping, and number rendering.
  */
class IdentityHardSuite extends FunSuite:
  private def id(s: String) = ConceptId.unsafe(s)

  /** One predicate with `n` identical unknown fillers under the same role. */
  private def fan(n: Int, labels: Int => String): PropositionChart[Unchecked] =
    val p = id("p")
    val fillers = (0 until n).map(i => id(labels(i))).toVector
    PropositionChart.unchecked(
      Some(p),
      (fillers.map(_ -> Concept.unknown) :+ (p -> Concept.predicate("see"))).toMap,
      fillers.map(f => PropositionRelation(p, RoleAssignment.named("mod"), ConceptTarget.Node(f)))
    )

  test("12 identical fillers: canonical form is exact, fast, and label-invariant") {
    val a = fan(12, i => s"x$i")
    val b = fan(12, i => s"q${(i * 7) % 12}")
    val t0 = System.nanoTime()
    assert(Canonical.isExact(a))
    assertEquals(Canonical.serialization(a), Canonical.serialization(b))
    assertEquals(Canonical.form(a), Canonical.form(b))
    assert(ChartIsomorphism.isomorphic(a, b))
    val ms = (System.nanoTime() - t0) / 1e6
    assert(ms < 5000, s"took $ms ms")
  }

  test("25 identical fillers: no Long overflow, no hang, still exact via twin cells") {
    val a = fan(25, i => s"x$i")
    val b = fan(25, i => s"z${24 - i}")
    val t0 = System.nanoTime()
    assert(Canonical.isExact(a))
    assertEquals(Canonical.checksum(a), Canonical.checksum(b))
    assert(ChartIsomorphism.mapping(a, b).nonEmpty)
    val ms = (System.nanoTime() - t0) / 1e6
    assert(ms < 5000, s"took $ms ms")
  }

  /** Directed cycles C6 ⊔ C3 ⊔ C3 of `next` relations on identical concepts, plus a predicate focus
    * pointing at all of them: 1-WL cannot separate the twelve nodes.
    */
  private def cycles(perm: Vector[Int]): PropositionChart[Unchecked] =
    val p = id("p")
    val nodes = (0 until 12).map(i => id(s"n${perm(i)}")).toVector
    val ring = (members: Vector[Int]) =>
      members
        .zip(members.tail :+ members.head)
        .map((a, b) =>
          PropositionRelation(nodes(a), RoleAssignment.named("next"), ConceptTarget.Node(nodes(b)))
        )
    val edges = ring(Vector(0, 1, 2, 3, 4, 5)) ++ ring(Vector(6, 7, 8)) ++ ring(Vector(9, 10, 11))
    val fromP =
      nodes.map(n => PropositionRelation(p, RoleAssignment.named("mod"), ConceptTarget.Node(n)))
    PropositionChart.unchecked(
      Some(p),
      (nodes.map(_ -> Concept.entity("thing")) :+ (p -> Concept.predicate("list"))).toMap,
      edges ++ fromP
    )

  test("WL-tied cycles: canonical form does not depend on which variable sits on which cycle") {
    val a = cycles((0 until 12).toVector)
    val b = cycles(Vector(7, 3, 11, 0, 9, 5, 1, 6, 10, 2, 8, 4))
    assert(ChartIsomorphism.isomorphic(a, b))
    assert(Canonical.isExact(a))
    assertEquals(Canonical.serialization(a), Canonical.serialization(b))
    assertEquals(Canonical.checksum(a), Canonical.checksum(b))
  }

  test("C6 ⊔ 2·C3 and 3·C4 are not isomorphic although WL cannot separate them") {
    val a = cycles((0 until 12).toVector)
    val p = id("p")
    val nodes = (0 until 12).map(i => id(s"m$i")).toVector
    val ring = (members: Vector[Int]) =>
      members
        .zip(members.tail :+ members.head)
        .map((x, y) =>
          PropositionRelation(nodes(x), RoleAssignment.named("next"), ConceptTarget.Node(nodes(y)))
        )
    val b = PropositionChart.unchecked(
      Some(p),
      (nodes.map(_ -> Concept.entity("thing")) :+ (p -> Concept.predicate("list"))).toMap,
      ring(Vector(0, 1, 2, 3)) ++ ring(Vector(4, 5, 6, 7)) ++ ring(Vector(8, 9, 10, 11)) ++
        nodes.map(n => PropositionRelation(p, RoleAssignment.named("mod"), ConceptTarget.Node(n)))
    )
    assert(!ChartIsomorphism.isomorphic(a, b))
    assertNotEquals(Canonical.serialization(a), Canonical.serialization(b))
  }

  test("serialization is injective under crafted separators in identifiers") {
    val p = id("p"); val q = id("q")
    val a = PropositionChart.unchecked(
      Some(p),
      Map(p -> Concept.predicate("x|y"), q -> Concept.entity("z")),
      Vector(PropositionRelation(p, RoleAssignment.named("r"), ConceptTarget.Node(q)))
    )
    val b = PropositionChart.unchecked(
      Some(p),
      Map(p -> Concept.predicate("x"), q -> Concept.entity("z")),
      Vector(PropositionRelation(p, RoleAssignment.named("r"), ConceptTarget.Node(q)))
    )
    assertNotEquals(Canonical.checksum(a), Canonical.checksum(b))
    val frameA =
      Concept.predicate("f", Some(FrameRef("propbank", "f-01\nc k00001 Entity|z|-", None)))
    val c = PropositionChart.unchecked(Some(p), Map(p -> frameA), Vector.empty)
    assert(!Canonical.serialization(c).contains("\nc k00001"))
    assertEquals(Canonical.serialization(c).linesIterator.size, 2)
  }

  test("numbers are canonical: 1E+3, 1000.0 and 1000 share a literal key and equality") {
    val ns = Vector(BigDecimal("1E+3"), BigDecimal("1000.0"), BigDecimal(1000))
    assertEquals(ns.map(LiteralValue.Number(_).render).distinct, Vector("1000"))
    assertEquals(ns.map(n => ChartIdentity.literalKey(LiteralValue.Number(n))).distinct.size, 1)
    assertEquals(LiteralValue.Number(BigDecimal("0.00")).render, "0")
    assertEquals(LiteralValue.Number(BigDecimal("2.50")).render, "2.5")
  }

  test("embeddingsOf reports every holder; embeddingOf is None when ambiguous") {
    val s = id("s"); val t = id("t"); val x = id("x")
    val chart = PropositionChart.unchecked(
      Some(s),
      Map(
        s -> Concept.predicate("say"),
        t -> Concept.predicate("think"),
        x -> Concept.predicate("go")
      ),
      Vector(
        PropositionRelation(s, RoleAssignment.arg(1), ConceptTarget.Node(x)),
        PropositionRelation(t, RoleAssignment.arg(1), ConceptTarget.Node(x))
      ),
      embedded = Vector(
        EmbeddedProposition(s, EmbeddingKind.Speech, x),
        EmbeddedProposition(t, EmbeddingKind.Belief, x)
      )
    )
    assert(ChartValidator.validate(chart).isValid)
    assertEquals(chart.embeddingKinds(x), Set(EmbeddingKind.Speech, EmbeddingKind.Belief))
    assertEquals(chart.embeddingOf(x), None)
    assertEquals(chart.embeddingsOf(x).size, 2)
  }
