package storymodel4s.document

import cats.data.{NonEmptySet, NonEmptyVector}
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.proposition.{ConceptKind, PropositionEvidence}
import storymodel4s.story.{Polarity as StoryPolarity, *}

/** Whether an accepted proposition denotes an event or a state.
  *
  * Why: providers propose this distinction, but they never construct a trusted [[SituationNode]].
  */
enum SituationKind:
  case Event, State

/** Provider-proposed fields of one situation, before identity, evidence metadata, and context are
  * created by the deterministic compiler.
  */
final case class SituationProposal(
    kind: SituationKind,
    predicate: Predicate,
    description: String,
    polarity: StoryPolarity,
    modality: Modality,
    aspect: Option[Aspect]
)

/** Provider-proposed root-segment summary. The compiler supplies its identity and evidence. */
final case class StorySummaryProposal(text: String)

/** Provider-proposed causal relation. Endpoints live on [[CausalAttempt]], so competing values can
  * disagree about relation kind without changing the claim being resolved.
  */
final case class CausalProposal(relation: CausalRelation)

/** Restricted first-slice context proposal; providers cannot mint arbitrary context or status. */
enum ContextAssignmentProposal:
  case NarratedWorld

/** Restricted first-slice hierarchy proposal; acceptance denotes weight-one primary membership. */
enum SegmentMembershipProposal:
  case PrimaryStoryMember

/** All candidates and evidence for the situation anchored at `source`. */
final case class SituationAttempt(
    source: ChartNodeRef,
    bundle: EvidenceBundle[SituationProposal]
)

/** All candidates and evidence for the story-root summary. */
final case class StorySummaryAttempt(bundle: EvidenceBundle[StorySummaryProposal])

/** Context assignment candidate for one chart-anchored situation. */
final case class ContextAssignmentAttempt(
    source: ChartNodeRef,
    bundle: EvidenceBundle[ContextAssignmentProposal]
)

/** Primary story-segment membership candidate for one chart-anchored situation. */
final case class SegmentMembershipAttempt(
    member: ChartNodeRef,
    bundle: EvidenceBundle[SegmentMembershipProposal]
)

/** Sparse causal candidate. Pairs absent from this vector were not evaluated. */
final case class CausalAttempt(
    from: ChartNodeRef,
    to: ChartNodeRef,
    bundle: EvidenceBundle[CausalProposal]
)

/** Typed address of one derivation attempt. It is the support universe of the compiler receipt. */
enum NarrativeCandidateAddress:
  case Situation(source: ChartNodeRef)
  case ContextAssignment(source: ChartNodeRef)
  case StorySummary(story: StoryId)
  case SegmentMembership(story: StoryId, member: ChartNodeRef)
  case Causal(from: ChartNodeRef, to: ChartNodeRef)
  case TrajectoryStep(from: ChartNodeRef, to: ChartNodeRef)

  def render: String = this match
    case Situation(source)             => s"situation:${source.key}"
    case ContextAssignment(source)     => s"context-assignment:${source.key}"
    case StorySummary(id)              => s"story-summary:${id.value}"
    case SegmentMembership(id, member) => s"segment-membership:${id.value}:${member.key}"
    case Causal(from, to)              => s"causal:${from.key}->${to.key}"
    case TrajectoryStep(from, to)      => s"trajectory:${from.key}->${to.key}"

object NarrativeCandidateAddress:
  given Ordering[NarrativeCandidateAddress] = Ordering.by(_.render)

/** Why an evaluated candidate produced no narrative value. */
enum DerivationGapReason:
  case Alternatives
  case Unresolved(reason: ResolutionFailure)
  case Rejected(reason: RejectionReason)
  case MissingRawScore
  case MissingSpanEvidence
  case MissingUpstream(addresses: Vector[NarrativeCandidateAddress])
  case UnsupportedEmbeddedContext(source: ChartNodeRef)
  case UnsupportedTrajectoryInputs(families: Vector[ClaimFamily])
  case InvalidAccepted(error: DomainError)

  def render: String = this match
    case Alternatives               => "alternatives"
    case Unresolved(reason)         => s"unresolved:$reason"
    case Rejected(reason)           => s"rejected:$reason"
    case MissingRawScore            => "missing-raw-score"
    case MissingSpanEvidence        => "missing-span-evidence"
    case MissingUpstream(addresses) =>
      s"missing-upstream:${addresses.map(_.render).sorted.mkString(",")}"
    case UnsupportedEmbeddedContext(source)    => s"embedded-context-unsupported:${source.key}"
    case UnsupportedTrajectoryInputs(families) =>
      s"trajectory-inputs-unsupported:${families.mkString(",")}"
    case InvalidAccepted(error) => s"invalid-accepted:${error.message}"

/** One missing derivation, retained in the compiled artifact rather than replaced by a value. */
final case class DerivationGap(
    stage: StageId,
    family: ClaimFamily,
    target: NarrativeCandidateAddress,
    reason: DerivationGapReason,
    upstreamClaims: Set[ClaimId],
    evidence: Vector[EvidenceRef]
)

/** Whether a nominated candidate emitted a claim. */
enum DerivationDisposition:
  case Emitted(claim: ClaimId)
  case NotEmitted(reason: DerivationGapReason)

/** Receipt entry for one sparse candidate the compiler evaluated. */
final case class DerivationAttempt(
    target: NarrativeCandidateAddress,
    family: ClaimFamily,
    disposition: DerivationDisposition
)

/** Machine-readable support of narrative compilation.
  *
  * `candidateSet` identifies the sparse nominated universe. An absent pair outside that universe is
  * not evaluated; a gap is evaluated but not emitted.
  */
final class DerivationReceipt private (
    val candidateSet: Checksum,
    val attempts: Vector[DerivationAttempt],
    val emittedClaims: Map[ClaimId, ClaimMeta],
    val gaps: Vector[DerivationGap]
):
  override def equals(other: Any): Boolean = other match
    case that: DerivationReceipt =>
      candidateSet == that.candidateSet &&
      attempts == that.attempts &&
      emittedClaims == that.emittedClaims &&
      gaps == that.gaps
    case _ => false

  override def hashCode(): Int = (candidateSet, attempts, emittedClaims, gaps).##

  override def toString: String =
    s"DerivationReceipt(candidateSet=${candidateSet.hex}, attempts=${attempts.size}, " +
      s"emitted=${emittedClaims.size}, gaps=${gaps.size})"

object DerivationReceipt:
  private[document] def of(
      candidateSet: Checksum,
      attempts: Vector[DerivationAttempt],
      emittedClaims: Map[ClaimId, ClaimMeta],
      gaps: Vector[DerivationGap]
  ): Either[DomainError, DerivationReceipt] =
    val targets = attempts.map(_.target)
    val emittedByAttempts = attempts.collect {
      case DerivationAttempt(
            _,
            _,
            DerivationDisposition.Emitted(id)
          ) =>
        id
    }.toSet
    val failure =
      if targets.distinct.size != targets.size then
        Some("derivation attempts must have unique targets")
      else if !gaps.forall(g => targets.contains(g.target)) then
        Some("every gap must name an attempted target")
      else if gaps.map(_.target).distinct.size != gaps.size then
        Some("each attempted target may have at most one gap")
      else if emittedClaims.exists((id, meta) => id != meta.id) then
        Some("every emitted-claim ledger key must equal its ClaimMeta id")
      else if !emittedByAttempts.subsetOf(emittedClaims.keySet) then
        Some("every emitted attempt must name a claim in emittedClaims")
      else None
    failure
      .toLeft(new DerivationReceipt(candidateSet, attempts, emittedClaims, gaps))
      .left
      .map(DomainError.InvariantViolation("narrative-derivation", _))

/** Resolver state for one situation candidate. */
final case class SituationResolution(
    source: ChartNodeRef,
    bundle: EvidenceBundle[SituationProposal],
    state: ResolutionState[SituationProposal]
):
  def target: NarrativeCandidateAddress = NarrativeCandidateAddress.Situation(source)

/** Resolver state for one narrated-world assignment candidate. */
final case class ContextAssignmentResolution(
    source: ChartNodeRef,
    bundle: EvidenceBundle[ContextAssignmentProposal],
    state: ResolutionState[ContextAssignmentProposal]
):
  def target: NarrativeCandidateAddress = NarrativeCandidateAddress.ContextAssignment(source)

