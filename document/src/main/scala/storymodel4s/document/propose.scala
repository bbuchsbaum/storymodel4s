package storymodel4s.document

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.core.TextNorm.lower as foldCase
import storymodel4s.proposition.{
  Canonical,
  ChartOrigin,
  Checked,
  Concept,
  ConceptId,
  ConceptKind,
  Gloss,
  Polarity as ChartPolarity,
  PropositionAlignment,
  PropositionChart,
  PropositionEvidence,
  RoleAssignment,
  SourceRole
}
import storymodel4s.story.{
  EntityType,
  Modality,
  ParticipantRole,
  Polarity as StoryPolarity,
  Predicate,
  StoryModel,
  TemporalRelation
}

/** Why a chart with concepts yielded no situation attempt at its anchor.
  *
  * Why typed: the compiler refuses every root that is not the chart focus, not a predicate, or
  * embedded; recording which of those held keeps "no proposal" distinguishable from "not run".
  */
enum AbstentionReason:
  case NoFocus
  case FocusNotPredicate(kind: ConceptKind)
  case FocusEmbedded

  def render: String = this match
    case NoFocus                 => "no-focus"
    case FocusNotPredicate(kind) => s"focus-not-predicate:$kind"
    case FocusEmbedded           => "focus-embedded"

/** One ledger row per atlas sentence: what the provider did with it.
  *
  * Why a per-sentence ledger: a story with fifty sentences and three proposals must say, for the
  * other forty-seven, whether a chart was missing, empty, or inadmissible; silence would let a
  * partial run read as a complete one.
  */
enum SentenceCoverage:
  /** `fillers` counts the entity-kind fillers proposed as participants of the root; `unlicensed`
    * counts the entity-kind fillers the chart reaches from the root by no single normalized
    * participant role, which are never proposed. Together they say how much of the root's argument
    * structure the participant layer carries. The sentence is the root's own.
    */
  case Proposed(root: ChartNodeRef, fillers: Int, unlicensed: Int)
  case Abstained(anchor: ChartNodeRef, reason: AbstentionReason)
  case EmptyChart(unit: SurfaceUnitId)
  case NoChart(unit: SurfaceUnitId)

  /** The sentence the row is about: derived from the chart node for rows that carry one. */
  def sentence: SurfaceUnitId = this match
    case Proposed(root, _, _) => root.sentence
    case Abstained(anchor, _) => anchor.sentence
    case EmptyChart(unit)     => unit
    case NoChart(unit)        => unit

/** Whether a story-summary proposal was emitted; the title is the only summary source here. */
enum SummaryCoverage:
  case Proposed(title: String)
  case NoTitle

/** Coverage counts over the sentence ledger. Honest product data: every combination is lawful. */
final case class CoverageCounts(proposed: Int, abstained: Int, emptyCharts: Int, noCharts: Int):
  def sentences: Int = proposed + abstained + emptyCharts + noCharts

/** Everything [[ChartProposalProvider.propose]] emitted for one story, in canonical order.
  *
  * Why a non-case class with a package-private factory: the attempt vectors stand in a relation
  * (one context, one membership, and one participant-coverage attempt per situation attempt, one
  * entity-mention attempt per participant filler, one evidence record per proposal, one coverage
  * row per sentence) that only the provider establishes. There is no `copy` or `fromProduct` door.
  */
final class ChartProposals private (
    val evidence: Vector[Evidence],
    val situations: Vector[SituationAttempt],
    val contexts: Vector[ContextAssignmentAttempt],
    val summary: StorySummaryAttempt,
    val memberships: Vector[SegmentMembershipAttempt],
    val causal: Vector[CausalAttempt],
    val entityMentions: Vector[EntityMentionAttempt],
    val participants: Vector[ParticipantAttempt],
    val participantCoverage: Vector[ParticipantCoverageAttempt],
    val temporal: Vector[TemporalAttempt],
    val calls: Vector[ProviderCall],
    val coverage: Vector[SentenceCoverage],
    val summaryCoverage: SummaryCoverage
):
  def counts: CoverageCounts =
    coverage.foldLeft(CoverageCounts(0, 0, 0, 0)) { (acc, row) =>
      row match
        case SentenceCoverage.Proposed(_, _, _) => acc.copy(proposed = acc.proposed + 1)
        case SentenceCoverage.Abstained(_, _)   => acc.copy(abstained = acc.abstained + 1)
        case SentenceCoverage.EmptyChart(_)     => acc.copy(emptyCharts = acc.emptyCharts + 1)
        case SentenceCoverage.NoChart(_)        => acc.copy(noCharts = acc.noCharts + 1)
    }

  override def equals(other: Any): Boolean = other match
    case that: ChartProposals =>
      evidence == that.evidence &&
      situations == that.situations &&
      contexts == that.contexts &&
      summary == that.summary &&
      memberships == that.memberships &&
      causal == that.causal &&
      entityMentions == that.entityMentions &&
      participants == that.participants &&
      participantCoverage == that.participantCoverage &&
      temporal == that.temporal &&
      calls == that.calls &&
      coverage == that.coverage &&
      summaryCoverage == that.summaryCoverage
    case _ => false

  override def hashCode(): Int =
    (
      evidence,
      situations,
      contexts,
      summary,
      memberships,
      causal,
      entityMentions,
      participants,
      participantCoverage,
      temporal,
      calls,
      coverage,
      summaryCoverage
    ).##

  override def toString: String =
    val c = counts
    s"ChartProposals(proposed=${c.proposed}, abstained=${c.abstained}, " +
      s"empty=${c.emptyCharts}, noChart=${c.noCharts}, participants=${participants.size}, " +
      s"temporal=${temporal.size}, calls=${calls.size})"

