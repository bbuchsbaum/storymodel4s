package storymodel4s.consumer

import scala.compiletime.testing.typeCheckErrors
import scala.deriving.Mirror

import munit.FunSuite

/** Same-file control: a plain case class is still forgeable through `Mirror.Product`. */
final case class AlignForgeControl(temperature: Double, refinementPasses: Int)

/** Outside `storymodel4s.align`: validating align values expose observations, not construction. */
class AlignBoundarySuite extends FunSuite:

  test("positive control: a case class Mirror.fromProduct still compiles") {
    val errors = typeCheckErrors(
      """summon[scala.deriving.Mirror.ProductOf[storymodel4s.consumer.AlignForgeControl]]
           .fromProduct((0.0, -1))"""
    )
    assertEquals(errors, Nil, "control Mirror.fromProduct must compile or the probe cannot fail")
    val forged = summon[Mirror.ProductOf[AlignForgeControl]].fromProduct((0.0, -1))
    assertEquals(forged.temperature, 0.0)
    assertEquals(forged.refinementPasses, -1)
  }

  test("AlignmentRow has no Mirror.ProductOf or fromProduct bypass") {
    val mirror = typeCheckErrors(
      "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.AlignmentRow]]"
    )
    val fromProduct = typeCheckErrors(
      "storymodel4s.align.AlignmentRow.fromProduct(EmptyTuple)"
    )
    assert(mirror.nonEmpty, "Mirror.ProductOf reconstructed an unchecked AlignmentRow")
    assert(fromProduct.nonEmpty, "fromProduct reconstructed an unchecked AlignmentRow")
  }

  test("AlignmentMatrix has no Mirror.ProductOf or fromProduct bypass") {
    val mirror = typeCheckErrors(
      "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.AlignmentMatrix]]"
    )
    val fromProduct = typeCheckErrors(
      "storymodel4s.align.AlignmentMatrix.fromProduct(EmptyTuple)"
    )
    assert(mirror.nonEmpty, "Mirror.ProductOf reconstructed an unchecked AlignmentMatrix")
    assert(fromProduct.nonEmpty, "fromProduct reconstructed an unchecked AlignmentMatrix")
  }

  test("HsmmConfig has no Mirror.ProductOf or fromProduct bypass") {
    val mirror = typeCheckErrors(
      "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.HsmmConfig]]"
    )
    val fromProduct = typeCheckErrors(
      "storymodel4s.align.HsmmConfig.fromProduct(EmptyTuple)"
    )
    val ctor = typeCheckErrors(
      """new storymodel4s.align.HsmmConfig(
           0.0,
           storymodel4s.align.TransitionModel.default,
           -1,
           -1.0
         )"""
    )
    assert(mirror.nonEmpty, "Mirror.ProductOf reconstructed an unchecked HsmmConfig")
    assert(fromProduct.nonEmpty, "fromProduct reconstructed an unchecked HsmmConfig")
    assert(ctor.nonEmpty, "the private HsmmConfig constructor was public")
  }

  test("CostWeights has no Mirror.ProductOf or fromProduct bypass") {
    val mirror = typeCheckErrors(
      "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.CostWeights]]"
    )
    val fromProduct = typeCheckErrors(
      "storymodel4s.align.CostWeights.fromProduct(EmptyTuple)"
    )
    val ctor = typeCheckErrors(
      "new storymodel4s.align.CostWeights(1, -1, 0, 0, 0, 0, 0.5, 0.5)"
    )
    assert(mirror.nonEmpty, "Mirror.ProductOf reconstructed an unchecked CostWeights")
    assert(fromProduct.nonEmpty, "fromProduct reconstructed an unchecked CostWeights")
    assert(ctor.nonEmpty, "the private CostWeights constructor was public")
  }

  test("WeightedCoverage has no construction, Product, copy, or Mirror bypass") {
    val mirror = typeCheckErrors(
      "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.WeightedCoverage]]"
    )
    val fromProduct = typeCheckErrors(
      "storymodel4s.align.WeightedCoverage.fromProduct(EmptyTuple)"
    )
    val ctor = typeCheckErrors(
      "new storymodel4s.align.WeightedCoverage(???, 0.0, storymodel4s.features.Coverage.empty)"
    )
    val copy = typeCheckErrors(
      "(w: storymodel4s.align.WeightedCoverage) => w.copy()"
    )
    val product = typeCheckErrors(
      "(w: storymodel4s.align.WeightedCoverage) => w: Product"
    )
    assert(mirror.nonEmpty, "Mirror.ProductOf reconstructed an unchecked WeightedCoverage")
    assert(fromProduct.nonEmpty, "fromProduct reconstructed an unchecked WeightedCoverage")
    assert(ctor.nonEmpty, "the private WeightedCoverage constructor was public")
    assert(copy.nonEmpty, "copy edited WeightedCoverage without revalidation")
    assert(product.nonEmpty, "WeightedCoverage remained a Product")
  }

  test("ImportanceWeight has no Product, Mirror, copy, constructor, or fromProduct bypass") {
    val probes = List(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.ImportanceWeight]]"
      ),
      typeCheckErrors("storymodel4s.align.ImportanceWeight.fromProduct(EmptyTuple)"),
      typeCheckErrors(
        "new storymodel4s.align.ImportanceWeight(storymodel4s.features.Estimate.observed(0.0))"
      ),
      typeCheckErrors("(w: storymodel4s.align.ImportanceWeight) => w.copy()"),
      typeCheckErrors("(w: storymodel4s.align.ImportanceWeight) => w: Product")
    )
    probes.foreach(errors => assert(errors.nonEmpty, "ImportanceWeight construction bypassed"))
  }

  test("NodeSummary has no Product, Mirror, constructor, or fromProduct bypass") {
    val probes = List(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.NodeSummary]]"
      ),
      typeCheckErrors("storymodel4s.align.NodeSummary.fromProduct(EmptyTuple)"),
      typeCheckErrors(
        """new storymodel4s.align.NodeSummary(
          ???, ???, ???, ???, ???, ???, ???, ???,
          ???, ???, ???, ???, ???, ???, ???, ???
        )"""
      ),
      typeCheckErrors("(n: storymodel4s.align.NodeSummary) => n: Product")
    )
    probes.foreach(errors => assert(errors.nonEmpty, "NodeSummary construction bypassed"))
  }

  test("NodeSummary checked copy cannot smuggle a raw unvalidated importance estimate") {
    val copy = typeCheckErrors(
      """(n: storymodel4s.align.NodeSummary) =>
        n.copy(importance = storymodel4s.features.Estimate.observed(Double.NaN))"""
    )
    assert(copy.nonEmpty, "NodeSummary.copy admitted a raw, unvalidated importance estimate")
  }

  test("ImportanceWeight unsafe validates rather than bypassing the boundary") {
    intercept[IllegalArgumentException] {
      storymodel4s.align.ImportanceWeight.unsafe(
        storymodel4s.features.Estimate.observed(Double.NaN)
      )
    }
  }

  test("public reads and validated factories remain available") {
    val reads = List(
      typeCheckErrors(
        """val row = null.asInstanceOf[storymodel4s.align.AlignmentRow]
          row.unit -> row.mass.size -> row.total"""
      ),
      typeCheckErrors(
        """val matrix = null.asInstanceOf[storymodel4s.align.AlignmentMatrix]
          matrix.rows.size + matrix.size"""
      ),
      typeCheckErrors(
        """val cfg = storymodel4s.align.HsmmConfig.default
          val _obs = (cfg.temperature, cfg.refinementPasses, cfg.refinementWeight, cfg.transitions)"""
      ),
      typeCheckErrors(
        """val w = storymodel4s.align.CostWeights.default
          val _obs = w(storymodel4s.align.CostTerm.Semantic) + w.semantic"""
      ),
      typeCheckErrors("storymodel4s.align.HsmmConfig.of(temperature = 0.0)"),
      typeCheckErrors("storymodel4s.align.CostWeights.of(1, -1, 0, 0, 0, 0)"),
      typeCheckErrors("storymodel4s.align.WeightedCoverage.of(0.0, Vector.empty, 0)"),
      typeCheckErrors(
        "storymodel4s.align.ImportanceWeight.observed(0.0).map(_.toOption)"
      )
    )
    reads.zipWithIndex.foreach { (errors, i) =>
      assertEquals(errors, Nil, s"public read/factory $i must compile")
    }
  }
