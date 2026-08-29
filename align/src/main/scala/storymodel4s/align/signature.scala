package storymodel4s.align

import storymodel4s.features.{Coverage, Estimate, MissingReason, ScoreEstimate, UndefinedReason}
import storymodel4s.recall.{RecallGraph, RecallUnitId}
import storymodel4s.recall.RecallGraphStatus.Checked

/** The decomposed recall signature `m_s` (design record §12). Every component stays separately
  * accessible; a scalar is only ever produced by a declared [[SignatureProjection]].
  *
  *   - coverage counts anchored mass in *either* mode: a distorted anchor is recall of the event.
  *   - `distortedMass` / `distortedMassByFacet`: mean per-unit mass on distorted states, overall
  *     and per contradicted facet — reported separately from omission (uncovered leaves) and from
  *     intrusion (external mass), because they are different phenomena (design record §9).
  *   - `fidelity` and `perUnitFidelity`: facets assessed on the MAP `(anchor, mode)`; the facets of
  *     a distorted mode are wrong by construction.
  *   - `specificity`: mean localizability over units with source mass (K = source node count).
  *   - `backwardMass` / `worldBackwardMass`: mean per-step anchor→anchor mass on moves that go
  *     backward in discourse / story-world order, excluding moves to an ancestor (§12.3).
  *   - `causalPreservation`: fraction of source causal edges among recalled leaves whose endpoints
  *     are covered by two *distinct* recall units linked by a recall causal edge, both mapped at or
  *     below `causalLevelThreshold` (§12.4).
  *   - external masses: mean per-unit mass on each explicit external state.
  */
final case class RecallSignature(
    uniformCoverage: Double,
    importanceWeightedCoverage: WeightedCoverage,
    fidelityMass: MassRatio,
    fidelityByFacet: Map[Facet, MassRatio],
    specificityMass: MassRatio,
    compression: MassRatio,
    discourseChronology: MassRatio,
    worldChronology: MassRatio,
    causalPreservation: MassRatio,
    semanticFlowCoherence: MassRatio,
    associationMass: Double,
    intrusionMass: Double,
    commentaryMass: Double,
    sourceConsistentInferenceMass: Double,
    uninterpretableMass: Double,
    unrankedMass: Double,
    distortedMass: Double,
    distortedMassByFacet: Map[Facet, Double],
    backwardMass: Option[StepMass],
    worldBackwardMass: Option[StepMass],
    perUnitLocalizability: Map[RecallUnitId, Double],
    perUnitFidelity: Map[RecallUnitId, FidelityReport],
    perUnitMode: Map[RecallUnitId, FidelityMode]
):
  /** External mass, with our own failure held apart from the participant's behaviour.
    *
    * Deliberately NOT a `Double`. The five attributed terms are claims about the person — they said
    * something associative, intrusive, commentarial, source-consistently inferred, or
    * uninterpretable. `unrankedMass` is a claim about US: the aligner could not rank the unit at
    * all (see `AlignState.External(Unranked)`). Summing the two into one number lets our inability
    * to align be quoted as evidence that the participant produced content outside the source — and
    * unranked mass is highest for vaguer, sparser recall, so the error runs one way and correlates
    * with exactly the participant properties a study compares.
    *
    * Returning a pair rather than a scalar is the point: a caller cannot quote the external mass
    * without carrying the caveat, because there is no method that hands back the sum.
    */
  /** Which estimand definitions produced these numbers.
    *
    * DERIVED from the code that computed them, never supplied by a caller: a caller-set version is
    * an ungrounded assertion, the same defect rejected for portable sensitivity. It changes when a
    * definition changes, so a figure from before a redefinition cannot be silently compared with
    * one from after - which is the honesty of the number's HISTORY, distinct from the honesty of
    * the number.
    */
  def estimandVersion: String = RecallSignature.EstimandVersion

  def externalMass: ExternalMassReport =
    ExternalMassReport.unsafe(
      attributed = associationMass + intrusionMass + commentaryMass +
        sourceConsistentInferenceMass + uninterpretableMass,
      unranked = unrankedMass
    )

/** Importance-weighted leaf coverage with both kinds of support that make it interpretable.
  *
  * `conditioningWeight` is the observed importance mass used by the weighted mean; `coverage`
  * counts how many eligible leaves actually carried an importance. The weight mass is rendered
  * separately and is never divided by the eligible count: mass and count answer different support
  * questions. Missing all importances and observing only zero weights are also distinct states.
  */
