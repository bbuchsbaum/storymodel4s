package storymodel4s.bench

import cats.data.{NonEmptySet, NonEmptyVector}
import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths, StandardOpenOption}
import storymodel4s.align.*
import storymodel4s.codec.CoreCodecs.given
import storymodel4s.codec.MappingCodecs
import storymodel4s.codec.PropositionCodecs.given
import storymodel4s.core.*
import storymodel4s.proposition.*
import storymodel4s.recall.*

/** Source: original machine-authored synthetic contrast clauses, 2026, Toronto, Canada. Basis:
  * original project contribution under Apache-2.0; no copied edition or participant text.
  * Modifications: canonical predicate/entity inputs supplied independently of surface wording to
  * isolate comparison from extraction. All such inputs are exported, not claimed as extracted.
  * Tradition: none; no traditional or community-owned narrative. Admission: /root/scoring_review,
  * 2026-10-21, original machine-authored edition; text-admission review SHA256
  * f0ff1d23ffd6aaa04def4082be8bfa4477b12748943ed6c0fe53cc2d8a373112.
  *
  * A narrow diagnostic: controlled event/entity correspondence, one atomic root proposition,
  * explicit polarity. A structurally checked chart never proves extraction or world truth.
  */
object ClaimAssessmentContrast:
  private val p = ConceptId.unsafe("predicate")
  private val e = ConceptId.unsafe("entity")
  private val mainEvent = SourceNodeRef.Situation(SituationId.unsafe("controlled:main"))
  private val otherEvent = SourceNodeRef.Situation(SituationId.unsafe("controlled:other"))
  private val bell = EntityId.unsafe("controlled:bell")
  private val lamp = EntityId.unsafe("controlled:lamp")
  private val procedure = Fingerprint.unsafe("controlled-exact-proposition-polarity/v1")
  private val credence = Credence.unsafeRaw(1.0, ScorerId.unsafe("authored-control"))

  /** Supplied experimental correspondence, never inferred from equal labels or nearby time. */
  final case class ControlledClaim(
      chart: PropositionChart[Checked],
      event: SourceNodeRef,
      participants: Map[ConceptId, EntityId]
  )
  final case class ContrastCase(
      id: String,
      text: String,
      recalled: Option[ControlledClaim],
      sources: Vector[ControlledClaim],
      expected: ClaimAssessmentStatus,
      recallState: RecallClaimState = RecallClaimState.Expressed,
      remaining: Set[ClaimAssessmentUnknown] = Set.empty,
      unitGroup: Option[String] = None
  )
  final case class ComparisonResult(
      support: Boolean,
      counter: Boolean,
      unknown: Set[ClaimAssessmentUnknown]
  )

  private def atom(
      predicate: String = "ring",
      entity: EntityId = bell,
      polarity: Polarity = Polarity.Positive,
      event: SourceNodeRef = mainEvent,
      context: Option[EmbeddingKind] = None,
      unknownEntity: Boolean = false,
      alternative: Boolean = false
  ): ControlledClaim =
    val holder = ConceptId.unsafe("holder")
    val alternatives =
      if alternative then Map(ConceptId.unsafe("alternative") -> Concept.property("possible"))
      else Map.empty
    val concepts = Map(
      p -> Concept.predicate(predicate),
      e -> (if unknownEntity then Concept.unknown else Concept.entity("object"))
    ) ++
      context.map(_ => holder -> Concept.predicate("report")) ++ alternatives
    val chart = ChartValidator
      .validate(
        PropositionChart.unchecked(
          Some(p),
          concepts,
          Vector(PropositionRelation(p, RoleAssignment.named("agent"), ConceptTarget.Node(e))),
          Map(p -> polarity),
          context.toVector.map(k => EmbeddedProposition(holder, k, p)),
          provenance = ChartProvenance(ChartOrigin.Agent(procedure), Vector.empty, Vector.empty)
        )
      )
      .toOption
      .get
    ControlledClaim(chart, event, if unknownEntity then Map.empty else Map(e -> entity))

  private val positive = atom()
  private val negative = atom(polarity = Polarity.Negative)
  private val compound = "A bell rang, but a lamp did not shine."

  // Expected statuses are declared independently of the comparison implementation below.
  // Paraphrase equivalence is supplied, not extracted. The disjunction control has an unmodeled
  // extra concept, not a qualified disjunction parser. The perhaps control deliberately withholds
  // chart polarity; it does not claim that modality uncertainty is polarity uncertainty.
  val cases: Vector[ContrastCase] = Vector(
    ContrastCase(
      "exact",
      "A bell rang.",
      Some(positive),
      Vector(positive),
      ClaimAssessmentStatus.Supported
    ),
    ContrastCase(
      "paraphrase-controlled",
      "A bell sounded.",
      Some(positive),
      Vector(positive),
      ClaimAssessmentStatus.Supported
    ),
    ContrastCase(
      "explicit-negation",
      "A bell did not ring.",
      Some(negative),
      Vector(positive),
      ClaimAssessmentStatus.Contradicted
    ),
    ContrastCase(
      "different-predicate",
      "A bell fell.",
      Some(atom(predicate = "fall")),
      Vector(positive),
      ClaimAssessmentStatus.Unresolved
    ),
    ContrastCase(
      "different-participant",
      "A lamp rang.",
      Some(atom(entity = lamp)),
      Vector(positive),
      ClaimAssessmentStatus.Unresolved
    ),
    ContrastCase(
      "different-event",
      "A bell rang at another time.",
      Some(positive),
      Vector(atom(event = otherEvent)),
      ClaimAssessmentStatus.Unresolved
    ),
    ContrastCase(
      "source-missing",
      "A bell rang.",
      Some(positive),
      Vector.empty,
      ClaimAssessmentStatus.Unresolved
    ),
    ContrastCase(
      "extraction-missing",
      "A bell rang.",
      None,
      Vector(positive),
      ClaimAssessmentStatus.ExtractionUnresolved,
      RecallClaimState.ExtractionUnresolved
    ),
    ContrastCase(
      "reviewed-omission",
      "I remember the room.",
      None,
      Vector(positive),
      ClaimAssessmentStatus.NotExpressed,
      RecallClaimState.ReviewedNotExpressed
    ),
    ContrastCase(
      "unknown-participant",
      "Something rang.",
      Some(atom(unknownEntity = true)),
      Vector(positive),
      ClaimAssessmentStatus.ReferenceUnresolved,
      RecallClaimState.ReferenceUnresolved
    ),
    ContrastCase(
      "reported-content",
      "A bell rang.",
      Some(positive),
      Vector(atom(context = Some(EmbeddingKind.Speech))),
      ClaimAssessmentStatus.Unresolved
    ),
    ContrastCase(
      "disjunction",
      "A bell rang or a lamp shone.",
      Some(atom(alternative = true)),
      Vector(positive),
      ClaimAssessmentStatus.Unresolved
    ),
    ContrastCase(
      "unknown-polarity",
      "Perhaps a bell rang.",
      Some(atom(polarity = Polarity.Unknown)),
      Vector(positive),
      ClaimAssessmentStatus.Unresolved
    ),
    ContrastCase(
      "partial-memory-supported",
      compound,
      Some(positive),
      Vector(positive),
      ClaimAssessmentStatus.Supported,
      unitGroup = Some("compound")
    ),
    ContrastCase(
      "partial-memory-contradicted",
      compound,
      Some(atom(predicate = "shine", entity = lamp, polarity = Polarity.Negative)),
      Vector(atom(predicate = "shine", entity = lamp)),
      ClaimAssessmentStatus.Contradicted,
      unitGroup = Some("compound")
    ),
    ContrastCase(
      "source-conflict",
      "A bell rang.",
      Some(positive),
      Vector(positive, negative),
      ClaimAssessmentStatus.Conflicted
    ),
    ContrastCase(
      "support-with-incomplete-context",
      "A bell rang.",
      Some(positive),
      Vector(positive),
      ClaimAssessmentStatus.Unresolved,
      remaining = Set(ClaimAssessmentUnknown.ContextUnresolved)
    )
  )

  /** Strict baseline: mismatches abstain; only explicit opposite polarity can be counterevidence.
    * Unknown references, extra/unmodeled concepts and embedded scope cannot be silently dropped.
    */
  def compare(a: ControlledClaim, b: ControlledClaim): ComparisonResult =
    def unknown(reason: ClaimAssessmentUnknown) = ComparisonResult(false, false, Set(reason))
    def signature(x: ControlledClaim): Option[(String, Vector[(String, EntityId)])] =
      val c = x.chart
      c.focus.flatMap { focus =>
        val rels = c.relationsFrom(focus)
        val entityIds = rels.flatMap(_.to.nodeId).toSet
        val complete = c.concepts.keySet == entityIds + focus && c.predicates == Vector(focus) &&
          rels.nonEmpty && rels.size == c.relations.size &&
          rels.forall(r =>
            r.to.nodeId.exists(id =>
              c.concepts(id).kind == ConceptKind.Entity && x.participants.contains(id)
            )
          ) &&
          x.participants.keySet == entityIds && c.polarity.keySet == Set(focus)
        Option.when(complete)(
          ChartIdentity.conceptKey(c.concepts(focus)) -> rels
            .map(r => ChartIdentity.roleKey(r.role) -> x.participants(r.to.nodeId.get))
            .sortBy((role, id) => (role, id.value))
        )
      }
    if a.event != b.event then unknown(ClaimAssessmentUnknown.ContextUnresolved)
    else if a.chart.embedded.nonEmpty || b.chart.embedded.nonEmpty then
      unknown(ClaimAssessmentUnknown.ContextUnresolved)
    else if a.chart.concepts.values.exists(_.isUnknown) || b.chart.concepts.values
        .exists(_.isUnknown)
    then unknown(ClaimAssessmentUnknown.ReferenceUnresolved)
    else
      (signature(a), signature(b)) match
        case (Some(x), Some(y)) if x == y =>
          val ap = a.chart.polarityOf(a.chart.focus.get)
          val bp = b.chart.polarityOf(b.chart.focus.get)
          if ap == Polarity.Unknown || bp == Polarity.Unknown then
            unknown(ClaimAssessmentUnknown.SemanticComparisonUnavailable)
          else ComparisonResult(ap == bp, ap != bp, Set.empty)
        case _ => unknown(ClaimAssessmentUnknown.SemanticComparisonUnavailable)

  private def chartAt(
      claim: ControlledClaim,
      owner: Checksum,
      spans: SpanSet
  ): ClaimChartReference =
    val c = claim.chart
    val element = AlignmentTarget.Concepts(NonEmptySet.one(c.focus.get))
    val meta = ClaimMeta.unsafe(
      ClaimId.unsafe("controlled:alignment"),
      EpistemicStatus.Hypothesized,
      credence,
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe("controlled:span"),
          Some(spans),
          Set.empty,
          procedure,
          StageId.unsafe("controlled")
        )
      ),
      Provenance.deterministic(procedure.value, Checksum.ofText("controlled"))
    )
    val aligned = ChartValidator
      .validate(
        PropositionChart.unchecked(
          c.focus,
          c.concepts,
          c.relations,
          c.polarity,
          c.embedded,
          Vector(PropositionAlignment(element, spans, credence, meta)),
          c.provenance,
          c.sentence
        )
      )
      .toOption
      .get
    ClaimChartReference(owner, PropositionEvidence.of(aligned), element)

  private def sourceTextFor(c: ControlledClaim): String =
    if c.chart.embedded.nonEmpty then "A person reported that a bell rang."
    else if c.event == otherEvent then "A bell rang at another time."
    else if c.chart.polarityOf(p) == Polarity.Negative then "A bell did not ring."
    else if c.chart.concepts(p).lemma.value == "shine" then "A lamp shone."
    else "A bell rang."

  /** Materialize an actual checked receipt, retaining the entire synthetic mapping unchanged. */
  def run(): ClaimAssessmentReceipt =
    val groups = cases.map(c => c.unitGroup.getOrElse(c.id)).distinct
    val texts = groups.map(g => cases.find(c => c.unitGroup.getOrElse(c.id) == g).get.text)
    val transcript = StorySource.fromText(texts.mkString("\n")).toOption.get
    val offsets = texts.scanLeft(0)((n, text) => n + text.length + 1).init
    val spans = groups
      .zip(texts.zip(offsets))
      .map { case (g, (text, start)) =>
        g -> SpanSet.one(TextSpan.unsafe(start, start + text.length))
      }
      .toMap
    val units = groups.zipWithIndex.map { (g, i) =>
      RecallUnit(
        RecallUnitId.unsafe("controlled:" + g),
        i,
        spans(g),
        texts(i),
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch.empty,
        None
      )
    }
    val recall = RecallGraph
      .validated(transcript, SurfaceAnalyzer.analyze(transcript), units, RecallRelations.empty)
      .toOption
      .get
    val inventory = RecallInventory
      .of(recall, Vector.empty, WordIdPolicy.inputArtifact(transcript.canonicalChecksum))
      .toOption
      .get
    val sourceCases = cases.flatMap(c => c.sources.zipWithIndex.map((s, i) => (c.id, i, s)))
    val sourceTexts = sourceCases.map((_, _, c) => sourceTextFor(c))
    val sourceText = sourceTexts.mkString("\n")
    val wholeSource = SpanSet.one(TextSpan.unsafe(0, sourceText.length))
    val sourceOffsets = sourceTexts.scanLeft(0)((n, t) => n + t.length + 1).init
    val sourceSpans = sourceCases
      .zip(sourceTexts.zip(sourceOffsets))
      .map { case ((id, i, _), (text, offset)) =>
        (id, i) -> SpanSet.one(TextSpan.unsafe(offset, offset + text.length))
      }
      .toMap
    def origin(id: String, i: Int) = SourceNodeRef.Situation(SituationId.unsafe(s"evidence:$id:$i"))
    val refs = Vector(mainEvent, otherEvent) ++ sourceCases.map((id, i, _) => origin(id, i))
    val supportByRef = Map(mainEvent -> wholeSource, otherEvent -> wholeSource) ++
      sourceCases.map((id, i, _) => origin(id, i) -> sourceSpans((id, i)))
    def node(ref: SourceNodeRef, i: Int) = NodeSummary(
      ref,
      0,
      None,
      i,
      supportByRef(ref),
      None,
      Vector.empty,
      ContextTag.NarratedWorld,
      PolarityTag.Unknown,
      ModalityTag.Unknown,
      Vector.empty,
      Set.empty
    )
    val view = InMemorySourceView(refs.zipWithIndex.map(node), Map.empty, None, sourceText.length)
    val source = SourceRepresentation
      .of(
        view,
        NonEmptyVector.one(BundleEntry.text(Checksum.ofText(sourceText))),
        None,
        refs
          .map(ref => ref -> SourceSupportStatus.located(TypedSupport.Text(supportByRef(ref))))
          .toMap
      )
      .toOption
      .get
    val universe = DeclaredUniverse.of(refs, TargetGrain.SingleLevel(0)).toOption.get
    val policies = MappingPolicies
      .of(
        InferencePolicy.Unspecified("controlled fixed alignment"),
        ContextPolicy.Unspecified("controlled"),
        CandidatePolicy.Unknown("authored control"),
        ReferencePrior.NotApplicable("authored raw values"),
        DecisionPolicy.NotApplicable("control"),
        universe
      )
      .toOption
      .get
    val roles = UnitRoles
      .of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(TargetGrain.SingleLevel(0))
      )
      .toOption
      .get
    val ledger = StageLedger
      .of(
        Stage.values.toVector.map(s =>
          StageEntry
            .of(s, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
            .toOption
            .get
        )
      )
      .toOption
      .get
    val stages = UnitStageRefs
      .of(
        ledger,
        ledger.at(Stage.Inference).head.id,
        ledger.at(Stage.Candidates).head.id,
        ledger.at(Stage.Decision).head.id
      )
      .toOption
      .get
    val values: Map[Destination, Double] =
      Map(Destination.Target(mainEvent) -> 0.8, Destination.Target(otherEvent) -> 0.2)
    val basis = DecisionBasis.of(MeasureKind.RawScore, Some("controlled")).toOption.get
    val outcomes = units.map { u =>
      val raw = RawScores
        .of(
          "controlled",
          ScoreDirection.HigherIsBetter,
          "authored arbitrary units",
          values,
          ledger.at(Stage.Scoring).head.id
        )
        .toOption
        .get
      val measures = UnitMeasures.of(Vector(raw), None, None, None).toOption.get
      val candidate = CandidateSetId.of(stages.candidates, u.id, values.keySet)
      val links = values.keys.toVector.sorted.map(d => MappingLink.ungated(d, stages, candidate))
      UnitOutcome
        .computed(u.id, measures, links, basis, DecisionRequest.RawArgmax, stages)
        .toOption
        .get
    }
    val mapping =
      MappingResult.checked(inventory, source, policies, roles, ledger, outcomes).toOption.get
    val questions = cases.map(c =>
      ClaimAssessmentQuestion(
        ClaimId.unsafe(c.id),
        RecallUnitId.unsafe("controlled:" + c.unitGroup.getOrElse(c.id)),
        mainEvent,
        Facet.Action
      )
    )
    def evidenceId(id: String, i: Int) = EvidenceId.unsafe(s"$id:$i")
    val citations = sourceCases.map { (id, i, claim) =>
      val support = TypedSupport.Text(sourceSpans((id, i)))
      val owner = ClaimAssessmentReceipt.sourceChartOwner(mapping, origin(id, i), support)
      ClaimSourceCitation(
        evidenceId(id, i),
        origin(id, i),
        support,
        Some(chartAt(claim, owner, sourceSpans((id, i))))
      )
    }
    val submissions = cases.map { c =>
      val span = spans(c.unitGroup.getOrElse(c.id))
      val claimSpan =
        if c.id == "partial-memory-supported" then
          SpanSet.one(
            TextSpan.unsafe(span.minSpan.start, span.minSpan.start + compound.indexOf(','))
          )
        else if c.id == "partial-memory-contradicted" then
          SpanSet.one(
            TextSpan
              .unsafe(span.minSpan.start + compound.indexOf("a lamp"), span.minSpan.endExclusive)
          )
        else span
      val comparisons = c.recalled.toVector.flatMap(a => c.sources.map(compare(a, _)))
      val supports = comparisons.zipWithIndex.collect {
        case (v, i) if v.support => evidenceId(c.id, i)
      }.toSet
      val counters = comparisons.zipWithIndex.collect {
        case (v, i) if v.counter => evidenceId(c.id, i)
      }.toSet
      val reasons = c.remaining ++ comparisons.flatMap(_.unknown) ++
        (if c.sources.isEmpty then Set(ClaimAssessmentUnknown.SourceUnavailable) else Set.empty) ++
        (c.recallState match
          case RecallClaimState.ExtractionUnresolved =>
            Set(ClaimAssessmentUnknown.RecallExtractionUnavailable)
          case RecallClaimState.ReferenceUnresolved =>
            Set(ClaimAssessmentUnknown.ReferenceUnresolved)
          case _ => Set.empty)
      val expressed =
        c.recallState == RecallClaimState.Expressed || c.recallState == RecallClaimState.ReferenceUnresolved
      ClaimAssessmentSubmission(
        ClaimId.unsafe(c.id),
        c.recallState,
        span,
        Option.when(expressed)(claimSpan),
        c.recalled.map(chartAt(_, inventory.transcript, claimSpan)),
        supports,
        counters,
        reasons,
        ClaimAssessorKind.DeterministicRule,
        procedure
      )
    }
    ClaimAssessmentReceipt
      .checked(mapping, questions, citations, submissions)
      .fold(e => throw new IllegalStateException(e.toString), identity)

  def main(args: Array[String]): Unit =
    require(args.length == 1, "output JSON path required")
    val receipt = run()
    def chartJson(ref: ClaimChartReference): Json = Json.obj(
      "owner" -> ref.owner.hex.asJson,
      "element" -> ref.element.asJson,
      "evidence" -> ref.evidence.asJson
    )
    val rows = cases.zip(receipt.questions.zip(receipt.submissions)).map { case (c, (q, s)) =>
      Json.obj(
        "case" -> c.id.asJson,
        "question" -> q.id.value.asJson,
        "unit" -> q.unit.value.asJson,
        "assessedEvent" -> q.event.key.asJson,
        "facet" -> q.facet.toString.asJson,
        "syntheticRecall" -> c.text.asJson,
        "expected" -> c.expected.toString.asJson,
        "actual" -> receipt.status(s.question).get.toString.asJson,
        "recallState" -> s.recallState.toString.asJson,
        "supporting" -> s.supporting.toVector.map(_.value).sorted.asJson,
        "counterevidence" -> s.counterevidence.toVector.map(_.value).sorted.asJson,
        "unresolved" -> s.unresolved.toVector.map(_.toString).sorted.asJson,
        "reviewedSpans" -> s.reviewedSpans.asJson,
        "claimSpans" -> s.claimSpans.asJson,
        "assessor" -> s.assessor.toString.asJson,
        "procedure" -> s.procedure.value.asJson,
        "recallChart" -> s.recallChart.map(chartJson).asJson,
        "controlledParticipantBindings" -> c.recalled
          .map(_.participants.toVector.map((k, v) => k.value -> v.value).toMap)
          .asJson,
        "controlledEvent" -> c.recalled.map(_.event.key).asJson,
        "sourceInputs" -> c.sources.zipWithIndex
          .map((s, i) =>
            Json.obj(
              "citation" -> s"${c.id}:$i".asJson,
              "syntheticText" -> sourceTextFor(s).asJson,
              "chart" -> s.chart.asJson,
              "event" -> s.event.key.asJson,
              "participants" -> s.participants.toVector
                .map((k, v) => k.value -> v.value)
                .toMap
                .asJson
            )
          )
          .asJson
      )
    }
    val report = Json.obj(
      "schema" -> receipt.version.asJson,
      "qualification" -> "Synthetic controlled comparison; event/entity binding and charts supplied, no automatic extraction, no corpus accuracy or calibrated veridicality claim".asJson,
      "mappingDigest" -> receipt.mappingDigest.hex.asJson,
      "mapping" -> MappingCodecs.toJson(receipt.mapping),
      "populationDigest" -> receipt.populationDigest.hex.asJson,
      "questionCount" -> receipt.totalQuestions.asJson,
      "expressedCount" -> receipt.expressedQuestions.asJson,
      "resolvedCount" -> receipt.resolvedQuestions.asJson,
      "counts" -> receipt.counts.map((k, v) => k.toString -> v).asJson,
      "citations" -> receipt.citations
        .map(c =>
          Json.obj(
            "id" -> c.id.value.asJson,
            "origin" -> c.origin.key.asJson,
            "support" -> c.support.asJson,
            "chart" -> c.chart.map(chartJson).asJson
          )
        )
        .asJson,
      "rows" -> rows.asJson
    )
    val written =
      Files.write(Paths.get(args(0)), report.spaces2.getBytes(UTF_8), StandardOpenOption.CREATE_NEW)
    require(Files.size(written) > 0, "empty report")
