package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.proposition.*
import storymodel4s.recall.RecallUnitId

/** Recall-side availability is independent of whether source evidence supports a claim. */
enum RecallClaimState:
  case Expressed, ReviewedNotExpressed, ExtractionUnresolved, ReferenceUnresolved

/** Attribution is recorded, not authenticated or promoted into semantic authority. */
enum ClaimAssessorKind:
  case HumanReview, AgentProposal, DeterministicRule, ImportedAssessment

/** Why a comparison remains unanswered; absence is never positive counterevidence. */
enum ClaimAssessmentUnknown:
  case SourceUnavailable, SourcePartial, RecallExtractionUnavailable, ReferenceUnresolved,
    ContextUnresolved, SemanticComparisonUnavailable

/** A derived accounting state; support and counterevidence remain separately inspectable. */
enum ClaimAssessmentStatus:
  case Supported, Contradicted, Conflicted, Unresolved, NotExpressed, ExtractionUnresolved,
    ReferenceUnresolved

/** An unchecked question in a declared population, independent of extraction success. */
final case class ClaimAssessmentQuestion(
    id: ClaimId,
    unit: RecallUnitId,
    event: SourceNodeRef,
    facet: Facet
)

/** An unchecked chart reference retains its full context and chart-local element owner. */
final case class ClaimChartReference(
    owner: Checksum,
    evidence: PropositionEvidence,
    element: AlignmentTarget
)

/** Unchecked source citation; its origin interval and assessed event need not be the same shot. The
  * assessor owns the semantic link between them; checked receipt construction proves only physical
  * ownership and complete accounting, never that the link is correct.
  */
final case class ClaimSourceCitation(
    id: EvidenceId,
    origin: SourceNodeRef,
    support: TypedSupport,
    chart: Option[ClaimChartReference] = None
)

/** Unchecked attributed assessment. Reviewed absence is a positive review assertion, not a value
  * inferred from an empty chart. A reference-unresolved participant may still be expressed.
  */
final case class ClaimAssessmentSubmission(
    question: ClaimId,
    recallState: RecallClaimState,
    reviewedSpans: SpanSet,
    claimSpans: Option[SpanSet],
    recallChart: Option[ClaimChartReference],
    supporting: Set[EvidenceId],
    counterevidence: Set[EvidenceId],
    unresolved: Set[ClaimAssessmentUnknown],
    assessor: ClaimAssessorKind,
    procedure: Fingerprint
)

/** A well-bound record of attributed claim assessments alongside an immutable mapping result.
  *
  * This certifies ownership, inventory accounting and provenance consistency, not factual truth,
  * assessor identity, successful execution or extraction completeness. No legacy alignment,
  * fidelity mode, score or signature is changed. Counts weight declared questions equally; they are
  * not participant accuracy or calibrated probabilities. See ADR 0022.
  */
