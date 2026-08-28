package storymodel4s.align

import storymodel4s.recall.*

/** Typed transition features between source states. Each has a weight θ. Source→source moves are a
  * softmax of `Σ_k θ_k φ_k(s, t)` over the source states available at the next unit; `ExternalIn`
  * and `ExternalStay` are logits of leaving / remaining outside the source, mixed in *before* that
  * normalization so the chance of going external does not depend on how many attractive source
  * moves exist.
  */
enum TransitionKind:
  case Stay, DiscourseSuccessor, WorldTimeSuccessor, CausalNeighbor, HierarchyUp, HierarchyDown,
    SameEntityThread, SemanticNeighbor, Backward, LongJump, ExternalIn, ExternalStay

final case class TransitionModel(theta: Map[TransitionKind, Double]):
  def apply(k: TransitionKind): Double = theta.getOrElse(k, 0.0)

  /** Probability of moving from a source state to an external state. */
  def pExternalIn: Double = Logistic.sigmoid(apply(TransitionKind.ExternalIn))

  /** Probability of remaining external once external. */
  def pExternalStay: Double = Logistic.sigmoid(apply(TransitionKind.ExternalStay))

object TransitionModel:
  /** Provisional defaults: forward-in-discourse and hierarchy moves are cheap, long backward jumps
    * are penalized but permitted, external excursions are entered with probability σ(−1.5) ≈ 0.18
    * and left with probability 1 − σ(−0.85) ≈ 0.7.
    */
  val default: TransitionModel = TransitionModel(
    Map(
      TransitionKind.Stay -> 0.5,
      TransitionKind.DiscourseSuccessor -> 1.5,
      TransitionKind.WorldTimeSuccessor -> 1.0,
      TransitionKind.CausalNeighbor -> 0.8,
      TransitionKind.HierarchyUp -> 0.6,
      TransitionKind.HierarchyDown -> 0.6,
      TransitionKind.SameEntityThread -> 0.2,
      TransitionKind.SemanticNeighbor -> 0.3,
      TransitionKind.Backward -> -0.5,
      TransitionKind.LongJump -> -1.0,
      TransitionKind.ExternalIn -> -1.5,
      TransitionKind.ExternalStay -> -0.85
    )
  )

/** Feature vector of a source→source move; exposed for diagnostics and learning. */
final case class TransitionFeatures(values: Map[TransitionKind, Double]):
  def score(model: TransitionModel): Double =
    values.iterator.map { case (k, v) => model(k) * v }.sum

object TransitionFeatures:
  def between(view: SourceView, s: SourceNodeRef, t: SourceNodeRef): TransitionFeatures =
    val ps = view.relativePosition(s)
    val pt = view.relativePosition(t)
    def ind(b: Boolean): Double = if b then 1.0 else 0.0
    TransitionFeatures(
      Map(
        TransitionKind.Stay -> ind(s == t),
        TransitionKind.DiscourseSuccessor -> ind(
          view.hasEdge(RelationLayer.DiscourseSuccession, s, t)
        ),
        TransitionKind.WorldTimeSuccessor -> ind(view.hasEdge(RelationLayer.WorldTime, s, t)),
        TransitionKind.CausalNeighbor -> ind(
          view.hasEdge(RelationLayer.Causal, s, t) || view.hasEdge(RelationLayer.Causal, t, s)
        ),
        TransitionKind.HierarchyUp -> ind(view.isAncestor(t, s)),
        TransitionKind.HierarchyDown -> ind(view.isAncestor(s, t)),
        TransitionKind.SameEntityThread -> ind(
          view.hasEdge(RelationLayer.EntityContinuity, s, t) ||
            view.hasEdge(RelationLayer.EntityContinuity, t, s)
        ),
        TransitionKind.SemanticNeighbor -> math.max(
          view.weight(RelationLayer.Semantic, s, t),
          view.weight(RelationLayer.Semantic, t, s)
        ),
        TransitionKind.Backward -> ind(s != t && pt < ps && !view.isAncestor(t, s)),
        TransitionKind.LongJump -> math.abs(pt - ps)
      )
    )

