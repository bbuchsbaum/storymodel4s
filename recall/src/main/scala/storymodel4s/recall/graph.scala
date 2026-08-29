package storymodel4s.recall

import cats.data.ValidatedNec
import cats.syntax.all.*
import storymodel4s.core.{DomainError, StorySource, SurfaceAtlas}

/** Phantom validation state of a recall graph, so downstream analyses can require checked input. */
sealed trait RecallGraphStatus
object RecallGraphStatus:
  /** A graph assembled or edited since its invariants were last checked. */
  sealed trait Unchecked extends RecallGraphStatus

  /** A graph whose structural and text-at-span invariants have all been checked. */
  sealed trait Checked extends RecallGraphStatus

/** A structured recall: transcript, its surface atlas, ordered idea units, and typed relations.
  *
  * `S` records whether the invariants have been checked. Construction is private: fresh parts enter
  * through [[RecallGraph.validated]], and every field replacement returns `Unchecked` so an edit
  * cannot silently retain a `Checked` witness.
  *
  * Invariants: the atlas belongs to the transcript; ordinals are `0..n-1`; unit spans lie inside
  * the transcript and reproduce unit text; relation endpoints exist; no self-edges; participant
  * entity references resolve.
  */
final class RecallGraph[S <: RecallGraphStatus] private (
    val transcript: StorySource,
    val atlas: SurfaceAtlas,
    val units: Vector[RecallUnit],
    val relations: RecallRelations
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

  /** Replace any fields while deliberately forgetting the validation witness. */
  def copy(
      transcript: StorySource = transcript,
      atlas: SurfaceAtlas = atlas,
      units: Vector[RecallUnit] = units,
      relations: RecallRelations = relations
  ): RecallGraph[RecallGraphStatus.Unchecked] =
    new RecallGraph[RecallGraphStatus.Unchecked](transcript, atlas, units, relations)

  override def equals(other: Any): Boolean = other match
    case that: RecallGraph[?] =>
      transcript == that.transcript && atlas == that.atlas && units == that.units &&
      relations == that.relations
    case _ => false

  override def hashCode: Int = (transcript, atlas, units, relations).##

  override def toString: String = s"RecallGraph(units=${units.size})"

object RecallGraph:
  import RecallGraphStatus.{Checked, Unchecked}

  /** Assemble and validate a fresh graph, returning a checked witness only when every law passes.
    */
  def validated(
      transcript: StorySource,
      atlas: SurfaceAtlas,
      units: Vector[RecallUnit],
      relations: RecallRelations
  ): ValidatedNec[DomainError, RecallGraph[Checked]] =
    validated(unchecked(transcript, atlas, units, relations))

  /** Revalidate an assembled or edited graph, accumulating all invariant violations. */
  def validated(
      g: RecallGraph[Unchecked]
  ): ValidatedNec[DomainError, RecallGraph[Checked]] =
    val atlasCheck: ValidatedNec[DomainError, Unit] =
      if g.atlas.source == g.transcript then ().validNec
      else
        DomainError
          .InvariantViolation("recall/atlas", "atlas source must equal transcript")
          .invalidNec
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
    val canonical = g.transcript.canonicalText
    val len = canonical.length
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
    // A unit's `text` must be the words its `span` points at. Without this, a unit can claim any
    // text while its span points somewhere else, and "which words support this cell" gets two
    // different answers with no law that they agree: the only code that turns a unit into a vector
    // reads `text` (embed-bench channels), while a span-based trace reads the transcript. The
    // recall checksum commits to both, so it faithfully IDENTIFIES an incoherent graph and never
    // refuses one - identity is not validity, which is the gap this closes.
    //
    // Compared against the hull (`minSpan`), so a discontinuous SpanSet is checked as the covered
    // extent rather than concatenated pieces: a unit spanning two sentences carries the text
    // between them too, and slicing per-ref would reject that legitimately-gappy case.
    val textCheck: ValidatedNec[DomainError, Unit] =
      g.units.traverse_ { u =>
        val hull = u.span.minSpan
        // Guarded by spanCheck above, but validated accumulates rather than short-circuits, so a
        // graph failing the span check would reach this and throw on substring without the guard.
        if hull.endExclusive > len then ().validNec
        else if canonical.substring(hull.start, hull.endExclusive) == u.text then ().validNec
        else
          DomainError
            .InvariantViolation(
              s"recall/units/${u.id.value}",
              "text must be the transcript at the unit's span"
            )
            .invalidNec
      }
    (atlasCheck, dupCheck, ordCheck, spanCheck, relCheck, partCheck, textCheck).mapN {
      (_, _, _, _, _, _, _) =>
        new RecallGraph[Checked](g.transcript, g.atlas, g.units, g.relations)
    }

  /** Raw construction for validator fixtures inside `recall`; public callers validate parts. */
  private[recall] def unchecked(
      transcript: StorySource,
      atlas: SurfaceAtlas,
      units: Vector[RecallUnit],
      relations: RecallRelations
  ): RecallGraph[Unchecked] =
    new RecallGraph[Unchecked](transcript, atlas, units, relations)
