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

/** Why placement mass was excluded from counting, and therefore which rule a later reader has to
  * look at when a published figure moves.
  *
  * Chief's requirement on this bead, stated exactly: "record WHICH threshold each exclusion failed.
  * Six metrics moving with an undifferentiated `excluded` tells a later reader that something
  * changed and not what." An aggregate `excluded` is the same failure this whole line of work is
  * about - a number that records THAT something was removed while erasing WHY, so two different
  * decisions become indistinguishable after the fact.
  */
enum ExclusionCause:
  /** Removed by a scoring policy before counting - a repetition dropped under
    * `RepetitionRule.Ignore`. Not a model outcome: we chose not to count it.
    */
  case RepetitionPolicy

  /** Resolved, but too spread across classes to be a member of any of them: it failed the
    * MEMBERSHIP threshold, not the abstention threshold.
    *
    * This is the case the bead was opened for. A detail resolved at 0.6 and split 0.3/0.3 across
    * two classes is publishable by resolution and belongs to nothing being measured, so a per-class
    * metric for it would publish a measurement that could not be made. It is distinct from
    * `unresolved`, which is the model declining to place mass at all, and folding the two together
    * would report a scoring decision as model abstention.
    */
  case BelowMembership(threshold: Double)

  def render: String = this match
    case RepetitionPolicy   => "repetition-policy"
    case BelowMembership(t) => f"below-membership($t%.4f)"

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
  *   - `excluded` — mass removed before counting (a repetition dropped under
  *     `RepetitionRule.Ignore` is not unresolved, and it is not resolved either), ATTRIBUTED by
  *     [[excludedBy]] to the rule that removed it.
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
    val abstentionThreshold: Double,
    /** Which rule removed each part of [[excluded]]. Sums to `excluded`, and is empty exactly when
      * `excluded` is zero, so the attribution can never be quietly dropped while the mass remains.
      */
    val excludedBy: Map[ExclusionCause, Double]
):
  /** Whether the resolved mass clears the threshold in force, so a caller can abstain on the same
    * rule the summary was built with instead of inventing its own cutoff.
    */
  def clearsThreshold: Boolean = resolved >= abstentionThreshold

  def render: String =
    val causes =
      if excludedBy.isEmpty then ""
      else
        excludedBy.toVector
          // TOTAL ORDER, not just the enum tag. Sorting by ordinal alone is not a total order over
          // ExclusionCause: every BelowMembership shares one ordinal, so two EQUAL resolutions built
          // from different insertion orders rendered their exclusions in different orders. A receipt
          // whose text depends on how a Map was built is not a receipt. Found by
          // codex-storymodel-collab with a reversed-insertion court.
          .sortBy { case (cause, _) =>
            (
              cause.ordinal,
              cause match
                case ExclusionCause.BelowMembership(t) => t
                case ExclusionCause.RepetitionPolicy   => 0.0
            )
          }
          .map { case (cause, mass) => f"${cause.render}=$mass%.4f" }
          .mkString(" [", ", ", "]")
    f"${grain.render}: resolved=$resolved%.4f unresolved=$unresolved%.4f excluded=$excluded%.4f" +
      causes + f" (threshold $abstentionThreshold%.2f)"

  // Written out because this is deliberately NOT a case class: a case class with a private
  // constructor still derives Mirror.ProductOf, whose public fromProduct reconstructs the type
  // field-by-field and walks straight past `of`. Demonstrated on this very type - fromProduct
  // built one with masses summing to 3.0 and a threshold of -5.0.
  override def equals(other: Any): Boolean = other match
    case that: PlacementResolution =>
      grain == that.grain && resolved == that.resolved && unresolved == that.unresolved &&
      excluded == that.excluded && abstentionThreshold == that.abstentionThreshold &&
      // excludedBy BELONGS IN EQUALITY. Omitting it makes two summaries that removed the same mass
      // for DIFFERENT reasons compare equal, which is the indistinguishability this field exists to
      // remove, reintroduced through equals. Caught by the test asserting the two causes stay
      // apart; the hand-written equals here is the cost of not being a case class, and a new field
      // has to be added to it by hand or it is silently invisible to every comparison.
      excludedBy == that.excludedBy
    case _ => false

  override def hashCode: Int =
    (grain, resolved, unresolved, excluded, abstentionThreshold, excludedBy).hashCode

  override def toString: String = s"PlacementResolution(${render})"

