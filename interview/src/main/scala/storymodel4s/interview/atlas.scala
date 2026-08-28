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

/** One entry of the pseudonymization key: a surface form and its stable relational pseudonym.
  *
  * Matching is case-sensitive and whole-word by default. `caseInsensitive` is opt-in because a name
  * that is also an ordinary word ("Will", "Mark", "Grace") would otherwise rewrite the word
  * wherever it occurs; an entry that takes that risk must say so.
  */
final case class PseudonymEntry(
    surface: String,
    pseudonym: String,
    caseInsensitive: Boolean = false
)

/** Result of pseudonymization: a new source, the exact offset map, and the key needed to reverse.
  *
  * The original atlas is never mutated; every offset in the original text maps through `segments`
  * so that spans measured against the original can be localized in the pseudonymized text and back.
  * `key` maps each pseudonym to every surface form it replaced: several surfaces may share one
  * relational pseudonym, in which case the mapping is not reversible (see
  * [[Pseudonymizer.reverse]]).
  */
final case class Pseudonymized(
    source: StorySource,
    segments: Vector[Pseudonymized.Segment],
    key: Map[String, Vector[String]]
):
  private lazy val originalLength: Int =
    segments.lastOption.map(_.original.endExclusive).getOrElse(0)
  private lazy val targetLength: Int = segments.lastOption.map(_.target.endExclusive).getOrElse(0)

  /** Map an original *start* offset to the pseudonymized text.
    *
    * A boundary offset belongs to the segment that starts there (the following one), so a token
    * that begins right after a replaced name maps to the position right after its pseudonym.
    * Offsets strictly inside a replaced region map to the pseudonym's start.
    */
  def mapOffset(original: Int): Int =
    if original >= originalLength then targetLength - (originalLength - original)
    else
      segments.find(_.original.contains(original)) match
        case Some(seg) if seg.replaced.isDefined => seg.target.start
        case Some(seg) => seg.target.start + (original - seg.original.start)
        case None      => original

  /** Map an original *end* (exclusive) offset; an end inside a replaced region maps to the end of
    * the pseudonym so that the mapped span still covers the whole replacement.
    */
  def mapOffsetEnd(originalEnd: Int): Int =
    if originalEnd <= 0 then 0
    else if originalEnd >= originalLength then targetLength - (originalLength - originalEnd)
    else
      segments.find(_.original.contains(originalEnd - 1)) match
        case Some(seg) if seg.replaced.isDefined => seg.target.endExclusive
        case Some(seg) => seg.target.start + (originalEnd - seg.original.start)
        case None      => originalEnd

  def mapSpan(span: TextSpan): Either[DomainError, TextSpan] =
    if span.isEmpty then TextSpan.of(mapOffset(span.start), mapOffset(span.start))
    else TextSpan.of(mapOffset(span.start), mapOffsetEnd(span.endExclusive))

  /** True when every pseudonym stands for exactly one surface form. */
  def isReversible: Boolean = key.values.forall(_.size == 1)

object Pseudonymized:
  /** A run of the original text that was either copied verbatim (`replaced = None`) or replaced by
    * the named pseudonym.
    */
  final case class Segment(original: TextSpan, target: TextSpan, replaced: Option[String])

/** Deterministic relational pseudonymization (design record §73).
  *
  * Why relational pseudonyms: replacing every person with `[PERSON]` collapses identity and
  * destroys coreference; `[PERSON_1]`, `[SISTER_OF_SPEAKER]` keep the graph intact. Matching is
  * longest-surface-first and whole-word (a match may not be preceded or followed by a letter or
  * digit, checked per code point, so no regular expressions and no lookaround are involved).
  */
object Pseudonymizer:
  private def isWordChar(cp: Int): Boolean = Character.isLetterOrDigit(cp)

  private def boundaryBefore(text: String, i: Int): Boolean =
    i == 0 || !isWordChar(text.codePointBefore(i))

  private def boundaryAfter(text: String, end: Int): Boolean =
    end >= text.length || !isWordChar(text.codePointAt(end))

  private def regionMatches(text: String, at: Int, surface: String, ci: Boolean): Boolean =
    text.regionMatches(ci, at, surface, 0, surface.length)

  /** All whole-word occurrences of `surface` in `text`, left to right. */
  private[interview] def occurrences(text: String, surface: String, ci: Boolean): Vector[TextSpan] =
    val out = Vector.newBuilder[TextSpan]
    var i = 0
    val n = surface.length
    while i + n <= text.length do
      if regionMatches(text, i, surface, ci) && boundaryBefore(text, i) && boundaryAfter(
          text,
          i + n
        )
      then
        out += TextSpan.unsafe(i, i + n)
        i += n
      else i += 1
    out.result()

  def pseudonymize(
      source: StorySource,
      table: Vector[PseudonymEntry]
  ): Either[DomainError, Pseudonymized] =
    val text = source.canonicalText
    val entries = table.filter(_.surface.nonEmpty).sortBy(e => (-e.surface.length, e.surface))
    val matches: Vector[(TextSpan, String)] = entries
      .flatMap(e => occurrences(text, e.surface, e.caseInsensitive).map(_ -> e.pseudonym))
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
          None
        )
      val t0 = sb.length
      sb.append(pseudo)
      segs += Pseudonymized.Segment(span, TextSpan.unsafe(t0, sb.length), Some(pseudo))
      cursor = span.endExclusive
    }
    if cursor < text.length then
      val t0 = sb.length
      sb.append(text.substring(cursor))
      segs += Pseudonymized.Segment(
        TextSpan.unsafe(cursor, text.length),
        TextSpan.unsafe(t0, sb.length),
        None
      )
    val key: Map[String, Vector[String]] =
      table.groupMap(_.pseudonym)(_.surface).view.mapValues(_.distinct).toMap
    StorySource
      .fromText(
        sb.toString,
        source.title,
        source.language,
        source.metadata + ("pseudonymized" -> "true") + ("pseudonymizedFrom" -> source.id.value)
      )
      .flatMap { out =>
        // The offset map is only exact if canonicalization left the rewritten text untouched.
        if out.canonicalText == sb.toString then Right(Pseudonymized(out, segs.result(), key))
        else
          Left(
            DomainError.InvariantViolation(
              "pseudonymize/canonical",
              "rewritten text changed under canonicalization; offset map would be inexact"
            )
          )
      }

  /** Restore the original wording by walking the segments. Exact when every pseudonym stands for
    * one surface form; a relational (many-to-one) key is reported as irreversible rather than
    * guessed.
    */
  def reverse(p: Pseudonymized): Either[DomainError, String] =
    val text = p.source.canonicalText
    p.segments
      .traverse { seg =>
        seg.replaced match
          case None         => Right(text.substring(seg.target.start, seg.target.endExclusive))
          case Some(pseudo) =>
            p.key.getOrElse(pseudo, Vector.empty) match
              case Vector(surface) => Right(surface)
              case surfaces        =>
                Left(
                  DomainError.InvariantViolation(
                    s"pseudonymize/reverse/$pseudo",
                    s"irreversible: pseudonym stands for ${surfaces.size} surface forms"
                  )
                )
      }
      .map(_.mkString)