final class WeightedCoverage private (
    val estimate: ScoreEstimate,
    val conditioningWeight: Double,
    val coverage: Coverage
):
  /** Fraction of eligible leaves whose importance was observed. */
  def support: Double = coverage.fraction

  def render: String =
    val value = estimate.toOption.map(v => f"$v%.4f").getOrElse("n/a")
    f"$value (conditioning weight $conditioningWeight%.4f; " +
      s"coverage ${coverage.observed}/${coverage.eligible})"

  override def equals(other: Any): Boolean = other match
    case that: WeightedCoverage =>
      estimate == that.estimate && conditioningWeight == that.conditioningWeight &&
      coverage == that.coverage
    case _ => false

  override def hashCode: Int = (estimate, conditioningWeight, coverage).hashCode
  override def toString: String = s"WeightedCoverage(${render})"

object WeightedCoverage:
  /** Checked construction from a weighted numerator and every observed leaf weight.
    *
    * Individual weights are retained as inputs so a negative value cannot hide behind a positive
    * sum. Zero observed weights means `AllMissing`; observed weights summing to zero means the
    * weighted mean is `Undefined(ZeroTotalWeight)`; otherwise the finite quotient is observed.
    */
  def of(
      numerator: Double,
      observedWeights: Vector[Double],
      eligibleLeaves: Int
  ): Either[AlignError, WeightedCoverage] =
    def malformed(detail: String) =
      Left(AlignError.MalformedRecord("weightedCoverage", detail))
    def finite(value: Double): Boolean = !value.isNaN && !value.isInfinite

    Coverage.of(eligibleLeaves, observedWeights.size) match
      case Left(error)     => malformed(error.message)
      case Right(coverage) =>
        observedWeights.zipWithIndex.collectFirst {
          case (weight, index) if !finite(weight) => s"weight $index is not finite"
          case (weight, index) if weight < 0.0    => s"weight $index is negative"
        } match
          case Some(detail) => malformed(detail)
          case None         =>
            val conditioningWeight = observedWeights.sum
            if !finite(numerator) then malformed("numerator is not finite")
            else if numerator < 0.0 then malformed("numerator is negative")
            else if !finite(conditioningWeight) then malformed("conditioning weight is not finite")
            else if coverage.observed == 0 then
              if numerator != 0.0 || conditioningWeight != 0.0 then
                malformed("no observed leaf may carry a numerator or conditioning weight")
              else
                Right(
                  new WeightedCoverage(
                    Estimate.missing(MissingReason.AllMissing),
                    conditioningWeight,
                    coverage
                  )
                )
            else if conditioningWeight == 0.0 then
              if numerator != 0.0 then malformed("zero total weight cannot carry a numerator")
              else
                Right(
                  new WeightedCoverage(
                    Estimate.missing(MissingReason.Undefined(UndefinedReason.ZeroTotalWeight)),
                    conditioningWeight,
                    coverage
                  )
                )
            else
              val value = numerator / conditioningWeight
              if !finite(value) || value < 0.0 then malformed("weighted outcome is invalid")
              else if value > 1.0 + 1e-9 then
                malformed(
                  s"numerator $numerator exceeds conditioning weight $conditioningWeight"
                )
              else
                // Floating accumulation can put a lawful proportion infinitesimally above one.
                // Apply tolerance to the dimensionless quotient, not to the raw masses: an
                // absolute mass tolerance would admit an arbitrarily large ratio at tiny weights.
                Right(
                  new WeightedCoverage(
                    Estimate.observed(math.min(value, 1.0)),
                    conditioningWeight,
                    coverage
                  )
                )

/** A ratio-of-sums with the support it rests on: value = N / A, support = A / T.
  *
  * The ratified shape for every conditional signature quantity. Two things it makes impossible.
  * First, MEAN-OF-RATIOS: dividing per unit and averaging gives a unit carrying 0.01 of source mass
  * the same vote as a fully placed one, so a single barely-placed unit can move the published
  * figure as much as a confident one. Ratio-of-sums weights each unit by the mass it actually
  * contributed. Second, INVENTION FROM NOTHING: when the conditioning mass A is zero there is no
  * ratio, and `value` is None rather than a 0.0 or 1.0 standing in for it.
  */
final class MassRatio private (
    val value: Option[Double],
    val conditioningMass: Double,
    val totalMass: Double
):
  /** Fraction of the whole that the value rests on; 0 when nothing was eligible. */
  def support: Double = if totalMass <= 0.0 then 0.0 else conditioningMass / totalMass

  def render: String =
    val v = value.map(x => f"$x%.4f").getOrElse("n/a")
    f"$v (support ${support}%.4f = $conditioningMass%.4f/$totalMass%.4f)"

  override def equals(other: Any): Boolean = other match
    case that: MassRatio =>
      value == that.value && conditioningMass == that.conditioningMass &&
      totalMass == that.totalMass
    case _ => false

  override def hashCode: Int = (value, conditioningMass, totalMass).hashCode
  override def toString: String = s"MassRatio(${render})"

