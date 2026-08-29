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

/** Strength of belief in a claim, keeping the uncalibrated score distinct from any calibrated
  * probability.
  *
  * Invariant: a calibrated value is present only if the calibration model that produced it is
  * named. This is a non-case class so `fromProduct` cannot mint a calibrated probability without
  * that name (design-contract rule 3).
  */
final class Credence private (
    val rawScore: Double,
    val calibrated: Option[Probability],
    val calibrationModel: Option[String]
):
  override def equals(other: Any): Boolean = other match
    case that: Credence =>
      rawScore == that.rawScore &&
      calibrated == that.calibrated &&
      calibrationModel == that.calibrationModel
    case _ => false

  override def hashCode(): Int = (rawScore, calibrated, calibrationModel).hashCode()

  override def toString: String = (calibrated, calibrationModel) match
    case (Some(p), Some(m)) => s"Credence(raw=$rawScore, p=${p.value}, model=$m)"
    case _                  => s"Credence(raw=$rawScore)"

object Credence:
  def raw(score: Double): Either[DomainError, Credence] =
    if score.isNaN || score.isInfinite then
      Left(DomainError.InvalidFormat("Credence", score.toString, "non-finite raw score"))
    else Right(new Credence(score, None, None))

  def calibrated(
      score: Double,
      probability: Probability,
      model: String
  ): Either[DomainError, Credence] =
    if model.trim.isEmpty then
      Left(DomainError.InvalidFormat("Credence", model, "empty calibration model"))
    else raw(score).map(_ => new Credence(score, Some(probability), Some(model)))

  def from(
      score: Double,
      calibrated: Option[Probability],
      model: Option[String]
  ): Either[DomainError, Credence] =
    (calibrated, model) match
      case (None, None)       => raw(score)
      case (Some(p), Some(m)) => Credence.calibrated(score, p, m)
      case (Some(_), None)    =>
        Left(
          DomainError.InvariantViolation(
            "credence",
            "calibrated probability without calibration model"
          )
        )
      case (None, Some(_)) =>
        Left(DomainError.InvariantViolation("credence", "calibration model without probability"))

  def unsafeRaw(score: Double): Credence =
    raw(score).fold(e => throw new IllegalArgumentException(e.message), identity)

  given Show[Credence] = Show.show { c =>
    (c.calibrated, c.calibrationModel) match
      case (Some(p), Some(m)) => s"Credence(raw=${c.rawScore}, p=${p.value}, model=$m)"
      case _                  => s"Credence(raw=${c.rawScore})"
  }
