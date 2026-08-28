package storymodel4s.acquire

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** A candidate value with the strength of belief attached to it. */
final case class Weighted[+A](value: A, weight: Credence)

/** Why a claim could not be resolved to an accepted value or rejected outright. */
enum ResolutionFailure:
  case NoProposal
  case Uncalibrated
  case InsufficientAgreement(have: Int, need: Int)
  case InsufficientSupport(score: Double)
  case BlockingFinding(codes: Vector[FindingCode])

/** Why a claim was rejected as canonical. Rejected candidates stay in the ledger. */
enum RejectionReason:
  case StructurallyInvalid(violations: Vector[String])
  case BlockingFinding(codes: Vector[FindingCode])
  case NoSourceSupport
  case BelowRejectBand(probability: Probability, band: Probability)

/** Outcome of deterministic resolution for one claim (design record §94).
  *
  * `Accepted` carries a calibrated probability by type; there is no way to accept a claim on a raw
  * score.
  */
enum ResolutionState[+A]:
  case Accepted(value: A, probability: Probability)
  case Alternatives(values: NonEmptyVector[Weighted[A]])
  case Unresolved(reason: ResolutionFailure)
  case Rejected(reason: RejectionReason)

  def isAccepted: Boolean = this match
    case Accepted(_, _) => true
    case _              => false
  def accepted: Option[A] = this match
    case Accepted(v, _) => Some(v)
    case _              => None
  def map[B](f: A => B): ResolutionState[B] = this match
    case Accepted(v, p)   => Accepted(f(v), p)
    case Alternatives(vs) => Alternatives(vs.map(w => Weighted(f(w.value), w.weight)))
    case Unresolved(r)    => Unresolved(r)
    case Rejected(r)      => Rejected(r)

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
    StrictPrecedence,
    CausalEdge,
    TargetEpisodeMembership
  )

/** Per-family acceptance thresholds. Regions: `p >= acceptThreshold` accept; `reviewBand <= p <
  * acceptThreshold` alternatives; `p < reviewBand` reject as canonical.
  */
final case class FamilyPolicy private (
    acceptThreshold: Probability,
    reviewBand: Probability,
    requireAgreement: Int,
    requireCalibration: Boolean,
    conservative: Boolean
)

object FamilyPolicy:
  def of(
      acceptThreshold: Probability,
      reviewBand: Probability,
      requireAgreement: Int,
      requireCalibration: Boolean,
      conservative: Boolean
  ): Either[DomainError, FamilyPolicy] =
    if reviewBand.value > acceptThreshold.value then
      Left(DomainError.InvariantViolation("policy", "reviewBand exceeds acceptThreshold"))
    else if requireAgreement < 1 then
      Left(DomainError.InvariantViolation("policy", "requireAgreement must be at least 1"))
    else
      Right(
        new FamilyPolicy(
          acceptThreshold,
          reviewBand,
          requireAgreement,
          requireCalibration,
          conservative
        )
      )

  /** High-impact default: two independent agreeing proposers, calibration mandatory. */
  val Conservative: FamilyPolicy =
    new FamilyPolicy(Probability.unsafe(0.9), Probability.unsafe(0.5), 2, true, true)

  /** Ordinary default: one proposer, calibration mandatory. */
  val Ordinary: FamilyPolicy =
    new FamilyPolicy(Probability.unsafe(0.7), Probability.unsafe(0.4), 1, true, false)

  /** Development only: uncalibrated bundles yield alternatives instead of blocking. Never accepts
    * without calibration either.
    */
  val Development: FamilyPolicy =
    new FamilyPolicy(Probability.unsafe(0.7), Probability.unsafe(0.4), 1, false, false)

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

/** Everything the resolver may look at for one claim. `calibrated` is produced offline by a
  * family-specific calibration model; the resolver never derives it from raw scores.
  */
final case class EvidenceBundle[A](
    proposals: Vector[AgentProposal[A]],
    findings: Vector[CriticFinding],
    structural: StructuralValidity,
    sourceSupport: SourceSupport,
    agreementScore: Double,
    calibrated: Option[Probability]
)

/** Deterministic resolution (design record §94). Not a vote: proposal counts gate acceptance via
  * `requireAgreement`, but the decision is made on structural validity, blocking findings, and the
  * calibrated probability.
  */
object Resolver:
  private final case class Candidate[A](value: A, support: Int, best: Option[RawScore], order: Int)

  def resolve[A](
      family: ClaimFamily,
      bundle: EvidenceBundle[A],
      policy: AcceptancePolicy
  ): ResolutionState[A] =
    val fp = policy.forFamily(family)
    val blocking = CriticFinding.blocking(bundle.findings).map(_.code)
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
          val leading = cs.toVector.maxBy(c =>
            (c.support, c.best.map(_.value).getOrElse(Double.NegativeInfinity), -c.order)
          )
          if fp.conservative && bundle.sourceSupport.score <= 0.0 then
            ResolutionState.Rejected(RejectionReason.NoSourceSupport)
          else
            bundle.calibrated match
              case None =>
                if fp.requireCalibration && cs.length == 1 then
                  ResolutionState.Unresolved(ResolutionFailure.Uncalibrated)
                else alternatives(cs)
              case Some(p) =>
                if leading.support < fp.requireAgreement then
                  ResolutionState.Unresolved(
                    ResolutionFailure.InsufficientAgreement(leading.support, fp.requireAgreement)
                  )
                else if p.value >= fp.acceptThreshold.value then
                  ResolutionState.Accepted(leading.value, p)
                else if p.value >= fp.reviewBand.value then alternatives(cs)
                else ResolutionState.Rejected(RejectionReason.BelowRejectBand(p, fp.reviewBand))

  /** Distinct proposed values with the number of `Proposed` dispositions supporting each, their
    * best raw score, and first-appearance order for deterministic tie-breaking.
    */
  private def collect[A](proposals: Vector[AgentProposal[A]]): Vector[Candidate[A]] =
    val substantive = proposals.filter(_.isSubstantive)
    val order = substantive.map(_.value.get).distinct.zipWithIndex.toMap
    substantive
      .groupBy(_.value.get)
      .toVector
      .map { (v, ps) =>
        val support = ps.count(_.disposition == ProposalDisposition.Proposed)
        val best = ps.flatMap(_.rawScore).maxOption
        Candidate(v, support, best, order(v))
      }
      .sortBy(_.order)

  private def alternatives[A](cs: NonEmptyVector[Candidate[A]]): ResolutionState[A] =
    ResolutionState.Alternatives(
      cs.map(c => Weighted(c.value, Credence.unsafeRaw(c.best.map(_.value).getOrElse(0.0))))
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