object MassRatio:
  private val Tolerance = 1e-9

  /** Trusted construction from inside `align`, where the sums are computed together. */
  private[align] def unsafe(numerator: Double, conditioning: Double, total: Double): MassRatio =
    val v = if conditioning <= 0.0 then None else Some(numerator / conditioning)
    new MassRatio(v, conditioning, total)

  /** Checked construction: masses finite and non-negative, conditioning no larger than the total,
    * and a value present exactly when there was conditioning mass to divide by. Relative overflow
    * within the floating-point tolerance is absorbed before the ratio is stored.
    */
  def of(numerator: Double, conditioning: Double, total: Double): Either[AlignError, MassRatio] =
    def normalizeSubmass(
        part: Double,
        whole: Double,
        detail: => String
    ): Either[AlignError, Double] =
      if whole == 0.0 then
        if part == 0.0 then Right(0.0)
        else Left(AlignError.MalformedRecord("massRatio", detail))
      else
        val fraction = part / whole
        if fraction > 1.0 + Tolerance then Left(AlignError.MalformedRecord("massRatio", detail))
        else Right(math.min(part, whole))

    val bad = Vector("numerator" -> numerator, "conditioning" -> conditioning, "total" -> total)
      .collectFirst {
        case (n, v) if v.isNaN || v.isInfinite || v < 0.0 =>
          AlignError.MalformedRecord("massRatio", s"$n must be finite and nonnegative, got $v")
      }
    bad match
      case Some(e) => Left(e)
      case None    =>
        for
          normalizedConditioning <- normalizeSubmass(
            conditioning,
            total,
            s"conditioning mass $conditioning exceeds the total $total"
          )
          normalizedNumerator <- normalizeSubmass(
            numerator,
            normalizedConditioning,
            s"numerator $numerator exceeds its conditioning mass $normalizedConditioning"
          )
        yield unsafe(normalizedNumerator, normalizedConditioning, total)

/** A per-step route quantity together with the support it rests on.
  *
  * The mean is over EVERY step of the route, not only the steps that carried comparable mass:
  * dropping the others and renormalizing turns "one of three steps moved backward" into "the route
  * moved backward", which is the same manufactured certainty this file exists to remove. The
  * support says how many steps could be compared at all, so a reader can see that a per-step mass
  * of 1/3 rests on one step out of three.
  */
final class StepMass private (
    val perStep: Double,
    val comparableSteps: Int,
    val totalSteps: Int
):
  /** Share of the route's steps that could be judged, named to match [[MassRatio.support]] so the
    * two support carriers answer the same question through the same word. A consumer reporting a
    * clock alongside its support should not have to know which carrier produced it.
    */
  def support: Double = if totalSteps <= 0 then 0.0 else comparableSteps.toDouble / totalSteps

  def render: String = f"$perStep%.4f (over $comparableSteps/$totalSteps comparable steps)"

  override def equals(other: Any): Boolean = other match
    case that: StepMass =>
      perStep == that.perStep && comparableSteps == that.comparableSteps &&
      totalSteps == that.totalSteps
    case _ => false

  override def hashCode: Int = (perStep, comparableSteps, totalSteps).hashCode
  override def toString: String = s"StepMass(${render})"

object StepMass:
  /** Trusted construction from inside `align`, where the computation guarantees the invariants. */
  private[align] def unsafe(perStep: Double, comparableSteps: Int, totalSteps: Int): StepMass =
    new StepMass(perStep, comparableSteps, totalSteps)

  /** Checked construction for anyone outside: a per-step mass must be a finite fraction, and the
    * comparable steps must be a sub-count of the total.
    */
  def of(
      perStep: Double,
      comparableSteps: Int,
      totalSteps: Int
  ): Either[AlignError, StepMass] =
    if perStep.isNaN || perStep.isInfinite || perStep < 0.0 || perStep > 1.0 then
      Left(AlignError.MalformedRecord("stepMass", s"perStep must be a fraction, got $perStep"))
    else if totalSteps < 1 then
      Left(AlignError.MalformedRecord("stepMass", "a route with no steps has no per-step mass"))
    else if comparableSteps < 0 || comparableSteps > totalSteps then
      Left(
        AlignError.MalformedRecord(
          "stepMass",
          s"comparableSteps $comparableSteps is not a sub-count of $totalSteps"
        )
      )
    else Right(new StepMass(perStep, comparableSteps, totalSteps))

/** External mass split into what the participant did and what we could not do.
  *
  * There is no `total`: re-adding the two halves is exactly the conflation this type exists to
  * prevent, and a caller that genuinely wants the sum must write it at the call site where a
  * reviewer can see it.
  */
final class ExternalMassReport private (val attributed: Double, val unranked: Double):
  /** Fraction of mass the aligner was able to rank at all — the coverage of `attributed`. */
  def rankedMass: Double = 1.0 - unranked

  def render: String =
    f"external(attributed)=$attributed%.4f unranked=$unranked%.4f ranked=${rankedMass}%.4f"

  override def equals(other: Any): Boolean = other match
    case that: ExternalMassReport =>
      attributed == that.attributed && unranked == that.unranked
    case _ => false

  override def hashCode: Int = (attributed, unranked).hashCode
  override def toString: String = s"ExternalMassReport(${render})"

