package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class TemporalExposureBoundarySuite extends FunSuite:

  test("TemporalExposure constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, timing: RecallTiming, overlap: TemporalExposure.Overlap, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : MappingResult, ??? : RecallTiming, ??? : TemporalExposure.Overlap, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalExposure(??? : MappingResult, ??? : RecallTiming, ??? : TemporalExposure.Overlap, ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalExposure apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, timing: RecallTiming, overlap: TemporalExposure.Overlap, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : MappingResult, ??? : RecallTiming, ??? : TemporalExposure.Overlap, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure(??? : MappingResult, ??? : RecallTiming, ??? : TemporalExposure.Overlap, ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalExposure copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, timing: RecallTiming, overlap: TemporalExposure.Overlap, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalExposure).copy()"""
      ).nonEmpty
    )
  }

  test("TemporalExposure fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, timing: RecallTiming, overlap: TemporalExposure.Overlap, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : MappingResult, ??? : RecallTiming, ??? : TemporalExposure.Overlap, ??? : Checksum))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure.fromProduct((??? : MappingResult, ??? : RecallTiming, ??? : TemporalExposure.Overlap, ??? : Checksum))"""
      ).nonEmpty
    )
  }

  test("TemporalExposure Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(mapping: MappingResult, timing: RecallTiming, overlap: TemporalExposure.Overlap, digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalExposure]]"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.UnitReadout constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, duration: (BigInt, BigInt), onsetEvents: Vector[ExactRational], missingWords: Int, decomposition: DecompositionStatus, processing: ProcessingStatus); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : RecallUnitId, ??? : (BigInt, BigInt), ??? : Vector[ExactRational], 0, ??? : DecompositionStatus, ??? : ProcessingStatus)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalExposure.UnitReadout(??? : RecallUnitId, ??? : (BigInt, BigInt), ??? : Vector[ExactRational], 0, ??? : DecompositionStatus, ??? : ProcessingStatus)"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.UnitReadout apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, duration: (BigInt, BigInt), onsetEvents: Vector[ExactRational], missingWords: Int, decomposition: DecompositionStatus, processing: ProcessingStatus); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : RecallUnitId, ??? : (BigInt, BigInt), ??? : Vector[ExactRational], 0, ??? : DecompositionStatus, ??? : ProcessingStatus)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure.UnitReadout(??? : RecallUnitId, ??? : (BigInt, BigInt), ??? : Vector[ExactRational], 0, ??? : DecompositionStatus, ??? : ProcessingStatus)"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.UnitReadout copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, duration: (BigInt, BigInt), onsetEvents: Vector[ExactRational], missingWords: Int, decomposition: DecompositionStatus, processing: ProcessingStatus); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalExposure.UnitReadout).copy()"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.UnitReadout fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, duration: (BigInt, BigInt), onsetEvents: Vector[ExactRational], missingWords: Int, decomposition: DecompositionStatus, processing: ProcessingStatus); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : RecallUnitId, ??? : (BigInt, BigInt), ??? : Vector[ExactRational], 0, ??? : DecompositionStatus, ??? : ProcessingStatus))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure.UnitReadout.fromProduct((??? : RecallUnitId, ??? : (BigInt, BigInt), ??? : Vector[ExactRational], 0, ??? : DecompositionStatus, ??? : ProcessingStatus))"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.UnitReadout Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(unit: RecallUnitId, duration: (BigInt, BigInt), onsetEvents: Vector[ExactRational], missingWords: Int, decomposition: DecompositionStatus, processing: ProcessingStatus); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalExposure.UnitReadout]]"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.Readout constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(exposure: TemporalExposure, start: ExactRational, endExclusive: ExactRational, units: Vector[TemporalExposure.UnitReadout], uncovered: (BigInt, BigInt), digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : TemporalExposure, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new TemporalExposure.Readout(??? : TemporalExposure, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.Readout apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(exposure: TemporalExposure, start: ExactRational, endExclusive: ExactRational, units: Vector[TemporalExposure.UnitReadout], uncovered: (BigInt, BigInt), digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : TemporalExposure, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure.Readout(??? : TemporalExposure, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.Readout copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(exposure: TemporalExposure, start: ExactRational, endExclusive: ExactRational, units: Vector[TemporalExposure.UnitReadout], uncovered: (BigInt, BigInt), digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; (??? : TemporalExposure.Readout).copy()"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.Readout fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(exposure: TemporalExposure, start: ExactRational, endExclusive: ExactRational, units: Vector[TemporalExposure.UnitReadout], uncovered: (BigInt, BigInt), digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : TemporalExposure, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; TemporalExposure.Readout.fromProduct((??? : TemporalExposure, ??? : ExactRational, ??? : ExactRational, ??? : Vector[TemporalExposure.UnitReadout], ??? : (BigInt, BigInt), ??? : Checksum))"""
      ).nonEmpty
    )
  }

  test("TemporalExposure.Readout Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(exposure: TemporalExposure, start: ExactRational, endExclusive: ExactRational, units: Vector[TemporalExposure.UnitReadout], uncovered: (BigInt, BigInt), digest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[TemporalExposure.Readout]]"""
      ).nonEmpty
    )
  }