/** Resolver state for the single story-summary candidate. */
final case class StorySummaryResolution(
    story: StoryId,
    bundle: EvidenceBundle[StorySummaryProposal],
    state: ResolutionState[StorySummaryProposal]
):
  def target: NarrativeCandidateAddress = NarrativeCandidateAddress.StorySummary(story)

/** Resolver state for one primary story-segment membership candidate. */
final case class SegmentMembershipResolution(
    story: StoryId,
    member: ChartNodeRef,
    bundle: EvidenceBundle[SegmentMembershipProposal],
    state: ResolutionState[SegmentMembershipProposal]
):
  def target: NarrativeCandidateAddress =
    NarrativeCandidateAddress.SegmentMembership(story, member)

/** Resolver state for one sparse causal candidate. */
final case class CausalResolution(
    from: ChartNodeRef,
    to: ChartNodeRef,
    bundle: EvidenceBundle[CausalProposal],
    state: ResolutionState[CausalProposal]
):
  def target: NarrativeCandidateAddress = NarrativeCandidateAddress.Causal(from, to)

/** Typed resolution records for the first compiler slice. */
final case class NarrativeResolutions(
    situations: Vector[SituationResolution],
    contexts: Vector[ContextAssignmentResolution],
    summary: StorySummaryResolution,
    memberships: Vector[SegmentMembershipResolution],
    causal: Vector[CausalResolution]
)

/** Failure before or during deterministic compilation. Non-accepted claims are not failures; they
  * become [[DerivationGap]] values in a successful [[NarrativeCompilation]].
  */
enum NarrativeCompilerError:
  case InvalidInput(errors: NonEmptyVector[DomainError])
  case MentionConstruction(error: DocumentError)
  case ProjectionConstruction(error: DocumentError)
  case ProjectionValidation(violations: NonEmptyVector[ProjectionViolation])
  case DerivationConstruction(error: DomainError)
  case CompilationConstruction(error: DomainError)
  case ClaimConstruction(error: DomainError)

  def message: String = this match
    case InvalidInput(errors)             => errors.toVector.map(_.message).mkString("; ")
    case MentionConstruction(error)       => error.message
    case ProjectionConstruction(error)    => error.message
    case ProjectionValidation(violations) =>
      violations.toVector.mkString("projection validation: ", ", ", "")
    case DerivationConstruction(error)  => error.message
    case CompilationConstruction(error) => error.message
    case ClaimConstruction(error)       => error.message

/** Checked input to the pure narrative compiler.
  *
  * Construct with [[NarrativeCompilerInput.of]]. Its constructor is private because source, atlas,
  * chart, receipt, evidence, and attempt references must agree before resolution starts.
  */
final class NarrativeCompilerInput private (
    val source: StorySource,
    val atlas: SurfaceAtlas,
    val localCharts: Vector[(SurfaceUnitId, PropositionEvidence)],
    val mentionGraph: MentionGraph,
    val evidence: Map[EvidenceId, Evidence],
    val situations: Vector[SituationAttempt],
    val contexts: Vector[ContextAssignmentAttempt],
    val summary: StorySummaryAttempt,
    val memberships: Vector[SegmentMembershipAttempt],
    val causal: Vector[CausalAttempt],
    val policy: AcceptancePolicy,
    val receipt: BuildReceipt,
    val provenance: Provenance
)

