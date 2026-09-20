package storymodel4s.codec

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.acquire.{
  ClaimFamily,
  EvidenceRef,
  FindingCode,
  RejectionReason,
  ResolutionFailure
}
import storymodel4s.core.*
import storymodel4s.document.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.features.*
import storymodel4s.proposition.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.{Checked as RecallChecked}
import storymodel4s.story.*

/** Minimal generators and fixtures for codec laws. Kept local: `laws` depends on `codec`'s peers
  * but not on `codec`, and `codec` must not depend on `laws` (no cycle).
  */
object CodecGens:
  val TextLength = 60

  val fingerprint: Fingerprint = Fingerprint.unsafe("test:codec:0")
  val stage: StageId = StageId.unsafe("codec-test")
  val provenance: Provenance = Provenance.deterministic("0.1.0", Checksum.ofText("cfg"))

  val ident: Gen[String] = for
    n <- Gen.choose(1, 8)
    cs <- Gen.listOfN(n, Gen.alphaNumChar)
  yield cs.mkString

  val span: Gen[TextSpan] = for
    s <- Gen.choose(0, TextLength - 1)
    e <- Gen.choose(s, TextLength)
  yield TextSpan.unsafe(s, e)

  val spanRef: Gen[SpanRef] = for
    u <- Gen.option(ident.map(s => SurfaceUnitId.unsafe("u:" + s)))
    s <- span
  yield SpanRef(u, s)

  val spanSet: Gen[SpanSet] =
    Gen.nonEmptyListOf(spanRef).map(rs => SpanSet.of(rs).get)

  val finiteDouble: Gen[Double] = Gen.oneOf(
    Gen.choose(-1e6, 1e6),
    Gen.oneOf(0.0, -0.0, 1.0, 0.1, 1e-300, Double.MinPositiveValue, Double.MaxValue)
  )

  val probability: Gen[Probability] = Gen.choose(0.0, 1.0).map(Probability.unsafe)

  val credence: Gen[Credence] = Gen.oneOf(
    finiteDouble.map(Credence.unsafeRaw(_, ScorerId.unsafe("test-scorer"))),
    for
      s <- finiteDouble
      p <- probability
      m <- ident
    yield Credence
      .calibrated(s, ScorerId.unsafe("test-scorer"), p, CalibrationModelId.unsafe(m))
      .toOption
      .get
  )

  val status: Gen[EpistemicStatus] = Gen.oneOf(EpistemicStatus.values.toSeq)

  def evidence(withSpans: Boolean): Gen[Evidence] = for
    id <- ident
    s <- if withSpans then spanSet.map(Some(_)) else Gen.option(spanSet)
    up <- Gen.listOf(ident.map(i => ClaimId.unsafe("c:" + i)))
  yield Evidence(EvidenceId.unsafe("e:" + id), s, up.toSet, fingerprint, stage)

  val claimMeta: Gen[ClaimMeta] = for
    id <- ident
    st <- status
    cr <- credence
    first <- evidence(withSpans = st == EpistemicStatus.SurfaceExplicit)
    rest <- Gen.listOf(evidence(withSpans = false))
  yield ClaimMeta.unsafe(
    ClaimId.unsafe("c:" + id),
    st,
    cr,
    NonEmptyVector(first, rest.toVector),
    provenance
  )

  /** Every summary state the wire may carry (ADR 0005 §10): a stated summary and each of the three
    * typed absences, so a decoder that reads the tag and forgets the gap is caught.
    */
  val summaryGap: Gen[SummaryGap] = Gen.oneOf(SummaryGap.values.toSeq)
  lazy val segmentSummary: Gen[SegmentSummary] = Gen.oneOf(
    resolvedString.map(SegmentSummary.Stated(_)),
    summaryGap.map(SegmentSummary.Unsummarized(_))
  )

  val resolvedString: Gen[Resolved[String]] = for
    v <- ident
    m <- claimMeta
    alts <- Gen.listOf(Gen.zip(ident, credence))
  yield Resolved(v, m, alts.toVector)

  val undefinedReason: Gen[UndefinedReason] = Gen.oneOf(
    Gen.const(UndefinedReason.SlopeNeedsTwoPositions),
    Gen.const(UndefinedReason.ZeroTotalWeight),
    Gen.const(UndefinedReason.OutsideKernelSupport),
    Gen.const(UndefinedReason.NotFinite),
    Gen.zip(ident, ident).map(UndefinedReason.Custom.apply)
  )

  val malformedReason: Gen[MalformedReason] = Gen.oneOf(
    Gen.const(MalformedReason.ProviderResult),
    Gen.zip(ident, ident).map(MalformedReason.Custom.apply)
  )

  val missingReason: Gen[MissingReason] = Gen.oneOf(
    Gen.const(MissingReason.NotInLexicon),
    Gen.const(MissingReason.OutOfVocabulary),
    Gen.const(MissingReason.ProviderAbstained),
    Gen.const(MissingReason.Excluded),
    Gen.const(MissingReason.AllMissing),
    Gen.const(MissingReason.InputUnresolved),
    Gen.const(MissingReason.Unknown),
    Gen.zip(ident, ident).map(MissingReason.Custom.apply),
    malformedReason.map(MissingReason.Malformed.apply),
    undefinedReason.map(MissingReason.Undefined.apply)
  )

  def estimate[V](v: Gen[V]): Gen[Estimate[V]] = Gen.oneOf(
    Gen.zip(v, Gen.option(credence)).map(Estimate.Observed.apply),
    missingReason.map(Estimate.Missing.apply)
  )

  val scoreEstimate: Gen[Estimate[Double]] = estimate(finiteDouble)

  def space[V](schema: FeatureValueSchema): Gen[FeatureSpace[V]] = for
    id <- ident
    d <- ident
    n <- Gen.oneOf(true, false)
    pop <- Gen.option(ident)
  yield FeatureSpace[V](FeatureSpaceId.unsafe("fs:" + id), d, schema, None, fingerprint, n, pop)

  /** Tracks over sentence targets with distinct, sorted targets (a validated track). */
  def track[V](schema: FeatureValueSchema, value: Gen[V]): Gen[FeatureTrack[FeatureTarget, V]] =
    for
      sp <- space[V](schema)
      n <- Gen.choose(0, 6)
      ests <- Gen.listOfN(n, estimate(value))
      sups <- Gen.listOfN(n, Gen.option(spanSet))
      covs <- Gen.listOfN(
        n,
        Gen.option(Gen.choose(0, 9).flatMap(e => Gen.choose(0, e).map(o => Coverage.unsafe(e, o))))
      )
    yield
      val targets = (0 until n).map(i =>
        FeatureTarget.Sentence(SurfaceUnitId.unsafe(f"s:$i%03d")): FeatureTarget
      )
      val obs = targets
        .zip(ests)
        .zip(sups)
        .zip(covs)
        .map { case (((t, e), s), c) =>
          FeatureObservation(t, e, s, c)
        }
        .toVector
      FeatureTrack.raw(sp, obs, TrackProvenance(provenance, None))

  val scalarTrack: Gen[FeatureTrack[FeatureTarget, Double]] =
    track(FeatureValueSchema.Scalar(None), finiteDouble)
  val categoricalTrack: Gen[FeatureTrack[FeatureTarget, String]] =
    track(FeatureValueSchema.Categorical(Vector("a", "b")), Gen.oneOf("a", "b"))

  val temporalTag: Gen[TemporalRelationTag] = Gen.oneOf(
    Gen.oneOf(
      TemporalRelationTag.Before,
      TemporalRelationTag.Meets,
      TemporalRelationTag.During,
      TemporalRelationTag.Unclear
    ),
    Gen.zip(ident, ident).map(TemporalRelationTag.Custom.apply)
  )

  val duration: Gen[DurationEstimate] = for
    m <- scoreEstimate
    u <- Gen.oneOf(DurationUnit.values.toSeq)
    cue <- Gen.option(spanSet)
  yield DurationEstimate(m, u, cue)

  val worldTime: Gen[WorldTimeTransition] = Gen.oneOf(
    Gen.const(WorldTimeTransition.Continues),
    Gen.const(WorldTimeTransition.ReturnFromEarlierFrame),
    Gen.const(WorldTimeTransition.SimultaneousThreadSwitch),
    Gen.const(WorldTimeTransition.Atemporal),
    Gen.option(duration).map(WorldTimeTransition.JumpForward.apply),
    Gen.option(duration).map(WorldTimeTransition.JumpBackward.apply),
    Gen
      .listOf(Gen.zip(temporalTag, credence).map(TemporalHypothesis.apply))
      .map(hs => WorldTimeTransition.Unresolved(hs.toVector))
  )

  /** A small valid proposition chart: a predicate with two entity arguments, optional embedding. */
  val chart: Gen[PropositionChart[Checked]] = for
    pred <- ident
    a <- ident
    b <- ident
    neg <- Gen.oneOf(true, false)
    embed <- Gen.oneOf(true, false)
  yield
    val p = ConceptId.unsafe("p")
    val x = ConceptId.unsafe("x")
    val y = ConceptId.unsafe("y")
    val s = ConceptId.unsafe("s")
    val base = Map(
      p -> Concept.predicate("v" + pred, Some(FrameRef("propbank", "v" + pred + "-01", None))),
      x -> Concept.entity("e" + a),
      y -> Concept.entity("e" + b)
    )
    val concepts = if embed then base + (s -> Concept.predicate("say")) else base
    val rels = Vector(
      PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(x)),
      PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(y)),
      PropositionRelation(
        p,
        RoleAssignment.named("time"),
        ConceptTarget.Literal(LiteralValue.Number(BigDecimal("1.50")))
      )
    ) ++ (if embed then Vector(PropositionRelation(s, RoleAssignment.arg(1), ConceptTarget.Node(p)))
          else Vector.empty)
    val polarity = Map(
      p -> (if neg then storymodel4s.proposition.Polarity.Negative
            else storymodel4s.proposition.Polarity.Positive)
    )
    val embedded =
      if embed then Vector(EmbeddedProposition(s, EmbeddingKind.Speech, p)) else Vector.empty
    val focus = if embed then s else p
    ChartValidator
      .check(PropositionChart.unchecked(Some(focus), concepts, rels, polarity, embedded))
      .fold(vs => throw new IllegalStateException(vs.map(_.message).mkString("; ")), identity)

  // ---- derivation record (document / acquire vocabulary) ----------------------------------
  val surfaceUnitId: Gen[SurfaceUnitId] = ident.map(s => SurfaceUnitId.unsafe("u:" + s))
  val conceptId: Gen[ConceptId] = ident.map(ConceptId.unsafe)
  val chartNodeRef: Gen[ChartNodeRef] = for
    unit <- surfaceUnitId
    concept <- conceptId
  yield ChartNodeRef(unit, concept)
  val claimId: Gen[ClaimId] = ident.map(i => ClaimId.unsafe("c:" + i))
  val storyId: Gen[StoryId] = ident.map(i => StoryId.unsafe("story:" + i))
  val checksum: Gen[Checksum] = ident.map(Checksum.ofText)
  val conceptKind: Gen[ConceptKind] = Gen.oneOf(ConceptKind.values.toSeq)
  val findingCode: Gen[FindingCode] = Gen.oneOf(FindingCode.values.toSeq)

  val claimFamily: Gen[ClaimFamily] = Gen.frequency(
    9 -> Gen.oneOf(
      ClaimFamily.ReportedToRootPromotion,
      ClaimFamily.EventCoreference,
      ClaimFamily.RoleReversal,
      ClaimFamily.Polarity,
      ClaimFamily.StrictPrecedence,
      ClaimFamily.CausalEdge,
      ClaimFamily.TargetEpisodeMembership,
      ClaimFamily.EntityMention,
      ClaimFamily.EntityCoreference,
      ClaimFamily.SituationMention,
      ClaimFamily.ParticipantRole,
      ClaimFamily.ParticipantCoverage,
      ClaimFamily.SituationCircumstance,
      ClaimFamily.Modality,
      ClaimFamily.ContextAssignment,
      ClaimFamily.TemporalRelation,
      ClaimFamily.GoalRelation,
      ClaimFamily.StateChange,
      ClaimFamily.Reference,
      ClaimFamily.Boundary,
      ClaimFamily.SegmentMembership,
      ClaimFamily.DiscourseTrajectory,
      ClaimFamily.Summary,
      ClaimFamily.DetailAtom
    ),
    1 -> (for
      ns <- ident
      name <- ident
    yield ClaimFamily.Custom(ns, name))
  )

  val resolutionFailure: Gen[ResolutionFailure] = Gen.oneOf(
    Gen.const(ResolutionFailure.NoProposal),
    Gen.const(ResolutionFailure.Uncalibrated),
    Gen.const(ResolutionFailure.NoSpanEvidence),
    for
      have <- Gen.choose(0, 5)
      need <- Gen.choose(1, 5)
    yield ResolutionFailure.InsufficientAgreement(have, need),
    finiteDouble.map(ResolutionFailure.InsufficientSupport.apply),
    Gen.listOf(findingCode).map(cs => ResolutionFailure.BlockingFinding(cs.toVector))
  )

  val rejectionReason: Gen[RejectionReason] = Gen.oneOf(
    Gen.const(RejectionReason.NoSourceSupport),
    Gen.listOf(ident).map(vs => RejectionReason.StructurallyInvalid(vs.toVector)),
    Gen.listOf(findingCode).map(cs => RejectionReason.BlockingFinding(cs.toVector)),
    for
      p <- probability
      band <- probability
    yield RejectionReason.BelowRejectBand(p, band)
  )

  val domainError: Gen[DomainError] = Gen.oneOf(
    for
      s <- Gen.choose(-3, 10)
      e <- Gen.choose(-3, 10)
      r <- ident
    yield DomainError.InvalidSpan(s, e, r),
    for
      k <- ident
      raw <- ident
      r <- ident
    yield DomainError.InvalidId(k, raw, r),
    finiteDouble.map(DomainError.InvalidProbability.apply),
    for
      p <- ident
      r <- ident
    yield DomainError.InvariantViolation(p, r),
    for
      k <- ident
      id <- ident
    yield DomainError.DuplicateId(k, id),
    for
      k <- ident
      raw <- ident
      r <- ident
    yield DomainError.InvalidFormat(k, raw, r)
  )

  val candidateAddress: Gen[NarrativeCandidateAddress] = Gen.oneOf(
    chartNodeRef.map(NarrativeCandidateAddress.Situation.apply),
    chartNodeRef.map(NarrativeCandidateAddress.ContextAssignment.apply),
    storyId.map(NarrativeCandidateAddress.StorySummary.apply),
    for
      s <- storyId
      m <- chartNodeRef
    yield NarrativeCandidateAddress.SegmentMembership(s, m),
    for
      a <- chartNodeRef
      b <- chartNodeRef
    yield NarrativeCandidateAddress.Causal(a, b),
    for
      a <- chartNodeRef
      b <- chartNodeRef
    yield NarrativeCandidateAddress.TrajectoryStep(a, b),
    chartNodeRef.map(NarrativeCandidateAddress.EntityMention.apply),
    for
      a <- chartNodeRef
      b <- chartNodeRef
    yield NarrativeCandidateAddress.Participant(a, b),
    chartNodeRef.map(NarrativeCandidateAddress.ParticipantCoverage.apply),
    for
      a <- chartNodeRef
      b <- chartNodeRef
    yield NarrativeCandidateAddress.Circumstance(a, b),
    for
      a <- chartNodeRef
      b <- chartNodeRef
    yield NarrativeCandidateAddress.Temporal(a, b),
    chartNodeRef.map(NarrativeCandidateAddress.EntityReference.apply)
  )

  val openReference: Gen[OpenReference] = Gen.oneOf(
    Gen.const(OpenReference.NoAntecedent),
    Gen.const(OpenReference.SeveralAntecedents),
    Gen.oneOf(Person.values.toSeq).map(OpenReference.NeedsSpeechHolder.apply),
    Gen.const(OpenReference.SpeakerGroup),
    Gen.const(OpenReference.NeedsAddressee),
    Gen.oneOf(Person.values.toSeq).map(OpenReference.OutsideSpeech.apply),
    ident.map(OpenReference.UnrecognizedForm.apply)
  )

  val gapReason: Gen[DerivationGapReason] = Gen.oneOf(
    Gen.const(DerivationGapReason.Alternatives),
    Gen.const(DerivationGapReason.MissingRawScore),
    Gen.const(DerivationGapReason.MissingSpanEvidence),
    resolutionFailure.map(DerivationGapReason.Unresolved.apply),
    rejectionReason.map(DerivationGapReason.Rejected.apply),
    Gen.listOf(candidateAddress).map(as => DerivationGapReason.MissingUpstream(as.toVector)),
    for
      a <- chartNodeRef
      b <- chartNodeRef
    yield DerivationGapReason.UnscopableRelation(a, b),
    domainError.map(DerivationGapReason.InvalidAccepted.apply),
    openReference.map(DerivationGapReason.OpenReference.apply)
  )

  val evidenceRef: Gen[EvidenceRef] = Gen.oneOf(
    ident.map(i => EvidenceRef.ById(EvidenceId.unsafe("e:" + i))),
    evidence(withSpans = false).map(EvidenceRef.Inline.apply)
  )

  val derivationGap: Gen[DerivationGap] = for
    family <- claimFamily
    target <- candidateAddress
    reason <- gapReason
    upstream <- Gen.listOf(claimId)
    refs <- Gen.listOf(evidenceRef)
  yield DerivationGap(stage, family, target, reason, upstream.toSet, refs.toVector)

  val derivationAttempt: Gen[DerivationAttempt] = for
    family <- claimFamily
    target <- candidateAddress
    disposition <- Gen.oneOf(
      claimId.map(DerivationDisposition.Emitted.apply),
      gapReason.map(DerivationDisposition.NotEmitted.apply)
    )
  yield DerivationAttempt(target, family, disposition)

  val abstentionReason: Gen[AbstentionReason] = Gen.oneOf(
    Gen.const(AbstentionReason.NoFocus),
    Gen.const(AbstentionReason.NoCoordinationBranch),
    Gen.const(AbstentionReason.NestedCoordination),
    conceptKind.map(AbstentionReason.FocusNotPredicate.apply),
    conceptKind.map(AbstentionReason.BranchNotAdmissible.apply)
  )

  val fillerCounts: Gen[FillerCounts] = for
    a <- Gen.choose(0, 4)
    b <- Gen.choose(0, 4)
    c <- Gen.choose(0, 4)
    d <- Gen.choose(0, 4)
    e <- Gen.choose(0, 4)
    f <- Gen.choose(0, 4)
  yield FillerCounts(a, b, c, d, e, f)

  val sourceRole: Gen[SourceRole] = Gen.oneOf(
    Gen.choose(0, 5).map(SourceRole.Numbered.apply),
    ident.map(SourceRole.Named.apply),
    Gen.choose(1, 5).map(SourceRole.Operand.apply),
    for
      ns <- ident
      n <- ident
    yield SourceRole.Extension(ns, n)
  )

  val coordinatedBranch: Gen[CoordinatedBranch] = Gen.oneOf(
    for
      root <- chartNodeRef
      role <- sourceRole
      counts <- fillerCounts
    yield CoordinatedBranch.Admitted(root, role, counts),
    for
      root <- chartNodeRef
      role <- sourceRole
      reason <- abstentionReason
    yield CoordinatedBranch.Abstained(root, role, reason)
  )

  val sentenceCoverage: Gen[SentenceCoverage] = Gen.oneOf(
    for
      root <- chartNodeRef
      counts <- fillerCounts
    yield SentenceCoverage.Proposed(root, counts),
    for
      anchor <- chartNodeRef
      branches <- Gen.listOf(coordinatedBranch)
    yield SentenceCoverage.Coordinated(anchor, branches.toVector),
    for
      anchor <- chartNodeRef
      reason <- abstentionReason
    yield SentenceCoverage.Abstained(anchor, reason),
    surfaceUnitId.map(SentenceCoverage.EmptyChart.apply),
    surfaceUnitId.map(SentenceCoverage.NoChart.apply)
  )

  val summaryCoverage: Gen[SummaryCoverage] = Gen.oneOf(
    Gen.const(SummaryCoverage.NoTitle),
    Gen.const(SummaryCoverage.TitleUnestablished),
    ident.map(t => SummaryCoverage.Proposed(t, TitleProvenance.CallerSupplied))
  )

  /** A lawful record: attempts with distinct targets, gaps drawn from the `NotEmitted` attempts
    * with their own reasons, and one coverage row per sentence.
    */
  val derivationArtifact: Gen[DerivationArtifact] = for
    story <- storyId
    source <- checksum
    model <- checksum
    fingerprint <- checksum
    candidates <- checksum
    attempts <- Gen.listOf(derivationAttempt).map(_.distinctBy(_.target).toVector)
    gapped <- Gen.someOf(attempts.collect {
      case DerivationAttempt(target, family, DerivationDisposition.NotEmitted(reason)) =>
        (target, family, reason)
    })
    upstreams <- Gen.listOfN(gapped.size, Gen.listOf(claimId))
    refs <- Gen.listOfN(gapped.size, Gen.listOf(evidenceRef))
    coverage <- Gen.listOf(sentenceCoverage).map(_.distinctBy(_.sentence).toVector)
    summary <- summaryCoverage
  yield
    val gaps = gapped.toVector.zip(upstreams).zip(refs).map { case (((t, f, r), up), ev) =>
      DerivationGap(stage, f, t, r, up.toSet, ev.toVector)
    }
    DerivationArtifact
      .of(story, source, model, fingerprint, candidates, attempts, gaps, coverage, summary)
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** Evidence draws both provenance shapes, because they can differ: `of` takes the chart's own
    * origin while `hand` overrides it, so a codec that wrote only the chart would round-trip one
    * and silently relabel the other.
    */
  val propositionEvidence: Gen[PropositionEvidence] =
    for
      ch <- chart
      ev <- Gen.oneOf(PropositionEvidence.of(ch), PropositionEvidence.hand(ch))
    yield ev

  val sketchParticipant: Gen[SketchParticipant] =
    for
      role <- Gen.oneOf[SketchRole](
        SketchRole.Agent,
        SketchRole.Patient,
        SketchRole.Theme,
        SketchRole.Other("topic")
      )
      entity <- Gen.option(Gen.oneOf("re:a", "re:b").map(RecallEntityId.unsafe))
      label <- Gen.oneOf("a woman", "the man", "she", "the door")
      specified <- Gen.oneOf(true, false)
      aliases <- Gen.oneOf(Set.empty[String], Set("woman"), Set("man", "fellow"))
      head <- Gen.oneOf("", "woman", "man")
      modifiers <- Gen.oneOf(Vector.empty[String], Vector("young"), Vector("old", "tall"))
      determiner <- Gen.option(Gen.oneOf(Determiner.values.toSeq))
      number <- Gen.option(Gen.oneOf(MentionNumber.values.toSeq))
    yield SketchParticipant(
      role,
      entity,
      label,
      specified,
      aliases,
      head,
      modifiers,
      determiner,
      number
    )

  val propositionSketch: Gen[PropositionSketch] =
    for
      predicate <- Gen.option(Gen.oneOf("go", "sleep", "open"))
      participants <- Gen.chooseNum(0, 2).flatMap(Gen.listOfN(_, sketchParticipant)).map(_.toVector)
      polarity <- Gen.oneOf(PolarityTag.values.toSeq)
      modality <- Gen.oneOf(ModalityTag.values.toSeq)
      locations <- Gen.oneOf(Vector.empty[String], Vector("home"))
      times <- Gen.oneOf(Vector.empty[String], Vector("then"))
      sensory <- Gen.oneOf(Vector.empty[String], Vector("loud"))
      lemmas <- Gen.oneOf(Set.empty[String], Set("go", "home"))
      outcome <- Gen.option(Gen.oneOf("reached", "failed"))
      cause <- Gen.option(Gen.oneOf("storm", "fear"))
    yield PropositionSketch(
      predicate,
      participants,
      polarity,
      modality,
      locations,
      times,
      sensory,
      lemmas,
      outcome,
      cause
    )

  /** `evidence` is `Some` on three draws in four. It was `None` on every draw for four schema
    * versions because no generator produced it and no law covered it, which is exactly how the
    * codec came to drop the field unnoticed.
    */
  val recallUnit: Gen[RecallUnit] =
    for
      ordinal <- Gen.chooseNum(0, 99)
      support <- spanSet
      text <- Gen.oneOf("A woman went home.", "Then she slept.", "The door opened.")
      function <- Gen.oneOf(DiscourseFunction.values.toSeq)
      pick <- Gen.chooseNum(0, 2)
      cues <- spanSet
      sketch <- propositionSketch
      grounding <- Gen.option(Gen.chooseNum(0.0, 1.0).map(Probability.unsafe))
      evidence <- Gen.frequency(1 -> Gen.const(None), 3 -> propositionEvidence.map(Some(_)))
    yield RecallUnit(
      RecallUnitId.unsafe(s"ru:$ordinal"),
      ordinal,
      support,
      text,
      function,
      pick match
        case 0 => ExpressedUncertainty.Unmarked
        case 1 => ExpressedUncertainty.Hedged(cues)
        case _ => ExpressedUncertainty.Explicit(cues),
      sketch,
      grounding,
      evidence
    )

  given Arbitrary[TextSpan] = Arbitrary(span)
  given Arbitrary[SpanSet] = Arbitrary(spanSet)
  given Arbitrary[Credence] = Arbitrary(credence)
  given Arbitrary[ClaimMeta] = Arbitrary(claimMeta)
  given Arbitrary[Resolved[String]] = Arbitrary(resolvedString)
  given Arbitrary[SegmentSummary] = Arbitrary(segmentSummary)
  given Arbitrary[Estimate[Double]] = Arbitrary(scoreEstimate)
  given Arbitrary[WorldTimeTransition] = Arbitrary(worldTime)
  given Arbitrary[PropositionChart[Checked]] = Arbitrary(chart)
  given Arbitrary[PropositionEvidence] = Arbitrary(propositionEvidence)
  given Arbitrary[RecallUnit] = Arbitrary(recallUnit)
  given Arbitrary[DerivationGap] = Arbitrary(derivationGap)
  given Arbitrary[DerivationAttempt] = Arbitrary(derivationAttempt)
  given Arbitrary[SentenceCoverage] = Arbitrary(sentenceCoverage)
  given Arbitrary[SummaryCoverage] = Arbitrary(summaryCoverage)
  given Arbitrary[DerivationArtifact] = Arbitrary(derivationArtifact)
  given scalarTrackArb: Arbitrary[FeatureTrack[FeatureTarget, Double]] = Arbitrary(scalarTrack)
  given categoricalTrackArb: Arbitrary[FeatureTrack[FeatureTarget, String]] = Arbitrary(
    categoricalTrack
  )

