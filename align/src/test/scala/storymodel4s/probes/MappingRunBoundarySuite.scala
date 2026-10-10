package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Outside-package construction courts for retained mapping execution provenance. */
class MappingRunBoundarySuite extends FunSuite:
  private val controls = typeCheckErrors("""
    import storymodel4s.align.*
    import storymodel4s.recall.*
    import storymodel4s.recall.RecallGraphStatus.Checked
    case class ExecutionControl(
      recall: RecallGraph[Checked], view: SourceView, inventory: RecallInventory,
      source: SourceRepresentation, reference: LocalReference.Result,
      reconstruction: StructuredReconstruction.Result
    )
    object ExecutionControl:
      def fromProduct(p: Product): ExecutionControl =
        summon[scala.deriving.Mirror.ProductOf[ExecutionControl]].fromProduct(p)
    case class DerivedControl(
      execution: MappingRun.Execution, member: MappingRun.Member, receipt: StageReceipt
    )
    object DerivedControl:
      def fromProduct(p: Product): DerivedControl =
        summon[scala.deriving.Mirror.ProductOf[DerivedControl]].fromProduct(p)
    new ExecutionControl(???, ???, ???, ???, ???, ???)
    ExecutionControl(???, ???, ???, ???, ???, ???)
    (??? : ExecutionControl).copy()
    ExecutionControl.fromProduct(Tuple6(
      ??? : RecallGraph[Checked], ??? : SourceView, ??? : RecallInventory,
      ??? : SourceRepresentation, ??? : LocalReference.Result,
      ??? : StructuredReconstruction.Result
    ))
    summon[scala.deriving.Mirror.ProductOf[ExecutionControl]]
    new DerivedControl(???, ???, ???)
    DerivedControl(???, ???, ???)
    (??? : DerivedControl).copy()
    DerivedControl.fromProduct(Tuple3(
      ??? : MappingRun.Execution, ??? : MappingRun.Member, ??? : StageReceipt
    ))
    summon[scala.deriving.Mirror.ProductOf[DerivedControl]]
  """)

  test("same-shape positive controls compile") {
    assertEquals(controls, Nil)
  }

  test("MappingRun Execution doors are closed") {
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        import storymodel4s.recall.*
        import storymodel4s.recall.RecallGraphStatus.Checked
        new MappingRun.Execution(
          ??? : RecallGraph[Checked], ??? : SourceView, ??? : RecallInventory,
          ??? : SourceRepresentation, ??? : LocalReference.Result,
          ??? : StructuredReconstruction.Result
        )
      """).nonEmpty
    )
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        import storymodel4s.recall.*
        import storymodel4s.recall.RecallGraphStatus.Checked
        MappingRun.Execution(
          ??? : RecallGraph[Checked], ??? : SourceView, ??? : RecallInventory,
          ??? : SourceRepresentation, ??? : LocalReference.Result,
          ??? : StructuredReconstruction.Result
        )
      """).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; (??? : MappingRun.Execution).copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        import storymodel4s.recall.*
        import storymodel4s.recall.RecallGraphStatus.Checked
        MappingRun.Execution.fromProduct(Tuple6(
          ??? : RecallGraph[Checked], ??? : SourceView, ??? : RecallInventory,
          ??? : SourceRepresentation, ??? : LocalReference.Result,
          ??? : StructuredReconstruction.Result
        ))
      """).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[MappingRun.Execution]]"""
      ).nonEmpty
    )
  }

  test("StageProvenance Derived doors are closed") {
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        new StageProvenance.Derived(
          ??? : MappingRun.Execution, ??? : MappingRun.Member, ??? : StageReceipt
        )
      """).nonEmpty
    )
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        StageProvenance.Derived(
          ??? : MappingRun.Execution, ??? : MappingRun.Member, ??? : StageReceipt
        )
      """).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; (??? : StageProvenance.Derived).copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        StageProvenance.Derived.fromProduct(Tuple3(
          ??? : MappingRun.Execution, ??? : MappingRun.Member, ??? : StageReceipt
        ))
      """).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StageProvenance.Derived]]"""
      ).nonEmpty
    )
  }
