package storymodel4s.amr

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.{Checked, Unchecked}
import storymodel4s.amr.graph.RoleForm.{CanonicalRoles, SurfaceRoles}
import storymodel4s.amr.interop.*
import storymodel4s.amr.penman.*
import storymodel4s.amr.schema.*
import storymodel4s.core.*
import storymodel4s.proposition as p

/** Loss reporting (review #13), number spelling (#32), primary `-of` roles (#10), and the marker
  * sidecar (#12).
  */
class InteropLossSuite extends ScalaCheckSuite:

  private val lexicon: FrameLexicon = StarterLexicon.lexicon
  private val sentence = Some(SurfaceUnitId.unsafe("s1"))

  private def unchecked(s: String): AmrGraph[Unchecked, SurfaceRoles] =
    Decoder.graphFromPenman(s).fold(fail(_), identity)

  private def canonical(s: String): AmrGraph[Checked, CanonicalRoles] =
    RoleCanonicalizer.canonicalize(AmrValidator.validateOrThrow(unchecked(s)))

  private def chart(s: String): p.PropositionChart[p.Checked] =
    ToChart.convert(canonical(s), None, lexicon, sentence).fold(e => fail(e.message), identity)

  private def back(c: p.PropositionChart[p.Checked]): AmrGraph[Checked, CanonicalRoles] =
    FromChart.convert(c, lexicon).fold(e => fail(e.message), identity)

  private def rechart(c: p.PropositionChart[p.Checked]): p.PropositionChart[p.Checked] =
    ToChart.convert(back(c), None, lexicon, sentence).fold(e => fail(e.message), identity)

  private def conceptWithLemma(c: p.PropositionChart[p.Checked], lemma: String): p.ConceptId =
    c.conceptIds
      .find(id => c.concepts(id).lemma.value == lemma)
      .getOrElse(fail(s"no concept with lemma $lemma"))

  private def rebuild(
      c: p.PropositionChart[p.Checked],
      concepts: Map[p.ConceptId, p.Concept] = null,
      relations: Vector[p.PropositionRelation] = null,
      polarity: Map[p.ConceptId, p.Polarity] = null,
      embedded: Vector[p.EmbeddedProposition] = null,
      alignments: Vector[p.PropositionAlignment] = null
  ): p.PropositionChart[p.Checked] =
    p.ChartValidator
      .check(
        p.PropositionChart.unchecked(
          c.focus,
          Option(concepts).getOrElse(c.concepts),
          Option(relations).getOrElse(c.relations),
          Option(polarity).getOrElse(c.polarity),
          Option(embedded).getOrElse(c.embedded),
          Option(alignments).getOrElse(c.alignments),
          c.provenance,
          c.sentence
        )
      )
      .fold(v => fail(v.toString), identity)

  test("unknown predicate polarity is lossy: strict refuses rather than asserting it") {
    val base = chart("(w / want-01 :ARG0 (b / boy))")
    val w = conceptWithLemma(base, "want")
    val unknown = rebuild(base, polarity = base.polarity.updated(w, p.Polarity.Unknown))
    val reasons = FromChart.lossReasons(unknown, lexicon)
    assert(reasons.exists(_.contains("unknown polarity")), reasons.toString)
    FromChart.convert(unknown, lexicon) match
      case Left(InteropError.Lossy(_)) => ()
      case other                       => fail(s"expected Lossy, got $other")
    val boy = conceptWithLemma(base, "boy")
    val positiveEntity = rebuild(base, polarity = base.polarity.updated(boy, p.Polarity.Positive))
    assert(FromChart.lossReasons(positiveEntity, lexicon).exists(_.contains("non-predicate")))
    assertEquals(FromChart.lossReasons(base, lexicon), Vector.empty)
  }

  test("alignments, glosses, sense credences, and foreign namespaces are reported as lossy") {
    val base = chart("(w / want-01 :ARG0 (b / boy))")
    val w = conceptWithLemma(base, "want")
    val want = base.concepts(w)
    val glossed = rebuild(base, concepts = base.concepts.updated(w, want.copy(gloss = Some("x"))))
    val sensed = rebuild(
      base,
      concepts = base.concepts.updated(
        w,
        want.copy(frame = want.frame.map(_.copy(senseCredence = Some(Credence.unsafeRaw(0.4)))))
      )
    )
    val foreign = rebuild(
      base,
      concepts =
        base.concepts.updated(w, want.copy(frame = want.frame.map(_.copy(namespace = "verbnet"))))
    )
    assert(FromChart.lossReasons(glossed, lexicon).exists(_.contains("gloss")))
    assert(FromChart.lossReasons(sensed, lexicon).exists(_.contains("sense credence")))
    assert(FromChart.lossReasons(foreign, lexicon).exists(_.contains("namespace")))
    val span = SpanSet.one(TextSpan.unsafe(0, 3))
    val meta = ClaimMeta(
      ClaimId.unsafe("a1"),
      EpistemicStatus.SurfaceExplicit,
      Credence.unsafeRaw(1.0),
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe("a1-ev"),
          Some(span),
          Set.empty,
          Fingerprint.unsafe("hand"),
          StageId.unsafe("st")
        )
      ),
      Provenance.human("test", "0")
    )
    val aligned = rebuild(
      base,
      alignments = Vector(
        p.PropositionAlignment(
          p.AlignmentTarget.Concepts(NonEmptySet.one(w)),
          span,
          Credence.unsafeRaw(1.0),
          meta
        )
      )
    )
    assert(FromChart.lossReasons(aligned, lexicon).exists(_.contains("alignment")))
  }

  test("embeddings and normalized roles the tables would not regenerate are lossy") {
    val base = chart("(z / zork-01 :ARG0 (b / boy) :ARG1 (g / go-02 :ARG0 b))")
    val z = conceptWithLemma(base, "zork")
    val g = conceptWithLemma(base, "go")
    val embedded =
      rebuild(base, embedded = Vector(p.EmbeddedProposition(z, p.EmbeddingKind.Speech, g)))
    assert(FromChart.lossReasons(embedded, lexicon).exists(_.contains("embedding")))
    val agentRole = base.relations.map { r =>
      if r.role.source == p.SourceRole.Numbered(0) && r.from == z then
        r.copy(role =
          r.role.copy(normalized = Some((p.ParticipantRole.Agent, Credence.unsafeRaw(0.7))))
        )
      else r
    }
    val normalized = rebuild(base, relations = agentRole)
    assert(FromChart.lossReasons(normalized, lexicon).exists(_.contains("normalized role")))
    // a licensed normalization is regenerated and therefore not lossy
    val licensed = chart("(h / hit-01 :ARG0 (w / warrior) :ARG1 (m / man))")
    assertEquals(FromChart.lossReasons(licensed, lexicon), Vector.empty)
  }

  property("law: lossReasons(c).isEmpty ⇒ ToChart(FromChart(c)) ≅ c") {
    forAll(Gens.canonicalGraph) { g =>
      val ch = ToChart.convert(g, None, lexicon, None).fold(e => fail(e.message), identity)
      assertEquals(FromChart.lossReasons(ch, lexicon), Vector.empty)
      assert(p.ChartIsomorphism.isomorphic(ch, rechart(ch)))
      true
    }
  }

  test("number literal spelling is canonical in the graph and round-trips") {
    val ch = chart("(t / temperature-quantity :quant 1e3 :scale (k / kelvin))")
    val quant = ch.relations.find(_.role.source.render == "quant").get.to
    assertEquals(quant, p.ConceptTarget.Literal(p.LiteralValue.Number(BigDecimal(1000))))
    val g = back(ch)
    assertEquals(g.attributes(g.top).map(_._2.render), Vector("1000"))
    val plain = canonical("(t / temperature-quantity :quant 1000 :scale (k / kelvin))")
    assert(AmrIsomorphism.isomorphic(plain, g))
    val trailing = canonical("(t / temperature-quantity :quant 5.0 :scale (k / kelvin))")
    assertEquals(trailing.attributes(trailing.top).map(_._2.render), Vector("5"))
    val tree = PenmanParser.parse("(t / temperature-quantity :quant 1e3)").toOption.get
    assertEquals(
      PenmanPrinter.print(tree, PenmanPrinter.Options.oneLine),
      "(t / temperature-quantity :quant 1e3)",
      "the tree keeps the surface spelling"
    )
  }

  test("number-shaped symbols are lossy because they would re-decode as numbers") {
    val base = chart("(a / and :op1 \"x\")")
    val a = conceptWithLemma(base, "and")
    val sym = rebuild(
      base,
      relations = Vector(
        p.PropositionRelation(
          a,
          p.RoleAssignment(p.SourceRole.Operand(1), None),
          p.ConceptTarget.Literal(p.LiteralValue.Symbol("42"))
        )
      )
    )
    assert(FromChart.lossReasons(sym, lexicon).exists(_.contains("number-shaped")))
  }

  test("multiword prep roles ending in -of are primary, not inverses") {
    val ch = chart("(l / leave-11 :ARG0 (b / boy) :prep-out-of (h / house))")
    val rel = ch.relations.find(_.role.source.render == "prep-out-of").get
    assertEquals(rel.from, conceptWithLemma(ch, "leave"))
    assertEquals(rel.to, p.ConceptTarget.Node(conceptWithLemma(ch, "house")))
    assertEquals(SurfaceRole.parse("prep-of").map(_.isInverse), Right(true))
    assertEquals(SurfaceRole.parse("prep-out-of-of").map(_.isInverse), Right(true))
  }

  // ---- marker sidecar ----------------------------------------------------------------------

  private val markedText =
    "(b / boy~e.1 :ARG0-of~e.2 (s / sing-01~e.2 :ARG1 b~e.1) :mode imperative~e.3)"
  private val tokenSpans: TokenSpans = TokenSpans.fromSpans(
    Vector(
      TextSpan.unsafe(0, 3),
      TextSpan.unsafe(4, 7),
      TextSpan.unsafe(8, 13),
      TextSpan.unsafe(14, 15)
    )
  )

  test("markers without token spans are refused under Strict and dropped under Ignore") {
    AmrCandidates.fromPenman(markedText, lexicon, sentence) match
      case Left(InteropError.Lossy(rs)) => assert(rs.exists(_.contains("alignment marker")))
      case other                        => fail(s"expected Lossy, got $other")
    val ignored = AmrCandidates
      .fromPenman(markedText, lexicon, sentence, markers = MarkerPolicy.Ignore)
      .fold(e => fail(e.message), identity)
    assertEquals(ignored.alignments, Vector.empty)
    val unmarked = AmrCandidates.fromPenman("(b / boy)", lexicon, sentence)
    assert(unmarked.isRight, "no markers, nothing to lose")
  }

  test("markers with token spans become chart alignments with exact spans") {
    val ch = AmrCandidates
      .fromPenman(markedText, lexicon, sentence, tokens = Some(tokenSpans))
      .fold(e => fail(e.message), identity)
    val boy = conceptWithLemma(ch, "boy")
    val sing = conceptWithLemma(ch, "sing")
    assert(ch.alignments.nonEmpty)
    // concept markers: `boy~e.1` and the reentrant `b~e.1` both point at token 1
    val boySpans = ch.alignments.collect {
      case p.PropositionAlignment(p.AlignmentTarget.Concepts(ids), s, _, _)
          if ids.toSortedSet.toSet == Set(boy) =>
        s
    }
    assert(boySpans.size == 2, ch.alignments.toString)
    assert(boySpans.forall(_.minSpan == TextSpan.unsafe(4, 7)))
    // the attribute marker `imperative~e.3` aligns the :mode relation to token 3
    assert(ch.alignments.exists {
      case p.PropositionAlignment(p.AlignmentTarget.Relation(r), s, _, _) =>
        r.role.source.render == "mode" && s.minSpan == TextSpan.unsafe(14, 15)
      case _ => false
    })
    assert(
      ch.alignments.exists(a =>
        a.target.conceptIds == Set(sing) && a.spans.minSpan == TextSpan.unsafe(8, 13)
      )
    )
    assert(ch.alignments.exists {
      case p.PropositionAlignment(p.AlignmentTarget.Relation(r), s, _, _) =>
        r.role.source.render == "ARG0" && s.minSpan == TextSpan.unsafe(8, 13)
      case _ => false
    })
    ch.alignments.foreach(a => assertEquals(a.meta.status, EpistemicStatus.SurfaceExplicit))
    val outOfRange =
      AmrCandidates.fromPenman("(b / boy~e.9)", lexicon, sentence, tokens = Some(tokenSpans))
    assert(outOfRange.isLeft)
  }
