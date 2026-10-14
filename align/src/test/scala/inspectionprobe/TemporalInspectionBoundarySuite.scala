package inspectionprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class TemporalInspectionBoundarySuite extends FunSuite:
  test("TemporalQuery.Inspection refuses constructor outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallUnitId, v1: TemporalQuery.Measure, v2: TemporalSupport.Coordinate, v3: Vector[TemporalQuery.Component], v4: TemporalQuery.Ledger, v5: Checksum); new Control(??? : RecallUnitId, ??? : TemporalQuery.Measure, ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Component], ??? : TemporalQuery.Ledger, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalQuery.Inspection(??? : RecallUnitId, ??? : TemporalQuery.Measure, ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Component], ??? : TemporalQuery.Ledger, ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.Inspection refuses apply outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallUnitId, v1: TemporalQuery.Measure, v2: TemporalSupport.Coordinate, v3: Vector[TemporalQuery.Component], v4: TemporalQuery.Ledger, v5: Checksum); Control(??? : RecallUnitId, ??? : TemporalQuery.Measure, ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Component], ??? : TemporalQuery.Ledger, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalQuery.Inspection(??? : RecallUnitId, ??? : TemporalQuery.Measure, ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Component], ??? : TemporalQuery.Ledger, ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.Inspection refuses copy outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallUnitId, v1: TemporalQuery.Measure, v2: TemporalSupport.Coordinate, v3: Vector[TemporalQuery.Component], v4: TemporalQuery.Ledger, v5: Checksum); (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalQuery.Inspection).copy()"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.Inspection refuses fromProduct outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallUnitId, v1: TemporalQuery.Measure, v2: TemporalSupport.Coordinate, v3: Vector[TemporalQuery.Component], v4: TemporalQuery.Ledger, v5: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalQuery.Inspection.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.Inspection refuses Mirror outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallUnitId, v1: TemporalQuery.Measure, v2: TemporalSupport.Coordinate, v3: Vector[TemporalQuery.Component], v4: TemporalQuery.Ledger, v5: Checksum); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalQuery.Inspection]]"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionReadout refuses constructor outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: TemporalQuery.Region, v2: Vector[TemporalQuery.Contribution], v3: TemporalQuery.Bounds, v4: Double, v5: Checksum); new Control(??? : TemporalQuery.Inspection, ??? : TemporalQuery.Region, ??? : Vector[TemporalQuery.Contribution], ??? : TemporalQuery.Bounds, ??? : Double, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalQuery.InspectionReadout(??? : TemporalQuery.Inspection, ??? : TemporalQuery.Region, ??? : Vector[TemporalQuery.Contribution], ??? : TemporalQuery.Bounds, ??? : Double, ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionReadout refuses apply outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: TemporalQuery.Region, v2: Vector[TemporalQuery.Contribution], v3: TemporalQuery.Bounds, v4: Double, v5: Checksum); Control(??? : TemporalQuery.Inspection, ??? : TemporalQuery.Region, ??? : Vector[TemporalQuery.Contribution], ??? : TemporalQuery.Bounds, ??? : Double, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalQuery.InspectionReadout(??? : TemporalQuery.Inspection, ??? : TemporalQuery.Region, ??? : Vector[TemporalQuery.Contribution], ??? : TemporalQuery.Bounds, ??? : Double, ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionReadout refuses copy outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: TemporalQuery.Region, v2: Vector[TemporalQuery.Contribution], v3: TemporalQuery.Bounds, v4: Double, v5: Checksum); (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalQuery.InspectionReadout).copy()"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionReadout refuses fromProduct outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: TemporalQuery.Region, v2: Vector[TemporalQuery.Contribution], v3: TemporalQuery.Bounds, v4: Double, v5: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalQuery.InspectionReadout.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionReadout refuses Mirror outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: TemporalQuery.Region, v2: Vector[TemporalQuery.Contribution], v3: TemporalQuery.Bounds, v4: Double, v5: Checksum); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalQuery.InspectionReadout]]"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionRanked refuses constructor outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.InspectionReadout, v1: Vector[TemporalQuery.Contribution], v2: Double, v3: Double); new Control(??? : TemporalQuery.InspectionReadout, ??? : Vector[TemporalQuery.Contribution], ??? : Double, ??? : Double)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalQuery.InspectionRanked(??? : TemporalQuery.InspectionReadout, ??? : Vector[TemporalQuery.Contribution], ??? : Double, ??? : Double)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionRanked refuses apply outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.InspectionReadout, v1: Vector[TemporalQuery.Contribution], v2: Double, v3: Double); Control(??? : TemporalQuery.InspectionReadout, ??? : Vector[TemporalQuery.Contribution], ??? : Double, ??? : Double)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalQuery.InspectionRanked(??? : TemporalQuery.InspectionReadout, ??? : Vector[TemporalQuery.Contribution], ??? : Double, ??? : Double)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionRanked refuses copy outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.InspectionReadout, v1: Vector[TemporalQuery.Contribution], v2: Double, v3: Double); (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalQuery.InspectionRanked).copy()"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionRanked refuses fromProduct outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.InspectionReadout, v1: Vector[TemporalQuery.Contribution], v2: Double, v3: Double); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalQuery.InspectionRanked.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionRanked refuses Mirror outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.InspectionReadout, v1: Vector[TemporalQuery.Contribution], v2: Double, v3: Double); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalQuery.InspectionRanked]]"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionPartition refuses constructor outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.InspectionReadout], v2: Double); new Control(??? : TemporalQuery.Inspection, ??? : Vector[TemporalQuery.InspectionReadout], ??? : Double)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalQuery.InspectionPartition(??? : TemporalQuery.Inspection, ??? : Vector[TemporalQuery.InspectionReadout], ??? : Double)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionPartition refuses apply outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.InspectionReadout], v2: Double); Control(??? : TemporalQuery.Inspection, ??? : Vector[TemporalQuery.InspectionReadout], ??? : Double)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalQuery.InspectionPartition(??? : TemporalQuery.Inspection, ??? : Vector[TemporalQuery.InspectionReadout], ??? : Double)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionPartition refuses copy outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.InspectionReadout], v2: Double); (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalQuery.InspectionPartition).copy()"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionPartition refuses fromProduct outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.InspectionReadout], v2: Double); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalQuery.InspectionPartition.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }
  test("TemporalQuery.InspectionPartition refuses Mirror outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.InspectionReadout], v2: Double); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalQuery.InspectionPartition]]"""
      ).nonEmpty
    )
  }
  test("TemporalInspection refuses constructor outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.Region], v2: Map[Int, Vector[TemporalQuery.Contribution]], v3: Map[Int, Double], v4: Map[Int, (Double, Double)], v5: Double, v6: Checksum); new Control(??? : TemporalQuery.Inspection, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], ??? : Double, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalInspection(??? : TemporalQuery.Inspection, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], ??? : Double, ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalInspection refuses apply outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.Region], v2: Map[Int, Vector[TemporalQuery.Contribution]], v3: Map[Int, Double], v4: Map[Int, (Double, Double)], v5: Double, v6: Checksum); Control(??? : TemporalQuery.Inspection, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], ??? : Double, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalInspection(??? : TemporalQuery.Inspection, ??? : Vector[TemporalQuery.Region], ??? : Map[Int, Vector[TemporalQuery.Contribution]], ??? : Map[Int, Double], ??? : Map[Int, (Double, Double)], ??? : Double, ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalInspection refuses copy outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.Region], v2: Map[Int, Vector[TemporalQuery.Contribution]], v3: Map[Int, Double], v4: Map[Int, (Double, Double)], v5: Double, v6: Checksum); (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalInspection).copy()"""
      ).nonEmpty
    )
  }
  test("TemporalInspection refuses fromProduct outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.Region], v2: Map[Int, Vector[TemporalQuery.Contribution]], v3: Map[Int, Double], v4: Map[Int, (Double, Double)], v5: Double, v6: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalInspection.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }
  test("TemporalInspection refuses Mirror outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalQuery.Inspection, v1: Vector[TemporalQuery.Region], v2: Map[Int, Vector[TemporalQuery.Contribution]], v3: Map[Int, Double], v4: Map[Int, (Double, Double)], v5: Double, v6: Checksum); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalInspection]]"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.Inspection refuses constructor outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallTiming, v1: Map[RecallUnitId, ProcessingStatus], v2: TemporalExposure.Overlap, v3: Checksum); new Control(??? : RecallTiming, ??? : Map[RecallUnitId, ProcessingStatus], ??? : TemporalExposure.Overlap, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalExposure.Inspection(??? : RecallTiming, ??? : Map[RecallUnitId, ProcessingStatus], ??? : TemporalExposure.Overlap, ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.Inspection refuses apply outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallTiming, v1: Map[RecallUnitId, ProcessingStatus], v2: TemporalExposure.Overlap, v3: Checksum); Control(??? : RecallTiming, ??? : Map[RecallUnitId, ProcessingStatus], ??? : TemporalExposure.Overlap, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure.Inspection(??? : RecallTiming, ??? : Map[RecallUnitId, ProcessingStatus], ??? : TemporalExposure.Overlap, ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.Inspection refuses copy outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallTiming, v1: Map[RecallUnitId, ProcessingStatus], v2: TemporalExposure.Overlap, v3: Checksum); (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalExposure.Inspection).copy()"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.Inspection refuses fromProduct outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallTiming, v1: Map[RecallUnitId, ProcessingStatus], v2: TemporalExposure.Overlap, v3: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure.Inspection.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.Inspection refuses Mirror outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: RecallTiming, v1: Map[RecallUnitId, ProcessingStatus], v2: TemporalExposure.Overlap, v3: Checksum); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalExposure.Inspection]]"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.InspectionReadout refuses constructor outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalExposure.Inspection, v1: ExactRational, v2: ExactRational, v3: Vector[TemporalExposure.UnitReadout], v4: (BigInt, BigInt), v5: Checksum); new Control(??? : TemporalExposure.Inspection, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalExposure.InspectionReadout(??? : TemporalExposure.Inspection, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.InspectionReadout refuses apply outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalExposure.Inspection, v1: ExactRational, v2: ExactRational, v3: Vector[TemporalExposure.UnitReadout], v4: (BigInt, BigInt), v5: Checksum); Control(??? : TemporalExposure.Inspection, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure.InspectionReadout(??? : TemporalExposure.Inspection, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum)"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.InspectionReadout refuses copy outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalExposure.Inspection, v1: ExactRational, v2: ExactRational, v3: Vector[TemporalExposure.UnitReadout], v4: (BigInt, BigInt), v5: Checksum); (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalExposure.InspectionReadout).copy()"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.InspectionReadout refuses fromProduct outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalExposure.Inspection, v1: ExactRational, v2: ExactRational, v3: Vector[TemporalExposure.UnitReadout], v4: (BigInt, BigInt), v5: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure.InspectionReadout.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }
  test("TemporalExposure.InspectionReadout refuses Mirror outside its defining package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(v0: TemporalExposure.Inspection, v1: ExactRational, v2: ExactRational, v3: Vector[TemporalExposure.UnitReadout], v4: (BigInt, BigInt), v5: Checksum); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalExposure.InspectionReadout]]"""
      ).nonEmpty
    )
  }
