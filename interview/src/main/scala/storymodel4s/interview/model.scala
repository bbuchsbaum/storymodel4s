package storymodel4s.interview

import cats.data.ValidatedNec
import cats.syntax.all.*

import storymodel4s.core.*
import storymodel4s.recall.RecallGraph
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.story.ModelStatus

/** An episode as implied by the transcript (design record §60.1).
  *
  * Its `status` can only be `Hypothesized` or `StructurallyDerived`: an inferred episode is never
  * an observed source, so it can never carry `SurfaceExplicit` truth about the world.
  */
final class EpisodeModel private (
    val id: EpisodeId,
    val scope: EpisodeScope,
    val situations: Vector[SituationId],
    val entities: Set[EntityId],
    val locations: Set[PlaceName],
    val temporalAnchors: Vector[TimeExpression],
    val relations: Vector[NarrativeRelationRef],
    val status: EpistemicStatus,
    val support: Option[SpanSet]
):
  override def equals(other: Any): Boolean = other match
    case that: EpisodeModel =>
      id == that.id &&
      scope == that.scope &&
      situations == that.situations &&
      entities == that.entities &&
      locations == that.locations &&
      temporalAnchors == that.temporalAnchors &&
      relations == that.relations &&
      status == that.status &&
      support == that.support
    case _ => false

  override def hashCode(): Int =
    (id, scope, situations, entities, locations, temporalAnchors, relations, status, support).##

  override def toString: String =
    s"EpisodeModel(id=${id.value}, scope=$scope, status=$status, " +
      s"situations=${situations.size}, entities=${entities.size})"

object EpisodeModel:
  val AllowedStatuses: Set[EpistemicStatus] =
    Set(EpistemicStatus.Hypothesized, EpistemicStatus.StructurallyDerived)

  def of(
      id: EpisodeId,
      scope: EpisodeScope,
      situations: Vector[SituationId],
      entities: Set[EntityId],
      locations: Set[PlaceName],
      temporalAnchors: Vector[TimeExpression],
      relations: Vector[NarrativeRelationRef],
      status: EpistemicStatus,
      support: Option[SpanSet]
  ): Either[DomainError, EpisodeModel] =
    if !AllowedStatuses.contains(status) then
      Left(
        DomainError.InvariantViolation(
          s"episode/${id.value}",
          s"inferred episode cannot carry status $status"
        )
      )
    else
      Right(
        new EpisodeModel(
          id,
          scope,
          situations.distinct,
          entities,
          locations,
          temporalAnchors.distinct,
          relations,
          status,
          support
        )
      )

  /** Total constructor for the only status induction ever produces. */
  def hypothesized(
      id: EpisodeId,
      scope: EpisodeScope,
      situations: Vector[SituationId],
      entities: Set[EntityId],
      locations: Set[PlaceName],
      temporalAnchors: Vector[TimeExpression],
      relations: Vector[NarrativeRelationRef],
      support: Option[SpanSet]
  ): EpisodeModel =
    new EpisodeModel(
      id,
      scope,
      situations.distinct,
      entities,
      locations,
      temporalAnchors.distinct,
      relations,
      EpistemicStatus.Hypothesized,
      support
    )

/** The complete interview artifact: source, structured recall, atoms, assessments, episodes,
  * knowledge stores, discourse, and the claim ledger. Status-indexed like `StoryModel`.
  *
  * `targetAlternatives` are competing target hypotheses: under the selected hypothesis they are
  * other episodes, so each is also present (with `OtherSpecific` scope) in `otherEpisodes`.
  */
final case class InterviewModel[S <: ModelStatus] private[interview] (
    schemaVersion: String,
    source: InterviewSource,
    recall: RecallGraph[Checked],
    details: Vector[Detail],
    assessments: Vector[DetailAssessment],
    target: Option[EpisodeModel],
    targetAlternatives: Vector[(EpisodeModel, Double)],
    otherEpisodes: Vector[EpisodeModel],
    ledger: ClaimLedger
):
  lazy val detailById: Map[DetailId, Detail] = details.iterator.map(d => d.id -> d).toMap
  lazy val assessmentById: Map[DetailId, DetailAssessment] =
    assessments.iterator.map(a => a.detail.id -> a).toMap
  lazy val episodeById: Map[EpisodeId, EpisodeModel] =
    (target.toVector ++ otherEpisodes).iterator.map(e => e.id -> e).toMap

  def assessmentsIn(phase: InterviewPhase): Vector[DetailAssessment] =
    assessments.filter(_.promptContext.phase == phase)

