package storymodel4s.align

import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.RecallUnit

/** Emits a deterministic, synthetic JVM witness for the transport baseline.
  *
  * Kept in test scope because it reuses the checked AnnaFixture rather than making a second
  * story/recall construction path.
  */
object SinkhornJvmEvidence:
  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** JSON has no non-finite number literal. Preserve the solver's forbidden-edge encoding exactly
    * as a string so the emitted document remains parseable without silently changing input.
    */
  private def number(value: Double): String =
    if value == Double.PositiveInfinity then quote("+Infinity")
    else if value == Double.NegativeInfinity then quote("-Infinity")
    else if value.isNaN then quote("NaN")
    else value.toString

  private def doubles(values: Vector[Double]): String = values.map(number).mkString("[", ",", "]")
  private def matrix(values: Vector[Vector[Double]]): String = values.map(doubles).mkString("[", ",", "]")

  private def config(value: SinkhornConfig): String =
    s"{\"epsilon\":${value.epsilon},\"rhoRows\":${value.rhoRows},\"rhoCols\":${value.rhoCols},\"maxIterations\":${value.maxIterations},\"tolerance\":${value.tolerance}}"

  private def result(value: SinkhornResult): String =
    s"{\"plan\":${matrix(value.plan)},\"rowMarginals\":${doubles(value.rowMarginals)},\"colMarginals\":${doubles(value.colMarginals)},\"iterations\":${value.iterations},\"converged\":${value.converged}}"

  private def source(ref: SourceNodeRef): String = quote(ref.key)

  private def baselinePlan(
      rows: AlignmentMatrix,
      units: Vector[RecallUnit],
      columns: Vector[SourceNodeRef]
  ): String =
    val values = units.map { unit =>
      columns.map(column => rows.rows.find(_.unit == unit.id).get.mass(AlignState.Source(column)))
    }
    matrix(values)

  def main(args: Array[String]): Unit =
    val directCost = Vector(Vector(0.1, Double.PositiveInfinity), Vector(0.8, 0.2))
    val directA = Vector(1.0, 1.0)
    val directB = Vector(1.0, 1.0)
    val directConfig = SinkhornConfig(0.1, 1.0, 1.0, 200, 1e-9)
    val direct = UnbalancedSinkhorn.solve(directCost, directA, directB, directConfig).toOption.get
    val zeroPriorZeroPenaltyCost = Vector(Vector(0.1, 0.9), Vector(0.8, 0.2))
    val zeroPriorZeroPenaltyA = Vector(1.0, 0.0)
    val zeroPriorZeroPenaltyB = Vector(1.0, 1.0)
    val zeroPriorZeroPenaltyConfig = SinkhornConfig(0.1, 0.0, 1.0, 200, 1e-9)
    val zeroPriorZeroPenalty = UnbalancedSinkhorn
      .solve(
        zeroPriorZeroPenaltyCost,
        zeroPriorZeroPenaltyA,
        zeroPriorZeroPenaltyB,
        zeroPriorZeroPenaltyConfig
      )
      .toOption
      .get
    val zeroOnlyCost = Vector(
      Vector(Double.PositiveInfinity, 0.2),
      Vector(0.2, Double.PositiveInfinity)
    )
    val zeroOnlyA = Vector(1.0, 0.0)
    val zeroOnlyB = Vector(1.0, 1.0)
    val zeroOnlyConfig = SinkhornConfig(0.1, 1.0, 1.0, 200, 1e-9)
    val zeroOnly = UnbalancedSinkhorn
      .solve(zeroOnlyCost, zeroOnlyA, zeroOnlyB, zeroOnlyConfig)
      .toOption
      .get

    import AnnaFixture.{e4, e5, recall, view}
    val units = recall.ordered
    val nominated = Map(
      units(0).id -> Vector(e4),
      units(1).id -> Vector(e5),
      units(2).id -> Vector(e5),
      units(3).id -> Vector(e4)
    )
    val candidates = Candidates.of(nominated)
    val columns = candidates.union
    val semantic = SemanticDistance { (unit, node) =>
      if unit.id == units.head.id && node.ref == e4 then Estimate.Ineligible
      else Estimate.missing(MissingReason.ProviderAbstained)
    }
    val missingDistance = 0.5
    val baselineCost = units.map { unit =>
      columns.map { column =>
        semantic(unit, view.node(column).get) match
          case Estimate.Ineligible     => Double.PositiveInfinity
          case Estimate.Observed(v, _) => v
          case Estimate.Missing(_)     => missingDistance
      }
    }
    val baselineA = Vector.fill(units.size)(1.0)
    val baselineB = Vector.fill(columns.size)(units.size.toDouble / columns.size.toDouble)
    val baseline = BaselineAligner
      .align(recall, view, candidates, semantic, missingDistance = missingDistance)
      .toOption
      .get
    val baselineRaw = UnbalancedSinkhorn
      .solve(baselineCost, baselineA, baselineB, BaselineAligner.defaultConfig)
      .toOption
      .get

    val nominations = units.map { unit =>
      val refs = nominated(unit.id).map(source).mkString("[", ",", "]")
      s"{\"unit\":${quote(unit.id.value)},\"nominated\":$refs}"
    }.mkString("[", ",", "]")
    val union = columns.map(source).mkString("[", ",", "]")
    println(
      s"{\"schema\":\"sinkhorn-jvm-oracle/v1\",\"direct\":{\"cost\":${matrix(directCost)},\"a\":${doubles(directA)},\"b\":${doubles(directB)},\"config\":${config(directConfig)},\"result\":${result(direct)}},\"zeroPriorZeroPenalty\":{\"cost\":${matrix(zeroPriorZeroPenaltyCost)},\"a\":${doubles(zeroPriorZeroPenaltyA)},\"b\":${doubles(zeroPriorZeroPenaltyB)},\"config\":${config(zeroPriorZeroPenaltyConfig)},\"result\":${result(zeroPriorZeroPenalty)}},\"positiveColumnOnlyZeroRow\":{\"cost\":${matrix(zeroOnlyCost)},\"a\":${doubles(zeroOnlyA)},\"b\":${doubles(zeroOnlyB)},\"config\":${config(zeroOnlyConfig)},\"result\":${result(zeroOnly)}},\"baseline\":{\"perUnitNominations\":$nominations,\"union\":$union,\"cost\":${matrix(baselineCost)},\"a\":${doubles(baselineA)},\"b\":${doubles(baselineB)},\"config\":${config(BaselineAligner.defaultConfig)},\"missingDistance\":$missingDistance,\"result\":${result(baselineRaw)},\"alignPlan\":${baselinePlan(baseline, units, columns)}}}"
    )
