package storymodel4s.interview

import cats.data.ValidatedNec
import cats.syntax.all.*

import storymodel4s.core.*
import storymodel4s.recall.RecallGraph
import storymodel4s.story.ModelStatus

/** An episode as implied by the transcript (design record §60.1).
  *
  * Its `status` can only be `Hypothesized` or `StructurallyDerived`: an inferred episode is never
  * an observed source, so it can never carry `SurfaceExplicit` truth about the world.
  */
final case class EpisodeModel private (
    id: EpisodeId,
    scope: EpisodeScope,
    situations: Vector[SituationId],
    entities: Set[EntityId],
    locations: Set[String],
    temporalAnchors: Vector[String],
    relations: Vector[NarrativeRelationRef],
    status: EpistemicStatus,
    support: Option[SpanSet]
)

object EpisodeModel:
  val AllowedStatuses: Set[EpistemicStatus] =
    Set(EpistemicStatus.Hypothesized, EpistemicStatus.StructurallyDerived)

  def of(
      id: EpisodeId,
      scope: EpisodeScope,
      situations: Vector[SituationId],
      entities: Set[EntityId],
      locations: Set[String],
      temporalAnchors: Vector[String],
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

/** The complete interview artifact: source, structured recall, atoms, assessments, episodes,
  * knowledge stores, discourse, and the claim ledger. Status-indexed like `StoryModel`.
  */
final case class InterviewModel[S <: ModelStatus] private[interview] (
    schemaVersion: String,
    source: InterviewSource,
    recall: RecallGraph,
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

  def assessmentsIn(phase: InterviewPhase): Vector[DetailAssessment] =
    assessments.filter(_.promptContext.phase == phase)

object InterviewModel:
  val SchemaVersion = "interview-0.1"

  def draft(
      source: InterviewSource,
      recall: RecallGraph,
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
    case DetailOutsideTranscript(id: DetailId)
    case UnknownTurn(id: DetailId, turn: TurnId)
    case UnknownUnit(id: DetailId, unit: String)
    case TargetNotTargetScope(id: EpisodeId)
    case OtherEpisodeIsTarget(id: EpisodeId)
    case UnknownEpisodeReference(detail: DetailId, episode: EpisodeId)

  /** Promote a draft: every detail assessed exactly once, all anchors resolve, episode scopes are
    * coherent, and every address references a known episode.
    */
  def validate(
      m: InterviewModel[ModelStatus.Draft]
  ): ValidatedNec[Violation, InterviewModel[ModelStatus.Validated]] =
    type V = ValidatedNec[Violation, Unit]
    val ok: V = ().validNec
    def bad(v: Violation): V = v.invalidNec
    def check(cond: Boolean, v: => Violation): V = if cond then ok else bad(v)

    val ids = m.details.map(_.id)
    val dup: V =
      ids
        .diff(ids.distinct)
        .distinct
        .headOption
        .map(d => bad(Violation.DuplicateDetail(d)))
        .getOrElse(ok)
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
        )
    val episodes: V =
      m.target.toVector.traverse_(t =>
        check(t.scope == EpisodeScope.TargetSpecific, Violation.TargetNotTargetScope(t.id))
      ) *> m.otherEpisodes.traverse_(e =>
        check(e.scope != EpisodeScope.TargetSpecific, Violation.OtherEpisodeIsTarget(e.id))
      )
    val knownEpisodes: Set[EpisodeId] =
      (m.target.toVector ++ m.targetAlternatives.map(_._1) ++ m.otherEpisodes).map(_.id).toSet
    val refs: V = m.assessments.traverse_ { a =>
      a.address.support.toVector.traverse_ {
        case MemoryAddress.Episode(id, _) =>
          check(knownEpisodes.contains(id), Violation.UnknownEpisodeReference(a.detail.id, id))
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
