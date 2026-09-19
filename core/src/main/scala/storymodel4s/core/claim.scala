package storymodel4s.core

import cats.Functor
import cats.data.NonEmptyVector

/** How a claim is licensed. Kept distinct from credence: a `Hypothesized` claim may be held
  * strongly, a `SurfaceExplicit` claim may be uncertain because of parsing noise.
  */
enum EpistemicStatus:
  /** Directly stated by the source text; must cite spans. */
  case SurfaceExplicit

  /** Follows from the linguistic form (presupposition, entailment). */
  case LinguisticallyEntailed

  /** Requires commonsense or world knowledge beyond the text. */
  case WorldKnowledgeInferred

  /** Derived mechanically from other accepted claims (closure, containment). */
  case StructurallyDerived

  /** A conjecture, including model guesses without explicit support. */
  case Hypothesized

  /** Accepted or corrected by a human adjudicator. */
  case HumanAdjudicated

/** What supports a claim: exact spans, upstream claims, and the extractor that produced it. */
final case class Evidence(
    id: EvidenceId,
    spans: Option[SpanSet],
    upstream: Set[ClaimId],
    extractor: Fingerprint,
    stage: StageId,
    anchors: Option[EvidenceSupport] = None
):
  def hasSpans: Boolean = spans.nonEmpty

/** Metadata attached to every nontrivial machine assertion.
  *
  * Why a non-case class: the span law (a `SurfaceExplicit` claim cites at least one nonempty span
  * set) must hold for every `ClaimMeta` that exists. A private case-class constructor still emits
  * `fromProduct`, and `private[core]` still emits `copy` inside the package — both mint
  * SurfaceExplicit without spans. Construct with [[ClaimMeta.of]] (checked) or [[ClaimMeta.unsafe]]
  * (throws on violation); there is no unchecked path.
  */
final class ClaimMeta private (
    val id: ClaimId,
    val status: EpistemicStatus,
    val credence: Credence,
    val evidence: NonEmptyVector[Evidence],
    val provenance: Provenance
):
  def spans: Option[SpanSet] =
    evidence.toVector.flatMap(_.spans).reduceOption(_ ++ _)

  /** Checked update of the fields that do not affect the span law. */
  def withCredence(c: Credence): ClaimMeta =
    new ClaimMeta(id, status, c, evidence, provenance)
  def withProvenance(p: Provenance): ClaimMeta =
    new ClaimMeta(id, status, credence, evidence, p)

  override def equals(other: Any): Boolean = other match
    case that: ClaimMeta =>
      id == that.id &&
      status == that.status &&
      credence == that.credence &&
      evidence == that.evidence &&
      provenance == that.provenance
    case _ => false

  override def hashCode(): Int = (id, status, credence, evidence, provenance).hashCode()

  override def toString: String = s"ClaimMeta(${id.value}, $status)"

  /** Checked update of status/evidence; fails when the result would violate the span law. */
  def withStatus(s: EpistemicStatus): Either[DomainError, ClaimMeta] =
    ClaimMeta.of(id, s, credence, evidence, provenance)
  def withEvidence(ev: NonEmptyVector[Evidence]): Either[DomainError, ClaimMeta] =
    ClaimMeta.of(id, status, credence, ev, provenance)

object ClaimMeta:
  private def spanLaw(
      id: ClaimId,
      status: EpistemicStatus,
      evidence: NonEmptyVector[Evidence]
  ): Either[DomainError, Unit] =
    status match
      case EpistemicStatus.SurfaceExplicit if !evidence.exists(_.hasSpans) =>
        Left(
          DomainError.InvariantViolation(
            s"claim/${id.value}",
            "SurfaceExplicit claim has no span evidence"
          )
        )
      case _ => Right(())

  /** The only checked constructor. Law: surface-explicit claims must cite at least one nonempty
    * span set.
    */
  def of(
      id: ClaimId,
      status: EpistemicStatus,
      credence: Credence,
      evidence: NonEmptyVector[Evidence],
      provenance: Provenance
  ): Either[DomainError, ClaimMeta] =
    spanLaw(id, status, evidence).map(_ =>
      new ClaimMeta(id, status, credence, evidence, provenance)
    )

  /** Throws `IllegalArgumentException` when the span law is violated. For fixtures and tests. */
  def unsafe(
      id: ClaimId,
      status: EpistemicStatus,
      credence: Credence,
      evidence: NonEmptyVector[Evidence],
      provenance: Provenance
  ): ClaimMeta =
    of(id, status, credence, evidence, provenance)
      .fold(e => throw new IllegalArgumentException(e.message), identity)

  /** Re-checks an existing value; always `Right` for values built through [[of]], kept so callers
    * that re-validate ledgers do not need to special-case the type.
    */
  def validated(meta: ClaimMeta): Either[DomainError, ClaimMeta] =
    spanLaw(meta.id, meta.status, meta.evidence).map(_ => meta)

/** A resolved value together with its claim and the alternatives that were not selected.
  *
  * Why: ambiguity must survive resolution; the machine proposal and its rivals stay inspectable
  * after a value is chosen. A `Resolved` can only carry a lawful `ClaimMeta`.
  */
final case class Resolved[A](value: A, meta: ClaimMeta, alternatives: Vector[(A, Credence)]):
  def map[B](f: A => B): Resolved[B] =
    Resolved(f(value), meta, alternatives.map((a, c) => (f(a), c)))
  def hasAlternatives: Boolean = alternatives.nonEmpty

object Resolved:
  given Functor[Resolved] with
    def map[A, B](fa: Resolved[A])(f: A => B): Resolved[B] = fa.map(f)

/** Append-only registry of claims keyed by identifier. */
final case class ClaimLedger private (claims: Map[ClaimId, ClaimMeta], order: Vector[ClaimId]):
  def add(meta: ClaimMeta): Either[DomainError, ClaimLedger] =
    if claims.contains(meta.id) then Left(DomainError.DuplicateId("ClaimId", meta.id.value))
    else ClaimMeta.validated(meta).map(m => ClaimLedger(claims.updated(m.id, m), order :+ m.id))

  def addAll(metas: Iterable[ClaimMeta]): Either[DomainError, ClaimLedger] =
    metas.foldLeft[Either[DomainError, ClaimLedger]](Right(this))((acc, m) => acc.flatMap(_.add(m)))

  def lookup(id: ClaimId): Option[ClaimMeta] = claims.get(id)
  def contains(id: ClaimId): Boolean = claims.contains(id)
  def byStatus(status: EpistemicStatus): Vector[ClaimMeta] =
    order.flatMap(claims.get).filter(_.status == status)
  def size: Int = order.size
  def all: Vector[ClaimMeta] = order.flatMap(claims.get)

object ClaimLedger:
  val empty: ClaimLedger = ClaimLedger(Map.empty, Vector.empty)