object ExternalMassReport:
  private val Tolerance = 1e-9

  /** Trusted construction from inside `align`. */
  private[align] def unsafe(attributed: Double, unranked: Double): ExternalMassReport =
    new ExternalMassReport(attributed, unranked)

  /** Checked construction: both halves are mean masses per unit, so each is a fraction and their
    * sum cannot exceed the whole. A report claiming 1.2 of external mass is not a report. Overflow
    * within the floating-point tolerance is normalized before the partition is stored.
    */
  def of(attributed: Double, unranked: Double): Either[AlignError, ExternalMassReport] =
    val bad = Vector("attributed" -> attributed, "unranked" -> unranked).collectFirst {
      case (n, v) if v.isNaN || v.isInfinite || v < 0.0 || v > 1.0 =>
        AlignError.MalformedRecord("externalMassReport", s"$n must be a fraction, got $v")
    }
    bad match
      case Some(e) => Left(e)
      case None    =>
        val sum = attributed + unranked
        if sum > 1.0 + Tolerance then
          Left(
            AlignError.MalformedRecord(
              "externalMassReport",
              s"attributed + unranked is $sum, more than the whole"
            )
          )
        else if sum > 1.0 then
          // Preserve the split while making the stored partition agree with the tolerance
          // decision. Computing the second part as the complement pins the accepted whole.
          val normalizedAttributed = attributed / sum
          Right(new ExternalMassReport(normalizedAttributed, 1.0 - normalizedAttributed))
        else Right(new ExternalMassReport(attributed, unranked))

