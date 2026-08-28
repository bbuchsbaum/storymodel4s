package storymodel4s.align

import storymodel4s.recall.*

/** Typed transition features between source states. Each has a weight θ. Source→source moves are a
  * softmax of `Σ_k θ_k φ_k(s, t)` over the source states available at the next unit; `ExternalIn`
  * and `ExternalStay` are logits of leaving / remaining outside the source, mixed in *before* that
  * normalization so the chance of going external does not depend on how many attractive source
  * moves exist. Features are computed on anchors, so a distorted state moves like its anchor.
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
  /** Deterministic summation order (review #30). */
  def score(model: TransitionModel): Double =
    TransitionKind.values.toVector.map(k => model(k) * values.getOrElse(k, 0.0)).sum

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

final case class HsmmConfig private (
    temperature: Double,
    transitions: TransitionModel,
    refinementPasses: Int,
    refinementWeight: Double
)

object HsmmConfig:
  def of(
      temperature: Double = 0.15,
      transitions: TransitionModel = TransitionModel.default,
      refinementPasses: Int = 0,
      refinementWeight: Double = 0.3
  ): Either[AlignError, HsmmConfig] =
    if temperature <= 0.0 || temperature.isNaN || temperature.isInfinite then
      Left(AlignError.InvalidConfig("HsmmConfig.temperature", "must be positive and finite"))
    else if refinementPasses < 0 then
      Left(AlignError.InvalidConfig("HsmmConfig.refinementPasses", "must be nonnegative"))
    else if refinementWeight < 0.0 || refinementWeight.isNaN then
      Left(AlignError.InvalidConfig("HsmmConfig.refinementWeight", "must be nonnegative"))
    else if transitions.theta.values.exists(v => v.isNaN || v.isInfinite) then
      Left(AlignError.InvalidConfig("HsmmConfig.transitions", "weights must be finite"))
    else Right(new HsmmConfig(temperature, transitions, refinementPasses, refinementWeight))

  val default: HsmmConfig = of().fold(e => throw new IllegalStateException(e.message), identity)

  def unsafe(
      temperature: Double = 0.15,
      transitions: TransitionModel = TransitionModel.default,
      refinementPasses: Int = 0,
      refinementWeight: Double = 0.3
  ): HsmmConfig =
    of(temperature, transitions, refinementPasses, refinementWeight).fold(
      e => throw new IllegalArgumentException(e.message),
      identity
    )

/** Result of gated inference — a **proof of mode-gate admission**, not a record (ADR 0001 rev 3 L1;
  * forward-review P0). `costs` is keyed by the *admissible* states of each unit (plus its external
  * states); `admissibility` records, per candidate anchor, which modes the gate allowed and which
  * contradictions it found — including anchors whose only admissible mode is distorted.
  *
  * The constructor is `private[align]` and the class has no `copy`: the only ways to obtain a value
  * are [[GraphHsmm.infer]] (which itself goes through [[HsmmResult.validated]]) and
  * [[HsmmResult.validated]], which takes the recall and the source view and proves that every
  * anchored state appearing in the posterior, the flow, the costs, or the Viterbi path is admitted
  * in exactly that mode by an admissibility record that the [[ModeGate]] itself reproduces on that
  * unit and node. A forged row on an inadmissible `(anchor, mode)` — or a forged admissibility
  * record — therefore cannot inhabit this type, and every consumer that requires it
  * ([[RecallSignature]], [[PopulationAggregate]], [[SupportDensity]]) is guaranteed a gated result.
  * [[AblationResult]] is a separate type with no path here.
  */
final class HsmmResult private[align] (
    val posterior: AlignmentMatrix,
    val flow: TransitionFlow,
    val viterbi: Vector[AlignState],
    val logLikelihood: Double,
    val costs: Map[RecallUnitId, Map[AlignState, CostBreakdown]],
    val admissibility: Map[RecallUnitId, Map[SourceNodeRef, Admissibility]],
    val refinementPasses: Int
):
  private def parts =
    (posterior, flow, viterbi, logLikelihood, costs, admissibility, refinementPasses)

  override def equals(o: Any): Boolean = o match
    case that: HsmmResult => this.parts == that.parts
    case _                => false
  override def hashCode: Int = parts.hashCode
  override def toString: String =
    s"HsmmResult(units=${posterior.size}, steps=${flow.size}, logLikelihood=$logLikelihood, passes=$refinementPasses)"

