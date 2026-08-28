package storymodel4s.features

import storymodel4s.core.*

/** Independent sources of change evidence at a gap between adjacent discourse units (design record
  * §112). Stored separately so a hierarchy build can say which signals it used.
  */
enum BoundarySignal:
  case Semantic, Proposition, Entities, Location, Context, WorldTime, Goals, Sensory, Affect,
    Imageability, DiscourseCue, ModelVote
  case Custom(namespace: String, name: String)

/** Typed evidence vector at one candidate boundary, at one hierarchy level.
  *
  * `inputSpaces` records, per signal, the feature spaces that fed it, so later analyses can detect
  * circularity signal by signal (§116) rather than over one undifferentiated set.
  */
final case class BoundaryEvidence(
    target: FeatureTarget.Boundary,
    signals: Map[BoundarySignal, ScoreEstimate],
    inputSpaces: Map[BoundarySignal, Set[FeatureSpaceId]],
    level: Int,
    claims: Vector[ClaimId] = Vector.empty
):
  def observedSignals: Map[BoundarySignal, Double] =
    signals.collect { case (k, Estimate.Observed(v, _)) if Estimate.isFinite(v) => k -> v }

  def allInputSpaces: Set[FeatureSpaceId] = inputSpaces.values.flatten.toSet

  def inputsOf(signal: BoundarySignal): Set[FeatureSpaceId] =
    inputSpaces.getOrElse(signal, Set.empty)

/** Unit of a duration estimate; `Unknown` when the text gives magnitude words without a scale. */
enum DurationUnit:
  case Seconds, Minutes, Hours, Days, Weeks, Months, Years, Unknown

/** Estimated size of a world-time jump; text rarely fixes it, so the magnitude is an estimate. */
final case class DurationEstimate(
    magnitude: ScoreEstimate,
    unit: DurationUnit,
    cue: Option[SpanSet]
)

/** Closed vocabulary for the temporal relation named by a hypothesis. Mirrors the story-level
  * temporal relation enum without depending on `story`; `Custom` carries imported labels.
  */
enum TemporalRelationTag:
  case Before, Meets, Overlaps, During, Contains, Starts, Finishes, Equal, Unclear
  case Custom(namespace: String, name: String)

/** One reading of the temporal relation between adjacent units, with its credence. */
final case class TemporalHypothesis(relation: TemporalRelationTag, credence: Credence)

/** Typed interpretation of how story-world time moves between adjacent discourse units (§113).
  *
  * Distinguishes a flashback from a retrospective mention, a backward jump from a return to the
  * main timeline, and intercut simultaneous threads from chronological reversal. Precision is never
  * manufactured: `Unresolved` carries the competing readings.
  */
enum WorldTimeTransition:
  case Continues
  case JumpForward(magnitude: Option[DurationEstimate])
  case JumpBackward(magnitude: Option[DurationEstimate])
  case ReturnFromEarlierFrame
  case SimultaneousThreadSwitch
  case Atemporal
  case Unresolved(alternatives: Vector[TemporalHypothesis])

  def isBackward: Boolean = this match
    case JumpBackward(_) => true
    case _               => false

/** A raw boundary score together with the feature use that produced it.
  *
  * Only [[FeatureUseLedger.scoreBoundary]] can construct one: obtaining a score records the use, so
  * a hierarchy build cannot consume feature evidence without leaving a ledger entry (§116).
  */
final case class BoundaryScore private[features] (
    target: FeatureTarget.Boundary,
    level: Int,
    rawScore: Double,
    use: FeatureUse
)

/** Combines boundary evidence into a raw boundary score under declared per-level weights.
  *
  * Pure and parameterised: weights are inputs (fit offline, leave-story-out), never learned here.
  * The result is a raw score, not a probability; calibration happens elsewhere. Scores are only
  * issued through the ledger (see [[FeatureUseLedger.scoreBoundary]]).
  */
final case class BoundaryBeliefInput(weights: Map[Int, Map[BoundarySignal, Double]]):
  private[features] def rawScoreUnrecorded(evidence: BoundaryEvidence): Option[Double] =
    val w = weights.getOrElse(evidence.level, Map.empty)
    val terms = evidence.observedSignals.toVector.flatMap((k, v) => w.get(k).map(_ * v))
    if terms.isEmpty then None else Some(terms.sum)

  /** Signals that actually contribute: observed, and carrying a nonzero weight at this level. */
  def contributingSignals(evidence: BoundaryEvidence): Set[BoundarySignal] =
    val w = weights.getOrElse(evidence.level, Map.empty)
    evidence.observedSignals.keySet.filter(k => w.getOrElse(k, 0.0) != 0.0)

  /** Spaces that participated: the inputs of every contributing signal. */
  def usedSpaces(evidence: BoundaryEvidence): Set[FeatureSpaceId] =
    contributingSignals(evidence).flatMap(evidence.inputsOf)
