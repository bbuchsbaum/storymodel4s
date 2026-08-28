package storymodel4s.amr

import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.Checked
import storymodel4s.amr.graph.RoleForm.CanonicalRoles

/** Canonical-form soundness on 1-WL-indistinguishable graphs (review #11) and role-inventory round
  * trips (review #10).
  */
class CanonicalFormSuite extends ScalaCheckSuite:

  /** A top with `n` `:mod` children of one concept, plus `:op1` edges among the children forming
    * the given cycles (as index lists). Every child is 2-regular in the child subgraph, so 1-WL
    * colours them identically whatever the cycle lengths.
    */
  private def cycles(
      names: Vector[String],
      cyc: Vector[Vector[Int]]
  ): AmrGraph[Checked, CanonicalRoles] =
    val top = NodeId.unsafe("t")
    val ids = names.map(NodeId.unsafe)
    val concepts = (top -> Concept.unsafe("thing")) +: ids.map(_ -> Concept.unsafe("x"))
    val modEdges =
      ids.map(i => Edge(top, SurfaceRole.direct(Role.standard("mod")), AmrValue.Node(i)))
    val cycEdges = cyc.flatMap { c =>
      c.zip(c.tail :+ c.head)
        .map((a, b) =>
          Edge(ids(a), SurfaceRole.direct(Role.Operand(PosIndex.unsafe(1))), AmrValue.Node(ids(b)))
        )
    }
    RoleCanonicalizer.canonicalize(
      AmrValidator.validateOrThrow(AmrGraph.unchecked(top, concepts, modEdges ++ cycEdges))
    )

  private val twelve = (0 until 12).map(i => s"c$i").toVector

  test("C6 ⊔ 2·C3 is canonicalized label-invariantly and separated from 3·C4") {
    val c6c3 = cycles(twelve, Vector(Vector(0, 1, 2, 3, 4, 5), Vector(6, 7, 8), Vector(9, 10, 11)))
    // same structure with the lexically-first variables placed on a 3-cycle instead of the 6-cycle
    val shuffled =
      cycles(twelve, Vector(Vector(6, 7, 8, 9, 10, 11), Vector(0, 1, 2), Vector(3, 4, 5)))
    val relabelled =
      cycles(twelve.reverse, Vector(Vector(0, 1, 2, 3, 4, 5), Vector(6, 7, 8), Vector(9, 10, 11)))
    assert(AmrIsomorphism.isomorphic(c6c3, shuffled))
    assertEquals(Canonical.form(c6c3), Canonical.form(shuffled))
    assertEquals(Canonical.form(c6c3), Canonical.form(relabelled))
    assertEquals(Canonical.digest(c6c3), Canonical.digest(shuffled))
    val threeC4 =
      cycles(twelve, Vector(Vector(0, 1, 2, 3), Vector(4, 5, 6, 7), Vector(8, 9, 10, 11)))
    assert(!AmrIsomorphism.isomorphic(c6c3, threeC4))
    assertNotEquals(Canonical.form(c6c3), Canonical.form(threeC4))
    val (_, complete) = Canonical.formBounded(c6c3)
    assert(complete, "search must finish within the branch budget")
  }

  test("many identical fillers (twins) canonicalize in linear time") {
    val top = NodeId.unsafe("t")
    val ids = (0 until 40).map(i => NodeId.unsafe(s"m$i")).toVector
    val concepts = (top -> Concept.unsafe("thing")) +: ids.map(_ -> Concept.unsafe("x"))
    val edges = ids.map(i => Edge(top, SurfaceRole.direct(Role.standard("mod")), AmrValue.Node(i)))
    val g = RoleCanonicalizer.canonicalize(
      AmrValidator.validateOrThrow(AmrGraph.unchecked(top, concepts, edges))
    )
    val start = System.nanoTime()
    val (form, complete) = Canonical.formBounded(g)
    val elapsedMs = (System.nanoTime() - start) / 1000000
    assert(complete)
    assert(elapsedMs < 5000, s"took $elapsedMs ms")
    assertEquals(Canonical.form(form), form)
  }

  property("law: canonical form is invariant under relabelling for graphs with tied siblings") {
    val gen: Gen[AmrGraph[Checked, CanonicalRoles]] = for
      k <- Gen.chooseNum(2, 5)
      shared <- Gen.prob(0.5)
    yield
      val top = NodeId.unsafe("t")
      val kids = (0 until k).map(i => NodeId.unsafe(s"k$i")).toVector
      val leaf = NodeId.unsafe("leaf")
      val concepts =
        (top -> Concept.unsafe("say-01")) +: kids.map(_ -> Concept.unsafe("go-02")) :+
          (leaf -> Concept.unsafe("boy"))
      val edges =
        kids.map(kid => Edge(top, SurfaceRole.direct(Role.arg(1)), AmrValue.Node(kid))) ++
          (if shared then
             kids.map(kid => Edge(kid, SurfaceRole.direct(Role.arg(0)), AmrValue.Node(leaf)))
           else Vector(Edge(kids.head, SurfaceRole.direct(Role.arg(0)), AmrValue.Node(leaf))))
      RoleCanonicalizer.canonicalize(
        AmrValidator.validateOrThrow(AmrGraph.unchecked(top, concepts, edges))
      )
    forAll(gen.flatMap(g => Gens.alphaVariant(g).map(g -> _))) { case (g, variant) =>
      val v = RoleCanonicalizer.canonicalize(AmrValidator.validateOrThrow(variant))
      assertEquals(Canonical.form(g), Canonical.form(v))
      true
    }
  }

  // ---- role inventory ----------------------------------------------------------------------

  private val inventory: Vector[String] =
    (0 to 9).map(i => s"ARG$i").toVector ++
      Vector(
        "op1",
        "op12",
        "snt1",
        "snt3",
        "location",
        "time",
        "manner",
        "purpose",
        "cause",
        "instrument",
        "beneficiary",
        "source",
        "destination",
        "mod",
        "domain",
        "poss",
        "part",
        "consist-of",
        "prep-out-of",
        "prep-on-behalf-of",
        "prep-in-front",
        "prep-in",
        "prep",
        "polarity",
        "quant",
        "unit",
        "name",
        "wiki",
        "condition",
        "concession",
        "ns.custom"
      )

  property("law: parse(render(r)) == r over the role inventory, direct and inverse") {
    forAll(Gen.oneOf(inventory), Gen.oneOf(true, false)) { (text, inverse) =>
      val r = SurfaceRole.unsafe(text)
      val role = if inverse then r.invert else r
      assertEquals(SurfaceRole.parse(role.render), Right(role), role.render)
      assertEquals(role.invert.invert, role)
      true
    }
  }

  test("primary -of roles are direct; bare prep-of is the inverse of prep") {
    assertEquals(SurfaceRole.parse("consist-of").map(_.isInverse), Right(false))
    assertEquals(SurfaceRole.parse("prep-out-of").map(_.isInverse), Right(false))
    assertEquals(SurfaceRole.parse("prep-on-behalf-of").map(_.isInverse), Right(false))
    // pinned to Penman: any other `X-of` is the inverse of `X`
    assertEquals(SurfaceRole.parse("prep-in-front-of").map(_.isInverse), Right(true))
    assertEquals(SurfaceRole.parse("prep-of").map(_.isInverse), Right(true))
    assertEquals(SurfaceRole.parse("ARG0-of").map(_.isInverse), Right(true))
    assertEquals(
      SurfaceRole.parse("consist-of-of").map(r => (r.isInverse, r.base.render)),
      Right((true, "consist-of"))
    )
  }

  test("Eq[AmrGraph] distinguishes graphs that differ only in an unconcepted node") {
    val a = Decoder.graphFromPenman("(b / boy)").fold(fail(_), identity)
    val b = Decoder.graphFromPenman("(b / boy :ARG0 (c))").fold(fail(_), identity)
    val aNoEdge = AmrGraph.uncheckedWithNodes(b.top, b.nodes, b.concepts.toVector, Vector.empty)
    assert(!(a === aNoEdge))
  }

  test("Order[Concept] and Order[AmrLiteral] agree with ==") {
    val lex = Concept.Lexical(Lemma.unsafe("want-01"))
    val frame = Concept.Frame(FrameId.unsafe("want-01"))
    assert(lex != frame && cats.Order[Concept].compare(lex, frame) != 0)
    val num = AmrLiteral.Number("5")
    val sym = AmrLiteral.Symbol("5")
    assert(num != sym && cats.Order[AmrLiteral].compare(num, sym) != 0)
  }
