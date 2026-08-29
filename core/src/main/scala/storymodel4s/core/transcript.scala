package storymodel4s.core

import cats.syntax.all.*

object SpeakerId extends OpaqueId("SpeakerId")
type SpeakerId = SpeakerId.T

object TurnId extends OpaqueId("TurnId")
type TurnId = TurnId.T

object PromptId extends OpaqueId("PromptId")
type PromptId = PromptId.T

/** Phase of an Autobiographical-Interview-style protocol in which a turn was produced.
  *
  * `Other(label)` is the `Custom`-style escape for protocol phases outside the standard three (e.g.
  * a cued-imagery phase); the label is a protocol identifier, never free prose, and comparisons are
  * exact.
  */
enum InterviewPhase:
  case FreeRecall, GeneralProbe, SpecificProbe
  case Other(label: String)

/** Who a speaker is, for traversal filters; the scientific role, not a name. */
enum SpeakerRole:
  case Participant, Interviewer, Other

/** Audio interval in milliseconds, half-open.
  *
  * Non-case so `fromProduct` cannot mint a negative start or an inverted interval.
  */
final class AudioSpan private (val startMillis: Long, val endMillis: Long):
  def durationMillis: Long = endMillis - startMillis
  def contains(t: Long): Boolean = t >= startMillis && t < endMillis
  def overlaps(o: AudioSpan): Boolean = startMillis < o.endMillis && o.startMillis < endMillis

  override def equals(other: Any): Boolean = other match
    case that: AudioSpan => startMillis == that.startMillis && endMillis == that.endMillis
    case _               => false

  override def hashCode(): Int = (startMillis, endMillis).hashCode()

  override def toString: String = s"AudioSpan[$startMillis, $endMillis)"

object AudioSpan:
  def of(startMillis: Long, endMillis: Long): Either[DomainError, AudioSpan] =
    if startMillis < 0 then
      Left(DomainError.InvalidFormat("AudioSpan", s"[$startMillis,$endMillis)", "negative start"))
    else if endMillis < startMillis then
      Left(
        DomainError.InvalidFormat("AudioSpan", s"[$startMillis,$endMillis)", "end precedes start")
      )
    else Right(new AudioSpan(startMillis, endMillis))
  def unsafe(startMillis: Long, endMillis: Long): AudioSpan =
    of(startMillis, endMillis).fold(e => throw new IllegalArgumentException(e.message), identity)

/** One speaker turn, anchored to the transcript text by exact spans; optionally to audio time, an
  * interview phase, and the prompt it answers.
  *
  * Why an overlay: text coordinates stay the single basis for every claim; audio timing and
  * protocol structure are additional views, never a replacement coordinate system.
  */
final case class TranscriptTurn(
    id: TurnId,
    speaker: SpeakerId,
    support: SpanSet,
    audio: Option[AudioSpan],
    phase: Option[InterviewPhase],
    prompt: Option[PromptId]
):
  def start: Int = support.minSpan.start
  def endExclusive: Int = support.minSpan.endExclusive

/** A surface atlas plus speaker turns and speaker roles.
  *
  * Why a non-case class: unique IDs, bounded non-overlapping supports, declared speakers, and
  * coherent text/audio order must hold for every transcript that exists. Construct with
  * [[TranscriptAtlas.of]] (checked) or [[TranscriptAtlas.unsafe]] (throws on violation); there is
  * no unchecked `copy` or `fromProduct` path.
  */
