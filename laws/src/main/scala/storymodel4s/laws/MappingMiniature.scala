package storymodel4s.laws

import cats.data.NonEmptyVector
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*

/** Experimental, project-authored diagnostic data. This transcription is checked against the
  * admitted JSON fixture; its alternatives are authored controls, not model estimates.
  */
object MappingMiniature:
  val fixtureSha256 = "be3f8c3d8b4595cd4e58ba0ef8135f26b702cc9c470f4d0a0b2ed8f17d742fc3"
  val schema = "storymodel4s.bench.baseline-miniatures/v1"
  val meaning =
    "Independently authored contract fixtures and expected alternatives, not mapper predictions or calibration data."
  val textId = "synthetic-text-v1"
  val textJoin = "newline"
  val videoId = "synthetic-two-part-v1"
  val mediaBytes = "none: invented annotations and coordinates, not an acquired film"

  final case class Event(id: String, order: Int, text: String)
  final case class Part(id: String, axis: String, ticksPerSecond: String, durationTicks: String)
  final case class Locus(
      event: String,
      part: Option[String],
      startTick: Option[String],
      endTick: Option[String]
  )
  final case class Packet(
      id: String,
      text: String,
      admissible: Vector[String],
      onsetSeconds: Option[String]
  )

  val events: Vector[Event] = Vector(
    Event("e1", 1, "A courier folds a silver map."),
    Event("e2", 2, "A mechanic lifts a violet lantern."),
    Event("e3", 3, "A baker locks a round tin."),
    Event("e4", 4, "A pilot counts five copper pins."),
    Event("e5", 5, "A gardener rinses a blue cup."),
    Event("e6", 6, "A diver unties a green ribbon."),
    Event("e7", 7, "A painter rolls a striped rug."),
    Event("e8", 8, "A nurse closes an empty crate.")
  )
  val parts: Vector[Part] = Vector(
    Part("part-a", "axis-a", "10", "40"),
    Part("part-b", "axis-b", "10", "40")
  )
  val loci: Vector[Locus] = Vector(
    Locus("e1", Some("part-a"), Some("0"), Some("10")),
    Locus("e2", Some("part-a"), Some("10"), Some("20")),
    Locus("e3", Some("part-a"), Some("20"), Some("30")),
    Locus("e4", None, None, None),
    Locus("e5", Some("part-b"), Some("0"), Some("10")),
    Locus("e6", Some("part-b"), Some("10"), Some("20")),
    Locus("e7", Some("part-b"), Some("20"), Some("30")),
    Locus("e8", Some("part-b"), Some("30"), Some("30"))
  )
  val groupId = "g1"
  val groupMembers = Vector("e2", "e4")
  val groupCompletePlaybackSupport = false
  val packets: Vector[Packet] = Vector(
    Packet("p1", "The mechanic lifted the violet lantern.", Vector("e2"), Some("1.0")),
    Packet("p2", "The painter rolled the striped rug.", Vector("e7"), Some("2.0")),
    Packet("p3", "The baker locked the round tin.", Vector("e3"), None),
    Packet("p4", "The nurse closed the empty crate.", Vector("e8"), Some("4.0")),
    Packet("p5", "I replaced the batteries in my desk clock.", Vector("external"), Some("5.0")),
    Packet("p6", "The diver untied the green ribbon.", Vector("e6"), Some("6.0")),
    Packet("p7", "Someone closed a container.", Vector("e3", "e8"), Some("7.0")),
    Packet("p8", "The mechanic lifted that violet lantern again.", Vector("e2"), Some("8.0"))
  )
  val clearPath = Vector("e2", "e7", "e3", "e8")
  val clearBackward = 1
  val clearForward = 2
  val revisitPacket = "p8"
  val externalPacket = "p5"
  val missingTimingPacket = "p3"
  val failurePacket = "p6"
  val failureStatus = "failed"
  val failureSelected: Option[String] = None
  val disagreementPacket = "p3"
  val argmax = "e3"
  val decoded = "e7"
  val disagreementMeaning = "deliberately different reconstruction choice; not a second observation"
  val partialSupportTarget = "g1"
  val ambiguousPacket = "p7"

  /** Build a fresh, checked record using only public producers. No estimator or media is run. */
  def record: MappingResult =
    def target(id: String): SourceNodeRef = SourceNodeRef.Situation(SituationId.unsafe(id))
    val group = SourceNodeRef.Segment(SegmentId.unsafe(groupId))
    val bundles = parts.map { part =>
      part.id -> SourceBundle
        .filmEdition(
          EditionId.unsafe(part.id),
          Checksum.ofText(s"synthetic-${part.id}"),
          0L,
          part.durationTicks.toLong,
          RationalTimebase.of(1L, part.ticksPerSecond.toLong).toOption.get
        )
        .toOption
        .get
    }.toMap
    val nodes = events.map { event =>
      NodeSummary(
        target(event.id),
        0,
        Option.when(groupMembers.contains(event.id))(group),
        event.order - 1,
        SpanSet.one(TextSpan.unsafe(event.order - 1, event.order)),
        None,
        Vector.empty,
        ContextTag.NarratedWorld,
        PolarityTag.Unknown,
        ModalityTag.Unknown,
        Vector.empty,
        Set.empty
      )
    } :+ NodeSummary(
      group,
      1,
      None,
      1,
      SpanSet.one(TextSpan.unsafe(1, 4)),
      None,
      Vector.empty,
      ContextTag.NarratedWorld,
      PolarityTag.Unknown,
      ModalityTag.Unknown,
      Vector.empty,
      Set.empty
    )
    val view = InMemorySourceView(nodes, Map.empty, None, events.size)
    val physical = loci.map { locus =>
      val status = locus.part match
        case None     => SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource)
        case Some(id) =>
          val bundle = bundles(id)
          val start = locus.startTick.get.toLong
          val end = locus.endTick.get.toLong
          val anchor =
            if start == end then
              EvidenceAnchor.MediaPoint(
                bundle.id,
                bundle.streams.head.id,
                PlaybackInstant.on(bundle.primaryAxis, start).toOption.get
              )
            else
              EvidenceAnchor.MediaTime(
                bundle.id,
                bundle.streams.head.id,
                bundle.primaryAxis.id,
                PlaybackIntervalSet.one(
                  PlaybackInterval.on(bundle.primaryAxis, start, end).toOption.get
                )
              )
          SourceSupportStatus.located(
            TypedSupport.Anchored(EvidenceSupport.of(bundle, Vector(anchor)).toOption.get)
          )
      target(locus.event) -> status
    }.toMap
    val source = SourceRepresentation
      .of(
        view,
        NonEmptyVector.fromVector(parts.map(p => BundleEntry.media(bundles(p.id)))).get,
        None,
        physical.updated(group, physical(target("e2")))
      )
      .toOption
      .get

    // Separators are parsed words deliberately outside every unit: the inventory retains them.
    val separator = "\n|\n"
    val recallText = packets.map(_.text).mkString(separator)
    val transcript = StorySource.fromText(recallText).toOption.get
    val atlas = SurfaceAnalyzer.analyze(transcript)
    val starts = packets
      .scanLeft(0)((offset, packet) => offset + packet.text.length + separator.length)
      .dropRight(1)
    val units = packets.zip(starts).zipWithIndex.map { case ((packet, start), ordinal) =>
      RecallUnit(
        RecallUnitId.unsafe(packet.id),
        ordinal,
        SpanSet.one(TextSpan.unsafe(start, start + packet.text.length)),
        packet.text,
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch(
          None,
          Vector.empty,
          PolarityTag.Unknown,
          ModalityTag.Unknown,
          Vector.empty,
          Vector.empty,
          Vector.empty,
          Set.empty
        ),
        None
      )
    }
    val recall = RecallGraph.validated(transcript, atlas, units, RecallRelations.empty).toOption.get
    val inventory = RecallInventory
      .of(
        recall,
        "\\S+".r
          .findAllMatchIn(recallText)
          .map(m => TextSpan.unsafe(m.start, m.end))
          .toVector,
        WordIdPolicy.inputArtifact(Checksum.ofText(recallText))
      )
      .toOption
      .get
    val ledger = StageLedger
      .of(Stage.values.toVector.map { stage =>
        StageEntry
          .of(stage, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
          .toOption
          .get
      })
      .toOption
      .get
    def stage(value: Stage): StageEntryId = ledger.at(value).head.id
    val stages = UnitStageRefs
      .of(ledger, stage(Stage.Inference), stage(Stage.Candidates), stage(Stage.Decision))
      .toOption
      .get
    val policy = DecisionPolicyId.unsafe("synthetic-authored-choice/v1")
    val grain = TargetGrain.Hierarchy(Vector(0, 1))
    val universe = DeclaredUniverse.of(nodes.map(_.ref), grain).toOption.get
    val policies = MappingPolicies
      .of(
        InferencePolicy.Unspecified("Authored synthetic controls; no estimator"),
        ContextPolicy.Unspecified("No context model"),
        CandidatePolicy.Declared(
          CandidatePolicyId.unsafe("synthetic-authored-alternatives/v1"),
          CandidateCoverage.Unknown("Nomination recall is unmeasured")
        ),
        ReferencePrior.NotApplicable("Authored raw scores"),
        DecisionPolicy.Declared(policy),
        universe
      )
      .toOption
      .get
    val roles = UnitRoles
      .of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(grain)
      )
      .toOption
      .get
    val basis = DecisionBasis.of(MeasureKind.RawScore, Some("authored-control")).toOption.get
    val outcomes = packets.map { packet =>
      val unit = RecallUnitId.unsafe(packet.id)
      if packet.id == failurePacket then
        UnitOutcome.failed(unit, ProcessingFailure.ProviderFailure("Synthetic failure control"))
      else
        val values: Map[Destination, Double] = packet.id match
          case `disagreementPacket` =>
            Map(
              Destination.Target(target(argmax)) -> 0.9,
              Destination.Target(target(decoded)) -> 0.4
            )
          case `ambiguousPacket` =>
            packet.admissible.map(id => Destination.Target(target(id)) -> 0.5).toMap
          case `externalPacket` => Map(Destination.External(ExternalState.Intrusion) -> 1.0)
          case _ => packet.admissible.map(id => Destination.Target(target(id)) -> 1.0).toMap
        val scores = RawScores
          .of(
            "authored-control",
            ScoreDirection.HigherIsBetter,
            "arbitrary synthetic control",
            values,
            stage(Stage.Scoring)
          )
          .toOption
          .get
        val measures = UnitMeasures.of(Vector(scores), None, None, None).toOption.get
        val candidates = CandidateSetId.of(stages.candidates, unit, values.keySet)
        val links = values.keys.toVector.sorted.map(MappingLink.ungated(_, stages, candidates))
        val request =
          if packet.id == disagreementPacket then
            DecisionRequest.ExternalDecode(Destination.Target(target(decoded)), policy)
          else DecisionRequest.RawArgmax
        UnitOutcome.computed(unit, measures, links, basis, request, stages).toOption.get
    }
    MappingResult.checked(inventory, source, policies, roles, ledger, outcomes).toOption.get
