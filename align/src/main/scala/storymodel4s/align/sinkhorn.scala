package storymodel4s.align

import storymodel4s.recall.RecallGraph
import storymodel4s.recall.RecallGraphStatus.Checked

final case class SinkhornConfig(
    epsilon: Double = 0.1,
    rhoRows: Double = 1.0,
    rhoCols: Double = 1.0,
    maxIterations: Int = 200,
    tolerance: Double = 1e-6
)

final case class SinkhornResult(
    plan: Vector[Vector[Double]],
    rowMarginals: Vector[Double],
    colMarginals: Vector[Double],
    iterations: Int,
    converged: Boolean
):
  def rowDeviation(a: Vector[Double]): Double =
    rowMarginals.zip(a).map { case (m, t) => math.abs(m - t) }.sum
  def colDeviation(b: Vector[Double]): Double =
    colMarginals.zip(b).map { case (m, t) => math.abs(m - t) }.sum

/** Entropic unbalanced optimal transport (KL marginal penalties), log-domain Sinkhorn.
  *
  * Solves `min_P ⟨P, C⟩ − ε H(P) + ρ_a KL(P1 | a) + ρ_b KL(Pᵀ1 | b)`. Kept as the transport
  * baseline the design record starts from; the HSMM is the engine. Iterates until the largest
  * change of a scaling potential falls below `tolerance` or `maxIterations` is reached, and reports
  * both (review #29). A zero penalty means the corresponding marginal is unconstrained (potential
  * fixed at 0); a zero target mass forces the row/column to zero.
  *
  * Cost `+Inf` is a supported forbidden-edge encoding: `-c/ε` is `-Inf` and `exp` yields exact
  * `0.0`. That identity holds only after row and column masses are known finite, so those marginals
  * are validated first. Cost `NaN` and `-Inf` are refused. A row or column that is entirely `+Inf`
  * while its target mass is positive is refused, so "no admissible partner" cannot publish as the
  * same all-zero plan as "nothing to send" or "nothing found".
  */
