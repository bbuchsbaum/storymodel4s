package storymodel4s.align.attack

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** The same doors stay closed even to a subpackage of `storymodel4s.align`. */
class DecisionSupportPackageAttackSuite extends FunSuite:
  test("categorical outcome construction remains public") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; UnitOutcome.categorical(??? : RecallUnitId, DecisionRequest.Abstain(??? : DecisionPolicyId, "r"), Vector.empty, ??? : UnitStageRefs)"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; (d: UnitDecision) => (d.support, d.numerical, d.chosen, d.origin, d.calibration, d.policy, d.request)"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; (c: DecisionSupport.Categorical, n: DecisionSupport.Numerical) => (c.policy, n.basis, n.rawArgmax, n.decodedMass)"""
      ),
      Nil
    )
  }
  test("categorical support has no new bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(policy: DecisionPolicyId); new Control(??? : DecisionPolicyId)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; new DecisionSupport.Categorical(??? : DecisionPolicyId)"""
      ).nonEmpty
    )
  }
  test("categorical support has no apply bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(policy: DecisionPolicyId); Control(??? : DecisionPolicyId)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; DecisionSupport.Categorical(??? : DecisionPolicyId)"""
      ).nonEmpty
    )
  }
  test("categorical support has no derived bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(policy: DecisionPolicyId); object Make { def derived(p: DecisionPolicyId): Control = Control(p) }; Make.derived(??? : DecisionPolicyId)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; DecisionSupport.Categorical.derived(??? : DecisionPolicyId)"""
      ).nonEmpty
    )
  }
  test("categorical support has no copy bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(policy: DecisionPolicyId); (x: Control) => x.copy(policy = ??? : DecisionPolicyId)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; (x: DecisionSupport.Categorical) => x.copy(policy = ??? : DecisionPolicyId)"""
      ).nonEmpty
    )
  }
  test("categorical support has no fromProduct bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(policy: DecisionPolicyId); summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(Tuple1(??? : DecisionPolicyId))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; DecisionSupport.Categorical.fromProduct(Tuple1(??? : DecisionPolicyId))"""
      ).nonEmpty
    )
  }
  test("categorical support has no Mirror bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(policy: DecisionPolicyId); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[DecisionSupport.Categorical]]"""
      ).nonEmpty
    )
  }
  test("numerical support has no new bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(basis: DecisionBasis, rawArgmax: Option[(Destination, Double)], decodedMass: DecodedTargetMass); new Control(??? : DecisionBasis, None, ??? : DecodedTargetMass)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; new DecisionSupport.Numerical(??? : DecisionBasis, None, ??? : DecodedTargetMass)"""
      ).nonEmpty
    )
  }
  test("numerical support has no apply bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(basis: DecisionBasis, rawArgmax: Option[(Destination, Double)], decodedMass: DecodedTargetMass); Control(??? : DecisionBasis, None, ??? : DecodedTargetMass)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; DecisionSupport.Numerical(??? : DecisionBasis, None, ??? : DecodedTargetMass)"""
      ).nonEmpty
    )
  }
  test("numerical support has no derived bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(basis: DecisionBasis, rawArgmax: Option[(Destination, Double)], decodedMass: DecodedTargetMass); object Make { def derived(b: DecisionBasis, a: Option[(Destination, Double)], m: DecodedTargetMass): Control = Control(b, a, m) }; Make.derived(??? : DecisionBasis, None, ??? : DecodedTargetMass)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; DecisionSupport.Numerical.derived(??? : DecisionBasis, None, ??? : DecodedTargetMass)"""
      ).nonEmpty
    )
  }
  test("numerical support has no copy bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(basis: DecisionBasis, rawArgmax: Option[(Destination, Double)], decodedMass: DecodedTargetMass); (x: Control) => x.copy(rawArgmax = None)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; (x: DecisionSupport.Numerical) => x.copy(rawArgmax = None)"""
      ).nonEmpty
    )
  }
  test("numerical support has no fromProduct bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(basis: DecisionBasis, rawArgmax: Option[(Destination, Double)], decodedMass: DecodedTargetMass); summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct((??? : DecisionBasis, None, ??? : DecodedTargetMass))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; DecisionSupport.Numerical.fromProduct((??? : DecisionBasis, None, ??? : DecodedTargetMass))"""
      ).nonEmpty
    )
  }
  test("numerical support has no Mirror bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(basis: DecisionBasis, rawArgmax: Option[(Destination, Double)], decodedMass: DecodedTargetMass); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[DecisionSupport.Numerical]]"""
      ).nonEmpty
    )
  }
  test("decision has no new bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(support: DecisionSupport, chosen: Option[Destination], origin: DecisionOrigin, calibration: DecisionCalibration, policy: Option[DecisionPolicyId], request: DecisionRequest); new Control(??? : DecisionSupport, None, DecisionOrigin.RawArgmax, ??? : DecisionCalibration, None, ??? : DecisionRequest)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; new UnitDecision(??? : DecisionSupport, None, DecisionOrigin.RawArgmax, ??? : DecisionCalibration, None, ??? : DecisionRequest)"""
      ).nonEmpty
    )
  }
  test("decision has no apply bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(support: DecisionSupport, chosen: Option[Destination], origin: DecisionOrigin, calibration: DecisionCalibration, policy: Option[DecisionPolicyId], request: DecisionRequest); Control(??? : DecisionSupport, None, DecisionOrigin.RawArgmax, ??? : DecisionCalibration, None, ??? : DecisionRequest)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; UnitDecision(??? : DecisionSupport, None, DecisionOrigin.RawArgmax, ??? : DecisionCalibration, None, ??? : DecisionRequest)"""
      ).nonEmpty
    )
  }
  test("decision has no copy bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(support: DecisionSupport, chosen: Option[Destination], origin: DecisionOrigin, calibration: DecisionCalibration, policy: Option[DecisionPolicyId], request: DecisionRequest); (x: Control) => x.copy(chosen = None)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; (x: UnitDecision) => x.copy(chosen = None)"""
      ).nonEmpty
    )
  }
  test("decision has no fromProduct bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(support: DecisionSupport, chosen: Option[Destination], origin: DecisionOrigin, calibration: DecisionCalibration, policy: Option[DecisionPolicyId], request: DecisionRequest); summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct((??? : DecisionSupport, None, DecisionOrigin.RawArgmax, ??? : DecisionCalibration, None, ??? : DecisionRequest))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; UnitDecision.fromProduct((??? : DecisionSupport, None, DecisionOrigin.RawArgmax, ??? : DecisionCalibration, None, ??? : DecisionRequest))"""
      ).nonEmpty
    )
  }
  test("decision has no Mirror bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(support: DecisionSupport, chosen: Option[Destination], origin: DecisionOrigin, calibration: DecisionCalibration, policy: Option[DecisionPolicyId], request: DecisionRequest); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[UnitDecision]]"""
      ).nonEmpty
    )
  }