object RecallSignature:

  /** Bumped whenever any signature quantity changes what it MEANS rather than what it computes.
    *
    * v2 is the ratio-of-sums rework: compression and semantic-flow coherence became MassRatio with
    * explicit conditioning mass, chronology and per-step backward mass became Option, and external
    * mass split attributed from unranked. A v1 figure and a v2 figure of the same name are not
    * comparable, and this marker is what says so.
    */
  val EstimandVersion: String = "recall-signature/v2"

  /** Computes the supported signature, preserving malformed importance or aggregate overflow as a
    * typed refusal rather than publishing a default or throwing from this pure boundary.
    */
  def compute(
      result: HsmmResult,
      recall: RecallGraph[Checked],
      view: SourceView,
      causalLevelThreshold: Int = 1
  ): Either[AlignError, RecallSignature] =
    val p = result.posterior
    val f = result.flow
    val leaves = view.leaves.map(_.ref)
    val visitation = leafVisitation(p, view)
    val uniform = if leaves.isEmpty then 0.0 else leaves.map(visitation).sum / leaves.size
    // Leaves whose importance is Missing are excluded from the weighted sum (never counted as 0).
    val importance = view.leaves.flatMap(n => n.importance.toOption.map(w => (n.ref, w)))
    val weightedNumerator = importance.map { case (r, w) => w * visitation(r) }.sum
    val weighted = WeightedCoverage.of(weightedNumerator, importance.map(_._2), leaves.size)

    val anchored: Vector[(RecallUnitId, FidelityReport, FidelityMode)] = recall.ordered.flatMap {
      u =>
        for
          row <- p.row(u.id)
          ref <- row.mapSource
          mode <- row.mapMode
          node <- view.node(ref)
          if row.sourceMass > row.externalMass
        yield (u.id, FidelityFacets.assess(u.proposition, node, mode), mode)
    }
    val facets = anchored.map(a => a._1 -> a._2).toMap
    val modes = anchored.map(a => a._1 -> a._3).toMap
    // Fidelity as a mass-weighted ratio of sums over the ANCHOR DISTRIBUTION, not over the MAP of
    // units that cleared a 0.5 cliff. Two defects removed at once: a unit at 0.51 source mass used
    // to contribute its MAP verdict at FULL weight while one at 0.49 contributed nothing, and a
    // unit split 0.51/0.49 across two anchors was scored as though the first were certain.
    //
    // Per facet, N_f is correct-verdict mass and A_f is SPECIFIED-verdict mass: Unspecified is
    // assessment support the recall never committed to, not a wrong answer, so it leaves the
    // denominator rather than counting against the unit.
    val facetSums = scala.collection.mutable.Map.empty[Facet, (Double, Double)]
    var fidelityN = 0.0
    var fidelityA = 0.0
    recall.ordered.foreach { u =>
      p.row(u.id).foreach { row =>
        row.mass.toVector.sortBy(_._1.key).foreach { case (state, m) =>
          if m > 0.0 then
            for
              ref <- state.anchor
              mode <- state.mode
              node <- view.node(ref)
            do
              val report = FidelityFacets.assess(u.proposition, node, mode)
              Facet.values.foreach { f =>
                val (n, a) = facetSums.getOrElse(f, (0.0, 0.0))
                report(f) match
                  case FacetVerdict.Correct     => facetSums.update(f, (n + m, a + m))
                  case FacetVerdict.Wrong       => facetSums.update(f, (n, a + m))
                  case FacetVerdict.Unspecified => ()
              }
              if report.specified > 0 then
                fidelityN += m * report.correct.toDouble / report.specified.toDouble
                fidelityA += m
        }
      }
    }
    val sourceMassTotal = p.rows.map(_.sourceMass).sum
    val fidelityRatio = MassRatio.unsafe(fidelityN, fidelityA, sourceMassTotal)
    val fidelityByFacet: Map[Facet, MassRatio] = facetSums.iterator.map { case (f, (n, a)) =>
      f -> MassRatio.unsafe(n, a, sourceMassTotal)
    }.toMap

    val k = view.sourceNodeCount
    val loc = p.rows.flatMap(r => r.localizability(k).map(r.unit -> _)).toMap
    // Specificity as a ratio of SUMS, per the ruling. Localizability is defined only ON source
    // mass and describes how concentrated that mass is, so the conditioning event is "this unit
    // has source mass" and its natural measure is the mass itself, not the fact of the unit's
    // existence. Mean-of-ratios gave a unit with 0.01 of source mass the same vote as a fully
    // placed one - the same defect compression had nine lines earlier in this function.
    val specificityN = p.rows.map(r => r.localizability(k).fold(0.0)(_ * r.sourceMass)).sum
    val specificityA = p.rows.filter(r => r.localizability(k).isDefined).map(_.sourceMass).sum
    val specificityT = p.rows.map(r => r.mass.values.sum).sum
    val specificityRatio = MassRatio.unsafe(specificityN, specificityA, specificityT)

    // Compression as a ratio of SUMS, per the ratified estimand: N is level mass summed over every
    // unit, A is the source mass those levels were placed on, T is all row mass. Dividing per unit
    // and averaging gave a unit carrying 0.01 of source mass the same vote as a fully placed one,
    // and emitted 0.0 - maximally fine-grained - for a unit with no source mass at all.
    val maxLevel = math.max(1, view.maxLevel)
    val compressionN = p.rows.map { r =>
      (0 to view.maxLevel).map(l => l * r.massAtLevel(view, l)).sum / maxLevel
    }.sum
    val compressionA = p.rows.map(_.sourceMass).sum
    val compressionT = p.rows.map(r => r.mass.values.sum).sum
    val compression = MassRatio.unsafe(compressionN, compressionA, compressionT)

    val totalStepMass = f.steps.map(_.mass.values.sum).sum
    def isBackward(pos: SourceNodeRef => Option[Double])(a: SourceNodeRef, b: SourceNodeRef) =
      a != b && !view.isAncestor(b, a) && ((pos(a), pos(b)) match
        case (Some(x), Some(y)) => y < x
        case _                  => false)
    def isForward(pos: SourceNodeRef => Option[Double])(a: SourceNodeRef, b: SourceNodeRef) =
      a != b && !view.isAncestor(b, a) && !view.isAncestor(a, b) && ((pos(a), pos(b)) match
        case (Some(x), Some(y)) => y > x
        case _                  => false)

    /** Forward share of the directionally comparable route mass, with that comparability beside it.
      *
      * The ratio was already a ratio of sums, so the arithmetic is unchanged; what was missing is
      * T. A chronology of 1.0 computed on 2% of the route mass and one computed on 90% published
      * the same number, and the first is a claim about almost nothing. Only ORDERED pairs can be
      * forward or backward at all - a step onto an ancestor, or onto a node with no position, is
      * not disordered, it is unjudgeable - so the comparable mass is the conditioning event and the
      * rest of the route is the coverage it is missing.
      */
    def ordered(pos: SourceNodeRef => Option[Double]): MassRatio =
      val fw = f.steps.map(_.sourceMass(isForward(pos))).sum
      val bw = f.steps.map(_.sourceMass(isBackward(pos))).sum
      MassRatio.unsafe(fw, fw + bw, totalStepMass)

    /** Mean per-step backward mass over EVERY step, with the comparable-step support beside it.
      *
      * `None` only when the route has no steps at all — dividing by `max(1, 0)` used to report 0.0,
      * "this person never moved backwards", when we never saw a move to judge. A step that carried
      * no source-to-source mass is still a step of the route and stays in the denominator;
      * excluding it would renormalize the residue into a stronger claim than the evidence.
      */
    def backwardMean(pos: SourceNodeRef => Option[Double]): Option[StepMass] =
      if f.steps.isEmpty then None
      else
        val comparable = f.steps.count(st =>
          st.sourceMass(isBackward(pos)) > 0.0 || st.sourceMass(isForward(pos)) > 0.0
        )
        val mean = f.steps.map(_.sourceMass(isBackward(pos))).sum / f.steps.size
        Some(StepMass.unsafe(mean, comparable, f.steps.size))
    // Was `r => Some(view.relativePosition(r))`: an Option whose None was structurally
    // unreachable, because relativePosition substitutes 0.0 for an unresolvable ref. That
    // published the START OF THE DISCOURSE as the measured position of a node we could not place,
    // and fed it to chronology. `worldPos` on the line below always used its absence channel
    // correctly; this one was hardcoded shut beside it.
    val discoursePos: SourceNodeRef => Option[Double] = view.measuredPosition
    val worldPos: Option[SourceNodeRef => Option[Double]] =
      view.worldOrder.map(o => r => o.get(r).map(_.toDouble))
    // `ordered` already abstains when no step carries directional mass; the previous
    // `.getOrElse(1.0)` threw that away and published PERFECT forward chronology for a recall we
    // could not place at all. The honest value is None.
    val discourse = ordered(discoursePos)
    // A view with NO world order and a route with no judgeable steps are different failures, and
    // the total is what separates them: 0 of 0 says the source has no world chronology to violate,
    // 0 of totalStepMass says it has one and this route told us nothing about it. Collapsing them
    // is the same conflation causalPreservation had, one field earlier.
    val world = worldPos.map(ordered).getOrElse(MassRatio.unsafe(0.0, 0.0, 0.0))
    val backward = backwardMean(discoursePos)
    val worldBackward = worldPos.flatMap(backwardMean)

    val causalEdges = view.adjacency(RelationLayer.Causal).toVector.sortBy(_._1.key).flatMap {
      case (a, m) => m.toVector.sortBy(_._1.key).collect { case (b, w) if w > 0 => (a, b) }
    }
    val recalledCausal = causalEdges.filter { case (a, b) =>
      visitation.getOrElse(a, 0.0) > 0.5 && visitation.getOrElse(b, 0.0) > 0.5
    }
    val unitMap: Map[RecallUnitId, SourceNodeRef] = p.rows.flatMap { r =>
      r.mapSource
        .filter(_ => r.sourceMass > r.externalMass)
        .filter(ref => view.node(ref).exists(_.level <= causalLevelThreshold))
        .map(r.unit -> _)
    }.toMap
    def covers(ref: SourceNodeRef, leaf: SourceNodeRef): Boolean =
      ref == leaf || view.leavesUnder(ref).contains(leaf)
    val preserved = recalledCausal.count { case (a, b) =>
      recall.relations.causal.exists { e =>
        e.cause != e.effect && {
          (unitMap.get(e.cause), unitMap.get(e.effect)) match
            case (Some(ca), Some(ef)) => ca != ef && covers(ca, a) && covers(ef, b)
            case _                    => false
        }
      }
    }
    // Causal preservation as a ratio of sums, per ADR 0003. The conditioning event is "this source
    // causal edge was recalled at both endpoints", and its measure is a COUNT here rather than a
    // mass - deliberately, and not by inattention to the specificity ruling. Localizability is
    // defined on source mass and describes how concentrated that mass is, so summing mass was the
    // only coherent denominator there. A causal edge is a discrete object in the source graph: it
    // is recalled or it is not, and there is no partial edge for a mass to measure.
    //
    // The support is what the bare ratio never said: preserving 2 of 2 recalled edges out of 40 in
    // the source is not the same finding as preserving 38 of 38 out of 40, and both published 1.0.
    val causalRatio =
      MassRatio.unsafe(preserved.toDouble, recalledCausal.size.toDouble, causalEdges.size.toDouble)

    // Coherence as a ratio of SUMS: N is coherent source-to-source mass summed over steps, A is all
    // source-to-source mass, T is every step's mass. A step with no source-to-source mass used to
    // score 1.0 - PERFECT coherence for a step we could not evaluate - and an empty flow scored 1.0
    // for a route with no steps at all.
    def coherentMass(s: FlowStep): Double =
      s.sourceMass((a, b) =>
        a == b || view.hasEdge(RelationLayer.DiscourseSuccession, a, b) ||
          view.hasEdge(RelationLayer.Causal, a, b) || view.hasEdge(RelationLayer.Causal, b, a) ||
          view.isAncestor(a, b) || view.isAncestor(b, a) ||
          view.weight(RelationLayer.Semantic, a, b) > 0 || view.weight(
            RelationLayer.Semantic,
            b,
            a
          ) > 0
      )
    val coherenceN = f.steps.map(coherentMass).sum
    val coherenceA = f.steps.map(_.sourceToSourceMass).sum
    val coherenceT = f.steps.map(_.mass.values.sum).sum
    val semanticFlow = MassRatio.unsafe(coherenceN, coherenceA, coherenceT)

    val n = math.max(1, p.rows.size)
    def extMean(state: ExternalState): Double =
      if p.rows.isEmpty then 0.0 else p.rows.map(_.externalMass(state)).sum / n
    val distorted = if p.rows.isEmpty then 0.0 else p.rows.map(_.distortedMass).sum / n
    val byFacet = Facet.values.toVector
      .map(fc => fc -> (if p.rows.isEmpty then 0.0 else p.rows.map(_.distortedMass(fc)).sum / n))
      .filter(_._2 > 0.0)
      .toMap

    weighted.map { importanceWeightedCoverage =>
      RecallSignature(
        uniform,
        importanceWeightedCoverage,
        fidelityRatio,
        fidelityByFacet,
        specificityRatio,
        compression,
        discourse,
        world,
        causalRatio,
        semanticFlow,
        extMean(ExternalState.Association),
        extMean(ExternalState.Intrusion),
        extMean(ExternalState.Commentary),
        extMean(ExternalState.SourceConsistentInference),
        extMean(ExternalState.Uninterpretable),
        extMean(ExternalState.Unranked),
        distorted,
        byFacet,
        backward,
        worldBackward,
        loc,
        facets,
        modes
      )
    }

  /** Visitation per leaf, counting mass placed on ancestors as spread over their leaves; anchors of
    * either mode count.
    */
  def leafVisitation(p: AlignmentMatrix, view: SourceView): Map[SourceNodeRef, Double] =
    val acc = scala.collection.mutable.Map.empty[SourceNodeRef, Double].withDefaultValue(0.0)
    p.rows.foreach { row =>
      row.anchorMass.toVector.sortBy(_._1.key).foreach { case (ref, m) =>
        if m > 0 then
          val ls = view.leavesUnder(ref)
          if ls.nonEmpty then ls.foreach(l => acc.update(l, acc(l) + m / ls.size))
      }
    }
    view.leaves.map(n => n.ref -> (1.0 - math.exp(-acc(n.ref)))).toMap

