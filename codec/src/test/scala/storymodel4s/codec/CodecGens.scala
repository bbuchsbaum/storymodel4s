package storymodel4s.codec

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.features.*
import storymodel4s.proposition.*
import storymodel4s.recall.*
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
    finiteDouble.map(Credence.unsafeRaw),
    for
      s <- finiteDouble
      p <- probability
      m <- ident
    yield Credence.calibrated(s, p, m).toOption.get
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

  val missingReason: Gen[MissingReason] = Gen.oneOf(
    Gen.const(MissingReason.NotInLexicon),
    Gen.const(MissingReason.OutOfVocabulary),
    Gen.const(MissingReason.ProviderAbstained),
    Gen.const(MissingReason.Excluded),
    Gen.const(MissingReason.AllMissing),
    Gen.const(MissingReason.Unknown),
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

  given Arbitrary[TextSpan] = Arbitrary(span)
  given Arbitrary[SpanSet] = Arbitrary(spanSet)
  given Arbitrary[Credence] = Arbitrary(credence)
  given Arbitrary[ClaimMeta] = Arbitrary(claimMeta)
  given Arbitrary[Resolved[String]] = Arbitrary(resolvedString)
  given Arbitrary[Estimate[Double]] = Arbitrary(scoreEstimate)
  given Arbitrary[WorldTimeTransition] = Arbitrary(worldTime)
  given Arbitrary[PropositionChart[Checked]] = Arbitrary(chart)
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
      Credence.unsafeRaw(0.9),
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
          Vector(("Ann", Credence.unsafeRaw(0.2)))
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
        Resolved("whole", meta("root:sum", EpistemicStatus.Hypothesized, None), Vector.empty),
        whole
      ),
      scene -> SegmentNode(
        scene,
        SegmentKind.Scene,
        1,
        Resolved("home", meta("scene:sum", EpistemicStatus.Hypothesized, None), Vector.empty),
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
        ContextKind.Speech(anna),
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
    vectorSpace -> SidecarManifest(vectorSpace, 3, 3, Dtype.Float32, Checksum.ofText("emb"))
  )
  val featureRefs: Vector[FeatureRef] = Vector(
    FeatureRef(FeatureTarget.Situation(s0), vectorSpace, 0),
    FeatureRef(FeatureTarget.Situation(s1), vectorSpace, 1),
    FeatureRef(FeatureTarget.Situation(s2), vectorSpace, 2)
  )

  val draft: StoryModel[ModelStatus.Draft] =
    val trajectory = DiscourseTrajectory.derive(graph, hierarchy, atlas)
    StoryModel.draft(
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
            Vector(("the house was not quiet", Credence.unsafeRaw(0.3)))
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

  lazy val validated: StoryModel[ModelStatus.Validated] =
    val out = StoryValidator.validate(draft, ValidationPolicy.default)
    out.validated.getOrElse(throw new IllegalStateException(out.report.toString))

  /** A small recall graph over its own transcript. */
  val recall: RecallGraph =
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
        "A woman went home",
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
        "Then she slept",
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
    val g = RecallGraph(transcript, ratlas, units, rels)
    RecallGraph.validated(g).fold(es => throw new IllegalStateException(es.toString), identity)

  /** A transcript overlay over the recall transcript: one participant turn covering everything. */
  val transcriptAtlas: TranscriptAtlas =
    val ra = recall.atlas
    val speaker = SpeakerId.unsafe("sp:participant")
    val t = TranscriptAtlas(
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
    TranscriptAtlas.validated(t).fold(e => throw new IllegalStateException(e.message), identity)
