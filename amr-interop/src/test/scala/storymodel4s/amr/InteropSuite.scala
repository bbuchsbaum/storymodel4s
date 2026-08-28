package storymodel4s.amr

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.amr.align.*
import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.{Checked, Unchecked}
import storymodel4s.amr.graph.RoleForm.{CanonicalRoles, SurfaceRoles}
import storymodel4s.amr.interop.*
import storymodel4s.amr.schema.*
import storymodel4s.core.*
import storymodel4s.proposition as p

class InteropSuite extends ScalaCheckSuite:

  private val lexicon: FrameLexicon = StarterLexicon.lexicon
  private val sentence = Some(SurfaceUnitId.unsafe("s1"))

  private def unchecked(s: String): AmrGraph[Unchecked, SurfaceRoles] =
    Decoder.graphFromPenman(s).fold(fail(_), identity)

  private def canonical(s: String): AmrGraph[Checked, CanonicalRoles] =
    RoleCanonicalizer.canonicalize(AmrValidator.validateOrThrow(unchecked(s)))

  private def chart(s: String): p.PropositionChart[p.Checked] =
    ToChart.convert(canonical(s), None, lexicon, sentence).fold(e => fail(e.message), identity)

  private def back(c: p.PropositionChart[p.Checked]): AmrGraph[Checked, CanonicalRoles] =
    FromChart.convert(c).fold(e => fail(e.message), identity)

  private def conceptWithLemma(c: p.PropositionChart[p.Checked], lemma: String): p.ConceptId =
    c.conceptIds
      .find(id => c.concepts(id).lemma.value == lemma)
      .getOrElse(fail(s"no concept with lemma $lemma"))

  // ---- round-trip laws -----------------------------------------------------------------

  test("FromChart(ToChart(g)) is isomorphic to g for every golden case") {
    PenmanGolden.cases.foreach { c =>
      val g = canonical(c.input)
      val ch = ToChart
        .convert(g, None, lexicon, sentence)
        .fold(e => fail(s"${c.name}: ${e.message}"), identity)
      val g2 = FromChart.convert(ch).fold(e => fail(s"${c.name}: ${e.message}"), identity)
      assert(AmrIsomorphism.isomorphic(g, g2), s"${c.name}: AMR round trip not isomorphic")
    }
  }

  test("ToChart(FromChart(c)) is chart-isomorphic to c for every golden-derived chart") {
    PenmanGolden.cases.foreach { c =>
      val ch = chart(c.input)
      val g2 = back(ch)
      val ch2 = ToChart
        .convert(g2, None, lexicon, sentence)
        .fold(e => fail(s"${c.name}: ${e.message}"), identity)
      assert(p.ChartIsomorphism.isomorphic(ch, ch2), s"${c.name}: chart round trip failed")
      assertEquals(p.Canonical.checksum(ch), p.Canonical.checksum(ch2), c.name)
    }
  }

  test("golden-derived charts are fully AMR-expressible") {
    PenmanGolden.cases.foreach { c =>
      assertEquals(FromChart.lossReasons(chart(c.input)), Vector.empty, c.name)
    }
  }

  property("FromChart(ToChart(g)) ≅ g for generated canonical graphs") {
    forAll(Gens.canonicalGraph) { g =>
      val ch = ToChart.convert(g, None, lexicon, None).fold(e => fail(e.message), identity)
      val g2 = back(ch)
      AmrIsomorphism.isomorphic(g, g2)
    }
  }

  property("ToChart(FromChart(c)) ≅ c for charts derived from generated graphs") {
    forAll(Gens.canonicalGraph) { g =>
      val ch = ToChart.convert(g, None, lexicon, None).fold(e => fail(e.message), identity)
      val ch2 = ToChart.convert(back(ch), None, lexicon, None).fold(e => fail(e.message), identity)
      p.ChartIsomorphism.isomorphic(ch, ch2)
    }
  }

  // ---- mapping details -----------------------------------------------------------------

  test("frame concepts become predicates with a propbank FrameRef and Positive polarity") {
    val ch = chart("(w / want-01 :ARG0 (b / boy))")
    val w = conceptWithLemma(ch, "want")
    val c = ch.concepts(w)
    assertEquals(c.kind, p.ConceptKind.Predicate)
    assertEquals(c.frame.map(_.key), Some(("propbank", "want-01")))
    assertEquals(ch.polarityOf(w), p.Polarity.Positive)
    assertEquals(ch.focus, Some(w))
    assertEquals(ch.concepts(conceptWithLemma(ch, "boy")).kind, p.ConceptKind.Entity)
  }

  test("special concepts: -91 rolesets, name, quantities, amr-unknown") {
    val ch = chart(
      "(h / have-org-role-91 :ARG0 (p / person :name (n / name :op1 \"Ann\")) " +
        ":ARG2 (d / distance-quantity :quant 5) :ARG3 (u / amr-unknown))"
    )
    assertEquals(ch.concepts(conceptWithLemma(ch, "have-org-role")).kind, p.ConceptKind.Special)
    assertEquals(ch.concepts(conceptWithLemma(ch, "name")).kind, p.ConceptKind.Name)
    assertEquals(
      ch.concepts(conceptWithLemma(ch, "distance-quantity")).kind,
      p.ConceptKind.Quantity
    )
    assertEquals(ch.concepts(conceptWithLemma(ch, "amr-unknown")).kind, p.ConceptKind.Special)
  }

  test("lexical concepts heading :domain or filling :mod are properties") {
    val ch = chart("(t / tall :domain (g / girl :mod (s / small)))")
    assertEquals(ch.concepts(conceptWithLemma(ch, "tall")).kind, p.ConceptKind.Property)
    assertEquals(ch.concepts(conceptWithLemma(ch, "small")).kind, p.ConceptKind.Property)
    assertEquals(ch.concepts(conceptWithLemma(ch, "girl")).kind, p.ConceptKind.Entity)
  }

  test(":polarity - becomes Negative chart polarity and no relation") {
    val ch = chart("(g / go-02 :ARG0 (b / boy) :polarity -)")
    val g = conceptWithLemma(ch, "go")
    assertEquals(ch.polarityOf(g), p.Polarity.Negative)
    assert(ch.relations.forall(_.role.source.render != "polarity"))
  }

  test("bare ARGn on an unknown frame never receives a normalized role") {
    val ch = chart("(z / zork-01 :ARG0 (b / boy) :ARG1 (g / girl))")
    ch.relations.foreach(r => assertEquals(r.role.normalized, None))
  }

  test("lexicon-licensed ARGn receives a weak normalized role from the functional tag") {
    val ch = chart("(h / hit-01 :ARG0 (w / warrior) :ARG1 (m / man))")
    val roles = ch.relations.map(r => r.role.source.render -> r.role.normalized).toMap
    assertEquals(roles("ARG0").map(_._1), Some(p.ParticipantRole.Agent))
    assertEquals(roles("ARG1").map(_._1), Some(p.ParticipantRole.Patient))
    assertEquals(roles("ARG0").map(_._2.rawScore), Some(InteropTables.LexiconCredence))
    assert(
      roles("ARG0").exists(_._2.calibrated.isEmpty),
      "lexicon credence is raw, never calibrated"
    )
  }

  test("standard roles receive high-credence normalized roles; other named roles none") {
    val ch =
      chart("(g / go-02 :ARG0 (b / boy) :location (h / house) :mod (q / quick) :time (n / now))")
    val roles = ch.relations.map(r => r.role.source.render -> r.role.normalized).toMap
    assertEquals(roles("location").map(_._1), Some(p.ParticipantRole.Location))
    assertEquals(roles("time").map(_._1), Some(p.ParticipantRole.Time))
    assertEquals(roles("location").map(_._2.rawScore), Some(InteropTables.StandardRoleCredence))
    assertEquals(roles("mod"), None)
  }

  test("say-01 :ARG1 with a predicate filler yields a Speech embedding; nested embeddings stack") {
    val ch =
      chart("(s / say-01 :ARG0 (g / girl) :ARG1 (w / want-01 :ARG0 g :ARG1 (l / like-01 :ARG0 g)))")
    val say = conceptWithLemma(ch, "say")
    val want = conceptWithLemma(ch, "want")
    val like = conceptWithLemma(ch, "like")
    assertEquals(
      ch.embeddingOf(want),
      Some(p.EmbeddedProposition(say, p.EmbeddingKind.Speech, want))
    )
    assertEquals(
      ch.embeddingOf(like),
      Some(p.EmbeddedProposition(want, p.EmbeddingKind.Desire, like))
    )
    assert(!ch.isEmbedded(say))
  }

  test("say-01 :ARG1 with a non-predicate filler and unknown containers embed nothing") {
    val ch = chart("(s / say-01 :ARG0 (g / girl) :ARG1 (t / truth))")
    assertEquals(ch.embedded, Vector.empty)
    val ch2 = chart("(z / zork-01 :ARG0 (g / girl) :ARG1 (l / like-01 :ARG0 g))")
    assertEquals(ch2.embedded, Vector.empty)
  }

  test(":condition embeds a predicate filler as Hypothetical") {
    val ch = chart("(g / go-02 :ARG0 (b / boy) :condition (r / rain-01))")
    assertEquals(
      ch.embeddingOf(conceptWithLemma(ch, "rain")).map(_.kind),
      Some(p.EmbeddingKind.Hypothetical)
    )
  }

  test("literals convert by kind and :sntN / :opN / extension roles survive") {
    val ch = chart(
      "(m / multi-sentence :snt1 (a / and :op1 \"x\" :op2 5 :op3 -1.5 :mode imperative) :snt2 (b / boy :ns.custom (c / cat)))"
    )
    val rendered = ch.relations.map(r => r.role.source.render -> r.to).toMap
    assertEquals(rendered("op1"), p.ConceptTarget.Literal(p.LiteralValue.Text("x")))
    assertEquals(rendered("op2"), p.ConceptTarget.Literal(p.LiteralValue.Number(BigDecimal(5))))
    assertEquals(
      rendered("op3"),
      p.ConceptTarget.Literal(p.LiteralValue.Number(BigDecimal("-1.5")))
    )
    assertEquals(rendered("mode"), p.ConceptTarget.Literal(p.LiteralValue.Symbol("imperative")))
    assert(rendered.contains("snt1") && rendered.contains("snt2"))
    assert(ch.relations.exists(_.role.source == p.SourceRole.Extension("ns", "custom")))
  }

  test("reentrancy is preserved as a shared concept") {
    val ch = chart("(w / want-01 :ARG0 (b / boy) :ARG1 (g / go-02 :ARG0 b))")
    val boy = conceptWithLemma(ch, "boy")
    assert(ch.isReentrant(boy))
    assertEquals(ch.referenceCount(boy), 2)
  }

  test("provenance records the AMR digest so the source artifact is recoverable") {
    val g = canonical("(w / want-01 :ARG0 (b / boy))")
    val ch = ToChart.convert(g, None, lexicon, sentence).fold(e => fail(e.message), identity)
    assertEquals(ToChart.sourceDigest(ch), Some(Canonical.digest(g)))
    ch.provenance.origin match
      case p.ChartOrigin.Converted(from, fp) =>
        assertEquals(from, "amr")
        assertEquals(fp, ToChart.fingerprint)
      case other => fail(s"unexpected origin $other")
    assertEquals(ch.sentence, sentence)
    assertEquals(ch.provenance.receipts.last.params.get("lexicon"), Some(lexicon.version))
  }

  test("alignment sidecar entries become chart alignments, including relation alignments") {
    val g = canonical("(s / see-01 :ARG0 (i / i) :ARG1 (p / picture) :polarity -)")
    val span = SpanSet.unsafe(SpanRef(TextSpan.unsafe(0, 3)))
    val meta = ClaimMeta.unsafe(
      ClaimId.unsafe("c1"),
      EpistemicStatus.SurfaceExplicit,
      Credence.unsafeRaw(1.0),
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe("e1"),
          Some(span),
          Set.empty,
          Fingerprint.unsafe("hand"),
          StageId.unsafe("st")
        )
      ),
      Provenance.human("test", "0")
    )
    val cred = Credence.unsafeRaw(0.8)
    val seeEdge = g.edges.find(e => e.role.base.render == "ARG1").get
    val polEdge = g.edges.find(e => e.role.base == Role.polarity).get
    val alignment = AmrAlignment(
      sentence.get,
      Canonical.digest(g),
      Vector(
        AlignmentEntry.Subgraph(
          SubgraphAlignment(NonEmptySet.of(NodeId.unsafe("s")), span, cred, meta)
        ),
        AlignmentEntry.Reentrancy(
          ReentrancyAlignment(NodeId.unsafe("i"), seeEdge, span, cred, meta)
        ),
        AlignmentEntry.Relation(RelationAlignment(seeEdge, span, cred, meta)),
        AlignmentEntry.Relation(RelationAlignment(polEdge, span, cred, meta))
      )
    )
    val ch =
      ToChart.convert(g, Some(alignment), lexicon, sentence).fold(e => fail(e.message), identity)
    assertEquals(ch.alignments.size, 4)
    val relationTargets = ch.alignments.collect {
      case p.PropositionAlignment(p.AlignmentTarget.Relation(r), _, _, _) => r
    }
    assertEquals(relationTargets.size, 1)
    assertEquals(relationTargets.head.role.source.render, "ARG1")
    // the polarity edge has no chart relation: it falls back to the source concept
    val see = conceptWithLemma(ch, "see")
    assert(ch.alignments.count(_.target.conceptIds == Set(see)) >= 2)
  }

  // ---- compatibility gates through the bridge ------------------------------------------

  test("role-swapped PENMAN pair is flagged as role reversal") {
    val straight = chart("(s / strike-01 :ARG0 (w / warrior) :ARG1 (m / man))")
    val swapped = chart("(s / strike-01 :ARG0 (m / man) :ARG1 (w / warrior))")
    val report = p.ChartCompatibility.compare(straight, swapped)
    assert(report.roleReversal, report.toString)
    assert(report.gated)
    assert(!p.ChartCompatibility.compare(straight, straight).gated)
  }

  test("negated PENMAN variant is flagged as polarity conflict") {
    val pos = chart("(f / feel-01 :ARG0 (m / man) :ARG1 (s / sick-05 :ARG1 m))")
    val neg = chart("(f / feel-01 :ARG0 (m / man) :ARG1 (s / sick-05 :ARG1 m) :polarity -)")
    val report = p.ChartCompatibility.compare(pos, neg)
    assert(report.polarityConflict, report.toString)
    assert(!report.roleReversal)
  }

  test("reported versus asserted proposition is flagged as embedding conflict") {
    val reported =
      chart("(s / say-01 :ARG0 (w / warrior) :ARG1 (h / hit-01 :ARG0 w :ARG1 (m / man)))")
    val asserted = chart("(h / hit-01 :ARG0 (w / warrior) :ARG1 (m / man))")
    val report = p.ChartCompatibility.compare(reported, asserted)
    assert(report.embeddingConflict, report.toString)
  }

  // ---- FromChart policy ----------------------------------------------------------------

  test("FromChart is strict about unknown fillers and lossy on request") {
    val base = chart("(w / want-01 :ARG0 (b / boy))")
    val w = conceptWithLemma(base, "want")
    val withUnknown = p.ChartValidator
      .check(
        p.PropositionChart.unchecked(
          base.focus,
          base.concepts,
          base.relations :+ p
            .PropositionRelation(w, p.RoleAssignment.arg(1), p.ConceptTarget.Unknown),
          base.polarity,
          base.embedded
        )
      )
      .fold(v => fail(v.toString), identity)
    FromChart.convert(withUnknown) match
      case Left(InteropError.Lossy(rs)) => assert(rs.exists(_.contains("unknown filler")))
      case other                        => fail(s"expected Lossy, got $other")
    val lossy =
      FromChart.convert(withUnknown, FromChart.Policy.Lossy).fold(e => fail(e.message), identity)
    assertEquals(lossy.edgeCount, 1)
  }

  test("FromChart maps Concept.unknown to amr-unknown under the lossy policy only") {
    val u = p.ConceptId.unsafe("u")
    val f = p.ConceptId.unsafe("f")
    val ch = p.ChartValidator
      .check(
        p.PropositionChart.unchecked(
          Some(f),
          Map(
            f -> p.Concept.predicate("find", Some(p.FrameRef("propbank", "find-01", None))),
            u -> p.Concept.unknown
          ),
          Vector(p.PropositionRelation(f, p.RoleAssignment.arg(1), p.ConceptTarget.Node(u)))
        )
      )
      .fold(v => fail(v.toString), identity)
    assert(FromChart.convert(ch).isLeft)
    val g = FromChart.convert(ch, FromChart.Policy.Lossy).fold(e => fail(e.message), identity)
    assert(g.concepts.values.exists(_ == Concept.Special("amr-unknown")))
  }

  test("FromChart rejects charts without a focus under strict policy and reports it") {
    val b = p.ConceptId.unsafe("b")
    val ch = p.ChartValidator
      .check(p.PropositionChart.unchecked(None, Map(b -> p.Concept.entity("boy")), Vector.empty))
      .fold(v => fail(v.toString), identity)
    assert(FromChart.lossReasons(ch).exists(_.contains("focus")))
    assert(FromChart.convert(ch).isLeft)
    assert(FromChart.convert(ch, FromChart.Policy.Lossy).isRight)
  }

  // ---- candidates ------------------------------------------------------------------------

  test("AmrCandidates yields typed errors for malformed input and never throws") {
    val out = AmrCandidates.toChartCandidates(
      Vector(
        "(w / want-01 :ARG0 (b / boy))",
        "(w / want-01 :ARG0 (b / boy)",
        "(w / want-01 :ARG0 x)",
        ""
      ),
      lexicon,
      sentence
    )
    assertEquals(out.size, 4)
    assert(out(0).isRight)
    assert(out(1).isLeft)
    assert(out.drop(1).forall(_.isLeft))
    out(2) match
      case Left(InteropError.Malformed(_)) | Left(InteropError.GraphInvalid(_)) => ()
      case other => fail(s"unexpected $other")
  }