final class TranscriptAtlas private (
    val atlas: SurfaceAtlas,
    val turns: Vector[TranscriptTurn],
    val speakers: Map[SpeakerId, SpeakerRole]
):
  lazy val byId: Map[TurnId, TranscriptTurn] = turns.iterator.map(t => t.id -> t).toMap

  def roleOf(speaker: SpeakerId): Option[SpeakerRole] = speakers.get(speaker)

  def turnsBy(speaker: SpeakerId): Vector[TranscriptTurn] = turns.filter(_.speaker == speaker)
  def turnsByRole(role: SpeakerRole): Vector[TranscriptTurn] =
    turns.filter(t => speakers.get(t.speaker).contains(role))
  def turnsIn(phase: InterviewPhase): Vector[TranscriptTurn] = turns.filter(_.phase.contains(phase))

  /** Tokens of the atlas lying inside `turn`'s support. */
  def tokensOf(turn: TranscriptTurn): Vector[SurfaceUnit] =
    turn.support.spans.toVector
      .flatMap(s => atlas.unitsOverlapping(s, SurfaceUnitKind.Token))
      .distinct

  def participantTokens: Vector[SurfaceUnit] =
    turnsByRole(SpeakerRole.Participant).flatMap(tokensOf)

  def interviewerTokens: Vector[SurfaceUnit] =
    turnsByRole(SpeakerRole.Interviewer).flatMap(tokensOf)

  /** Tokens in every turn strictly after the first turn carrying `prompt`: post-probe material. */
  def tokensAfter(prompt: PromptId): Vector[SurfaceUnit] =
    val i = turns.indexWhere(_.prompt.contains(prompt))
    if i < 0 then Vector.empty else turns.drop(i + 1).flatMap(tokensOf)

  /** Turns from the one carrying `prompt` onward (inclusive). */
  def turnsFrom(prompt: PromptId): Vector[TranscriptTurn] =
    val i = turns.indexWhere(_.prompt.contains(prompt))
    if i < 0 then Vector.empty else turns.drop(i)

  /** The turn whose support contains the text offset. */
  def turnAt(offset: Int): Option[TranscriptTurn] =
    turns.find(_.support.spans.exists(_.contains(offset)))

  /** The turn whose audio interval contains the time, when audio timing is present. */
  def turnAtAudio(millis: Long): Option[TranscriptTurn] =
    turns.find(_.audio.exists(_.contains(millis)))

  def phaseOf(offset: Int): Option[InterviewPhase] = turnAt(offset).flatMap(_.phase)

  override def equals(other: Any): Boolean = other match
    case that: TranscriptAtlas =>
      atlas == that.atlas && turns == that.turns && speakers == that.speakers
    case _ => false

  override def hashCode(): Int = (atlas, turns, speakers).hashCode()

  override def toString: String =
    s"TranscriptAtlas(${atlas.source.id.value}, turns=${turns.size}, speakers=${speakers.size})"

object TranscriptAtlas:
  private def validate(t: TranscriptAtlas): Either[DomainError, TranscriptAtlas] =
    val textLen = t.atlas.source.canonicalText.length
    val dup = t.turns.groupBy(_.id).collectFirst { case (id, ts) if ts.size > 1 => id }
    val checks: Either[DomainError, Unit] = for
      _ <- dup.toLeft(()).leftMap(id => DomainError.DuplicateId("TurnId", id.value))
      _ <- t.turns.traverse_ { turn =>
        val path = s"transcript/turns/${turn.id.value}"
        if turn.support.minSpan.endExclusive > textLen then
          Left(DomainError.InvariantViolation(path, s"support exceeds text length $textLen"))
        else if !t.speakers.contains(turn.speaker) then
          Left(DomainError.InvariantViolation(path, s"undeclared speaker ${turn.speaker.value}"))
        else Right(())
      }
      _ <- t.turns.sliding(2).toVector.traverse_ {
        case Vector(a, b) =>
          val path = s"transcript/turns/${b.id.value}"
          if b.start < a.start then Left(DomainError.InvariantViolation(path, "turns not ordered"))
          else if a.support.minSpan.overlaps(b.support.minSpan) then
            Left(DomainError.InvariantViolation(path, s"overlaps turn ${a.id.value}"))
          else
            (a.audio, b.audio) match
              case (Some(x), Some(y)) if y.startMillis < x.startMillis =>
                Left(DomainError.InvariantViolation(path, "audio order disagrees with text order"))
              case _ => Right(())
        case _ => Right(())
      }
    yield ()
    checks.as(t)

  /** Checked constructor enforcing all transcript-atlas invariants. */
  def of(
      atlas: SurfaceAtlas,
      turns: Vector[TranscriptTurn],
      speakers: Map[SpeakerId, SpeakerRole]
  ): Either[DomainError, TranscriptAtlas] =
    validate(new TranscriptAtlas(atlas, turns, speakers))

  /** Throws `IllegalArgumentException` when any transcript-atlas invariant is violated. */
  def unsafe(
      atlas: SurfaceAtlas,
      turns: Vector[TranscriptTurn],
      speakers: Map[SpeakerId, SpeakerRole]
  ): TranscriptAtlas =
    of(atlas, turns, speakers).fold(e => throw new IllegalArgumentException(e.message), identity)

  /** Re-checks an existing value; always `Right` for values built through [[of]], kept for
    * aggregate validators that recursively validate their members.
    */
  def validated(t: TranscriptAtlas): Either[DomainError, TranscriptAtlas] = validate(t)
