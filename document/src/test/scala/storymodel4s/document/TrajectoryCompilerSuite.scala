package storymodel4s.document

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.proposition.{Polarity as ChartPolarity, *}
import storymodel4s.story.{Polarity as StoryPolarity, *}

/** The phase 1.4 court: a three-sentence machine-built draft validates on evidence alone.
  *
  * Why hand-built attempts rather than the provider: each guard the compiler adds (a coverage claim
  * per step endpoint, a participant edge per covered filler, a canonical temporal relation,
  * case-folded exact coreference) must be falsifiable one at a time, and only a fixture that names
  * every attempt can withhold exactly one.
  */
class TrajectoryCompilerSuite extends FunSuite:
  private val source = StorySource
    .titled(
      "The man went. The man saw. The man returned.",
      StoryTitle.callerSupplied("Three steps").fold(e => fail(e.message), identity)
    )
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val sentences = atlas.sentences
  private val stage = StageId.unsafe("test-trajectory-provider")
  private val extractor = Fingerprint.unsafe("test:trajectory-provider:1")
  private val prompt = PromptPackageRef("test-trajectory", "1", Checksum.ofText("test-prompt"))
  private val parser = Fingerprint.unsafe("test:trajectory-parser:1")

  private val predicate = ConceptId.unsafe("p")
  private val filler = ConceptId.unsafe("e")
  private val words =
    Vector(("go", "go-02", "went"), ("see", "see-01", "saw"), ("return", "return-01", "returned"))

  private def call(provider: String, salt: String): ProviderCall =
    ProviderCall(
      provider,
      "test-model",
      "1",
      Some(PromptTemplateVersion.unsafe("1")),
      source.canonicalChecksum,
      Checksum.ofText(s"output:$salt"),
      Map.empty,
      Some(1L),
      cached = true
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
      stage
    )
    PropositionAlignment(
      AlignmentTarget.Concepts(NonEmptySet.one(concept)),
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

  private val licensedAgent = RoleAssignment(
    SourceRole.Numbered(0),
    Some((ParticipantRole.Agent, Credence.unsafeRaw(0.5, ScorerId.unsafe("test-scorer"))))
  )

  private def chart(index: Int, fillerKind: ConceptKind = ConceptKind.Entity): PropositionEvidence =
    val unit = sentences(index)
    val (lemma, frame, word) = words(index)
    val unchecked = PropositionChart.unchecked(
      Some(predicate),
      Map(
        predicate -> Concept.predicate(lemma, Some(FrameRef("propbank", frame, None))),
        filler -> Concept(Lemma.unsafe("man"), None, None, fillerKind)
      ),
      Vector(PropositionRelation(predicate, licensedAgent, ConceptTarget.Node(filler))),
      Map(predicate -> ChartPolarity.Positive),
      Vector.empty,
      Vector(align(unit, word, predicate), align(unit, "man", filler)),
      ChartProvenance.hand,
      Some(unit.id)
    )
    PropositionEvidence.of(ChartValidator.check(unchecked).fold(v => fail(v.toString), identity))

  private val charts = Vector(0, 1, 2).map(i => sentences(i).id -> chart(i))
  private def s(i: Int): ChartNodeRef = ChartNodeRef(sentences(i).id, predicate)
  private def m(i: Int): ChartNodeRef = ChartNodeRef(sentences(i).id, filler)

  private def evidence(id: String, spans: SpanSet): Evidence =
    Evidence(EvidenceId.unsafe(id), Some(spans), Set.empty, extractor, stage)
  private def ev(i: Int): Evidence =
    evidence(s"ev:sentence:$i", SpanSet.one(SpanRef(Some(sentences(i).id), sentences(i).span)))
  private def evMention(i: Int): Evidence =
    evidence(s"ev:mention:$i", SpanSet.one(spanOf(sentences(i), "man")))
  private def evPair(i: Int, j: Int): Evidence =
    evidence(
      s"ev:pair:$i:$j",
      SpanSet.one(SpanRef(Some(sentences(i).id), sentences(i).span)) ++
        SpanSet.one(SpanRef(Some(sentences(j).id), sentences(j).span))
    )
  private val evSummary =
    evidence(
      "ev:summary",
      SpanSet.one(SpanRef(None, TextSpan.unsafe(0, source.canonicalText.length)))
    )
  private val ledger: Vector[Evidence] =
    Vector(0, 1, 2).flatMap(i => Vector(ev(i), evMention(i))) ++
      Vector(evPair(0, 1), evPair(1, 2), evSummary)

  private def bundle[A](
      value: A,
      ev: Evidence,
      provider: String,
      salt: String,
      calibrated: Boolean = true,
      additionalProviders: Vector[String] = Vector.empty
  ): EvidenceBundle[A] =
    val proposals = (provider +: additionalProviders).zipWithIndex.map { (name, index) =>
      val task = TaskId.unsafe(s"task:$salt:$index")
      AgentProposal.proposed(
        task,
        value,
        NonEmptyVector.one(EvidenceRef.Inline(ev)),
        Some(RawScore.unsafe(0.91, ScorerId.unsafe("test-scorer"))),
        Vector.empty,
        AgentCallReceipt(call(name, s"$salt:$index"), prompt, task)
      )
    }
    EvidenceBundle(
      proposals,
      Vector.empty,
      StructuralValidity.Valid,
      SourceSupport(1.0, ev.spans),
      agreementScore = 1.0,
      bases =
        if calibrated then
          Vector(
            CandidateBasis(
              value,
              AcceptanceBasis
                .Calibrated(Probability.unsafe(0.97), CalibrationModelId.unsafe("test-v1"))
            )
          )
        else Vector.empty
    )

  private def situationAttempt(i: Int): SituationAttempt =
    val (lemma, frame, _) = words(i)
    SituationAttempt(
      s(i),
      bundle(
        SituationProposal(
          SituationKind.Event,
          Predicate(lemma, Some(s"propbank:$frame"), lemma),
          s"$lemma man",
          StoryPolarity.Positive,
          Modality.Asserted,
          None
        ),
        ev(i),
        "situation-agent",
        s"s$i"
      )
    )
  private def contextAttempt(i: Int): ContextAssignmentAttempt =
    ContextAssignmentAttempt(
      s(i),
      bundle(
        ContextAssignmentProposal.NarratedWorld,
        ev(i),
        "context-agent-a",
        s"context:$i",
        additionalProviders = Vector("context-agent-b")
      )
    )
  private def membershipAttempt(i: Int): SegmentMembershipAttempt =
    SegmentMembershipAttempt(
      s(i),
      bundle(
        SegmentMembershipProposal.PrimaryStoryMember,
        ev(i),
        "membership-agent-a",
        s"membership:$i",
        additionalProviders = Vector("membership-agent-b")
      )
    )
  private def mentionAttempt(
      i: Int,
      label: String = "man",
      entityType: EntityType = EntityType.Custom("chart", "entity"),
      calibrated: Boolean = true,
      at: Option[ChartNodeRef] = None
  ): EntityMentionAttempt =
    EntityMentionAttempt(
      at.getOrElse(m(i)),
      bundle(
        EntityMentionProposal(label, entityType),
        evMention(i),
        "mention-agent",
        s"m$i",
        calibrated
      )
    )
  private def participantAttempt(
      i: Int,
      calibrated: Boolean = true,
      at: Option[ChartNodeRef] = None
  ): ParticipantAttempt =
    ParticipantAttempt(
      s(i),
      at.getOrElse(m(i)),
      bundle(ParticipantRole.Agent, ev(i), "participant-agent", s"p$i", calibrated)
    )
  private def coverageAttempt(
      i: Int,
      fillers: Vector[ChartNodeRef],
      calibrated: Boolean = true
  ): ParticipantCoverageAttempt =
    ParticipantCoverageAttempt(
      s(i),
      bundle(ParticipantCoverage.of(fillers), ev(i), "coverage-agent", s"c$i", calibrated)
    )
  private def temporalAttempt(
      i: Int,
      j: Int,
      relation: TemporalRelation = TemporalRelation.Unclear
  ): TemporalAttempt =
    TemporalAttempt(s(i), s(j), bundle(relation, evPair(i, j), "temporal-agent", s"t$i$j"))

  private val summaryAttempt =
    StorySummaryAttempt(
      bundle(
        StorySummaryProposal("A man goes, sees, and returns."),
        evSummary,
        "summary-agent",
        "sum"
      )
    )
  private val defaultMentions = Vector(0, 1, 2).map(i => mentionAttempt(i))
  private val defaultParticipants = Vector(0, 1, 2).map(i => participantAttempt(i))
  private val defaultCoverage = Vector(0, 1, 2).map(i => coverageAttempt(i, Vector(m(i))))
  private val defaultTemporal = Vector(temporalAttempt(0, 1), temporalAttempt(1, 2))

  private def circumstanceAttempt(
      i: Int,
      label: String = "then",
      at: Option[ChartNodeRef] = None
  ): SituationCircumstanceAttempt =
    SituationCircumstanceAttempt(
      s(i),
      at.getOrElse(m(i)),
      bundle(
        CircumstanceProposal(CircumstanceKind.Time, label),
        ev(i),
        "circumstance-agent",
        s"x$i"
      )
    )

  private def attemptedInput(
      mentions: Vector[EntityMentionAttempt] = defaultMentions,
      participants: Vector[ParticipantAttempt] = defaultParticipants,
      coverage: Vector[ParticipantCoverageAttempt] = defaultCoverage,
      circumstances: Vector[SituationCircumstanceAttempt] = Vector.empty,
      temporal: Vector[TemporalAttempt] = defaultTemporal,
      chartOrder: Vector[(SurfaceUnitId, PropositionEvidence)] = charts
  ): Either[NarrativeCompilerError, NarrativeCompilerInput] =
    val receipt = BuildReceipt(
      source.id,
      source.canonicalChecksum,
      StoryModel.SchemaVersion,
      Vector(stage -> Checksum.ofText("recorded-provider-outputs")),
      createdAtEpochMillis = 0L
    )
    NarrativeCompilerInput.of(
      source,
      atlas,
      chartOrder,
      ledger,
      Vector(0, 1, 2).map(situationAttempt),
      Vector(0, 1, 2).map(contextAttempt),
      summaryAttempt,
      Vector(0, 1, 2).map(membershipAttempt),
      Vector.empty,
      mentions,
      participants,
      coverage,
      circumstances,
      temporal,
      AcceptancePolicy.Conservative,
      receipt,
      Provenance(Vector.empty, StoryModel.SchemaVersion, Checksum.ofText("trajectory-test"))
    )

  private def compile(
      mentions: Vector[EntityMentionAttempt] = defaultMentions,
      participants: Vector[ParticipantAttempt] = defaultParticipants,
      coverage: Vector[ParticipantCoverageAttempt] = defaultCoverage,
      temporal: Vector[TemporalAttempt] = defaultTemporal,
      chartOrder: Vector[(SurfaceUnitId, PropositionEvidence)] = charts
  ): NarrativeCompilation =
    val input =
      attemptedInput(mentions, participants, coverage, temporal = temporal, chartOrder = chartOrder)
        .fold(e => fail(e.message), identity)
    NarrativeCompiler.compile(input).fold(e => fail(e.message), identity)

  private def gapsOf(
      result: NarrativeCompilation
  ): Map[NarrativeCandidateAddress, DerivationGapReason] =
    result.derivation.gaps.map(g => g.target -> g.reason).toMap

  private def stepTarget(i: Int, j: Int): NarrativeCandidateAddress =
    NarrativeCandidateAddress.TrajectoryStep(s(i), s(j))
  private def coverageTarget(i: Int): NarrativeCandidateAddress =
    NarrativeCandidateAddress.ParticipantCoverage(s(i))

  test("three evidenced sentences compile into a validated story with one entity and two steps") {
    val result = compile()
    val model = result.validated.getOrElse(fail(result.validation.report.render))

    assertEquals(result.derivation.gaps, Vector.empty)
    assertEquals(model.graph.situations.size, 3)
    assertEquals(model.graph.entities.size, 1)
    val entity = model.graph.entities.values.head
    assertEquals(entity.label.value, "man")
    assertEquals(entity.label.alternatives, Vector.empty)
    assertEquals(entity.entityType, EntityType.Custom("chart", "entity"))
    assertEquals(entity.mentions.length, 3)
    assertEquals(entity.meta.status, EpistemicStatus.StructurallyDerived)
    val mentionClaims = entity.meta.evidence.toVector.flatMap(_.upstream).toSet
    assertEquals(mentionClaims.size, 3)
    assert(mentionClaims.subsetOf(result.derivation.emittedClaims.keySet))
    assert(
      mentionClaims.forall(id =>
        result.derivation.emittedClaims(id).status == EpistemicStatus.SurfaceExplicit
      )
    )
    assertEquals(result.entityMentions.size, 3)
    assertEquals(result.entityPartition.size, 1)
    assertEquals(result.projections.entities.size, 1)
    assertEquals(result.projections.entities.head.sources.length, 3)

    assertEquals(model.graph.relations.participants.size, 3)
    assertEquals(model.graph.relations.participants.map(_.role).toSet, Set(ParticipantRole.Agent))
    assertEquals(model.graph.relations.participants.map(_.entity).toSet, Set(entity.id))
    assertEquals(
      model.graph.relations.participants.map(_.meta.status).toSet,
      Set(EpistemicStatus.Hypothesized)
    )
    assertEquals(
      model.graph.relations.temporal.map(_.relation),
      Vector(TemporalRelation.Unclear, TemporalRelation.Unclear)
    )
    assertEquals(model.graph.relations.temporal.map(_.context).toSet, model.graph.rootContext.toSet)

    assertEquals(model.trajectory.steps.size, 2)
    model.trajectory.steps.foreach { step =>
      assertEquals(step.entityTurnover, 0.0)
      assertEquals(step.worldTime.value, WorldTimeTransition.Unresolved(Vector.empty))
      assertEquals(step.worldTimeContext, model.graph.rootContext)
      assert(!step.contextChange)
    }
    val emittedFamilies = result.derivation.attempts.collect {
      case DerivationAttempt(_, family, DerivationDisposition.Emitted(_)) => family
    }
    assertEquals(emittedFamilies.count(_ == ClaimFamily.ParticipantCoverage), 3)
    assertEquals(emittedFamilies.count(_ == ClaimFamily.DiscourseTrajectory), 2)
    assert(!result.isPartial)
  }

  test("a pair without accepted participant coverage yields no trajectory step") {
    val result = compile(coverage =
      Vector(
        coverageAttempt(0, Vector(m(0))),
        coverageAttempt(1, Vector(m(1)), calibrated = false),
        coverageAttempt(2, Vector(m(2)))
      )
    )
    val gaps = gapsOf(result)

    assertEquals(result.draft.trajectory, DiscourseTrajectory.empty)
    assertEquals(result.validated, None)
    assertEquals(
      gaps(coverageTarget(1)),
      DerivationGapReason.Unresolved(ResolutionFailure.Uncalibrated)
    )
    assertEquals(
      gaps(stepTarget(0, 1)),
      DerivationGapReason.MissingUpstream(Vector(coverageTarget(1)))
    )
    assertEquals(
      gaps(stepTarget(1, 2)),
      DerivationGapReason.MissingUpstream(Vector(coverageTarget(1)))
    )
    assertEquals(gaps.size, 3)
    assertEquals(result.draft.graph.relations.participants.size, 3)
    assertEquals(result.draft.graph.entities.values.head.mentions.length, 3)
    assert(result.validation.report.byLaw.contains("trajectory.complete"))
  }

  test("a coverage naming a filler whose participant claim is unresolved is a gap, not a step") {
    val result = compile(participants =
      Vector(
        participantAttempt(0),
        participantAttempt(1, calibrated = false),
        participantAttempt(2)
      )
    )
    val gaps = gapsOf(result)

    assertEquals(result.draft.trajectory, DiscourseTrajectory.empty)
    assertEquals(
      gaps(NarrativeCandidateAddress.Participant(s(1), m(1))),
      DerivationGapReason.Unresolved(ResolutionFailure.Uncalibrated)
    )
    assertEquals(
      gaps(coverageTarget(1)),
      DerivationGapReason.MissingUpstream(Vector(NarrativeCandidateAddress.Participant(s(1), m(1))))
    )
    assertEquals(
      gaps(stepTarget(0, 1)),
      DerivationGapReason.MissingUpstream(Vector(coverageTarget(1)))
    )
    assertEquals(
      gaps(stepTarget(1, 2)),
      DerivationGapReason.MissingUpstream(Vector(coverageTarget(1)))
    )
    assertEquals(gaps.size, 4)
    assertEquals(result.draft.graph.relations.participants.size, 2)
    assertEquals(result.validated, None)
  }

  test("a participant whose filler mention is unresolved is a gap, never a default edge") {
    val result = compile(mentions =
      Vector(
        mentionAttempt(0),
        mentionAttempt(1, calibrated = false),
        mentionAttempt(2)
      )
    )
    val gaps = gapsOf(result)

    assertEquals(
      gaps(NarrativeCandidateAddress.EntityMention(m(1))),
      DerivationGapReason.Unresolved(ResolutionFailure.Uncalibrated)
    )
    assertEquals(
      gaps(NarrativeCandidateAddress.Participant(s(1), m(1))),
      DerivationGapReason.MissingUpstream(Vector(NarrativeCandidateAddress.EntityMention(m(1))))
    )
    assertEquals(
      gaps(coverageTarget(1)),
      DerivationGapReason.MissingUpstream(Vector(NarrativeCandidateAddress.Participant(s(1), m(1))))
    )
    assertEquals(result.draft.graph.entities.values.head.mentions.length, 2)
    assertEquals(result.draft.graph.relations.participants.size, 2)
    assertEquals(result.draft.trajectory, DiscourseTrajectory.empty)
  }

  test("a participant whose filler lies in another sentence is refused at the input") {
    val attempted = attemptedInput(
      participants = Vector(
        participantAttempt(0, at = Some(m(1))),
        participantAttempt(1),
        participantAttempt(2)
      ),
      coverage = Vector(
        coverageAttempt(0, Vector(m(1))),
        coverageAttempt(1, Vector(m(1))),
        coverageAttempt(2, Vector(m(2)))
      )
    )

    attempted match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(errors.exists(_.message.contains("is not in the situation's sentence")))
      case Left(other) => fail(other.message)
      case Right(_)    => fail("a cross-sentence participant reached the compiler")
  }

  test("a converse temporal relation is refused at the input boundary") {
    val attempted = attemptedInput(temporal =
      Vector(temporalAttempt(0, 1, TemporalRelation.After), temporalAttempt(1, 2))
    )

    attempted match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(errors.exists(_.message.contains("After is a converse form; propose Before")))
      case Left(other) => fail(other.message)
      case Right(_)    => fail("a converse temporal relation reached the compiler")
  }

  test("exact coreference folds label case and splits on the lemma") {
    val folded = compile(mentions =
      Vector(
        mentionAttempt(0, label = "Man"),
        mentionAttempt(1),
        mentionAttempt(2)
      )
    )
    val foldedModel = folded.validated.getOrElse(fail(folded.validation.report.render))
    assertEquals(foldedModel.graph.entities.size, 1)
    val entity = foldedModel.graph.entities.values.head
    assertEquals(entity.label.value, "Man")
    assertEquals(entity.label.alternatives.map(_._1), Vector("man"))
    assertEquals(entity.mentions.length, 3)

    val split = compile(mentions =
      Vector(
        mentionAttempt(0),
        mentionAttempt(1),
        mentionAttempt(2, label = "dog")
      )
    )
    val splitModel = split.validated.getOrElse(fail(split.validation.report.render))
    assertEquals(splitModel.graph.entities.size, 2)
    assertEquals(splitModel.graph.entities.values.map(_.label.value).toSet, Set("man", "dog"))
    assertEquals(splitModel.graph.relations.participants.size, 3)
    assertEquals(splitModel.trajectory.steps.map(_.entityTurnover), Vector(0.0, 1.0))
    assertEquals(split.entityPartition.size, 1)
  }

  test("permuting the four new attempt vectors changes neither identities nor the fingerprint") {
    val ordinary = compile()
    val permuted = compile(
      mentions = defaultMentions.reverse,
      participants = defaultParticipants.reverse,
      coverage = defaultCoverage.reverse,
      temporal = defaultTemporal.reverse,
      chartOrder = charts.reverse
    )

    assertEquals(permuted.fingerprint, ordinary.fingerprint)
    assertEquals(permuted, ordinary)
    assertEquals(permuted.draft.graph.entities.keySet, ordinary.draft.graph.entities.keySet)
  }

  test("an accepted empty coverage yields a zero-turnover step; withholding it yields a gap") {
    val emptyCoverage = Vector(0, 1, 2).map(i => coverageAttempt(i, Vector.empty))
    val stepped =
      compile(mentions = Vector.empty, participants = Vector.empty, coverage = emptyCoverage)
    val model = stepped.validated.getOrElse(fail(stepped.validation.report.render))
    assertEquals(model.graph.entities, Map.empty)
    assertEquals(model.trajectory.steps.map(_.entityTurnover), Vector(0.0, 0.0))
    assertEquals(stepped.derivation.gaps, Vector.empty)

    val withheld = compile(
      mentions = Vector.empty,
      participants = Vector.empty,
      coverage = Vector(
        coverageAttempt(0, Vector.empty),
        coverageAttempt(1, Vector.empty, calibrated = false),
        coverageAttempt(2, Vector.empty)
      )
    )
    val gaps = gapsOf(withheld)
    assertEquals(withheld.draft.trajectory, DiscourseTrajectory.empty)
    assertEquals(
      gaps(stepTarget(0, 1)),
      DerivationGapReason.MissingUpstream(Vector(coverageTarget(1)))
    )
    assertEquals(
      gaps(stepTarget(1, 2)),
      DerivationGapReason.MissingUpstream(Vector(coverageTarget(1)))
    )
    assertEquals(withheld.validated, None)
    assertNotEquals(withheld.fingerprint, stepped.fingerprint)
  }

  test("a quantity filler is an entity mention; a predicate filler is refused at the input") {
    val quantityCharts = charts.updated(0, sentences(0).id -> chart(0, ConceptKind.Quantity))
    val result = compile(
      mentions = Vector(
        mentionAttempt(0, entityType = EntityType.Custom("chart", "quantity")),
        mentionAttempt(1),
        mentionAttempt(2)
      ),
      chartOrder = quantityCharts
    )
    val model = result.validated.getOrElse(fail(result.validation.report.render))
    assertEquals(
      model.graph.entities.values.map(_.entityType).toSet,
      Set(EntityType.Custom("chart", "quantity"), EntityType.Custom("chart", "entity"))
    )
    assertEquals(model.graph.relations.participants.size, 3)

    val predicateFiller = attemptedInput(
      mentions = defaultMentions :+ mentionAttempt(1, at = Some(s(1))),
      participants = defaultParticipants :+ participantAttempt(0, at = Some(s(1))),
      coverage = Vector(
        coverageAttempt(0, Vector(m(0), s(1))),
        coverageAttempt(1, Vector(m(1))),
        coverageAttempt(2, Vector(m(2)))
      )
    )
    predicateFiller match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(errors.exists(_.message.contains("is not an entity, name, or quantity concept")))
      case Left(other) => fail(other.message)
      case Right(_)    => fail("a predicate filler reached the compiler")
  }

  test("a circumstance becomes an edge on its situation and mints no entity") {
    // Sentence 0's filler stands as a circumstance rather than a participant, which is what the
    // referentiality rule does to a `:time`: the situation keeps the evidence and the entity layer
    // does not grow.
    def compiled(circumstances: Vector[SituationCircumstanceAttempt]) =
      NarrativeCompiler
        .compile(
          attemptedInput(
            mentions = Vector(1, 2).map(i => mentionAttempt(i)),
            participants = Vector(1, 2).map(i => participantAttempt(i)),
            coverage = Vector(
              coverageAttempt(0, Vector.empty),
              coverageAttempt(1, Vector(m(1))),
              coverageAttempt(2, Vector(m(2)))
            ),
            circumstances = circumstances
          ).fold(e => fail(e.message), identity)
        )
        .fold(e => fail(e.message), identity)

    val without = compiled(Vector.empty)
    val with_ = compiled(Vector(circumstanceAttempt(0)))
    val circumstances = with_.draft.graph.relations.circumstances
    assertEquals(without.draft.graph.relations.circumstances, Vector.empty)
    assertEquals(circumstances.size, 1)
    assertEquals(circumstances.head.kind, CircumstanceKind.Time)
    assertEquals(circumstances.head.label, "then")
    // The circumstance carries its own words, and the entity layer is exactly the size it is
    // without it: recording a time never adds a referent.
    assert(circumstances.head.support.refs.toVector.nonEmpty)
    assertEquals(with_.draft.graph.entities.size, without.draft.graph.entities.size)
    assertEquals(with_.draft.graph.relations.participants.size, 2)
  }

  test("input refuses a filler proposed as both a participant and a circumstance") {
    // Without this the same word could be a cast member and a time of one situation, and the model
    // would carry both claims with no way to tell which the source supported.
    val both = attemptedInput(circumstances = Vector(circumstanceAttempt(0)))
    both match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(
          errors.exists(_.message.contains("is both a participant and a circumstance")),
          errors.toVector.map(_.message).mkString("; ")
        )
      case Left(other) => fail(other.message)
      case Right(_)    => fail("a filler was admitted as participant and circumstance at once")
  }

  test("input refuses a circumstance whose situation endpoint is not a situation attempt") {
    val stray = SituationCircumstanceAttempt(
      m(0),
      m(1),
      bundle(CircumstanceProposal(CircumstanceKind.Manner, "thus"), ev(0), "circ-agent", "stray")
    )
    attemptedInput(circumstances = Vector(stray)) match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(
          errors.exists(_.message.contains("unknown situation endpoint")),
          errors.toVector.map(_.message).mkString("; ")
        )
      case Left(other) => fail(other.message)
      case Right(_)    => fail("a circumstance with no situation reached the compiler")
  }
