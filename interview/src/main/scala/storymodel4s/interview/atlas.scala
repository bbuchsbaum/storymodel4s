package storymodel4s.interview

import cats.syntax.all.*

import storymodel4s.core.*
import storymodel4s.features.ScoreEstimate

/** The retrieval cue: the interviewer's request, an optional requested life period, and the event
  * label the participant nominated (if any).
  */
final case class Cue(text: String, requestedPeriod: Option[String], nominatedEvent: Option[String])

enum ProbeKind:
  case General, Specific

/** An interviewer probe anchored to the turn that delivers it. */
final case class Probe(id: PromptId, kind: ProbeKind, turn: TurnId)

/** Participant-reported phenomenology. Stored as evidence, never inferred from content. */
final case class SubjectiveRatings(
    vividness: Option[ScoreEstimate],
    reliving: Option[ScoreEstimate],
    confidence: Option[ScoreEstimate]
)

/** An Autobiographical-Interview transcript with its protocol structure.
  *
  * Invariants (see [[InterviewSource.validated]]): each probe references an interviewer turn; a
  * probe's turn carries a phase consistent with the probe kind; participant turns after a probe
  * carry that probe's phase or a later one.
  */
final case class InterviewSource(
    transcript: TranscriptAtlas,
    cue: Cue,
    probes: Vector[Probe],
    ratings: Option[SubjectiveRatings]
):
  def probeById(id: PromptId): Option[Probe] = probes.find(_.id == id)

  /** The probe (if any) that most recently preceded the turn at `offset`. */
  def probeBefore(offset: Int): Option[Probe] =
    probes
      .flatMap(p => transcript.byId.get(p.turn).map(t => (t.start, p)))
      .filter(_._1 <= offset)
      .maxByOption(_._1)
      .map(_._2)

  def phaseAt(offset: Int): InterviewPhase =
    transcript.phaseOf(offset).getOrElse {
      probeBefore(offset) match
        case Some(Probe(_, ProbeKind.Specific, _)) => InterviewPhase.SpecificProbe
        case Some(Probe(_, ProbeKind.General, _))  => InterviewPhase.GeneralProbe
        case None                                  => InterviewPhase.FreeRecall
    }

object InterviewSource:
  private val phaseRank: InterviewPhase => Int =
    case InterviewPhase.FreeRecall    => 0
    case InterviewPhase.GeneralProbe  => 1
    case InterviewPhase.SpecificProbe => 2
    case InterviewPhase.Other(_)      => 3

  def validated(src: InterviewSource): Either[DomainError, InterviewSource] =
    for
      t <- TranscriptAtlas.validated(src.transcript)
      _ <- src.probes.traverse_ { p =>
        val path = s"interview/probes/${p.id.value}"
        t.byId.get(p.turn) match
          case None => Left(DomainError.InvariantViolation(path, s"unknown turn ${p.turn.value}"))
          case Some(turn) if !t.roleOf(turn.speaker).contains(SpeakerRole.Interviewer) =>
            Left(DomainError.InvariantViolation(path, "probe turn is not an interviewer turn"))
          case Some(turn) =>
            (turn.phase, p.kind) match
              case (Some(InterviewPhase.GeneralProbe), ProbeKind.General)   => Right(())
              case (Some(InterviewPhase.SpecificProbe), ProbeKind.Specific) => Right(())
              case (None, _)                                                => Right(())
              case (Some(ph), k)                                            =>
                Left(DomainError.InvariantViolation(path, s"phase $ph inconsistent with $k probe"))
      }
      _ <- {
        val ranks = t.turns.flatMap(_.phase).map(phaseRank)
        if ranks == ranks.sorted then Right(())
        else Left(DomainError.InvariantViolation("interview/turns", "phases are not monotone"))
      }
    yield src.copy(transcript = t)
