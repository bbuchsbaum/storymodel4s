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
    stage: StageId
):
  def hasSpans: Boolean = spans.nonEmpty

/** Metadata attached to every nontrivial machine assertion. */
final case class ClaimMeta(
    id: ClaimId,
    status: EpistemicStatus,
    credence: Credence,
    evidence: NonEmptyVector[Evidence],
    provenance: Provenance
):
  def spans: Option[SpanSet] =
    evidence.toVector.flatMap(_.spans).reduceOption(_ ++ _)

object ClaimMeta:
  /** Law: surface-explicit claims must cite at least one nonempty span set. */
  def validated(meta: ClaimMeta): Either[DomainError, ClaimMeta] =
    meta.status match
      case EpistemicStatus.SurfaceExplicit if !meta.evidence.exists(_.hasSpans) =>
        Left(
          DomainError.InvariantViolation(
            s"claim/${meta.id.value}",
            "SurfaceExplicit claim has no span evidence"
          )
        )
      case _ => Right(meta)

/** A resolved value together with its claim and the alternatives that were not selected.
  *
  * Why: ambiguity must survive resolution; the machine proposal and its rivals stay inspectable
  * after a value is chosen.
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
