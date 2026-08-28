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

/** One entry of the pseudonymization key: a surface form and its stable relational pseudonym. */
final case class PseudonymEntry(surface: String, pseudonym: String)

/** Result of pseudonymization: a new source, the exact offset map, and the key needed to reverse.
  *
  * The original atlas is never mutated; every offset in the pseudonymized text maps back through
  * `segments` so that spans measured against the new text can be localized in the original.
  */
final case class Pseudonymized(
    source: StorySource,
    segments: Vector[Pseudonymized.Segment],
    key: Map[String, String]
):
  /** Map an original offset to the pseudonymized text; replaced regions map to their start. */
  def mapOffset(original: Int): Int =
    segments.find(s => s.original.contains(original) || s.original.endExclusive == original) match
      case Some(seg) if seg.replaced => seg.target.start
      case Some(seg)                 => seg.target.start + (original - seg.original.start)
      case None                      => original

  def mapSpan(span: TextSpan): Either[DomainError, TextSpan] =
    TextSpan.of(mapOffset(span.start), mapOffsetEnd(span.endExclusive))

  private def mapOffsetEnd(originalEnd: Int): Int =
    segments.find(s => s.original.contains(originalEnd - 1)) match
      case Some(seg) if seg.replaced => seg.target.endExclusive
      case Some(seg)                 => seg.target.start + (originalEnd - seg.original.start)
      case None                      => originalEnd

object Pseudonymized:
  /** A run of the original text that was either copied verbatim or replaced by a pseudonym. */
  final case class Segment(original: TextSpan, target: TextSpan, replaced: Boolean)

/** Deterministic relational pseudonymization (design record §73).
  *
  * Why relational pseudonyms: replacing every person with `[PERSON]` collapses identity and
  * destroys coreference; `[PERSON_1]`, `[SISTER_OF_SPEAKER]` keep the graph intact. Matching is
  * longest-surface-first, whole-word, case-insensitive; reversal needs the key.
  */
object Pseudonymizer:
  def pseudonymize(
      source: StorySource,
      table: Vector[PseudonymEntry]
  ): Either[DomainError, Pseudonymized] =
    val text = source.canonicalText
    val entries = table.filter(_.surface.nonEmpty).sortBy(e => (-e.surface.length, e.surface))
    val matches: Vector[(TextSpan, String)] = entries
      .flatMap { e =>
        // No lookbehind (Scala.js regex portability): capture the boundary char, use group 2.
        val re = ("(?i)(^|[^A-Za-z0-9])(" + java.util.regex.Pattern.quote(e.surface) +
          ")(?![A-Za-z0-9])").r
        re.findAllMatchIn(text)
          .map(m => (TextSpan.unsafe(m.start(2), m.end(2)), e.pseudonym))
          .toVector
      }
      .sortBy { case (span, _) => (span.start, -span.length) }
      .foldLeft(Vector.empty[(TextSpan, String)]) { (acc, m) =>
        if acc.exists(_._1.overlaps(m._1)) then acc else acc :+ m
      }
    val sb = new StringBuilder
    val segs = Vector.newBuilder[Pseudonymized.Segment]
    var cursor = 0
    matches.foreach { case (span, pseudo) =>
      if span.start > cursor then
        val t0 = sb.length
        sb.append(text.substring(cursor, span.start))
        segs += Pseudonymized.Segment(
          TextSpan.unsafe(cursor, span.start),
          TextSpan.unsafe(t0, sb.length),
          replaced = false
        )
      val t0 = sb.length
      sb.append(pseudo)
      segs += Pseudonymized.Segment(span, TextSpan.unsafe(t0, sb.length), replaced = true)
      cursor = span.endExclusive
    }
    if cursor < text.length then
      val t0 = sb.length
      sb.append(text.substring(cursor))
      segs += Pseudonymized.Segment(
        TextSpan.unsafe(cursor, text.length),
        TextSpan.unsafe(t0, sb.length),
        replaced = false
      )
    val key = table.map(e => e.pseudonym -> e.surface).toMap
    StorySource
      .fromText(
        sb.toString,
        source.title,
        source.language,
        source.metadata + ("pseudonymized" -> "true") + ("pseudonymizedFrom" -> source.id.value)
      )
      .map(Pseudonymized(_, segs.result(), key))

  /** Restore the original wording; only possible with the key. */
  def reverse(p: Pseudonymized): String =
    p.key.toVector.sortBy(e => (-e._1.length, e._1)).foldLeft(p.source.canonicalText) {
      case (acc, (pseudo, surface)) => acc.replace(pseudo, surface)
    }
