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

  /** Sweep 3 slice: the Mirror door across every private-constructor case class in `features`.
    *
    * Companion `fromProduct` is probed above for the slice-C types. This asks the OTHER door --
    * `summon[Mirror.ProductOf[T]]` -- for the four `case class X private (...)` types that remain,
    * and it asks all four at once rather than assuming they behave like each other.
    *
    * Measured 2026-08-30 in `view`: 21 of 21 open. This records what `features` actually does.
    */
  test("sweep 3: Mirror.ProductOf door across features private-constructor case classes") {
    assertEquals(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SurfaceUnit]]"),
      Nil,
      "the summon control must compile or every result below is meaningless"
    )
    val results: List[(String, List[scala.compiletime.testing.Error])] = List(
      (
        "FeatureUseLedger",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.features.FeatureUseLedger]]"
        )
      ),
      (
        "BoundaryScore",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.features.BoundaryScore]]"
        )
      ),
      (
        "NarrativeWindowPlan",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.features.NarrativeWindowPlan]]"
        )
      ),
      (
        "DerivationGraph",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.features.DerivationGraph]]"
        )
      )
    )
    val forgeable = results.collect { case (n, errs) if errs.isEmpty => n }.toSet
    // Pins the KNOWN-BAD state so the suite stays green while bd-01M183VBPNEAPT5JNQMBMYMKQ9 tracks
    // it. Closing any of these makes this FAIL and asks for the name to be removed. Good news.
    assertEquals(
      forgeable,
      results.map(_._1).toSet,
      "sweep 3 expects the Mirror door OPEN on every features private-constructor case class; a " +
        "difference means one was closed (update this list) or a new one was added unmeasured"
    )
  }

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
