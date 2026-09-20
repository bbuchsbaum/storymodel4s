package storymodel4s.bench

import storymodel4s.align.*
import storymodel4s.core.{Checksum, ContentAddress}
import storymodel4s.features.{Coverage, Estimate, MissingReason, UndefinedReason}
import storymodel4s.recall.{RecallUnit, RecallUnitId}

/** A percentile bootstrap interval over story-macro means. */
final case class Interval(lower: Double, upper: Double, resamples: Int)

/** One reported number: an [[Estimate]] with the coverage of the units it was computed over, a
  * story-macro confidence interval when more than one story contributed, and a receipt naming the
  * inputs. Never a bare `Double` (vision: every number stays examinable).
  */
final case class MetricValue(
    name: String,
    value: Estimate[Double],
    coverage: Coverage,
    stories: Int,
    interval: Option[Interval],
    receipt: Checksum
):
  def render: String =
    val v = value match
      case Estimate.Observed(x, _) => f"$x%.4f"
      case Estimate.Missing(r)     => s"missing(${r.toString})"
    val ci =
      interval.map(i => f" [${i.lower}%.4f, ${i.upper}%.4f] (B=${i.resamples})").getOrElse("")
    s"$name = $v cov=${coverage.observed}/${coverage.eligible} stories=$stories$ci receipt=${receipt.short()}"

/** Whether one unit was outside a metric's estimand, measured, or eligible but unmeasurable.
  *
  * Why three cases: `Ineligible` is not missing evidence, while `Missing` is. Collapsing both to
  * `None` lets aggregation silently drop an aligner abstention and renormalize accuracy over the
  * surviving units.
  */
enum MetricObservation:
  case Ineligible
  private[bench] case Observed(value: Double)
  case Missing(reason: MissingReason)

  /** Eliminate an observation without discarding eligibility, value, or missingness.
    *
    * The observed constructor stays package-private so non-finite values cannot bypass
    * [[MetricObservation.observed]], while this total public operation lets consumers inspect the
    * finite value without depending on that constructor.
    */
  def fold[A](
      onIneligible: => A,
      onObserved: Double => A,
      onMissing: MissingReason => A
  ): A = this match
    case Ineligible      => onIneligible
    case Observed(value) => onObserved(value)
    case Missing(reason) => onMissing(reason)

object MetricObservation:
  /** A finite observation, or a typed absence when arithmetic produced no real score. */
  def observed(value: Double): MetricObservation =
    if value.isNaN || value.isInfinite then
      Missing(MissingReason.Undefined(UndefinedReason.NotFinite))
    else Observed(value)

  /** Lift the ordinary optional scoring path: absence means the metric does not apply. */
  def fromOption(value: Option[Double]): MetricObservation =
    value.fold(Ineligible)(observed)

/** A per-unit metric outcome with eligibility and missingness kept distinct. */
final case class UnitObservation(unit: RecallUnitId, observation: MetricObservation)

/** Per-case, per-metric raw observations, kept so aggregation is a pure function of them. */
final case class CaseObservations(caseId: String, byMetric: Map[String, Vector[UnitObservation]])

/** The scoring rules of the spike spec, applied to one proven [[HsmmResult]] against its gold.
  *
  * Source-anchor metrics are computed only over source-anchored gold units; open-world metrics only
  * over units whose groundedness is not `Source`; the two families are never pooled. Rank-based
  * metrics rank anchored states only (external states are destinations, not candidates), so an
  * external MAP with a target in second place is recall@1 = 0, MRR = 1/1 among anchors.
  */
