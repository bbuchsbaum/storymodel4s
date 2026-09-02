package storymodel4s.document

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.proposition.{
  Canonical,
  Checked,
  Concept,
  ConceptKind,
  Gloss,
  Polarity as ChartPolarity,
  PropositionChart,
  PropositionEvidence
}
import storymodel4s.story.{Modality, Polarity as StoryPolarity, Predicate, StoryModel}

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
  case Proposed(sentence: SurfaceUnitId, root: ChartNodeRef)
  case Abstained(sentence: SurfaceUnitId, anchor: ChartNodeRef, reason: AbstentionReason)
  case EmptyChart(sentence: SurfaceUnitId)
  case NoChart(sentence: SurfaceUnitId)

  def sentence: SurfaceUnitId

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
  * (one context and one membership attempt per situation attempt, one evidence record per proposal,
  * one coverage row per sentence) that only the provider establishes. There is no `copy` or
  * `fromProduct` door.
  */
final class ChartProposals private (
    val evidence: Vector[Evidence],
    val situations: Vector[SituationAttempt],
    val contexts: Vector[ContextAssignmentAttempt],
    val summary: StorySummaryAttempt,
    val memberships: Vector[SegmentMembershipAttempt],
    val causal: Vector[CausalAttempt],
    val calls: Vector[ProviderCall],
    val coverage: Vector[SentenceCoverage],
    val summaryCoverage: SummaryCoverage
):
  def counts: CoverageCounts =
    coverage.foldLeft(CoverageCounts(0, 0, 0, 0)) { (acc, row) =>
      row match
        case SentenceCoverage.Proposed(_, _)     => acc.copy(proposed = acc.proposed + 1)
        case SentenceCoverage.Abstained(_, _, _) => acc.copy(abstained = acc.abstained + 1)
        case SentenceCoverage.EmptyChart(_)      => acc.copy(emptyCharts = acc.emptyCharts + 1)
        case SentenceCoverage.NoChart(_)         => acc.copy(noCharts = acc.noCharts + 1)
    }

  override def equals(other: Any): Boolean = other match
    case that: ChartProposals =>
      evidence == that.evidence &&
      situations == that.situations &&
      contexts == that.contexts &&
      summary == that.summary &&
      memberships == that.memberships &&
      causal == that.causal &&
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
      calls,
      coverage,
      summaryCoverage
    ).##

  override def toString: String =
    val c = counts
    s"ChartProposals(proposed=${c.proposed}, abstained=${c.abstained}, " +
      s"empty=${c.emptyCharts}, noChart=${c.noCharts}, calls=${calls.size})"

object ChartProposals:
  private[document] def derived(
      evidence: Vector[Evidence],
      situations: Vector[SituationAttempt],
      contexts: Vector[ContextAssignmentAttempt],
      summary: StorySummaryAttempt,
      memberships: Vector[SegmentMembershipAttempt],
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
      calls,
      coverage,
      summaryCoverage
    )

/** Deterministic, receipted proposal provider from sentence charts to compiler input.
  *
  * Why it exists: ADR 0005's compiler consumes typed proposals, and until now the only provider was
  * a one-sentence lexical fixture. This provider reads every chart of a story and emits the
  * situation, context-assignment, segment-membership, and summary attempts the compiler needs, with
  * typed abstention wherever a chart has no admissible root. It never reads the sentence text for
  * content: every content word comes from the chart. Its rules are [[RulesText]], whose checksum is
  * the prompt-package checksum and the provenance config hash, so a rule change changes every
  * receipt.
  */