object HsmmResult:

  /** Tolerance for row normalization and flow-marginal consistency (stated constant). */
  val Tolerance: Double = 1e-9

  /** The sole external constructor: proves the gate invariant over the supplied parts, **anchored
    * in the [[ModeGate]]**.
    *
    * Checks, in order: rows well-formed with unique units and normalized (`|Σ − 1| ≤ Tolerance`);
    * every row's unit exists in `recall`; `flow` has one step fewer than the rows with matching
    * consecutive endpoints, finite nonnegative mass, and step marginals equal to the adjacent rows
    * within `Tolerance`; `viterbi` has one state per row; `logLikelihood` is not NaN; and — the
    * gate — for every unit, every anchored state that appears as a key of its posterior row, of a
    * flow step (either endpoint), of its cost map, or as its Viterbi step (key presence, not
    * positive mass) is admitted in exactly that mode by the recorded admissibility, AND that
    * recorded admissibility is precisely what `ModeGate.assess(unit, view.node(ref), view)`
    * computes now. Every recorded admissibility entry is likewise re-derived from the gate, so a
    * caller cannot smuggle in a record the gate would not produce. A unit with no admissibility
    * entry may carry only external states (an unrankable unit). External states are never gated.
    *
    * Because the recall and the source view are required, a serialized result can only be decoded
    * back into this type with both in hand.
    */
  def validated(
      recall: RecallGraph,
      view: SourceView,
      posterior: AlignmentMatrix,
      flow: TransitionFlow,
      viterbi: Vector[AlignState],
      logLikelihood: Double,
      costs: Map[RecallUnitId, Map[AlignState, CostBreakdown]],
      admissibility: Map[RecallUnitId, Map[SourceNodeRef, Admissibility]],
      refinementPasses: Int
  ): Either[AlignError, HsmmResult] =
    val rows = posterior.rows
    def admitted(unit: RecallUnitId, s: AlignState): Boolean = s match
      case AlignState.External(_) => true
      case AlignState.Source(ref) => admissibility.get(unit).flatMap(_.get(ref)).exists(_.faithful)
      case AlignState.Distorted(ref, fs) =>
        admissibility.get(unit).flatMap(_.get(ref)).exists(_.distortion.contains(fs))
    def violation(unit: RecallUnitId, s: AlignState, where: String): AlignError =
      AlignError.GateViolation(unit, s, s"$where on a state the gate did not admit in this mode")
    def close(a: Double, b: Double): Boolean = math.abs(a - b) <= Tolerance
    val structural: Either[AlignError, Unit] =
      AlignmentMatrix.of(rows).flatMap { _ =>
        if flow.size != math.max(0, rows.size - 1) then
          Left(AlignError.InconsistentResult(s"flow has ${flow.size} steps for ${rows.size} rows"))
        else if viterbi.size != rows.size then
          Left(
            AlignError
              .InconsistentResult(s"viterbi has ${viterbi.size} states for ${rows.size} rows")
          )
        else if logLikelihood.isNaN then Left(AlignError.InconsistentResult("logLikelihood is NaN"))
        else if refinementPasses < 0 then
          Left(AlignError.InconsistentResult("refinementPasses must be nonnegative"))
        else
          val unknownUnit = rows.collectFirst {
            case r if !recall.byId.contains(r.unit) =>
              AlignError.InconsistentResult(s"row unit ${r.unit.value} is not in the recall")
          }
          val unnormalized = rows.collectFirst {
            case r if !close(r.total, 1.0) =>
              AlignError.InconsistentResult(
                s"row ${r.unit.value} sums to ${r.total}, not 1 within $Tolerance"
              )
          }
          val endpoints = flow.steps.zipWithIndex.collectFirst {
            case (st, i) if st.from != rows(i).unit || st.to != rows(i + 1).unit =>
              AlignError.InconsistentResult(s"flow step $i does not join rows $i and ${i + 1}")
          }
          val stepMass = flow.steps.collectFirst {
            case st if st.mass.values.exists(m => m < 0.0 || m.isNaN || m.isInfinite) =>
              AlignError.InconsistentResult(
                s"flow step ${st.from.value}→${st.to.value} has malformed mass"
              )
          }
          val marginals = flow.steps.zipWithIndex.collectFirst {
            case (st, i) if !marginalsMatch(st, rows(i), rows(i + 1)) =>
              AlignError.InconsistentResult(
                s"flow step $i marginals do not match rows $i and ${i + 1} within $Tolerance"
              )
          }
          unknownUnit
            .orElse(unnormalized)
            .orElse(endpoints)
            .orElse(stepMass)
            .orElse(marginals)
            .toLeft(())
      }
    // The recorded admissibility must be exactly what the gate computes on this recall and view:
    // every entry is re-derived, and every anchor that appears anywhere must have an entry.
    val anchoredInGate: Either[AlignError, Unit] =
      val entries = admissibility.toVector.sortBy(_._1.value).iterator.flatMap { (u, m) =>
        m.toVector.sortBy(_._1.key).iterator.map { (ref, a) => (u, ref, a) }
      }
      entries
        .map { (u, ref, a) =>
          (recall.byId.get(u), view.node(ref)) match
            case (None, _) =>
              Left(AlignError.InconsistentResult(s"admissibility for unknown unit ${u.value}"))
            case (_, None) =>
              Left(
                AlignError.GateViolation(
                  u,
                  AlignState.Source(ref),
                  "admissibility recorded for an anchor absent from the source view"
                )
              )
            case (Some(unit), Some(node)) =>
              val expected = ModeGate.assess(unit, node, view)
              if expected == a then Right(())
              else
                Left(
                  AlignError.GateViolation(
                    u,
                    AlignState.Source(ref),
                    "recorded admissibility differs from what the mode gate computes"
                  )
                )
        }
        .collectFirst { case l @ Left(_) => l }
        .getOrElse(Right(()))
    val gate: Either[AlignError, Unit] =
      // Key presence, not positive mass: a zero-mass key on an inadmissible state is still a
      // state a consumer can read (argmax, population.sourceRefs).
      val posteriorV = rows.iterator.flatMap { r =>
        r.mass.keys.toVector.sortBy(_.key).collect {
          case s if !admitted(r.unit, s) => violation(r.unit, s, "posterior key")
        }
      }
      val flowV = flow.steps.iterator.flatMap { st =>
        st.mass.keys.toVector.sortBy { case (a, b) => (a.key, b.key) }.collect {
          case (a, _) if !admitted(st.from, a) => violation(st.from, a, "flow key")
          case (_, b) if !admitted(st.to, b)   => violation(st.to, b, "flow key")
        }
      }
      val costV = costs.toVector.sortBy(_._1.value).iterator.flatMap { (u, m) =>
        m.keys.toVector.sortBy(_.key).collect {
          case s if !admitted(u, s) => violation(u, s, "cost entry")
        }
      }
      val pathV = rows.zip(viterbi).iterator.collect {
        case (r, s) if !admitted(r.unit, s) => violation(r.unit, s, "viterbi step")
      }
      (posteriorV ++ flowV ++ costV ++ pathV).nextOption().toLeft(())
    for
      _ <- structural
      _ <- anchoredInGate
      _ <- gate
    yield new HsmmResult(
      posterior,
      flow,
      viterbi,
      logLikelihood,
      costs,
      admissibility,
      refinementPasses
    )

  /** Row-marginal consistency of a flow step with its adjacent rows (deterministic key order). */
  private def marginalsMatch(st: FlowStep, from: AlignmentRow, to: AlignmentRow): Boolean =
    val byFrom = st.mass.toVector.sortBy { case ((a, b), _) => (a.key, b.key) }
    val outMass = byFrom.groupMapReduce(_._1._1)(_._2)(_ + _)
    val inMass = byFrom.groupMapReduce(_._1._2)(_._2)(_ + _)
    val fromKeys = (from.mass.keySet ++ outMass.keySet).toVector.sortBy(_.key)
    val toKeys = (to.mass.keySet ++ inMass.keySet).toVector.sortBy(_.key)
    fromKeys.forall(s => math.abs(outMass.getOrElse(s, 0.0) - from(s)) <= Tolerance) &&
    toKeys.forall(t => math.abs(inMass.getOrElse(t, 0.0) - to(t)) <= Tolerance)

