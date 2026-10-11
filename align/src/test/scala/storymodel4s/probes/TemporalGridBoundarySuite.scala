package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class TemporalGridBoundarySuite extends FunSuite:

  test("TemporalGrid constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(prepared: TemporalQuery.Prepared, regions: Vector[TemporalQuery.Region], cells: Map[Int, Vector[TemporalQuery.Contribution]], allocated: Map[Int, Double], bounds: Map[Int, (Double, Double)], allocatedOutside: Double, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : TemporalQuery.Prepared, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], 0.0, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new TemporalGrid(??? : TemporalQuery.Prepared, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], 0.0, ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalGrid apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(prepared: TemporalQuery.Prepared, regions: Vector[TemporalQuery.Region], cells: Map[Int, Vector[TemporalQuery.Contribution]], allocated: Map[Int, Double], bounds: Map[Int, (Double, Double)], allocatedOutside: Double, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : TemporalQuery.Prepared, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], 0.0, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; TemporalGrid(??? : TemporalQuery.Prepared, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], 0.0, ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalGrid copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(prepared: TemporalQuery.Prepared, regions: Vector[TemporalQuery.Region], cells: Map[Int, Vector[TemporalQuery.Contribution]], allocated: Map[Int, Double], bounds: Map[Int, (Double, Double)], allocatedOutside: Double, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; (??? : TemporalGrid).copy()"""
      ).nonEmpty
    )
  }

  test("TemporalGrid fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(prepared: TemporalQuery.Prepared, regions: Vector[TemporalQuery.Region], cells: Map[Int, Vector[TemporalQuery.Contribution]], allocated: Map[Int, Double], bounds: Map[Int, (Double, Double)], allocatedOutside: Double, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : TemporalQuery.Prepared, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], 0.0, ??? : Checksum))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; TemporalGrid.fromProduct((??? : TemporalQuery.Prepared, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], 0.0, ??? : Checksum))"""
      ).nonEmpty
    )
  }

  test("TemporalGrid Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(prepared: TemporalQuery.Prepared, regions: Vector[TemporalQuery.Region], cells: Map[Int, Vector[TemporalQuery.Contribution]], allocated: Map[Int, Double], bounds: Map[Int, (Double, Double)], allocatedOutside: Double, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[TemporalGrid]]"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Window constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(readout: TemporalQuery.Readout, width: BigInt, target: Option[TemporalConcentration.Target], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : TemporalQuery.Readout, BigInt(1), ??? : Option[TemporalConcentration.Target], ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new TemporalConcentration.Window(??? : TemporalQuery.Readout, BigInt(1), ??? : Option[TemporalConcentration.Target], ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Window apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(readout: TemporalQuery.Readout, width: BigInt, target: Option[TemporalConcentration.Target], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : TemporalQuery.Readout, BigInt(1), ??? : Option[TemporalConcentration.Target], ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; TemporalConcentration.Window(??? : TemporalQuery.Readout, BigInt(1), ??? : Option[TemporalConcentration.Target], ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Window copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(readout: TemporalQuery.Readout, width: BigInt, target: Option[TemporalConcentration.Target], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; (??? : TemporalConcentration.Window).copy()"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Window fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(readout: TemporalQuery.Readout, width: BigInt, target: Option[TemporalConcentration.Target], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : TemporalQuery.Readout, BigInt(1), ??? : Option[TemporalConcentration.Target], ??? : Checksum))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; TemporalConcentration.Window.fromProduct((??? : TemporalQuery.Readout, BigInt(1), ??? : Option[TemporalConcentration.Target], ??? : Checksum))"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Window Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(readout: TemporalQuery.Readout, width: BigInt, target: Option[TemporalConcentration.Target], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[TemporalConcentration.Window]]"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Disconnected constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, selected: Vector[Int], quantity: Double, target: TemporalConcentration.Target, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : TemporalGrid, Vector(0), 0.0, ??? : TemporalConcentration.Target, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new TemporalConcentration.Disconnected(??? : TemporalGrid, Vector(0), 0.0, ??? : TemporalConcentration.Target, ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Disconnected apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, selected: Vector[Int], quantity: Double, target: TemporalConcentration.Target, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : TemporalGrid, Vector(0), 0.0, ??? : TemporalConcentration.Target, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; TemporalConcentration.Disconnected(??? : TemporalGrid, Vector(0), 0.0, ??? : TemporalConcentration.Target, ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Disconnected copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, selected: Vector[Int], quantity: Double, target: TemporalConcentration.Target, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; (??? : TemporalConcentration.Disconnected).copy()"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Disconnected fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, selected: Vector[Int], quantity: Double, target: TemporalConcentration.Target, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : TemporalGrid, Vector(0), 0.0, ??? : TemporalConcentration.Target, ??? : Checksum))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; TemporalConcentration.Disconnected.fromProduct((??? : TemporalGrid, Vector(0), 0.0, ??? : TemporalConcentration.Target, ??? : Checksum))"""
      ).nonEmpty
    )
  }

  test("TemporalConcentration.Disconnected Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, selected: Vector[Int], quantity: Double, target: TemporalConcentration.Target, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[TemporalConcentration.Disconnected]]"""
      ).nonEmpty
    )
  }