object PlacementResolution:
  /** Tolerance on the three-way mass sum `resolved + unresolved + excluded`.
    *
    * '''INHERITED, NOT DERIVED.''' It matches `align`'s `HsmmResult.Tolerance` because that is
    * where the number came from, and it has never been computed against this constructor's own
    * phenomenon. The phenomenon here is small: three float64 additions, so error near `1e-16` —
    * about seven orders below the tolerance. The value is very likely fine and is certainly not
    * tight.
    *
    * Said this way on purpose. A constant that reads as chosen invites a reader to reason about the
    * precision it defends and "correct" it; a constant that says it was inherited tells them the
    * honest thing, which is that nobody has done that work yet. If you tighten it, measure this
    * constructor's inputs rather than copying a bound from `align` a second time.
    *
    * Unlike a bare comparison tolerance, this one ADMITS a value that is then stored — so it
    * carries the absorption obligation: a within-tolerance partition is renormalised before
    * construction so the stored triple sums to exactly 1.
    */
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
      abstentionThreshold: Double = DefaultAbstentionThreshold,
      // Defaulted, but the default cannot lie: `excludedBy` must be empty exactly when `excluded`
      // is zero, so a caller that removes mass and omits the attribution is REFUSED rather than
      // publishing an unexplained exclusion. The default exists only so the many callers with no
      // exclusion at all need not write `Map.empty`.
      excludedBy: Map[ExclusionCause, Double] = Map.empty
  ): Either[DomainError, PlacementResolution] =
    val parts =
      Vector("resolved" -> resolved, "unresolved" -> unresolved, "excluded" -> excluded) ++
        excludedBy.toVector.map { case (cause, mass) => s"excludedBy/${cause.render}" -> mass }
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
          // THE THRESHOLD A CAUSE CARRIES IS A THRESHOLD, and gets the same guard three lines above
          // it. Without this, BelowMembership(NaN), +/-Infinity, -0.1 and 1.1 were all accepted and
          // PUBLISHED as the rule in force - a receipt naming an impossible cutoff, which is worse
          // than naming none, because it reads as measured. Found by codex-storymodel-collab. The
          // asymmetry was mine: I added a field carrying a threshold and validated everything in
          // this constructor except it.
          // Explicit isNaN first: NaN fails every comparison, so a bare range check FAILS OPEN.
          else if excludedBy.keys.exists {
              case ExclusionCause.BelowMembership(t) => t.isNaN || t < 0.0 || t > 1.0
              case ExclusionCause.RepetitionPolicy   => false
            }
          then
            Left(
              DomainError.InvariantViolation(
                "placementResolution/excludedBy/threshold",
                "a membership threshold must be finite and in [0, 1]"
              )
            )
          else if excludedBy.isEmpty != (excluded <= 0.0) then
            Left(
              DomainError.InvariantViolation(
                "placementResolution/excludedBy",
                s"excluded mass $excluded must be attributed to a cause, and only excluded mass may be"
              )
            )
          else if math.abs(excludedBy.values.sum - excluded) > Tolerance + math.ulp(1.0) then
            // The attribution has to ACCOUNT for the exclusion, not merely accompany it. Without
            // this, `excludedBy` could name one cause for a tenth of the mass and stay silent about
            // the rest, which is the undifferentiated `excluded` the ruling rejected, wearing a
            // label.
            Left(
              DomainError.InvariantViolation(
                "placementResolution/excludedBy",
                s"excludedBy sums to ${excludedBy.values.sum}, which does not account for $excluded"
              )
            )
          else
            // Absorb slack so the stored parts are the admitted partition, not the unclamped
            // near-miss. Divide-all-three, not a residual on one slot: 1 - r - u can go
            // slightly negative from rounding and would publish a mass `of` itself refuses.
            // The attribution is scaled by the SAME factor, or it would stop summing to the
            // `excluded` it explains.
            Right(
              new PlacementResolution(
                grain,
                resolved / total,
                unresolved / total,
                excluded / total,
                abstentionThreshold,
                // ABSORBED, not merely scaled. Dividing by `total` preserves an inner mismatch
                // admitted by the tolerance above: excludedBy summing to excluded + Tolerance/2
                // was accepted and then STORED, so the whole-attribution invariant held at the
                // door and was false in the value. Found by codex-storymodel-collab, who measured
                // 0.3000000005 stored against excluded 0.3 and a resulting 1/17 suite failure at
                // the existing eps. The same absorption obligation the three masses carry applies
                // here: renormalise the attribution onto the excluded mass it explains.
                attributed(excludedBy, excluded / total)
              )
            )
        else
          Left(
            DomainError.InvariantViolation(
              "placementResolution/total",
              s"resolved + unresolved + excluded must be 1 within $Tolerance, got $total"
            )
          )

  /** Renormalise an attribution so it sums EXACTLY to the excluded mass it explains.
    *
    * The tolerance admits a near-miss; absorbing it is what stops the admitted slack being
    * published. Empty stays empty, because an exclusion of zero has nothing to attribute.
    */
  private def attributed(
      by: Map[ExclusionCause, Double],
      excluded: Double
  ): Map[ExclusionCause, Double] =
    val sum = by.values.sum
    if by.isEmpty || !(sum > 0.0) then Map.empty
    else by.view.mapValues(m => m * excluded / sum).toMap

  /** Everything placed: the case where a caller genuinely resolved all of the mass.
    *
    * Takes no threshold. A total function that accepted one could construct an invalid public state
    * (NaN, out of range) while bypassing [[of]], which would make the smart constructor a
    * suggestion rather than the only door. A caller wanting a non-default threshold on fully
    * resolved mass goes through `of`, which validates it.
    */
  def complete(grain: PlacementGrain): PlacementResolution =
    new PlacementResolution(grain, 1.0, 0.0, 0.0, DefaultAbstentionThreshold, Map.empty)

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