/** Result of *ungated* inference, for ablations only. It deliberately has no `AlignmentMatrix`:
  * nothing that consumes a posterior ([[RecallSignature]], densities, population aggregates) can be
  * fed an ungated result, so the ablation cannot masquerade as a scientific alignment.
  */
final case class AblationResult(
    rows: Vector[(RecallUnitId, Map[AlignState, Double])],
    viterbi: Vector[AlignState],
    logLikelihood: Double
):
  def massOn(unit: RecallUnitId, state: AlignState): Double =
    rows.find(_._1 == unit).flatMap(_._2.get(state)).getOrElse(0.0)

/** Stage 3: sparse graph-structured HSMM over the recall sequence.
  *
  * States at unit `i` are the `(anchor, mode)` pairs the [[ModeGate]] admits on that unit's
  * candidates plus the external states. The gate runs first and is non-bypassable: a contradicted
  * anchor is present only in its distorted mode, so the faithful mode of a role-reversed or negated
  * event can never receive mass, while the event itself is still recalled (ADR 0001 rev 3 §D5). A
  * unit the aligner could not rank has the single state `Unranked`. Emissions are `exp(−cost/τ)`
  * where the cost model sees only admissible pairs (law L3); external states emit at the floor.
  * Transitions are typed by source structure on anchors. Forward–backward in log space yields `P`
  * and `F` as posteriors; Viterbi gives the MAP path on the same (possibly refined) costs as the
  * posterior. Refinement passes re-weight emissions by relation preservation over the admissible
  * states only and never widen the state space.
  */