final case class HsmmConfig(
    temperature: Double = 0.15,
    transitions: TransitionModel = TransitionModel.default,
    refinementPasses: Int = 0,
    refinementWeight: Double = 0.3
)

final case class HsmmResult(
    posterior: AlignmentMatrix,
    flow: TransitionFlow,
    viterbi: Vector[AlignState],
    logLikelihood: Double,
    costs: Map[RecallUnitId, Map[AlignState, CostBreakdown]]
)

/** Stage 3: sparse graph-structured HSMM over the recall sequence.
  *
  * States at unit `i` are that unit's source candidates plus the external states. Emissions are
  * `exp(−cost/τ)`; external states emit at the floor. Transitions are typed by source structure.
  * Forward–backward in log space yields `P` and `F` as posteriors; Viterbi gives the MAP path.
  * Optional refinement passes re-weight emissions by relation preservation (corrective only).
  */
object GraphHsmm:

  def infer(
      recall: RecallGraph,
      view: SourceView,
      candidates: Candidates,
      costModel: LocalCostModel,
      config: HsmmConfig = HsmmConfig()
  ): HsmmResult =
    val units = recall.ordered
    require(units.nonEmpty, "recall has no units")
    val tau = config.temperature

    val states: Vector[Vector[AlignState]] = units.map { u =>
      candidates(u.id).map(AlignState.Source(_)) ++ AlignState.externals
    }
    val breakdowns: Vector[Map[AlignState, CostBreakdown]] = units.zip(states).map { (u, ss) =>
      ss.map {
        case s @ AlignState.Source(ref) =>
          s -> view.node(ref).map(costModel.cost(u, _, view)).getOrElse(unreachable)
        case s @ AlignState.External(x) =>
          val c = costModel.externalCost(u, x)
          s -> CostBreakdown(Map.empty, Vector.empty, gated = false, c)
      }.toMap
    }
    val baseCost: Vector[Map[AlignState, Double]] =
      breakdowns.map(_.view.mapValues(_.total).toMap)

    // log transition matrices A_i(s, t): a mixture of "go/stay external" and a feature softmax
    // over the source states available at i+1
    val logA: Vector[Map[AlignState, Map[AlignState, Double]]] =
      (0 until units.size - 1).toVector.map { i =>
        val from = states(i)
        val to = states(i + 1)
        from.map(s => s -> transitionRow(view, config.transitions, s, to)).toMap
      }

    def run(costs: Vector[Map[AlignState, Double]]): (AlignmentMatrix, TransitionFlow, Double) =
      val logE = costs.map(_.view.mapValues(c => -c / tau).toMap)
      val n = units.size
      val logAlpha = Array.ofDim[Map[AlignState, Double]](n)
      val logPi = -math.log(states(0).size.toDouble)
      logAlpha(0) = states(0).map(s => s -> (logPi + logE(0)(s))).toMap
      for i <- 1 until n do
        logAlpha(i) = states(i).map { t =>
          val acc = states(i - 1).map(s => logAlpha(i - 1)(s) + logA(i - 1)(s)(t))
          t -> (logE(i)(t) + logSumExp(acc))
        }.toMap
      val logBeta = Array.ofDim[Map[AlignState, Double]](n)
      logBeta(n - 1) = states(n - 1).map(s => s -> 0.0).toMap
      for i <- (n - 2) to 0 by -1 do
        logBeta(i) = states(i).map { s =>
          val acc = states(i + 1).map(t => logA(i)(s)(t) + logE(i + 1)(t) + logBeta(i + 1)(t))
          s -> logSumExp(acc)
        }.toMap
      val logZ = logSumExp(logAlpha(n - 1).values.toVector)
      val rows = (0 until n).toVector.map { i =>
        AlignmentRow(
          units(i).id,
          states(i).map(s => s -> math.exp(logAlpha(i)(s) + logBeta(i)(s) - logZ)).toMap
        )
      }
      val steps = (0 until n - 1).toVector.map { i =>
        val mass = for
          s <- states(i)
          t <- states(i + 1)
        yield (s, t) -> math.exp(
          logAlpha(i)(s) + logA(i)(s)(t) + logE(i + 1)(t) + logBeta(i + 1)(t) - logZ
        )
        FlowStep(units(i).id, units(i + 1).id, mass.toMap)
      }
      (AlignmentMatrix(rows), TransitionFlow(steps), logZ)

    var (posterior, flow, logZ) = run(baseCost)
    var pass = 0
    while pass < config.refinementPasses do
      val refined = RelationPreservation.reweight(
        baseCost,
        units,
        posterior,
        recall,
        view,
        config.refinementWeight
      )
      val r = run(refined)
      posterior = r._1
      flow = r._2
      logZ = r._3
      pass += 1

    val path = viterbi(units, states, baseCost.map(_.view.mapValues(c => -c / tau).toMap), logA)
    HsmmResult(posterior, flow, path, logZ, units.map(_.id).zip(breakdowns).toMap)

  private def unreachable: CostBreakdown =
    CostBreakdown(Map.empty, Vector.empty, gated = true, Double.MaxValue / 4)

  /** Log transition distribution from `s` over the states `to` available at the next unit. */
  private[align] def transitionRow(
      view: SourceView,
      model: TransitionModel,
      s: AlignState,
      to: Vector[AlignState]
  ): Map[AlignState, Double] =
    val sources = to.collect { case t @ AlignState.Source(_) => t }
    val externals = to.collect { case t @ AlignState.External(_) => t }
    val nExt = math.max(1, externals.size)
    s match
      case AlignState.Source(a) =>
        val pIn = if sources.isEmpty then 1.0 else model.pExternalIn
        val scores = sources.map(t => t -> TransitionFeatures.between(view, a, t.ref).score(model))
        val z = logSumExp(scores.map(_._2))
        val src = scores.map { case (t, sc) => t -> (math.log(1.0 - pIn) + sc - z) }
        val ext = externals.map(t => t -> (math.log(pIn) - math.log(nExt.toDouble)))
        (src ++ ext).toMap
      case AlignState.External(_) =>
        val pStay = if sources.isEmpty then 1.0 else model.pExternalStay
        val src = sources.map(t => t -> (math.log(1.0 - pStay) - math.log(sources.size.toDouble)))
        val ext = externals.map(t => t -> (math.log(pStay) - math.log(nExt.toDouble)))
        (src ++ ext).toMap

  private def viterbi(
      units: Vector[RecallUnit],
      states: Vector[Vector[AlignState]],
      logE: Vector[Map[AlignState, Double]],
      logA: Vector[Map[AlignState, Map[AlignState, Double]]]
  ): Vector[AlignState] =
    val n = units.size
    val delta = Array.ofDim[Map[AlignState, Double]](n)
    val back = Array.ofDim[Map[AlignState, AlignState]](n)
    val logPi = -math.log(states(0).size.toDouble)
    delta(0) = states(0).map(s => s -> (logPi + logE(0)(s))).toMap
    for i <- 1 until n do
      val pairs = states(i).map { t =>
        val best = states(i - 1)
          .map(s => (s, delta(i - 1)(s) + logA(i - 1)(s)(t)))
          .maxBy { case (s, v) => (v, s.key) }
        (t, best._1, best._2 + logE(i)(t))
      }
      delta(i) = pairs.map(p => p._1 -> p._3).toMap
      back(i) = pairs.map(p => p._1 -> p._2).toMap
    val last = delta(n - 1).maxBy { case (s, v) => (v, s.key) }._1
    val path = Array.ofDim[AlignState](n)
    path(n - 1) = last
    for i <- (n - 1) until 0 by -1 do path(i - 1) = back(i)(path(i))
    path.toVector

  private[align] def logSumExp(xs: Vector[Double]): Double =
    if xs.isEmpty then Double.NegativeInfinity
    else
      val m = xs.max
      if m.isNegInfinity then m else m + math.log(xs.map(x => math.exp(x - m)).sum)