/** A scalar projection with the support behind it.
  *
  * NO VALUE and LOW SUPPORT are different failures. A weighted component with no value refuses -
  * dropping it would silently compute a different linear functional under the same name and
  * weights. A component with a value but thin support contributes, and the scalar publishes the
  * WEAKEST support among the components that carry one, named: a chain is no better supported than
  * its thinnest link, and naming the link is what makes it actionable.
  */
final class SupportedScalar private (
    val value: Double,
    val weakestSupport: Option[Double],
    val weakestComponent: Option[String],
    /** Weighted components that carry no support notion at all (ADR 0003 field backlog). */
    val unsupportedComponents: Vector[String]
):
  def render: String =
    val s = weakestSupport
      .map(x => f"support $x%.4f set by ${weakestComponent.getOrElse("?")}")
      .getOrElse("no component carries support")
    val u =
      if unsupportedComponents.isEmpty then ""
      else s"; ${unsupportedComponents.size} weighted components carry no support"
    f"$value%.4f ($s$u)"

  override def equals(other: Any): Boolean = other match
    case that: SupportedScalar =>
      value == that.value && weakestSupport == that.weakestSupport &&
      weakestComponent == that.weakestComponent &&
      unsupportedComponents == that.unsupportedComponents
    case _ => false

  override def hashCode: Int =
    (value, weakestSupport, weakestComponent, unsupportedComponents).hashCode

  override def toString: String = s"SupportedScalar(${render})"