object UnbalancedSinkhorn:
  def solve(
      cost: Vector[Vector[Double]],
      a: Vector[Double],
      b: Vector[Double],
      config: SinkhornConfig = SinkhornConfig()
  ): Either[AlignError, SinkhornResult] =
    val m = cost.size
    val n = if m == 0 then 0 else cost.head.size
    if a.size != m || b.size != n || cost.exists(_.size != n) then
      Left(AlignError.SizeMismatch("marginal sizes must match the cost matrix"))
    else
      UnbalancedSinkhorn.invalidFields(config) match
        case bad if bad.nonEmpty =>
          Left(
            AlignError.InvalidConfig(
              "SinkhornConfig",
              s"${bad.mkString(", ")}: epsilon > 0 finite, penalties ≥ 0 finite, " +
                "tolerance > 0 finite, maxIterations > 0 required"
            )
          )
        case _ =>
          UnbalancedSinkhorn.invalidTransportInputs(cost, a, b) match
            case Some(err) => Left(err)
            case None      =>
              val eps = config.epsilon
              val logK = cost.map(_.map(c => -c / eps))
              val logA = a.map(x => if x > 0 then math.log(x) else Double.NegativeInfinity)
              val logB = b.map(x => if x > 0 then math.log(x) else Double.NegativeInfinity)
              val fa = config.rhoRows / (config.rhoRows + eps)
              val fb = config.rhoCols / (config.rhoCols + eps)
              var logU = Vector.fill(m)(0.0)
              var logV = Vector.fill(n)(0.0)
              var it = 0
              var converged = false
              while it < config.maxIterations && !converged do
                val newU = (0 until m).toVector.map { i =>
                  if fa == 0.0 then 0.0
                  else if logA(i).isNegInfinity then Double.NegativeInfinity
                  else
                    val s = GraphHsmm.logSumExp((0 until n).toVector.map(j => logK(i)(j) + logV(j)))
                    fa * (logA(i) - s)
                }
                val newV = (0 until n).toVector.map { j =>
                  if fb == 0.0 then 0.0
                  else if logB(j).isNegInfinity then Double.NegativeInfinity
                  else
                    val s =
                      GraphHsmm.logSumExp((0 until m).toVector.map(i => logK(i)(j) + newU(i)))
                    fb * (logB(j) - s)
                }
                val delta = (newU.zip(logU) ++ newV.zip(logV))
                  .map { case (x, y) =>
                    if x.isNegInfinity && y.isNegInfinity then 0.0 else math.abs(x - y)
                  }
                  .maxOption
                  .getOrElse(0.0)
                logU = newU
                logV = newV
                it += 1
                if delta < config.tolerance then converged = true
              val plan = (0 until m).toVector.map { i =>
                (0 until n).toVector.map { j =>
                  val v = math.exp(logU(i) + logK(i)(j) + logV(j))
                  if v.isNaN then 0.0 else v
                }
              }
              Right(
                SinkhornResult(
                  plan,
                  plan.map(_.sum),
                  (0 until n).toVector.map(j => plan.map(_(j)).sum),
                  it,
                  converged
                )
              )

  /** Labels whose values fall outside the domain this implementation can solve. Fail-closed: a NaN
    * comparison is false, so NaN joins +Inf, 0, and negatives on the reject list. `rho == 0` is
    * lawful (unconstrained marginal). Cost and marginal values are [[invalidTransportInputs]].
    */
  private[align] def invalidFields(config: SinkhornConfig): List[String] =
    List(
      Option.unless(config.epsilon.isFinite && config.epsilon > 0.0)("epsilon"),
      Option.unless(config.rhoRows.isFinite && config.rhoRows >= 0.0)("rhoRows"),
      Option.unless(config.rhoCols.isFinite && config.rhoCols >= 0.0)("rhoCols"),
      Option.unless(config.tolerance.isFinite && config.tolerance > 0.0)("tolerance"),
      Option.unless(config.maxIterations > 0)("maxIterations")
    ).flatten

  /** Public cost/marginal class for [[solve]]. Fail-closed on every numeric door: a NaN comparison
    * is false, so `isFinite && >= 0` rejects NaN. `+Inf` cost is a forbidden edge, not a defect;
    * that encoding is exact only because finite nonnegative masses are required first.
    */
  private[align] def invalidTransportInputs(
      cost: Vector[Vector[Double]],
      a: Vector[Double],
      b: Vector[Double]
  ): Option[AlignError] =
    val n = if cost.isEmpty then 0 else cost.head.size
    val badCells =
      cost.zipWithIndex.flatMap { (row, i) =>
        row.zipWithIndex.collect {
          case (c, j) if c.isNaN || c.isNegInfinity => s"($i,$j)"
        }
      }
    if badCells.nonEmpty then
      Some(
        AlignError.InvalidConfig(
          "cost",
          s"${badCells.mkString(", ")}: NaN and -Inf refused; +Inf is a forbidden edge"
        )
      )
    else
      val badA = a.zipWithIndex.collect {
        case (x, i) if !(x.isFinite && x >= 0.0) => s"a($i)"
      }
      val badB = b.zipWithIndex.collect {
        case (x, j) if !(x.isFinite && x >= 0.0) => s"b($j)"
      }
      val badMass = badA ++ badB
      if badMass.nonEmpty then
        Some(
          AlignError.InvalidConfig(
            "marginals",
            s"${badMass.mkString(", ")}: finite and ≥ 0 required; +Inf-cost is exact only then"
          )
        )
      else
        val blockedRows = cost.zipWithIndex.collect {
          case (row, i) if a(i) > 0.0 && row.forall(_ == Double.PositiveInfinity) => i
        }
        val blockedCols = (0 until n).filter { j =>
          b(j) > 0.0 && cost.forall(row => row(j) == Double.PositiveInfinity)
        }
        if blockedRows.nonEmpty || blockedCols.nonEmpty then
          val bits =
            blockedRows.map(i => s"row $i") ++ blockedCols.map(j => s"column $j")
          Some(
            AlignError.InvalidConfig(
              "cost",
              s"${bits.mkString(", ")}: all +Inf with positive mass — no admissible partner"
            )
          )
        else None

/** The embedding-plus-transport baseline: semantic cost only, no gating, no external states, one
  * column per candidate node. Reproduced so ablations can be run against it.
  *
  * Default configuration mirrors the failure mode it is meant to exhibit: every recall unit must be
  * placed (strong row penalty) while source nodes need not all be recalled (weak column penalty).
  * An abstaining semantic provider is substituted by the same neutral `missingDistance` the HSMM
  * uses, so the ablation compares engines under one missingness policy (review #29).
  */
object BaselineAligner:
  val defaultConfig: SinkhornConfig =
    SinkhornConfig(epsilon = 0.05, rhoRows = 10.0, rhoCols = 0.1, maxIterations = 300)

  def align(
      recall: RecallGraph[Checked],
      view: SourceView,
      candidates: Candidates,
      semantic: SemanticDistance,
      config: SinkhornConfig = defaultConfig,
      missingDistance: Double = 0.5
  ): Either[AlignError, AlignmentMatrix] =
    val units = recall.ordered
    val columns = candidates.union
    if units.isEmpty then Left(AlignError.EmptyRecall)
    else if columns.isEmpty then
      Right(AlignmentMatrix(units.map(u => AlignmentRow(u.id, Map.empty))))
    else
      val cost = units.map { u =>
        columns.map(c =>
          view.node(c).map(n => semantic.orElse(u, n, missingDistance)).getOrElse(1.0)
        )
      }
      val a = Vector.fill(units.size)(1.0)
      val b = Vector.fill(columns.size)(units.size.toDouble / columns.size.toDouble)
      UnbalancedSinkhorn.solve(cost, a, b, config).map { res =>
        AlignmentMatrix(units.zipWithIndex.map { case (u, i) =>
          AlignmentRow(
            u.id,
            columns.zipWithIndex.map { case (c, j) =>
              AlignState.Source(c) -> res.plan(i)(j)
            }.toMap
          )
        })
      }
