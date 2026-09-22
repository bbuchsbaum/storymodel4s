package storymodel4s.align.attack

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*

class MappingOutcomePackageAttackSuite extends FunSuite:
  private val dependencies = Vector(
    classOf[UnitOutcome],
    classOf[UnitDecision],
    classOf[DecisionBasis],
    classOf[DecisionCalibration.Calibrated]
  )
  test("checked outcome construction remains public") {
    assertEquals(dependencies.size, 4)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; UnitOutcome.computed(??? : RecallUnitId, ??? : UnitMeasures, Vector.empty, ??? : DecisionBasis, DecisionRequest.RawArgmax, ??? : UnitStageRefs)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; DecisionBasis.of(MeasureKind.RawScore, Some("raw"))"""
      ).isEmpty
    )
  }
  test("outcomes have no unchecked construction") {
    assertEquals(dependencies.size, 4)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; new UnitOutcome(??? : RecallUnitId, ProcessingStatus.Complete, LocalizationStatus.Located, Vector.empty, ??? : UnitMeasures, None, None)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; (x: UnitOutcome) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[UnitOutcome]]"""
      ).nonEmpty
    )
  }
  test("decisions have no unchecked construction") {
    assertEquals(dependencies.size, 4)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; new UnitDecision(??? : DecisionBasis, None, DecisionOrigin.RawArgmax, None, DecodedTargetMass.NoDecision, DecisionCalibration.Unavailable(DecisionCalibrationUnavailableReason.NoCalibrationArtifact), None)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; (x: UnitDecision) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[UnitDecision]]"""
      ).nonEmpty
    )
  }
  test("calibrated decisions have no door") {
    assertEquals(dependencies.size, 4)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; new DecisionCalibration.Calibrated(??? : CalibratedProbability)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; DecisionCalibration.Calibrated.of(??? : CalibratedProbability)"""
      ).nonEmpty
    )
  }
  test("decision basis requires checked construction") {
    assertEquals(dependencies.size, 4)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; new DecisionBasis(MeasureKind.RawScore, None)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[DecisionBasis]]"""
      ).nonEmpty
    )
  }
