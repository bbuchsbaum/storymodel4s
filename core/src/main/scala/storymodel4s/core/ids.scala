package storymodel4s.core

import cats.{Hash, Order, Show}

/** Lexical rules shared by every identifier kind. */
object IdRules:
  val MaxLength = 256

  def check(kind: String, raw: String): Either[DomainError, String] =
    if raw.isEmpty then Left(DomainError.InvalidId(kind, raw, "empty"))
    else if raw.length > MaxLength then
      Left(DomainError.InvalidId(kind, raw, s"longer than $MaxLength"))
    else if raw.exists(c => c.isWhitespace || c.isControl) then
      Left(DomainError.InvalidId(kind, raw, "contains whitespace or control characters"))
    else Right(raw)

/** Base for opaque string identifiers with validated construction.
  *
  * Why: one implementation of the lexical rules and the cats instances, while each concrete
  * identifier remains a distinct type at compile time.
  */
abstract class OpaqueId(val kind: String):
  opaque type T = String

  /** Validated constructor. */
  def from(raw: String): Either[DomainError, T] = IdRules.check(kind, raw)

  /** Unchecked constructor for literals known to be valid; throws on invalid input. */
  def unsafe(raw: String): T =
    from(raw).fold(e => throw new IllegalArgumentException(e.message), identity)

  extension (id: T) def value: String = id

  given Show[T] = Show.show(identity)
  given Order[T] = Order[String]
  given Hash[T] = Hash[String]

  /** Ordering for standard-library sorting. */
  given Ordering[T] = Ordering.String

object StoryId extends OpaqueId("StoryId")
type StoryId = StoryId.T

object SurfaceUnitId extends OpaqueId("SurfaceUnitId")
type SurfaceUnitId = SurfaceUnitId.T

object EntityId extends OpaqueId("EntityId")
type EntityId = EntityId.T

object SituationId extends OpaqueId("SituationId")
type SituationId = SituationId.T

object SegmentId extends OpaqueId("SegmentId")
type SegmentId = SegmentId.T

object ContextId extends OpaqueId("ContextId")
type ContextId = ContextId.T

object ClaimId extends OpaqueId("ClaimId")
type ClaimId = ClaimId.T

object EvidenceId extends OpaqueId("EvidenceId")
type EvidenceId = EvidenceId.T

object FeatureSpaceId extends OpaqueId("FeatureSpaceId")
type FeatureSpaceId = FeatureSpaceId.T

object PatchId extends OpaqueId("PatchId")
type PatchId = PatchId.T

object StageId extends OpaqueId("StageId")
type StageId = StageId.T

/** Phantom markers distinguishing the kinds of narrative objects a mention or canonical identifier
  * may denote.
  *
  * Why: `MentionId[EntityK]` and `MentionId[SituationK]` must not be confusable, so an entity
  * mention can never be placed in an event-coreference cluster by accident.
  */
sealed trait NarrativeKind
object NarrativeKind:
  sealed trait EntityK extends NarrativeKind
  sealed trait SituationK extends NarrativeKind
  sealed trait SegmentK extends NarrativeKind
  sealed trait ContextK extends NarrativeKind

/** Identifier of a single text-anchored mention of a kind-`K` object. */
object MentionId:
  opaque type MentionId[K <: NarrativeKind] = String
  def from[K <: NarrativeKind](raw: String): Either[DomainError, MentionId[K]] =
    IdRules.check("MentionId", raw)
  def unsafe[K <: NarrativeKind](raw: String): MentionId[K] =
    from[K](raw).fold(e => throw new IllegalArgumentException(e.message), identity)
  extension [K <: NarrativeKind](id: MentionId[K]) def value: String = id
  given [K <: NarrativeKind]: Show[MentionId[K]] = Show.show(identity)
  given [K <: NarrativeKind]: Order[MentionId[K]] = Order[String]
  given [K <: NarrativeKind]: Hash[MentionId[K]] = Hash[String]
  given [K <: NarrativeKind]: Ordering[MentionId[K]] = Ordering.String
type MentionId[K <: NarrativeKind] = MentionId.MentionId[K]