object InterviewModel:
  val SchemaVersion = "interview-0.1"

  def draft(
      source: InterviewSource,
      recall: RecallGraph[Checked],
      details: Vector[Detail],
      assessments: Vector[DetailAssessment],
      target: Option[EpisodeModel],
      targetAlternatives: Vector[(EpisodeModel, Double)],
      otherEpisodes: Vector[EpisodeModel],
      ledger: ClaimLedger
  ): InterviewModel[ModelStatus.Draft] =
    new InterviewModel(
      SchemaVersion,
      source,
      recall,
      details,
      assessments,
      target,
      targetAlternatives,
      otherEpisodes,
      ledger
    )

  enum Violation:
    case UnknownDetail(id: DetailId)
    case Unassessed(id: DetailId)
    case DuplicateDetail(id: DetailId)
    case DuplicateAssessment(id: DetailId)
    case DetailOutsideTranscript(id: DetailId)
    case UnknownTurn(id: DetailId, turn: TurnId)
    case UnknownUnit(id: DetailId, unit: String)
    case TargetNotTargetScope(id: EpisodeId)
    case OtherEpisodeIsTarget(id: EpisodeId)
    case DuplicateEpisode(id: EpisodeId)
    case AlternativeNotAmongOthers(id: EpisodeId)
    case UnknownEpisodeReference(detail: DetailId, episode: EpisodeId)
    case ScopeMismatch(
        detail: DetailId,
        episode: EpisodeId,
        addressed: EpisodeScope,
        declared: EpisodeScope
    )

  /** Promote a draft: every detail assessed exactly once, all anchors resolve, episodes are
    * materialized once with coherent scopes, every address references a known episode, and the
    * scope an address asserts agrees with the scope the episode declares.
    */
  def validate(
      m: InterviewModel[ModelStatus.Draft]
  ): ValidatedNec[Violation, InterviewModel[ModelStatus.Validated]] =
    type V = ValidatedNec[Violation, Unit]
    val ok: V = ().validNec
    def bad(v: Violation): V = v.invalidNec
    def check(cond: Boolean, v: => Violation): V = if cond then ok else bad(v)
    def duplicates[A](xs: Vector[A]): Vector[A] = xs.diff(xs.distinct).distinct

    val ids = m.details.map(_.id)
    val dup: V = duplicates(ids).traverse_(d => bad(Violation.DuplicateDetail(d)))
    val known = ids.toSet
    val len = m.recall.transcript.canonicalText.length
    val anchors: V = m.details.traverse_ { d =>
      check(d.support.minSpan.endExclusive <= len, Violation.DetailOutsideTranscript(d.id)) *>
        check(m.source.transcript.byId.contains(d.turn), Violation.UnknownTurn(d.id, d.turn)) *>
        check(
          m.recall.byId.contains(d.sourceUnit),
          Violation.UnknownUnit(d.id, d.sourceUnit.value)
        )
    }
    val assessed: V =
      m.details.traverse_(d =>
        check(m.assessmentById.contains(d.id), Violation.Unassessed(d.id))
      ) *>
        m.assessments.traverse_(a =>
          check(known.contains(a.detail.id), Violation.UnknownDetail(a.detail.id))
        ) *>
        duplicates(m.assessments.map(_.detail.id))
          .traverse_(d => bad(Violation.DuplicateAssessment(d)))
    val episodeIds = (m.target.toVector ++ m.otherEpisodes).map(_.id)
    val episodes: V =
      m.target.toVector.traverse_(t =>
        check(t.scope == EpisodeScope.TargetSpecific, Violation.TargetNotTargetScope(t.id))
      ) *> m.otherEpisodes.traverse_(e =>
        check(e.scope != EpisodeScope.TargetSpecific, Violation.OtherEpisodeIsTarget(e.id))
      ) *> duplicates(episodeIds).traverse_(e => bad(Violation.DuplicateEpisode(e))) *>
        m.targetAlternatives.traverse_ { case (a, _) =>
          check(
            m.otherEpisodes.exists(_.id == a.id),
            Violation.AlternativeNotAmongOthers(a.id)
          )
        }
    val declared: Map[EpisodeId, EpisodeScope] =
      (m.target.toVector ++ m.otherEpisodes).map(e => e.id -> e.scope).toMap
    val refs: V = m.assessments.traverse_ { a =>
      a.address.support.toVector.traverse_ {
        case MemoryAddress.Episode(id, scope) =>
          declared.get(id) match
            case None    => bad(Violation.UnknownEpisodeReference(a.detail.id, id))
            case Some(s) =>
              check(s == scope, Violation.ScopeMismatch(a.detail.id, id, scope, s))
        case _ => ok
      }
    }
    (dup, anchors, assessed, episodes, refs).mapN((_, _, _, _, _) =>
      new InterviewModel[ModelStatus.Validated](
        m.schemaVersion,
        m.source,
        m.recall,
        m.details,
        m.assessments,
        m.target,
        m.targetAlternatives,
        m.otherEpisodes,
        m.ledger
      )
    )