/** Relation-preservation term `D_r(B^{(r)}, P A^{(r)} P^T)` restricted to explicit recall
  * relations. Used as a diagnostic and as an optional corrective on emissions.
  */
object RelationPreservation:

  /** For each source layer with a recall counterpart, the mean induced source weight over the
    * recall's explicit edges (1 = every recalled relation is preserved in the source).
    */
  def diagnostic(
      posterior: AlignmentMatrix,
      recall: RecallGraph,
      view: SourceView
  ): Map[RelationLayer, Double] =
    def induced(layer: RelationLayer, from: RecallUnitId, to: RecallUnitId): Double =
      (posterior.row(from), posterior.row(to)) match
        case (Some(a), Some(b)) =>
          val pairs = for
            case (AlignState.Source(s), ma) <- a.mass.toVector
            case (AlignState.Source(t), mb) <- b.mass.toVector
            if ma > 0.0 && mb > 0.0
          yield ma * mb * (if view.reachable(layer, s, t) then 1.0 else 0.0)
          val z = a.sourceMass * b.sourceMass
          if z <= 0.0 then 0.0 else pairs.sum / z
        case _ => 0.0
    val temporal = recall.relations.temporal.map { e =>
      e.relation match
        case RecallTemporalRelation.Before       => induced(RelationLayer.WorldTime, e.from, e.to)
        case RecallTemporalRelation.After        => induced(RelationLayer.WorldTime, e.to, e.from)
        case RecallTemporalRelation.Simultaneous => 0.0
    }
    val causal = recall.relations.causal.map(e => induced(RelationLayer.Causal, e.cause, e.effect))
    def mean(xs: Vector[Double]): Double = if xs.isEmpty then 1.0 else xs.sum / xs.size
    Map(RelationLayer.WorldTime -> mean(temporal), RelationLayer.Causal -> mean(causal))

  /** Lower the cost of candidates that would preserve the recall's explicit relations given the
    * current posterior of the related units.
    */
  private[align] def reweight(
      base: Vector[Map[AlignState, Double]],
      units: Vector[RecallUnit],
      posterior: AlignmentMatrix,
      recall: RecallGraph,
      view: SourceView,
      lambda: Double
  ): Vector[Map[AlignState, Double]] =
    val idx = units.map(_.id).zipWithIndex.toMap
    val bonus = Array.fill(units.size)(scala.collection.mutable.Map.empty[AlignState, Double])
    def add(i: Int, s: AlignState, v: Double): Unit =
      bonus(i).update(s, bonus(i).getOrElse(s, 0.0) + v)
    def support(layer: RelationLayer, a: RecallUnitId, b: RecallUnitId): Unit =
      for
        i <- idx.get(a)
        j <- idx.get(b)
        rowB <- posterior.row(b)
        rowA <- posterior.row(a)
      do
        base(i).keys.foreach {
          case s @ AlignState.Source(sr) =>
            val v = rowB.mass.collect {
              case (AlignState.Source(t), m) if view.reachable(layer, sr, t) => m
            }.sum
            add(i, s, v)
          case _ => ()
        }
        base(j).keys.foreach {
          case t @ AlignState.Source(tr) =>
            val v = rowA.mass.collect {
              case (AlignState.Source(s), m) if view.reachable(layer, s, tr) => m
            }.sum
            add(j, t, v)
          case _ => ()
        }
    recall.relations.temporal.foreach { e =>
      e.relation match
        case RecallTemporalRelation.Before => support(RelationLayer.WorldTime, e.from, e.to)
        case RecallTemporalRelation.After  => support(RelationLayer.WorldTime, e.to, e.from)
        case _                             => ()
    }
    recall.relations.causal.foreach(e => support(RelationLayer.Causal, e.cause, e.effect))
    base.zipWithIndex.map { case (m, i) =>
      m.map { case (s, c) => s -> (c - lambda * bonus(i).getOrElse(s, 0.0)) }
    }
