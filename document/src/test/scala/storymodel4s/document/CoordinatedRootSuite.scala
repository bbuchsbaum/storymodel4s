package storymodel4s.document

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.proposition.{Polarity as ChartPolarity, *}
import storymodel4s.story.{Polarity as StoryPolarity, *}

/** Court for the three root shapes slice 1.5 added to [[ChartProposalProvider]]: a coordinating
  * focus whose branches are the roots, a property predicated of a `:domain` filler, and an entity
  * placed by a `:location` filler.
  *
  * Every chart here is hand-built and checked, so each test states exactly one structural fact and
  * a rule deletion turns a named test red rather than moving a fifty-sentence total.
  */
class CoordinatedRootSuite extends FunSuite:
  private val source = StorySource
    .titled(
      "They landed and went home. He was dead. There were people at Egulac.",
      StoryTitle.callerSupplied("Coordination court").fold(e => fail(e.message), identity)
    )
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val s0 = atlas.sentences(0)
  private val s1 = atlas.sentences(1)
  private val s2 = atlas.sentences(2)
  private val parser = Fingerprint.unsafe("test:chart-parser:1")
  private val parserStage = StageId.unsafe("test-chart-parser")

  private def id(name: String): ConceptId = ConceptId.unsafe(name)
  private def ref(unit: SurfaceUnit, concept: String): ChartNodeRef =
    ChartNodeRef(unit.id, id(concept))

  private def chartCall(salt: String): ProviderCall =
    ProviderCall(
      "test-parser",
      "test-model",
      "1",
      None,
      source.canonicalChecksum,
      Checksum.ofText(s"chart:$salt"),
      Map("salt" -> salt),
      None,
      cached = false
    )

  private def spanOf(unit: SurfaceUnit, word: String): SpanRef =
    val text = atlas.text(unit)
    val at = text.indexOf(word)
    assert(at >= 0, s"'$word' is not in '$text'")
    SpanRef(
      Some(unit.id),
      TextSpan.unsafe(unit.span.start + at, unit.span.start + at + word.length)
    )

  private def align(unit: SurfaceUnit, word: String, concept: String): PropositionAlignment =
    val spans = SpanSet.one(spanOf(unit, word))
    val evidence = Evidence(
      EvidenceId.unsafe(s"ev:align:${unit.id.value}:$word"),
      Some(spans),
      Set.empty,
      parser,
      parserStage
    )
    PropositionAlignment(
      AlignmentTarget.Concepts(NonEmptySet.one(id(concept))),
      spans,
      Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
      ClaimMeta.unsafe(
        ClaimId.unsafe(s"claim:align:${unit.id.value}:$word"),
        EpistemicStatus.SurfaceExplicit,
        Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
        NonEmptyVector.one(evidence),
        Provenance.deterministic("test", Checksum.ofText("test-parser"))
      )
    )

  private def checked(
      unit: SurfaceUnit,
      focus: String,
      concepts: Map[String, Concept],
      relations: Vector[PropositionRelation] = Vector.empty,
      polarity: Map[String, ChartPolarity] = Map.empty,
      embedded: Vector[EmbeddedProposition] = Vector.empty,
      alignments: Vector[PropositionAlignment] = Vector.empty,
      salt: String = ""
  ): PropositionEvidence =
    val unchecked = PropositionChart.unchecked(
      Some(id(focus)),
      concepts.map((name, concept) => id(name) -> concept),
      relations,
      polarity.map((name, value) => id(name) -> value),
      embedded,
      alignments,
      ChartProvenance(
        ChartOrigin.Parser(parser),
        Vector(chartCall(s"${unit.id.value}$salt")),
        Vector.empty
      ),
      Some(unit.id)
    )
    PropositionEvidence.of(ChartValidator.check(unchecked).fold(v => fail(v.toString), identity))

  private def rel(from: String, role: RoleAssignment, to: String): PropositionRelation =
    PropositionRelation(id(from), role, ConceptTarget.Node(id(to)))

  private def lit(from: String, role: RoleAssignment, value: String): PropositionRelation =
    PropositionRelation(id(from), role, ConceptTarget.Literal(LiteralValue.Symbol(value)))

  private def op(index: Int): RoleAssignment = RoleAssignment(SourceRole.Operand(index), None)
  private def snt(index: Int): RoleAssignment = RoleAssignment.named(s"snt$index")
  private val agent =
    RoleAssignment(
      SourceRole.Numbered(0),
      Some((ParticipantRole.Agent, Credence.unsafeRaw(0.5, ScorerId.unsafe("test-scorer"))))
    )

  private def frame(name: String): Option[FrameRef] = Some(FrameRef("propbank", name, None))

  private def propose(charts: Vector[(SurfaceUnitId, PropositionEvidence)]): ChartProposals =
    ChartProposalProvider.propose(source, atlas, charts).fold(e => fail(e.message), identity)

  private def compile(charts: Vector[(SurfaceUnitId, PropositionEvidence)]): NarrativeCompilation =
    val input =
      ChartProposalProvider
        .input(source, atlas, charts, None, 0L)
        .fold(e => fail(e.message), identity)
    NarrativeCompiler.compile(input).fold(e => fail(e.message), identity)

  private def value[A](bundle: EvidenceBundle[A]): A =
    bundle.proposals.head.value.getOrElse(fail("the bundle has no proposed value"))

  private def spansOf(bundle: EvidenceBundle[?]): SpanSet =
    bundle.proposals.head.evidence.head match
      case EvidenceRef.Inline(ev) => ev.spans.getOrElse(fail("no spans"))
      case other                  => fail(s"not inline: $other")

  private def branches(row: SentenceCoverage): Vector[CoordinatedBranch] = row match
    case SentenceCoverage.Coordinated(_, bs) => bs
    case other                               => fail(s"not a coordinated row: $other")

  /** `They landed and went home.`: `(a / and :op1 (l / land-01 :ARG0 (t / they)) :op2 (g / go-02
    * :ARG0 t :destination (h / home)))`. Both branches take the same `they`.
    */
  private def landedAndWent: PropositionEvidence =
    checked(
      s0,
      "a",
      Map(
        "a" -> Concept.entity("and"),
        "l" -> Concept.predicate("land", frame("land-01")),
        "g" -> Concept.predicate("go", frame("go-02")),
        "t" -> Concept.entity("they"),
        "h" -> Concept.entity("home")
      ),
      Vector(
        rel("a", op(1), "l"),
        rel("a", op(2), "g"),
        rel("l", agent, "t"),
        rel("g", agent, "t"),
        rel("g", RoleAssignment.named("destination"), "h")
      ),
      polarity = Map("l" -> ChartPolarity.Positive, "g" -> ChartPolarity.Positive),
      alignments = Vector(
        align(s0, "and", "a"),
        align(s0, "landed", "l"),
        align(s0, "went", "g"),
        align(s0, "They", "t"),
        align(s0, "home", "h")
      )
    )

  test("a coordinating focus yields one situation per branch, in :op order") {
    val proposals = propose(Vector(s0.id -> landedAndWent))
    val land = ref(s0, "l")
    val go = ref(s0, "g")

    assertEquals(
      branches(proposals.coverage.head),
      Vector(
        CoordinatedBranch.Admitted(land, SourceRole.Operand(1), FillerCounts(1, 0, 0, 0, 0, 0)),
        CoordinatedBranch.Admitted(go, SourceRole.Operand(2), FillerCounts(2, 0, 0, 0, 0, 0))
      )
    )
    assertEquals(proposals.counts, CoverageCounts(0, 1, 0, 0, 2))
    assertEquals(proposals.counts.sentences, atlas.sentences.size)
    assertEquals(proposals.situations.map(_.source).toSet, Set(land, go))
    assertEquals(proposals.contexts.map(_.source).toSet, Set(land, go))
    assertEquals(proposals.memberships.map(_.member).toSet, Set(land, go))
    assertEquals(proposals.participantCoverage.map(_.situation).toSet, Set(land, go))
    val bySource = proposals.situations.map(a => a.source -> value(a.bundle)).toMap
    assertEquals(bySource(land).predicate, Predicate("land", Some("propbank:land-01"), "land"))
    assertEquals(bySource(land).description, "land they")
    assertEquals(bySource(land).kind, SituationKind.Event)
    assertEquals(bySource(land).polarity, StoryPolarity.Positive)
    assertEquals(bySource(go).predicate, Predicate("go", Some("propbank:go-02"), "go"))
    assertEquals(bySource(go).description, "go they (destination: home)")

    val compiled = compile(Vector(s0.id -> landedAndWent))
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    assertEquals(model.graph.situations.size, 2)
    assertEquals(
      model.graph.discourseOrder.flatMap(model.graph.situations.get).map(_.predicate.lemma),
      Vector("land", "go")
    )
    assertEquals(compiled.derivation.gaps, Vector.empty)
  }

  test("each branch is supported by its own words, never by the whole chart") {
    val proposals = propose(Vector(s0.id -> landedAndWent))
    val bySource = proposals.situations.map(a => a.source -> a.bundle).toMap

    assertEquals(
      spansOf(bySource(ref(s0, "l"))),
      SpanSet.one(spanOf(s0, "landed")) ++ SpanSet.one(spanOf(s0, "They"))
    )
    assertEquals(
      spansOf(bySource(ref(s0, "g"))),
      SpanSet.one(spanOf(s0, "went")) ++ SpanSet.one(spanOf(s0, "They")) ++
        SpanSet.one(spanOf(s0, "home"))
    )
    assertEquals(
      proposals.calls
        .filter(_.params.get("rule").contains(ChartProposalProvider.SituationRule))
        .map(_.params("span-source"))
        .toSet,
      Set("branch-alignments")
    )
    assertEquals(
      proposals.calls
        .filter(_.params.get("rule").contains(ChartProposalProvider.SituationRule))
        .map(_.params("root-rule"))
        .toSet,
      Set("predicate")
    )
  }

  test("coordinated siblings get one Unclear step: and asserts conjunction, not sequence") {
    val proposals = propose(Vector(s0.id -> landedAndWent))

    assertEquals(
      proposals.temporal.map(a => (a.from, a.to)),
      Vector((ref(s0, "l"), ref(s0, "g")))
    )
    assertEquals(value(proposals.temporal.head.bundle), TemporalRelation.Unclear)
  }

  test("a filler both branches license is mentioned once and participates in both") {
    val proposals = propose(Vector(s0.id -> landedAndWent))
    val they = ref(s0, "t")

    assertEquals(proposals.entityMentions.map(_.mention), Vector(ref(s0, "h"), they))
    assertEquals(
      proposals.participants.map(a => (a.situation, a.filler)).toSet,
      Set((ref(s0, "g"), ref(s0, "h")), (ref(s0, "g"), they), (ref(s0, "l"), they))
    )
    val mentionCalls =
      proposals.calls.filter(_.params.get("rule").contains(ChartProposalProvider.MentionRule))
    assertEquals(mentionCalls.map(_.params("filler")).sorted, Vector("h", "t"))

    val compiled = compile(Vector(s0.id -> landedAndWent))
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    assertEquals(
      model.graph.entities.values.map(_.label.value).toVector.sorted,
      Vector("home", "they")
    )
    assertEquals(model.graph.relations.participants.size, 3)
  }

  test("a coordinator whose branches are not roots admits none and names why for each") {
    val chart = checked(
      s0,
      "a",
      Map(
        "a" -> Concept.entity("and"),
        "f" -> Concept.entity("foggy"),
        "c" -> Concept.entity("calm")
      ),
      Vector(rel("a", op(1), "f"), rel("a", op(2), "c")),
      alignments = Vector(align(s0, "and", "a")),
      salt = "not-roots"
    )
    val proposals = propose(Vector(s0.id -> chart))

    assertEquals(
      branches(proposals.coverage.head),
      Vector(
        CoordinatedBranch.Abstained(
          ref(s0, "f"),
          SourceRole.Operand(1),
          AbstentionReason.BranchNotAdmissible(ConceptKind.Entity)
        ),
        CoordinatedBranch.Abstained(
          ref(s0, "c"),
          SourceRole.Operand(2),
          AbstentionReason.BranchNotAdmissible(ConceptKind.Entity)
        )
      )
    )
    assertEquals(proposals.counts, CoverageCounts(0, 1, 0, 0, 2))
    assertEquals(
      proposals.situations.map(_.bundle.proposals.head.disposition).toSet,
      Set(ProposalDisposition.Abstained)
    )
    assertEquals(proposals.situations.map(_.source).toSet, Set(ref(s0, "c"), ref(s0, "f")))
    assertEquals(compile(Vector(s0.id -> chart)).draft.graph.situations, Map.empty)
  }

  test("coordination is not descended: a coordinator under a coordinator abstains") {
    val chart = checked(
      s0,
      "a",
      Map(
        "a" -> Concept.entity("and"),
        "b" -> Concept.entity("and"),
        "l" -> Concept.predicate("land", frame("land-01")),
        "g" -> Concept.predicate("go", frame("go-02")),
        "r" -> Concept.predicate("rest", frame("rest-01"))
      ),
      Vector(
        rel("a", op(1), "l"),
        rel("a", op(2), "b"),
        rel("b", op(1), "g"),
        rel("b", op(2), "r")
      ),
      polarity = Map(
        "l" -> ChartPolarity.Positive,
        "g" -> ChartPolarity.Positive,
        "r" -> ChartPolarity.Positive
      ),
      alignments = Vector(align(s0, "and", "a"), align(s0, "landed", "l"), align(s0, "went", "g")),
      salt = "nested"
    )
    val proposals = propose(Vector(s0.id -> chart))

    assertEquals(
      branches(proposals.coverage.head),
      Vector(
        CoordinatedBranch.Admitted(ref(s0, "l"), SourceRole.Operand(1), FillerCounts.empty),
        CoordinatedBranch.Abstained(
          ref(s0, "b"),
          SourceRole.Operand(2),
          AbstentionReason.NestedCoordination
        )
      )
    )
    // The predicates under the inner coordinator are reached by no branch order this rule states,
    // so they are not roots and no situation claims them.
    assertEquals(proposals.situations.map(_.source).toSet, Set(ref(s0, "b"), ref(s0, "l")))
    assertEquals(compile(Vector(s0.id -> chart)).draft.graph.situations.size, 1)
  }

  test("a coordinator with no branch abstains at the coordinator itself") {
    val chart = checked(
      s0,
      "a",
      Map("a" -> Concept.entity("and"), "r" -> Concept.property("red")),
      Vector(rel("a", RoleAssignment.named("mod"), "r")),
      alignments = Vector(align(s0, "and", "a")),
      salt = "no-branch"
    )
    val proposals = propose(Vector(s0.id -> chart))

    assertEquals(
      proposals.coverage.head,
      SentenceCoverage.Abstained(ref(s0, "a"), AbstentionReason.NoCoordinationBranch)
    )
    assertEquals(proposals.counts, CoverageCounts(0, 0, 1, 0, 2))
  }

  test("multi-sentence coordinates by :snt index, and or coordinates like and") {
    val multi = checked(
      s0,
      "m",
      Map(
        "m" -> Concept(Lemma.unsafe("multi-sentence"), None, None, ConceptKind.Special),
        "l" -> Concept.predicate("land", frame("land-01")),
        "g" -> Concept.predicate("go", frame("go-02"))
      ),
      Vector(rel("m", snt(1), "l"), rel("m", snt(2), "g")),
      polarity = Map("l" -> ChartPolarity.Positive, "g" -> ChartPolarity.Positive),
      alignments = Vector(align(s0, "landed", "l"), align(s0, "went", "g")),
      salt = "multi"
    )
    assertEquals(
      branches(propose(Vector(s0.id -> multi)).coverage.head).map(b => (b, b.branchRole)),
      Vector(
        (
          CoordinatedBranch.Admitted(ref(s0, "l"), SourceRole.Named("snt1"), FillerCounts.empty),
          SourceRole.Named("snt1")
        ),
        (
          CoordinatedBranch.Admitted(ref(s0, "g"), SourceRole.Named("snt2"), FillerCounts.empty),
          SourceRole.Named("snt2")
        )
      )
    )

    val disjunction = checked(
      s0,
      "o",
      Map(
        "o" -> Concept.entity("or"),
        "l" -> Concept.predicate("land", frame("land-01")),
        "g" -> Concept.predicate("go", frame("go-02"))
      ),
      Vector(rel("o", op(1), "l"), rel("o", op(2), "g")),
      polarity = Map("l" -> ChartPolarity.Positive, "g" -> ChartPolarity.Positive),
      alignments = Vector(align(s0, "landed", "l"), align(s0, "went", "g")),
      salt = "or"
    )
    assertEquals(branches(propose(Vector(s0.id -> disjunction)).coverage.head).size, 2)
  }

  test("an embedded coordination branch is admitted and held, never asserted at the root") {
    val chart = checked(
      s0,
      "a",
      Map(
        "a" -> Concept.entity("and"),
        "s" -> Concept.predicate("say", frame("say-01")),
        "b" -> Concept.predicate("go", frame("go-02"))
      ),
      Vector(rel("a", op(1), "s"), rel("a", op(2), "b")),
      polarity = Map("s" -> ChartPolarity.Positive, "b" -> ChartPolarity.Positive),
      embedded = Vector(EmbeddedProposition(id("s"), EmbeddingKind.Speech, id("b"))),
      alignments = Vector(align(s0, "landed", "s"), align(s0, "went", "b")),
      salt = "embedded-branch"
    )

    assertEquals(
      branches(propose(Vector(s0.id -> chart)).coverage.head),
      Vector(
        CoordinatedBranch.Admitted(ref(s0, "s"), SourceRole.Operand(1), FillerCounts.empty),
        CoordinatedBranch.Admitted(ref(s0, "b"), SourceRole.Operand(2), FillerCounts.empty)
      )
    )

    val model = compile(Vector(s0.id -> chart)).draft
    val byLemma = model.graph.situations.values.map(s => s.predicate.lemma -> s).toMap
    val container = model.graph.contexts(byLemma("say").context)
    val held = model.graph.contexts(byLemma("go").context)
    assertEquals(container.kind, ContextKind.NarratedWorld)
    assertEquals(held.kind, ContextKind.Speech(ContextHolder.Unattributed(HolderGap.NoCandidate)))
    assertEquals(held.parent, Some(container.id))
  }

  /** `He was dead.`: `(d / dead :domain (h / he))`. */
  private def deadDomainHe: PropositionEvidence =
    checked(
      s1,
      "d",
      Map("d" -> Concept.property("dead"), "h" -> Concept.entity("he")),
      Vector(rel("d", RoleAssignment.named("domain"), "h")),
      alignments = Vector(align(s1, "dead", "d"), align(s1, "He", "h")),
      salt = "domain"
    )

  test("a property predicated of a :domain filler is a State whose participant is that filler") {
    val proposals = propose(Vector(s1.id -> deadDomainHe))
    val root = ref(s1, "d")

    assertEquals(
      proposals.coverage(1),
      SentenceCoverage.Proposed(root, FillerCounts(1, 0, 0, 0, 0, 0))
    )
    val situation = value(proposals.situations.head.bundle)
    assertEquals(situation.kind, SituationKind.State)
    assertEquals(situation.predicate, Predicate("dead", None, "dead"))
    assertEquals(situation.description, "dead (domain: he)")
    // The chart records no polarity for a non-predicate concept, and the provider copies the chart.
    assertEquals(situation.polarity, StoryPolarity.Unknown)
    assertEquals(
      proposals.participants.map(a => (a.filler, value(a.bundle))),
      Vector((ref(s1, "h"), ParticipantRole.Custom("amr", "domain")))
    )
    assertEquals(
      proposals.calls
        .find(_.params.get("rule").contains(ChartProposalProvider.SituationRule))
        .map(_.params("root-rule")),
      Some("predicative")
    )

    val compiled = compile(Vector(s1.id -> deadDomainHe))
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    assertEquals(
      model.graph.situations.values.collect { case SituationNode.State(node) =>
        node.predicate.lemma
      }.toVector,
      Vector("dead")
    )
    assertEquals(compiled.derivation.gaps, Vector.empty)
  }

  test("a :domain reaching no concept is not a predicative root") {
    val literalFiller = checked(
      s1,
      "d",
      Map("d" -> Concept.property("dead")),
      Vector(lit("d", RoleAssignment.named("domain"), "he")),
      alignments = Vector(align(s1, "dead", "d")),
      salt = "domain-literal"
    )
    val noDomain = checked(
      s1,
      "d",
      Map("d" -> Concept.property("dead")),
      alignments = Vector(align(s1, "dead", "d")),
      salt = "no-domain"
    )
    Vector(literalFiller, noDomain).foreach { chart =>
      assertEquals(
        propose(Vector(s1.id -> chart)).coverage(1),
        SentenceCoverage.Abstained(
          ref(s1, "d"),
          AbstentionReason.FocusNotPredicate(ConceptKind.Property)
        )
      )
    }
  }

  /** `There were people at Egulac.`: `(p / person :quant many :location (e / egulac))`. */
  private def peopleAtEgulac: PropositionEvidence =
    checked(
      s2,
      "p",
      Map("p" -> Concept.entity("person"), "e" -> Concept.entity("egulac")),
      Vector(
        lit("p", RoleAssignment.named("quant"), "many"),
        rel("p", RoleAssignment.named("location"), "e")
      ),
      alignments = Vector(align(s2, "people", "p"), align(s2, "Egulac", "e")),
      salt = "existential"
    )

  test("an entity placed by a :location filler is a State of existence at that place") {
    val proposals = propose(Vector(s2.id -> peopleAtEgulac))
    val root = ref(s2, "p")

    assertEquals(
      proposals.coverage(2),
      SentenceCoverage.Proposed(root, FillerCounts(1, 0, 0, 0, 0, 0))
    )
    val situation = value(proposals.situations.head.bundle)
    assertEquals(situation.kind, SituationKind.State)
    // No frame is invented: the chart carries none, so the predicate carries none.
    assertEquals(situation.predicate, Predicate("person", None, "person"))
    assertEquals(situation.description, "person (location: egulac) (quant: many)")
    assertEquals(
      proposals.participants.map(a => (a.filler, value(a.bundle))),
      Vector((ref(s2, "e"), ParticipantRole.Location))
    )
    assertEquals(
      proposals.calls
        .find(_.params.get("rule").contains(ChartProposalProvider.SituationRule))
        .map(_.params("root-rule")),
      Some("existential")
    )

    val compiled = compile(Vector(s2.id -> peopleAtEgulac))
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    assertEquals(
      model.graph.situations.values.collect { case SituationNode.State(node) =>
        node.predicate.lemma
      }.toVector,
      Vector("person")
    )
  }

  test("an entity with no :location concept is not an existential root") {
    val quantityOnly = checked(
      s2,
      "p",
      Map("p" -> Concept.entity("person")),
      Vector(lit("p", RoleAssignment.named("quant"), "many")),
      alignments = Vector(align(s2, "people", "p")),
      salt = "quant-only"
    )
    val literalPlace = checked(
      s2,
      "p",
      Map("p" -> Concept.entity("person")),
      Vector(lit("p", RoleAssignment.named("location"), "egulac")),
      alignments = Vector(align(s2, "people", "p")),
      salt = "literal-place"
    )
    Vector(quantityOnly, literalPlace).foreach { chart =>
      assertEquals(
        propose(Vector(s2.id -> chart)).coverage(2),
        SentenceCoverage.Abstained(
          ref(s2, "p"),
          AbstentionReason.FocusNotPredicate(ConceptKind.Entity)
        )
      )
    }
  }

  test("the coordination set is closed: a lexical entity outside it is not a coordinator") {
    val chart = checked(
      s0,
      "a",
      Map(
        "a" -> Concept.entity("group"),
        "l" -> Concept.predicate("land", frame("land-01"))
      ),
      Vector(rel("a", op(1), "l")),
      polarity = Map("l" -> ChartPolarity.Positive),
      alignments = Vector(align(s0, "and", "a")),
      salt = "not-coordinator"
    )

    assertEquals(
      propose(Vector(s0.id -> chart)).coverage.head,
      SentenceCoverage.Abstained(
        ref(s0, "a"),
        AbstentionReason.FocusNotPredicate(ConceptKind.Entity)
      )
    )
    assertEquals(ChartRoots.CoordinationLemmas, Set("and", "or", "multi-sentence"))
  }

  test("a coordinated sentence stays one sentence in the ledger and in the counts") {
    val proposals =
      propose(Vector(s0.id -> landedAndWent, s1.id -> deadDomainHe, s2.id -> peopleAtEgulac))

    assertEquals(proposals.coverage.size, atlas.sentences.size)
    assertEquals(proposals.counts, CoverageCounts(2, 1, 0, 0, 0))
    assertEquals(proposals.counts.sentences, atlas.sentences.size)
    assertEquals(proposals.situations.size, 4)
    assertEquals(proposals.coverage.head.admittedRoots, Vector(ref(s0, "l"), ref(s0, "g")))
    assertEquals(proposals.coverage(1).admittedRoots, Vector(ref(s1, "d")))
  }