object GraphHsmm:

  def infer(
      recall: RecallGraph,
      view: SourceView,
      candidates: Candidates,
      costModel: LocalCostModel,
      config: HsmmConfig = HsmmConfig.default
  ): Either[AlignError, HsmmResult] =
    val units = recall.ordered
    if units.isEmpty then Left(AlignError.EmptyRecall)
    else
      val (post, flow, path, logZ, costs, adm, passes) =
        run(units, recall, view, candidates, costModel, config, gate = true)
      // The engine proves its own output: a gate violation here would be a bug, and it surfaces
      // as a typed error rather than an unproven result (law: every infer output validates).
      HsmmResult.validated(recall, view, post, flow, path, logZ, costs, adm, passes)

  /** Ungated inference for ablations: every candidate is admitted in the faithful mode and no
    * distorted state exists. Returns an [[AblationResult]], never an `HsmmResult`.
    */
  def ablationUngated(
      recall: RecallGraph,
      view: SourceView,
      candidates: Candidates,
      costModel: LocalCostModel,
      config: HsmmConfig = HsmmConfig.default
  ): Either[AlignError, AblationResult] =
    val units = recall.ordered
    if units.isEmpty then Left(AlignError.EmptyRecall)
    else
      val (post, _, path, logZ, _, _, _) =
        run(units, recall, view, candidates, costModel, config, gate = false)
      Right(AblationResult(post.rows.map(r => r.unit -> r.mass), path, logZ))

  private type Costs = Map[RecallUnitId, Map[AlignState, CostBreakdown]]
  private type Adm = Map[RecallUnitId, Map[SourceNodeRef, Admissibility]]

  private def run(
      units: Vector[RecallUnit],
      recall: RecallGraph,
      view: SourceView,
      candidates: Candidates,
      costModel: LocalCostModel,
      config: HsmmConfig,
      gate: Boolean
  ): (AlignmentMatrix, TransitionFlow, Vector[AlignState], Double, Costs, Adm, Int) =
    val tau = config.temperature

    // 1. The mode gate (prepass): which (anchor, mode) pairs exist for each unit. The cost model
    //    is consulted only for those pairs.
    val admissibility: Vector[Map[SourceNodeRef, Admissibility]] = units.map { u =>
      candidates
        .set(u.id)
        .ranked
        .flatMap { ref =>
          view.node(ref).map { n =>
            ref -> (if gate then ModeGate.assess(u, n, view) else Admissibility.faithfulOnly)
          }
        }
        .toMap
    }
    val breakdowns: Vector[Map[AlignState, CostBreakdown]] =
      units.zip(admissibility).map { (u, adm) =>
        val set = candidates.set(u.id)
        val sources = set.ranked.flatMap { ref =>
          view.node(ref) match
            case None    => Vector(AlignState.Source(ref) -> CostBreakdown.unreachable)
            case Some(n) =>
              adm(ref).modes.map(m => AlignState.anchored(ref, m) -> costModel.cost(u, n, m, view))
        }
        val externals =
          if set.abstained && set.ranked.isEmpty then Vector(AlignState.unranked)
          else AlignState.externals
        val ext = externals.map {
          case s @ AlignState.External(x) =>
            s -> CostBreakdown(Map.empty, None, None, costModel.externalCost(u, x))
          case s => s -> CostBreakdown.unreachable
        }
        (sources ++ ext).toMap
      }
    val states: Vector[Vector[AlignState]] = breakdowns.map { m =>
      m.toVector.collect { case (s, b) if !b.excluded => s }.sortBy(_.key)
    }
    val baseCost: Vector[Map[AlignState, Double]] =
      breakdowns.zip(states).map { (m, ss) => ss.map(s => s -> m(s).total).toMap }

    // log transition matrices A_i(s, t): a mixture of "go/stay external" and a feature softmax
    // over the source states available at i+1
    val logA: Vector[Map[AlignState, Map[AlignState, Double]]] =
      (0 until units.size - 1).toVector.map { i =>
        val from = states(i)
        val to = states(i + 1)
        from.map(s => s -> transitionRow(view, config.transitions, s, to)).toMap
      }

    def forwardBackward(
        costs: Vector[Map[AlignState, Double]]
    ): (AlignmentMatrix, TransitionFlow, Double) =
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
      // deterministic summation order: iterate states, not a map (review #30)
      val logZ = logSumExp(states(n - 1).map(s => logAlpha(n - 1)(s)))
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

    var costs = baseCost
    var (posterior, flow, logZ) = forwardBackward(costs)
    var pass = 0
    while pass < config.refinementPasses do
      costs = RelationPreservation.reweight(
        baseCost,
        units,
        posterior,
        recall,
        view,
        config.refinementWeight
      )
      val r = forwardBackward(costs)
      posterior = r._1
      flow = r._2
      logZ = r._3
      pass += 1

    val path = viterbi(units, states, costs.map(_.view.mapValues(c => -c / tau).toMap), logA)
    (
      posterior,
      flow,
      path,
      logZ,
      units.map(_.id).zip(breakdowns).toMap,
      units.map(_.id).zip(admissibility).toMap,
      pass
    )

  /** Log transition distribution from `s` over the states `to` available at the next unit. */
  private[align] def transitionRow(
      view: SourceView,
      model: TransitionModel,
      s: AlignState,
      to: Vector[AlignState]
  ): Map[AlignState, Double] =
    val sources = to.filter(_.isSource)
    val externals = to.filter(_.isExternal)
    val nExt = math.max(1, externals.size)
    s.anchor match
      case Some(a) =>
        val pIn = if sources.isEmpty then 1.0 else model.pExternalIn
        val scores =
          sources.map(t => t -> TransitionFeatures.between(view, a, t.anchor.get).score(model))
        val z = logSumExp(scores.map(_._2))
        val src = scores.map { case (t, sc) => t -> (math.log(1.0 - pIn) + sc - z) }
        val ext = externals.map(t => t -> (math.log(pIn) - math.log(nExt.toDouble)))
        (src ++ ext).toMap
      case None =>
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
    val last = states(n - 1).map(s => (s, delta(n - 1)(s))).maxBy { case (s, v) => (v, s.key) }._1
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
    * recall's explicit edges (1 = every recalled relation is preserved in the source). Anchors of
    * either mode count.
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
            (s, ma) <- a.anchorMass.toVector.sortBy(_._1.key)
            (t, mb) <- b.anchorMass.toVector.sortBy(_._1.key)
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

  /** Lower the cost of states that would preserve the recall's explicit relations given the current
    * posterior of the related units. Only states present in `base` (the admissible ones) are
    * touched, so the gate can never be undone (law L1).
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
        base(i).keys.toVector.sortBy(_.key).foreach { s =>
          s.anchor.foreach { sr =>
            val v = rowB.anchorMass.toVector
              .sortBy(_._1.key)
              .collect { case (t, m) if view.reachable(layer, sr, t) => m }
              .sum
            add(i, s, v)
          }
        }
        base(j).keys.toVector.sortBy(_.key).foreach { t =>
          t.anchor.foreach { tr =>
            val v = rowA.anchorMass.toVector
              .sortBy(_._1.key)
              .collect { case (s, m) if view.reachable(layer, s, tr) => m }
              .sum
            add(j, t, v)
          }
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