object NarrativeCompilerInput:
  /** Validate and canonically order all inputs. */
  def of(
      source: StorySource,
      atlas: SurfaceAtlas,
      localCharts: Iterable[(SurfaceUnitId, PropositionEvidence)],
      evidence: Iterable[Evidence],
      situations: Iterable[SituationAttempt],
      contexts: Iterable[ContextAssignmentAttempt],
      summary: StorySummaryAttempt,
      memberships: Iterable[SegmentMembershipAttempt],
      causal: Iterable[CausalAttempt],
      policy: AcceptancePolicy,
      receipt: BuildReceipt,
      provenance: Provenance
  ): Either[NarrativeCompilerError, NarrativeCompilerInput] =
    val charts = localCharts.toVector.sortBy(_._1)
    val evs = evidence.toVector
    val situationVec = situations.toVector.sortBy(_.source.key)
    val contextVec = contexts.toVector.sortBy(_.source.key)
    val membershipVec = memberships.toVector.sortBy(_.member.key)
    val causalVec = causal.toVector.sortBy(a => (a.from.key, a.to.key))
    val errors = Vector.newBuilder[DomainError]
    def invalid(path: String, reason: String): Unit =
      errors += DomainError.InvariantViolation(path, reason)

    if atlas.source.id != source.id || atlas.source.canonicalChecksum != source.canonicalChecksum
    then invalid("compiler/atlas", "atlas belongs to a different source identity or checksum")
    if receipt.storyId != source.id || receipt.sourceChecksum != source.canonicalChecksum then
      invalid("compiler/receipt", "receipt story or source checksum does not match")

    val duplicateChart = charts.groupBy(_._1).collectFirst { case (id, xs) if xs.size > 1 => id }
    duplicateChart.foreach(id => invalid("compiler/charts", s"duplicate chart ${id.value}"))
    charts.foreach { (unit, ev) =>
      if !atlas.byId.get(unit).exists(_.kind == SurfaceUnitKind.Sentence) then
        invalid("compiler/charts", s"${unit.value} is not a sentence in the atlas")
      if !ev.chart.sentence.contains(unit) then
        invalid("compiler/charts", s"chart sentence does not equal ${unit.value}")
    }

    val duplicateEvidence = evs.groupBy(_.id).collectFirst { case (id, xs) if xs.size > 1 => id }
    duplicateEvidence.foreach(id => invalid("compiler/evidence", s"duplicate ${id.value}"))
    val evidenceMap = evs.map(e => e.id -> e).toMap
    evs.foreach { evidence =>
      if evidence.upstream.nonEmpty then
        invalid(
          "compiler/evidence",
          s"${evidence.id.value} cites upstream claims but this slice has no upstream claim ledger"
        )
      validateSpans(evidence.spans, source, atlas, "compiler/evidence", invalid)
    }

    val duplicateSituation = situationVec
      .groupBy(_.source)
      .collectFirst { case (source, xs) if xs.size > 1 => source }
    duplicateSituation.foreach(source => invalid("compiler/situations", s"duplicate ${source.key}"))

    val chartMap = charts.map(_._1).toSet
    def known(ref: ChartNodeRef): Boolean =
      chartMap(ref.sentence) && charts
        .find(_._1 == ref.sentence)
        .exists(_._2.chart.concept(ref.concept).nonEmpty)

    situationVec.foreach { attempt =>
      if !known(attempt.source) then
        invalid("compiler/situations", s"unknown chart node ${attempt.source.key}")
      validateBundle(attempt.bundle, evidenceMap, source, atlas, "compiler/situations", invalid)
      candidateValues(attempt.bundle).foreach(validateSituation(_, "compiler/situations", invalid))
    }

    val duplicateContext = contextVec
      .groupBy(_.source)
      .collectFirst { case (source, xs) if xs.size > 1 => source }
    duplicateContext.foreach(source => invalid("compiler/contexts", s"duplicate ${source.key}"))
    contextVec.foreach { attempt =>
      if !known(attempt.source) then
        invalid("compiler/contexts", s"unknown chart node ${attempt.source.key}")
      validateBundle(attempt.bundle, evidenceMap, source, atlas, "compiler/contexts", invalid)
    }

    val duplicateMembership = membershipVec
      .groupBy(_.member)
      .collectFirst { case (member, xs) if xs.size > 1 => member }
    duplicateMembership.foreach(member =>
      invalid("compiler/memberships", s"duplicate ${member.key}")
    )
    membershipVec.foreach { attempt =>
      if !known(attempt.member) then
        invalid("compiler/memberships", s"unknown chart node ${attempt.member.key}")
      validateBundle(attempt.bundle, evidenceMap, source, atlas, "compiler/memberships", invalid)
    }

    val situationSources = situationVec.map(_.source).toSet
    if contextVec.map(_.source).toSet != situationSources then
      invalid("compiler/contexts", "exactly one context attempt is required per situation attempt")
    if membershipVec.map(_.member).toSet != situationSources then
      invalid(
        "compiler/memberships",
        "exactly one segment-membership attempt is required per situation attempt"
      )

    validateBundle(summary.bundle, evidenceMap, source, atlas, "compiler/summary", invalid)
    candidateValues(summary.bundle).foreach { value =>
      if value.text.trim.isEmpty then invalid("compiler/summary", "summary text must be nonblank")
    }

    val duplicateCausal = causalVec
      .groupBy(a => (a.from, a.to))
      .collectFirst { case ((from, to), xs) if xs.size > 1 => (from, to) }
    duplicateCausal.foreach((from, to) =>
      invalid("compiler/causal", s"duplicate ${from.key}->${to.key}")
    )
    causalVec.foreach { attempt =>
      if attempt.from == attempt.to then invalid("compiler/causal", "self relation candidate")
      if !situationSources(attempt.from) then
        invalid("compiler/causal", s"unknown source endpoint ${attempt.from.key}")
      if !situationSources(attempt.to) then
        invalid("compiler/causal", s"unknown target endpoint ${attempt.to.key}")
      validateBundle(attempt.bundle, evidenceMap, source, atlas, "compiler/causal", invalid)
    }

    val allBundles: Vector[EvidenceBundle[?]] =
      situationVec.map(_.bundle) ++ contextVec.map(_.bundle) ++ Vector(summary.bundle) ++
        membershipVec.map(_.bundle) ++ causalVec.map(_.bundle)
    val declaredTasks = allBundles.flatMap(_.proposals.map(_.taskId)).toSet
    val chartNodes = charts.flatMap { (sentence, chart) =>
      chart.chart.concepts.keys.map(concept => ChartNodeRef(sentence, concept).key)
    }.toSet
    val sentenceIds = atlas.sentences.map(_.id.value).toSet
    val tokenIds = atlas.tokens.map(_.id.value).toSet
    val chartIds = charts.map(_._1.value).toSet
    allBundles.foreach { bundle =>
      bundle.proposals.flatMap(_.conflicts).foreach { conflict =>
        if conflict.reason.trim.isEmpty then
          invalid("compiler/conflicts", "reason must be nonblank")
        conflict.target match
          case ConflictTarget.Claim(id) =>
            invalid(
              "compiler/conflicts",
              s"claim ${id.value} cannot be referenced without an upstream claim ledger"
            )
          case ConflictTarget.Task(id) if !declaredTasks(id) =>
            invalid("compiler/conflicts", s"unknown task ${id.value}")
          case ConflictTarget.Node(ref) if !chartNodes(ref) =>
            invalid("compiler/conflicts", s"unknown chart node $ref")
          case _ => ()
      }
      bundle.findings.flatMap(_.targets).foreach { target =>
        val knownTarget = target.kind match
          case TargetKind.Node                    => chartNodes(target.id)
          case TargetKind.Sentence                => sentenceIds(target.id)
          case TargetKind.Token                   => tokenIds(target.id)
          case TargetKind.Chart                   => chartIds(target.id)
          case TargetKind.Edge | TargetKind.Claim => false
        if !knownTarget then
          invalid("compiler/findings", s"unknown ${target.kind} target ${target.id}")
      }
    }

    val found = errors.result()
    NonEmptyVector.fromVector(found) match
      case Some(es) => Left(NarrativeCompilerError.InvalidInput(es))
      case None     =>
        MentionGraph.of(charts.map((id, ev) => id -> ev.chart)) match
          case Left(e) =>
            Left(NarrativeCompilerError.InvalidInput(NonEmptyVector.one(e)))
          case Right(graph) =>
            val canonicalProvenance = provenance.copy(
              calls = provenance.calls.distinct.sortBy(NarrativeCompiler.renderProviderCall)
            )
            Right(
              new NarrativeCompilerInput(
                source,
                atlas,
                charts,
                graph,
                evidenceMap,
                situationVec,
                contextVec,
                summary,
                membershipVec,
                causalVec,
                policy,
                receipt,
                canonicalProvenance
              )
            )

  private def validateBundle[A](
      bundle: EvidenceBundle[A],
      evidence: Map[EvidenceId, Evidence],
      source: StorySource,
      atlas: SurfaceAtlas,
      path: String,
      invalid: (String, String) => Unit
  ): Unit =
    def unitInterval(value: Double, field: String): Unit =
      if !value.isFinite || value < 0.0 || value > 1.0 then
        invalid(path, s"$field must be finite and in [0, 1], found $value")

    unitInterval(bundle.sourceSupport.score, "source support")
    unitInterval(bundle.agreementScore, "agreement score")
    if bundle.structural.valid && bundle.structural.violations.nonEmpty then
      invalid(path, "structurally valid bundle must not carry violations")
    val duplicateCalibration = bundle.calibrations
      .groupBy(_.value)
      .collectFirst { case (_, values) if values.size > 1 => values.head }
    duplicateCalibration.foreach(_ => invalid(path, "candidate has more than one calibration"))
    bundle.calibrations.foreach { calibration =>
      if calibration.model.trim.isEmpty then invalid(path, "calibration model must be nonblank")
    }

    val refs = bundle.proposals.flatMap(_.evidence) ++ bundle.findings.flatMap(_.evidence)
    refs.foreach {
      case EvidenceRef.ById(id) if !evidence.contains(id) =>
        invalid(path, s"unknown evidence ${id.value}")
      case EvidenceRef.Inline(ev) =>
        if ev.upstream.nonEmpty then
          invalid(path, s"inline evidence ${ev.id.value} cites unavailable upstream claims")
        evidence.get(ev.id).foreach { recorded =>
          if recorded != ev then
            invalid(path, s"inline evidence ${ev.id.value} conflicts with ledger")
        }
        validateSpans(ev.spans, source, atlas, path, invalid)
      case _ => ()
    }
    validateSpans(bundle.sourceSupport.spans, source, atlas, path, invalid)
    bundle.proposals.foreach { proposal =>
      if proposal.taskId != proposal.receipt.taskId then
        invalid(path, s"proposal task ${proposal.taskId.value} disagrees with its receipt")
      if proposal.receipt.call.inputChecksum != source.canonicalChecksum then
        invalid(path, s"proposal task ${proposal.taskId.value} used a different source checksum")
    }

  private def candidateValues[A](bundle: EvidenceBundle[A]): Vector[A] =
    (bundle.proposals.flatMap(_.value) ++ bundle.calibrations.map(_.value)).distinct

  private def validateSituation(
      value: SituationProposal,
      path: String,
      invalid: (String, String) => Unit
  ): Unit =
    if value.predicate.lemma.trim.isEmpty then invalid(path, "predicate lemma must be nonblank")
    if value.predicate.gloss.trim.isEmpty then invalid(path, "predicate gloss must be nonblank")
    if value.predicate.frame.exists(_.trim.isEmpty) then
      invalid(path, "predicate frame must be nonblank when present")
    if value.description.trim.isEmpty then invalid(path, "situation description must be nonblank")
    if value.modality != Modality.Asserted then
      invalid(path, "first-slice narrated-world situations must have Asserted modality")
    if value.kind == SituationKind.State && value.aspect.nonEmpty then
      invalid(path, "state proposals cannot carry event aspect")

  private def validateSpans(
      spans: Option[SpanSet],
      source: StorySource,
      atlas: SurfaceAtlas,
      path: String,
      invalid: (String, String) => Unit
  ): Unit =
    spans.toVector.flatMap(_.refs.toVector).foreach { ref =>
      val span = ref.span
      if span.endExclusive > source.canonicalText.length then
        invalid(path, s"span $span exceeds source length ${source.canonicalText.length}")
      if !isCodePointBoundary(source.canonicalText, span.start) ||
        !isCodePointBoundary(source.canonicalText, span.endExclusive)
      then invalid(path, s"span $span cuts a UTF-16 surrogate pair")
      ref.unit.foreach { unitId =>
        atlas.byId.get(unitId) match
          case None => invalid(path, s"span names unknown surface unit ${unitId.value}")
          case Some(unit) if !unit.span.contains(span) =>
            invalid(path, s"span $span escapes surface unit ${unitId.value} at ${unit.span}")
          case Some(_) => ()
      }
    }

  private def isCodePointBoundary(text: String, index: Int): Boolean =
    def highSurrogate(value: Char): Boolean = value >= '\uD800' && value <= '\uDBFF'
    def lowSurrogate(value: Char): Boolean = value >= '\uDC00' && value <= '\uDFFF'
    index <= 0 || index >= text.length ||
    !(highSurrogate(text.charAt(index - 1)) && lowSurrogate(text.charAt(index)))

