package storymodel4s.codec

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.view.*
import scala.compiletime.testing.typeCheckErrors

class TemporalGridViewSuite extends FunSuite:
  test("sparse view keeps producer values, qualified navigation and omitted top-k accounting") {
    val readout = TemporalQueryFixture.readout()
    val grid = TemporalGrid.checked(readout.prepared, Vector(readout.region)).toOption.get
    val view = TemporalGridView.from(grid)
    assertEquals(view.grid, grid)
    val cell = grid.cells(0).head
    val target = cell.component.alternative.destination.asInstanceOf[Destination.Target].ref
    assertEquals(view.cellsFor(Checksum.ofText("foreign"), target), None)
    assertEquals(view.cellsFor(grid.prepared.mapping.digest, target).get(0).head, cell)
    assert(view.mark(0, cell.component.alternative).nonEmpty)
    assertEquals(view.mark(10, cell.component.alternative), None)
    assertEquals(view.topK(0, 0).toOption.get.omittedWeight, grid.prepared.ledger.suppliedTotal)
    assertEquals(view.topK(0, 0).toOption.get.omittedAllocatedRegionMass, grid.allocatedAt(0).get)
    assertEquals(view.topK(2, 0), Left(TemporalGrid.Refusal.InvalidGrid))
  }

  test("view constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, digest: Checksum); new Control(??? : TemporalGrid, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """new storymodel4s.view.TemporalGridView(??? : storymodel4s.align.TemporalGrid, ??? : storymodel4s.core.Checksum)"""
      ).nonEmpty
    )
  }
  test("view apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, digest: Checksum); Control(??? : TemporalGrid, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.view.TemporalGridView(??? : storymodel4s.align.TemporalGrid, ??? : storymodel4s.core.Checksum)"""
      ).nonEmpty
    )
  }
  test("view copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, digest: Checksum); (??? : Control).copy()"""
      ),
      Nil
    )
    assert(typeCheckErrors("""(??? : storymodel4s.view.TemporalGridView).copy()""").nonEmpty)
  }
  test("view fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : TemporalGrid, ??? : Checksum))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.view.TemporalGridView.fromProduct((??? : storymodel4s.align.TemporalGrid, ??? : storymodel4s.core.Checksum))"""
      ).nonEmpty
    )
  }
  test("view Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(grid: TemporalGrid, digest: Checksum); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.TemporalGridView]]"""
      ).nonEmpty
    )
  }
