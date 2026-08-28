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
  * `inputSpaces` records the feature spaces that fed the signals so later analyses can detect
  * circularity (§116).
  */
final case class BoundaryEvidence(
    target: FeatureTarget.Boundary,
    signals: Map[BoundarySignal, ScoreEstimate],
    inputSpaces: Set[FeatureSpaceId],
    level: Int,
    claims: Vector[ClaimId] = Vector.empty
):
  def observedSignals: Map[BoundarySignal, Double] =
    signals.collect { case (k, Estimate.Observed(v, _)) => k -> v }

/** Unit of a duration estimate; `Unknown` when the text gives magnitude words without a scale. */
enum DurationUnit:
  case Seconds, Minutes, Hours, Days, Weeks, Months, Years, Unknown

/** Estimated size of a world-time jump; text rarely fixes it, so the magnitude is an estimate. */
final case class DurationEstimate(
    magnitude: ScoreEstimate,
    unit: DurationUnit,
    cue: Option[SpanSet]
)

/** One reading of the temporal relation between adjacent units, with its credence. */
final case class TemporalHypothesis(relation: String, credence: Credence)

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

/** Combines boundary evidence into a raw boundary score under declared per-level weights.
  *
  * Pure and parameterised: weights are inputs (fit offline, leave-story-out), never learned here.
  * The result is a raw score, not a probability; calibration happens elsewhere.
  */
final case class BoundaryBeliefInput(weights: Map[Int, Map[BoundarySignal, Double]]):
  def rawScore(evidence: BoundaryEvidence): Option[Double] =
    val w = weights.getOrElse(evidence.level, Map.empty)
    val terms = evidence.observedSignals.toVector.flatMap((k, v) => w.get(k).map(_ * v))
    if terms.isEmpty then None else Some(terms.sum)

  /** Spaces that participated: the evidence's inputs, restricted to signals with nonzero weight. */
  def usedSpaces(evidence: BoundaryEvidence): Set[FeatureSpaceId] =
    val w = weights.getOrElse(evidence.level, Map.empty)
    if evidence.observedSignals.keys.exists(k => w.getOrElse(k, 0.0) != 0.0) then
      evidence.inputSpaces
    else Set.empty