object Metrics:
  val Ks: Vector[Int] = Vector(1, 3, 5, 10)

  object Names:
    def strictRecall(k: Int): String = s"strict-recall@$k"
    def ancestorCredit(k: Int): String = s"ancestor-credit@$k"
    val mrr = "mrr"
    val levelExact = "level-exact"
    val summaryAccuracy = "summary-accuracy"
    val blendCoverage = "blend-coverage@5"
    val candidateBurden = "candidate-burden"
    val falseGating = "false-gating"
    val distortionDetection = "distortion-anchor-detection@5"
    val facetCorrectness = "distortion-facets-correct"
    val externalRule = "open-world:external-over-source"
    val externalSubtype = "open-world:subtype-correct"
    val inferenceMass = "open-world:source-consistent-inference-mass"
    val structuralTermCoverage = "cost:structural-term-coverage"
    val semanticTermCoverage = "cost:semantic-term-coverage"
    val routeSupportMidpointDirection = "route:support-midpoint-direction"
    val routeRevisitPairRecall = "route:revisit-pair-recall"
    val routeRevisitPairPrecision = "route:revisit-pair-precision"
    val routeGoldLevelCloseness = "route:gold-level-closeness"
    val routeGoldLevelSignedBias = "route:gold-level-signed-bias"
    val routeTransitionDisplacementCloseness = "route:transition-displacement-closeness"
    val all: Vector[String] =
      Ks.map(strictRecall) ++ Ks.map(ancestorCredit) ++ Vector(
        mrr,
        levelExact,
        summaryAccuracy,
        blendCoverage,
        candidateBurden,
        falseGating,
        distortionDetection,
        facetCorrectness,
        externalRule,
        externalSubtype,
        inferenceMass,
        structuralTermCoverage,
        semanticTermCoverage,
        routeSupportMidpointDirection,
        routeRevisitPairRecall,
        routeRevisitPairPrecision,
        routeGoldLevelCloseness,
        routeGoldLevelSignedBias,
        routeTransitionDisplacementCloseness
      )
    val openWorld: Set[String] = Set(externalRule, externalSubtype, inferenceMass)

  /** Rank of the first target in an anchored ranking, as a reciprocal; 0 when absent.
    *
    * Extracted as a pure function because the arithmetic is a claim in its own right: an off-by-one
    * here silently rescales every MRR the bench has ever reported, and no aggregation test can see
    * it.
    */
  private[bench] def reciprocalRank(
      ranking: Vector[SourceNodeRef],
      targets: Set[SourceNodeRef]
  ): Double =
    val idx = ranking.indexWhere(targets.contains)
    if idx < 0 then 0.0 else 1.0 / (idx + 1)

  /** Did the gate refuse an anchor the gold says is faithful?
    *
    * `None` when the gate never considered the anchor at all — an anchor that was never nominated
    * was not refused by the gate, it was missed by the candidate generator, which is what
    * `strict-recall@k` and `candidate-burden` measure. Scoring that as 0.0 would report the gate as
    * well-behaved precisely when it was never exercised, and a channel that nominated nothing would
    * post a perfect false-gating score.
    */
  private[bench] def falseGate(admissibility: Option[Admissibility]): Option[Double] =
    admissibility.map(a => if a.gated then 1.0 else 0.0)

  /** Which way a recall step moved through the source: forward, backward, or staying put.
    *
    * The vision asks whether recall followed presentation order, story-world time, or a route with
    * jumps and reversals. Every other metric here scores WHERE a single unit landed; this is the
    * only one that scores the STEP BETWEEN two units, which is where a route lives.
    */
  private[bench] def stepDirection(from: Double, to: Double): Int =
    math.signum(to - from).toInt

  /** Exact recurrence pairs, linked to the most recent earlier occurrence of the same anchor.
    * Adjacent equal anchors are dwell, not revisit.
    */
  private[bench] def recurrencePairs(
      anchors: Vector[Option[SourceNodeRef]]
  ): Vector[(Int, Int)] =
    val lastSeen = scala.collection.mutable.Map.empty[SourceNodeRef, Int]
    anchors.zipWithIndex.flatMap { case (anchor, current) =>
      anchor.toVector.flatMap { ref =>
        val pair = lastSeen.get(ref).filter(previous => current > previous + 1).map(_ -> current)
        lastSeen.update(ref, current)
        pair
      }
    }

  /** Strict recall at k: is any target inside the first k of the anchored ranking. */
  private[bench] def recallAt(
      ranking: Vector[SourceNodeRef],
      targets: Set[SourceNodeRef],
      k: Int
  ): Double =
    if ranking.take(k).exists(targets.contains) then 1.0 else 0.0

  def observe(c: BenchCase, result: HsmmResult): CaseObservations =
    val view = c.view
    val units = c.recall.ordered
    def typedObs(
        name: String
    )(f: (AlignmentRow, GoldUnit) => MetricObservation): (String, Vector[UnitObservation]) =
      name -> units.map { u =>
        val observation = (c.gold(u.id), result.posterior.row(u.id)) match
          case (Some(g), Some(row)) => f(row, g)
          case _                    => MetricObservation.Ineligible
        UnitObservation(u.id, observation)
      }
    def obs(
        name: String
    )(f: (AlignmentRow, GoldUnit) => Option[Double]): (String, Vector[UnitObservation]) =
      typedObs(name)((row, gold) => MetricObservation.fromOption(f(row, gold)))
    def ranked(row: AlignmentRow)(value: => Double): MetricObservation =
      if row.externalMass(ExternalState.Unranked) > 0.0 then
        MetricObservation.Missing(MissingReason.ProviderAbstained)
      else MetricObservation.observed(value)
    def anchoredRanking(row: AlignmentRow): Vector[SourceNodeRef] =
      row.mass.toVector
        .collect { case (s, m) if s.isSource && m > 0.0 => (s, m) }
        .sortBy { case (s, m) => (-m, s.key) }
        .flatMap(_._1.anchor)
        .distinct
    def hit(g: GoldUnit, refs: Vector[SourceNodeRef]): Boolean =
      refs.exists(g.targetNodes.contains)
    def ancestorHit(g: GoldUnit, refs: Vector[SourceNodeRef]): Boolean =
      refs.exists(r => g.targets.exists(t => view.isAncestor(r, t.node)))
    def ind(b: Boolean): Double = if b then 1.0 else 0.0
    def sourceOnly(g: GoldUnit)(f: => Option[Double]): Option[Double] =
      if g.isSourceAnchored then f else None
    def mapAnchor(row: AlignmentRow): Option[SourceNodeRef] = anchoredRanking(row).headOption
    def levelOf(ref: SourceNodeRef): Option[Int] = view.node(ref).map(_.level)
    def hasUnranked(row: AlignmentRow): Boolean =
      row.externalMass(ExternalState.Unranked) > 0.0
    def unavailable(row: AlignmentRow): MetricObservation =
      if hasUnranked(row) then MetricObservation.Missing(MissingReason.ProviderAbstained)
      else MetricObservation.Missing(MissingReason.AllMissing)

    val recallAtK = Ks.map { k =>
      obs(Names.strictRecall(k)) { (row, g) =>
        sourceOnly(g)(Some(recallAt(anchoredRanking(row), g.targetNodes.toSet, k)))
      }
    }
    val ancestorAtK = Ks.map { k =>
      obs(Names.ancestorCredit(k)) { (row, g) =>
        sourceOnly(g) {
          val top = anchoredRanking(row).take(k)
          Some(ind(!hit(g, top) && ancestorHit(g, top)))
        }
      }
    }
    val mrr = obs(Names.mrr) { (row, g) =>
      sourceOnly(g)(Some(reciprocalRank(anchoredRanking(row), g.targetNodes.toSet)))
    }
    val levelExact = obs(Names.levelExact) { (row, g) =>
      sourceOnly(g) {
        Some(ind(g.primary.exists(p => mapAnchor(row).flatMap(levelOf).contains(p.level))))
      }
    }
    val summaryAccuracy = obs(Names.summaryAccuracy) { (row, g) =>
      g.primary.filter(_.level > 0).map(_ => ind(mapAnchor(row).flatMap(levelOf).exists(_ > 0)))
    }
    val blend = obs(Names.blendCoverage) { (row, g) =>
      if g.blend && g.targets.size >= 2 then
        val top = anchoredRanking(row).take(5).toSet
        Some(ind(g.targets.forall(t => top.contains(t.node))))
      else None
    }
    val burden = obs(Names.candidateBurden) { (row, _) =>
      Some(result.candidateAnchors.getOrElse(row.unit, Vector.empty).size.toDouble)
    }
    val falseGating = obs(Names.falseGating) { (row, g) =>
      if g.isFaithful then
        g.primary.flatMap(p => falseGate(result.admissibility.get(row.unit).flatMap(_.get(p.node))))
      else None
    }
    val detection = obs(Names.distortionDetection) { (row, g) =>
      if g.isDistorted then Some(ind(hit(g, anchoredRanking(row).take(5)))) else None
    }
    val facets = obs(Names.facetCorrectness) { (row, g) =>
      if g.isDistorted && hit(g, anchoredRanking(row).take(5)) then
        val onTargets = row.mass.toVector.collect {
          case (AlignState.Distorted(ref, fs), m) if g.targetNodes.contains(ref) && m > 0.0 =>
            (fs.toSortedSet.toSet, m)
        }
        val best = onTargets.sortBy { case (fs, m) =>
          (-m, fs.map(_.toString).toVector.sorted.mkString)
        }.headOption
        Some(ind(best.exists(_._1 == g.facets)))
      else None
    }
    val externalRule = typedObs(Names.externalRule) { (row, g) =>
      g.groundedness match
        case Groundedness.Association | Groundedness.Intrusion | Groundedness.Uninterpretable =>
          ranked(row)(ind(row.externalMass > row.sourceMass))
        case _ => MetricObservation.Ineligible
    }
    val externalSubtype = typedObs(Names.externalSubtype) { (row, g) =>
      g.externalSubtype match
        case Some(expected) =>
          ranked(row)(ind(row.argmax.contains(AlignState.External(expected))))
        case None => MetricObservation.Ineligible
    }
    val inference = typedObs(Names.inferenceMass) { (row, g) =>
      if g.groundedness == Groundedness.Inference then
        ranked(row)(row.externalMass(ExternalState.SourceConsistentInference))
      else MetricObservation.Ineligible
    }

    def hierarchyMetric(name: String)(score: (Int, Int, Int) => Double) =
      typedObs(name) { (row, g) =>
        if view.maxLevel <= 0 then MetricObservation.Ineligible
        else
          g.primary match
            case None                        => MetricObservation.Ineligible
            case Some(_) if hasUnranked(row) =>
              MetricObservation.Missing(MissingReason.ProviderAbstained)
            case Some(gold) =>
              mapAnchor(row).flatMap(levelOf) match
                case Some(inferredLevel) =>
                  MetricObservation.observed(score(inferredLevel, gold.level, view.maxLevel))
                case None => unavailable(row)
      }

    val goldLevelCloseness = hierarchyMetric(Names.routeGoldLevelCloseness) {
      (inferred, gold, maxLevel) =>
        1.0 - math.abs(inferred - gold).toDouble / maxLevel
    }
    val goldLevelSignedBias = hierarchyMetric(Names.routeGoldLevelSignedBias) {
      (inferred, gold, maxLevel) =>
        (inferred - gold).toDouble / maxLevel
    }
    def termCoverage(term: CostTerm): (AlignmentRow, GoldUnit) => Option[Double] = (row, _) =>
      val breakdowns = result.costs.getOrElse(row.unit, Map.empty).toVector.collect {
        case (s, b) if s.isSource => b
      }
      if breakdowns.isEmpty then None
      else Some(breakdowns.count(_.has(term)).toDouble / breakdowns.size)

    /** Support-midpoint route agreement, recorded on the later unit of each adjacent pair.
      *
      * For each step, compare the direction the GOLD route took through the source with the
      * direction the inferred MAP anchors took on the level-independent source-support midpoint
      * axis. A channel can place every unit on a plausible anchor and still reconstruct the wrong
      * journey; nothing else in this suite would notice, because every other metric scores units
      * independently. Steps where either side is not source-anchored are `None` — an external
      * destination is a legitimate route, but it is not a step through the source, and scoring it
      * as agreement or disagreement would be an invention.
      */
    val route: (String, Vector[UnitObservation]) =
      def goldAnchor(u: RecallUnit): Option[SourceNodeRef] =
        c.gold(u.id).flatMap(_.primary).map(_.node)
      val values = units.zipWithIndex.map { case (u, i) =>
        val observation =
          if i == 0 then MetricObservation.Ineligible
          else
            val prev = units(i - 1)
            (goldAnchor(prev), goldAnchor(u)) match
              case (Some(goldPrevious), Some(goldCurrent)) =>
                (result.posterior.row(prev.id), result.posterior.row(u.id)) match
                  case (Some(previousRow), Some(currentRow))
                      if previousRow.externalMass(ExternalState.Unranked) > 0.0 ||
                        currentRow.externalMass(ExternalState.Unranked) > 0.0 =>
                    MetricObservation.Missing(MissingReason.ProviderAbstained)
                  case (Some(previousRow), Some(currentRow)) =>
                    (mapAnchor(previousRow), mapAnchor(currentRow)) match
                      case (Some(previous), Some(current)) =>
                        (view.measuredPosition(goldPrevious), view.measuredPosition(goldCurrent),
                          view.measuredPosition(previous), view.measuredPosition(current)) match
                          case (Some(gp), Some(gc), Some(ip), Some(ic)) =>
                            MetricObservation.observed(ind(stepDirection(gp, gc) == stepDirection(ip, ic)))
                          case _ => MetricObservation.Missing(MissingReason.AllMissing)
                      case _ => MetricObservation.Ineligible
                  case _ => MetricObservation.Ineligible
              case _ => MetricObservation.Ineligible
        UnitObservation(u.id, observation)
      }
      Names.routeSupportMidpointDirection -> values

    def positionOf(ref: SourceNodeRef): Option[Double] =
      view.measuredPosition(ref)
    def goldAnchor(unit: RecallUnit): Option[SourceNodeRef] =
      c.gold(unit.id).flatMap(_.primary).map(_.node)
    val goldAnchors = units.map(goldAnchor)
    val inferredRows = units.map(u => result.posterior.row(u.id))
    val inferredAnchors = inferredRows.map(_.flatMap(mapAnchor))
    def intervalHasUnranked(from: Int, to: Int): Boolean =
      inferredRows.slice(from, to + 1).exists(_.exists(hasUnranked))
    def pairObservations(
        pairs: Vector[(Int, Int)],
        comparison: Set[(Int, Int)]
    ): Vector[UnitObservation] =
      pairs.map { case pair @ (from, to) =>
        val observation =
          if intervalHasUnranked(from, to) then
            MetricObservation.Missing(MissingReason.ProviderAbstained)
          else MetricObservation.observed(ind(comparison.contains(pair)))
        UnitObservation(units(to).id, observation)
      }

    val goldRecurrences = recurrencePairs(goldAnchors)
    val inferredRecurrences = recurrencePairs(inferredAnchors)
    val revisitRecall =
      Names.routeRevisitPairRecall -> pairObservations(
        goldRecurrences,
        inferredRecurrences.toSet
      )
    val revisitPrecision =
      Names.routeRevisitPairPrecision -> pairObservations(
        inferredRecurrences,
        goldRecurrences.toSet
      )

    val displacement =
      val values = units.zipWithIndex.map { case (u, i) =>
        val observation =
          if i == 0 then MetricObservation.Ineligible
          else
            val previous = units(i - 1)
            (goldAnchor(previous), goldAnchor(u)) match
              case (Some(goldPreviousRef), Some(goldCurrentRef)) =>
                (inferredRows(i - 1), inferredRows(i)) match
                  case (Some(previousRow), Some(currentRow))
                      if hasUnranked(previousRow) || hasUnranked(currentRow) =>
                    MetricObservation.Missing(MissingReason.ProviderAbstained)
                  case (Some(previousRow), Some(currentRow)) =>
                    (
                      positionOf(goldPreviousRef), positionOf(goldCurrentRef),
                      mapAnchor(previousRow).flatMap(positionOf),
                      mapAnchor(currentRow).flatMap(positionOf)
                    ) match
                      case (Some(goldPrevious), Some(goldCurrent), Some(inferredPrevious), Some(inferredCurrent)) =>
                        val goldDistance = math.abs(goldCurrent - goldPrevious)
                        val inferredDistance = math.abs(inferredCurrent - inferredPrevious)
                        MetricObservation.observed(
                          1.0 - math.abs(goldDistance - inferredDistance)
                        )
                      case _ => MetricObservation.Missing(MissingReason.AllMissing)
                  case _ => MetricObservation.Missing(MissingReason.AllMissing)
              case _ => MetricObservation.Ineligible
        UnitObservation(u.id, observation)
      }
      Names.routeTransitionDisplacementCloseness -> values
    val structuralCoverage = obs(Names.structuralTermCoverage)(termCoverage(CostTerm.Structural))
    val semanticCoverage = obs(Names.semanticTermCoverage)(termCoverage(CostTerm.Semantic))

    CaseObservations(
      c.id,
      (recallAtK ++ ancestorAtK ++ Vector(
        route,
        revisitRecall,
        revisitPrecision,
        goldLevelCloseness,
        goldLevelSignedBias,
        displacement,
        mrr,
        levelExact,
        summaryAccuracy,
        blend,
        burden,
        falseGating,
        detection,
        facets,
        externalRule,
        externalSubtype,
        inference,
        structuralCoverage,
        semanticCoverage
      )).toMap
    )

  /** Story-macro aggregation with a seeded percentile bootstrap over the per-case means.
    *
    * Why story-macro: the spec's pass/fail rules are stated per story family with story-macro CIs,
    * so one long story may not dominate a rate. Cases with no eligible unit for a metric do not
    * contribute a mean (and are not in the coverage), rather than contributing zero.
    *
    * Any explicitly missing eligible observation makes the aggregate missing. Conditioning on the
    * surviving observations would let a channel improve its accuracy by abstaining selectively on
    * failures; coverage stays attached so the missing support remains visible.
    */
  def aggregate(
      name: String,
      cases: Vector[CaseObservations],
      inputs: Vector[Checksum],
      seed: Long,
      resamples: Int = 200
  ): MetricValue =
    val perCase: Vector[(Coverage, Option[Double], Vector[MissingReason])] = cases.map { c =>
      val obs = c.byMetric.getOrElse(name, Vector.empty)
      val eligible = obs.filter(_.observation != MetricObservation.Ineligible)
      val observed = eligible.collect {
        case UnitObservation(_, MetricObservation.Observed(value)) => value
      }
      val missing = eligible.collect { case UnitObservation(_, MetricObservation.Missing(reason)) =>
        reason
      }
      val coverage = Coverage.of(eligible.size, observed.size).getOrElse(Coverage.empty)
      val mean =
        if observed.isEmpty || missing.nonEmpty then None else Some(observed.sum / observed.size)
      (coverage, mean, missing)
    }
    val coverage = perCase.map(_._1).foldLeft(Coverage.empty)(_ + _)
    val means = perCase.flatMap(_._2)
    val missing = perCase.flatMap(_._3).sortBy(_.toString)
    val receipt = ContentAddress.digest(
      Vector("metric/v2", name, seed.toString, resamples.toString) ++ inputs.map(_.hex)
    )
    if missing.nonEmpty then
      MetricValue(name, Estimate.missing(missing.head), coverage, 0, None, receipt)
    else if means.isEmpty then
      MetricValue(name, Estimate.missing(MissingReason.AllMissing), coverage, 0, None, receipt)
    else
      val mean = means.sum / means.size
      val interval =
        if means.size < 2 then None
        else
          val rng = new scala.util.Random(
            seed ^ java.lang.Long.parseLong(ContentAddress.digest(Vector(name)).hex.take(8), 16)
          )
          val boots = Vector
            .fill(resamples) {
              val draw = Vector.fill(means.size)(means(rng.nextInt(means.size)))
              draw.sum / draw.size
            }
            .sorted
          def q(p: Double): Double =
            val idx = math.min(boots.size - 1, math.max(0, math.round(p * (boots.size - 1)).toInt))
            boots(idx)
          Some(Interval(q(0.025), q(0.975), resamples))
      MetricValue(name, Estimate.observed(mean), coverage, means.size, interval, receipt)
