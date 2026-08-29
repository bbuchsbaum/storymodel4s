package storymodel4s.featuresprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Outside `storymodel4s.features`: the three slice-C types have no case-class construction bypass.
  *
  * Demonstrated on the pre-seal case classes in this worktree, before the constructor closed:
  * `SidecarManifest.fromProduct((space, 0, 10, Float32, checksum, RowMajor))` produced dimension=0
  * while `of` refused it; `FeatureRef.fromProduct((target, space, -1))` produced row=-1;
  * `FeatureDerivation.fromProduct` with both a surface window and a narrative window produced a
  * recipe `of` refuses. Same-shape control is a remaining public features case class (`Sample`),
  * not Coverage and not a tuple.
  */
class ConstructionBoundarySuite extends FunSuite:

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what still exposes fromProduct")

  test("positive control: a remaining features case class still has fromProduct") {
    val errors = typeCheckErrors(
      """storymodel4s.features.Sample.fromProduct((
           0,
           storymodel4s.features.Estimate.observed(1.0),
           1.0
         ))"""
    )
    assertEquals(
      errors,
      Nil,
      s"if Sample.fromProduct fails to typecheck, every refusal beside it is meaningless:\n${errors.mkString("\n")}"
    )
  }

  test("SidecarManifest has no derived fromProduct bypass") {
    refused(
      typeCheckErrors("storymodel4s.features.SidecarManifest.fromProduct(EmptyTuple)"),
      "SidecarManifest.fromProduct"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.features.SidecarManifest]]"
      ),
      "SidecarManifest Mirror.ProductOf"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.features.SidecarManifest) => x.copy()"),
      "SidecarManifest.copy"
    )
  }

  test("FeatureRef has no derived fromProduct bypass") {
    refused(
      typeCheckErrors("storymodel4s.features.FeatureRef.fromProduct(EmptyTuple)"),
      "FeatureRef.fromProduct"
    )
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.features.FeatureRef]]"),
      "FeatureRef Mirror.ProductOf"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.features.FeatureRef) => x.copy()"),
      "FeatureRef.copy"
    )
  }

  test("FeatureDerivation has no derived fromProduct bypass") {
    refused(
      typeCheckErrors("storymodel4s.features.FeatureDerivation.fromProduct(EmptyTuple)"),
      "FeatureDerivation.fromProduct"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.features.FeatureDerivation]]"
      ),
      "FeatureDerivation Mirror.ProductOf"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.features.FeatureDerivation) => x.copy()"),
      "FeatureDerivation.copy"
    )
  }

  test("public reads and field-level factories remain available") {
    val reads = typeCheckErrors(
      """val space = storymodel4s.core.FeatureSpaceId.unsafe("space:probe")
        val manifest = storymodel4s.features.SidecarManifest.of(
          space, 1, 1, storymodel4s.features.Dtype.Float32,
          storymodel4s.core.Checksum.ofText("probe")
        )
        val ref = storymodel4s.features.FeatureRef.of(
          storymodel4s.features.FeatureTarget.Token(storymodel4s.core.TokenIndex.unsafe(0)),
          space,
          0
        )
        val _ = (manifest.map(_.dimension), ref.map(_.row))"""
    )
    assertEquals(reads, Nil)
  }
