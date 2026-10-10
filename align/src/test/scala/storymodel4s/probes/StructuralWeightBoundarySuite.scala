package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Independent-package probes preserve the checked weighted execution boundary. */
class StructuralWeightBoundarySuite extends FunSuite:

  test("StructuralWeight constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(numerator: Long, denominator: Long); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(0L, 1L)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors("""import storymodel4s.align.*; new StructuralWeight(0L, 1L)""").nonEmpty
    )
  }

  test("StructuralWeight apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(numerator: Long, denominator: Long); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(0L, 1L)"""
      ),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*; StructuralWeight(0L, 1L)""").nonEmpty)
  }

  test("StructuralWeight copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(numerator: Long, denominator: Long); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors("""import storymodel4s.align.*; (??? : StructuralWeight).copy()""").nonEmpty
    )
  }

  test("StructuralWeight fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(numerator: Long, denominator: Long); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple2(0L, 1L))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; StructuralWeight.fromProduct(Tuple2(0L, 1L))"""
      ).nonEmpty
    )
  }

  test("StructuralWeight mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(numerator: Long, denominator: Long); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StructuralWeight]]"""
      ).nonEmpty
    )
  }

  test("StructuralProfile constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(weight: StructuralWeight, inference: HsmmConfig, preset: StructuredReconstruction.Profile); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : StructuralWeight, ??? : HsmmConfig, ??? : StructuredReconstruction.Profile)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; new StructuralProfile(??? : StructuralWeight, ??? : HsmmConfig, ??? : StructuredReconstruction.Profile)"""
      ).nonEmpty
    )
  }

  test("StructuralProfile apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(weight: StructuralWeight, inference: HsmmConfig, preset: StructuredReconstruction.Profile); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : StructuralWeight, ??? : HsmmConfig, ??? : StructuredReconstruction.Profile)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; StructuralProfile(??? : StructuralWeight, ??? : HsmmConfig, ??? : StructuredReconstruction.Profile)"""
      ).nonEmpty
    )
  }

  test("StructuralProfile copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(weight: StructuralWeight, inference: HsmmConfig, preset: StructuredReconstruction.Profile); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors("""import storymodel4s.align.*; (??? : StructuralProfile).copy()""").nonEmpty
    )
  }

  test("StructuralProfile fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(weight: StructuralWeight, inference: HsmmConfig, preset: StructuredReconstruction.Profile); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple3(??? : StructuralWeight, ??? : HsmmConfig, ??? : StructuredReconstruction.Profile))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; StructuralProfile.fromProduct(Tuple3(??? : StructuralWeight, ??? : HsmmConfig, ??? : StructuredReconstruction.Profile))"""
      ).nonEmpty
    )
  }

  test("StructuralProfile mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(weight: StructuralWeight, inference: HsmmConfig, preset: StructuredReconstruction.Profile); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StructuralProfile]]"""
      ).nonEmpty
    )
  }

  test("StructuralSelection constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(execution: MappingRun.Execution, profile: StructuralProfile, inference: Option[HsmmResult]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : MappingRun.Execution, ??? : StructuralProfile, ??? : Option[HsmmResult])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; new StructuralSelection(??? : MappingRun.Execution, ??? : StructuralProfile, ??? : Option[HsmmResult])"""
      ).nonEmpty
    )
  }

  test("StructuralSelection apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(execution: MappingRun.Execution, profile: StructuralProfile, inference: Option[HsmmResult]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : MappingRun.Execution, ??? : StructuralProfile, ??? : Option[HsmmResult])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; StructuralSelection(??? : MappingRun.Execution, ??? : StructuralProfile, ??? : Option[HsmmResult])"""
      ).nonEmpty
    )
  }

  test("StructuralSelection copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(execution: MappingRun.Execution, profile: StructuralProfile, inference: Option[HsmmResult]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; (??? : StructuralSelection).copy()"""
      ).nonEmpty
    )
  }

  test("StructuralSelection fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(execution: MappingRun.Execution, profile: StructuralProfile, inference: Option[HsmmResult]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple3(??? : MappingRun.Execution, ??? : StructuralProfile, ??? : Option[HsmmResult]))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; StructuralSelection.fromProduct(Tuple3(??? : MappingRun.Execution, ??? : StructuralProfile, ??? : Option[HsmmResult]))"""
      ).nonEmpty
    )
  }

  test("StructuralSelection mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(execution: MappingRun.Execution, profile: StructuralProfile, inference: Option[HsmmResult]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StructuralSelection]]"""
      ).nonEmpty
    )
  }