final class ClaimAssessmentReceipt private (
    val mapping: MappingResult,
    val questions: Vector[ClaimAssessmentQuestion],
    val citations: Vector[ClaimSourceCitation],
    val submissions: Vector[ClaimAssessmentSubmission]
):
  val version: String = "attributed-claim-assessment/v1"
  val mappingDigest: Checksum = mapping.digest
  val populationDigest: Checksum = MappingRender.digest(
    Vector(version, mappingDigest.hex) ++ questions.map(q =>
      MappingRender.sequence(Vector(q.id.value, q.unit.value, q.event.key, q.facet.toString))
    )
  )
  private val byQuestion = submissions.map(s => s.question -> s).toMap

  /** A status is derived from the two evidence sets and recall state, never caller-selected. */
  def status(question: ClaimId): Option[ClaimAssessmentStatus] = byQuestion.get(question).map { s =>
    s.recallState match
      case RecallClaimState.ReviewedNotExpressed => ClaimAssessmentStatus.NotExpressed
      case RecallClaimState.ExtractionUnresolved => ClaimAssessmentStatus.ExtractionUnresolved
      case RecallClaimState.ReferenceUnresolved  => ClaimAssessmentStatus.ReferenceUnresolved
      case RecallClaimState.Expressed            =>
        (s.supporting.nonEmpty, s.counterevidence.nonEmpty) match
          case (true, true)                          => ClaimAssessmentStatus.Conflicted
          case (true, false) if s.unresolved.isEmpty => ClaimAssessmentStatus.Supported
          case (false, true) if s.unresolved.isEmpty => ClaimAssessmentStatus.Contradicted
          case _                                     => ClaimAssessmentStatus.Unresolved
  }

  /** Full fixed-population accounting, including every unresolved and unexpressed question. */
  def counts: Map[ClaimAssessmentStatus, Int] =
    ClaimAssessmentStatus.values.toVector
      .map(s => s -> questions.count(q => status(q.id).contains(s)))
      .toMap

  def totalQuestions: Int = questions.size

  /** Expressed but unresolved references stay in this denominator. Extraction failure cannot
    * establish whether a claim was expressed and is reported separately in `counts`.
    */
  def expressedQuestions: Int = submissions.count(s =>
    s.recallState == RecallClaimState.Expressed || s.recallState == RecallClaimState.ReferenceUnresolved
  )

  /** Conflicting evidence is not a resolved assessment. No correctness fraction is manufactured. */
  def resolvedQuestions: Int =
    counts(ClaimAssessmentStatus.Supported) + counts(ClaimAssessmentStatus.Contradicted)

  override def equals(other: Any): Boolean = other match
    case that: ClaimAssessmentReceipt =>
      mappingDigest == that.mappingDigest && questions == that.questions &&
      citations == that.citations && submissions == that.submissions
    case _ => false
  override def hashCode: Int = (mappingDigest, questions, citations, submissions).hashCode
  override def toString: String =
    s"ClaimAssessmentReceipt($version, $populationDigest, $totalQuestions)"

