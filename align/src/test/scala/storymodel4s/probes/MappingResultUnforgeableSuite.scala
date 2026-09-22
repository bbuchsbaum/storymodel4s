package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*

class MappingResultUnforgeableSuite extends FunSuite:
  private val dependencies = Vector(classOf[MappingResult], classOf[DerivationSource.Bound])
  test("checked result construction remains public") {
    assertEquals(dependencies.size, 2)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; MappingResult.checked(??? : RecallInventory, ??? : SourceRepresentation, ??? : MappingPolicies, ??? : UnitRoles, ??? : StageLedger, Vector.empty)"""
      ).isEmpty
    )
  }
  test("result has no unchecked construction") {
    assertEquals(dependencies.size, 2)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.recall.*; new MappingResult(??? : RecallInventory, ??? : SourceRepresentation, ??? : MappingPolicies, ??? : UnitRoles, ??? : StageLedger, DerivationSource.NoDerivedValues, Vector.empty)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors("""import storymodel4s.align.*; (x: MappingResult) => x.copy()""").nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; MappingResult.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[MappingResult]]"""
      ).nonEmpty
    )
  }
  test("bound derivation is owner constructed") {
    assertEquals(dependencies.size, 2)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; new DerivationSource.Bound(??? : DerivationBinding)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; DerivationSource.Bound.derived(??? : DerivationBinding)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; DerivationSource.Bound(??? : DerivationBinding)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; (x: DerivationSource.Bound) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[DerivationSource.Bound]]"""
      ).nonEmpty
    )
  }