/** Identifier of a canonical (mention-independent) kind-`K` object. */
object CanonicalId:
  opaque type CanonicalId[K <: NarrativeKind] = String
  def from[K <: NarrativeKind](raw: String): Either[DomainError, CanonicalId[K]] =
    IdRules.check("CanonicalId", raw)
  def unsafe[K <: NarrativeKind](raw: String): CanonicalId[K] =
    from[K](raw).fold(e => throw new IllegalArgumentException(e.message), identity)
  extension [K <: NarrativeKind](id: CanonicalId[K]) def value: String = id
  given [K <: NarrativeKind]: Show[CanonicalId[K]] = Show.show(identity)
  given [K <: NarrativeKind]: Order[CanonicalId[K]] = Order[String]
  given [K <: NarrativeKind]: Hash[CanonicalId[K]] = Hash[String]
  given [K <: NarrativeKind]: Ordering[CanonicalId[K]] = Ordering.String
type CanonicalId[K <: NarrativeKind] = CanonicalId.CanonicalId[K]

/** A calibrated probability in `[0, 1]`.
  *
  * Why: raw model scores must never be typed as probabilities; only values that came through a
  * recorded calibration model may inhabit this type.
  */
object Probability:
  opaque type Probability = Double
  def from(v: Double): Either[DomainError, Probability] =
    if v.isNaN || v < 0.0 || v > 1.0 then Left(DomainError.InvalidProbability(v)) else Right(v)
  def unsafe(v: Double): Probability =
    from(v).fold(e => throw new IllegalArgumentException(e.message), identity)
  val Zero: Probability = 0.0
  val One: Probability = 1.0
  extension (p: Probability)
    def value: Double = p
    def complement: Probability = 1.0 - p
  given Show[Probability] = Show.show(_.toString)
  given Order[Probability] = Order[Double]
  given Ordering[Probability] = Ordering.Double.TotalOrdering
type Probability = Probability.Probability

/** Who produced a number or a licence in a [[Credence]]. Three identifier kinds, not one: a scorer
  * emits a raw number, a rule determines a value from its inputs, and a calibration model maps a
  * raw number to a probability. They are different roles, and a type per role keeps a reader from
  * taking the name of a table lookup for the name of a fitted model.
  */
object ScorerId extends OpaqueId("ScorerId")
type ScorerId = ScorerId.T

object RuleId extends OpaqueId("RuleId")
type RuleId = RuleId.T

object CalibrationModelId extends OpaqueId("CalibrationModelId")
type CalibrationModelId = CalibrationModelId.T

/** What was measured about a claim's strength, if anything.
  *
  * `Unmeasured` is a first-class state: a parser that reports no per-node confidence, a rule that
  * reads no number, and a claim nobody has scored all stop here rather than at `1.0`. A raw number
  * names the scorer that produced it, so an imputed table constant and a fitted model's output
  * cannot share a representation (design contract 7).
  */
enum Score:
  case Unmeasured
  case Raw(value: Double, scorer: ScorerId)

  def measured: Option[Double] = this match
    case Unmeasured    => None
    case Raw(value, _) => Some(value)

  def render: String = this match
    case Unmeasured         => "unmeasured"
    case Raw(value, scorer) => s"raw:${scorer.value}=${Score.hexBits(value)}"

object Score:
  /** IEEE-754 bits as sixteen lowercase hex digits: the same text on JVM, Scala.js, and Native,
    * where `Double.toString` is not.
    */
  def hexBits(value: Double): String =
    val hex = java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(value))
    "0x" + ("0" * (16 - hex.length)) + hex

  /** A finite raw score from a named scorer; negative zero folds onto zero so equal values have one
    * identity on every platform.
    */
  def raw(value: Double, scorer: ScorerId): Either[DomainError, Score] =
    if value.isNaN || value.isInfinite then
      Left(DomainError.InvalidFormat("Score", value.toString, "non-finite raw score"))
    else Right(Raw(if value == 0.0 then 0.0 else value, scorer))

  given Order[Score] = Order.by {
    case Unmeasured         => (0, 0.0, "")
    case Raw(value, scorer) => (1, value, scorer.value)
  }
  given Ordering[Score] = Order[Score].toOrdering

/** What entitles a reader to a probability for a claim. Three states that must never share a
  * representation:
  *
  *   - `Uncalibrated`: a probability would be the right question and no model has answered it;
  *   - `Calibrated`: a named fitted model mapped the claim's raw score to a probability;
  *   - `Determined`: the claim's value is a total function of its evidence and upstream claims
  *     under a named rule. It has no probability of its own; its certainty is exactly that of its
  *     inputs. Recording it as a calibrated `1.0` put the certainty of a total function in the
  *     field a reader takes for certainty about the world, which is design contract 7's
  *     indistinguishability test failing.
  */
