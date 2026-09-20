package storymodel4s.document

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.features.Estimate
import storymodel4s.proposition.{Polarity as ChartPolarity, *}
import storymodel4s.story.{Polarity as StoryPolarity, *}

/** Court for [[ChartProposalProvider]]: hand-built checked charts in, compiler input out. */
class ChartProposalProviderSuite extends FunSuite:
  private val title = StoryTitle.callerSupplied("Tiny story").fold(e => fail(e.message), identity)
  private val source = StorySource
    .titled("Anna entered the room. She did not rest. The lamp was on the table.", title)
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val sentences = atlas.sentences
  private val s0 = sentences(0)
  private val s1 = sentences(1)
  private val s2 = sentences(2)
  private val parser = Fingerprint.unsafe("test:chart-parser:1")
  private val parserStage = StageId.unsafe("test-chart-parser")

  private def chartCall(salt: String, input: Checksum = source.canonicalChecksum): ProviderCall =
    ProviderCall(
      "test-parser",
      "test-model",
      "1",
      None,
      input,
      Checksum.ofText(s"chart:$salt"),
      Map("salt" -> salt),
      None,
      cached = false
    )

  /** Every filler the referentiality rule turned away, as (concept, reason), in call order. */
  private def refusalReasons(proposals: ChartProposals): Vector[(String, String)] =
    proposals.calls
      .filter(_.params.get("rule").contains(Referentiality.RuleName))
      .map(call => call.params("filler") -> call.params("reason"))

  private def spanOf(unit: SurfaceUnit, word: String): SpanRef =
    val text = atlas.text(unit)
    val at = text.indexOf(word)
    assert(at >= 0, s"'$word' is not in '$text'")
    SpanRef(
      Some(unit.id),
      TextSpan.unsafe(unit.span.start + at, unit.span.start + at + word.length)
    )

  private def align(
      unit: SurfaceUnit,
      word: String,
      concept: ConceptId,
      credence: Double = 1.0,
      ref: Option[SpanRef] = None,
      unmeasured: Boolean = false
  ): PropositionAlignment =
    val spans = SpanSet.one(ref.getOrElse(spanOf(unit, word)))
    val evidence = Evidence(
      EvidenceId.unsafe(s"ev:align:${unit.id.value}:$word"),
      Some(spans),
      Set.empty,
      parser,
      parserStage
    )
    PropositionAlignment(
      AlignmentTarget.Concepts(NonEmptySet.one(concept)),
      spans,
      if unmeasured then Credence.unmeasured
      else Credence.unsafeRaw(credence, ScorerId.unsafe("test-scorer")),
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
      focus: Option[ConceptId],
      concepts: Map[ConceptId, Concept],
      relations: Vector[PropositionRelation] = Vector.empty,
      polarity: Map[ConceptId, ChartPolarity] = Map.empty,
      embedded: Vector[EmbeddedProposition] = Vector.empty,
      alignments: Vector[PropositionAlignment] = Vector.empty,
      salt: String = "",
      provenance: Option[ChartProvenance] = None
  ): PropositionEvidence =
    val unchecked = PropositionChart.unchecked(
      focus,
      concepts,
      relations,
      polarity,
      embedded,
      alignments,
      provenance.getOrElse(
        ChartProvenance(
          ChartOrigin.Parser(parser),
          Vector(chartCall(s"${unit.id.value}$salt")),
          Vector.empty
        )
      ),
      Some(unit.id)
    )
    PropositionEvidence.of(ChartValidator.check(unchecked).fold(v => fail(v.toString), identity))

  private val c0 = ConceptId.unsafe("c0")
  private val c1 = ConceptId.unsafe("c1")
  private val c2 = ConceptId.unsafe("c2")
  private def ref(unit: SurfaceUnit, concept: ConceptId): ChartNodeRef =
    ChartNodeRef(unit.id, concept)

  private val enterFrame = FrameRef("propbank", "enter-01", None)
  private val locatedFrame = FrameRef("propbank", "be-located-at-91", None)

  /** The adapter's classification of a `-91` roleset: kind Special, frame under `propbank`. */
  private def special(lemma: String, frame: Option[FrameRef]): Concept =
    Concept(Lemma.unsafe(lemma), None, frame, ConceptKind.Special)

  private val enterRelations = Vector(
    PropositionRelation(c0, RoleAssignment.arg(0), ConceptTarget.Node(c1)),
    PropositionRelation(c0, RoleAssignment.arg(1), ConceptTarget.Node(c2))
  )
  private def enterAlignments = Vector(
    align(s0, "entered", c0),
    align(s0, "Anna", c1),
    align(s0, "room", c2)
  )
  private def enterChart(
      relations: Vector[PropositionRelation] = enterRelations,
      alignments: Vector[PropositionAlignment] = enterAlignments
  ): PropositionEvidence =
    checked(
      s0,
      Some(c0),
      Map(
        c0 -> Concept.predicate("enter", Some(enterFrame)),
        c1 -> Concept.name("Anna"),
        c2 -> Concept.entity("room")
      ),
      relations,
      polarity = Map(c0 -> ChartPolarity.Positive),
      alignments = alignments
    )

  private def restChart(alignments: Vector[PropositionAlignment] = Vector(align(s1, "rest", c0))) =
    checked(
      s1,
      Some(c0),
      Map(c0 -> Concept.predicate("rest")),
      polarity = Map(c0 -> ChartPolarity.Negative),
      alignments = alignments
    )

  private val lampChart = checked(
    s2,
    Some(c0),
    Map(
      c0 -> special("be-located-at", Some(locatedFrame)),
      c1 -> Concept.entity("lamp"),
      c2 -> Concept.entity("table")
    ),
    Vector(
      PropositionRelation(c0, RoleAssignment.arg(1), ConceptTarget.Node(c1)),
      PropositionRelation(c0, RoleAssignment.arg(2), ConceptTarget.Node(c2))
    ),
    alignments = Vector(align(s2, "lamp", c1), align(s2, "table", c2))
  )

  private def propose(charts: Vector[(SurfaceUnitId, PropositionEvidence)]): ChartProposals =
    ChartProposalProvider.propose(source, atlas, charts).fold(e => fail(e.message), identity)

  private def compile(
      charts: Vector[(SurfaceUnitId, PropositionEvidence)],
      src: StorySource = source,
      atl: SurfaceAtlas = atlas
  ): NarrativeCompilation =
    val input = ChartProposalProvider
      .input(src, atl, charts, None, 0L)
      .fold(e => fail(e.message), identity)
    NarrativeCompiler.compile(input).fold(e => fail(e.message), identity)

  private def dispositions(bundle: EvidenceBundle[?]): Vector[ProposalDisposition] =
    bundle.proposals.map(_.disposition)

  private def spanSourceOf(call: ProviderCall): String =
    call.params.getOrElse("span-source", fail(s"no span-source on ${call.params}"))

  test("a predicate focus yields one situation, context, and membership attempt at the root") {
    val proposals = propose(Vector(s0.id -> enterChart()))
    val root = ref(s0, c0)

    assertEquals(proposals.situations.map(_.source), Vector(root))
    assertEquals(proposals.contexts.map(_.source), Vector(root))
    assertEquals(proposals.memberships.map(_.member), Vector(root))
    assertEquals(proposals.causal, Vector.empty)
    assertEquals(proposals.participantCoverage.map(_.situation), Vector(root))
    assertEquals(
      proposals.participantCoverage.head.bundle.proposals.head.value,
      Some(ParticipantCoverage.empty)
    )
    assertEquals(proposals.participants, Vector.empty)
    assertEquals(proposals.entityMentions, Vector.empty)
    assertEquals(proposals.temporal, Vector.empty)
    val situation = proposals.situations.head.bundle
    assertEquals(dispositions(situation), Vector(ProposalDisposition.Proposed))
    assertEquals(
      situation.proposals.head.value,
      Some(
        SituationProposal(
          SituationKind.Event,
          Predicate("enter", Some("propbank:enter-01"), "enter"),
          "enter Anna room",
          StoryPolarity.Positive,
          Modality.Asserted,
          None
        )
      )
    )
    assertEquals(
      situation.bases.map(_.basis),
      Vector(AcceptanceBasis.Determined(RuleId.unsafe("chart-rule-v1")))
    )
    assertEquals(
      proposals.coverage,
      Vector(
        SentenceCoverage.Proposed(root, FillerCounts(0, 0, 0, 0, 2, 0)),
        SentenceCoverage.NoChart(s1.id),
        SentenceCoverage.NoChart(s2.id)
      )
    )
    assertEquals(proposals.counts, CoverageCounts(1, 0, 0, 0, 2))
    assertEquals(
      proposals.summaryCoverage,
      SummaryCoverage.Proposed("Tiny story", TitleProvenance.CallerSupplied)
    )
  }

  test("situation support is the union of chart alignment spans, never the sentence text") {
    val proposals = propose(Vector(s0.id -> enterChart()))
    val situationEvidence = proposals.situations.head.bundle.proposals.head.evidence

    assertEquals(situationEvidence.map(_.evidenceId).toSet.size, 1)
    val record = proposals.evidence
      .find(_.id == situationEvidence.head.evidenceId)
      .getOrElse(fail("no record"))
    assertEquals(proposals.evidence.map(_.id).toSet, Set(record.id, summaryEvidenceId))
    assertEquals(
      record.spans,
      SpanSet.of(Vector(spanOf(s0, "Anna"), spanOf(s0, "entered"), spanOf(s0, "room")))
    )
    // Six calls, not four: since slice 1.7 each of this chart's two fillers - both reached by a
    // numbered argument no lexicon licensed - carries its own refusal receipt, which the four
    // proposal calls did not previously record anywhere. A refusal cites no spans of its own, so
    // only the proposal calls carry a span-source.
    val sentenceCalls = proposals.calls.filter(_.params.get("sentence").contains(s0.id.value))
    assertEquals(sentenceCalls.size, 6)
    val situationCalls = sentenceCalls.filterNot(_.params.contains("reason"))
    assertEquals(situationCalls.size, 4)
    assertEquals(situationCalls.map(spanSourceOf).toSet, Set("chart-alignments"))
    assertEquals(refusalReasons(proposals).map(_._2).toSet, Set("no-licensed-role"))
  }

  test("a chart without alignments falls back to the sentence span and says so") {
    val proposals = propose(Vector(s1.id -> restChart(alignments = Vector.empty)))
    val record = proposals.evidence.filterNot(_.id == summaryEvidenceId) match
      case Vector(one) => one
      case other       => fail(s"expected one sentence evidence record, found $other")

    assertEquals(record.spans, Some(SpanSet.one(SpanRef(Some(s1.id), s1.span))))
    val calls = proposals.calls.filter(_.params.get("sentence").contains(s1.id.value))
    assertEquals(calls.map(spanSourceOf).toSet, Set("sentence"))
  }

  test("chart polarity maps one-to-one: negative stays negative, unrecorded stays unknown") {
    val proposals = propose(Vector(s1.id -> restChart(), s2.id -> lampChart))
    val byRef = proposals.situations
      .map(a => a.source -> a.bundle.proposals.head.value.getOrElse(fail("no value")))
      .toMap
    val negative = byRef(ref(s1, c0))

    assertEquals(negative.polarity, StoryPolarity.Negative)
    assertEquals(negative.description, "not rest")
    assertEquals(negative.predicate, Predicate("rest", None, "rest"))
    assertEquals(lampChart.chart.polarityOf(c0), ChartPolarity.Unknown)
    assertEquals(byRef(ref(s2, c0)).polarity, StoryPolarity.Unknown)
  }

  test("a Special focus with a propbank -91 frame is a State root; other Special foci abstain") {
    val proposals = propose(Vector(s0.id -> enterChart(), s2.id -> lampChart))
    val byRef = proposals.situations
      .map(a => a.source -> a.bundle.proposals.head.value.getOrElse(fail("no value")))
      .toMap

    assertEquals(lampChart.chart.concept(c0).map(_.kind), Some(ConceptKind.Special))
    assertEquals(byRef(ref(s2, c0)).kind, SituationKind.State)
    assertEquals(byRef(ref(s2, c0)).predicate.frame, Some("propbank:be-located-at-91"))
    assertEquals(byRef(ref(s2, c0)).description, "be-located-at lamp table")
    assertEquals(byRef(ref(s0, c0)).kind, SituationKind.Event)
    assertEquals(
      proposals.coverage(2),
      SentenceCoverage.Proposed(ref(s2, c0), FillerCounts(0, 0, 0, 0, 2, 0))
    )

    val compiled = compile(Vector(s2.id -> lampChart))
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    val stateNodes = model.graph.situations.values.collect { case SituationNode.State(node) =>
      node
    }
    assertEquals(stateNodes.map(_.predicate.lemma).toVector, Vector("be-located-at"))
    assertEquals(model.graph.situations.size, 1)

    def focusOnly(concept: Concept, salt: String): PropositionEvidence =
      checked(s2, Some(c0), Map(c0 -> concept), salt = salt)
    val predicateWithFrame = focusOnly(Concept.predicate("be-located-at", Some(locatedFrame)), "pf")
    assertEquals(
      propose(Vector(s2.id -> predicateWithFrame)).situations.head.bundle.proposals.head.value
        .map(_.kind),
      Some(SituationKind.State)
    )
    val foreignNamespace =
      focusOnly(
        Concept.predicate("be-located-at", Some(FrameRef("custom", "be-located-at-91", None))),
        "fn"
      )
    assertEquals(
      propose(Vector(s2.id -> foreignNamespace)).situations.head.bundle.proposals.head.value
        .map(_.kind),
      Some(SituationKind.Event)
    )
    val frameless = focusOnly(special("date-entity", None), "frameless")
    assertEquals(
      propose(Vector(s2.id -> frameless)).coverage(2),
      SentenceCoverage.Abstained(
        ref(s2, c0),
        AbstentionReason.FocusNotPredicate(ConceptKind.Special)
      )
    )
    val outsideSet =
      focusOnly(
        special("be-located-at", Some(FrameRef("propbank", "be-located-at-92", None))),
        "os"
      )
    assertEquals(
      propose(Vector(s2.id -> outsideSet)).coverage(2),
      SentenceCoverage.Abstained(
        ref(s2, c0),
        AbstentionReason.FocusNotPredicate(ConceptKind.Special)
      )
    )
    val orgRole = focusOnly(
      special("have-org-role", Some(FrameRef("propbank", "have-org-role-91", None))),
      "org"
    )
    assertEquals(
      propose(Vector(s2.id -> orgRole)).situations.head.bundle.proposals.head.value.map(_.kind),
      Some(SituationKind.State)
    )
  }
  test("an embedded focus is placed under its holder, not abstained and not asserted at root") {
    val embeddedFocus = checked(
      s0,
      Some(c1),
      Map(c0 -> Concept.predicate("think"), c1 -> Concept.predicate("enter")),
      embedded = Vector(EmbeddedProposition(c0, EmbeddingKind.Belief, c1)),
      polarity = Map(c1 -> ChartPolarity.Positive),
      alignments = Vector(align(s0, "Anna", c0), align(s0, "entered", c1)),
      salt = "embedded-focus"
    )
    val root = ref(s0, c1)
    val proposals = propose(Vector(s0.id -> embeddedFocus))
    assertEquals(proposals.coverage.head.sentence, s0.id)
    assertEquals(proposals.contexts.map(_.source), Vector(root))
    assertEquals(
      dispositions(proposals.contexts.head.bundle),
      Vector(ProposalDisposition.Proposed)
    )

    val model = compile(Vector(s0.id -> embeddedFocus)).draft
    val situation = model.graph.situations.values.head
    val frame = model.graph.contexts(situation.context)
    assertNotEquals(frame.kind, ContextKind.NarratedWorld: ContextKind)
    assertEquals(frame.kind, ContextKind.Belief(ContextHolder.Unattributed(HolderGap.NoCandidate)))
    assertEquals(frame.parent.map(model.graph.contexts(_).kind), Some(ContextKind.NarratedWorld))
    assertEquals(model.graph.contexts.size, 2)
  }

  test("an inadmissible root is absent and recorded") {
    val noFocus = checked(
      s0,
      None,
      Map(c0 -> Concept.predicate("enter"), c1 -> Concept.name("Anna")),
      salt = "no-focus"
    )
    val entityFocus = checked(
      s0,
      Some(c1),
      Map(c0 -> Concept.predicate("enter"), c1 -> Concept.name("Anna")),
      salt = "entity-focus"
    )
    val cases = Vector(
      (noFocus, ref(s0, c0), AbstentionReason.NoFocus),
      (entityFocus, ref(s0, c1), AbstentionReason.FocusNotPredicate(ConceptKind.Name))
    )
    cases.foreach { (chart, anchor, reason) =>
      val proposals = propose(Vector(s0.id -> chart))
      assertEquals(proposals.coverage.head, SentenceCoverage.Abstained(anchor, reason))
      assertEquals(proposals.counts, CoverageCounts(0, 0, 1, 0, 2))
      assertEquals(proposals.situations.map(_.source), Vector(anchor))
      assertEquals(proposals.contexts.map(_.source), Vector(anchor))
      assertEquals(proposals.memberships.map(_.member), Vector(anchor))
      assertEquals(
        dispositions(proposals.situations.head.bundle),
        Vector(ProposalDisposition.Abstained)
      )
      assertEquals(
        dispositions(proposals.contexts.head.bundle),
        Vector(ProposalDisposition.Abstained)
      )
      assertEquals(
        dispositions(proposals.memberships.head.bundle),
        Vector(ProposalDisposition.Abstained)
      )
      assertEquals(proposals.situations.head.bundle.bases, Vector.empty)
      assertEquals(proposals.participantCoverage.map(_.situation), Vector(anchor))
      assertEquals(
        dispositions(proposals.participantCoverage.head.bundle),
        Vector(ProposalDisposition.Abstained)
      )
      assertEquals(proposals.evidence.map(_.id), Vector(summaryEvidenceId))

      val compiled = compile(Vector(s0.id -> chart))
      assertEquals(compiled.draft.graph.situations, Map.empty)
      val noProposal = DerivationGapReason.Unresolved(ResolutionFailure.NoProposal)
      val gapsByFamily = compiled.derivation.gaps.map(g => g.family -> g.reason).toMap
      assertEquals(gapsByFamily(ClaimFamily.SituationMention), noProposal)
      assertEquals(gapsByFamily(ClaimFamily.ContextAssignment), noProposal)
      assertEquals(gapsByFamily(ClaimFamily.SegmentMembership), noProposal)
      assertEquals(gapsByFamily(ClaimFamily.ParticipantCoverage), noProposal)
      assertEquals(compiled.derivation.gaps.size, 5)
      assertEquals(compiled.validated, None)
    }
  }

  private val summaryEvidenceId: EvidenceId =
    EvidenceId.unsafe(
      ContentAddress.of(
        "chart-proposal-evidence/v2",
        "story",
        source.canonicalChecksum.hex,
        s"-:0:${source.canonicalText.length}"
      )
    )

  test("an empty chart and a missing chart are ledger rows, and the input still compiles") {
    val empty = checked(s0, None, Map.empty, salt = "empty")
    val charts = Vector(s0.id -> empty, s1.id -> restChart())
    val proposals = propose(charts)

    assertEquals(
      proposals.coverage,
      Vector(
        SentenceCoverage.EmptyChart(s0.id),
        SentenceCoverage.Proposed(ref(s1, c0), FillerCounts(0, 0, 0, 0, 0, 0)),
        SentenceCoverage.NoChart(s2.id)
      )
    )
    assertEquals(proposals.counts, CoverageCounts(1, 0, 0, 1, 1))
    assertEquals(proposals.situations.size, 1)

    val compiled = compile(charts)
    assertEquals(compiled.derivation.gaps, Vector.empty)
    assertEquals(compiled.draft.graph.situations.size, 1)
    assert(compiled.validated.isDefined, compiled.validation.report.render)
  }

  test("the summary support spans the whole canonical text") {
    val charts = Vector(s0.id -> enterChart(), s1.id -> restChart(), s2.id -> lampChart)
    val proposals = propose(charts)
    val summaryEvidence = proposals.summary.bundle.proposals.head.evidence.head match
      case EvidenceRef.Inline(ev) => ev
      case other                  => fail(s"summary evidence is not inline: $other")

    assertEquals(
      summaryEvidence.spans,
      Some(SpanSet.one(SpanRef(None, TextSpan.unsafe(0, source.canonicalText.length))))
    )
    assertEquals(
      proposals.summary.bundle.proposals.head.value,
      Some(StorySummaryProposal("Tiny story"))
    )

    val compiled = compile(charts)
    assertEquals(compiled.draft.graph.situations.size, 3)
    assertEquals(compiled.draft.hierarchy.primary.size, 3)
    assert(!compiled.validation.report.byLaw.contains("hierarchy.member-within-parent"))
    assertEquals(compiled.derivation.gaps, Vector.empty)
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    assertEquals(
      model.trajectory.steps.map(_.entityTurnover),
      Vector(Estimate.observed(0.0), Estimate.observed(0.0))
    )
    assertEquals(
      model.trajectory.steps.map(_.worldTime.value).toSet,
      Set(WorldTimeTransition.Unresolved(Vector.empty))
    )
    assertEquals(model.graph.entities, Map.empty)
  }

  private val licensedAgent = RoleAssignment(
    SourceRole.Numbered(0),
    Some((ParticipantRole.Agent, Credence.unsafeRaw(0.5, ScorerId.unsafe("test-scorer"))))
  )

  test("a licensed numbered role yields a participant, a mention, and a coverage naming it") {
    val licensed = enterChart(relations =
      Vector(
        PropositionRelation(c0, licensedAgent, ConceptTarget.Node(c1)),
        PropositionRelation(c0, RoleAssignment.arg(1), ConceptTarget.Node(c2))
      )
    )
    val proposals = propose(Vector(s0.id -> licensed))
    val root = ref(s0, c0)
    val anna = ref(s0, c1)

    assertEquals(
      proposals.coverage.head,
      SentenceCoverage.Proposed(root, FillerCounts(1, 0, 0, 0, 1, 0))
    )
    assertEquals(proposals.participants.map(a => (a.situation, a.filler)), Vector((root, anna)))
    assertEquals(
      proposals.participants.head.bundle.proposals.head.value,
      Some(ParticipantRole.Agent)
    )
    assertEquals(proposals.entityMentions.map(_.mention), Vector(anna))
    assertEquals(
      proposals.entityMentions.head.bundle.proposals.head.value,
      Some(EntityMentionProposal("Anna", EntityType.Custom("chart", "name")))
    )
    assertEquals(
      proposals.participantCoverage.head.bundle.proposals.head.value,
      Some(ParticipantCoverage.of(Vector(anna)))
    )
    val rootSpans =
      SpanSet.of(Vector(spanOf(s0, "Anna"), spanOf(s0, "entered"), spanOf(s0, "room")))
    def inlineEvidence(bundle: EvidenceBundle[?]): Evidence =
      bundle.proposals.head.evidence.head match
        case EvidenceRef.Inline(ev) => ev
        case other                  => fail(s"not inline: $other")
    assertEquals(
      inlineEvidence(proposals.entityMentions.head.bundle).spans,
      Some(SpanSet.one(spanOf(s0, "Anna")))
    )
    assertEquals(inlineEvidence(proposals.participants.head.bundle).spans, rootSpans)
    val mentionCall =
      proposals.calls.find(_.params.get("rule").contains("entity-filler-mention-rule"))
    assertEquals(mentionCall.map(spanSourceOf), Some("filler-alignments"))
    assertEquals(mentionCall.flatMap(_.params.get("filler")), Some("c1"))

    val compiled = compile(Vector(s0.id -> licensed))
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    assertEquals(model.graph.entities.size, 1)
    assertEquals(model.graph.entities.values.head.label.value, "Anna")
    assertEquals(model.graph.relations.participants.map(_.role), Vector(ParticipantRole.Agent))
    assertEquals(compiled.derivation.gaps, Vector.empty)
  }

  test(
    "a named time role becomes a circumstance and a named location role a participant"
  ) {
    val chart = checked(
      s0,
      Some(c0),
      Map(
        c0 -> Concept.predicate("enter", Some(enterFrame)),
        c1 -> Concept.entity("night"),
        c2 -> Concept.entity("room")
      ),
      Vector(
        PropositionRelation(c0, RoleAssignment.named("time"), ConceptTarget.Node(c1)),
        PropositionRelation(c0, RoleAssignment.named("location"), ConceptTarget.Node(c2))
      ),
      alignments = Vector(align(s0, "entered", c0), align(s0, "room", c2)),
      salt = "named-roles"
    )
    val proposals = propose(Vector(s0.id -> chart))
    val byFiller = proposals.participants
      .map(a => a.filler -> a.bundle.proposals.head.value.getOrElse(fail("no role")))
      .toMap

    assertEquals(
      proposals.coverage.head,
      SentenceCoverage.Proposed(ref(s0, c0), FillerCounts(1, 1, 0, 0, 0, 0))
    )
    assertEquals(byFiller.keySet, Set(ref(s0, c2)))
    assertEquals(byFiller(ref(s0, c2)), ParticipantRole.Location)
    assertEquals(proposals.entityMentions.map(_.mention), Vector(ref(s0, c2)))
    assertEquals(
      proposals.circumstances.map(a => (a.situation, a.filler)),
      Vector((ref(s0, c0), ref(s0, c1)))
    )
    assertEquals(
      proposals.circumstances.head.bundle.proposals.head.value,
      Some(CircumstanceProposal(CircumstanceKind.Time, "night"))
    )
    val mentionSources = proposals.calls
      .filter(_.params.get("rule").contains("entity-filler-mention-rule"))
      .map(c => c.params("filler") -> spanSourceOf(c))
      .toMap
    assertEquals(mentionSources, Map("c2" -> "filler-alignments"))
    val circumstanceSources = proposals.calls
      .filter(_.params.get("rule").contains("situation-circumstance-rule"))
      .map(c => c.params("filler") -> spanSourceOf(c))
      .toMap
    assertEquals(circumstanceSources, Map("c1" -> "root-support"))
  }

  test("a quantity under a referential role is not a referent and mints no entity") {
    val chart = checked(
      s0,
      Some(c0),
      Map(
        c0 -> Concept.predicate("enter", Some(enterFrame)),
        c1 -> Concept(Lemma.unsafe("5"), None, None, ConceptKind.Quantity)
      ),
      Vector(PropositionRelation(c0, licensedAgent, ConceptTarget.Node(c1))),
      alignments = Vector(align(s0, "entered", c0), align(s0, "room", c1)),
      salt = "quantity-filler"
    )
    val proposals = propose(Vector(s0.id -> chart))

    // The role takes a referent; the concept cannot be one. Both coordinates have to hold, and the
    // receipt says which of them refused.
    assertEquals(
      proposals.coverage.head,
      SentenceCoverage.Proposed(ref(s0, c0), FillerCounts(0, 0, 0, 1, 0, 0))
    )
    assertEquals(proposals.participants, Vector.empty)
    assertEquals(proposals.entityMentions, Vector.empty)
    assertEquals(proposals.circumstances, Vector.empty)
    val refusals = proposals.calls
      .filter(_.params.get("rule").contains(Referentiality.RuleName))
      .map(call => call.params("filler") -> call.params("reason"))
    assertEquals(refusals, Vector("c1" -> "concept-kind-not-referential:Quantity"))
  }

  test("a filler reached by two different licensed roles is unlicensed, not a guess") {
    val chart = checked(
      s0,
      Some(c0),
      Map(c0 -> Concept.predicate("enter", Some(enterFrame)), c1 -> Concept.name("Anna")),
      Vector(
        PropositionRelation(c0, licensedAgent, ConceptTarget.Node(c1)),
        PropositionRelation(c0, RoleAssignment.named("location"), ConceptTarget.Node(c1))
      ),
      alignments = Vector(align(s0, "entered", c0), align(s0, "Anna", c1)),
      salt = "two-roles"
    )
    val proposals = propose(Vector(s0.id -> chart))

    // Ambiguous, not unlicensed: the chart named two licensed roles for one filler, which is a
    // different finding from a filler no role reached, and the two counters keep them apart.
    assertEquals(
      proposals.coverage.head,
      SentenceCoverage.Proposed(ref(s0, c0), FillerCounts(0, 0, 0, 0, 0, 1))
    )
    assertEquals(proposals.participants, Vector.empty)
    assertEquals(proposals.entityMentions, Vector.empty)
    assertEquals(refusalReasons(proposals), Vector("c1" -> "several-licensed-roles"))
  }

  test("every filler the rule turns away carries a receipt naming its reason") {
    // The audit of 2026-09-02 found 51 of this story's 119 argument fillers leaving the provider
    // as an anonymous increment, in no layer, no gap and no alternatives list. A filler no role
    // reached is now receipted with its concept, its word, its kind, the source roles that reached
    // it, and the reason, so the frame-lexicon slice can find its own work.
    val chart = checked(
      s0,
      Some(c0),
      Map(c0 -> Concept.predicate("enter", Some(enterFrame)), c1 -> Concept.name("Anna")),
      Vector(PropositionRelation(c0, RoleAssignment.arg(3), ConceptTarget.Node(c1))),
      alignments = Vector(align(s0, "entered", c0), align(s0, "Anna", c1)),
      salt = "unlicensed-arg"
    )
    val proposals = propose(Vector(s0.id -> chart))

    assertEquals(
      proposals.coverage.head,
      SentenceCoverage.Proposed(ref(s0, c0), FillerCounts(0, 0, 0, 0, 1, 0))
    )
    val call = proposals.calls
      .find(_.params.get("rule").contains(Referentiality.RuleName))
      .getOrElse(fail("the refused filler carries no receipt"))
    assertEquals(call.params("filler"), "c1")
    assertEquals(call.params("lemma"), "Anna")
    assertEquals(call.params("concept-kind"), "Name")
    assertEquals(call.params("source-roles"), "ARG3")
    assertEquals(call.params("reason"), "no-licensed-role")
  }

  test("a :cause filler is neither a participant nor a circumstance; it is the causal layer's") {
    val chart = checked(
      s0,
      Some(c0),
      Map(c0 -> Concept.predicate("enter", Some(enterFrame)), c1 -> Concept.entity("fog")),
      Vector(PropositionRelation(c0, RoleAssignment.named("cause"), ConceptTarget.Node(c1))),
      alignments = Vector(align(s0, "entered", c0), align(s0, "room", c1)),
      salt = "cause-filler"
    )
    val proposals = propose(Vector(s0.id -> chart))

    // Counted under its own name, not lumped with a time or a manner: `:cause` relates one
    // situation to another, and what it describes belongs to the causal layer this version does
    // not build. The receipt names the role so that slice can find every one of them.
    assertEquals(
      proposals.coverage.head,
      SentenceCoverage.Proposed(ref(s0, c0), FillerCounts(0, 0, 1, 0, 0, 0))
    )
    assertEquals(proposals.participants, Vector.empty)
    assertEquals(proposals.entityMentions, Vector.empty)
    assertEquals(proposals.circumstances, Vector.empty)
    assertEquals(refusalReasons(proposals), Vector("c1" -> "role-takes-situation:Cause"))
  }

  test("Unclear temporal attempts follow atlas order and skip sentences without a proposed root") {
    val entityFocus = checked(
      s1,
      Some(c1),
      Map(c0 -> Concept.predicate("rest"), c1 -> Concept.name("She")),
      salt = "s1-entity-focus"
    )
    val skipping = propose(Vector(s0.id -> enterChart(), s1.id -> entityFocus, s2.id -> lampChart))
    assertEquals(
      skipping.temporal.map(a => (a.from, a.to)),
      Vector((ref(s0, c0), ref(s2, c0)))
    )
    assertEquals(
      skipping.temporal.head.bundle.proposals.head.value,
      Some(TemporalRelation.Unclear)
    )

    val full = propose(Vector(s2.id -> lampChart, s0.id -> enterChart(), s1.id -> restChart()))
    assertEquals(
      full.temporal.map(a => (a.from, a.to)),
      Vector((ref(s0, c0), ref(s1, c0)), (ref(s1, c0), ref(s2, c0)))
    )
    val pairEvidence = full.temporal.head.bundle.proposals.head.evidence.head match
      case EvidenceRef.Inline(ev) => ev
      case other                  => fail(s"not inline: $other")
    assertEquals(
      pairEvidence.spans,
      SpanSet.of(
        Vector(spanOf(s0, "Anna"), spanOf(s0, "entered"), spanOf(s0, "room"), spanOf(s1, "rest"))
      )
    )
    assert(full.temporal.forall(_.bundle.proposals.head.value.contains(TemporalRelation.Unclear)))
  }

  test("the rules text is pinned by its checksum, so a rule change is a visible change") {
    assertEquals(
      ChartProposalProvider.Prompt.checksum.hex,
      "2b01824390025610be3ccd4efb2ef77332f2d53cfdb719ac398e645f7254f7a9"
    )
    val rules = ChartProposalProvider.RulesText
    assert(rules.contains("Never Before or Meets"))
    assert(rules.contains("time=Time"))
    // Slice 1.5's three admissions are stated here, so deleting one moves the checksum above and
    // the prompt-package checksum and provenance config hash with it.
    assert(rules.contains("{and, multi-sentence, or}"), "the coordination set is not stated")
    assert(rules.contains(":domain (h / he)"), "the predicative rule is not stated")
    assert(rules.contains(":location (e / egulac)"), "the existential rule is not stated")
    // D0's context rule is stated here too, so deleting either half of the positive root-world
    // reading, or the one-sentence speaker lookback, moves the checksum above.
    assert(rules.contains("A root is in the NARRATED WORLD exactly when both"), "root rule absent")
    assert(rules.contains(QuotationScan.RuleName), "the quotation rule is not named")
    assert(rules.contains("Attribution is a separate question"), "attribution is not stated")
    assert(rules.contains("span-source=branch-alignments"), "branch support is not stated")
    assert(rules.contains("domain=Custom(amr,domain)"), "the domain role is not in the table")
    // Slice 1.7's referentiality rule is stated here for the same reason: deleting a clause moves
    // the checksum, and with it the prompt-package checksum and the provenance config hash.
    assert(rules.contains("role-referentiality-rule"), "the referentiality rule is not named")
    assert(
      rules.contains(
        "Agent, Beneficiary, Destination, Experiencer, Instrument, Location, Patient, Source, " +
          "Stimulus, Theme"
      ),
      "the referential role set is not stated"
    )
    assert(rules.contains("Time and Manner name circumstances"), "circumstances are not stated")
    assert(
      rules.contains("concept kinds that denote a referent are Entity and Name"),
      "the referential concept kinds are not stated"
    )
    assert(
      rules.contains("Cause and Result relate one"),
      "the causal-layer exclusion is not stated"
    )
    assert(
      rules.contains(
        "referents, circumstances, eventualities, nonReferential, unlicensed and\n  ambiguous"
      ),
      "the six coverage classes are not stated"
    )
  }

  test("chart order and alignment order do not change the proposals or the fingerprint") {
    val ordinary = Vector(s0.id -> enterChart(), s1.id -> restChart(), s2.id -> lampChart)
    val permuted = Vector(
      s2.id -> lampChart,
      s1.id -> restChart(),
      s0.id -> enterChart(enterRelations.reverse, enterAlignments.reverse)
    )

    assertEquals(propose(permuted), propose(ordinary))
    assertEquals(compile(permuted).fingerprint, compile(ordinary).fingerprint)
    assertEquals(
      propose(permuted).situations.head.bundle.proposals.head.taskId,
      propose(ordinary).situations.head.bundle.proposals.head.taskId
    )
  }

  test("scores that compare equal have one identity: negative zero digests as zero") {
    val positiveZero =
      Vector(s0.id -> enterChart(alignments = Vector(align(s0, "entered", c0, 0.0))))
    val negativeZero =
      Vector(s0.id -> enterChart(alignments = Vector(align(s0, "entered", c0, -0.0))))
    assert(0.0 == -0.0, "the two scores must compare equal for this court to mean anything")
    assertNotEquals(
      java.lang.Double.doubleToLongBits(0.0),
      java.lang.Double.doubleToLongBits(-0.0),
      "and their bit patterns must differ, or the fold is untested"
    )
    assertEquals(
      ChartProposalProvider.chartsDigest(negativeZero),
      ChartProposalProvider.chartsDigest(positiveZero)
    )
  }

  test("receipts bind the source checksum, the rules checksum, and the chart receipts") {
    val charts = Vector(s0.id -> enterChart(), s1.id -> restChart())
    val proposals = propose(charts)
    val input = ChartProposalProvider
      .input(source, atlas, charts, Some(parserStage), 7L)
      .fold(e => fail(e.message), identity)

    assertEquals(input.provenance.configHash, ChartProposalProvider.Prompt.checksum)
    assertEquals(input.provenance.softwareVersion, StoryModel.SchemaVersion)
    assert(proposals.calls.forall(_.inputChecksum == source.canonicalChecksum))
    assert(proposals.calls.forall(_.provider == "chart-proposal-provider"))
    // Twelve, not ten: the two charts' four unlicensed fillers each carry a refusal receipt now.
    assertEquals(proposals.calls.size, 12)
    assertEquals(input.receipt.stages.map(_._1), Vector(parserStage, ChartProposalProvider.Stage))
    assertEquals(
      input.receipt.stages.map(_._2.hex),
      Vector(
        "ef43b46db1be4f4ad1c4b53c61361354036c4713ce372b86ef561cd016f9fc9d",
        "9855eaf8e4dc1cb80351f31da1c6c04dd03733dc99185b39b9d1a7bf0d4734d6"
      )
    )
    assertEquals(input.receipt.createdAtEpochMillis, 7L)
    assertEquals(
      input.provenance.calls.toSet,
      (proposals.calls ++ charts.flatMap(_._2.provenance.receipts)).toSet
    )
    proposals.situations.foreach { attempt =>
      attempt.bundle.proposals.foreach { proposal =>
        assertEquals(proposal.receipt.promptPackage, ChartProposalProvider.Prompt)
        assertEquals(proposal.receipt.taskId, proposal.taskId)
      }
    }
  }

  test(
    "two charts differing only in alignment spans have different evidence, receipts, and digests"
  ) {
    val full = enterChart()
    val fewer = enterChart(alignments = Vector(align(s0, "entered", c0), align(s0, "Anna", c1)))
    assertEquals(Canonical.checksum(full.chart), Canonical.checksum(fewer.chart))

    val a = propose(Vector(s0.id -> full))
    val b = propose(Vector(s0.id -> fewer))
    def situationEvidence(p: ChartProposals): EvidenceId =
      p.situations.head.bundle.proposals.head.evidence.head.evidenceId
    assertNotEquals(situationEvidence(a), situationEvidence(b))
    val situationCall = (p: ChartProposals) =>
      p.calls.find(_.params.get("rule").contains(ChartProposalProvider.SituationRule)).get
    assertNotEquals(situationCall(a).outputChecksum, situationCall(b).outputChecksum)
    assert(
      situationCall(a).outputChecksum == ContentAddress.digest(
        ChartProposalProvider.SituationRule +: Vector(
          "situation",
          "Event",
          "enter",
          "propbank:enter-01",
          "enter",
          "enter Anna room",
          "Positive",
          situationEvidence(a).value
        )
      )
    )

    def stages(ev: PropositionEvidence): Vector[Checksum] =
      ChartProposalProvider
        .input(source, atlas, Vector(s0.id -> ev), Some(parserStage), 0L)
        .fold(e => fail(e.message), identity)
        .receipt
        .stages
        .map(_._2)
    val Vector(parserA, providerA) = stages(full): @unchecked
    val Vector(parserB, providerB) = stages(fewer): @unchecked
    assertNotEquals(parserA, parserB)
    assertNotEquals(providerA, providerB)
  }

  test("an alignment span outside the chart's own sentence is refused whatever unit it names") {
    val restSpan = spanOf(s1, "rest")
    val unnamedOutside = enterChart(alignments =
      Vector(
        align(s0, "entered", c0),
        align(s1, "rest", c1, ref = Some(restSpan.copy(unit = None)))
      )
    )
    val namedOtherSentence = enterChart(alignments =
      Vector(align(s0, "entered", c0), align(s1, "rest", c1, ref = Some(restSpan)))
    )
    val paragraph = atlas.byId.values
      .find(u => u.kind == SurfaceUnitKind.Paragraph && u.span.contains(s0.span))
      .getOrElse(fail("the atlas has no paragraph holding s0"))
    val namedParagraph = enterChart(alignments =
      Vector(
        align(s0, "entered", c0, ref = Some(spanOf(s0, "entered").copy(unit = Some(paragraph.id))))
      )
    )

    val refusedUnnamed =
      ChartProposalProvider.propose(source, atlas, Vector(s0.id -> unnamedOutside))
    assert(refusedUnnamed.left.exists(_.message.contains("lies outside the chart's sentence")))
    val refusedNamed =
      ChartProposalProvider.propose(source, atlas, Vector(s0.id -> namedOtherSentence))
    assert(refusedNamed.left.exists(_.message.contains("lies outside the chart's sentence")))
    val refusedParagraph =
      ChartProposalProvider.propose(source, atlas, Vector(s0.id -> namedParagraph))
    assert(
      refusedParagraph.left.exists(
        _.message.contains("not the chart's sentence or a unit inside it")
      )
    )
    val token = atlas.tokens.find(t => s0.span.contains(t.span)).getOrElse(fail("no token in s0"))
    val namedToken = enterChart(alignments =
      Vector(align(s0, "entered", c0, ref = Some(SpanRef(Some(token.id), token.span))))
    )
    assert(ChartProposalProvider.propose(source, atlas, Vector(s0.id -> namedToken)).isRight)
  }

  /** A support whose alignments are not all measured has no minimum: the number a minimum over the
    * measured members alone would give is a fact about a subset presented as a fact about the
    * support (design contract 7). The parser that produced these charts measures nothing, so this
    * is the case every real chart is in today, with one measured alignment mixed in to show the
    * rule is about the set and not about emptiness.
    */
  test("one unmeasured alignment in a support leaves the proposal's score absent") {
    val licensedPatient = RoleAssignment(
      SourceRole.Numbered(1),
      Some((ParticipantRole.Patient, Credence.unsafeRaw(0.5, ScorerId.unsafe("test-scorer"))))
    )
    val mixed = enterChart(
      relations = Vector(
        PropositionRelation(c0, licensedAgent, ConceptTarget.Node(c1)),
        PropositionRelation(c0, licensedPatient, ConceptTarget.Node(c2))
      ),
      alignments = Vector(
        align(s0, "entered", c0, credence = 0.9),
        align(s0, "Anna", c1, unmeasured = true),
        align(s0, "room", c2, credence = 0.8)
      )
    )
    val proposals = propose(Vector(s0.id -> mixed, s1.id -> restChart()))
    val situation = proposals.situations.find(_.source == ref(s0, c0)).get.bundle
    assertEquals(situation.proposals.head.rawScore, None)
    assertEquals(
      proposals.participantCoverage
        .find(_.situation == ref(s0, c0))
        .get
        .bundle
        .proposals
        .head
        .rawScore,
      None
    )
    // The mention of the one measured filler still carries its own alignment's number.
    val roomMention = proposals.entityMentions.find(_.mention == ref(s0, c2)).get.bundle
    assertEquals(roomMention.proposals.head.rawScore.map(_.value), Some(0.8))
    val annaMention = proposals.entityMentions.find(_.mention == ref(s0, c1)).get.bundle
    assertEquals(annaMention.proposals.head.rawScore, None)
  }

  /** Scores and bases after ADR 0010. A chart-reading proposal's score is the minimum measured
    * alignment score over its support, and nothing when any alignment is unmeasured (the s1 chart
    * has no alignments, so its situation and coverage carry no score rather than an imputed 1.0). A
    * participant's score is the role table's own grade of the normalization, under the table's
    * scorer, not the alignment minimum. No proposal is calibrated: every basis names the rule that
    * determined the value.
    */
  test(
    "scores are measured or absent, participants carry the role table's grade, bases are rules"
  ) {
    val graded = enterChart(
      relations = Vector(
        PropositionRelation(c0, licensedAgent, ConceptTarget.Node(c1)),
        PropositionRelation(c0, RoleAssignment.arg(1), ConceptTarget.Node(c2))
      ),
      alignments = Vector(
        align(s0, "entered", c0, credence = 0.9),
        align(s0, "Anna", c1, credence = 0.6),
        align(s0, "room", c2, credence = 0.8)
      )
    )
    val proposals = propose(Vector(s0.id -> graded, s1.id -> restChart(alignments = Vector.empty)))
    def raw(bundle: EvidenceBundle[?]): Option[Double] =
      bundle.proposals.head.rawScore.map(_.value)
    def basis(bundle: EvidenceBundle[?]): Vector[String] = bundle.bases.map(_.basis.render)
    val bySource = proposals.situations.map(a => a.source -> a.bundle).toMap

    assertEquals(raw(bySource(ref(s0, c0))), Some(0.6))
    assertEquals(raw(bySource(ref(s1, c0))), None)
    assertEquals(raw(proposals.entityMentions.head.bundle), Some(0.6))
    assertEquals(raw(proposals.participants.head.bundle), Some(0.5))
    assertEquals(
      proposals.participants.head.bundle.proposals.head.rawScore.map(_.scorer),
      Some(ScorerId.unsafe("test-scorer"))
    )
    assertEquals(
      bySource(ref(s0, c0)).proposals.head.rawScore.map(_.scorer),
      Some(ChartProposalProvider.MinAlignmentScorer)
    )
    assertEquals(
      proposals.participantCoverage.map(a => a.situation -> raw(a.bundle)).toMap,
      Map(ref(s0, c0) -> Some(0.6), ref(s1, c0) -> None)
    )
    // The temporal pair spans s0 (scored 0.6) and s1 (unscored): a minimum over a set with an
    // unmeasured member is unmeasured, not the measured member's number.
    assertEquals(raw(proposals.temporal.head.bundle), None)
    assertEquals(basis(bySource(ref(s0, c0))), Vector("determined:chart-rule-v1"))
    assertEquals(basis(proposals.contexts.head.bundle), Vector("determined:context-placement-v1"))
    assertEquals(basis(proposals.memberships.head.bundle), Vector("determined:chart-rule-v1"))
    assertEquals(basis(proposals.summary.bundle), Vector("determined:title-rule-v1"))
    // The title rule reads no chart and measures nothing: it is determined by the caller's claim.
    assertEquals(raw(proposals.summary.bundle), None)
    val restCalls = proposals.calls.filter(_.params.get("sentence").contains(s1.id.value))
    assertEquals(restCalls.map(spanSourceOf).toSet, Set("sentence"))
  }

  test("the situation support excludes alignments that name only embedded concepts") {
    val believed = checked(
      s0,
      Some(c0),
      Map(c0 -> Concept.predicate("enter"), c1 -> Concept.predicate("rest")),
      embedded = Vector(EmbeddedProposition(c0, EmbeddingKind.Belief, c1)),
      alignments =
        Vector(align(s0, "entered", c0, credence = 0.7), align(s0, "room", c1, credence = 0.2)),
      salt = "believed"
    )
    val proposals = propose(Vector(s0.id -> believed))
    val record = proposals.evidence.filterNot(_.id == summaryEvidenceId) match
      case Vector(one) => one
      case other       => fail(s"expected one sentence evidence record, found $other")

    assertEquals(record.spans, Some(SpanSet.one(spanOf(s0, "entered"))))
    assertEquals(proposals.situations.head.bundle.proposals.head.rawScore.map(_.value), Some(0.7))
  }

  test("a receipt hashing the provider's own input is accepted; an asserted story id is refused") {
    // A remote parser's receipt hashes the request envelope it sent, not the story text. Requiring
    // it to equal the source or the sentence refused every honestly receipted machine chart, so the
    // binding rests on the sentence id instead.
    val envelopeBound = enterChart().copy(provenance =
      ChartProvenance(
        ChartOrigin.Parser(parser),
        Vector(chartCall("envelope", Checksum.ofText("""{"schema":"parser.request/v1"}"""))),
        Vector.empty
      )
    )
    assert(ChartProposalProvider.propose(source, atlas, Vector(s0.id -> envelopeBound)).isRight)

    // The sentence id only binds a chart to this text while the story id is the text's own content
    // address; an asserted id breaks that chain and must be refused rather than trusted.
    val asserted = StorySource
      .fromText(source.rawText, explicitId = Some(StoryId.unsafe("story:asserted")))
      .fold(e => fail(e.message), identity)
    val assertedAtlas = SurfaceAnalyzer.analyze(asserted)
    val assertedSentence = assertedAtlas.sentences.head
    val refused = ChartProposalProvider.propose(
      asserted,
      assertedAtlas,
      Vector(assertedSentence.id -> enterChart())
    )
    assert(
      refused.left.exists(_.message.contains("is not the content address of its text")),
      s"expected a content-address refusal, got $refused"
    )
  }

  test("receipts record the chart origin") {
    val parsed = propose(Vector(s0.id -> enterChart()))
    val hand = propose(Vector(s0.id -> enterChart().copy(provenance = ChartProvenance.hand)))
    def origins(p: ChartProposals): Set[String] =
      p.calls.flatMap(_.params.get("chart-origin")).toSet
    assertEquals(origins(parsed), Set("parser:test:chart-parser:1"))
    assertEquals(origins(hand), Set("hand"))
  }
  test("the policy is conservative except that one program counts as one provider") {
    val policy = ChartProposalProvider.Policy
    Vector(ClaimFamily.ContextAssignment, ClaimFamily.SegmentMembership).foreach { family =>
      val single = policy.forFamily(family)
      assertEquals(single.acceptThreshold.value, 0.9)
      assertEquals(single.reviewBand.value, 0.5)
      assertEquals(single.requireAgreement, 1)
      assertEquals(single.requireCalibration, true)
      assertEquals(single.conservative, true)
      assertEquals(single.requireSpanEvidence, true)
      assertEquals(single.criticBlockThreshold, 0.5)
    }
    assertEquals(policy.forFamily(ClaimFamily.CausalEdge), FamilyPolicy.Conservative)
    assertEquals(policy.forFamily(ClaimFamily.StrictPrecedence), FamilyPolicy.Conservative)
    assertEquals(policy.forFamily(ClaimFamily.SituationMention), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.Summary), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.EntityMention), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.ParticipantRole), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.ParticipantCoverage), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.TemporalRelation), FamilyPolicy.Ordinary)
  }
  test("a title whose provenance the source does not record is not established and abstains") {
    // The exact defect this closes: a caller put the input file's name in `title` and the summary
    // rule published it at credence 1.0. The string is still there; what is missing is any basis
    // for carrying it, and the two abstentions are told apart by their reason.
    val unestablished = StorySource
      .fromText(source.rawText, Some("wog.txt"))
      .fold(e => fail(e.message), identity)
    assertEquals(unestablished.titleProvenance, None)
    assertEquals(unestablished.establishedTitle, None)

    val atlas2 = SurfaceAnalyzer.analyze(unestablished)
    val u0 = atlas2.sentences(0)
    val chart = checked(u0, Some(c0), Map(c0 -> Concept.predicate("enter")), salt = "unestablished")
    val proposals = ChartProposalProvider
      .propose(unestablished, atlas2, Vector(u0.id -> chart))
      .fold(e => fail(e.message), identity)

    assertEquals(proposals.summaryCoverage, SummaryCoverage.TitleUnestablished)
    assertEquals(dispositions(proposals.summary.bundle), Vector(ProposalDisposition.Abstained))
    val summaryCall = proposals.calls
      .filter(_.params.get("rule").contains(ChartProposalProvider.AbstainSummaryRule))
    assertEquals(
      summaryCall.map(_.params("reason")),
      Vector(ChartProposalProvider.UnestablishedTitleReason)
    )
    assert(
      proposals.calls.forall(call => !call.params.values.exists(_.contains("wog.txt"))),
      "the unestablished title reached a receipt"
    )
  }

  test("a missing title yields an abstained summary and a NoTitle row") {
    val untitled = StorySource
      .fromText(source.rawText, None)
      .fold(e => fail(e.message), identity)
    val untitledAtlas = SurfaceAnalyzer.analyze(untitled)
    val u0 = untitledAtlas.sentences(0)
    val chart = checked(u0, Some(c0), Map(c0 -> Concept.predicate("enter")), salt = "untitled")
    val proposals = ChartProposalProvider
      .propose(untitled, untitledAtlas, Vector(u0.id -> chart))
      .fold(e => fail(e.message), identity)

    assertEquals(proposals.summaryCoverage, SummaryCoverage.NoTitle)
    assertEquals(dispositions(proposals.summary.bundle), Vector(ProposalDisposition.Abstained))
    assertEquals(proposals.summary.bundle.sourceSupport, SourceSupport.text(0.0, None))

    // ADR 0005 §10: the summary is still a gap, and nothing else waits for it. The root segment is
    // derived from its one member, spans the canonical text, cites the member's claim, and records
    // the summary's absence with its reason; the membership is emitted; the bare text validates.
    val compiled = compile(Vector(u0.id -> chart), untitled, untitledAtlas)
    val byFamily = compiled.derivation.gaps.map(g => g.family -> g.reason).toMap
    assertEquals(
      byFamily(ClaimFamily.Summary),
      DerivationGapReason.Unresolved(ResolutionFailure.NoProposal)
    )
    assert(!byFamily.contains(ClaimFamily.SegmentMembership), byFamily.toString)
    val graph = compiled.draft.graph
    val root = graph.segments.values.toVector match
      case Vector(one) => one
      case other       => fail(s"expected one root segment, got ${other.size}")
    assertEquals(root.kind, SegmentKind.Story)
    assertEquals(root.summary, SegmentSummary.Unsummarized(SummaryGap.NotProposed))
    assertEquals(
      root.support.textSpans.get.minSpan,
      TextSpan.of(0, untitled.canonicalText.length).toOption.get
    )
    assertEquals(root.meta.evidence.head.upstream, graph.situations.values.map(_.meta.id).toSet)
    assertEquals(
      compiled.draft.hierarchy.primary.map(e => (e.member, e.parent)),
      graph.situations.keys.toVector.map(id => (NarrativeMember.Situation(id), root.id))
    )
    assert(compiled.validated.isDefined, compiled.validation.report.violations.toString)
  }

  test("a stated summary is the root's second claim, beside the segment's own") {
    val chart = checked(s0, Some(c0), Map(c0 -> Concept.predicate("enter")), salt = "stated")
    val compiled = compile(Vector(s0.id -> chart))
    val root = compiled.draft.graph.segments.values.head
    root.summary match
      case SegmentSummary.Stated(r) =>
        assertEquals(r.value, source.title.getOrElse(fail("the fixture source has a title")))
        assertNotEquals(r.meta.id, root.meta.id)
        assert(compiled.draft.claims.exists(_.id == root.meta.id))
        assert(compiled.draft.claims.exists(_.id == r.meta.id))
      case other => fail(s"expected a stated summary, got $other")
  }

  test("a chart on a non-sentence unit, a duplicate sentence, or a foreign atlas is refused") {
    val token = atlas.tokens.head
    val onToken = PropositionEvidence.of(
      ChartValidator
        .check(
          PropositionChart.unchecked(
            Some(c0),
            Map(c0 -> Concept.predicate("enter")),
            Vector.empty,
            sentence = Some(token.id)
          )
        )
        .fold(v => fail(v.toString), identity)
    )
    val refusedToken = ChartProposalProvider.propose(source, atlas, Vector(token.id -> onToken))
    assert(refusedToken.isLeft, "a token-anchored chart must be refused")
    assert(refusedToken.left.exists(_.message.contains("not a sentence")))

    val duplicate =
      ChartProposalProvider.propose(
        source,
        atlas,
        Vector(s0.id -> enterChart(), s0.id -> enterChart())
      )
    assert(duplicate.left.exists(_.message.contains("duplicate")))

    val mismatched = ChartProposalProvider.propose(source, atlas, Vector(s1.id -> enterChart()))
    assert(mismatched.left.exists(_.message.contains("chart sentence does not equal")))

    val other = StorySource.fromText("Another text.", None).fold(e => fail(e.message), identity)
    val foreign = ChartProposalProvider.propose(other, atlas, Vector.empty)
    assert(foreign.left.exists(_.message.contains("different source")))

    val escaping = enterChart(alignments =
      Vector(align(s1, "rest", c0)).map(a =>
        a.copy(spans = SpanSet.one(SpanRef(Some(s0.id), a.spans.refs.head.span)))
      )
    )
    val refusedSpan = ChartProposalProvider.propose(source, atlas, Vector(s0.id -> escaping))
    assert(refusedSpan.left.exists(_.message.contains("lies outside the chart's sentence")))
  }

  test("a story with no charts at all still yields a full ledger and a compilable input") {
    val proposals = propose(Vector.empty)
    assertEquals(
      proposals.coverage,
      Vector(
        SentenceCoverage.NoChart(s0.id),
        SentenceCoverage.NoChart(s1.id),
        SentenceCoverage.NoChart(s2.id)
      )
    )
    assertEquals(proposals.counts, CoverageCounts(0, 0, 0, 0, 3))
    assertEquals(proposals.situations, Vector.empty)
    val compiled = compile(Vector.empty)
    assertEquals(compiled.draft.graph.situations, Map.empty)
    assertEquals(compiled.derivation.gaps.map(_.family), Vector(ClaimFamily.Summary))
  }
