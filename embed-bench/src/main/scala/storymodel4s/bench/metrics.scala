package storymodel4s.bench

import storymodel4s.align.*
import storymodel4s.core.{Checksum, ContentAddress}
import storymodel4s.features.{Coverage, Estimate, MissingReason}
import storymodel4s.recall.RecallUnitId

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

/** A per-unit observation of one metric: `None` when the unit is not eligible for it. */
final case class UnitObservation(unit: RecallUnitId, value: Option[Double])

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
        semanticTermCoverage
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
    def obs(
        name: String
    )(f: (AlignmentRow, GoldUnit) => Option[Double]): (String, Vector[UnitObservation]) =
      name -> units.map { u =>
        val v = for
          g <- c.gold(u.id)
          row <- result.posterior.row(u.id)
          x <- f(row, g)
        yield x
        UnitObservation(u.id, v)
      }
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
        g.primary.map { p =>
          val refused = result.admissibility.get(row.unit).flatMap(_.get(p.node)).exists(_.gated)
          ind(refused)
        }
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
    val externalRule = obs(Names.externalRule) { (row, g) =>
      g.groundedness match
        case Groundedness.Association | Groundedness.Intrusion | Groundedness.Uninterpretable =>
          Some(ind(row.externalMass > row.sourceMass))
        case _ => None
    }
    val externalSubtype = obs(Names.externalSubtype) { (row, g) =>
      g.externalSubtype.map { expected =>
        ind(row.argmax.contains(AlignState.External(expected)))
      }
    }
    val inference = obs(Names.inferenceMass) { (row, g) =>
      if g.groundedness == Groundedness.Inference then
        Some(row.externalMass(ExternalState.SourceConsistentInference))
      else None
    }
    def termCoverage(term: CostTerm): (AlignmentRow, GoldUnit) => Option[Double] = (row, _) =>
      val breakdowns = result.costs.getOrElse(row.unit, Map.empty).toVector.collect {
        case (s, b) if s.isSource => b
      }
      if breakdowns.isEmpty then None
      else Some(breakdowns.count(_.has(term)).toDouble / breakdowns.size)
    val structuralCoverage = obs(Names.structuralTermCoverage)(termCoverage(CostTerm.Structural))
    val semanticCoverage = obs(Names.semanticTermCoverage)(termCoverage(CostTerm.Semantic))

    CaseObservations(
      c.id,
      (recallAtK ++ ancestorAtK ++ Vector(
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
    */
  def aggregate(
      name: String,
      cases: Vector[CaseObservations],
      inputs: Vector[Checksum],
      seed: Long,
      resamples: Int = 200
  ): MetricValue =
    val perCase: Vector[(Coverage, Option[Double])] = cases.map { c =>
      val obs = c.byMetric.getOrElse(name, Vector.empty)
      val observed = obs.flatMap(_.value)
      val coverage = Coverage.of(obs.size, observed.size).getOrElse(Coverage.empty)
      (coverage, if observed.isEmpty then None else Some(observed.sum / observed.size))
    }
    val coverage = perCase.map(_._1).foldLeft(Coverage.empty)(_ + _)
    val means = perCase.flatMap(_._2)
    val receipt = ContentAddress.digest(
      Vector("metric/v1", name, seed.toString, resamples.toString) ++ inputs.map(_.hex)
    )
    if means.isEmpty then
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