object SupportedScalar:
  private[align] def unsafe(
      value: Double,
      weakestSupport: Option[Double],
      weakestComponent: Option[String],
      unsupportedComponents: Vector[String]
  ): SupportedScalar =
    new SupportedScalar(value, weakestSupport, weakestComponent, unsupportedComponents)

/** Why a scalar projection could not be produced. Both cases used to be silent zeros. */
enum ProjectionError:
  /** A weight names a component that does not exist — a typo used to delete a term. */
  case UnknownComponent(name: String)

  /** A weighted component has no measurement; the projection abstains rather than inventing one. */
  case MissingComponent(name: String)

  /** A weight set that cannot define a projection: empty, or not finite. */
  case InvalidWeights(detail: String)

  def message: String = this match
    case UnknownComponent(n) => s"projection weight names no such signature component: $n"
    case MissingComponent(n) => s"projection weights $n, which this signature did not measure"
    case InvalidWeights(d)   => s"projection weights are not a usable set: $d"

/** A declared scalar projection of the signature: explicit, versioned weights.
  *
  * Returns `Either` because both failure modes are real and were previously invisible: a weight
  * naming a component that does not exist silently dropped the term (a typo cost you a whole
  * dimension of the score), and a component with no measurement was substituted with 0.0, so "we
  * did not measure this" became "this scored worst" — or, under a negative weight, best.
  */
