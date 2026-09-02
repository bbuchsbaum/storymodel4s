package storymodel4s.document

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.proposition.*
import storymodel4s.story.{Polarity as StoryPolarity, *}

class CompilerSuite extends FunSuite:
  private val source = StorySource
    .fromText("Anna entered the room. She rested.", Some("Tiny story"))
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val sentences = atlas.sentences
  private val stage = StageId.unsafe("test-narrative-provider")
  private val extractor = Fingerprint.unsafe("test:narrative-provider:1")
  private val prompt = PromptPackageRef("test-narrative", "1", Checksum.ofText("test-prompt"))

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

  private def evidence(id: String, spans: SpanSet): Evidence =
    Evidence(EvidenceId.unsafe(id), Some(spans), Set.empty, extractor, stage)

  private val ev0 = evidence(
    "ev:situation:0",
    SpanSet.one(SpanRef(Some(sentences(0).id), sentences(0).span))
  )
  private val ev1 = evidence(
    "ev:situation:1",
    SpanSet.one(SpanRef(Some(sentences(1).id), sentences(1).span))
  )
  private def requiredSpans(value: Evidence): SpanSet =
    value.spans.getOrElse(fail(s"${value.id.value} lacks test spans"))
  private val evSummary = evidence("ev:summary", requiredSpans(ev0) ++ requiredSpans(ev1))

  private def chart(index: Int, lemma: String): PropositionEvidence =
    val concept = ConceptId.unsafe(s"predicate:$index")
    val providerCall = call("chart-agent", s"chart:$index")
    val unchecked = PropositionChart.unchecked(
      Some(concept),
      Map(concept -> Concept.predicate(lemma)),
      Vector.empty,
      provenance = ChartProvenance(
        ChartOrigin.Agent(extractor),
        Vector(providerCall),
        Vector.empty
      ),
      sentence = Some(sentences(index).id)
    )
    PropositionEvidence.of(
      ChartValidator.check(unchecked).fold(v => fail(v.toString), identity)
    )

  private val chart0 = chart(0, "enter")
  private val chart1 = chart(1, "rest")
  private val ref0 =
    ChartNodeRef(sentences(0).id, chart0.chart.focus.getOrElse(fail("chart 0 has no focus")))
  private val ref1 =
    ChartNodeRef(sentences(1).id, chart1.chart.focus.getOrElse(fail("chart 1 has no focus")))

  private def bundle[A](
      value: A,
      ev: Evidence,
      provider: String,
      salt: String,
      calibrated: Boolean = true,
      additionalProviders: Vector[String] = Vector.empty,
      evidenceRef: Option[EvidenceRef] = None,
      findings: Vector[CriticFinding] = Vector.empty
  ): EvidenceBundle[A] =
    val proposals = (provider +: additionalProviders).zipWithIndex.map { (name, index) =>
      val task = TaskId.unsafe(s"task:$salt:$index")
      val receipt = AgentCallReceipt(call(name, s"$salt:$index"), prompt, task)
      AgentProposal.proposed(
        task,
        value,
        NonEmptyVector.one(evidenceRef.getOrElse(EvidenceRef.Inline(ev))),
        Some(RawScore.unsafe(0.91)),
        Vector.empty,
        receipt
      )
    }
    EvidenceBundle(
      proposals,
      findings,
      StructuralValidity.Valid,
      SourceSupport(1.0, ev.spans),
      agreementScore = 1.0,
      calibrations =
        if calibrated then Vector(CandidateCalibration(value, Probability.unsafe(0.97), "test-v1"))
        else Vector.empty
    )

  private val situation0 = SituationProposal(
    SituationKind.Event,
    Predicate("enter", None, "enter"),
    "Anna entered the room.",
    StoryPolarity.Positive,
    Modality.Asserted,
    None
  )
  private val situation1 = SituationProposal(
    SituationKind.State,
    Predicate("rest", None, "rest"),
    "She rested.",
    StoryPolarity.Positive,
    Modality.Asserted,
    None
  )
  private val summary = StorySummaryProposal("Anna enters a room and rests.")

  private def evidenceFor(ref: ChartNodeRef): Evidence =
    if ref == ref0 then ev0 else if ref == ref1 then ev1 else fail(s"unknown test ref ${ref.key}")

  private def contextAttempt(ref: ChartNodeRef): ContextAssignmentAttempt =
    ContextAssignmentAttempt(
      ref,
      bundle(
        ContextAssignmentProposal.NarratedWorld,
        evidenceFor(ref),
        "context-agent-a",
        s"context:${ref.key}",
        additionalProviders = Vector("context-agent-b")
      )
    )

  private def membershipAttempt(ref: ChartNodeRef): SegmentMembershipAttempt =
    SegmentMembershipAttempt(
      ref,
      bundle(
        SegmentMembershipProposal.PrimaryStoryMember,
        evidenceFor(ref),
        "membership-agent-a",
        s"membership:${ref.key}",
        additionalProviders = Vector("membership-agent-b")
      )
    )

  /** Every situation attempt carries an accepted, empty participant coverage unless a test says
    * otherwise: the two-sentence fixture has no entity charts, and "no licensed participant" is the
    * evidenced value the compiler needs before it may derive a step.
    */
  private def coverageAttempt(ref: ChartNodeRef): ParticipantCoverageAttempt =
    ParticipantCoverageAttempt(
      ref,
      bundle(ParticipantCoverage.empty, evidenceFor(ref), "coverage-agent", s"coverage:${ref.key}")
    )

  private def attemptedInput(
      situationAttempts: Vector[SituationAttempt],
      chartOrder: Vector[(SurfaceUnitId, PropositionEvidence)] = Vector(
        sentences(0).id -> chart0,
        sentences(1).id -> chart1
      ),
      contextAttempts: Option[Vector[ContextAssignmentAttempt]] = None,
      causal: Vector[CausalAttempt] = Vector.empty,
      summaryAttempt: StorySummaryAttempt = StorySummaryAttempt(
        bundle(summary, evSummary, "summary-agent", "summary")
      ),
      membershipAttempts: Option[Vector[SegmentMembershipAttempt]] = None,
      provenanceCalls: Vector[ProviderCall] = Vector.empty,
      coverageAttempts: Option[Vector[ParticipantCoverageAttempt]] = None,
      temporal: Vector[TemporalAttempt] = Vector.empty
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
      Vector(ev0, ev1, evSummary),
      situationAttempts,
      contextAttempts.getOrElse(situationAttempts.map(a => contextAttempt(a.source))),
      summaryAttempt,
      membershipAttempts.getOrElse(situationAttempts.map(a => membershipAttempt(a.source))),
      causal,
      Vector.empty,
      Vector.empty,
      coverageAttempts.getOrElse(situationAttempts.map(a => coverageAttempt(a.source))),
      temporal,
      AcceptancePolicy.Conservative,
      receipt,
      Provenance(provenanceCalls, StoryModel.SchemaVersion, Checksum.ofText("compiler-test"))
    )

  private def input(
      situationAttempts: Vector[SituationAttempt] = Vector(
        SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0")),
        SituationAttempt(ref1, bundle(situation1, ev1, "situation-agent", "s1"))
      ),
      chartOrder: Vector[(SurfaceUnitId, PropositionEvidence)] = Vector(
        sentences(0).id -> chart0,
        sentences(1).id -> chart1
      ),
      contextAttempts: Option[Vector[ContextAssignmentAttempt]] = None,
      causal: Vector[CausalAttempt] = Vector.empty,
      summaryAttempt: StorySummaryAttempt = StorySummaryAttempt(
        bundle(summary, evSummary, "summary-agent", "summary")
      ),
      membershipAttempts: Option[Vector[SegmentMembershipAttempt]] = None,
      provenanceCalls: Vector[ProviderCall] = Vector.empty,
      coverageAttempts: Option[Vector[ParticipantCoverageAttempt]] = None,
      temporal: Vector[TemporalAttempt] = Vector.empty
  ): NarrativeCompilerInput =
    attemptedInput(
      situationAttempts,
      chartOrder,
      contextAttempts,
      causal,
      summaryAttempt,
      membershipAttempts,
      provenanceCalls,
      coverageAttempts,
      temporal
    )
      .fold(e => fail(e.message), identity)

  private def compile(in: NarrativeCompilerInput): NarrativeCompilation =
    NarrativeCompiler.compile(in).fold(e => fail(e.message), identity)

  test("accepted evidence compiles into a validated story model") {
    val result = compile(
      input(
        situationAttempts = Vector(
          SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0"))
        )
      )
    )
    val model = result.validated.getOrElse(fail(result.validation.report.render))

    assertEquals(model.graph.situations.size, 1)
    assertEquals(model.graph.contexts.values.map(_.kind).toSet, Set(ContextKind.NarratedWorld))
    assertEquals(model.graph.segments.values.map(_.kind).toSet, Set(SegmentKind.Story))
    assertEquals(model.hierarchy.primary.size, 1)
    assertEquals(result.projections.situations.size, 1)
    assertEquals(result.derivation.gaps, Vector.empty)
    assert(!result.isPartial)
  }

  test("proposal and chart order do not change identities or the compilation fingerprint") {
    val attempts = Vector(
      SituationAttempt(
        ref0,
        bundle(
          situation0,
          ev0,
          "situation-agent-a",
          "s0-order",
          additionalProviders = Vector("situation-agent-b")
        )
      ),
      SituationAttempt(
        ref1,
        bundle(
          situation1,
          ev1,
          "situation-agent-a",
          "s1-order",
          additionalProviders = Vector("situation-agent-b")
        )
      )
    )
    val ordinary = compile(input(situationAttempts = attempts))
    val permuted = compile(
      input(
        situationAttempts = attempts.reverse.map(a =>
          a.copy(bundle = a.bundle.copy(proposals = a.bundle.proposals.reverse))
        ),
        chartOrder = Vector(sentences(1).id -> chart1, sentences(0).id -> chart0)
      )
    )

    assertEquals(permuted.fingerprint, ordinary.fingerprint)
    assertEquals(
      permuted.draft.graph.situations.keySet,
      ordinary.draft.graph.situations.keySet
    )
    assertEquals(permuted.derivation, ordinary.derivation)
  }

  test("exact input replay has structural equality and an identical content fingerprint") {
    val first = compile(
      input(
        situationAttempts = Vector(
          SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0"))
        )
      )
    )
    val replayed = compile(
      input(
        situationAttempts = Vector(
          SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0"))
        )
      )
    )

    assertEquals(replayed, first)
    assertEquals(replayed.fingerprint, first.fingerprint)
  }

  test("base provenance call order is canonical in structural replay and fingerprinting") {
    val baseCalls = Vector(call("base-b", "base-b"), call("base-a", "base-a"))
    val oneSituation = Vector(
      SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0"))
    )
    val ordinary = compile(input(situationAttempts = oneSituation, provenanceCalls = baseCalls))
    val reversed = compile(
      input(situationAttempts = oneSituation, provenanceCalls = baseCalls.reverse)
    )

    assertEquals(reversed, ordinary)
    assertEquals(reversed.fingerprint, ordinary.fingerprint)
    assertEquals(
      ordinary.provenance.calls,
      baseCalls.sortBy(NarrativeCompiler.renderProviderCall)
    )
  }

  test("length-framed proposal identity cannot collide on provider punctuation") {
    val left = situation0.copy(
      predicate = situation0.predicate.copy(gloss = "enter|quietly"),
      description = "now"
    )
    val right = situation0.copy(
      predicate = situation0.predicate.copy(gloss = "enter"),
      description = "quietly|now"
    )
    val compiledLeft = compile(
      input(
        situationAttempts = Vector(
          SituationAttempt(ref0, bundle(left, ev0, "situation-agent", "collision"))
        )
      )
    )
    val compiledRight = compile(
      input(
        situationAttempts = Vector(
          SituationAttempt(ref0, bundle(right, ev0, "situation-agent", "collision"))
        )
      )
    )

    assertNotEquals(compiledLeft.derivation.candidateSet, compiledRight.derivation.candidateSet)
    assertNotEquals(compiledLeft.fingerprint, compiledRight.fingerprint)
  }

  test("provider-call prompt version presence remains fingerprint-significant") {
    val oneSituation = Vector(
      SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0"))
    )
    val baseCall = call("base-provider", "option-prompt")
    val absent = compile(
      input(
        situationAttempts = oneSituation,
        provenanceCalls = Vector(baseCall.copy(promptTemplateVersion = None))
      )
    )
    val present = compile(
      input(
        situationAttempts = oneSituation,
        provenanceCalls = Vector(
          baseCall.copy(promptTemplateVersion = Some(PromptTemplateVersion.unsafe("present")))
        )
      )
    )

    assertNotEquals(absent, present)
    assertEquals(absent.draft.graph.situations.keySet, present.draft.graph.situations.keySet)
    assertEquals(absent.draft.graph.contexts.keySet, present.draft.graph.contexts.keySet)
    assertEquals(absent.draft.graph.segments.keySet, present.draft.graph.segments.keySet)
    assertNotEquals(absent.derivation.candidateSet, present.derivation.candidateSet)
    assertNotEquals(absent.fingerprint, present.fingerprint)
  }

  test("critic-finding note presence remains fingerprint-significant") {
    val baseFinding = CriticFinding(
      TaskId.unsafe("task:critic-option-note"),
      CriticFamily.SourceEntailment,
      FindingCode.MissingConcept,
      Vector.empty,
      Vector.empty,
      None,
      None
    )
    def compiled(note: Option[FindingNote]): NarrativeCompilation =
      compile(
        input(
          situationAttempts = Vector(
            SituationAttempt(
              ref0,
              bundle(
                situation0,
                ev0,
                "situation-agent",
                "critic-option-note",
                findings = Vector(baseFinding.copy(note = note))
              )
            )
          )
        )
      )

    val absent = compiled(None)
    val present = compiled(Some(FindingNote.unsafe("present")))

    assertNotEquals(absent, present)
    assertEquals(absent.draft.graph, present.draft.graph)
    assertNotEquals(absent.derivation.candidateSet, present.derivation.candidateSet)
    assertNotEquals(absent.fingerprint, present.fingerprint)
  }

  test("a non-accepted causal candidate is absent and recorded, never emitted at a default") {
    val causalValue = CausalProposal(CausalRelation.Causes)
    val oneProvider = CausalAttempt(
      ref0,
      ref1,
      bundle(causalValue, evSummary, "causal-agent-a", "causal")
    )
    val withoutCandidate = compile(input())
    val result = compile(input(causal = Vector(oneProvider)))

    assertEquals(result.draft.graph.relations.causal, Vector.empty)
    val causalGaps = result.derivation.gaps.filter(_.family == ClaimFamily.CausalEdge)
    assertEquals(causalGaps.size, 1)
    causalGaps.head.reason match
      case DerivationGapReason.Unresolved(_) => ()
      case other                             => fail(other.render)
    assert(result.isPartial)
    assertNotEquals(result.fingerprint, withoutCandidate.fingerprint)
  }

  test("a gap retains ledger-backed evidence after compiler input disposal") {
    val causalValue = CausalProposal(CausalRelation.Causes)
    val result = compile(
      input(causal =
        Vector(
          CausalAttempt(
            ref0,
            ref1,
            bundle(
              causalValue,
              evSummary,
              "causal-agent-a",
              "causal-by-id",
              evidenceRef = Some(EvidenceRef.ById(evSummary.id))
            )
          )
        )
      )
    )
    val gap = result.derivation.gaps
      .find(_.family == ClaimFamily.CausalEdge)
      .getOrElse(fail("missing causal gap"))

    assertEquals(gap.evidence, Vector(EvidenceRef.ById(evSummary.id)))
    assertEquals(result.evidenceLedger.get(evSummary.id), Some(evSummary))
  }

  test("accepted context evidence and its derived upstream claim remain auditably closed") {
    val byIdContext = ContextAssignmentAttempt(
      ref0,
      bundle(
        ContextAssignmentProposal.NarratedWorld,
        ev0,
        "context-agent-a",
        "context-by-id",
        additionalProviders = Vector("context-agent-b"),
        evidenceRef = Some(EvidenceRef.ById(ev0.id))
      )
    )
    val result = compile(
      input(
        situationAttempts = Vector(
          SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0"))
        ),
        contextAttempts = Some(Vector(byIdContext))
      )
    )
    val contextClaim = result.derivation.attempts
      .collectFirst {
        case DerivationAttempt(
              _,
              ClaimFamily.ContextAssignment,
              DerivationDisposition.Emitted(id)
            ) =>
          id
      }
      .getOrElse(fail("missing emitted context-assignment claim"))
    val contextMeta = result.derivation.emittedClaims
      .getOrElse(contextClaim, fail("context ClaimMeta was not retained"))
    val rootMeta = result.draft.graph.contexts.values.headOption
      .map(_.meta)
      .getOrElse(fail("missing derived root context"))
    val rootUpstream = rootMeta.evidence.toVector.flatMap(_.upstream).toSet

    assertEquals(result.evidenceLedger.get(ev0.id), Some(ev0))
    assertEquals(contextMeta.evidence.toVector, Vector(ev0))
    assertEquals(contextMeta.status, EpistemicStatus.Hypothesized)
    assertEquals(rootUpstream, Set(contextClaim))
    assert(rootUpstream.subsetOf(result.derivation.emittedClaims.keySet))
  }

  test("accepted provider causal relations retain the conservative epistemic floor") {
    val causalValue = CausalProposal(CausalRelation.Causes)
    val result = compile(
      input(causal =
        Vector(
          CausalAttempt(
            ref0,
            ref1,
            bundle(
              causalValue,
              evSummary,
              "causal-agent-a",
              "causal-accepted",
              additionalProviders = Vector("causal-agent-b")
            )
          )
        )
      )
    )
    val edge = result.draft.graph.relations.causal.headOption
      .getOrElse(fail("accepted causal proposal emitted no edge"))

    assertEquals(edge.meta.status, EpistemicStatus.Hypothesized)
  }

  test("the compilation fingerprint includes hidden emitted claim metadata") {
    val compilerInput = input(
      situationAttempts = Vector(
        SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0"))
      )
    )
    val result = compile(compilerInput)
    val contextClaim = result.derivation.attempts
      .collectFirst {
        case DerivationAttempt(
              _,
              ClaimFamily.ContextAssignment,
              DerivationDisposition.Emitted(id)
            ) =>
          id
      }
      .getOrElse(fail("missing emitted context-assignment claim"))
    val changedContextMeta = result.derivation
      .emittedClaims(contextClaim)
      .withStatus(EpistemicStatus.LinguisticallyEntailed)
      .fold(e => fail(e.message), identity)
    val changedDerivation = DerivationReceipt
      .of(
        result.derivation.candidateSet,
        result.derivation.attempts,
        result.derivation.emittedClaims.updated(contextClaim, changedContextMeta),
        result.derivation.gaps
      )
      .fold(e => fail(e.message), identity)
    val changedFingerprint = NarrativeCompiler.fingerprint(
      compilerInput,
      changedDerivation,
      result.draft.graph,
      result.draft.hierarchy,
      result.draft.trajectory,
      result.validation
    )

    assertNotEquals(changedFingerprint, result.fingerprint)
  }

  test("uncalibrated situation proposals remain gaps and cannot reach AlignmentSource") {
    val unresolved = Vector(
      SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "u0", calibrated = false)),
      SituationAttempt(ref1, bundle(situation1, ev1, "situation-agent", "u1", calibrated = false))
    )
    val result = compile(input(situationAttempts = unresolved))

    assertEquals(result.draft.graph.situations, Map.empty)
    assertEquals(result.validated, None)
    assert(
      result.derivation.gaps.exists(
        _.reason == DerivationGapReason.Unresolved(
          ResolutionFailure.Uncalibrated
        )
      )
    )
  }

  test("an atlas alone cannot manufacture a nonempty narrative graph") {
    val task = TaskId.unsafe("task:summary-abstain")
    val emptySummary = StorySummaryAttempt(
      EvidenceBundle(
        Vector(
          AgentProposal.abstained(task, AgentCallReceipt(call("summary", "none"), prompt, task))
        ),
        Vector.empty,
        StructuralValidity.Valid,
        SourceSupport(0.0, None),
        0.0,
        Vector.empty
      )
    )
    val result = compile(input(situationAttempts = Vector.empty, summaryAttempt = emptySummary))

    assertEquals(result.draft.graph.situations, Map.empty)
    assertEquals(result.draft.graph.relations, RelationLayers.empty)
    assertEquals(result.validated, None)
    assert(result.derivation.gaps.nonEmpty)
  }

  test("input refuses an atlas from another source before resolution") {
    val foreign = StorySource.fromText("A different story.").fold(e => fail(e.message), identity)
    val receipt = BuildReceipt(
      foreign.id,
      foreign.canonicalChecksum,
      StoryModel.SchemaVersion,
      Vector.empty,
      0L
    )
    val attempted = NarrativeCompilerInput.of(
      foreign,
      atlas,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      StorySummaryAttempt(
        EvidenceBundle(
          Vector.empty,
          Vector.empty,
          StructuralValidity.Valid,
          SourceSupport(0.0, None),
          0.0,
          Vector.empty
        )
      ),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      AcceptancePolicy.Conservative,
      receipt,
      Provenance.deterministic(StoryModel.SchemaVersion, Checksum.ofText("foreign"))
    )

    assert(attempted.isLeft)
  }

  test("input rejects a state proposal whose event aspect would otherwise be erased") {
    val malformed = situation1.copy(aspect = Some(Aspect.Imperfective))
    val attempted = attemptedInput(
      situationAttempts = Vector(
        SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0")),
        SituationAttempt(ref1, bundle(malformed, ev1, "situation-agent", "s1"))
      )
    )

    attempted match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(errors.exists(_.message.contains("state proposals cannot carry event aspect")))
      case Left(other) => fail(other.message)
      case Right(_)    => fail("state aspect reached the compiler")
  }

  test("input rejects a modality that requires an unsupported child context") {
    val reported = situation0.copy(modality = Modality.Reported)
    val attempted = attemptedInput(
      situationAttempts = Vector(
        SituationAttempt(ref0, bundle(reported, ev0, "situation-agent", "reported"))
      )
    )

    attempted match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(errors.exists(_.message.contains("must have Asserted modality")))
      case Left(other) => fail(other.message)
      case Right(_)    => fail("reported content reached the narrated-world compiler slice")
  }

  test("input rejects non-finite source support before resolution") {
    val malformed = bundle(situation0, ev0, "situation-agent", "s0").copy(
      sourceSupport = SourceSupport(Double.NaN, ev0.spans)
    )
    val attempted = attemptedInput(
      situationAttempts = Vector(
        SituationAttempt(ref0, malformed),
        SituationAttempt(ref1, bundle(situation1, ev1, "situation-agent", "s1"))
      )
    )

    attempted match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(errors.exists(_.message.contains("source support must be finite")))
      case Left(other) => fail(other.message)
      case Right(_)    => fail("non-finite source support reached the resolver")
  }

  test("unresolved candidate evidence contributes to the compilation fingerprint") {
    val causalValue = CausalProposal(CausalRelation.Causes)
    val first = compile(
      input(causal =
        Vector(CausalAttempt(ref0, ref1, bundle(causalValue, evSummary, "causal", "a")))
      )
    )
    val revised = compile(
      input(causal =
        Vector(CausalAttempt(ref0, ref1, bundle(causalValue, evSummary, "causal", "b")))
      )
    )

    assertEquals(first.draft.graph.relations.causal, Vector.empty)
    assertEquals(revised.draft.graph.relations.causal, Vector.empty)
    assertNotEquals(first.derivation.candidateSet, revised.derivation.candidateSet)
    assertNotEquals(first.fingerprint, revised.fingerprint)
  }

  test("an unresolved root assignment blocks situation emission and validation") {
    val unresolvedContext = ContextAssignmentAttempt(
      ref0,
      bundle(
        ContextAssignmentProposal.NarratedWorld,
        ev0,
        "context-agent",
        "unresolved-context",
        calibrated = false
      )
    )
    val result = compile(
      input(
        situationAttempts = Vector(
          SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0"))
        ),
        contextAttempts = Some(Vector(unresolvedContext))
      )
    )

    assertEquals(result.draft.graph.situations, Map.empty)
    assertEquals(result.draft.graph.contexts, Map.empty)
    assertEquals(result.validated, None)
    assert(
      result.derivation.gaps.exists(g =>
        g.family == ClaimFamily.ContextAssignment &&
          g.reason == DerivationGapReason.Unresolved(ResolutionFailure.Uncalibrated)
      )
    )
  }

  test("a non-accepted primary membership emits no default containment edge") {
    val oneProviderMembership = SegmentMembershipAttempt(
      ref0,
      bundle(
        SegmentMembershipProposal.PrimaryStoryMember,
        ev0,
        "membership-agent-a",
        "one-provider-membership"
      )
    )
    val result = compile(
      input(
        situationAttempts = Vector(
          SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0"))
        ),
        membershipAttempts = Some(Vector(oneProviderMembership))
      )
    )

    assertEquals(result.draft.hierarchy.containment, Vector.empty)
    assertEquals(result.validated, None)
    assert(
      result.derivation.gaps.exists(g =>
        g.family == ClaimFamily.SegmentMembership &&
          g.reason == DerivationGapReason.Unresolved(
            ResolutionFailure.InsufficientAgreement(1, 2)
          )
      )
    )
  }

  test("a multi-situation compilation without a temporal claim yields no trajectory step") {
    val result = compile(input())

    assertEquals(result.draft.trajectory, DiscourseTrajectory.empty)
    assertEquals(result.validated, None)
    val stepGaps = result.derivation.gaps.filter(_.family == ClaimFamily.DiscourseTrajectory)
    assertEquals(
      stepGaps.map(g => g.target -> g.reason),
      Vector(
        NarrativeCandidateAddress.TrajectoryStep(ref0, ref1) ->
          DerivationGapReason.MissingUpstream(
            Vector(NarrativeCandidateAddress.Temporal(ref0, ref1))
          )
      )
    )
    assert(result.validation.report.byLaw.contains("trajectory.complete"))
  }

  test("an accepted Unclear temporal claim between evidenced situations completes the trajectory") {
    val result = compile(
      input(temporal =
        Vector(
          TemporalAttempt(
            ref0,
            ref1,
            bundle(TemporalRelation.Unclear, evSummary, "temporal-agent", "t01")
          )
        )
      )
    )
    val model = result.validated.getOrElse(fail(result.validation.report.render))

    assertEquals(model.trajectory.steps.size, 1)
    val step = model.trajectory.steps.head
    assertEquals(step.worldTime.value, WorldTimeTransition.Unresolved(Vector.empty))
    assertEquals(step.entityTurnover, 0.0)
    assertEquals(step.worldTimeContext, model.graph.rootContext)
    assertEquals(model.graph.relations.temporal.map(_.relation), Vector(TemporalRelation.Unclear))
    assertEquals(model.graph.relations.temporal.head.meta.status, EpistemicStatus.Hypothesized)
    assertEquals(result.derivation.gaps, Vector.empty)
    assert(!result.isPartial)
  }

  test("input refuses evidence spans outside the attempt's sentence, whatever unit they name") {
    val outside = SpanRef(None, sentences(1).span)
    val named = SpanRef(Some(sentences(1).id), sentences(1).span)
    def attemptedWith(ref: SpanRef): Either[NarrativeCompilerError, NarrativeCompilerInput] =
      val stray = evidence("ev:stray", SpanSet.one(ref))
      attemptedInput(
        situationAttempts = Vector(
          SituationAttempt(ref0, bundle(situation0, stray, "situation-agent", "stray"))
        )
      )
    Vector(outside, named).foreach { ref =>
      attemptedWith(ref) match
        case Left(NarrativeCompilerError.InvalidInput(errors)) =>
          assert(errors.exists(_.message.contains("lies outside the attempt's sentence")))
        case Left(other) => fail(other.message)
        case Right(_)    => fail(s"a span outside the sentence reached the compiler via $ref")
    }
  }

  test("input requires exactly one participant-coverage attempt per situation attempt") {
    val attempted = attemptedInput(
      situationAttempts = Vector(
        SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0")),
        SituationAttempt(ref1, bundle(situation1, ev1, "situation-agent", "s1"))
      ),
      coverageAttempts = Some(Vector(coverageAttempt(ref0)))
    )

    attempted match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(errors.exists(_.message.contains("exactly one participant-coverage attempt")))
      case Left(other) => fail(other.message)
      case Right(_)    => fail("a situation without a coverage attempt reached the compiler")
  }

  test("a coverage that lists no situation participant and a lone participant are both refused") {
    val stray = ParticipantCoverageAttempt(
      ref0,
      bundle(
        ParticipantCoverage.of(Vector(ref1)),
        ev0,
        "coverage-agent",
        "stray-coverage"
      )
    )
    val attempted = attemptedInput(
      situationAttempts = Vector(
        SituationAttempt(ref0, bundle(situation0, ev0, "situation-agent", "s0")),
        SituationAttempt(ref1, bundle(situation1, ev1, "situation-agent", "s1"))
      ),
      coverageAttempts = Some(Vector(stray, coverageAttempt(ref1)))
    )

    attempted match
      case Left(NarrativeCompilerError.InvalidInput(errors)) =>
        assert(errors.exists(_.message.contains("without a participant attempt")))
      case Left(other) => fail(other.message)
      case Right(_)    => fail("a coverage naming an unattempted filler reached the compiler")
  }