object ChartProposals:
  private[document] def derived(
      evidence: Vector[Evidence],
      situations: Vector[SituationAttempt],
      contexts: Vector[ContextAssignmentAttempt],
      summary: StorySummaryAttempt,
      memberships: Vector[SegmentMembershipAttempt],
      entityMentions: Vector[EntityMentionAttempt],
      participants: Vector[ParticipantAttempt],
      participantCoverage: Vector[ParticipantCoverageAttempt],
      temporal: Vector[TemporalAttempt],
      calls: Vector[ProviderCall],
      coverage: Vector[SentenceCoverage],
      summaryCoverage: SummaryCoverage
  ): ChartProposals =
    new ChartProposals(
      evidence,
      situations,
      contexts,
      summary,
      memberships,
      Vector.empty,
      entityMentions,
      participants,
      participantCoverage,
      temporal,
      calls,
      coverage,
      summaryCoverage
    )

/** Deterministic, receipted proposal provider from sentence charts to compiler input.
  *
  * Why it exists: ADR 0005's compiler consumes typed proposals, and until now the only provider was
  * a one-sentence lexical fixture. This provider reads every chart of a story and emits the
  * situation, context-assignment, segment-membership, participant, entity-mention,
  * participant-coverage, temporal, and summary attempts the compiler needs, with typed abstention
  * wherever a chart has no admissible root. It never reads the sentence text for content: every
  * content word comes from the chart. Its rules are [[RulesText]], whose checksum is the
  * prompt-package checksum and the provenance config hash, so a rule change changes every receipt.
  */
