package storymodel4s.fixtures

import cats.data.NonEmptyVector
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.SituationK
import storymodel4s.document.{SentenceCoverage, SummaryCoverage}
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.story.*
import storymodel4s.view.*

/** Offline product fixtures, built through public checked APIs. Authored control values are
  * deliberately not empirical observations or calibrated probabilities. The optional third policy
  * is an actual local lexical HSMM result, retaining historical reconstruction authority.
  */
object WorkspaceFixtures:
  private def checked[E, A](value: Either[E, A]): A =
    value.fold(e => throw new IllegalStateException(e.toString), identity)

  private val bellId = SituationId.unsafe("bell:sit:ring")
  private val clockId = SituationId.unsafe("bell:sit:quiet")

  private lazy val bellModel: TextModel[ModelStatus.Draft] =
    val source = checked(
      StorySource.fromText(
        "A bell rang. The clock remained silent. The bell rang again.",
        Some("Bell and clock")
      )
    )
    val atlas = SurfaceAnalyzer.analyze(source)
    def support(indices: Int*): SpanSet = SpanSet
      .of(indices.map(i => SpanRef(Some(atlas.sentences(i).id), atlas.sentences(i).span)))
      .get
    val all = support(0, 1, 2)
    val provenance =
      Provenance.deterministic("workspace-fixtures/v1", Checksum.ofText("authored-bell-control"))
    def meta(key: String, spans: SpanSet): ClaimMeta = ClaimMeta.unsafe(
      ClaimId.unsafe("bell:claim:" + key),
      EpistemicStatus.Hypothesized,
      Credence.unsafeRaw(1.0, ScorerId.unsafe("fixture:authored")),
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe("bell:evidence:" + key),
          Some(spans),
          Set.empty,
          Fingerprint.unsafe("fixture:bell:v1"),
          StageId.unsafe("authored-control")
        )
      ),
      provenance
    )
    val world = ContextId.unsafe("bell:world")
    val root = SegmentId.unsafe("bell:story")
    def event(id: SituationId, lemma: String, description: String, spans: SpanSet): SituationNode =
      SituationNode.Event(
        EventNode(
          id,
          Predicate(lemma, None, description),
          description,
          world,
          Polarity.Positive,
          Modality.Asserted,
          None,
          spans,
          NonEmptyVector.one(MentionId.unsafe[SituationK](id.value + ":mention")),
          meta(id.value, spans)
        )
      )
    val events = Vector(
      event(bellId, "ring", "The bell rang twice", support(0, 2)),
      event(clockId, "remain", "The clock remained silent", support(1))
    )
    val graph = NarrativeGraph(
      Map.empty,
      events.map(e => e.id -> e).toMap,
      Map(
        root -> SegmentNode(
          root,
          SegmentKind.Story,
          1,
          meta("root", all),
          SegmentSummary.Unsummarized(SummaryGap.NotProposed),
          all
        )
      ),
      Map(world -> ContextFrame(world, None, ContextKind.NarratedWorld, all, meta("world", all))),
      RelationLayers.empty
    )
    val hierarchy = NarrativeHierarchy(
      events.map(e =>
        ContainmentEdge(
          NarrativeMember.Situation(e.id),
          root,
          HierarchyKind.PrimarySegmentation,
          1.0,
          meta("contains:" + e.id.value, e.support.textSpans.get)
        )
      ),
      Vector.empty
    )
    checked(
      StoryModel.draftText(
        atlas,
        graph,
        hierarchy,
        checked(DiscourseTrajectory.derive(graph, hierarchy, atlas))
      )
    )

  private def recallFor(text: String): RecallGraph[Checked] =
    val transcript = checked(StorySource.fromText(text, Some("Synthetic recall")))
    val atlas = SurfaceAnalyzer.analyze(transcript)
    val groups = Vector(Vector(0, 2), Vector(3), Vector(4), Vector(5))
    val units = groups.zipWithIndex.map { (indices, ordinal) =>
      val spans = SpanSet
        .of(indices.map(i => SpanRef(Some(atlas.sentences(i).id), atlas.sentences(i).span)))
        .get
      RecallUnit(
        RecallUnitId.unsafe(s"m1:u$ordinal"),
        ordinal,
        spans,
        checked(spans.minSpan.slice(transcript.canonicalText)),
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch.empty,
        None
      )
    }
    checked(RecallGraph.validated(transcript, atlas, units, RecallRelations.empty).toEither)

  private def build(
      draft: TextModel[ModelStatus.Draft],
      recallText: String,
      first: SourceNodeRef,
      second: SourceNodeRef,
      partial: Boolean,
      revision: String,
      includeHistorical: Boolean,
      timed: Boolean
  ): SourceRecallWorkspace =
    val validation = StoryValidator.validate(draft)
    val validated =
      validation.validated.getOrElse(throw new IllegalStateException(validation.report.render))
    val (view, source) = checked(WorkspaceCodecs.sourceFor(validated))
    val recall = recallFor(recallText)
    val inventory = checked(
      RecallInventory.of(
        recall,
        "\\S+".r.findAllMatchIn(recallText).map(m => TextSpan.unsafe(m.start, m.end)).toVector,
        WordIdPolicy.inputArtifact(Checksum.ofText(recallText))
      )
    )
    val grain = TargetGrain.Hierarchy(source.targets.map(_.level).distinct.sorted)
    val universe = checked(DeclaredUniverse.of(source.targets.map(_.ref), grain))
    val ledger = checked(
      StageLedger.of(
        Stage.values.toVector.map(stage =>
          checked(
            StageEntry.of(stage, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
          )
        )
      )
    )
    def stage(value: Stage): StageEntryId = ledger.at(value).head.id
    val stages = checked(
      UnitStageRefs.of(
        ledger,
        stage(Stage.Inference),
        stage(Stage.Candidates),
        stage(Stage.Decision)
      )
    )
    val prior = ReferencePriorId.unsafe("authored-fixture-mass")
    val decoder = DecisionPolicyId.unsafe("authored-second-target")
    val roles = checked(
      UnitRoles.of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(grain)
      )
    )
    def authored(alternate: Boolean): WorkspaceMappingInput =
      val id = ArtifactId.unsafe(if alternate then "authored-b" else "authored-a")
      val policies = checked(
        MappingPolicies.of(
          InferencePolicy.Unspecified("Authored synthetic values; no inference executed"),
          ContextPolicy.Unspecified("Synthetic control; context not modeled"),
          CandidatePolicy.Unknown("Omitted candidate probability is unknown"),
          ReferencePrior.Declared(prior),
          DecisionPolicy.Declared(decoder),
          universe
        )
      )
      val outcomes = recall.ordered.zipWithIndex.map { (unit, ordinal) =>
        if ordinal == 3 then
          UnitOutcome.failed(
            unit.id,
            ProcessingFailure.ProviderFailure("Synthetic failure control")
          )
        else
          val a = Destination.Target(first)
          val b = Destination.Target(second)
          val external = Destination.External(ExternalState.Intrusion)
          val rawValues: Map[Destination, Double] =
            if ordinal == 2 then Map(Destination.External(ExternalState.Association) -> 1.0)
            else if alternate then Map(a -> 0.1, b -> 0.8, external -> 0.1)
            else Map(a -> 0.9, b -> 0.4, external -> 0.2)
          val massValues: Map[Destination, Double] =
            if ordinal == 2 then rawValues
            else if alternate then Map(a -> 0.1, b -> 0.6, external -> 0.3)
            else Map(a -> 0.25, b -> 0.25, external -> 0.5)
          val raw = checked(
            RawScores.of(
              "authored",
              ScoreDirection.HigherIsBetter,
              "synthetic arbitrary units",
              rawValues,
              stage(Stage.Scoring)
            )
          )
          val normalized = checked(
            NormalizedScoreMass.of(universe.id, prior, 1.0, massValues, stage(Stage.Inference))
          )
          val measures = checked(UnitMeasures.of(Vector(raw), Some(normalized), None, None))
          val candidates = CandidateSetId.of(stages.candidates, unit.id, rawValues.keySet)
          val links = rawValues.keys.toVector.sorted.map(MappingLink.ungated(_, stages, candidates))
          val request =
            if ordinal == 0 then DecisionRequest.ExternalDecode(b, decoder)
            else DecisionRequest.RawArgmax
          checked(
            UnitOutcome.computed(
              unit.id,
              measures,
              links,
              checked(DecisionBasis.of(MeasureKind.RawScore, Some("authored"))),
              request,
              stages
            )
          )
      }
      WorkspaceMappingInput(
        id,
        checked(MappingResult.checked(inventory, source, policies, roles, ledger, outcomes)),
        None
      )
    val historical =
      if !includeHistorical then Vector.empty
      else
        val result = checked(
          GraphHsmm.infer(
            recall,
            view,
            Candidates.of(recall.ordered.map(u => u.id -> Vector(first, second)).toMap),
            // Deliberately synthetic profile: obtain source winners for the timed adapter court.
            // This is fixture design, not evidence for a scientifically preferred estimator.
            DefaultLocalCostModel(
              semantic = SemanticDistance.lexicalJaccard,
              externalFloor = if timed then 4.0 else 1.0
            )
          )
        )
        Vector(
          WorkspaceMappingInput(
            ArtifactId.unsafe("historical-lexical"),
            checked(
              HistoricalMapping
                .of(result, recall, view, inventory, source, HistoricalDecode.ArgmaxOnly)
            ),
            Some(result)
          )
        )
    val compilation = Option.when(partial)(
      checked(
        DerivationArtifact.of(
          draft.source.id,
          draft.source.canonicalChecksum,
          StoryModelCodec.contentChecksum(draft),
          Checksum.ofText("authored-bell-compilation-v1"),
          Checksum.ofText("no-parser-candidates"),
          Vector.empty,
          Vector.empty,
          draft.atlas.sentences.map(s => SentenceCoverage.NoChart(s.id)),
          SummaryCoverage.NoTitle
        )
      )
    )
    val clocks = Map[RecallUnitId, WorkspaceTiming](
      recall.ordered(0).id -> WorkspaceTiming.Untimed,
      recall.ordered(1).id -> WorkspaceTiming.Onset(checked(Seconds.of(2.5))),
      recall.ordered(2).id -> WorkspaceTiming.Interval(checked(ClockSpan.of(4.0, 5.0))),
      recall.ordered(3).id -> WorkspaceTiming.Untimed
    )
    val presentation = Option.when(timed) {
      val origin = Checksum.ofText("synthetic-bell-recall-start-zero/v1")
      val declaration = Checksum.ofText("synthetic-bell-independent-word-and-source-clocks/v1")
      val clock = RecallTiming.Clock.declared(
        declaration,
        RecallTiming.ClockKey.unsafe("scripted-word-onsets"),
        declaration,
        RecallTiming.RecordingIdentity.Unestablished,
        RecallTiming.Origin.Declared(origin)
      )
      val supplied = Map(
        inventory.units(1).words.head -> checked(ExactRational.of(5, 2)),
        inventory.units(2).words.head -> checked(ExactRational.of(4, 1))
      )
      val words = checked(
        RecallTiming.checked(
          inventory,
          clock,
          inventory.words.map(w =>
            RecallTiming.Entry(
              w.id,
              clock,
              supplied
                .get(w.id)
                .fold[RecallTiming.Observation](
                  RecallTiming.Observation.Missing(RecallTiming.MissingReason.NotProvided)
                )(at =>
                  RecallTiming.Observation.OnsetOnly(at, RecallTiming.Basis.Estimated(declaration))
                )
            )
          ),
          RecallTiming.Provenance(declaration, RecallTiming.RecordingLink.NotEstablished)
        )
      )
      val timeline = checked(
        SourceTimeline.of(
          source.targets.map { target =>
            val (start, end) =
              if target.ref == first then (5.0, 25.0)
              else if target.ref == second then (10.0, 12.0)
              else (0.0, 30.0)
            SourceTimelineNode(
              target.ref,
              target.level,
              None,
              checked(ClockSpan.of(start, end)),
              "Synthetic presentation: " + target.ref.key
            )
          },
          Vector.empty
        )
      )
      WorkspaceClockInput(
        words,
        timeline,
        checked(Seconds.of(10)),
        declaration,
        WorkspaceClockOrigin.RecallStart(origin),
        WorkspaceClockKind.Synthetic
      )
    }
    checked(
      WorkspaceCodecs.create(
        draft,
        recall,
        inventory,
        compilation,
        Vector(authored(false), authored(true)) ++ historical,
        clocks,
        WorkspaceOrigin.AuthoredFixture,
        revision,
        WorkspaceContentGrant.Granted,
        WorkspaceContentGrant.Granted,
        presentation
      )
    )

  /** All estimator work finishes in the producer; opening the resulting archive never reruns it. */
  def all(
      producerRevision: String,
      includeHistorical: Boolean = true
  ): Vector[(String, SourceRecallWorkspace)] =
    Vector(
      "wog" -> build(
        WarOfTheGhostsModel.draft,
        "Two men hunted. This note is outside the selected evidence. They heard cries. The men heard cries again. I remembered a birthday. I forgot the ending.",
        SourceNodeRef.Situation(WarOfTheGhostsModel.S.huntSeals),
        SourceNodeRef.Situation(WarOfTheGhostsModel.S.hearWarCries),
        false,
        producerRevision,
        includeHistorical,
        false
      ),
      "bell" -> build(
        bellModel,
        "A bell rang. This note is outside the selected evidence. It rang again. I heard it twice. I remembered a birthday. I forgot the ending.",
        SourceNodeRef.Situation(bellId),
        SourceNodeRef.Situation(clockId),
        true,
        producerRevision,
        includeHistorical,
        true
      )
    )