/** Durable output of source-to-narrative compilation.
  *
  * A partial compilation still contains its draft, validation report, typed resolutions, and
  * derivation gaps. Only `validation.validated` may enter alignment.
  */
final class NarrativeCompilation private (
    val mentionGraph: MentionGraph,
    val entityMentions: MentionTable[EntityK],
    val situationMentions: MentionTable[SituationK],
    val entityPartition: CorefPartition[EntityK],
    val situationPartition: CorefPartition[SituationK],
    val projections: ProjectionIndex,
    val resolutions: NarrativeResolutions,
    val derivation: DerivationReceipt,
    val evidenceLedger: Map[EvidenceId, Evidence],
    val provenance: Provenance,
    val draft: StoryModel[ModelStatus.Draft],
    val validation: ValidationOutcome,
    val receipt: BuildReceipt,
    val fingerprint: Checksum
):
  def validated: Option[StoryModel[ModelStatus.Validated]] = validation.validated
  def isPartial: Boolean = derivation.gaps.nonEmpty || validated.isEmpty

  override def equals(other: Any): Boolean = other match
    case that: NarrativeCompilation =>
      mentionGraph == that.mentionGraph &&
      entityMentions == that.entityMentions &&
      situationMentions == that.situationMentions &&
      entityPartition == that.entityPartition &&
      situationPartition == that.situationPartition &&
      projections == that.projections &&
      resolutions == that.resolutions &&
      derivation == that.derivation &&
      evidenceLedger == that.evidenceLedger &&
      provenance == that.provenance &&
      draft == that.draft &&
      validation == that.validation &&
      receipt == that.receipt &&
      fingerprint == that.fingerprint
    case _ => false

  override def hashCode(): Int =
    (
      mentionGraph,
      entityMentions,
      situationMentions,
      entityPartition,
      situationPartition,
      projections,
      resolutions,
      derivation,
      evidenceLedger,
      provenance,
      draft,
      validation,
      receipt,
      fingerprint
    ).##

  override def toString: String =
    s"NarrativeCompilation(${fingerprint.short()}, partial=$isPartial, " +
      s"attempts=${derivation.attempts.size})"

object NarrativeCompilation:
  private[document] def of(
      mentionGraph: MentionGraph,
      entityMentions: MentionTable[EntityK],
      situationMentions: MentionTable[SituationK],
      entityPartition: CorefPartition[EntityK],
      situationPartition: CorefPartition[SituationK],
      projections: ProjectionIndex,
      resolutions: NarrativeResolutions,
      derivation: DerivationReceipt,
      evidenceLedger: Map[EvidenceId, Evidence],
      provenance: Provenance,
      draft: StoryModel[ModelStatus.Draft],
      validation: ValidationOutcome,
      receipt: BuildReceipt,
      fingerprint: Checksum
  ): Either[DomainError, NarrativeCompilation] =
    val resolvedTargets =
      resolutions.situations.map(_.target) ++
        resolutions.contexts.map(_.target) ++
        Vector(resolutions.summary.target) ++
        resolutions.memberships.map(_.target) ++
        resolutions.causal.map(_.target)
    val attemptedTargets = derivation.attempts.map(_.target).toSet
    val draftClaims = draft.claims.map(meta => meta.id -> meta).toMap
    val claimLedger = derivation.emittedClaims
    val emittedAttemptClaims = derivation.attempts.collect {
      case DerivationAttempt(_, _, DerivationDisposition.Emitted(id)) => id
    }.toSet
    val gapEvidence = derivation.gaps.flatMap(_.evidence)
    val missingGapEvidence = gapEvidence.collect {
      case EvidenceRef.ById(id) if !evidenceLedger.contains(id) => id
    }
    val retainedEvidence =
      evidenceLedger.values.toVector ++
        gapEvidence.collect { case EvidenceRef.Inline(evidence) => evidence } ++
        claimLedger.values.toVector.flatMap(_.evidence.toVector)
    val missingEvidenceUpstream = retainedEvidence.flatMap(_.upstream).toSet -- claimLedger.keySet
    val missingGapUpstream = derivation.gaps.flatMap(_.upstreamClaims).toSet -- claimLedger.keySet
    val failure =
      if draft.receipt != Some(receipt) then Some("draft and compilation receipts differ")
      else if validation.validated.exists(_ != draft) then
        Some("validated promotion does not represent the compilation draft")
      else if draftClaims.exists((id, meta) => claimLedger.get(id) != Some(meta)) then
        Some("derivation receipt omits claims present in the draft")
      else if !emittedAttemptClaims.subsetOf(claimLedger.keySet) then
        Some("an emitted derivation attempt has no retained ClaimMeta")
      else if missingGapEvidence.nonEmpty then
        Some("a derivation gap has a dangling evidence reference")
      else if missingEvidenceUpstream.nonEmpty then
        Some("retained evidence has a dangling upstream claim reference")
      else if missingGapUpstream.nonEmpty then
        Some("a derivation gap has a dangling upstream claim reference")
      else if !resolvedTargets.forall(attemptedTargets) then
        Some("derivation receipt omits a resolver target")
      else None
    failure
      .toLeft(
        new NarrativeCompilation(
          mentionGraph,
          entityMentions,
          situationMentions,
          entityPartition,
          situationPartition,
          projections,
          resolutions,
          derivation,
          evidenceLedger,
          provenance,
          draft,
          validation,
          receipt,
          fingerprint
        )
      )
      .left
      .map(DomainError.InvariantViolation("narrative-compilation", _))