object ChartProposalProvider:
  val Stage: StageId = StageId.unsafe("chart-proposal-provider")

  /** Extractor identity on every evidence record this provider writes. */
  val ProviderFingerprint: Fingerprint =
    Fingerprint.unsafe("storymodel4s:chart-proposal-provider:0.1")

  /** Provider identity on every receipt; the resolver counts agreement by this triple. */
  val ProviderName: String = "chart-proposal-provider"
  val ModelName: String = "chart-rules"
  val Version: String = "1"

  /** Calibration model of every rule that is a total function of the chart: the value cannot
    * disagree with what the chart says, so probability 1.0 is honest.
    */
  val CalibrationModel: String = "chart-rule-v1"

  /** Calibration model of the context rule. `NarratedWorld` is the absence-of-embedding default at
    * sentence grain (the focus is held by no embedding, so the sentence asserts it at root), not a
    * context the chart licenses positively; the name keeps that visible on every receipt.
    */
  val ContextCalibrationModel: String = "narrated-world-default-v1"

  /** Calibration model of the title-summary rule, which reads no chart. */
  val SummaryCalibrationModel: String = "title-rule-v1"

  /** Frame namespace of the `-91` reification set: the AMR adapter records every frame under its
    * `InteropTables.FrameNamespace`, which `ChartProposalCourtSuite` pins equal to this value
    * (`document` cannot depend on `amr-interop`). An id in another namespace is not a state.
    */
  val StateFrameNamespace: String = "propbank"

  /** The closed set of AMR `-91` reification frames whose focus denotes a state, not an event. */
  val StateFrames: Set[String] = Set(
    "be-destined-for-91",
    "be-from-91",
    "be-located-at-91",
    "be-temporally-at-91",
    "have-concession-91",
    "have-condition-91",
    "have-degree-91",
    "have-extent-91",
    "have-frequency-91",
    "have-instrument-91",
    "have-li-91",
    "have-manner-91",
    "have-mod-91",
    "have-name-91",
    "have-ord-91",
    "have-org-role-91",
    "have-part-91",
    "have-polarity-91",
    "have-purpose-91",
    "have-quant-91",
    "have-rel-role-91",
    "have-subevent-91",
    "have-value-91",
    "include-91"
  )

  /** Named (non-core) chart roles whose participant reading is stable across frames, as the AMR
    * adapter's standard-role table reads them. Numbered arguments never appear here: their meaning
    * is frame-specific and only a lexicon-licensed normalization on the chart itself counts.
    */
  val NamedRoles: Map[String, ParticipantRole] = Map(
    "location" -> ParticipantRole.Location,
    "time" -> ParticipantRole.Time,
    "manner" -> ParticipantRole.Manner,
    "cause" -> ParticipantRole.Cause,
    "purpose" -> ParticipantRole.Custom("amr", "purpose"),
    "instrument" -> ParticipantRole.Instrument,
    "beneficiary" -> ParticipantRole.Beneficiary,
    "source" -> ParticipantRole.Source,
    "destination" -> ParticipantRole.Destination
  )

  /** Rule names recorded on receipts. */
  val SituationRule: String = "focus-situation-rule"
  val ContextRule: String = "narrated-world-context-rule"
  val MembershipRule: String = "primary-story-membership-rule"
  val ParticipantRule: String = "licensed-role-participant-rule"
  val MentionRule: String = "entity-filler-mention-rule"
  val CoverageRule: String = "participant-coverage-rule"
  val TemporalRule: String = "adjacent-root-unclear-rule"
  val SummaryRule: String = "title-summary-rule"
  val AbstainSituationRule: String = "abstain-situation-rule"
  val AbstainContextRule: String = "abstain-context-rule"
  val AbstainMembershipRule: String = "abstain-membership-rule"
  val AbstainCoverageRule: String = "abstain-coverage-rule"
  val AbstainSummaryRule: String = "abstain-summary-rule"

  /** The mapping rules, verbatim. Its checksum is the prompt-package checksum and the provenance
    * config hash.
    */
  val RulesText: String =
    s"""chart-proposal-provider rules, version 1
       |
       |root: the situation root of a sentence is the chart focus and nothing else. There is no
       |  fallback to another predicate; a chart whose focus is inadmissible is abstained.
       |admissible: the focus concept is not held by any embedding and has kind Predicate, or
       |  kind Special with a state frame (below). The AMR adapter classifies every -91 roleset
       |  as Special, so the state rule is reachable only through that clause; any other Special
       |  focus (a frameless AMR special such as date-entity, or a -91 frame outside the closed
       |  set) abstains with focus-not-predicate:Special.
       |kind: State when the focus concept carries a frame in namespace $StateFrameNamespace whose
       |  id is in the closed set {${StateFrames.toVector.sorted.mkString(", ")}}; otherwise
       |  Event. Lexical statives without such a frame are Event in this version.
       |predicate: lemma = the concept lemma; frame = "namespace:id" of the concept frame when
       |  present; gloss = the concept gloss when present, else the lemma.
       |description: Gloss.predicate over the chart at the root, so every word is a chart lemma
       |  or literal; the sentence text is never copied.
       |polarity: the chart polarity of the root, mapped one-to-one (Positive, Negative, Unknown).
       |modality: Asserted. aspect: none.
       |context: NarratedWorld, one attempt per admissible root, anchored at the root.
       |membership: PrimaryStoryMember, one attempt per admissible root, anchored at the root.
       |support: the union of the alignment spans of every alignment naming at least one
       |  non-embedded concept, recorded as span-source=chart-alignments; when that union is empty
       |  the sentence span is used, recorded as span-source=sentence. Every alignment span of a
       |  chart must lie inside the chart's own sentence unit, whatever surface unit the span
       |  names; a chart violating this is refused, not repaired.
       |raw score: the minimum alignment credence among the alignments that supply a proposal's
       |  support; chart credence propagates only as this uncalibrated raw score and never as a
       |  probability. A sentence-fallback support has no alignment credence and carries 1.0,
       |  which span-source=sentence distinguishes from a measured 1.0.
       |calibration: probability 1.0 under $CalibrationModel for every rule that is a total
       |  function of the chart (situation, membership, coverage, participant, mention, temporal);
       |  the context rule under $ContextCalibrationModel, because NarratedWorld is the
       |  absence-of-embedding default at sentence grain; the summary rule under
       |  $SummaryCalibrationModel.
       |receipts: every evidence id is the content address of its scope, chart checksum, and
       |  rendered span set; every call render names the evidence id it cites; the chart receipts
       |  bind the source by their input checksum (the canonical text or the sentence text); the
       |  chart origin is a receipt parameter.
       |summary: the source title with evidence spanning the whole canonical text; no title or a
       |  blank title yields an abstained summary attempt.
       |participants: for each admissible root, every relation from the root whose filler is a
       |  chart concept of kind Entity, Name, or Quantity and whose role carries exactly one
       |  normalized participant role yields one participant attempt (situation = root, filler =
       |  the concept) valued at that role. A numbered argument carries a normalized role only when
       |  the chart's frame lexicon licensed one; a named role carries the chart's own normalized
       |  role, else the standard table {$namedRolesText}. A filler reached by no licensed role, or
       |  by two different licensed roles, is counted unlicensed on the coverage row and never
       |  proposed; literal and unknown fillers are not counted. Participant evidence is the union
       |  of the filler's alignment spans and the root support.
       |mentions: one entity-mention attempt per proposed filler with label = the concept lemma
       |  and type = Custom("chart", concept kind lowercased); evidence = the filler's alignment
       |  spans (span-source=filler-alignments), else the root support (span-source=root-support).
       |coverage: one participant-coverage attempt per admissible root listing exactly the
       |  proposed fillers, possibly none, with the root's evidence. An empty coverage is a value:
       |  it says the chart reaches no licensed participant from the root, never that participants
       |  were not evaluated.
       |temporal: one attempt per consecutive pair of admissible roots in sentence order, valued
       |  Unclear, with evidence spanning both roots' support. Never Before or Meets: a time
       |  filler in a sentence chart is a concept of that chart, not a preceding root, so nothing in
       |  a chart licenses strict precedence between roots; and StrictPrecedence is a high-impact
       |  family whose conservative policy this single provider could not satisfy alone.
       |abstention: an inadmissible root yields one abstained attempt in each of the situation,
       |  context, membership, and participant-coverage families at the anchor (the focus when
       |  present, else the lowest concept id). An empty chart or a sentence without a chart yields
       |  no attempt and a coverage row only.
       |causal: no causal attempt is emitted; absent pairs are not evaluated.
       |bundles: one proposed value per attempt with the raw score above, source support 1.0
       |  over the evidence spans, agreement 1.0, and one calibration at probability 1.0 under the
       |  rule's model; abstained attempts carry no value, support 0.0, no spans, agreement 0.0,
       |  no calibration.
       |policy: AcceptancePolicy.Conservative, except ContextAssignment and SegmentMembership at
       |  requireAgreement = 1, because one deterministic program is one provider; EntityMention,
       |  ParticipantRole, ParticipantCoverage, and TemporalRelation are FamilyPolicy.Ordinary.
       |""".stripMargin

  /** The named-role table as printed into [[RulesText]]. */
  private def namedRolesText: String =
    NamedRoles.toVector
      .sortBy(_._1)
      .map((name, role) => s"$name=${renderRole(role)}")
      .mkString(", ")

  val Prompt: PromptPackageRef =
    PromptPackageRef("chart-rules", Version, Checksum.ofText(RulesText))

  /** Conservative everywhere, with the two high-impact families this single provider can satisfy
    * alone lowered to one agreeing provider. Recorded in [[RulesText]], so in every config hash.
    */
  val Policy: AcceptancePolicy =
    // The literal arguments satisfy FamilyPolicy.of; if a future edit broke that, the family
    // would stay at Conservative (two providers, never satisfiable here) and the policy court
    // pins requireAgreement = 1, so the failure is closed and visible rather than a thrown
    // initializer.
    val single = FamilyPolicy
      .of(Probability.unsafe(0.9), Probability.unsafe(0.5), 1, true, true, true)
      .getOrElse(FamilyPolicy.Conservative)
    val base = AcceptancePolicy.Conservative
    base.copy(perFamily =
      base.perFamily ++ Map(
        ClaimFamily.ContextAssignment -> single,
        ClaimFamily.SegmentMembership -> single,
        ClaimFamily.EntityMention -> FamilyPolicy.Ordinary,
        ClaimFamily.ParticipantRole -> FamilyPolicy.Ordinary,
        ClaimFamily.ParticipantCoverage -> FamilyPolicy.Ordinary,
        ClaimFamily.TemporalRelation -> FamilyPolicy.Ordinary
      )
    )

  private val ChartPath = "chart-proposal-provider/charts"
  private val AlignmentPath = "chart-proposal-provider/alignments"

  /** A proposed root together with what the temporal rule needs from it. */
  private final case class ProposedRoot(
      unit: SurfaceUnit,
      root: ChartNodeRef,
      checksum: Checksum,
      spans: SpanSet,
      raw: Double
  )

  /** Support spans, where they came from, and the minimum alignment credence behind them. */
  private final case class Support(spans: SpanSet, source: String, raw: Double)

  /** An entity-kind filler of the root reached by exactly one licensed participant role. */
  private final case class LicensedFiller(
      concept: ConceptId,
      kind: ConceptKind,
      lemma: String,
      role: ParticipantRole
  )

  private final case class SentenceOutcome(
      coverage: SentenceCoverage,
      proposedRoot: Option[ProposedRoot],
      evidence: Vector[Evidence],
      situation: Option[SituationAttempt],
      context: Option[ContextAssignmentAttempt],
      membership: Option[SegmentMembershipAttempt],
      participantCoverage: Option[ParticipantCoverageAttempt],
      entityMentions: Vector[EntityMentionAttempt],
      participants: Vector[ParticipantAttempt],
      calls: Vector[ProviderCall]
  )

  private final case class SummaryOutcome(
      attempt: StorySummaryAttempt,
      coverage: SummaryCoverage,
      evidence: Option[Evidence],
      call: ProviderCall
  )

  /** Emit every attempt and the coverage ledger for `charts`, one chart per sentence at most. */
  def propose(
      source: StorySource,
      atlas: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionEvidence)]
  ): Either[DomainError, ChartProposals] =
    for
      _ <- checkAtlas(source, atlas)
      ordered <- checkCharts(source, atlas, charts)
      _ <- ordered.traverse_((unit, ev) => checkAlignments(source, atlas, unit, ev.chart))
      outcomes <- ordered.traverse((unit, ev) => sentenceOutcome(source, unit, ev))
      summary <- summaryOutcome(source)
    yield
      val byUnit = outcomes.map(o => o.coverage.sentence -> o).toMap
      val inOrder = atlas.sentences.sortBy(_.ordinal)
      val coverage = inOrder.map(unit =>
        byUnit.get(unit.id).map(_.coverage).getOrElse(SentenceCoverage.NoChart(unit.id))
      )
      val proposedRoots = inOrder.flatMap(unit => byUnit.get(unit.id).flatMap(_.proposedRoot))
      val temporal = proposedRoots
        .zip(proposedRoots.drop(1))
        .map((prev, next) => temporalOutcome(source, prev, next))
      ChartProposals.derived(
        (outcomes.flatMap(_.evidence) ++ temporal.map(_._2) ++ summary.evidence.toVector)
          .sortBy(_.id),
        outcomes.flatMap(_.situation).sortBy(_.source.key),
        outcomes.flatMap(_.context).sortBy(_.source.key),
        summary.attempt,
        outcomes.flatMap(_.membership).sortBy(_.member.key),
        outcomes.flatMap(_.entityMentions).sortBy(_.mention.key),
        outcomes.flatMap(_.participants).sortBy(a => (a.situation.key, a.filler.key)),
        outcomes.flatMap(_.participantCoverage).sortBy(_.situation.key),
        temporal.map(_._1).sortBy(a => (a.from.key, a.to.key)),
        (outcomes.flatMap(_.calls) ++ temporal.map(_._3) :+ summary.call).sortBy(renderCall),
        coverage,
        summary.coverage
      )

  /** [[propose]] and bind the result into checked compiler input with this provider's receipt.
    *
    * `parserStage` names the stage that produced the charts; its digest is derived here from the
    * charts themselves ([[chartsDigest]]), never typed by the caller.
    */
  def input(
      source: StorySource,
      atlas: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionEvidence)],
      parserStage: Option[StageId],
      createdAtEpochMillis: Long
  ): Either[NarrativeCompilerError, NarrativeCompilerInput] =
    propose(source, atlas, charts).left
      .map(e => NarrativeCompilerError.InvalidInput(NonEmptyVector.one(e)))
      .flatMap { proposals =>
        val stageDigest = ContentAddress.digest(proposals.calls.map(_.outputChecksum.hex))
        val receipt = BuildReceipt(
          source.id,
          source.canonicalChecksum,
          StoryModel.SchemaVersion,
          parserStage.map(_ -> chartsDigest(charts)).toVector :+ (Stage -> stageDigest),
          createdAtEpochMillis
        )
        val chartReceipts = charts.flatMap(_._2.provenance.receipts)
        val provenance = Provenance(
          (proposals.calls ++ chartReceipts).distinct.sortBy(renderCall),
          StoryModel.SchemaVersion,
          Checksum.ofText(RulesText)
        )
        NarrativeCompilerInput.of(
          source,
          atlas,
          charts,
          proposals.evidence,
          proposals.situations,
          proposals.contexts,
          proposals.summary,
          proposals.memberships,
          proposals.causal,
          proposals.entityMentions,
          proposals.participants,
          proposals.participantCoverage,
          proposals.temporal,
          Policy,
          receipt,
          provenance
        )
      }

  private def checkAtlas(source: StorySource, atlas: SurfaceAtlas): Either[DomainError, Unit] =
    if atlas.source.id != source.id || atlas.source.canonicalChecksum != source.canonicalChecksum
    then
      Left(
        DomainError.InvariantViolation(
          "chart-proposal-provider/atlas",
          "atlas belongs to a different source identity or checksum"
        )
      )
    else Right(())

  /** Digest of the charts a build consumed: each sentence, its canonical chart checksum, its
    * rendered alignment spans, and its receipts' output checksums. This is the parser stage's
    * digest on the build receipt.
    */
  def chartsDigest(charts: Vector[(SurfaceUnitId, PropositionEvidence)]): Checksum =
    ContentAddress.digest(
      charts
        .sortBy(_._1)
        .flatMap((unit, ev) =>
          Vector(
            "chart/v1",
            unit.value,
            Canonical.checksum(ev.chart).hex,
            renderAlignments(ev.chart)
          ) ++ ev.provenance.receipts.map(_.outputChecksum.hex)
        )
    )

  private def checkCharts(
      source: StorySource,
      atlas: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionEvidence)]
  ): Either[DomainError, Vector[(SurfaceUnit, PropositionEvidence)]] =
    val sorted = charts.sortBy(_._1)
    val duplicate = sorted.map(_._1).zip(sorted.map(_._1).drop(1)).collectFirst {
      case (a, b) if a == b => a
    }
    duplicate match
      case Some(id) =>
        Left(DomainError.InvariantViolation(ChartPath, s"duplicate chart ${id.value}"))
      case None =>
        sorted.traverse { (id, ev) =>
          atlas.byId.get(id).filter(_.kind == SurfaceUnitKind.Sentence) match
            case None =>
              Left(
                DomainError.InvariantViolation(
                  ChartPath,
                  s"${id.value} is not a sentence in the atlas"
                )
              )
            case Some(_) if !ev.chart.sentence.contains(id) =>
              Left(
                DomainError.InvariantViolation(
                  ChartPath,
                  s"chart sentence does not equal ${id.value}"
                )
              )
            case Some(unit) =>
              // A chart receipt binds the source by what it hashed: the canonical text or this
              // sentence's text. A receipt hashing anything else was made over other input.
              val bound = Set(source.canonicalChecksum, Checksum.ofText(atlas.text(unit)))
              ev.provenance.receipts.find(call => !bound(call.inputChecksum)) match
                case Some(call) =>
                  Left(
                    DomainError.InvariantViolation(
                      ChartPath,
                      s"${id.value}: chart receipt ${call.provider}/${call.model} hashed " +
                        s"${call.inputChecksum.hex}, neither the source nor the sentence"
                    )
                  )
                case None => Right(unit -> ev)
        }

  private def checkAlignments(
      source: StorySource,
      atlas: SurfaceAtlas,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked]
  ): Either[DomainError, Unit] =
    val text = source.canonicalText
    chart.alignments.flatMap(_.spans.refs.toVector).traverse_ { ref =>
      val span = ref.span
      if span.endExclusive > text.length then
        Left(
          DomainError.InvariantViolation(
            AlignmentPath,
            s"${unit.id.value}: span $span exceeds source length ${text.length}"
          )
        )
      else if !isCodePointBoundary(text, span.start) ||
        !isCodePointBoundary(text, span.endExclusive)
      then
        Left(
          DomainError.InvariantViolation(
            AlignmentPath,
            s"${unit.id.value}: span $span cuts a UTF-16 surrogate pair"
          )
        )
      else if !unit.span.contains(span) then
        // Whatever unit the ref names, a chart's alignment is evidence about its own sentence.
        Left(
          DomainError.InvariantViolation(
            AlignmentPath,
            s"${unit.id.value}: span $span lies outside the chart's sentence ${unit.span}"
          )
        )
      else
        ref.unit match
          case None        => Right(())
          case Some(named) =>
            atlas.byId.get(named) match
              case None =>
                Left(
                  DomainError.InvariantViolation(
                    AlignmentPath,
                    s"${unit.id.value}: span names unknown surface unit ${named.value}"
                  )
                )
              case Some(holder) if !holder.span.contains(span) =>
                Left(
                  DomainError.InvariantViolation(
                    AlignmentPath,
                    s"${unit.id.value}: span $span escapes surface unit ${named.value}"
                  )
                )
              case Some(holder) if !unit.span.contains(holder.span) =>
                Left(
                  DomainError.InvariantViolation(
                    AlignmentPath,
                    s"${unit.id.value}: span names surface unit ${named.value}, which is not " +
                      "the chart's sentence or a unit inside it"
                  )
                )
              case Some(_) => Right(())
    }

  private def isCodePointBoundary(text: String, index: Int): Boolean =
    def highSurrogate(value: Char): Boolean = value >= '\uD800' && value <= '\uDBFF'
    def lowSurrogate(value: Char): Boolean = value >= '\uDC00' && value <= '\uDFFF'
    index <= 0 || index >= text.length ||
    !(highSurrogate(text.charAt(index - 1)) && lowSurrogate(text.charAt(index)))

  private def sentenceOutcome(
      source: StorySource,
      unit: SurfaceUnit,
      ev: PropositionEvidence
  ): Either[DomainError, SentenceOutcome] =
    val chart = ev.chart
    val origin = ev.provenance.origin
    val checksum = Canonical.checksum(chart)
    if chart.isEmpty then
      Right(
        SentenceOutcome(
          SentenceCoverage.EmptyChart(unit.id),
          None,
          Vector.empty,
          None,
          None,
          None,
          None,
          Vector.empty,
          Vector.empty,
          Vector.empty
        )
      )
    else
      chart.focus match
        case None =>
          val anchor = ChartNodeRef(unit.id, chart.conceptIds.head)
          Right(abstain(source, unit, origin, checksum, anchor, AbstentionReason.NoFocus))
        case Some(focus) =>
          val root = ChartNodeRef(unit.id, focus)
          chart.concept(focus) match
            case None =>
              Left(
                DomainError.InvariantViolation(
                  ChartPath,
                  s"${unit.id.value}: focus ${focus.value} is not a concept of the chart"
                )
              )
            case Some(concept) if !admissibleFocus(concept) =>
              Right(
                abstain(
                  source,
                  unit,
                  origin,
                  checksum,
                  root,
                  AbstentionReason.FocusNotPredicate(concept.kind)
                )
              )
            case Some(_) if chart.isEmbedded(focus) =>
              Right(abstain(source, unit, origin, checksum, root, AbstentionReason.FocusEmbedded))
            case Some(concept) => proposeRoot(source, unit, chart, origin, checksum, root, concept)

  private def proposeRoot(
      source: StorySource,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked],
      origin: ChartOrigin,
      checksum: Checksum,
      root: ChartNodeRef,
      concept: Concept
  ): Either[DomainError, SentenceOutcome] =
    Gloss.predicate(chart, root.concept) match
      case None =>
        Left(
          DomainError.InvariantViolation(
            ChartPath,
            s"${unit.id.value}: focus ${root.concept.value} has no gloss"
          )
        )
      case Some(description) =>
        val support = supportSpans(unit, chart)
        val spans = support.spans
        val evidence = evidenceRecord(unit.id.value, checksum, spans)
        val kind = if stateFrame(concept) then SituationKind.State else SituationKind.Event
        val lemma = concept.lemma.value
        val value = SituationProposal(
          kind,
          Predicate(
            lemma,
            concept.frame.map(f => s"${f.namespace}:${f.id}"),
            concept.gloss.getOrElse(lemma)
          ),
          description,
          polarity(chart.polarityOf(root.concept)),
          Modality.Asserted,
          None
        )
        val params = chartParams(unit, checksum, origin) + ("span-source" -> support.source)
        val (situation, situationCall) = proposed(
          source,
          SituationRule,
          unit.id.value,
          checksum,
          value,
          evidence,
          support.raw,
          CalibrationModel,
          Vector(
            "situation",
            kind.toString,
            value.predicate.lemma,
            value.predicate.frame.getOrElse(""),
            value.predicate.gloss,
            value.description,
            value.polarity.toString
          ),
          params
        )
        val (context, contextCall) = proposed(
          source,
          ContextRule,
          unit.id.value,
          checksum,
          ContextAssignmentProposal.NarratedWorld,
          evidence,
          support.raw,
          ContextCalibrationModel,
          Vector("narrated-world", root.key),
          params
        )
        val (membership, membershipCall) = proposed(
          source,
          MembershipRule,
          unit.id.value,
          checksum,
          SegmentMembershipProposal.PrimaryStoryMember,
          evidence,
          support.raw,
          CalibrationModel,
          Vector("primary-story-member", root.key),
          params
        )
        val (licensed, unlicensed) = scanFillers(chart, root.concept)
        val fillerOutcomes = licensed.map(filler =>
          fillerOutcome(source, unit, chart, checksum, root, support, filler, params)
        )
        val coverageValue =
          ParticipantCoverage.of(licensed.map(f => ChartNodeRef(unit.id, f.concept)))
        val (coverage, coverageCall) = proposed(
          source,
          CoverageRule,
          unit.id.value,
          checksum,
          coverageValue,
          evidence,
          support.raw,
          CalibrationModel,
          "participant-coverage" +: root.key +: coverageValue.fillers.map(_.key),
          params + ("fillers" -> licensed.size.toString) + ("unlicensed" -> unlicensed.toString)
        )
        Right(
          SentenceOutcome(
            SentenceCoverage.Proposed(root, licensed.size, unlicensed),
            Some(ProposedRoot(unit, root, checksum, spans, support.raw)),
            evidence +: fillerOutcomes.flatMap(_.evidence),
            Some(SituationAttempt(root, situation)),
            Some(ContextAssignmentAttempt(root, context)),
            Some(SegmentMembershipAttempt(root, membership)),
            Some(ParticipantCoverageAttempt(root, coverage)),
            fillerOutcomes.map(_.mention),
            fillerOutcomes.map(_.participant),
            Vector(situationCall, contextCall, membershipCall, coverageCall) ++
              fillerOutcomes.flatMap(_.calls)
          )
        )

  private final case class FillerOutcome(
      evidence: Vector[Evidence],
      mention: EntityMentionAttempt,
      participant: ParticipantAttempt,
      calls: Vector[ProviderCall]
  )

  /** Entity-kind fillers of `root` with exactly one licensed role, in concept order, and the count
    * of entity-kind fillers reached by none or by several.
    */
  private def scanFillers(
      chart: PropositionChart[Checked],
      root: ConceptId
  ): (Vector[LicensedFiller], Int) =
    val byFiller = chart
      .relationsFrom(root)
      .flatMap(r => r.to.nodeId.map(id => id -> r.role))
      .flatMap((id, role) =>
        chart
          .concept(id)
          .filter(c => KindWitness.entity.accepts(c.kind))
          .map(concept => (id, concept, role))
      )
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
    byFiller.foldLeft((Vector.empty[LicensedFiller], 0)) {
      case ((licensed, unlicensed), (id, rows)) =>
        val concept = rows.head._2
        rows.map(_._3).flatMap(licensedRole).distinct match
          case Vector(role) =>
            (licensed :+ LicensedFiller(id, concept.kind, concept.lemma.value, role), unlicensed)
          case _ => (licensed, unlicensed + 1)
    }

  /** The chart's own normalized role when present; a standard named role otherwise; nothing for a
    * numbered argument without a lexicon licence, an operand, or an extension role.
    */
  private def licensedRole(role: RoleAssignment): Option[ParticipantRole] =
    role.normalizedRole.orElse(role.source match
      case SourceRole.Named(name) => NamedRoles.get(name)
      case _                      => None)

  private def fillerOutcome(
      source: StorySource,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked],
      checksum: Checksum,
      root: ChartNodeRef,
      rootSupport: Support,
      filler: LicensedFiller,
      params: Map[String, String]
  ): FillerOutcome =
    val fillerRef = ChartNodeRef(unit.id, filler.concept)
    val fillerAlignments = chart.alignments.filter(_.target.conceptIds.contains(filler.concept))
    val fillerSpans = SpanSet.of(fillerAlignments.flatMap(_.spans.refs.toVector))
    val (mentionSpans, mentionSource, mentionRaw) = fillerSpans match
      case Some(spans) => (spans, "filler-alignments", minCredence(fillerAlignments))
      case None        => (rootSupport.spans, "root-support", rootSupport.raw)
    val mentionEvidence = evidenceRecord(fillerRef.key, checksum, mentionSpans)
    val participantEvidence = evidenceRecord(
      s"${root.key}->${fillerRef.key}",
      checksum,
      fillerSpans.fold(rootSupport.spans)(_ ++ rootSupport.spans)
    )
    val participantRaw = math.min(mentionRaw, rootSupport.raw)
    val mentionValue = EntityMentionProposal(
      filler.lemma,
      EntityType.Custom("chart", foldCase(filler.kind.toString))
    )
    val fillerParams = params + ("filler" -> filler.concept.value)
    val (mention, mentionCall) = proposed(
      source,
      MentionRule,
      fillerRef.key,
      checksum,
      mentionValue,
      mentionEvidence,
      mentionRaw,
      CalibrationModel,
      Vector("entity-mention", fillerRef.key, mentionValue.label, mentionValue.entityType.toString),
      fillerParams + ("span-source" -> mentionSource)
    )
    val (participant, participantCall) = proposed(
      source,
      ParticipantRule,
      s"${root.key}->${fillerRef.key}",
      checksum,
      filler.role,
      participantEvidence,
      participantRaw,
      CalibrationModel,
      Vector("participant", root.key, fillerRef.key, renderRole(filler.role)),
      fillerParams + ("role" -> renderRole(filler.role))
    )
    FillerOutcome(
      Vector(mentionEvidence, participantEvidence),
      EntityMentionAttempt(fillerRef, mention),
      ParticipantAttempt(root, fillerRef, participant),
      Vector(mentionCall, participantCall)
    )

  /** One `Unclear` temporal attempt between two consecutive proposed roots. */
  private def temporalOutcome(
      source: StorySource,
      prev: ProposedRoot,
      next: ProposedRoot
  ): (TemporalAttempt, Evidence, ProviderCall) =
    val scope = s"${prev.unit.id.value}->${next.unit.id.value}"
    val checksum = ContentAddress.digest(Vector(prev.checksum.hex, next.checksum.hex))
    val evidence = evidenceRecord(scope, checksum, prev.spans ++ next.spans)
    val params = Map(
      "from" -> prev.unit.id.value,
      "to" -> next.unit.id.value,
      "chart" -> checksum.hex,
      "span-source" -> "root-supports"
    )
    val (bundle, call) = proposed(
      source,
      TemporalRule,
      scope,
      checksum,
      TemporalRelation.Unclear,
      evidence,
      math.min(prev.raw, next.raw),
      CalibrationModel,
      Vector("temporal", prev.root.key, next.root.key, TemporalRelation.Unclear.toString),
      params
    )
    (TemporalAttempt(prev.root, next.root, bundle), evidence, call)

  private def renderRole(role: ParticipantRole): String = role match
    case ParticipantRole.Custom(namespace, label) => s"Custom($namespace,$label)"
    case other                                    => other.toString

  private def abstain(
      source: StorySource,
      unit: SurfaceUnit,
      origin: ChartOrigin,
      checksum: Checksum,
      anchor: ChartNodeRef,
      reason: AbstentionReason
  ): SentenceOutcome =
    val params = chartParams(unit, checksum, origin) + ("reason" -> reason.render)
    val render = Vector("abstain", anchor.key, reason.render)
    val (situation, situationCall) =
      abstained[SituationProposal](
        source,
        AbstainSituationRule,
        unit.id.value,
        checksum,
        render,
        params
      )
    val (context, contextCall) =
      abstained[ContextAssignmentProposal](
        source,
        AbstainContextRule,
        unit.id.value,
        checksum,
        render,
        params
      )
    val (membership, membershipCall) =
      abstained[SegmentMembershipProposal](
        source,
        AbstainMembershipRule,
        unit.id.value,
        checksum,
        render,
        params
      )
    val (coverage, coverageCall) =
      abstained[ParticipantCoverage](
        source,
        AbstainCoverageRule,
        unit.id.value,
        checksum,
        render,
        params
      )
    SentenceOutcome(
      SentenceCoverage.Abstained(anchor, reason),
      None,
      Vector.empty,
      Some(SituationAttempt(anchor, situation)),
      Some(ContextAssignmentAttempt(anchor, context)),
      Some(SegmentMembershipAttempt(anchor, membership)),
      Some(ParticipantCoverageAttempt(anchor, coverage)),
      Vector.empty,
      Vector.empty,
      Vector(situationCall, contextCall, membershipCall, coverageCall)
    )

  private def summaryOutcome(source: StorySource): Either[DomainError, SummaryOutcome] =
    val scope = "story"
    val scopeChecksum = source.canonicalChecksum
    source.title.filter(_.trim.nonEmpty) match
      case None =>
        val params = Map("scope" -> scope, "rule" -> AbstainSummaryRule, "reason" -> "no-title")
        val (bundle, call) = abstained[StorySummaryProposal](
          source,
          AbstainSummaryRule,
          scope,
          scopeChecksum,
          Vector("abstain", scope, "no-title"),
          params
        )
        Right(SummaryOutcome(StorySummaryAttempt(bundle), SummaryCoverage.NoTitle, None, call))
      case Some(title) =>
        TextSpan.of(0, source.canonicalText.length).map { whole =>
          val evidence = evidenceRecord(scope, scopeChecksum, SpanSet.one(SpanRef(None, whole)))
          val params =
            Map("scope" -> scope, "rule" -> SummaryRule, "span-source" -> "canonical-text")
          val (bundle, call) = proposed(
            source,
            SummaryRule,
            scope,
            scopeChecksum,
            StorySummaryProposal(title),
            evidence,
            1.0,
            SummaryCalibrationModel,
            Vector("summary", title),
            params
          )
          SummaryOutcome(
            StorySummaryAttempt(bundle),
            SummaryCoverage.Proposed(title),
            Some(evidence),
            call
          )
        }

  /** A `-91` reification frame in the adapter's namespace: the only frames that make a State. */
  private def stateFrame(concept: Concept): Boolean =
    concept.frame.exists(f => f.namespace == StateFrameNamespace && StateFrames(f.id))

  /** A predicate, or a Special concept carrying a state frame. Any other Special focus abstains: a
    * frameless AMR special is not a situation, and a `-91` frame outside the closed set has no
    * state reading this provider can license.
    */
  private def admissibleFocus(concept: Concept): Boolean =
    concept.isPredicate || (concept.kind == ConceptKind.Special && stateFrame(concept))

  /** Alignment spans of every alignment naming a non-embedded concept, with their minimum credence;
    * the sentence with raw score 1.0 otherwise (recorded as span-source=sentence).
    */
  private def supportSpans(unit: SurfaceUnit, chart: PropositionChart[Checked]): Support =
    val supporting =
      chart.alignments.filter(_.target.conceptIds.exists(id => !chart.isEmbedded(id)))
    SpanSet.of(supporting.flatMap(_.spans.refs.toVector)) match
      case Some(set) => Support(set, "chart-alignments", minCredence(supporting))
      case None      => Support(SpanSet.one(SpanRef(Some(unit.id), unit.span)), "sentence", 1.0)

  /** Minimum raw credence of the given alignments; 1.0 for none, which callers only reach with a
    * fallback support whose span-source says so.
    */
  private def minCredence(alignments: Vector[PropositionAlignment]): Double =
    alignments.map(_.credence.rawScore).minOption.getOrElse(1.0)

  /** Content-addressed evidence over its scope, chart checksum, and rendered span set. */
  private def evidenceRecord(scope: String, checksum: Checksum, spans: SpanSet): Evidence =
    Evidence(
      EvidenceId.unsafe(
        ContentAddress.of("chart-proposal-evidence/v2", scope, checksum.hex, renderSpans(spans))
      ),
      Some(spans),
      Set.empty,
      ProviderFingerprint,
      Stage
    )

  private def renderSpans(spans: SpanSet): String =
    spans.refs.toVector
      .map(r => s"${r.unit.fold("-")(_.value)}:${r.span.start}:${r.span.endExclusive}")
      .mkString(",")

  private def renderAlignments(chart: PropositionChart[Checked]): String =
    chart.alignments
      .map(a =>
        s"${a.target.conceptIds.toVector.sorted.map(_.value).mkString("+")}=" +
          s"${renderSpans(a.spans)}@${a.credence.rawScore}"
      )
      .sorted
      .mkString(";")

  private def renderOrigin(origin: ChartOrigin): String = origin match
    case ChartOrigin.Hand               => "hand"
    case ChartOrigin.Parser(f)          => s"parser:${f.value}"
    case ChartOrigin.Agent(f)           => s"agent:${f.value}"
    case ChartOrigin.Converted(from, f) => s"converted:$from:${f.value}"
    case ChartOrigin.Resolved           => "resolved"

  private def polarity(value: ChartPolarity): StoryPolarity = value match
    case ChartPolarity.Positive => StoryPolarity.Positive
    case ChartPolarity.Negative => StoryPolarity.Negative
    case ChartPolarity.Unknown  => StoryPolarity.Unknown

  /** The origin comes from the evidence's provenance, the same record the binding rule reads. */
  private def chartParams(
      unit: SurfaceUnit,
      checksum: Checksum,
      origin: ChartOrigin
  ): Map[String, String] =
    Map(
      "sentence" -> unit.id.value,
      "chart" -> checksum.hex,
      "chart-origin" -> renderOrigin(origin)
    )

  private def taskId(rule: String, scope: String, checksum: Checksum): TaskId =
    TaskId.unsafe(ContentAddress.of("chart-proposal-task", rule, scope, checksum.hex))

  private def providerCall(
      source: StorySource,
      rule: String,
      render: Vector[String],
      params: Map[String, String]
  ): ProviderCall =
    ProviderCall(
      ProviderName,
      ModelName,
      Version,
      None,
      source.canonicalChecksum,
      ContentAddress.digest(rule +: render),
      params + ("rule" -> rule),
      None,
      cached = false
    )

  /** One proposed value with its evidence; the call render always ends with the evidence id, so a
    * receipt identifies the exact spans it was made over.
    */
  private def proposed[A](
      source: StorySource,
      rule: String,
      scope: String,
      checksum: Checksum,
      value: A,
      evidence: Evidence,
      rawScore: Double,
      calibrationModel: String,
      render: Vector[String],
      params: Map[String, String]
  ): (EvidenceBundle[A], ProviderCall) =
    val task = taskId(rule, scope, checksum)
    val call = providerCall(source, rule, render :+ evidence.id.value, params)
    val proposal = AgentProposal.proposed(
      task,
      value,
      NonEmptyVector.one(EvidenceRef.Inline(evidence)),
      Some(RawScore.unsafe(rawScore)),
      Vector.empty,
      AgentCallReceipt(call, Prompt, task)
    )
    (
      EvidenceBundle(
        Vector(proposal),
        Vector.empty,
        StructuralValidity.Valid,
        SourceSupport(1.0, evidence.spans),
        agreementScore = 1.0,
        Vector(CandidateCalibration(value, Probability.One, calibrationModel))
      ),
      call
    )

  private def abstained[A](
      source: StorySource,
      rule: String,
      scope: String,
      checksum: Checksum,
      render: Vector[String],
      params: Map[String, String]
  ): (EvidenceBundle[A], ProviderCall) =
    val task = taskId(rule, scope, checksum)
    val call = providerCall(source, rule, render, params)
    val proposal = AgentProposal.abstained[A](task, AgentCallReceipt(call, Prompt, task))
    (
      EvidenceBundle(
        Vector(proposal),
        Vector.empty,
        StructuralValidity.Valid,
        SourceSupport(0.0, None),
        agreementScore = 0.0,
        Vector.empty
      ),
      call
    )

  /** Total, collision-free ordering key over a call; params are sorted so map order is irrelevant.
    */
  private def renderCall(call: ProviderCall): String =
    (Vector(
      call.provider,
      call.model,
      call.version,
      call.promptTemplateVersion.fold("")(_.value),
      call.inputChecksum.hex,
      call.outputChecksum.hex,
      call.seed.fold("")(_.toString),
      call.cached.toString
    ) ++ call.params.toVector.sorted.map((k, v) => s"$k=$v")).mkString(" ")
