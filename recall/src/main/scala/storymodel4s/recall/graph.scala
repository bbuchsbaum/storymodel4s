package storymodel4s.recall

import cats.data.ValidatedNec
import cats.syntax.all.*
import storymodel4s.core.{DomainError, StorySource, SurfaceAtlas}

/** A structured recall: transcript, its surface atlas, ordered idea units, and typed relations.
  *
  * Invariants (see [[RecallGraph.validated]]): ordinals are `0..n-1`; unit spans lie inside the
  * transcript; relation endpoints exist; no self-edges; participant entity references resolve.
  */
final case class RecallGraph(
    transcript: StorySource,
    atlas: SurfaceAtlas,
    units: Vector[RecallUnit],
    relations: RecallRelations
):
  lazy val byId: Map[RecallUnitId, RecallUnit] = units.iterator.map(u => u.id -> u).toMap
  lazy val byOrdinal: Map[Int, RecallUnit] = units.iterator.map(u => u.ordinal -> u).toMap
  lazy val entityById: Map[RecallEntityId, RecallEntity] =
    relations.entities.iterator.map(e => e.id -> e).toMap

  /** Units in recall order. */
  lazy val ordered: Vector[RecallUnit] = units.sortBy(_.ordinal)

  /** Consecutive pairs in recall order. */
  def chain: Vector[(RecallUnit, RecallUnit)] =
    ordered.sliding(2).collect { case Vector(a, b) => (a, b) }.toVector

  def unit(id: RecallUnitId): Option[RecallUnit] = byId.get(id)
  def size: Int = units.size

object RecallGraph:
  def validated(g: RecallGraph): ValidatedNec[DomainError, RecallGraph] =
    val ids = g.units.map(_.id)
    val dup = ids.diff(ids.distinct).distinct
    val dupCheck: ValidatedNec[DomainError, Unit] =
      dup.headOption
        .map(d => DomainError.DuplicateId("RecallUnitId", d.value).invalidNec)
        .getOrElse(().validNec)
    val ordinals = g.units.map(_.ordinal).sorted
    val ordCheck: ValidatedNec[DomainError, Unit] =
      if ordinals == (0 until g.units.size).toVector then ().validNec
      else DomainError.InvariantViolation("recall/units", "ordinals must be 0..n-1").invalidNec
    val len = g.transcript.canonicalText.length
    val spanCheck: ValidatedNec[DomainError, Unit] =
      g.units.traverse_ { u =>
        if u.span.minSpan.endExclusive <= len then ().validNec
        else
          DomainError
            .InvariantViolation(s"recall/units/${u.id.value}", "span exceeds transcript")
            .invalidNec
      }
    val known = ids.toSet
    def endpoint(path: String, id: RecallUnitId): ValidatedNec[DomainError, Unit] =
      if known.contains(id) then ().validNec
      else DomainError.InvariantViolation(path, s"unknown unit ${id.value}").invalidNec
    def noSelf(path: String, a: RecallUnitId, b: RecallUnitId): ValidatedNec[DomainError, Unit] =
      if a == b then DomainError.InvariantViolation(path, "self edge").invalidNec else ().validNec
    val relCheck: ValidatedNec[DomainError, Unit] =
      g.relations.temporal.traverse_ { e =>
        endpoint("recall/temporal", e.from) *> endpoint("recall/temporal", e.to) *>
          noSelf("recall/temporal", e.from, e.to)
      } *>
        g.relations.causal.traverse_ { e =>
          endpoint("recall/causal", e.cause) *> endpoint("recall/causal", e.effect) *>
            noSelf("recall/causal", e.cause, e.effect)
        } *>
        g.relations.elaboration.traverse_ { e =>
          endpoint("recall/elaboration", e.parent) *> endpoint("recall/elaboration", e.child) *>
            noSelf("recall/elaboration", e.parent, e.child)
        }
    val entityIds = g.relations.entities.map(_.id).toSet
    val partCheck: ValidatedNec[DomainError, Unit] =
      g.units.traverse_ { u =>
        u.proposition.participants.traverse_ { p =>
          p.entity match
            case Some(e) if !entityIds.contains(e) =>
              DomainError
                .InvariantViolation(
                  s"recall/units/${u.id.value}",
                  s"unknown recall entity ${e.value}"
                )
                .invalidNec
            case _ => ().validNec
        }
      }
    (dupCheck, ordCheck, spanCheck, relCheck, partCheck).mapN((_, _, _, _, _) => g)
