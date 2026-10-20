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

  private val referenceControls = typeCheckErrors("""
    import storymodel4s.align.*
    import storymodel4s.recall.*
    import storymodel4s.recall.RecallGraphStatus.Checked
    case class ReferenceExecutionControl(
      recall: RecallGraph[Checked], view: SourceView, inventory: RecallInventory,
      source: SourceRepresentation, reference: LocalReference.Result
    )
    object ReferenceExecutionControl:
      def fromProduct(p: Product): ReferenceExecutionControl =
        summon[scala.deriving.Mirror.ProductOf[ReferenceExecutionControl]].fromProduct(p)
    case class ReferenceDerivedControl(
      execution: MappingRun.ReferenceExecution, receipt: StageReceipt
    )
    object ReferenceDerivedControl:
      def fromProduct(p: Product): ReferenceDerivedControl =
        summon[scala.deriving.Mirror.ProductOf[ReferenceDerivedControl]].fromProduct(p)
    new ReferenceExecutionControl(???, ???, ???, ???, ???)
    ReferenceExecutionControl(???, ???, ???, ???, ???)
    (??? : ReferenceExecutionControl).copy()
    ReferenceExecutionControl.fromProduct(Tuple5(
      ??? : RecallGraph[Checked], ??? : SourceView, ??? : RecallInventory,
      ??? : SourceRepresentation, ??? : LocalReference.Result
    ))
    summon[scala.deriving.Mirror.ProductOf[ReferenceExecutionControl]]
    new ReferenceDerivedControl(???, ???)
    ReferenceDerivedControl(???, ???)
    (??? : ReferenceDerivedControl).copy()
    ReferenceDerivedControl.fromProduct(Tuple2(
      ??? : MappingRun.ReferenceExecution, ??? : StageReceipt
    ))
    summon[scala.deriving.Mirror.ProductOf[ReferenceDerivedControl]]
  """)

  test("reference same-shape controls compile") { assertEquals(referenceControls, Nil) }

  test("ReferenceExecution doors are closed") {
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        import storymodel4s.recall.*
        import storymodel4s.recall.RecallGraphStatus.Checked
        new MappingRun.ReferenceExecution(
          ??? : RecallGraph[Checked], ??? : SourceView, ??? : RecallInventory,
          ??? : SourceRepresentation, ??? : LocalReference.Result
        )
      """).nonEmpty
    )
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        import storymodel4s.recall.*
        import storymodel4s.recall.RecallGraphStatus.Checked
        MappingRun.ReferenceExecution(
          ??? : RecallGraph[Checked], ??? : SourceView, ??? : RecallInventory,
          ??? : SourceRepresentation, ??? : LocalReference.Result
        )
      """).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; (??? : MappingRun.ReferenceExecution).copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        import storymodel4s.recall.*
        import storymodel4s.recall.RecallGraphStatus.Checked
        MappingRun.ReferenceExecution.fromProduct(Tuple5(
          ??? : RecallGraph[Checked], ??? : SourceView, ??? : RecallInventory,
          ??? : SourceRepresentation, ??? : LocalReference.Result
        ))
      """).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[MappingRun.ReferenceExecution]]"""
      ).nonEmpty
    )
  }

  test("ReferenceDerived doors are closed") {
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        new StageProvenance.ReferenceDerived(
          ??? : MappingRun.ReferenceExecution, ??? : StageReceipt
        )
      """).nonEmpty
    )
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        StageProvenance.ReferenceDerived(
          ??? : MappingRun.ReferenceExecution, ??? : StageReceipt
        )
      """).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; (??? : StageProvenance.ReferenceDerived).copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors("""
        import storymodel4s.align.*
        StageProvenance.ReferenceDerived.fromProduct(Tuple2(
          ??? : MappingRun.ReferenceExecution, ??? : StageReceipt
        ))
      """).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StageProvenance.ReferenceDerived]]"""
      ).nonEmpty
    )
  }
