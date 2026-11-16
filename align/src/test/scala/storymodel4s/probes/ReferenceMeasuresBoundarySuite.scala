package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Rule 8: reference-measure inputs and outputs are born only through their checked factories.
  *
  * Each refusal asserts the access error itself, beside a same-shape control that compiles.
  */
class ReferenceMeasuresBoundarySuite extends FunSuite:

  private def refusedWith(errors: List[scala.compiletime.testing.Error], expected: String): Unit =
    assert(errors.exists(_.message.contains(expected)), errors.map(_.message).mkString(" | "))

  test("ProvisionalReference constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(sourceDigest: Checksum, coordinate: TemporalSupport.Coordinate, declaration: ProvisionalReference.Declaration, coverage: ProvisionalReference.WeightCoverage, resolved: Map[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : Checksum, ??? : TemporalSupport.Coordinate, ProvisionalReference.Declaration.Unresolved, ProvisionalReference.WeightCoverage.Exhaustive, Map.empty[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]])"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new ProvisionalReference(??? : Checksum, ??? : TemporalSupport.Coordinate, ProvisionalReference.Declaration.Unresolved, ProvisionalReference.WeightCoverage.Exhaustive, Map.empty[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]])"""
      ),
      "cannot be accessed"
    )
  }

  test("ProvisionalReference apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(sourceDigest: Checksum, coordinate: TemporalSupport.Coordinate, declaration: ProvisionalReference.Declaration, coverage: ProvisionalReference.WeightCoverage, resolved: Map[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : Checksum, ??? : TemporalSupport.Coordinate, ProvisionalReference.Declaration.Unresolved, ProvisionalReference.WeightCoverage.Exhaustive, Map.empty[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]])"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; ProvisionalReference(??? : Checksum, ??? : TemporalSupport.Coordinate, ProvisionalReference.Declaration.Unresolved, ProvisionalReference.WeightCoverage.Exhaustive, Map.empty[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]])"""
      ),
      "does not take parameters"
    )
  }

  test("ProvisionalReference copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(sourceDigest: Checksum, coordinate: TemporalSupport.Coordinate, declaration: ProvisionalReference.Declaration, coverage: ProvisionalReference.WeightCoverage, resolved: Map[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; (??? : ProvisionalReference).copy()"""
      ),
      "is not a member of"
    )
  }

  test("ProvisionalReference fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(sourceDigest: Checksum, coordinate: TemporalSupport.Coordinate, declaration: ProvisionalReference.Declaration, coverage: ProvisionalReference.WeightCoverage, resolved: Map[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : Checksum, ??? : TemporalSupport.Coordinate, ProvisionalReference.Declaration.Unresolved, ProvisionalReference.WeightCoverage.Exhaustive, Map.empty[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]]))"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; ProvisionalReference.fromProduct((??? : Checksum, ??? : TemporalSupport.Coordinate, ProvisionalReference.Declaration.Unresolved, ProvisionalReference.WeightCoverage.Exhaustive, Map.empty[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]]))"""
      ),
      "is not a member of"
    )
  }

  test("ProvisionalReference Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(sourceDigest: Checksum, coordinate: TemporalSupport.Coordinate, declaration: ProvisionalReference.Declaration, coverage: ProvisionalReference.WeightCoverage, resolved: Map[SourceNodeRef, Either[TemporalSupport.Refusal, TemporalSupport]]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[ProvisionalReference]]"""
      ),
      "No given instance"
    )
  }

  test("ReferenceMeasures.Measures constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(reference: ProvisionalReference, alternativesPresent: Either[ReferenceMeasures.Unavailable, Boolean], memberCount: Either[ReferenceMeasures.Unavailable, Int], members: Either[ReferenceMeasures.Unavailable, Vector[ReferenceMeasures.MemberMeasure]], unassignedShare: Either[ReferenceMeasures.UnassignedUnavailable, Double]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : ProvisionalReference, Right(true), Right(1), Right(Vector.empty[ReferenceMeasures.MemberMeasure]), Right(0.0))"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new ReferenceMeasures.Measures(??? : ProvisionalReference, Right(true), Right(1), Right(Vector.empty[ReferenceMeasures.MemberMeasure]), Right(0.0))"""
      ),
      "cannot be accessed"
    )
  }

  test("ReferenceMeasures.Measures apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(reference: ProvisionalReference, alternativesPresent: Either[ReferenceMeasures.Unavailable, Boolean], memberCount: Either[ReferenceMeasures.Unavailable, Int], members: Either[ReferenceMeasures.Unavailable, Vector[ReferenceMeasures.MemberMeasure]], unassignedShare: Either[ReferenceMeasures.UnassignedUnavailable, Double]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : ProvisionalReference, Right(true), Right(1), Right(Vector.empty[ReferenceMeasures.MemberMeasure]), Right(0.0))"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; ReferenceMeasures.Measures(??? : ProvisionalReference, Right(true), Right(1), Right(Vector.empty[ReferenceMeasures.MemberMeasure]), Right(0.0))"""
      ),
      "cannot be accessed"
    )
  }

  test("ReferenceMeasures.Measures copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(reference: ProvisionalReference, alternativesPresent: Either[ReferenceMeasures.Unavailable, Boolean], memberCount: Either[ReferenceMeasures.Unavailable, Int], members: Either[ReferenceMeasures.Unavailable, Vector[ReferenceMeasures.MemberMeasure]], unassignedShare: Either[ReferenceMeasures.UnassignedUnavailable, Double]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; (??? : ReferenceMeasures.Measures).copy()"""
      ),
      "is not a member of"
    )
  }

  test("ReferenceMeasures.Measures fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(reference: ProvisionalReference, alternativesPresent: Either[ReferenceMeasures.Unavailable, Boolean], memberCount: Either[ReferenceMeasures.Unavailable, Int], members: Either[ReferenceMeasures.Unavailable, Vector[ReferenceMeasures.MemberMeasure]], unassignedShare: Either[ReferenceMeasures.UnassignedUnavailable, Double]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : ProvisionalReference, Right(true), Right(1), Right(Vector.empty[ReferenceMeasures.MemberMeasure]), Right(0.0)))"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; ReferenceMeasures.Measures.fromProduct((??? : ProvisionalReference, Right(true), Right(1), Right(Vector.empty[ReferenceMeasures.MemberMeasure]), Right(0.0)))"""
      ),
      "is not a member of"
    )
  }

  test("ReferenceMeasures.Measures Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(reference: ProvisionalReference, alternativesPresent: Either[ReferenceMeasures.Unavailable, Boolean], memberCount: Either[ReferenceMeasures.Unavailable, Int], members: Either[ReferenceMeasures.Unavailable, Vector[ReferenceMeasures.MemberMeasure]], unassignedShare: Either[ReferenceMeasures.UnassignedUnavailable, Double]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[ReferenceMeasures.Measures]]"""
      ),
      "No given instance"
    )
  }

  test("ReferenceMeasures.Extent constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(measure: ReferenceMeasures.AxisDuration, hull: ReferenceMeasures.AxisDuration, isolatedPoints: Int, grain: ReferenceMeasures.Grain); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : ReferenceMeasures.AxisDuration, ??? : ReferenceMeasures.AxisDuration, 0, ??? : ReferenceMeasures.Grain)"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new ReferenceMeasures.Extent(??? : ReferenceMeasures.AxisDuration, ??? : ReferenceMeasures.AxisDuration, 0, ??? : ReferenceMeasures.Grain)"""
      ),
      "cannot be accessed"
    )
  }

  test("ReferenceMeasures.Extent apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(measure: ReferenceMeasures.AxisDuration, hull: ReferenceMeasures.AxisDuration, isolatedPoints: Int, grain: ReferenceMeasures.Grain); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : ReferenceMeasures.AxisDuration, ??? : ReferenceMeasures.AxisDuration, 0, ??? : ReferenceMeasures.Grain)"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; ReferenceMeasures.Extent(??? : ReferenceMeasures.AxisDuration, ??? : ReferenceMeasures.AxisDuration, 0, ??? : ReferenceMeasures.Grain)"""
      ),
      "cannot be accessed"
    )
  }

  test("ReferenceMeasures.Extent copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(measure: ReferenceMeasures.AxisDuration, hull: ReferenceMeasures.AxisDuration, isolatedPoints: Int, grain: ReferenceMeasures.Grain); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; (??? : ReferenceMeasures.Extent).copy()"""
      ),
      "is not a member of"
    )
  }

  test("ReferenceMeasures.Extent fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(measure: ReferenceMeasures.AxisDuration, hull: ReferenceMeasures.AxisDuration, isolatedPoints: Int, grain: ReferenceMeasures.Grain); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : ReferenceMeasures.AxisDuration, ??? : ReferenceMeasures.AxisDuration, 0, ??? : ReferenceMeasures.Grain))"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; ReferenceMeasures.Extent.fromProduct((??? : ReferenceMeasures.AxisDuration, ??? : ReferenceMeasures.AxisDuration, 0, ??? : ReferenceMeasures.Grain))"""
      ),
      "is not a member of"
    )
  }

  test("ReferenceMeasures.Extent Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; case class Control(measure: ReferenceMeasures.AxisDuration, hull: ReferenceMeasures.AxisDuration, isolatedPoints: Int, grain: ReferenceMeasures.Grain); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    refusedWith(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[ReferenceMeasures.Extent]]"""
      ),
      "No given instance"
    )
  }

  test("ProvisionalReference.of is reachable from outside the package (positive control)") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; ProvisionalReference.of(??? : SourceRepresentation, ??? : TemporalSupport.Selection, ProvisionalReference.Declaration.Unresolved, ProvisionalReference.WeightCoverage.Exhaustive).map(ReferenceMeasures.of)"""
      ),
      Nil
    )
  }
