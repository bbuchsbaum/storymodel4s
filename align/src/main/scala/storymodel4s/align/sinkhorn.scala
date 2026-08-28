package storymodel4s.align

import storymodel4s.recall.RecallGraph

final case class SinkhornConfig(
    epsilon: Double = 0.1,
    rhoRows: Double = 1.0,
    rhoCols: Double = 1.0,
    iterations: Int = 200
)

final case class SinkhornResult(
    plan: Vector[Vector[Double]],
    rowMarginals: Vector[Double],
    colMarginals: Vector[Double],
    iterations: Int
):
  def rowDeviation(a: Vector[Double]): Double =
    rowMarginals.zip(a).map { case (m, t) => math.abs(m - t) }.sum
  def colDeviation(b: Vector[Double]): Double =
    colMarginals.zip(b).map { case (m, t) => math.abs(m - t) }.sum

/** Entropic unbalanced optimal transport (KL marginal penalties), log-domain Sinkhorn.
  *
  * Solves `min_P ⟨P, C⟩ − ε H(P) + ρ_a KL(P1 | a) + ρ_b KL(Pᵀ1 | b)`. Kept as the transport
  * baseline the design record starts from; the HSMM is the engine.
  */
object UnbalancedSinkhorn:
  def solve(
      cost: Vector[Vector[Double]],
      a: Vector[Double],
      b: Vector[Double],
      config: SinkhornConfig = SinkhornConfig()
  ): SinkhornResult =
    val m = cost.size
    val n = if m == 0 then 0 else cost.head.size
    require(a.size == m && b.size == n, "marginal sizes must match the cost matrix")
    val eps = config.epsilon
    val logK = cost.map(_.map(c => -c / eps))
    val logA = a.map(x => if x > 0 then math.log(x) else Double.NegativeInfinity)
    val logB = b.map(x => if x > 0 then math.log(x) else Double.NegativeInfinity)
    val fa = config.rhoRows / (config.rhoRows + eps)
    val fb = config.rhoCols / (config.rhoCols + eps)
    var logU = Vector.fill(m)(0.0)
    var logV = Vector.fill(n)(0.0)
    var it = 0
    while it < config.iterations do
      logU = (0 until m).toVector.map { i =>
        val s = GraphHsmm.logSumExp((0 until n).toVector.map(j => logK(i)(j) + logV(j)))
        fa * (logA(i) - s)
      }
      logV = (0 until n).toVector.map { j =>
        val s = GraphHsmm.logSumExp((0 until m).toVector.map(i => logK(i)(j) + logU(i)))
        fb * (logB(j) - s)
      }
      it += 1
    val plan = (0 until m).toVector.map { i =>
      (0 until n).toVector.map(j => math.exp(logU(i) + logK(i)(j) + logV(j)))
    }
    SinkhornResult(
      plan,
      plan.map(_.sum),
      (0 until n).toVector.map(j => plan.map(_(j)).sum),
      it
    )

/** The embedding-plus-transport baseline: semantic cost only, no gating, no external states, one
  * column per candidate node. Reproduced so ablations can be run against it.
  *
  * Default configuration mirrors the failure mode it is meant to exhibit: every recall unit must be
  * placed (strong row penalty) while source nodes need not all be recalled (weak column penalty).
  */
object BaselineAligner:
  val defaultConfig: SinkhornConfig =
    SinkhornConfig(epsilon = 0.05, rhoRows = 10.0, rhoCols = 0.1, iterations = 300)

  def align(
      recall: RecallGraph,
      view: SourceView,
      candidates: Candidates,
      semantic: SemanticDistance,
      config: SinkhornConfig = defaultConfig
  ): AlignmentMatrix =
    val units = recall.ordered
    val columns = candidates.union
    if units.isEmpty || columns.isEmpty then
      AlignmentMatrix(units.map(u => AlignmentRow(u.id, Map.empty)))
    else
      val cost = units.map { u =>
        columns.map(c => view.node(c).map(n => semantic(u, n)).getOrElse(1.0))
      }
      val a = Vector.fill(units.size)(1.0)
      val b = Vector.fill(columns.size)(units.size.toDouble / columns.size.toDouble)
      val res = UnbalancedSinkhorn.solve(cost, a, b, config)
      AlignmentMatrix(units.zipWithIndex.map { case (u, i) =>
        AlignmentRow(
          u.id,
          columns.zipWithIndex.map { case (c, j) => AlignState.Source(c) -> res.plan(i)(j) }.toMap
        )
      })