enum CredenceBasis:
  case Uncalibrated
  case Calibrated(probability: Probability, model: CalibrationModelId)
  case Determined(rule: RuleId)

  def calibratedProbability: Option[Probability] = this match
    case Calibrated(p, _) => Some(p)
    case _                => None

  def calibrationModel: Option[CalibrationModelId] = this match
    case Calibrated(_, m) => Some(m)
    case _                => None

  def determiningRule: Option[RuleId] = this match
    case Determined(r) => Some(r)
    case _             => None

  def render: String = this match
    case Uncalibrated         => "uncalibrated"
    case Calibrated(p, model) => s"calibrated:${model.value}=${p.value}"
    case Determined(rule)     => s"determined:${rule.value}"

object CredenceBasis:
  given Order[CredenceBasis] = Order.by {
    case Uncalibrated         => (0, 0.0, "")
    case Calibrated(p, model) => (1, p.value, model.value)
    case Determined(rule)     => (2, 0.0, rule.value)
  }
  given Ordering[CredenceBasis] = Order[CredenceBasis].toOrdering

/** Strength of belief in a claim, as two coordinates: the [[Score]] someone measured, and the
  * [[CredenceBasis]] that entitles a reader to a probability.
  *
  * Invariant: a calibrated probability rests on a raw score, because a calibration model maps a
  * number and there is nothing to map from `Unmeasured`. This is a non-case class so `fromProduct`
  * cannot mint a calibrated probability without that score (design-contract rule 3).
  */
final class Credence private (val score: Score, val basis: CredenceBasis):
  /** The raw number, when one was measured. Never a probability. */
  def rawScore: Option[Double] = score.measured

  /** The calibrated probability, when a named model produced one. */
  def calibrated: Option[Probability] = basis.calibratedProbability

  def calibrationModel: Option[CalibrationModelId] = basis.calibrationModel

  def isDetermined: Boolean = basis.determiningRule.isDefined

  override def equals(other: Any): Boolean = other match
    case that: Credence => score == that.score && basis == that.basis
    case _              => false

  override def hashCode(): Int = (score, basis).hashCode()

  override def toString: String = s"Credence(${score.render}, ${basis.render})"

object Credence:
  /** Nothing measured, nothing fitted, nothing determined: the honest value for a claim whose
    * producer reported no confidence and whose value follows from no rule.
    */
  val unmeasured: Credence = new Credence(Score.Unmeasured, CredenceBasis.Uncalibrated)

  def of(score: Score, basis: CredenceBasis): Either[DomainError, Credence] =
    val checked = score match
      case Score.Raw(value, scorer) => Score.raw(value, scorer)
      case Score.Unmeasured         => Right(Score.Unmeasured)
    checked.flatMap { s =>
      (s, basis) match
        case (Score.Unmeasured, CredenceBasis.Calibrated(_, _)) =>
          Left(
            DomainError.InvariantViolation("credence", "calibrated probability without a raw score")
          )
        case _ => Right(new Credence(s, basis))
    }

  /** A raw score from a named scorer, with no probability. */
  def raw(value: Double, scorer: ScorerId): Either[DomainError, Credence] =
    Score.raw(value, scorer).map(new Credence(_, CredenceBasis.Uncalibrated))

  /** A raw score mapped to a probability by a named calibration model. */
  def calibrated(
      value: Double,
      scorer: ScorerId,
      probability: Probability,
      model: CalibrationModelId
  ): Either[DomainError, Credence] =
    Score.raw(value, scorer).map(new Credence(_, CredenceBasis.Calibrated(probability, model)))

  /** A value determined by a named rule from its inputs, with or without a measured score. */
  def determined(rule: RuleId, score: Score = Score.Unmeasured): Either[DomainError, Credence] =
    of(score, CredenceBasis.Determined(rule))

  def unsafeRaw(value: Double, scorer: ScorerId): Credence =
    raw(value, scorer).fold(e => throw new IllegalArgumentException(e.message), identity)

  def unsafeDetermined(rule: RuleId, score: Score = Score.Unmeasured): Credence =
    determined(rule, score).fold(e => throw new IllegalArgumentException(e.message), identity)

  given Show[Credence] = Show.show(_.toString)
  given Order[Credence] = Order.by(c => (c.score, c.basis))
  given Ordering[Credence] = Order[Credence].toOrdering
