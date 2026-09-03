package storymodel4s.amr

import cats.data.{NonEmptySet, NonEmptyVector, Validated}
import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.amr.align.*
import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.{Checked, Unchecked}
import storymodel4s.amr.graph.RoleForm.{CanonicalRoles, SurfaceRoles}
import storymodel4s.amr.schema.*
import storymodel4s.core.*

class GraphSuite extends ScalaCheckSuite:

  private def unchecked(s: String): AmrGraph[Unchecked, SurfaceRoles] =
    Decoder.graphFromPenman(s).fold(fail(_), identity)

  private def canonical(s: String): AmrGraph[Checked, CanonicalRoles] =
    RoleCanonicalizer.canonicalize(AmrValidator.validateOrThrow(unchecked(s)))

  private def violations(g: AmrGraph[Unchecked, SurfaceRoles]): Vector[AmrViolation] =
    AmrValidator.validate(g) match
      case Validated.Invalid(e) => e.toChain.toVector
      case Validated.Valid(_)   => Vector.empty

  // ---- golden differential tests -------------------------------------------------------

  test("decoded canonical triples equal the Penman oracle for every golden case") {
    PenmanGolden.cases.foreach { c =>
      val g = canonical(c.input)
      assertEquals(g.top.value, c.top, c.name)
      val ours: Set[(String, String, String, Boolean)] =
        g.nodes.map(n => ("instance", n.value, g.concepts(n).render, false)).toSet ++
          g.canonicalTriples.map {
            case (s, r, AmrValue.Node(t))    => (r.render, s.value, t.value, true)
            case (s, r, AmrValue.Literal(l)) => (r.render, s.value, l.render, false)
          }.toSet
      assertEquals(ours, c.triples.toSet, c.name)
    }
  }

  test("golden cases survive encode → parse → decode isomorphically") {
    PenmanGolden.cases.foreach { c =>
      val g = canonical(c.input)
      val text = Encoder.toPenman(g).fold(e => fail(e.toString), identity)
      val back = canonical(text)
      assert(AmrIsomorphism.isomorphic(g, back), s"${c.name}:\n$text")
    }
  }

  test("decoder keeps alignment markers in a sidecar, not in the graph") {
    val d = Decoder
      .fromPenman("(b / boy~e.1 :ARG0-of~e.2 (s / sing-01~e.2) :mode imperative~4)")
      .fold(fail(_), identity)
    assertEquals(d.markers.size, 4)
    assert(d.markers.exists(_.site == MarkerSite.OnConcept(NodeId.unsafe("b"))))
    assert(d.markers.exists(_.site == MarkerSite.OnRole(0)))
    assert(d.markers.exists(_.site == MarkerSite.OnTarget(1)))
    assertEquals(d.graph.metadata, Vector.empty)
  }

  // ---- validator laws ------------------------------------------------------------------

  test("law 1: top must be defined") {
    val g = AmrGraph.unchecked(
      NodeId.unsafe("z"),
      Vector(NodeId.unsafe("b") -> Concept.unsafe("boy")),
      Vector.empty
    )
    assert(violations(g).exists(_.isInstanceOf[AmrViolation.TopNotDefined]))
  }

  test("law 2/3/7: dangling sources and unresolved variables are reported") {
    val b = NodeId.unsafe("b")
    val g = AmrGraph.unchecked(
      b,
      Vector(b -> Concept.unsafe("boy")),
      Vector(Edge("q", "ARG0", "b"), Edge("b", "ARG1", "zz"))
    )
    val vs = violations(g)
    assert(vs.exists(_.isInstanceOf[AmrViolation.DanglingSource]))
    assert(vs.exists(_.isInstanceOf[AmrViolation.UnresolvedVariable]))
  }

  test("law 4: a node without concept is reported (decoded from `(b)`)") {
    val g = unchecked("(b :ARG0 (c / cat))")
    assert(violations(g).contains(AmrViolation.MissingConcept(NodeId.unsafe("b"))))
  }

  test("law 5: disconnected nodes are reported") {
    val g = AmrGraph.unchecked(
      NodeId.unsafe("a"),
      Vector(
        NodeId.unsafe("a") -> Concept.unsafe("boy"),
        NodeId.unsafe("b") -> Concept.unsafe("girl")
      ),
      Vector.empty
    )
    assertEquals(violations(g), Vector(AmrViolation.Disconnected(Set(NodeId.unsafe("b")))))
  }

  test("law 6: inverse role to a literal and invalid numbers are reported") {
    val vs = violations(unchecked("""(b / boy :ARG0-of "x")"""))
    assert(vs.exists(_.isInstanceOf[AmrViolation.InverseRoleToLiteral]))
    val b = NodeId.unsafe("b")
    val bad = AmrGraph.unchecked(
      b,
      Vector(b -> Concept.unsafe("boy")),
      Vector(
        Edge(
          b,
          SurfaceRole.direct(Role.standard("quant")),
          AmrValue.Literal(AmrLiteral.Number("1x"))
        )
      )
    )
    assert(violations(bad).exists(_.isInstanceOf[AmrViolation.InvalidLiteral]))
  }

  test("law 8: duplicate triples are removed by default and rejected under the strict profile") {
    val g = unchecked("(w / want-01 :ARG0 (b / boy) :ARG0 b)")
    val checked = AmrValidator.validateOrThrow(g)
    assertEquals(checked.edgeCount, 1)
    val g2 = unchecked("(b / boy :ARG0-of (w / want-01 :ARG0 b))")
    assertEquals(
      AmrValidator.validateOrThrow(g2).edgeCount,
      1,
      "inverse spelling of the same triple is a duplicate"
    )
    AmrValidator.validate(g, ValidationProfile.strict) match
      case Validated.Invalid(e) => assert(e.exists(_.isInstanceOf[AmrViolation.DuplicateTriple]))
      case Validated.Valid(_)   => fail("expected duplicate violation")
  }

  test("cycles are permitted by default and reported under the strict profile") {
    val g =
      unchecked("(w / want-01 :ARG0 (b / boy) :ARG1 (b2 / believe-01 :ARG0 (g / girl) :ARG1 w))")
    val c =
      canonical("(w / want-01 :ARG0 (b / boy) :ARG1 (b2 / believe-01 :ARG0 (g / girl) :ARG1 w))")
    assertEquals(Shape.of(c), Shape(connected = true, acyclic = false))
    assertEquals(Acyclic.check(c), None)
    AmrValidator.validate(g, ValidationProfile.strict) match
      case Validated.Invalid(e) => assert(e.exists(_.isInstanceOf[AmrViolation.Cyclic]))
      case Validated.Valid(_)   => fail("expected cycle violation")
    val tree = canonical("(w / want-01 :ARG0 (b / boy) :ARG1 (g / go-02 :ARG0 b))")
    assertEquals(Shape.of(tree).acyclic, true)
    val order = Acyclic.check(tree).get.topologicalOrder
    assert(order.indexOf(NodeId.unsafe("w")) < order.indexOf(NodeId.unsafe("b")))
  }

  property("every valid generated graph validates and every one-violation mutation fails") {
    forAll(Gens.canonicalGraph.flatMap(g => Gens.invalidVariant(g).map(g -> _))) {
      case (g, (law, bad)) =>
        assert(AmrValidator.validate(g.asSurface.uncheck).isValid, "valid graph must validate")
        assert(AmrValidator.validate(bad).isInvalid, s"mutation '$law' must be rejected")
        true
    }
  }

  // ---- canonicalization & inversion ----------------------------------------------------

  test("inverse roles canonicalize to direct edges with swapped endpoints") {
    val g = canonical("(b / boy :ARG0-of (s / sing-01))")
    assertEquals(g.top.value, "b")
    assertEquals(g.edges, Vector(Edge("s", "ARG0", "b")))
    assert(g.edges.forall(!_.role.isInverse))
  }

  test("consist-of is a primary role, not an inverse") {
    val g = canonical("(r / ring :consist-of (g / gold))")
    assertEquals(g.edges.head.role.render, "consist-of")
    assertEquals(g.edges.head.source.value, "r")
  }

  property("law: invert(invert(r)) == r") {
    forAll(Gens.roleText) { text =>
      val r = SurfaceRole.unsafe(text)
      assertEquals(r.invert.invert, r)
      assertEquals(SurfaceRole.parse(r.invert.render).map(_.render), Right(r.invert.render))
      true
    }
  }

  // ---- isomorphism & canonical form -----------------------------------------------------

  test("isomorphism ignores variable names, edge order, and inverse spelling; exact Eq does not") {
    val a = canonical("(w / want-01 :ARG0 (b / boy) :ARG1 (g / go-02 :ARG0 b))")
    val b = canonical("(x / want-01 :ARG1 (y / go-02 :ARG0 (z / boy)) :ARG0 z)")
    val c = canonical("(z / boy :ARG0-of (x / want-01 :ARG1 (y / go-02 :ARG0 z)))")
    assert(AmrIsomorphism.isomorphic(a, b))
    assert(!AmrIsomorphism.isomorphic(a, c), "different top is not isomorphic")
    assert(!(a === b), "exact equality distinguishes variable names")
    assertEquals(Canonical.form(a), Canonical.form(b))
    assert(Canonical.form(a) === Canonical.form(b))
  }

  test("isomorphism distinguishes role reversal and polarity") {
    val a = canonical("(h / hit-01 :ARG0 (w / warrior) :ARG1 (m / man))")
    val b = canonical("(h / hit-01 :ARG0 (m / man) :ARG1 (w / warrior))")
    val c = canonical("(h / hit-01 :ARG0 (w / warrior) :ARG1 (m / man) :polarity -)")
    assert(!AmrIsomorphism.isomorphic(a, b))
    assert(!AmrIsomorphism.isomorphic(a, c))
    assertNotEquals(Canonical.digest(a), Canonical.digest(b))
  }

  test("isomorphism handles symmetric siblings (shared-literal fixture) correctly") {
    val g = canonical(PenmanGolden.cases.find(_.name == "g27-shared-literal").get.input)
    val swapped = canonical(
      "(a / and :op1 (c2 / cost-01 :ARG1 (b / book) :ARG2 (m2 / monetary-quantity :quant 5 :unit (d2 / dollar))) " +
        ":op2 (c / cost-01 :ARG1 (p / pen) :ARG2 (m / monetary-quantity :quant 5 :unit (d / dollar))))"
    )
    assert(AmrIsomorphism.isomorphic(g, swapped))
    assertEquals(Canonical.form(g), Canonical.form(swapped))
    val different = canonical(
      "(a / and :op1 (c / cost-01 :ARG1 (b / pen) :ARG2 (m / monetary-quantity :quant 5 :unit (d / dollar))) " +
        ":op2 (c2 / cost-01 :ARG1 (p / pen) :ARG2 (m2 / monetary-quantity :quant 5 :unit (d2 / dollar))))"
    )
    assert(!AmrIsomorphism.isomorphic(g, different))
  }

  property(
    "law: alpha variants (renamed, shuffled, re-oriented) are isomorphic and share a canonical form"
  ) {
    forAll(Gens.canonicalGraph.flatMap(g => Gens.alphaVariant(g).map(g -> _))) {
      case (g, variant) =>
        val v = RoleCanonicalizer.canonicalize(AmrValidator.validateOrThrow(variant))
        assert(AmrIsomorphism.isomorphic(g, v))
        assertEquals(Canonical.form(g), Canonical.form(v))
        assertEquals(Canonical.digest(g), Canonical.digest(v))
        true
    }
  }

  property("law: canonical form is idempotent and isomorphic to its input") {
    forAll(Gens.canonicalGraph) { g =>
      val f = Canonical.form(g)
      assertEquals(Canonical.form(f), f)
      assert(AmrIsomorphism.isomorphic(g, f))
      true
    }
  }

  property("law: non-isomorphic pairs have different canonical forms (iso ⇔ form equality)") {
    forAll(Gens.canonicalGraph, Gens.canonicalGraph) { (a, b) =>
      assertEquals(AmrIsomorphism.isomorphic(a, b), Canonical.form(a) == Canonical.form(b))
      true
    }
  }

  property("law: decode(encode(g)) ≅ g") {
    forAll(Gens.canonicalGraph) { g =>
      val text = Encoder.toPenman(g).fold(e => fail(e.toString), identity)
      val back = canonical(text)
      assert(AmrIsomorphism.isomorphic(g, back), text)
      true
    }
  }

  // ---- Smatch & compatibility ---------------------------------------------------------

  test("Smatch is 1.0 for alpha variants and below 1.0 for a role reversal; deterministic") {
    val a = canonical("(h / hit-01 :ARG0 (w / warrior) :ARG1 (m / man))")
    val b = canonical("(x / hit-01 :ARG1 (y / man) :ARG0 (z / warrior))")
    val c = canonical("(h / hit-01 :ARG0 (m / man) :ARG1 (w / warrior))")
    assertEqualsDouble(Smatch.score(a, b).f1, 1.0, 1e-9)
    val rev = Smatch.score(a, c)
    assert(rev.f1 < 1.0 && rev.f1 > 0.5)
    assertEquals(
      Smatch.score(a, c, restarts = 4, seed = 7),
      Smatch.score(a, c, restarts = 4, seed = 7)
    )
  }

  test("SoftCompatibility detects exact frames, role reversal, and polarity conflict") {
    val src = canonical("(h / hit-01 :ARG0 (w / warrior) :ARG1 (m / man))")
    val rev = canonical("(h / hit-01 :ARG0 (m / man) :ARG1 (w / warrior))")
    val neg = canonical("(f / feel-01 :ARG0 (m / man) :ARG1 (s / sick) :polarity -)")
    val pos = canonical("(f / feel-01 :ARG0 (m / man) :ARG1 (s / sick))")
    val para = canonical("(s / strike-01 :ARG0 (w / warrior) :ARG1 (m / man))")
    val r1 = SoftCompatibility.report(src, rev)
    assertEquals(r1.roleReversals.size, 1)
    assert(r1.hasStructuralConflict)
    val r2 = SoftCompatibility.report(neg, pos)
    assertEquals(r2.polarityConflicts.map(_.leftNegated), Vector(true))
    val r3 = SoftCompatibility.report(src, src)
    assertEqualsDouble(r3.argumentAgreement, 1.0, 1e-9)
    assert(!r3.hasStructuralConflict)
    val r4 = SoftCompatibility.report(src, para)
    assertEquals(r4.frames.head.kind, SoftCompatibility.FrameMatchKind.Unmatched)
  }

  // ---- schema ---------------------------------------------------------------------------

  test("schema checker: unknown frames warn, unlicensed args error, ARG0 is never 'Agent'") {
    val lex = StarterLexicon.lexicon
    val g = canonical("(w / want-01 :ARG0 (b / boy) :ARG7 (x / thing))")
    val findings = SchemaChecker.check(g, lex)
    assert(findings.exists {
      case SchemaFinding.UnlicensedArgument(_, f, i) => f.value == "want-01" && i.value == 7
      case _                                         => false
    })
    val unknown = canonical("(z / zorble-99 :ARG0 (b / boy))")
    assertEquals(SchemaChecker.check(unknown, lex).map(_.severity), Vector(Severity.Warning))
    assertEquals(SchemaChecker.errors(unknown, lex), Vector.empty)
    val comeSpec = lex.lookup(FrameId.unsafe("come-01")).get
    assert(
      !comeSpec.licenses(ArgIndex.unsafe(0)),
      "come-01 has no ARG0: argument meaning is frame-specific"
    )
    val partial = canonical("(b / boy :ARG0-of (s / sing))")
    assertEquals(
      SchemaChecker.check(partial, lex),
      Vector.empty,
      "lemma-only nodes need no lexicon hit"
    )
  }

  // ---- alignment sidecar ----------------------------------------------------------------

  private val fp = Fingerprint.unsafe("test:hand:1")
  private val stage = StageId.unsafe("amr")
  private def meta(id: String, spans: SpanSet): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(id),
      EpistemicStatus.SurfaceExplicit,
      Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
      NonEmptyVector.one(
        Evidence(EvidenceId.unsafe(id + "-ev"), Some(spans), Set.empty, fp, stage)
      ),
      Provenance.human("tester", "0.0")
    )

  test("alignment sidecar validates references and supports discontinuous / shared spans") {
    val g = canonical("(w / want-01 :ARG0 (b / boy) :ARG1 (g / go-02 :ARG0 b))")
    val sent = SurfaceUnitId.unsafe("s1")
    val spanBoy = SpanSet.one(TextSpan.unsafe(4, 7))
    val spanWant = SpanSet.unsafe(SpanRef(TextSpan.unsafe(8, 13)), SpanRef(TextSpan.unsafe(14, 16)))
    val entries = Vector(
      AlignmentEntry.Subgraph(
        SubgraphAlignment(
          NonEmptySet.one(NodeId.unsafe("b")),
          spanBoy,
          Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
          meta("c1", spanBoy)
        )
      ),
      AlignmentEntry.Subgraph(
        SubgraphAlignment(
          NonEmptySet.of(NodeId.unsafe("w"), NodeId.unsafe("g")),
          spanWant,
          Credence.unsafeRaw(0.9, ScorerId.unsafe("test-scorer")),
          meta("c2", spanWant)
        )
      ),
      AlignmentEntry.Reentrancy(
        ReentrancyAlignment(
          NodeId.unsafe("b"),
          Edge("g", "ARG0", "b"),
          spanBoy,
          Credence.unsafeRaw(0.8, ScorerId.unsafe("test-scorer")),
          meta("c3", spanBoy)
        )
      )
    )
    val a = AmrAlignment(sent, Canonical.digest(g), entries)
    assert(AmrAlignment.validate(a, g, Some(Canonical.digest(g))).isValid)
    assertEquals(a.unaligned(g), Set.empty[NodeId])
    assert(!a.spansOf(NodeId.unsafe("w")).get.isContiguous)
    val bad = a.copy(entries =
      entries :+ AlignmentEntry.Subgraph(
        SubgraphAlignment(
          NonEmptySet.one(NodeId.unsafe("zz")),
          spanBoy,
          Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
          meta("c4", spanBoy)
        )
      )
    )
    AmrAlignment.validate(bad, g) match
      case Validated.Invalid(e) =>
        assertEquals(
          e.toChain.toVector,
          Vector(AlignmentViolation.UnknownNode(NodeId.unsafe("zz")))
        )
      case Validated.Valid(_) => fail("expected unknown node")
    val other = canonical("(w / want-01 :ARG0 (b / boy))")
    assert(
      AmrAlignment.validate(a, other, Some(Canonical.digest(other))).isInvalid,
      "digest mismatch"
    )
  }
