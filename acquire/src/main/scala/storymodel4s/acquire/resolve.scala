package storymodel4s.acquire

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** A candidate value with the evidence for it: how many independent providers proposed it, how many
  * proposals mentioned it, and the best raw score reported, if any.
  *
  * `score` is optional by type: a provider that reports no score is not scored zero (review finding
  * #16). Ordering places scored candidates before unscored ones, then by score descending.
  */
final case class Weighted[+A](
    value: A,
    providers: Int,
    proposals: Int,
    score: Option[RawScore]
)

object Weighted:
  /** More providers, then more proposals, then higher score; missing scores sort last. */
  given [A]: Ordering[Weighted[A]] =
    Ordering
      .by[Weighted[A], (Int, Int, Int, Double)](w =>
        (w.providers, w.proposals, if w.score.isDefined then 1 else 0, w.score.fold(0.0)(_.value))
      )
      .reverse

/** Why a claim could not be resolved to an accepted value or rejected outright. */
enum ResolutionFailure:
  case NoProposal
  case Uncalibrated
  case InsufficientAgreement(have: Int, need: Int)
  case InsufficientSupport(score: Double)
  case NoSpanEvidence
  case BlockingFinding(codes: Vector[FindingCode])

/** Why a claim was rejected as canonical. Rejected candidates stay in the ledger. */
enum RejectionReason:
  case StructurallyInvalid(violations: Vector[String])
  case BlockingFinding(codes: Vector[FindingCode])
  case NoSourceSupport
  case BelowRejectBand(probability: Probability, band: Probability)

/** Outcome of deterministic resolution for one claim (design record §94).
  *
  * `Accepted` carries, by type, a calibrated probability *for the accepted value* and the evidence
  * references from which a lawful [[storymodel4s.core.ClaimMeta]] can be built. There is no way to
  * accept a claim on a raw score, on another candidate's probability, or without evidence.
  */
enum ResolutionState[+A]:
  case Accepted(value: A, probability: Probability, evidence: NonEmptyVector[EvidenceRef])
  case Alternatives(values: NonEmptyVector[Weighted[A]])
  case Unresolved(reason: ResolutionFailure)
  case Rejected(reason: RejectionReason)

  def isAccepted: Boolean = this match
    case Accepted(_, _, _) => true
    case _                 => false
  def accepted: Option[A] = this match
    case Accepted(v, _, _) => Some(v)
    case _                 => None
  def map[B](f: A => B): ResolutionState[B] = this match
    case Accepted(v, p, e) => Accepted(f(v), p, e)
    case Alternatives(vs)  => Alternatives(vs.map(w => w.copy(value = f(w.value))))
    case Unresolved(r)     => Unresolved(r)
    case Rejected(r)       => Rejected(r)

/** Claim families with distinct acceptance policies. The first group is high-impact: wrong
  * acceptance there corrupts the meaning of the story (§94).
  */
enum ClaimFamily:
  case ReportedToRootPromotion
  case EventCoreference
  case RoleReversal
  case Polarity
  case StrictPrecedence
  case CausalEdge
  case TargetEpisodeMembership
  case EntityMention
  case EntityCoreference
  case SituationMention
  case ParticipantRole
  case Modality
  case ContextAssignment
  case TemporalRelation
  case GoalRelation
  case StateChange
  case Reference
  case Boundary
  case SegmentMembership
  case DiscourseTrajectory
  case Summary
  case DetailAtom
  case Custom(namespace: String, name: String)

  def isHighImpact: Boolean = ClaimFamily.highImpact.contains(this)

object ClaimFamily:
  val highImpact: Set[ClaimFamily] = Set(
    ReportedToRootPromotion,
    EventCoreference,
    RoleReversal,
    Polarity,
    ContextAssignment,
    StrictPrecedence,
    CausalEdge,
    SegmentMembership,
    TargetEpisodeMembership
  )

/** Per-family acceptance thresholds. Regions: `p >= acceptThreshold` accept; `reviewBand <= p <
  * acceptThreshold` alternatives; `p < reviewBand` reject as canonical.
  *
  *   - `requireAgreement` counts *distinct providers* (by provider/model/version of the call
  *     receipt), never proposals: a retried or double-decoded call cannot agree with itself.
  *   - `requireSpanEvidence`: acceptance needs exact source spans, either in the bundle's source
  *     support or inline on the winning proposals' evidence (contract 3).
  *   - `criticBlockThreshold`: a `Blocking` finding blocks only when its raw score is at least this
  *     value or absent; low-confidence blocking findings are retained as warnings.
  */
final class FamilyPolicy private (
    val acceptThreshold: Probability,
    val reviewBand: Probability,
    val requireAgreement: Int,
    val requireCalibration: Boolean,
    val conservative: Boolean,
    val requireSpanEvidence: Boolean,
    val criticBlockThreshold: Double
):
  override def equals(other: Any): Boolean = other match
    case that: FamilyPolicy =>
      acceptThreshold == that.acceptThreshold &&
      reviewBand == that.reviewBand &&
      requireAgreement == that.requireAgreement &&
      requireCalibration == that.requireCalibration &&
      conservative == that.conservative &&
      requireSpanEvidence == that.requireSpanEvidence &&
      criticBlockThreshold == that.criticBlockThreshold
    case _ => false

  override def hashCode(): Int =
    (
      acceptThreshold,
      reviewBand,
      requireAgreement,
      requireCalibration,
      conservative,
      requireSpanEvidence,
      criticBlockThreshold
    ).##

  override def toString: String =
    s"FamilyPolicy(accept=${acceptThreshold.value}, review=${reviewBand.value}, " +
      s"agreement=$requireAgreement, calibration=$requireCalibration, conservative=$conservative)"

object FamilyPolicy:
  val DefaultCriticBlockThreshold: Double = 0.5

  def of(
      acceptThreshold: Probability,
      reviewBand: Probability,
      requireAgreement: Int,
      requireCalibration: Boolean,
      conservative: Boolean,
      requireSpanEvidence: Boolean = true,
      criticBlockThreshold: Double = DefaultCriticBlockThreshold
  ): Either[DomainError, FamilyPolicy] =
    if reviewBand.value > acceptThreshold.value then
      Left(DomainError.InvariantViolation("policy", "reviewBand exceeds acceptThreshold"))
    else if requireAgreement < 1 then
      Left(DomainError.InvariantViolation("policy", "requireAgreement must be at least 1"))
    else if criticBlockThreshold.isNaN then
      Left(DomainError.InvariantViolation("policy", "criticBlockThreshold must be a number"))
    else
      Right(
        new FamilyPolicy(
          acceptThreshold,
          reviewBand,
          requireAgreement,
          requireCalibration,
          conservative,
          requireSpanEvidence,
          criticBlockThreshold
        )
      )

  /** High-impact default: two independent agreeing providers, calibration and spans mandatory. */
  val Conservative: FamilyPolicy =
    new FamilyPolicy(
      Probability.unsafe(0.9),
      Probability.unsafe(0.5),
      2,
      true,
      true,
      true,
      DefaultCriticBlockThreshold
    )

  /** Ordinary default: one provider, calibration and spans mandatory. */
  val Ordinary: FamilyPolicy =
    new FamilyPolicy(
      Probability.unsafe(0.7),
      Probability.unsafe(0.4),
      1,
      true,
      false,
      true,
      DefaultCriticBlockThreshold
    )

  /** Development only: uncalibrated bundles yield alternatives instead of blocking, and spans are
    * not required. Never accepts without calibration either.
    */
  val Development: FamilyPolicy =
    new FamilyPolicy(
      Probability.unsafe(0.7),
      Probability.unsafe(0.4),
      1,
      false,
      false,
      false,
      DefaultCriticBlockThreshold
    )

final case class AcceptancePolicy(perFamily: Map[ClaimFamily, FamilyPolicy], default: FamilyPolicy):
  def forFamily(f: ClaimFamily): FamilyPolicy = perFamily.getOrElse(f, default)

object AcceptancePolicy:
  /** Conservative for every high-impact family, ordinary elsewhere. */
  val Conservative: AcceptancePolicy =
    AcceptancePolicy(
      ClaimFamily.highImpact.iterator.map(_ -> FamilyPolicy.Conservative).toMap,
      FamilyPolicy.Ordinary
    )

final case class StructuralValidity(valid: Boolean, violations: Vector[String])
object StructuralValidity:
  val Valid: StructuralValidity = StructuralValidity(true, Vector.empty)
  def invalid(reasons: String*): StructuralValidity = StructuralValidity(false, reasons.toVector)

/** Degree to which the source text supports the claim, in `[0, 1]`, with the spans found. */
final case class SourceSupport(score: Double, spans: Option[SpanSet])

/** A calibrated probability for one specific candidate value, produced offline by the named
  * family-specific calibration model. Calibration is attached to the value it calibrates, never to
  * the bundle as a whole (review finding #15).
  */
final case class CandidateCalibration[A](value: A, probability: Probability, model: String)

/** Everything the resolver may look at for one claim. The resolver never derives a probability from
  * raw scores.
  */
final case class EvidenceBundle[A](
    proposals: Vector[AgentProposal[A]],
    findings: Vector[CriticFinding],
    structural: StructuralValidity,
    sourceSupport: SourceSupport,
    agreementScore: Double,
    calibrations: Vector[CandidateCalibration[A]]
):
  def calibrationFor(value: A): Option[CandidateCalibration[A]] =
    calibrations.find(_.value == value)

/** Deterministic resolution (design record §94). Not a vote: provider agreement gates acceptance
  * via `requireAgreement`, but the decision is made on structural validity, blocking findings, span
  * evidence, and the calibrated probability of the leading value.
  */
object Resolver:
  private final case class Candidate[A](
      value: A,
      providers: Int,
      proposals: Int,
      best: Option[RawScore],
      evidence: Vector[EvidenceRef],
      order: Int
  ):
    def weighted: Weighted[A] = Weighted(value, providers, proposals, best)

  def resolve[A](
      family: ClaimFamily,
      bundle: EvidenceBundle[A],
      policy: AcceptancePolicy
  ): ResolutionState[A] =
    val fp = policy.forFamily(family)
    val blocking = CriticFinding.blocking(bundle.findings, fp.criticBlockThreshold).map(_.code)
    if !bundle.structural.valid then
      ResolutionState.Rejected(RejectionReason.StructurallyInvalid(bundle.structural.violations))
    else if blocking.nonEmpty then
      if fp.conservative then ResolutionState.Rejected(RejectionReason.BlockingFinding(blocking))
      else ResolutionState.Unresolved(ResolutionFailure.BlockingFinding(blocking))
    else
      val candidates = collect(bundle.proposals)
      NonEmptyVector.fromVector(candidates) match
        case None     => ResolutionState.Unresolved(ResolutionFailure.NoProposal)
        case Some(cs) =>
          val leading = cs.toVector.minBy(c => (c.weighted, c.order))
          if fp.conservative && bundle.sourceSupport.score <= 0.0 then
            ResolutionState.Rejected(RejectionReason.NoSourceSupport)
          else
            bundle.calibrationFor(leading.value) match
              case None =>
                if fp.requireCalibration && cs.length == 1 then
                  ResolutionState.Unresolved(ResolutionFailure.Uncalibrated)
                else alternatives(cs)
              case Some(cal) =>
                val p = cal.probability
                if leading.providers < fp.requireAgreement then
                  ResolutionState.Unresolved(
                    ResolutionFailure.InsufficientAgreement(leading.providers, fp.requireAgreement)
                  )
                else if p.value >= fp.acceptThreshold.value then
                  if fp.requireSpanEvidence && !hasSpans(bundle, leading) then
                    ResolutionState.Unresolved(ResolutionFailure.NoSpanEvidence)
                  else
                    NonEmptyVector.fromVector(leading.evidence) match
                      case Some(ev) => ResolutionState.Accepted(leading.value, p, ev)
                      case None     => ResolutionState.Unresolved(ResolutionFailure.NoSpanEvidence)
                else if p.value >= fp.reviewBand.value then alternatives(cs)
                else ResolutionState.Rejected(RejectionReason.BelowRejectBand(p, fp.reviewBand))

  private def hasSpans[A](bundle: EvidenceBundle[A], c: Candidate[A]): Boolean =
    bundle.sourceSupport.spans.nonEmpty || c.evidence.exists(_.spans.nonEmpty)

  /** Identity of the provider behind a proposal, for counting independent agreement. */
  private def providerKey(p: AgentProposal[?]): (String, String, String) =
    val call = p.receipt.call
    (call.provider, call.model, call.version)

  /** Distinct proposed values with the number of distinct providers that `Proposed` each, the
    * number of substantive proposals, their best raw score, the union of their evidence, and
    * first-appearance order for deterministic tie-breaking.
    */
  private def collect[A](proposals: Vector[AgentProposal[A]]): Vector[Candidate[A]] =
    val substantive = proposals.filter(_.isSubstantive)
    val order = substantive.map(_.value.get).distinct.zipWithIndex.toMap
    substantive
      .groupBy(_.value.get)
      .toVector
      .map { (v, ps) =>
        val proposedBy = ps.filter(_.disposition == ProposalDisposition.Proposed)
        val providers = proposedBy.map(providerKey).distinct.size
        val best = ps.flatMap(_.rawScore).maxOption
        val evidence = ps.flatMap(_.evidence).distinctBy(_.evidenceId)
        Candidate(v, providers, ps.size, best, evidence, order(v))
      }
      .sortBy(_.order)

  private def alternatives[A](cs: NonEmptyVector[Candidate[A]]): ResolutionState[A] =
    ResolutionState.Alternatives(
      NonEmptyVector.fromVectorUnsafe(
        cs.toVector
          .sortBy(c => (c.weighted, c.order))
          .map(_.weighted)
      )
    )

/** One ledger entry: the bundle a claim was resolved from, its state, and what it supersedes. */
final case class LedgerEntry[A](
    bundle: EvidenceBundle[A],
    state: ResolutionState[A],
    supersedes: Option[ClaimId]
)

/** Append-only record of every candidate and its resolution (design record §85.4, §94).
  *
  * Entries are never removed or overwritten; a re-resolution adds a new claim identifier that
  * supersedes the old one, so the machine record survives adjudication.
  */
final case class CandidateLedger[A] private (
    entries: Map[ClaimId, LedgerEntry[A]],
    order: Vector[ClaimId]
):
  def add(id: ClaimId, entry: LedgerEntry[A]): Either[DomainError, CandidateLedger[A]] =
    if entries.contains(id) then Left(DomainError.DuplicateId("ClaimId", id.value))
    else
      entry.supersedes match
        case Some(old) if !entries.contains(old) =>
          Left(
            DomainError.InvariantViolation(
              s"ledger/${id.value}",
              s"supersedes unknown ${old.value}"
            )
          )
        case _ => Right(CandidateLedger(entries.updated(id, entry), order :+ id))

  def supersede(
      old: ClaimId,
      id: ClaimId,
      bundle: EvidenceBundle[A],
      state: ResolutionState[A]
  ): Either[DomainError, CandidateLedger[A]] =
    add(id, LedgerEntry(bundle, state, Some(old)))

  def lookup(id: ClaimId): Option[LedgerEntry[A]] = entries.get(id)
  def contains(id: ClaimId): Boolean = entries.contains(id)
  def size: Int = order.size
  def all: Vector[(ClaimId, LedgerEntry[A])] = order.flatMap(id => entries.get(id).map(id -> _))

  /** Chain of superseded identifiers from `id` back to the original proposal. */
  def history(id: ClaimId): Vector[ClaimId] =
    Iterator
      .iterate(Option(id))(_.flatMap(entries.get).flatMap(_.supersedes))
      .takeWhile(_.isDefined)
      .flatten
      .toVector

  /** Identifiers not superseded by a later entry. */
  def current: Vector[ClaimId] =
    val superseded = entries.values.flatMap(_.supersedes).toSet
    order.filterNot(superseded.contains)

  def accepted: Vector[(ClaimId, A)] =
    current.flatMap(id => entries(id).state.accepted.map(id -> _))

object CandidateLedger:
  def empty[A]: CandidateLedger[A] = CandidateLedger(Map.empty, Vector.empty)