/** A hand-built, validated story model and a recall graph for artifact-level laws. */
object CodecFixture:
  import CodecGens.{fingerprint, provenance, stage}

  val text: String = "Anna went home. She slept there. The house was quiet."
  val source: StorySource = StorySource.fromText(text, Some("Tiny")).toOption.get
  val atlas: SurfaceAtlas = SurfaceAnalyzer.analyze(source)

  private def sp(i: Int): SpanSet =
    SpanSet.one(SpanRef(Some(atlas.sentences(i).id), atlas.sentences(i).span))
  private val whole: SpanSet =
    SpanSet.one(SpanRef(None, TextSpan.unsafe(0, source.canonicalText.length)))

  def meta(id: String, st: EpistemicStatus, spans: Option[SpanSet]): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe("c:" + id),
      st,
      Credence.unsafeRaw(0.9, ScorerId.unsafe("test-scorer")),
      NonEmptyVector.one(
        Evidence(EvidenceId.unsafe("e:" + id), spans, Set.empty, fingerprint, stage)
      ),
      provenance
    )

  val world: ContextId = ContextId.unsafe("ctx:world")
  val speech: ContextId = ContextId.unsafe("ctx:speech")
  val anna: EntityId = EntityId.unsafe("ent:anna")
  val house: EntityId = EntityId.unsafe("ent:house")
  val s0: SituationId = SituationId.unsafe("sit:0")
  val s1: SituationId = SituationId.unsafe("sit:1")
  val s2: SituationId = SituationId.unsafe("sit:2")
  val root: SegmentId = SegmentId.unsafe("seg:root")
  val scene: SegmentId = SegmentId.unsafe("seg:scene")
  val scalarSpace: FeatureSpaceId = FeatureSpaceId.unsafe("fs:imageability")
  val vectorSpace: FeatureSpaceId = FeatureSpaceId.unsafe("fs:emb")

  val graph: NarrativeGraph =
    val entities = Map(
      anna -> EntityNode(
        anna,
        Resolved(
          "Anna",
          meta("anna:label", EpistemicStatus.SurfaceExplicit, Some(sp(0))),
          Vector(("Ann", Credence.unsafeRaw(0.2, ScorerId.unsafe("test-scorer"))))
        ),
        EntityType.Person,
        NonEmptyVector
          .of(MentionId.unsafe[EntityK]("m:anna:0"), MentionId.unsafe[EntityK]("m:anna:1")),
        Vector(
          ScopedAttribute(
            speech,
            "mood",
            "calm",
            meta("anna:attr", EpistemicStatus.Hypothesized, None)
          )
        ),
        sp(0),
        meta("anna", EpistemicStatus.SurfaceExplicit, Some(sp(0)))
      ),
      house -> EntityNode(
        house,
        Resolved(
          "the house",
          meta("house:label", EpistemicStatus.SurfaceExplicit, Some(sp(2))),
          Vector.empty
        ),
        EntityType.Location,
        NonEmptyVector.one(MentionId.unsafe[EntityK]("m:house:0")),
        Vector.empty,
        sp(2),
        meta("house", EpistemicStatus.SurfaceExplicit, Some(sp(2)))
      )
    )
    val situations: Map[SituationId, SituationNode] = Map(
      s0 -> SituationNode.Event(
        EventNode(
          s0,
          Predicate("go", Some("go-02"), "go home"),
          "Anna goes home",
          world,
          storymodel4s.story.Polarity.Positive,
          Modality.Asserted,
          Some(Aspect.Perfective),
          sp(0),
          NonEmptyVector.one(MentionId.unsafe[SituationK]("m:s0")),
          meta("s0", EpistemicStatus.SurfaceExplicit, Some(sp(0)))
        )
      ),
      s1 -> SituationNode.Event(
        EventNode(
          s1,
          Predicate("sleep", None, "sleep"),
          "Anna sleeps",
          speech,
          storymodel4s.story.Polarity.Positive,
          Modality.Reported,
          None,
          sp(1),
          NonEmptyVector.one(MentionId.unsafe[SituationK]("m:s1")),
          meta("s1", EpistemicStatus.SurfaceExplicit, Some(sp(1)))
        )
      ),
      s2 -> SituationNode.State(
        StateNode(
          s2,
          Predicate("quiet", None, "be quiet"),
          "the house is quiet",
          world,
          storymodel4s.story.Polarity.Positive,
          Modality.Asserted,
          sp(2),
          NonEmptyVector.one(MentionId.unsafe[SituationK]("m:s2")),
          meta("s2", EpistemicStatus.SurfaceExplicit, Some(sp(2)))
        )
      )
    )
    val segments = Map(
      root -> SegmentNode(
        root,
        SegmentKind.Story,
        3,
        meta("root:seg", EpistemicStatus.Hypothesized, Some(whole)),
        SegmentSummary.Stated(
          Resolved("whole", meta("root:sum", EpistemicStatus.Hypothesized, None), Vector.empty)
        ),
        whole
      ),
      scene -> SegmentNode(
        scene,
        SegmentKind.Scene,
        1,
        meta("scene:seg", EpistemicStatus.Hypothesized, Some(whole)),
        SegmentSummary.Unsummarized(SummaryGap.NotProposed),
        whole
      )
    )
    val contexts = Map(
      world -> ContextFrame(
        world,
        None,
        ContextKind.NarratedWorld,
        whole,
        meta("ctx:world", EpistemicStatus.StructurallyDerived, None)
      ),
      speech -> ContextFrame(
        speech,
        Some(world),
        ContextKind.Speech(ContextHolder.Named(anna)),
        sp(1),
        meta("ctx:speech", EpistemicStatus.SurfaceExplicit, Some(sp(1)))
      )
    )
    val relations = RelationLayers(
      participants = Vector(
        ParticipantEdge(
          s0,
          ParticipantRole.Agent,
          anna,
          meta("p0", EpistemicStatus.SurfaceExplicit, Some(sp(0)))
        ),
        ParticipantEdge(
          s1,
          ParticipantRole.Agent,
          anna,
          meta("p1", EpistemicStatus.SurfaceExplicit, Some(sp(1)))
        ),
        ParticipantEdge(
          s2,
          ParticipantRole.Theme,
          house,
          meta("p2", EpistemicStatus.SurfaceExplicit, Some(sp(2)))
        ),
        ParticipantEdge(
          s0,
          ParticipantRole.Custom("vn", "Goal"),
          house,
          meta("p3", EpistemicStatus.WorldKnowledgeInferred, None)
        )
      ),
      temporal = Vector(
        TemporalEdge(
          s0,
          TemporalRelation.Before,
          s2,
          world,
          meta("t0", EpistemicStatus.LinguisticallyEntailed, None)
        )
      ),
      causal = Vector(
        CausalEdge(s0, CausalRelation.Enables, s2, meta("c0", EpistemicStatus.Hypothesized, None))
      ),
      goals = Vector(
        GoalEdge(s0, GoalRelation.Motivates, s1, meta("g0", EpistemicStatus.Hypothesized, None))
      ),
      stateChanges = Vector(
        StateChangeEdge(
          s0,
          StateChangeKind.Initiates,
          s2,
          meta("sc0", EpistemicStatus.Hypothesized, None)
        )
      ),
      references = Vector(
        ReferenceEdge(
          s1,
          NarrativeReference.Retrospective,
          s0,
          meta("r0", EpistemicStatus.Hypothesized, None)
        )
      ),
      entityRelations = Vector(
        EntityEdge(
          anna,
          EntityRelation.Custom("kin", "lives-in"),
          house,
          meta("er0", EpistemicStatus.Hypothesized, None)
        )
      )
    )
    NarrativeGraph(entities, situations, segments, contexts, relations)

  val hierarchy: NarrativeHierarchy = NarrativeHierarchy(
    Vector(
      ContainmentEdge(
        NarrativeMember.Segment(scene),
        root,
        HierarchyKind.PrimarySegmentation,
        1.0,
        meta("h:root", EpistemicStatus.StructurallyDerived, None)
      ),
      ContainmentEdge(
        NarrativeMember.Situation(s0),
        scene,
        HierarchyKind.PrimarySegmentation,
        1.0,
        meta("h:s0", EpistemicStatus.StructurallyDerived, None)
      ),
      ContainmentEdge(
        NarrativeMember.Situation(s1),
        scene,
        HierarchyKind.PrimarySegmentation,
        1.0,
        meta("h:s1", EpistemicStatus.StructurallyDerived, None)
      ),
      ContainmentEdge(
        NarrativeMember.Situation(s2),
        scene,
        HierarchyKind.PrimarySegmentation,
        1.0,
        meta("h:s2", EpistemicStatus.StructurallyDerived, None)
      )
    ),
    Vector(
      BoundaryBelief(
        atlas.sentences(1).id,
        1,
        0.25,
        Some(Probability.unsafe(0.3)),
        NonEmptyVector.one(
          Evidence(EvidenceId.unsafe("e:bb"), Some(sp(1)), Set.empty, fingerprint, stage)
        )
      )
    )
  )

  val featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]] = Map(
    scalarSpace -> FeatureSpace[Double](
      scalarSpace,
      "imageability",
      FeatureValueSchema.Scalar(None),
      None,
      fingerprint,
      false
    ),
    vectorSpace -> FeatureSpace[Vector[Double]](
      vectorSpace,
      "embedding",
      FeatureValueSchema.Vector(3),
      None,
      fingerprint,
      true,
      Some("pop")
    )
  )
  val sidecars: Map[FeatureSpaceId, SidecarManifest] = Map(
    vectorSpace -> SidecarManifest.unsafe(vectorSpace, 3, 3, Dtype.Float32, Checksum.ofText("emb"))
  )
  val featureRefs: Vector[FeatureRef] = Vector(
    FeatureRef.unsafe(FeatureTarget.Situation(s0), vectorSpace, 0),
    FeatureRef.unsafe(FeatureTarget.Situation(s1), vectorSpace, 1),
    FeatureRef.unsafe(FeatureTarget.Situation(s2), vectorSpace, 2)
  )

  val draft: StoryModel[ModelStatus.Draft] =
    val trajectory = DiscourseTrajectory
      .derive(graph, hierarchy, atlas)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    StoryModel
      .draft(
        source,
        atlas,
        graph,
        hierarchy,
        trajectory,
        featureSpaces,
        sidecars,
        featureRefs,
        descriptors = Vector(
          DescriptorClaim(
            root,
            DescriptorKind.Summary,
            "Anna comes home and rests.",
            meta("d0", EpistemicStatus.Hypothesized, None)
          )
        ),
        hypotheses = Vector(
          HypothesisClaim(
            s2,
            Resolved(
              "the house was quiet",
              meta("hyp0", EpistemicStatus.Hypothesized, None),
              Vector(
                ("the house was not quiet", Credence.unsafeRaw(0.3, ScorerId.unsafe("test-scorer")))
              )
            )
          )
        ),
        sensoryProfiles = Map(
          s2 -> Vector(
            SensoryProfile(
              SensoryProfileKind.Expressed,
              Map(
                SensoryModality.Auditory -> Estimate.score(0.8),
                SensoryModality.Visual -> Estimate.missing(MissingReason.NotInLexicon)
              )
            )
          )
        ),
        receipt = Some(
          BuildReceipt(
            source.id,
            source.canonicalChecksum,
            StoryModel.SchemaVersion,
            Vector((stage, Checksum.ofText("stage"))),
            0L
          )
        )
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  lazy val validated: StoryModel[ModelStatus.Validated] =
    val out = StoryValidator.validate(draft, ValidationPolicy.default)
    out.validated.getOrElse(throw new IllegalStateException(out.report.toString))

  /** A small recall graph over its own transcript.
    *
    * The unit texts include their sentence-final punctuation because they must EQUAL the transcript
    * at their spans - RecallGraph.validated enforces it, and the real segmenter produces text that
    * way. Dropping the period here made the fixture an object the model forbids: a unit claiming
    * text that is not the words its span points at, which is the case that lets a trace explain a
    * different string than the one embedded.
    */
  val recall: RecallGraph[RecallChecked] =
    val transcript = StorySource.fromText("A woman went home. Then she slept.").toOption.get
    val ratlas = SurfaceAnalyzer.analyze(transcript)
    def rs(i: Int): SpanSet =
      SpanSet.one(SpanRef(Some(ratlas.sentences(i).id), ratlas.sentences(i).span))
    val woman = RecallEntityId.unsafe("re:woman")
    val u0 = RecallUnitId.unsafe("ru:0")
    val u1 = RecallUnitId.unsafe("ru:1")
    val units = Vector(
      RecallUnit(
        u0,
        0,
        rs(0),
        "A woman went home.",
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch(
          Some("go"),
          Vector(
            SketchParticipant(
              SketchRole.Agent,
              Some(woman),
              "a woman",
              true,
              Set("woman"),
              "woman",
              Vector.empty,
              Some(Determiner.Indefinite),
              Some(MentionNumber.Singular)
            )
          ),
          PolarityTag.Positive,
          ModalityTag.Asserted,
          Vector("home"),
          Vector.empty,
          Vector.empty,
          Set("go", "home", "woman")
        ),
        Some(Probability.unsafe(0.9))
      ),
      RecallUnit(
        u1,
        1,
        rs(1),
        "Then she slept.",
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Hedged(rs(1)),
        PropositionSketch(
          Some("sleep"),
          Vector(SketchParticipant(SketchRole.Agent, Some(woman), "she", true, Set("woman"))),
          PolarityTag.Positive,
          ModalityTag.Asserted,
          Vector.empty,
          Vector("then"),
          Vector.empty,
          Set("sleep"),
          None,
          Some("tired")
        ),
        None
      )
    )
    val rels = RecallRelations(
      Vector(
        RecallTemporalEdge(u0, RecallTemporalRelation.Before, u1, Some(ratlas.sentences(1).span))
      ),
      Vector(RecallCausalEdge(u0, u1, None)),
      Vector(RecallEntity(woman, "woman", Vector(ratlas.sentences(0).span))),
      Vector(ElaborationEdge(u0, u1)),
      Vector(RecallCorefLink(ratlas.sentences(1).span, woman, RecallCorefKind.Pronoun))
    )
    RecallGraph
      .validated(transcript, ratlas, units, rels)
      .fold(es => throw new IllegalStateException(es.toString), identity)

  /** A transcript overlay over the recall transcript: one participant turn covering everything. */
  val transcriptAtlas: TranscriptAtlas =
    val ra = recall.atlas
    val speaker = SpeakerId.unsafe("sp:participant")
    TranscriptAtlas.unsafe(
      ra,
      Vector(
        TranscriptTurn(
          TurnId.unsafe("turn:0"),
          speaker,
          SpanSet.one(TextSpan.unsafe(0, ra.source.canonicalText.length)),
          Some(AudioSpan.of(0L, 4200L).toOption.get),
          Some(InterviewPhase.FreeRecall),
          Some(PromptId.unsafe("prompt:cue"))
        )
      ),
      Map(speaker -> SpeakerRole.Participant)
    )
