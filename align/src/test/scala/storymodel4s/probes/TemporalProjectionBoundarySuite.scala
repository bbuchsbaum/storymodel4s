package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class TemporalProjectionBoundarySuite extends FunSuite:

  test("TemporalProjection.Row constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, processing: ProcessingStatus, grid: Either[TemporalProjection.Refusal, TemporalGrid], exposure: Option[TemporalExposure.UnitReadout]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : RecallUnitId, ??? : ProcessingStatus, ??? : Either[TemporalProjection.Refusal, TemporalGrid], ??? : Option[TemporalExposure.UnitReadout])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalProjection.Row(??? : RecallUnitId, ??? : ProcessingStatus, ??? : Either[TemporalProjection.Refusal, TemporalGrid], ??? : Option[TemporalExposure.UnitReadout])"""
      ).nonEmpty
    )
  }

  test("TemporalProjection.Row apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, processing: ProcessingStatus, grid: Either[TemporalProjection.Refusal, TemporalGrid], exposure: Option[TemporalExposure.UnitReadout]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : RecallUnitId, ??? : ProcessingStatus, ??? : Either[TemporalProjection.Refusal, TemporalGrid], ??? : Option[TemporalExposure.UnitReadout])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalProjection.Row(??? : RecallUnitId, ??? : ProcessingStatus, ??? : Either[TemporalProjection.Refusal, TemporalGrid], ??? : Option[TemporalExposure.UnitReadout])"""
      ).nonEmpty
    )
  }

  test("TemporalProjection.Row copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, processing: ProcessingStatus, grid: Either[TemporalProjection.Refusal, TemporalGrid], exposure: Option[TemporalExposure.UnitReadout]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalProjection.Row).copy()"""
      ).nonEmpty
    )
  }

  test("TemporalProjection.Row fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, processing: ProcessingStatus, grid: Either[TemporalProjection.Refusal, TemporalGrid], exposure: Option[TemporalExposure.UnitReadout]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : RecallUnitId, ??? : ProcessingStatus, ??? : Either[TemporalProjection.Refusal, TemporalGrid], ??? : Option[TemporalExposure.UnitReadout]))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalProjection.Row.fromProduct((??? : RecallUnitId, ??? : ProcessingStatus, ??? : Either[TemporalProjection.Refusal, TemporalGrid], ??? : Option[TemporalExposure.UnitReadout]))"""
      ).nonEmpty
    )
  }

  test("TemporalProjection.Row Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, processing: ProcessingStatus, grid: Either[TemporalProjection.Refusal, TemporalGrid], exposure: Option[TemporalExposure.UnitReadout]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalProjection.Row]]"""
      ).nonEmpty
    )
  }

  test("TemporalProjection.Projection constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, measure: TemporalQuery.Measure, request: TemporalProjection.Request, timingState: TemporalProjection.TimingState, rows: Vector[TemporalProjection.Row], timing: Option[RecallTiming], exposure: Option[TemporalExposure.Readout], coordinate: TemporalSupport.Coordinate, regions: Vector[TemporalQuery.Region], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : MappingResult, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalProjection.TimingState, ??? : Vector[TemporalProjection.Row], ??? : Option[RecallTiming], ??? : Option[TemporalExposure.Readout], ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalProjection.Projection(??? : MappingResult, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalProjection.TimingState, ??? : Vector[TemporalProjection.Row], ??? : Option[RecallTiming], ??? : Option[TemporalExposure.Readout], ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalProjection.Projection apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, measure: TemporalQuery.Measure, request: TemporalProjection.Request, timingState: TemporalProjection.TimingState, rows: Vector[TemporalProjection.Row], timing: Option[RecallTiming], exposure: Option[TemporalExposure.Readout], coordinate: TemporalSupport.Coordinate, regions: Vector[TemporalQuery.Region], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : MappingResult, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalProjection.TimingState, ??? : Vector[TemporalProjection.Row], ??? : Option[RecallTiming], ??? : Option[TemporalExposure.Readout], ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalProjection.Projection(??? : MappingResult, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalProjection.TimingState, ??? : Vector[TemporalProjection.Row], ??? : Option[RecallTiming], ??? : Option[TemporalExposure.Readout], ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalProjection.Projection copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, measure: TemporalQuery.Measure, request: TemporalProjection.Request, timingState: TemporalProjection.TimingState, rows: Vector[TemporalProjection.Row], timing: Option[RecallTiming], exposure: Option[TemporalExposure.Readout], coordinate: TemporalSupport.Coordinate, regions: Vector[TemporalQuery.Region], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalProjection.Projection).copy()"""
      ).nonEmpty
    )
  }

  test("TemporalProjection.Projection fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, measure: TemporalQuery.Measure, request: TemporalProjection.Request, timingState: TemporalProjection.TimingState, rows: Vector[TemporalProjection.Row], timing: Option[RecallTiming], exposure: Option[TemporalExposure.Readout], coordinate: TemporalSupport.Coordinate, regions: Vector[TemporalQuery.Region], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : MappingResult, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalProjection.TimingState, ??? : Vector[TemporalProjection.Row], ??? : Option[RecallTiming], ??? : Option[TemporalExposure.Readout], ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Checksum))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalProjection.Projection.fromProduct((??? : MappingResult, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalProjection.TimingState, ??? : Vector[TemporalProjection.Row], ??? : Option[RecallTiming], ??? : Option[TemporalExposure.Readout], ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Checksum))"""
      ).nonEmpty
    )
  }

  test("TemporalProjection.Projection Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, measure: TemporalQuery.Measure, request: TemporalProjection.Request, timingState: TemporalProjection.TimingState, rows: Vector[TemporalProjection.Row], timing: Option[RecallTiming], exposure: Option[TemporalExposure.Readout], coordinate: TemporalSupport.Coordinate, regions: Vector[TemporalQuery.Region], digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalProjection.Projection]]"""
      ).nonEmpty
    )
  }
