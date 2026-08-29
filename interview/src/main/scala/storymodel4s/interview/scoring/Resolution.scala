package storymodel4s.interview.scoring

import storymodel4s.core.DomainError

/** The grain a placement-resolution summary is stated at.
  *
  * A resolution figure is meaningless without it: "we resolved 0.7" is a different claim about one
  * detail, one situation, and a whole interview phase, and the five sites that need this vehicle
  * work at different grains.
  */
enum PlacementGrain:
  case Detail, Situation, Phase

  def render: String = this match
    case Detail    => "detail"
    case Situation => "situation"
    case Phase     => "phase"

/** How much of a placement we actually resolved, stated as mass rather than as a count.
  *
  * `Coverage` answers *did we observe this detail*; it is integer-valued and cannot express partial
  * placement. This answers *how much of its placement did we resolve*, and the two travel together
  * rather than one replacing the other. The distinction is not academic: a pure-`Unresolved` detail
  * yields `episodicDensityPerWord = Observed(0.0)` with `Coverage(1, 1)` — model abstention
  * published as certain absence, at full coverage — because integer coverage has no way to say "we
  * saw it and could not place it".
  *
  * Three masses:
  *   - `resolved` — mass on addresses we placed;
  *   - `unresolved` — mass the model declined to place;
  *   - `excluded` — mass a scoring policy removed before counting (a repetition dropped under
  *     `RepetitionRule.Ignore` is not unresolved, and it is not resolved either).
  *
  * They sum to one. Float slack inside [[PlacementResolution.Tolerance]] is absorbed by scaling so
  * the stored parts are a partition of unity — the decision that admitted them. That is not
  * gap-filling: a 0.4 + 0.0 summary is still refused. Renormalizing `resolved` to fill the gap left
  * by the other two is the specific error this type exists to prevent: it converts "we placed 40%
  * of this" into "we placed all of it", which is a stronger claim than the evidence and always in
  * the flattering direction.
  */
final class PlacementResolution private (
    val grain: PlacementGrain,
    val resolved: Double,
    val unresolved: Double,
    val excluded: Double,
    val abstentionThreshold: Double
):
  /** Whether the resolved mass clears the threshold in force, so a caller can abstain on the same
    * rule the summary was built with instead of inventing its own cutoff.
    */
  def clearsThreshold: Boolean = resolved >= abstentionThreshold

  def render: String =
    f"${grain.render}: resolved=$resolved%.4f unresolved=$unresolved%.4f excluded=$excluded%.4f " +
      f"(threshold $abstentionThreshold%.2f)"

  // Written out because this is deliberately NOT a case class: a case class with a private
  // constructor still derives Mirror.ProductOf, whose public fromProduct reconstructs the type
  // field-by-field and walks straight past `of`. Demonstrated on this very type - fromProduct
  // built one with masses summing to 3.0 and a threshold of -5.0.
  override def equals(other: Any): Boolean = other match
    case that: PlacementResolution =>
      grain == that.grain && resolved == that.resolved && unresolved == that.unresolved &&
      excluded == that.excluded && abstentionThreshold == that.abstentionThreshold
    case _ => false

  override def hashCode: Int =
    (grain, resolved, unresolved, excluded, abstentionThreshold).hashCode

  override def toString: String = s"PlacementResolution(${render})"

object PlacementResolution:
  /** Tolerance on the mass sum, matching the alignment tolerance used elsewhere. */
  val Tolerance: Double = 1e-9

  /** Default threshold below which a conditional quantity should abstain rather than be published.
    *
    * Stated as a named constant rather than inlined at each site so the five consumers cannot drift
    * onto five different cutoffs, and so a change is one edit with one test.
    */
  val DefaultAbstentionThreshold: Double = 0.5

  /** Smart constructor: the three masses must be finite, non-negative, and sum to one.
    *
    * A summary that does not account for all of the mass is not a summary of it.
    */
  def of(
      grain: PlacementGrain,
      resolved: Double,
      unresolved: Double,
      excluded: Double = 0.0,
      abstentionThreshold: Double = DefaultAbstentionThreshold
  ): Either[DomainError, PlacementResolution] =
    val parts = Vector("resolved" -> resolved, "unresolved" -> unresolved, "excluded" -> excluded)
    parts.collectFirst {
      case (n, v) if v.isNaN || v.isInfinite =>
        DomainError.InvariantViolation(s"placementResolution/$n", "mass must be finite")
      case (n, v) if v < 0.0 =>
        DomainError.InvariantViolation(s"placementResolution/$n", "mass must be nonnegative")
    } match
      case Some(e) => Left(e)
      case None    =>
        val total = resolved + unresolved + excluded
        // Fail-closed under NaN: a comparison against NaN is false, so the reject branch holds.
        // `1.0 + Tolerance` is not Tolerance away from 1 in IEEE — the subtraction leaves
        // ~0.4 ulp extra — so the stated boundary is the constructed literal only if the
        // comparison folds that ulp. `1.0 + 2*Tolerance` still misses.
        if math.abs(total - 1.0) <= Tolerance + math.ulp(1.0) then
          if abstentionThreshold.isNaN || abstentionThreshold < 0.0 || abstentionThreshold > 1.0
          then
            Left(
              DomainError
                .InvariantViolation("placementResolution/threshold", "threshold must be in [0, 1]")
            )
          else
            // Absorb slack so the stored parts are the admitted partition, not the unclamped
            // near-miss. Divide-all-three, not a residual on one slot: 1 - r - u can go
            // slightly negative from rounding and would publish a mass `of` itself refuses.
            Right(
              new PlacementResolution(
                grain,
                resolved / total,
                unresolved / total,
                excluded / total,
                abstentionThreshold
              )
            )
        else
          Left(
            DomainError.InvariantViolation(
              "placementResolution/total",
              s"resolved + unresolved + excluded must be 1 within $Tolerance, got $total"
            )
          )

  /** Everything placed: the case where a caller genuinely resolved all of the mass.
    *
    * Takes no threshold. A total function that accepted one could construct an invalid public state
    * (NaN, out of range) while bypassing [[of]], which would make the smart constructor a
    * suggestion rather than the only door. A caller wanting a non-default threshold on fully
    * resolved mass goes through `of`, which validates it.
    */
  def complete(grain: PlacementGrain): PlacementResolution =
    new PlacementResolution(grain, 1.0, 0.0, 0.0, DefaultAbstentionThreshold)

/** A quantity that is conditional on how much placement was resolved, carried with that resolution.
  *
  * There is deliberately no accessor returning the bare value: a conditional ratio that can be
  * quoted without its resolution mass will be, and the number reads as unconditional. To use the
  * value a caller must pattern-match or ask for [[whenResolved]], both of which put the condition
  * in front of them.
  */
final class Conditional[A] private (
    private val underlying: A,
    val resolution: PlacementResolution
):
  /** The value, but only when the resolved mass clears the threshold in force. */
  def whenResolved: Option[A] = if resolution.clearsThreshold then Some(underlying) else None

  /** The value regardless of resolution, for a caller that is explicitly reporting the pair.
    *
    * Named to be unpleasant at a call site that is trying to drop the condition on the floor.
    */
  def valueIgnoringResolution: A = underlying

  def map[B](f: A => B): Conditional[B] = new Conditional(f(underlying), resolution)

  /** Deliberately does not print the payload: a toString that leaks the value would reintroduce the
    * unconditional read through logging.
    */
  override def toString: String = s"Conditional(<conditional on ${resolution.render}>)"

object Conditional:
  def apply[A](value: A, resolution: PlacementResolution): Conditional[A] =
    new Conditional(value, resolution)
