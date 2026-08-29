package storymodel4s.featuresprobe

import scala.compiletime.testing.typeCheckErrors
import scala.deriving.Mirror

import munit.FunSuite

/** Same-file control: a plain case class is still forgeable through `Mirror.Product`. */
final case class CoverageForgeControl(eligible: Int, observed: Int)

/** Outside `storymodel4s.features`: Coverage exposes counts, not a construction bypass.
  *
  * Demonstrated on the pre-seal case class (`CoverageForgeSuite`, worktree before this file):
  * `Coverage.fromProduct((0, 5))` yielded eligible=0, observed=5, missing=-5, fraction=0.0 (the
  * empty-eligible guard hid the violation), and `forged + unsafe(2, 1)` produced eligible=2,
  * observed=6 — a value `Coverage.of` refuses.
  */
class CoverageBoundarySuite extends FunSuite:

  test("positive control: a case class Mirror.fromProduct forges observed > eligible") {
    val errors = typeCheckErrors(
      """summon[scala.deriving.Mirror.ProductOf[storymodel4s.featuresprobe.CoverageForgeControl]].fromProduct((0, 5))"""
    )
    assertEquals(errors, Nil, "control Mirror.fromProduct must compile or the probe cannot fail")
    val forged = summon[Mirror.ProductOf[CoverageForgeControl]].fromProduct((0, 5))
    assertEquals(forged.eligible, 0)
    assertEquals(forged.observed, 5)
  }

  test("Coverage has no Mirror.ProductOf or fromProduct bypass") {
    val mirror = typeCheckErrors(
      "summon[scala.deriving.Mirror.ProductOf[storymodel4s.features.Coverage]]"
    )
    val fromProduct = typeCheckErrors("storymodel4s.features.Coverage.fromProduct((0, 5))")
    assert(mirror.nonEmpty, "Mirror.ProductOf[Coverage] reconstructed an unchecked Coverage")
    assert(fromProduct.nonEmpty, "fromProduct reconstructed an unchecked Coverage")
  }

  test("Coverage constructor is not callable outside features") {
    val errors = typeCheckErrors("new storymodel4s.features.Coverage(0, 5)")
    assert(errors.nonEmpty, "the private Coverage constructor was public")
  }

  test("public reads and validated factories remain available") {
    val reads = typeCheckErrors(
      """val value = storymodel4s.features.Coverage.unsafe(4, 1)
        val _ = (value.eligible, value.observed, value.fraction, value.missing, value.isEmpty)
        val _sum = value + storymodel4s.features.Coverage.empty
        val _of = storymodel4s.features.Coverage.of(1, 2)"""
    )
    assertEquals(reads, Nil)
  }
