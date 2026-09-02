package storymodel4s.document

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.proposition.{Polarity as ChartPolarity, *}
import storymodel4s.story.{Polarity as StoryPolarity, *}

/** Court for [[ChartProposalProvider]]: hand-built checked charts in, compiler input out. */
class ChartProposalProviderSuite extends FunSuite:
  private val source = StorySource
    .fromText(
      "Anna entered the room. She did not rest. The lamp was on the table.",
      Some("Tiny story")
    )
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val sentences = atlas.sentences
  private val s0 = sentences(0)
  private val s1 = sentences(1)
  private val s2 = sentences(2)
  private val parser = Fingerprint.unsafe("test:chart-parser:1")
  private val parserStage = StageId.unsafe("test-chart-parser")

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

  private def align(unit: SurfaceUnit, word: String, concept: ConceptId): PropositionAlignment =
    val spans = SpanSet.one(spanOf(unit, word))
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
      Credence.unsafeRaw(1.0),
      ClaimMeta.unsafe(
        ClaimId.unsafe(s"claim:align:${unit.id.value}:$word"),
        EpistemicStatus.SurfaceExplicit,
        Credence.unsafeRaw(1.0),
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
      salt: String = ""
  ): PropositionEvidence =
    val unchecked = PropositionChart.unchecked(
      focus,
      concepts,
      relations,
      polarity,
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

  private val c0 = ConceptId.unsafe("c0")
  private val c1 = ConceptId.unsafe("c1")
  private val c2 = ConceptId.unsafe("c2")
  private def ref(unit: SurfaceUnit, concept: ConceptId): ChartNodeRef =
    ChartNodeRef(unit.id, concept)

  private val enterFrame = FrameRef("propbank", "enter-01", None)
  private val locatedFrame = FrameRef("amr", "be-located-at-91", None)

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
      c0 -> Concept.predicate("be-located-at", Some(locatedFrame)),
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
      situation.calibrations.map(c => (c.probability, c.model)),
      Vector((Probability.One, "chart-rule-v1"))
    )
    assertEquals(
      proposals.coverage,
      Vector(
        SentenceCoverage.Proposed(s0.id, root, 0, 2),
        SentenceCoverage.NoChart(s1.id),
        SentenceCoverage.NoChart(s2.id)
      )
    )
    assertEquals(proposals.counts, CoverageCounts(1, 0, 0, 2))
    assertEquals(proposals.summaryCoverage, SummaryCoverage.Proposed("Tiny story"))
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
    val situationCalls = proposals.calls.filter(_.params.get("sentence").contains(s0.id.value))
    assertEquals(situationCalls.size, 4)
    assertEquals(situationCalls.map(spanSourceOf).toSet, Set("chart-alignments"))
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

  test("a -91 reification focus is a state; every other predicate focus is an event") {
    val proposals = propose(Vector(s0.id -> enterChart(), s2.id -> lampChart))
    val byRef = proposals.situations
      .map(a => a.source -> a.bundle.proposals.head.value.getOrElse(fail("no value")))
      .toMap

    assertEquals(byRef(ref(s2, c0)).kind, SituationKind.State)
    assertEquals(byRef(ref(s2, c0)).predicate.frame, Some("amr:be-located-at-91"))
    assertEquals(byRef(ref(s2, c0)).description, "be-located-at lamp table")
    assertEquals(byRef(ref(s0, c0)).kind, SituationKind.Event)
    assert(ChartProposalProvider.StateFrames("be-located-at-91"))
    assert(!ChartProposalProvider.StateFrames("enter-01"))
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
    val embeddedFocus = checked(
      s0,
      Some(c1),
      Map(c0 -> Concept.predicate("think"), c1 -> Concept.predicate("enter")),
      embedded = Vector(EmbeddedProposition(c0, EmbeddingKind.Belief, c1)),
      salt = "embedded-focus"
    )
    val cases = Vector(
      (noFocus, ref(s0, c0), AbstentionReason.NoFocus),
      (entityFocus, ref(s0, c1), AbstentionReason.FocusNotPredicate(ConceptKind.Name)),
      (embeddedFocus, ref(s0, c1), AbstentionReason.FocusEmbedded)
    )
    cases.foreach { (chart, anchor, reason) =>
      val proposals = propose(Vector(s0.id -> chart))
      assertEquals(proposals.coverage.head, SentenceCoverage.Abstained(s0.id, anchor, reason))
      assertEquals(proposals.counts, CoverageCounts(0, 1, 0, 2))
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
      assertEquals(proposals.situations.head.bundle.calibrations, Vector.empty)
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
      ContentAddress.of("chart-proposal-evidence", "story", source.canonicalChecksum.hex)
    )

  test("an empty chart and a missing chart are ledger rows, and the input still compiles") {
    val empty = checked(s0, None, Map.empty, salt = "empty")
    val charts = Vector(s0.id -> empty, s1.id -> restChart())
    val proposals = propose(charts)

    assertEquals(
      proposals.coverage,
      Vector(
        SentenceCoverage.EmptyChart(s0.id),
        SentenceCoverage.Proposed(s1.id, ref(s1, c0), 0, 0),
        SentenceCoverage.NoChart(s2.id)
      )
    )
    assertEquals(proposals.counts, CoverageCounts(1, 0, 1, 1))
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
    assertEquals(model.trajectory.steps.map(_.entityTurnover), Vector(0.0, 0.0))
    assertEquals(
      model.trajectory.steps.map(_.worldTime.value).toSet,
      Set(WorldTimeTransition.Unresolved(Vector.empty))
    )
    assertEquals(model.graph.entities, Map.empty)
  }

  private val licensedAgent = RoleAssignment(
    SourceRole.Numbered(0),
    Some((ParticipantRole.Agent, Credence.unsafeRaw(0.5)))
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

    assertEquals(proposals.coverage.head, SentenceCoverage.Proposed(s0.id, root, 1, 1))
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
    "named time and location roles map as the AMR adapter maps them; the unaligned filler cites the root"
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

    assertEquals(proposals.coverage.head, SentenceCoverage.Proposed(s0.id, ref(s0, c0), 2, 0))
    assertEquals(byFiller(ref(s0, c1)), ParticipantRole.Time)
    assertEquals(byFiller(ref(s0, c2)), ParticipantRole.Location)
    val mentionSources = proposals.calls
      .filter(_.params.get("rule").contains("entity-filler-mention-rule"))
      .map(c => c.params("filler") -> spanSourceOf(c))
      .toMap
    assertEquals(mentionSources, Map("c1" -> "root-support", "c2" -> "filler-alignments"))
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

    assertEquals(proposals.coverage.head, SentenceCoverage.Proposed(s0.id, ref(s0, c0), 0, 1))
    assertEquals(proposals.participants, Vector.empty)
    assertEquals(proposals.entityMentions, Vector.empty)
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
      "d8b5d676af644421f022b6fc8650f8ceb7d5b1ff65ce0c634c09e0bb25f47bde"
    )
    assert(ChartProposalProvider.RulesText.contains("Never Before or Meets"))
    assert(ChartProposalProvider.RulesText.contains("time=Time"))
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

  test("receipts bind the source checksum, the rules checksum, and the chart receipts") {
    val charts = Vector(s0.id -> enterChart(), s1.id -> restChart())
    val proposals = propose(charts)
    val parserDigest = Checksum.ofText("parser-stage")
    val input = ChartProposalProvider
      .input(source, atlas, charts, Some(parserStage -> parserDigest), 7L)
      .fold(e => fail(e.message), identity)

    assertEquals(
      ChartProposalProvider.Prompt.checksum,
      Checksum.ofText(ChartProposalProvider.RulesText)
    )
    assertEquals(input.provenance.configHash, Checksum.ofText(ChartProposalProvider.RulesText))
    assertEquals(input.provenance.softwareVersion, StoryModel.SchemaVersion)
    assert(proposals.calls.forall(_.inputChecksum == source.canonicalChecksum))
    assert(proposals.calls.forall(_.provider == "chart-proposal-provider"))
    assertEquals(proposals.calls.size, 10)
    assertEquals(
      input.receipt.stages,
      Vector(
        parserStage -> parserDigest,
        ChartProposalProvider.Stage -> ContentAddress.digest(
          proposals.calls.map(_.outputChecksum.hex)
        )
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

  test("the policy is conservative except that one program counts as one provider") {
    val policy = ChartProposalProvider.Policy
    assertEquals(policy.forFamily(ClaimFamily.ContextAssignment).requireAgreement, 1)
    assertEquals(policy.forFamily(ClaimFamily.SegmentMembership).requireAgreement, 1)
    assert(policy.forFamily(ClaimFamily.ContextAssignment).conservative)
    assertEquals(policy.forFamily(ClaimFamily.CausalEdge), FamilyPolicy.Conservative)
    assertEquals(policy.forFamily(ClaimFamily.SituationMention), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.Summary), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.EntityMention), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.ParticipantRole), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.ParticipantCoverage), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.TemporalRelation), FamilyPolicy.Ordinary)
    assertEquals(policy.forFamily(ClaimFamily.StrictPrecedence), FamilyPolicy.Conservative)
    assert(ChartProposalProvider.RulesText.contains("requireAgreement = 1"))
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
    assertEquals(proposals.summary.bundle.sourceSupport, SourceSupport(0.0, None))

    val compiled = compile(Vector(u0.id -> chart), untitled, untitledAtlas)
    val byFamily = compiled.derivation.gaps.map(g => g.family -> g.reason).toMap
    assertEquals(
      byFamily(ClaimFamily.Summary),
      DerivationGapReason.Unresolved(ResolutionFailure.NoProposal)
    )
    assertEquals(
      byFamily(ClaimFamily.SegmentMembership),
      DerivationGapReason.MissingUpstream(
        Vector(NarrativeCandidateAddress.StorySummary(untitled.id))
      )
    )
    assertEquals(compiled.validated, None)
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
    assert(refusedSpan.left.exists(_.message.contains("escapes surface unit")))
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
    assertEquals(proposals.counts, CoverageCounts(0, 0, 0, 3))
    assertEquals(proposals.situations, Vector.empty)
    val compiled = compile(Vector.empty)
    assertEquals(compiled.draft.graph.situations, Map.empty)
    assertEquals(compiled.derivation.gaps.map(_.family), Vector(ClaimFamily.Summary))
  }
