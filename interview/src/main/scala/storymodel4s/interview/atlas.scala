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

/** An Autobiographical-Interview transcript with its checked protocol structure.
  *
  * Why a non-case class: each probe must reference an interviewer turn with a compatible phase, and
  * phases must be monotone. Construct with [[InterviewSource.of]] (checked) or
  * [[InterviewSource.unsafe]] (throws on violation); there is no unchecked `copy` or `fromProduct`
  * path.
  */
final class InterviewSource private (
    val transcript: TranscriptAtlas,
    val cue: Cue,
    val probes: Vector[Probe],
    val ratings: Option[SubjectiveRatings]
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

  override def equals(other: Any): Boolean = other match
    case that: InterviewSource =>
      transcript == that.transcript && cue == that.cue && probes == that.probes &&
      ratings == that.ratings
    case _ => false

  override def hashCode(): Int = (transcript, cue, probes, ratings).##

  override def toString: String =
    s"InterviewSource(source=${transcript.atlas.source.id.value}, turns=${transcript.turns.size}, " +
      s"probes=${probes.size}, ratings=${ratings.isDefined})"

object InterviewSource:
  private val phaseRank: InterviewPhase => Int =
    case InterviewPhase.FreeRecall    => 0
    case InterviewPhase.GeneralProbe  => 1
    case InterviewPhase.SpecificProbe => 2
    case InterviewPhase.Other(_)      => 3

  private def validate(src: InterviewSource): Either[DomainError, InterviewSource] =
    val checks = for
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
    yield ()
    checks.as(src)

  /** Checked constructor enforcing all interview-source invariants. */
  def of(
      transcript: TranscriptAtlas,
      cue: Cue,
      probes: Vector[Probe],
      ratings: Option[SubjectiveRatings]
  ): Either[DomainError, InterviewSource] =
    validate(new InterviewSource(transcript, cue, probes, ratings))

  /** Throws `IllegalArgumentException` when any interview-source invariant is violated. */
  def unsafe(
      transcript: TranscriptAtlas,
      cue: Cue,
      probes: Vector[Probe],
      ratings: Option[SubjectiveRatings]
  ): InterviewSource =
    of(transcript, cue, probes, ratings)
      .fold(e => throw new IllegalArgumentException(e.message), identity)

  /** Re-checks an existing value; always `Right` for values built through [[of]], kept for
    * aggregate validators that recursively validate their members.
    */
  def validated(src: InterviewSource): Either[DomainError, InterviewSource] = validate(src)