object ClaimAssessmentReceipt:
  private def invalid(detail: String): Left[MappingRefusal, Nothing] =
    Left(MappingRefusal.InvalidValue("claimAssessment", detail))

  private def contains(outer: SpanSet, inner: SpanSet): Boolean =
    inner.refs.forall(s =>
      outer.refs.exists(p => p.span.contains(s.span) && (s.unit.isEmpty || p.unit == s.unit))
    )

  private def checkChart(
      ref: ClaimChartReference,
      owner: Checksum,
      scope: SpanSet
  ): Either[MappingRefusal, Unit] =
    val chart = ref.evidence.chart
    val member = ref.element match
      case AlignmentTarget.Concepts(ids) => ids.forall(chart.concepts.contains)
      case AlignmentTarget.Relation(r)   => chart.relations.contains(r)
    val selected = chart.alignments.filter(_.target == ref.element)
    if ref.owner != owner then invalid("foreign chart owner")
    else if !member then invalid("foreign chart element")
    else if selected.isEmpty || selected.exists(a => !contains(scope, a.spans))
    then invalid("chart element lacks alignment within its cited text scope")
    else if ref.evidence.provenance != chart.provenance then
      invalid("chart provenance disagrees with its owner")
    else Right(())

  private def checkCitation(
      mapping: MappingResult,
      c: ClaimSourceCitation
  ): Either[MappingRefusal, Unit] =
    mapping.source.target(c.origin).toRight(MappingRefusal.UnknownTarget(c.origin)).flatMap {
      target =>
        val owned = target.sourceSupport match
          case located: SourceSupportStatus.Located =>
            (located.support, c.support) match
              case (TypedSupport.Text(parent), TypedSupport.Text(child)) => contains(parent, child)
              case (TypedSupport.Anchored(parent), TypedSupport.Anchored(child)) =>
                parent.bundleIdentity == child.bundleIdentity &&
                child.anchors.forall(parent.anchors.toVector.contains) &&
                (mapping.source.bundles.toVector.collect { case b: BundleEntry.Media =>
                  b.bundle
                } ++
                  mapping.source.composition.toVector.map(_.composed))
                  .exists(b => child.checkedOn(b).isRight)
              case _ => false
          case _ => false
        if !owned then invalid("citation is outside its source target or bundle")
        else
          c.chart.traverse_ { ref =>
            c.support match
              case TypedSupport.Text(spans) =>
                checkChart(ref, sourceChartOwner(mapping, c.origin, c.support), spans)
              case TypedSupport.Anchored(_) =>
                invalid("a chart requires separately represented text support")
          }
    }

  /** Expected declared owner for a chart attached to a citation, including the complete source
    * identity, origin target and selected support. Matching this value does not prove extraction.
    */
  def sourceChartOwner(
      mapping: MappingResult,
      origin: SourceNodeRef,
      support: TypedSupport
  ): Checksum =
    MappingRender.digest(
      Vector(
        "claim-source-chart-owner/v1",
        mapping.source.digest.hex,
        origin.key,
        support.identity.hex
      )
    )

  /** Validate an attributed submission against the exact mapping and a separately frozen question
    * population. Source citations may be reused; repeated citations never multiply question counts.
    * Chart checks are structural. Cross-shot reference, entailment and review completeness remain
    * attributed assertions, not consequences of this constructor succeeding.
    */
  def checked(
      mapping: MappingResult,
      questions: Vector[ClaimAssessmentQuestion],
      citations: Vector[ClaimSourceCitation],
      submissions: Vector[ClaimAssessmentSubmission]
  ): Either[MappingRefusal, ClaimAssessmentReceipt] =
    val questionIds = questions.map(_.id)
    val byQuestion = questions.map(q => q.id -> q).toMap
    val citationIds = citations.map(_.id)
    val submissionIds = submissions.map(_.question)
    val bySubmission = submissions.map(s => s.question -> s).toMap
    val units = mapping.inventory.units.map(u => u.id -> u).toMap
    val evidence = citationIds.toSet
    if questionIds.distinct.size != questionIds.size then invalid("duplicate question")
    else if citationIds.distinct.size != citationIds.size then invalid("duplicate citation")
    else if submissionIds.distinct.size != submissionIds.size || submissionIds.toSet != questionIds.toSet
    then invalid("submissions must account for the complete question population exactly once")
    else
      for
        _ <- questions.traverse_ { q =>
          if !units.contains(q.unit) then Left(MappingRefusal.UnknownUnit(q.unit))
          else if mapping.source.target(q.event).isEmpty then
            Left(MappingRefusal.UnknownTarget(q.event))
          else Right(())
        }
        _ <- citations.traverse_(checkCitation(mapping, _))
        _ <- submissions.traverse_ { s =>
          val q = byQuestion(s.question)
          val unit = units(q.unit)
          val expressed =
            s.recallState == RecallClaimState.Expressed || s.recallState == RecallClaimState.ReferenceUnresolved
          val links = s.supporting ++ s.counterevidence
          if !contains(unit.span, s.reviewedSpans) || s.claimSpans.exists(p =>
              !contains(s.reviewedSpans, p)
            )
          then invalid("recall spans are outside their unit or review scope")
          else if expressed != s.claimSpans.nonEmpty then
            invalid(
              "expressed claims require spans; unavailable or unexpressed claims cannot assert spans"
            )
          else if !expressed && (links.nonEmpty || s.recallChart.nonEmpty)
          then
            invalid(
              "unavailable or unexpressed claims cannot carry decided evidence or a resolved chart"
            )
          else if !links.subsetOf(evidence) then invalid("foreign evidence link")
          else if s.recallState == RecallClaimState.Expressed && links.isEmpty && s.unresolved.isEmpty
          then invalid("an unanswered expressed claim requires an unresolved reason")
          else if s.recallState == RecallClaimState.ExtractionUnresolved && !s.unresolved.contains(
              ClaimAssessmentUnknown.RecallExtractionUnavailable
            )
          then invalid("extraction failure requires its own reason")
          else if s.recallState == RecallClaimState.ReferenceUnresolved && !s.unresolved.contains(
              ClaimAssessmentUnknown.ReferenceUnresolved
            )
          then invalid("unresolved reference requires its own reason")
          else if s.recallState == RecallClaimState.ReviewedNotExpressed && s.unresolved.nonEmpty
          then invalid("reviewed nonexpression cannot also mean failed assessment")
          else
            s.recallChart.traverse_(ref =>
              checkChart(ref, mapping.inventory.transcript, s.claimSpans.get)
            )
        }
      yield new ClaimAssessmentReceipt(
        mapping,
        questions,
        citations.sortBy(_.id.value),
        questions.map(q => bySubmission(q.id))
      )