final class SignatureProjection private (
    val version: String,
    val weights: Map[String, Double]
):
  // Not a case class: a private constructor does not suppress the derived Mirror, whose public
  // fromProduct would rebuild a projection from unvalidated weights.
  override def equals(other: Any): Boolean = other match
    case that: SignatureProjection => version == that.version && weights == that.weights
    case _                         => false

  override def hashCode: Int = (version, weights).hashCode

  override def toString: String = s"SignatureProjection($version, ${weights.size} weights)"

  def apply(s: RecallSignature): Either[ProjectionError, SupportedScalar] =
    val comps: Map[String, Option[Double]] = Map(
      "uniformCoverage" -> Some(s.uniformCoverage),
      "importanceWeightedCoverage" -> s.importanceWeightedCoverage.estimate.toOption,
      "fidelity" -> s.fidelityMass.value,
      "specificity" -> s.specificityMass.value,
      "compression" -> s.compression.value,
      "discourseChronology" -> s.discourseChronology.value,
      "worldChronology" -> s.worldChronology.value,
      "causalPreservation" -> s.causalPreservation.value,
      "semanticFlowCoherence" -> s.semanticFlowCoherence.value,
      "associationMass" -> Some(s.associationMass),
      "intrusionMass" -> Some(s.intrusionMass),
      "commentaryMass" -> Some(s.commentaryMass),
      "sourceConsistentInferenceMass" -> Some(s.sourceConsistentInferenceMass),
      "uninterpretableMass" -> Some(s.uninterpretableMass),
      "unrankedMass" -> Some(s.unrankedMass),
      "distortedMass" -> Some(s.distortedMass),
      "backwardMass" -> s.backwardMass.map(_.perStep),
      "worldBackwardMass" -> s.worldBackwardMass.map(_.perStep)
    )
    val terms = weights.toVector.sortBy(_._1).map { case (k, w) =>
      comps.get(k) match
        case None          => Left(ProjectionError.UnknownComponent(k))
        case Some(None)    => Left(ProjectionError.MissingComponent(k))
        case Some(Some(v)) => Right(w * v)
    }
    // Support, per component, for those that carry one at all. The rest are named so the reader
    // sees what the minimum does NOT cover rather than reading it as a guarantee.
    val support: Map[String, Double] = Map(
      "fidelity" -> s.fidelityMass.support,
      "specificity" -> s.specificityMass.support,
      "causalPreservation" -> s.causalPreservation.support,
      "discourseChronology" -> s.discourseChronology.support,
      "worldChronology" -> s.worldChronology.support,
      "compression" -> s.compression.support,
      "semanticFlowCoherence" -> s.semanticFlowCoherence.support,
      "importanceWeightedCoverage" -> s.importanceWeightedCoverage.support
    ) ++ s.backwardMass.map(m => "backwardMass" -> m.comparableSteps.toDouble / m.totalSteps).toMap
      ++ s.worldBackwardMass
        .map(m => "worldBackwardMass" -> m.comparableSteps.toDouble / m.totalSteps)
        .toMap

    terms.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None    =>
        val weighted = weights.keys.toVector.sorted
        val supported = weighted.flatMap(k => support.get(k).map(k -> _))
        val weakest = supported.minByOption(_._2)
        Right(
          SupportedScalar.unsafe(
            terms.collect { case Right(v) => v }.sum,
            weakest.map(_._2),
            weakest.map(_._1),
            weighted.filterNot(support.contains)
          )
        )

object SignatureProjection:
  /** The known component names, so a weight set can be checked before it is ever applied. */
  val components: Set[String] = Set(
    "uniformCoverage",
    "importanceWeightedCoverage",
    "fidelity",
    "specificity",
    "compression",
    "discourseChronology",
    "worldChronology",
    "causalPreservation",
    "semanticFlowCoherence",
    "associationMass",
    "intrusionMass",
    "commentaryMass",
    "sourceConsistentInferenceMass",
    "uninterpretableMass",
    "unrankedMass",
    "distortedMass",
    "backwardMass",
    "worldBackwardMass"
  )

  /** Smart constructor: a projection with no weights, or a non-finite weight, is not a projection.
    *
    * Empty weights used to produce `Right(0.0)` — a scalar summary of a signature computed from
    * nothing — and a NaN or infinite weight produced a NaN or infinite score that would propagate
    * silently through any aggregate built on it.
    */
  def of(
      version: String,
      weights: Map[String, Double]
  ): Either[ProjectionError, SignatureProjection] =
    if version.trim.isEmpty then Left(ProjectionError.InvalidWeights("version must be non-empty"))
    else if weights.isEmpty then
      Left(ProjectionError.InvalidWeights("a projection needs at least one weighted component"))
    else
      weights.toVector.sortBy(_._1).collectFirst {
        case (k, w) if w.isNaN || w.isInfinite =>
          ProjectionError.InvalidWeights(s"weight for $k is not finite")
        case (k, _) if !components.contains(k) => ProjectionError.UnknownComponent(k)
      } match
        case Some(e) => Left(e)
        case None    => Right(new SignatureProjection(version, weights))