/** Pure, deterministic compiler for the first unattended narrative slice. */
object NarrativeCompiler:
  val Stage: StageId = StageId.unsafe("narrative-compile")
  val Fingerprint: storymodel4s.core.Fingerprint =
    storymodel4s.core.Fingerprint.unsafe("storymodel4s:narrative-compiler:0.1")

  private final case class Material[A](value: A, meta: ClaimMeta, support: SpanSet)
  private final case class EmittedSituation(
      source: ChartNodeRef,
      canonical: CanonicalId[SituationK],
      mention: MentionId[SituationK],
      node: SituationNode,
      meta: ClaimMeta
  )

  /** Resolve proposals, retain every outcome, and assemble a draft plus validation report. */
  def compile(
      input: NarrativeCompilerInput
  ): Either[NarrativeCompilerError, NarrativeCompilation] =
    val situationRecords = input.situations.map { attempt =>
      val bundle = canonicalBundle(attempt.bundle, renderSituation)
      SituationResolution(
        attempt.source,
        bundle,
        Resolver.resolve(ClaimFamily.SituationMention, bundle, input.policy)
      )
    }
    val contextRecords = input.contexts.map { attempt =>
      val bundle = canonicalBundle(attempt.bundle, renderContextAssignment)
      ContextAssignmentResolution(
        attempt.source,
        bundle,
        Resolver.resolve(ClaimFamily.ContextAssignment, bundle, input.policy)
      )
    }
    val summaryBundle = canonicalBundle(input.summary.bundle, renderSummary)
    val summaryRecord = StorySummaryResolution(
      input.source.id,
      summaryBundle,
      Resolver.resolve(ClaimFamily.Summary, summaryBundle, input.policy)
    )
    val membershipRecords = input.memberships.map { attempt =>
      val bundle = canonicalBundle(attempt.bundle, renderSegmentMembership)
      SegmentMembershipResolution(
        input.source.id,
        attempt.member,
        bundle,
        Resolver.resolve(ClaimFamily.SegmentMembership, bundle, input.policy)
      )
    }
    val causalRecords = input.causal.map { attempt =>
      val bundle = canonicalBundle(attempt.bundle, renderCausal)
      CausalResolution(
        attempt.from,
        attempt.to,
        bundle,
        Resolver.resolve(ClaimFamily.CausalEdge, bundle, input.policy)
      )
    }
    val resolutions = NarrativeResolutions(
      situationRecords,
      contextRecords,
      summaryRecord,
      membershipRecords,
      causalRecords
    )

    val gaps = Vector.newBuilder[DerivationGap]
    val emittedByAddress = scala.collection.mutable.Map.empty[NarrativeCandidateAddress, ClaimId]
    val emittedSituations = Vector.newBuilder[EmittedSituation]

    val acceptedContextBySource = scala.collection.mutable.Map
      .empty[ChartNodeRef, Material[ContextAssignmentProposal]]
    contextRecords.foreach { record =>
      record.state match
        case accepted @ ResolutionState.Accepted(_, _, _) =>
          materialize(
            input,
            ClaimFamily.ContextAssignment,
            record.target,
            record.bundle,
            accepted,
            renderContextAssignment,
            _ => EpistemicStatus.Hypothesized
          ) match
            case Left(reason) =>
              gaps += gap(record.target, record.bundle, ClaimFamily.ContextAssignment, reason)
            case Right(material) =>
              acceptedContextBySource.update(record.source, material)
              emittedByAddress.update(record.target, material.meta.id)
        case state =>
          gaps += gap(record.target, record.bundle, ClaimFamily.ContextAssignment, gapReason(state))
    }

    situationRecords.foreach { record =>
      val source = record.source
      record.state match
        case accepted @ ResolutionState.Accepted(value, _, _) =>
          if !acceptedContextBySource.contains(source) then
            gaps += gap(
              record.target,
              record.bundle,
              ClaimFamily.SituationMention,
              DerivationGapReason.MissingUpstream(
                Vector(NarrativeCandidateAddress.ContextAssignment(source))
              )
            )
          else
            input.mentionGraph.chart(source.sentence) match
              case Some(chart) if chart.isEmbedded(source.concept) =>
                gaps += gap(
                  record.target,
                  record.bundle,
                  ClaimFamily.SituationMention,
                  DerivationGapReason.UnsupportedEmbeddedContext(source)
                )
              case Some(chart)
                  if chart.focus.contains(source.concept) &&
                    chart.concept(source.concept).exists(_.kind == ConceptKind.Predicate) =>
                materialize(
                  input,
                  ClaimFamily.SituationMention,
                  record.target,
                  record.bundle,
                  accepted,
                  renderSituation,
                  _ => EpistemicStatus.SurfaceExplicit
                ) match
                  case Left(reason) =>
                    gaps += gap(record.target, record.bundle, ClaimFamily.SituationMention, reason)
                  case Right(material) =>
                    val mention = situationMentionId(input.source.id, source, material.support)
                    val canonical =
                      ExactCorefCluster.canonicalFor[SituationK](input.source.id, Vector(mention))
                    val id = SituationId.unsafe(canonical.value)
                    val root = rootContextId(input.source.id)
                    val node = material.value.kind match
                      case SituationKind.Event =>
                        SituationNode.Event(
                          EventNode(
                            id,
                            material.value.predicate,
                            material.value.description,
                            root,
                            material.value.polarity,
                            material.value.modality,
                            material.value.aspect,
                            material.support,
                            NonEmptyVector.one(mention),
                            material.meta
                          )
                        )
                      case SituationKind.State =>
                        SituationNode.State(
                          StateNode(
                            id,
                            material.value.predicate,
                            material.value.description,
                            root,
                            material.value.polarity,
                            material.value.modality,
                            material.support,
                            NonEmptyVector.one(mention),
                            material.meta
                          )
                        )
                    emittedSituations += EmittedSituation(
                      source,
                      canonical,
                      mention,
                      node,
                      material.meta
                    )
                    emittedByAddress.update(record.target, material.meta.id)
              case _ =>
                gaps += gap(
                  record.target,
                  record.bundle,
                  ClaimFamily.SituationMention,
                  DerivationGapReason.InvalidAccepted(
                    DomainError.InvariantViolation(
                      s"compiler/situations/${source.key}",
                      "source must be the chart focus predicate"
                    )
                  )
                )
        case state =>
          gaps += gap(record.target, record.bundle, ClaimFamily.SituationMention, gapReason(state))
    }

    val emitted = emittedSituations.result().sortBy(s => (s.node.support.minSpan, s.node.id))
    val emittedNev = NonEmptyVector.fromVector(emitted)
    val situationEntries = emitted.map(s => s.mention -> s.source)
    val situationTable = MentionTable.of[SituationK](situationEntries, input.mentionGraph) match
      case Left(e)      => return Left(NarrativeCompilerError.MentionConstruction(e))
      case Right(table) => table
    val situationPartition = CorefPartition.empty[SituationK](input.source.id)
    val entityTable = MentionTable.empty[EntityK]
    val entityPartition = CorefPartition.empty[EntityK](input.source.id)

    val situationMap = emitted.map(s => s.node.id -> s.node).toMap
    val acceptedContexts =
      contextRecords.flatMap(record => acceptedContextBySource.get(record.source))
    val contexts = NonEmptyVector.fromVector(acceptedContexts) match
      case None           => Map.empty[ContextId, ContextFrame]
      case Some(accepted) =>
        val support = unionSupports(accepted.map(_.support))
        val meta = derivedMeta(
          input,
          "root-context",
          Vector(input.source.id.value),
          support,
          accepted.toVector.map(_.meta.id).toSet
        ) match
          case Left(error)  => return Left(NarrativeCompilerError.ClaimConstruction(error))
          case Right(value) => value
        val id = rootContextId(input.source.id)
        Map(id -> ContextFrame(id, None, ContextKind.NarratedWorld, support, meta))

    val emittedBySource = emitted.map(s => s.source -> s).toMap
    val causalEdges = Vector.newBuilder[CausalEdge]
    causalRecords.foreach { record =>
      val (from, to) = (record.from, record.to)
      record.state match
        case accepted @ ResolutionState.Accepted(_, _, _) =>
          val missing = Vector(from, to).filterNot(emittedBySource.contains)
          if missing.nonEmpty then
            val addresses = missing.map(NarrativeCandidateAddress.Situation.apply)
            gaps += gap(
              record.target,
              record.bundle,
              ClaimFamily.CausalEdge,
              DerivationGapReason.MissingUpstream(addresses),
              Vector(from, to)
                .map(NarrativeCandidateAddress.Situation.apply)
                .flatMap(emittedByAddress.get)
                .toSet
            )
          else
            materialize(
              input,
              ClaimFamily.CausalEdge,
              record.target,
              record.bundle,
              accepted,
              renderCausal,
              _ => EpistemicStatus.Hypothesized
            ) match
              case Left(reason) =>
                gaps += gap(record.target, record.bundle, ClaimFamily.CausalEdge, reason)
              case Right(material) =>
                val edge = CausalEdge(
                  emittedBySource(from).node.id,
                  material.value.relation,
                  emittedBySource(to).node.id,
                  material.meta
                )
                causalEdges += edge
                emittedByAddress.update(record.target, material.meta.id)
        case state =>
          gaps += gap(record.target, record.bundle, ClaimFamily.CausalEdge, gapReason(state))
    }

    val summaryMaterial = summaryRecord.state match
      case accepted @ ResolutionState.Accepted(_, _, _) =>
        emittedNev match
          case None =>
            gaps += gap(
              summaryRecord.target,
              summaryRecord.bundle,
              ClaimFamily.Summary,
              DerivationGapReason.MissingUpstream(
                situationRecords
                  .filterNot(r => emittedByAddress.contains(r.target))
                  .map(_.target)
              )
            )
            None
          case Some(_) =>
            materialize(
              input,
              ClaimFamily.Summary,
              summaryRecord.target,
              summaryRecord.bundle,
              accepted,
              renderSummary,
              _ => EpistemicStatus.Hypothesized
            ) match
              case Left(reason) =>
                gaps += gap(summaryRecord.target, summaryRecord.bundle, ClaimFamily.Summary, reason)
                None
              case Right(material) =>
                emittedByAddress.update(summaryRecord.target, material.meta.id)
                Some(material)
      case state =>
        gaps += gap(
          summaryRecord.target,
          summaryRecord.bundle,
          ClaimFamily.Summary,
          gapReason(state)
        )
        None

    val rootSegment = rootSegmentId(input.source.id, emitted.map(_.node.id))
    val membershipEdges = Vector.newBuilder[ContainmentEdge]
    membershipRecords.foreach { record =>
      record.state match
        case accepted @ ResolutionState.Accepted(_, _, _) =>
          val missing = Vector(
            Option.when(summaryMaterial.isEmpty)(summaryRecord.target),
            Option.when(!emittedBySource.contains(record.member))(
              NarrativeCandidateAddress.Situation(record.member)
            )
          ).flatten
          if missing.nonEmpty then
            gaps += gap(
              record.target,
              record.bundle,
              ClaimFamily.SegmentMembership,
              DerivationGapReason.MissingUpstream(missing),
              missing.flatMap(emittedByAddress.get).toSet
            )
          else
            materialize(
              input,
              ClaimFamily.SegmentMembership,
              record.target,
              record.bundle,
              accepted,
              renderSegmentMembership,
              _ => EpistemicStatus.Hypothesized
            ) match
              case Left(reason) =>
                gaps += gap(record.target, record.bundle, ClaimFamily.SegmentMembership, reason)
              case Right(material) =>
                membershipEdges += ContainmentEdge(
                  NarrativeMember.Situation(emittedBySource(record.member).node.id),
                  rootSegment,
                  HierarchyKind.PrimarySegmentation,
                  1.0,
                  material.meta
                )
                emittedByAddress.update(record.target, material.meta.id)
        case state =>
          gaps += gap(record.target, record.bundle, ClaimFamily.SegmentMembership, gapReason(state))
    }

    val segments = summaryMaterial match
      case None          => Map.empty[SegmentId, SegmentNode]
      case Some(summary) =>
        val alternatives = alternativesFor(summaryRecord.bundle, summary.value, renderSummary)
        val segment = SegmentNode(
          rootSegment,
          SegmentKind.Story,
          1,
          Resolved(summary.value.text, summary.meta, alternatives.map((a, c) => a.text -> c)),
          summary.support
        )
        Map(rootSegment -> segment)
    val hierarchy = NarrativeHierarchy(membershipEdges.result(), Vector.empty)

    val graph = NarrativeGraph(
      Map.empty,
      situationMap,
      segments,
      contexts,
      RelationLayers.empty.copy(causal = causalEdges.result())
    )
    val projectionResult = emitted.foldLeft[
      Either[NarrativeCompilerError, Vector[Projection[SituationK]]]
    ](Right(Vector.empty)) { (acc, situation) =>
      for
        built <- acc
        projection <- Projection
          .of(
            NonEmptySet.one(situation.source),
            situation.canonical,
            ProjectionMode.DirectMention,
            situation.meta,
            Some(input.mentionGraph)
          )
          .left
          .map(NarrativeCompilerError.ProjectionConstruction.apply)
      yield built :+ projection
    }
    val projectionVec = projectionResult match
      case Left(error)  => return Left(error)
      case Right(value) => value
    val projections = ProjectionIndex(projectionVec, Vector.empty)
    val projectionViolations = projections.validate(graph, Some(input.mentionGraph))
    NonEmptyVector.fromVector(projectionViolations) match
      case Some(violations) =>
        return Left(NarrativeCompilerError.ProjectionValidation(violations))
      case None => ()

    val situationRecordBySource = situationRecords.map(r => r.source -> r).toMap
    val trajectoryGaps = emitted
      .sliding(2)
      .collect { case Vector(from, to) =>
        val target = NarrativeCandidateAddress.TrajectoryStep(from.source, to.source)
        val evidence = Vector(from.source, to.source)
          .flatMap(situationRecordBySource.get)
          .flatMap(_.bundle.proposals)
          .flatMap(_.evidence)
          .distinctBy(_.evidenceId)
        val upstream = Vector(from.source, to.source)
          .map(NarrativeCandidateAddress.Situation.apply)
          .flatMap(emittedByAddress.get)
          .toSet
        DerivationGap(
          Stage,
          ClaimFamily.DiscourseTrajectory,
          target,
          DerivationGapReason.UnsupportedTrajectoryInputs(
            Vector(ClaimFamily.ParticipantRole, ClaimFamily.TemporalRelation)
          ),
          upstream,
          evidence
        )
      }
      .toVector
    gaps ++= trajectoryGaps
    val trajectory = DiscourseTrajectory.empty
    val draft = StoryModel.draft(
      input.source,
      input.atlas,
      graph,
      hierarchy,
      trajectory,
      receipt = Some(input.receipt)
    )
    val gapVec = gaps.result().sortBy(g => (g.target.render, g.reason.render))
    val structuralValidation = StoryValidator.validate(draft)
    val promotionBlockingFamilies = Set(
      ClaimFamily.SituationMention,
      ClaimFamily.ContextAssignment,
      ClaimFamily.SegmentMembership,
      ClaimFamily.DiscourseTrajectory,
      ClaimFamily.Summary
    )
    val compilerViolations = gapVec.collect {
      case gap if promotionBlockingFamilies(gap.family) =>
        Violation(
          "compiler.required-derivation",
          storymodel4s.story.Severity.Error,
          gap.target.render,
          gap.reason.render
        )
    }
    val validation =
      if compilerViolations.isEmpty then structuralValidation
      else
        ValidationOutcome(
          ValidationReport(
            (structuralValidation.report.violations ++ compilerViolations)
              .sortBy(v => (v.law, v.path, v.reason))
          ),
          None
        )

    val allRecords: Vector[(NarrativeCandidateAddress, ClaimFamily, ResolutionState[?])] =
      situationRecords.map(r => (r.target, ClaimFamily.SituationMention, r.state)) ++
        contextRecords.map(r => (r.target, ClaimFamily.ContextAssignment, r.state)) ++
        Vector((summaryRecord.target, ClaimFamily.Summary, summaryRecord.state)) ++
        membershipRecords.map(r => (r.target, ClaimFamily.SegmentMembership, r.state)) ++
        causalRecords.map(r => (r.target, ClaimFamily.CausalEdge, r.state))
    val gapByTarget = gapVec.groupBy(_.target)
    val resolvedAttempts = allRecords.sortBy(_._1).map { (target, family, state) =>
      emittedByAddress.get(target) match
        case Some(claim) => DerivationAttempt(target, family, DerivationDisposition.Emitted(claim))
        case None        =>
          val reason = gapByTarget
            .getOrElse(target, Vector.empty)
            .headOption
            .map(_.reason)
            .getOrElse(gapReason(state))
          DerivationAttempt(target, family, DerivationDisposition.NotEmitted(reason))
    }
    val trajectoryAttempts = trajectoryGaps.map(g =>
      DerivationAttempt(g.target, g.family, DerivationDisposition.NotEmitted(g.reason))
    )
    val attempts = (resolvedAttempts ++ trajectoryAttempts).sortBy(_.target)
    val candidateSet = ContentAddress.digest(
      Vector(
        "narrative-candidates/v3",
        input.source.id.value,
        input.source.canonicalChecksum.hex,
        input.receipt.contentChecksum.hex,
        s"software:${input.provenance.softwareVersion}",
        s"config:${input.provenance.configHash.hex}"
      ) ++
        input.provenance.calls.map(renderProviderCall).sorted.map(s => s"provenance-call:$s") ++
        input.evidence.values.toVector.map(renderEvidence).sorted.map(s => s"evidence:$s") ++
        renderPolicy(input.policy) ++
        situationRecords.flatMap(r =>
          renderFields(
            "candidate/v3",
            Vector(ClaimFamily.SituationMention.toString, r.target.render)
          ) +:
            renderBundle(r.bundle, renderSituation)
        ) ++
        contextRecords.flatMap(r =>
          renderFields(
            "candidate/v3",
            Vector(ClaimFamily.ContextAssignment.toString, r.target.render)
          ) +:
            renderBundle(r.bundle, renderContextAssignment)
        ) ++
        (renderFields(
          "candidate/v3",
          Vector(ClaimFamily.Summary.toString, summaryRecord.target.render)
        ) +:
          renderBundle(summaryRecord.bundle, renderSummary)) ++
        membershipRecords.flatMap(r =>
          renderFields(
            "candidate/v3",
            Vector(ClaimFamily.SegmentMembership.toString, r.target.render)
          ) +:
            renderBundle(r.bundle, renderSegmentMembership)
        ) ++
        causalRecords.flatMap(r =>
          renderFields(
            "candidate/v3",
            Vector(ClaimFamily.CausalEdge.toString, r.target.render)
          ) +:
            renderBundle(r.bundle, renderCausal)
        ) ++
        trajectoryGaps.map(g =>
          renderFields("candidate/v3", Vector(g.family.toString, g.target.render))
        )
    )
    val acceptedClaimMeta =
      acceptedContextBySource.values.map(_.meta).toVector ++ emitted.map(_.meta) ++
        graph.relations.causal.map(_.meta) ++ summaryMaterial.toVector.map(_.meta) ++
        hierarchy.containment.map(_.meta)
    val emittedClaims = (draft.claims ++ acceptedClaimMeta)
      .map(meta => meta.id -> meta)
      .toMap
    val derivation = DerivationReceipt.of(candidateSet, attempts, emittedClaims, gapVec) match
      case Left(error)  => return Left(NarrativeCompilerError.DerivationConstruction(error))
      case Right(value) => value
    val compilationFingerprint = fingerprint(
      input,
      derivation,
      graph,
      hierarchy,
      validation
    )

    NarrativeCompilation
      .of(
        input.mentionGraph,
        entityTable,
        situationTable,
        entityPartition,
        situationPartition,
        projections,
        resolutions,
        derivation,
        input.evidence,
        input.provenance,
        draft,
        validation,
        input.receipt,
        compilationFingerprint
      )
      .left
      .map(NarrativeCompilerError.CompilationConstruction.apply)

  private def materialize[A](
      input: NarrativeCompilerInput,
      family: ClaimFamily,
      target: NarrativeCandidateAddress,
      bundle: EvidenceBundle[A],
      accepted: ResolutionState.Accepted[A],
      render: A => String,
      status: A => EpistemicStatus
  ): Either[DerivationGapReason, Material[A]] =
    val value = accepted.value
    val proposals = bundle.proposals.filter(_.value.contains(value))
    val raw = proposals.flatMap(_.rawScore).maxOption.map(_.value)
    val calibration = bundle.calibrationFor(value)
    val evidence = accepted.evidence.toVector.flatMap(resolveEvidence(input, _))
    val support = NonEmptyVector
      .fromVector(evidence.flatMap(_.spans) ++ bundle.sourceSupport.spans.toVector)
      .map(unionSupports)
    (raw, calibration, NonEmptyVector.fromVector(evidence), support) match
      case (None, _, _, _) => Left(DerivationGapReason.MissingRawScore)
      case (_, _, None, _) => Left(DerivationGapReason.MissingSpanEvidence)
      case (_, _, _, None) => Left(DerivationGapReason.MissingSpanEvidence)
      case (Some(score), Some(cal), Some(evs), Some(spans)) =>
        Credence.calibrated(score, cal.probability, cal.model) match
          case Left(e)         => Left(DerivationGapReason.InvalidAccepted(e))
          case Right(credence) =>
            val claimId = ClaimId.unsafe(
              ContentAddress.of(
                "narrative-claim",
                input.source.id.value,
                family.toString,
                target.render,
                render(value),
                ContentAddress.digest(evs.toVector.map(_.id.value).sorted).hex
              )
            )
            val calls = proposals.map(_.receipt.call)
            val provenance = input.provenance.copy(
              calls = (input.provenance.calls ++ calls).distinct.sortBy(renderProviderCall)
            )
            ClaimMeta.of(claimId, status(value), credence, evs, provenance) match
              case Left(e)     => Left(DerivationGapReason.InvalidAccepted(e))
              case Right(meta) => Right(Material(value, meta, spans))
      case (_, None, _, _) =>
        Left(
          DerivationGapReason.InvalidAccepted(
            DomainError.InvariantViolation(
              s"compiler/materialize/${target.render}",
              "accepted candidate has no calibration"
            )
          )
        )

  private def resolveEvidence(
      input: NarrativeCompilerInput,
      ref: EvidenceRef
  ): Option[Evidence] = ref match
    case EvidenceRef.ById(id)       => input.evidence.get(id)
    case EvidenceRef.Inline(record) => Some(record)

  private def derivedMeta(
      input: NarrativeCompilerInput,
      kind: String,
      parts: Vector[String],
      support: SpanSet,
      upstream: Set[ClaimId]
  ): Either[DomainError, ClaimMeta] =
    val id = ClaimId.unsafe(ContentAddress.of(s"claim-$kind", (input.source.id.value +: parts)*))
    val evidence = Evidence(
      EvidenceId.unsafe(ContentAddress.of(s"evidence-$kind", id.value)),
      Some(support),
      upstream,
      Fingerprint,
      Stage
    )
    Credence
      .raw(1.0)
      .flatMap(credence =>
        ClaimMeta.of(
          id,
          EpistemicStatus.StructurallyDerived,
          credence,
          NonEmptyVector.one(evidence),
          input.provenance
        )
      )

  private def gap[A](
      target: NarrativeCandidateAddress,
      bundle: EvidenceBundle[A],
      family: ClaimFamily,
      reason: DerivationGapReason,
      upstream: Set[ClaimId] = Set.empty
  ): DerivationGap =
    val evidence = bundle.proposals.flatMap(_.evidence).distinctBy(_.evidenceId)
    DerivationGap(Stage, family, target, reason, upstream, evidence)

  private def gapReason(state: ResolutionState[?]): DerivationGapReason = state match
    case ResolutionState.Alternatives(_)   => DerivationGapReason.Alternatives
    case ResolutionState.Unresolved(r)     => DerivationGapReason.Unresolved(r)
    case ResolutionState.Rejected(r)       => DerivationGapReason.Rejected(r)
    case ResolutionState.Accepted(_, _, _) =>
      DerivationGapReason.InvalidAccepted(
        DomainError.InvariantViolation(
          "compiler/materialize",
          "accepted value was not emitted"
        )
      )

  private def canonicalBundle[A](
      bundle: EvidenceBundle[A],
      render: A => String
  ): EvidenceBundle[A] =
    bundle.copy(
      proposals = bundle.proposals.sortBy(renderProposal(_, render)),
      findings = bundle.findings.sortBy(renderFinding),
      structural = bundle.structural.copy(violations = bundle.structural.violations.sorted),
      calibrations =
        bundle.calibrations.sortBy(c => (render(c.value), c.model, c.probability.value))
    )

  private def renderBundle[A](bundle: EvidenceBundle[A], render: A => String): Vector[String] =
    bundle.proposals.map(p => renderFields("proposal/v2", Vector(renderProposal(p, render)))) ++
      bundle.findings.map(f => renderFields("finding/v2", Vector(renderFinding(f)))) ++
      Vector(
        renderFields(
          "structural/v1",
          Vector(
            bundle.structural.valid.toString,
            renderFields("violations/v1", bundle.structural.violations)
          )
        ),
        renderFields(
          "source-support/v2",
          Vector(
            bundle.sourceSupport.score.toString,
            renderOption(bundle.sourceSupport.spans)(renderSpans)
          )
        ),
        renderFields("agreement/v1", Vector(bundle.agreementScore.toString))
      ) ++
      bundle.calibrations.map(c =>
        renderFields(
          "calibration/v1",
          Vector(render(c.value), c.probability.value.toString, c.model)
        )
      )

  private def renderProposal[A](proposal: AgentProposal[A], render: A => String): String =
    val evidence = renderFields("evidence-list/v1", proposal.evidence.map(renderEvidenceRef).sorted)
    val conflicts = proposal.conflicts
      .map(c => renderFields("conflict/v1", Vector(renderConflictTarget(c.target), c.reason)))
      .sorted
    renderFields(
      "agent-proposal/v2",
      Vector(
        proposal.taskId.value,
        proposal.disposition.toString,
        renderOption(proposal.value)(render),
        renderOption(proposal.rawScore)(_.value.toString),
        evidence,
        renderFields("conflict-list/v1", conflicts),
        renderAgentReceipt(proposal.receipt)
      )
    )

  private def renderFinding(finding: CriticFinding): String =
    renderFields(
      "critic-finding/v2",
      Vector(
        finding.taskId.value,
        finding.family.toString,
        finding.code.toString,
        renderFields(
          "target-list/v1",
          finding.targets.map(t => renderFields("target/v1", Vector(t.kind.toString, t.id))).sorted
        ),
        renderFields("evidence-list/v1", finding.evidence.map(renderEvidenceRef).sorted),
        renderOption(finding.rawScore)(_.value.toString),
        renderOption(finding.note)(value => value)
      )
    )

  private def renderAgentReceipt(receipt: AgentCallReceipt): String =
    val prompt = receipt.promptPackage
    renderFields(
      "agent-call-receipt/v1",
      Vector(
        renderProviderCall(receipt.call),
        prompt.name,
        prompt.version,
        prompt.checksum.hex,
        receipt.taskId.value
      )
    )

  private[document] def renderProviderCall(call: ProviderCall): String =
    renderFields(
      "provider-call/v2",
      Vector(
        call.provider,
        call.model,
        call.version,
        renderOption(call.promptTemplateVersion)(value => value),
        call.inputChecksum.hex,
        call.outputChecksum.hex,
        renderFields(
          "params/v1",
          call.params.toVector.sortBy(_._1).map((k, v) => renderFields("param/v1", Vector(k, v)))
        ),
        renderOption(call.seed)(_.toString),
        call.cached.toString
      )
    )

  private def renderConflictTarget(target: ConflictTarget): String = target match
    case ConflictTarget.Claim(id) => renderFields("claim/v1", Vector(id.value))
    case ConflictTarget.Task(id)  => renderFields("task/v1", Vector(id.value))
    case ConflictTarget.Node(ref) => renderFields("node/v1", Vector(ref))

  private def renderEvidenceRef(ref: EvidenceRef): String = ref match
    case EvidenceRef.ById(id)       => renderFields("evidence-id/v1", Vector(id.value))
    case EvidenceRef.Inline(record) =>
      renderFields("inline-evidence/v1", Vector(renderEvidence(record)))

  private def renderEvidence(evidence: Evidence): String =
    renderFields(
      "evidence/v2",
      Vector(
        evidence.id.value,
        renderOption(evidence.spans)(renderSpans),
        renderFields("upstream/v1", evidence.upstream.toVector.sorted.map(_.value)),
        evidence.extractor.value,
        evidence.stage.value
      )
    )

  private def renderClaimMeta(meta: ClaimMeta): String =
    renderFields(
      "claim-meta/v2",
      Vector(
        meta.id.value,
        meta.status.toString,
        meta.credence.rawScore.toString,
        renderOption(meta.credence.calibrated)(_.value.toString),
        renderOption(meta.credence.calibrationModel)(value => value),
        renderFields("claim-evidence/v1", meta.evidence.toVector.map(renderEvidence)),
        renderProvenance(meta.provenance)
      )
    )

  private def renderProvenance(provenance: Provenance): String =
    renderFields(
      "provenance/v1",
      Vector(
        provenance.softwareVersion,
        provenance.configHash.hex,
        renderFields("provider-calls/v1", provenance.calls.map(renderProviderCall))
      )
    )

  private def renderSpans(spans: SpanSet): String =
    renderFields(
      "spans/v2",
      spans.refs.toVector.map(ref =>
        renderFields(
          "span/v2",
          Vector(
            renderOption(ref.unit)(_.value),
            ref.span.start.toString,
            ref.span.endExclusive.toString
          )
        )
      )
    )

  private def renderPolicy(policy: AcceptancePolicy): Vector[String] =
    def familyPolicy(p: FamilyPolicy): String =
      renderFields(
        "family-policy/v1",
        Vector(
          p.acceptThreshold.value,
          p.reviewBand.value,
          p.requireAgreement,
          p.requireCalibration,
          p.conservative,
          p.requireSpanEvidence,
          p.criticBlockThreshold
        ).map(_.toString)
      )
    Vector(renderFields("policy-default/v1", Vector(familyPolicy(policy.default)))) ++
      policy.perFamily.toVector
        .sortBy(_._1.toString)
        .map((family, p) => renderFields("policy/v1", Vector(family.toString, familyPolicy(p))))

  private def alternativesFor[A](
      bundle: EvidenceBundle[A],
      selected: A,
      render: A => String
  ): Vector[(A, Credence)] =
    bundle.proposals
      .flatMap(p => p.value.filterNot(_ == selected).flatMap(v => p.rawScore.map(v -> _)))
      .groupBy(_._1)
      .toVector
      .sortBy((value, _) => render(value))
      .flatMap { (value, scores) =>
        Credence.raw(scores.map(_._2.value).max).toOption.map(value -> _)
      }

  private def situationMentionId(
      story: StoryId,
      source: ChartNodeRef,
      support: SpanSet
  ): MentionId[SituationK] =
    MentionId.unsafe[SituationK](
      ContentAddress.of(
        "m-situation",
        story.value,
        source.key,
        support.refs.toVector.map(r => s"${r.span.start}:${r.span.endExclusive}").mkString(",")
      )
    )

  private def rootContextId(story: StoryId): ContextId =
    ContextId.unsafe(ContentAddress.of("context-root", story.value))

  private def rootSegmentId(story: StoryId, situations: Vector[SituationId]): SegmentId =
    SegmentId.unsafe(
      ContentAddress.of("segment-root", (story.value +: situations.map(_.value).sorted)*)
    )

  private def unionSupports(spans: NonEmptyVector[SpanSet]): SpanSet =
    spans.tail.foldLeft(spans.head)(_ ++ _)

  private def renderSituation(value: SituationProposal): String =
    renderFields(
      "situation/v2",
      Vector(
        value.kind.toString,
        value.predicate.lemma,
        renderOption(value.predicate.frame)(value => value),
        value.predicate.gloss,
        value.description,
        value.polarity.toString,
        value.modality.toString,
        renderOption(value.aspect)(_.toString)
      )
    )

  private def renderSummary(value: StorySummaryProposal): String =
    renderFields("summary/v1", Vector(value.text))

  private def renderCausal(value: CausalProposal): String =
    renderFields("causal/v1", Vector(value.relation.toString))

  private def renderContextAssignment(value: ContextAssignmentProposal): String =
    renderFields("context-assignment/v1", Vector(value.toString))

  private def renderSegmentMembership(value: SegmentMembershipProposal): String =
    renderFields("segment-membership/v1", Vector(value.toString))

  private def renderOption[A](value: Option[A])(render: A => String): String =
    value match
      case None       => renderFields("option/v1", Vector("none"))
      case Some(item) => renderFields("option/v1", Vector("some", render(item)))

  private def renderFields(tag: String, fields: Iterable[String]): String =
    (tag +: fields.toVector).map(value => s"${value.length}:$value").mkString

  private[document] def fingerprint(
      input: NarrativeCompilerInput,
      derivation: DerivationReceipt,
      graph: NarrativeGraph,
      hierarchy: NarrativeHierarchy,
      validation: ValidationOutcome
  ): Checksum =
    val attempts = derivation.attempts.map { attempt =>
      val outcome = attempt.disposition match
        case DerivationDisposition.Emitted(id)     => renderFields("emitted/v1", Vector(id.value))
        case DerivationDisposition.NotEmitted(why) =>
          renderFields("gap/v1", Vector(why.render))
      renderFields(
        "attempt/v1",
        Vector(attempt.family.toString, attempt.target.render, outcome)
      )
    }
    val nodes =
      graph.situations.keys.toVector.sorted.map(id =>
        renderFields("situation/v1", Vector(id.value))
      ) ++
        graph.segments.keys.toVector.sorted.map(id =>
          renderFields("segment/v1", Vector(id.value))
        ) ++
        graph.contexts.keys.toVector.sorted.map(id => renderFields("context/v1", Vector(id.value)))
    val edges =
      graph.relations.causal
        .map(e =>
          renderFields(
            "causal-edge/v1",
            Vector(e.cause.value, e.relation.toString, e.effect.value, e.meta.id.value)
          )
        ) ++
        hierarchy.containment.map(e =>
          renderFields(
            "containment-edge/v1",
            Vector(e.member.render, e.parent.value, e.kind.toString, e.meta.id.value)
          )
        )
    val claims = derivation.emittedClaims.values.toVector
      .sortBy(_.id.value)
      .map(renderClaimMeta)
    ContentAddress.digest(
      Vector(
        "narrative-compilation/v2",
        input.source.canonicalChecksum.hex,
        input.receipt.contentChecksum.hex,
        derivation.candidateSet.hex
      ) ++ attempts ++ nodes ++ edges ++ claims ++
        validation.report.violations.map(v =>
          renderFields("violation/v1", Vector(v.law, v.path, v.reason))
        )
    )