object ChartProposalProvider:
  val Stage: StageId = StageId.unsafe("chart-proposal-provider")
  val Fingerprint: storymodel4s.core.Fingerprint =
    storymodel4s.core.Fingerprint.unsafe("storymodel4s:chart-proposal-provider:0.1")

  /** Provider identity on every receipt; the resolver counts agreement by this triple. */
  val ProviderName: String = "chart-proposal-provider"
  val ModelName: String = "chart-rules"
  val Version: String = "1"
  val CalibrationModel: String = "chart-rule-v1"

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

  /** Rule names recorded on receipts. */
  val SituationRule: String = "focus-situation-rule"
  val ContextRule: String = "narrated-world-context-rule"
  val MembershipRule: String = "primary-story-membership-rule"
  val SummaryRule: String = "title-summary-rule"
  val AbstainSituationRule: String = "abstain-situation-rule"
  val AbstainContextRule: String = "abstain-context-rule"
  val AbstainMembershipRule: String = "abstain-membership-rule"
  val AbstainSummaryRule: String = "abstain-summary-rule"

  /** The mapping rules, verbatim. Its checksum is the prompt-package checksum and the provenance
    * config hash.
    */
  val RulesText: String =
    s"""chart-proposal-provider rules, version 1
       |
       |root: the situation root of a sentence is the chart focus and nothing else. There is no
       |  fallback to another predicate; a chart whose focus is inadmissible is abstained.
       |admissible: the focus concept has kind Predicate and is not held by any embedding.
       |kind: State when the focus concept carries a frame whose id is in the closed set
       |  {${StateFrames.toVector.sorted.mkString(", ")}}; otherwise Event. Lexical statives
       |  without such a frame are Event in this version.
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
       |  the sentence span is used, recorded as span-source=sentence.
       |summary: the source title with evidence spanning the whole canonical text; no title or a
       |  blank title yields an abstained summary attempt.
       |abstention: an inadmissible root yields one abstained attempt in each of the situation,
       |  context, and membership families at the anchor (the focus when present, else the lowest
       |  concept id). An empty chart or a sentence without a chart yields no attempt and a
       |  coverage row only.
       |causal: no causal attempt is emitted; absent pairs are not evaluated.
       |bundles: one proposed value per attempt with raw score 1.0, source support 1.0 over the
       |  evidence spans, agreement 1.0, and calibration $CalibrationModel at probability 1.0;
       |  abstained attempts carry no value, support 0.0, no spans, agreement 0.0, no calibration.
       |policy: AcceptancePolicy.Conservative, except ContextAssignment and SegmentMembership at
       |  requireAgreement = 1, because one deterministic program is one provider.
       |""".stripMargin

  val Prompt: PromptPackageRef =
    PromptPackageRef("chart-rules", Version, Checksum.ofText(RulesText))

  /** Conservative everywhere, with the two high-impact families this single provider can satisfy
    * alone lowered to one agreeing provider. Recorded in [[RulesText]], so in every config hash.
    */
  val Policy: AcceptancePolicy =
    val single = FamilyPolicy
      .of(Probability.unsafe(0.9), Probability.unsafe(0.5), 1, true, true, true)
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    val base = AcceptancePolicy.Conservative
    base.copy(perFamily =
      base.perFamily ++ Map(
        ClaimFamily.ContextAssignment -> single,
        ClaimFamily.SegmentMembership -> single
      )
    )

  private val ChartPath = "chart-proposal-provider/charts"
  private val AlignmentPath = "chart-proposal-provider/alignments"

  private final case class SentenceOutcome(
      coverage: SentenceCoverage,
      evidence: Option[Evidence],
      situation: Option[SituationAttempt],
      context: Option[ContextAssignmentAttempt],
      membership: Option[SegmentMembershipAttempt],
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
      ordered <- checkCharts(atlas, charts)
      _ <- ordered.traverse_((unit, ev) => checkAlignments(source, atlas, unit, ev.chart))
      outcomes <- ordered.traverse((unit, ev) => sentenceOutcome(source, unit, ev.chart))
      summary <- summaryOutcome(source)
    yield
      val byUnit = outcomes.map(o => o.coverage.sentence -> o).toMap
      val coverage = atlas.sentences
        .sortBy(_.ordinal)
        .map(unit =>
          byUnit.get(unit.id).map(_.coverage).getOrElse(SentenceCoverage.NoChart(unit.id))
        )
      ChartProposals.derived(
        (outcomes.flatMap(_.evidence) ++ summary.evidence.toVector).sortBy(_.id),
        outcomes.flatMap(_.situation).sortBy(_.source.key),
        outcomes.flatMap(_.context).sortBy(_.source.key),
        summary.attempt,
        outcomes.flatMap(_.membership).sortBy(_.member.key),
        (outcomes.flatMap(_.calls) :+ summary.call).sortBy(renderCall),
        coverage,
        summary.coverage
      )

  /** [[propose]] and bind the result into checked compiler input with this provider's receipt. */
  def input(
      source: StorySource,
      atlas: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionEvidence)],
      parserStage: Option[(StageId, Checksum)],
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
          parserStage.toVector :+ (Stage -> stageDigest),
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

  private def checkCharts(
      atlas: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionEvidence)]
  ): Either[DomainError, Vector[(SurfaceUnit, PropositionEvidence)]] =
    val sorted = charts.sortBy(_._1)
    val duplicate = sorted.groupBy(_._1).collectFirst { case (id, xs) if xs.size > 1 => id }
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
            case Some(unit) => Right(unit -> ev)
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
      chart: PropositionChart[Checked]
  ): Either[DomainError, SentenceOutcome] =
    val checksum = Canonical.checksum(chart)
    if chart.isEmpty then
      Right(
        SentenceOutcome(SentenceCoverage.EmptyChart(unit.id), None, None, None, None, Vector.empty)
      )
    else
      chart.focus match
        case None =>
          val anchor = ChartNodeRef(unit.id, chart.conceptIds.head)
          Right(abstain(source, unit, checksum, anchor, AbstentionReason.NoFocus))
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
            case Some(concept) if !concept.isPredicate =>
              Right(
                abstain(
                  source,
                  unit,
                  checksum,
                  root,
                  AbstentionReason.FocusNotPredicate(concept.kind)
                )
              )
            case Some(_) if chart.isEmbedded(focus) =>
              Right(abstain(source, unit, checksum, root, AbstentionReason.FocusEmbedded))
            case Some(concept) => proposeRoot(source, unit, chart, checksum, root, concept)

  private def proposeRoot(
      source: StorySource,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked],
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
        val (spans, spanSource) = supportSpans(unit, chart)
        val evidence = Evidence(
          EvidenceId.unsafe(
            ContentAddress.of("chart-proposal-evidence", unit.id.value, checksum.hex)
          ),
          Some(spans),
          Set.empty,
          Fingerprint,
          Stage
        )
        val kind =
          if concept.frame.exists(f => StateFrames(f.id)) then SituationKind.State
          else SituationKind.Event
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
        val params = chartParams(unit, checksum) + ("span-source" -> spanSource)
        val (situation, situationCall) = proposed(
          source,
          SituationRule,
          unit.id.value,
          checksum,
          value,
          evidence,
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
          Vector("primary-story-member", root.key),
          params
        )
        Right(
          SentenceOutcome(
            SentenceCoverage.Proposed(unit.id, root),
            Some(evidence),
            Some(SituationAttempt(root, situation)),
            Some(ContextAssignmentAttempt(root, context)),
            Some(SegmentMembershipAttempt(root, membership)),
            Vector(situationCall, contextCall, membershipCall)
          )
        )

  private def abstain(
      source: StorySource,
      unit: SurfaceUnit,
      checksum: Checksum,
      anchor: ChartNodeRef,
      reason: AbstentionReason
  ): SentenceOutcome =
    val params = chartParams(unit, checksum) + ("reason" -> reason.render)
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
    SentenceOutcome(
      SentenceCoverage.Abstained(unit.id, anchor, reason),
      None,
      Some(SituationAttempt(anchor, situation)),
      Some(ContextAssignmentAttempt(anchor, context)),
      Some(SegmentMembershipAttempt(anchor, membership)),
      Vector(situationCall, contextCall, membershipCall)
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
          val evidence = Evidence(
            EvidenceId
              .unsafe(ContentAddress.of("chart-proposal-evidence", scope, scopeChecksum.hex)),
            Some(SpanSet.one(SpanRef(None, whole))),
            Set.empty,
            Fingerprint,
            Stage
          )
          val params =
            Map("scope" -> scope, "rule" -> SummaryRule, "span-source" -> "canonical-text")
          val (bundle, call) = proposed(
            source,
            SummaryRule,
            scope,
            scopeChecksum,
            StorySummaryProposal(title),
            evidence,
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

  /** Alignment spans of every alignment naming a non-embedded concept; the sentence otherwise. */
  private def supportSpans(unit: SurfaceUnit, chart: PropositionChart[Checked]): (SpanSet, String) =
    val refs = chart.alignments
      .filter(_.target.conceptIds.exists(id => !chart.isEmbedded(id)))
      .flatMap(_.spans.refs.toVector)
    SpanSet.of(refs) match
      case Some(set) => (set, "chart-alignments")
      case None      => (SpanSet.one(SpanRef(Some(unit.id), unit.span)), "sentence")

  private def polarity(value: ChartPolarity): StoryPolarity = value match
    case ChartPolarity.Positive => StoryPolarity.Positive
    case ChartPolarity.Negative => StoryPolarity.Negative
    case ChartPolarity.Unknown  => StoryPolarity.Unknown

  private def chartParams(unit: SurfaceUnit, checksum: Checksum): Map[String, String] =
    Map("sentence" -> unit.id.value, "chart" -> checksum.hex)

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

  private def proposed[A](
      source: StorySource,
      rule: String,
      scope: String,
      checksum: Checksum,
      value: A,
      evidence: Evidence,
      render: Vector[String],
      params: Map[String, String]
  ): (EvidenceBundle[A], ProviderCall) =
    val task = taskId(rule, scope, checksum)
    val call = providerCall(source, rule, render, params)
    val proposal = AgentProposal.proposed(
      task,
      value,
      NonEmptyVector.one(EvidenceRef.Inline(evidence)),
      Some(RawScore.unsafe(1.0)),
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
        Vector(CandidateCalibration(value, Probability.One, CalibrationModel))
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
