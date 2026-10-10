package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.{DecisionPolicy as _, *}
import storymodel4s.align.StructuredReconstruction.*
import storymodel4s.core.Checksum
import storymodel4s.recall.RecallUnitId

/** Same-shape public control for the DecisionPolicy construction boundary. */
final case class ReconstructionDecisionPolicyControl(
    order: OrderPolicy,
    backwardPenalty: Double,
    forwardPenalty: Double,
    fill: FillPolicy
)
object ReconstructionDecisionPolicyControl:
  def fromProduct(p: Product): ReconstructionDecisionPolicyControl =
    ReconstructionDecisionPolicyControl(
      p.productElement(0).asInstanceOf[OrderPolicy],
      p.productElement(1).asInstanceOf[Double],
      p.productElement(2).asInstanceOf[Double],
      p.productElement(3).asInstanceOf[FillPolicy]
    )

/** Same-shape public control for the Profile construction boundary. */
final case class ReconstructionProfileControl(
    inference: HsmmConfig,
    decision: DecisionPolicy,
    historicalSherlock: Boolean
)
object ReconstructionProfileControl:
  def fromProduct(p: Product): ReconstructionProfileControl =
    ReconstructionProfileControl(
      p.productElement(0).asInstanceOf[HsmmConfig],
      p.productElement(1).asInstanceOf[DecisionPolicy],
      p.productElement(2).asInstanceOf[Boolean]
    )

/** Same-shape public control for the Grouping construction boundary. */
final case class ReconstructionGroupingControl(
    source: SourceView,
    roots: Vector[(SourceNodeRef, Int)],
    membership: Map[SourceNodeRef, Int]
)
object ReconstructionGroupingControl:
  def fromProduct(p: Product): ReconstructionGroupingControl =
    ReconstructionGroupingControl(
      p.productElement(0).asInstanceOf[SourceView],
      p.productElement(1).asInstanceOf[Vector[(SourceNodeRef, Int)]],
      p.productElement(2).asInstanceOf[Map[SourceNodeRef, Int]]
    )

/** Same-shape public control for the FillRequest construction boundary. */
final case class ReconstructionFillRequestControl(
    grouping: Grouping,
    recallBinding: Checksum,
    targets: Map[(RecallUnitId, Int), SourceNodeRef]
)
object ReconstructionFillRequestControl:
  def fromProduct(p: Product): ReconstructionFillRequestControl =
    ReconstructionFillRequestControl(
      p.productElement(0).asInstanceOf[Grouping],
      p.productElement(1).asInstanceOf[Checksum],
      p.productElement(2).asInstanceOf[Map[(RecallUnitId, Int), SourceNodeRef]]
    )

/** Same-shape public control for the Decision construction boundary. */
final case class ReconstructionDecisionControl(
    unit: RecallUnitId,
    rawArgmax: Option[AlignState],
    rawArgmaxMass: Option[Double],
    rawSourceMaximum: Option[SourceNodeRef],
    anchor: Option[SourceNodeRef],
    chosenMass: Option[Double],
    assignedGroup: Option[Int],
    origin: Origin
)
object ReconstructionDecisionControl:
  def fromProduct(p: Product): ReconstructionDecisionControl =
    ReconstructionDecisionControl(
      p.productElement(0).asInstanceOf[RecallUnitId],
      p.productElement(1).asInstanceOf[Option[AlignState]],
      p.productElement(2).asInstanceOf[Option[Double]],
      p.productElement(3).asInstanceOf[Option[SourceNodeRef]],
      p.productElement(4).asInstanceOf[Option[SourceNodeRef]],
      p.productElement(5).asInstanceOf[Option[Double]],
      p.productElement(6).asInstanceOf[Option[Int]],
      p.productElement(7).asInstanceOf[Origin]
    )

/** Same-shape public control for the Result construction boundary. */
final case class ReconstructionResultControl(
    evidence: LocalEvidence,
    profile: Profile,
    grouping: Grouping,
    fill: Option[FillRequest],
    inference: HsmmResult,
    decisions: Vector[Decision]
)
object ReconstructionResultControl:
  def fromProduct(p: Product): ReconstructionResultControl =
    ReconstructionResultControl(
      p.productElement(0).asInstanceOf[LocalEvidence],
      p.productElement(1).asInstanceOf[Profile],
      p.productElement(2).asInstanceOf[Grouping],
      p.productElement(3).asInstanceOf[Option[FillRequest]],
      p.productElement(4).asInstanceOf[HsmmResult],
      p.productElement(5).asInstanceOf[Vector[Decision]]
    )

/** Outside-package probes prevent replacement of relationally checked producer parts. */
class ReconstructionBoundarySuite extends FunSuite:
  inline val imports = """
    import storymodel4s.align.{DecisionPolicy as _, *}
    import storymodel4s.align.StructuredReconstruction.*
    import storymodel4s.probes.*
    import scala.deriving.Mirror
    import storymodel4s.core.Checksum
    import storymodel4s.recall.RecallUnitId
  """

  test("DecisionPolicy refuses constructor with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "new DecisionPolicy(OrderPolicy.Nondecreasing, 0.0, 0.0, FillPolicy.Disabled)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "new ReconstructionDecisionPolicyControl(OrderPolicy.Nondecreasing, 0.0, 0.0, FillPolicy.Disabled)"
      ),
      Nil
    )
  }

  test("DecisionPolicy refuses apply with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "DecisionPolicy(OrderPolicy.Nondecreasing, 0.0, 0.0, FillPolicy.Disabled)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReconstructionDecisionPolicyControl(OrderPolicy.Nondecreasing, 0.0, 0.0, FillPolicy.Disabled)"
      ),
      Nil
    )
  }

  test("DecisionPolicy refuses copy with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "null.asInstanceOf[DecisionPolicy].copy(order = OrderPolicy.Nondecreasing)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "null.asInstanceOf[ReconstructionDecisionPolicyControl].copy(order = OrderPolicy.Nondecreasing)"
      ),
      Nil
    )
  }

  test("DecisionPolicy refuses fromProduct with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "DecisionPolicy.fromProduct((OrderPolicy.Nondecreasing, 0.0, 0.0, FillPolicy.Disabled))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReconstructionDecisionPolicyControl.fromProduct((OrderPolicy.Nondecreasing, 0.0, 0.0, FillPolicy.Disabled))"
      ),
      Nil
    )
  }

  test("DecisionPolicy refuses Mirror with same-shape positive control") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[DecisionPolicy]]").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[ReconstructionDecisionPolicyControl]]"),
      Nil
    )
  }

  test("Profile refuses constructor with same-shape positive control") {
    assert(typeCheckErrors(imports + "new Profile(null, null, false)").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "new ReconstructionProfileControl(null, null, false)"),
      Nil
    )
  }

  test("Profile refuses apply with same-shape positive control") {
    assert(typeCheckErrors(imports + "Profile(null, null, false)").nonEmpty)
    assertEquals(typeCheckErrors(imports + "ReconstructionProfileControl(null, null, false)"), Nil)
  }

  test("Profile refuses copy with same-shape positive control") {
    assert(typeCheckErrors(imports + "null.asInstanceOf[Profile].copy(inference = null)").nonEmpty)
    assertEquals(
      typeCheckErrors(
        imports + "null.asInstanceOf[ReconstructionProfileControl].copy(inference = null)"
      ),
      Nil
    )
  }

  test("Profile refuses fromProduct with same-shape positive control") {
    assert(typeCheckErrors(imports + "Profile.fromProduct((null, null, false))").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "ReconstructionProfileControl.fromProduct((null, null, false))"),
      Nil
    )
  }

  test("Profile refuses Mirror with same-shape positive control") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[Profile]]").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[ReconstructionProfileControl]]"),
      Nil
    )
  }

  test("Grouping refuses constructor with same-shape positive control") {
    assert(typeCheckErrors(imports + "new Grouping(null, Vector.empty, Map.empty)").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "new ReconstructionGroupingControl(null, Vector.empty, Map.empty)"),
      Nil
    )
  }

  test("Grouping refuses apply with same-shape positive control") {
    assert(typeCheckErrors(imports + "Grouping(null, Vector.empty, Map.empty)").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "ReconstructionGroupingControl(null, Vector.empty, Map.empty)"),
      Nil
    )
  }

  test("Grouping refuses copy with same-shape positive control") {
    assert(typeCheckErrors(imports + "null.asInstanceOf[Grouping].copy(source = null)").nonEmpty)
    assertEquals(
      typeCheckErrors(
        imports + "null.asInstanceOf[ReconstructionGroupingControl].copy(source = null)"
      ),
      Nil
    )
  }

  test("Grouping refuses fromProduct with same-shape positive control") {
    assert(
      typeCheckErrors(imports + "Grouping.fromProduct((null, Vector.empty, Map.empty))").nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReconstructionGroupingControl.fromProduct((null, Vector.empty, Map.empty))"
      ),
      Nil
    )
  }

  test("Grouping refuses Mirror with same-shape positive control") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[Grouping]]").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[ReconstructionGroupingControl]]"),
      Nil
    )
  }

  test("FillRequest refuses constructor with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "new FillRequest(null, null.asInstanceOf[Checksum], Map.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "new ReconstructionFillRequestControl(null, null.asInstanceOf[Checksum], Map.empty)"
      ),
      Nil
    )
  }

  test("FillRequest refuses apply with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "FillRequest(null, null.asInstanceOf[Checksum], Map.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReconstructionFillRequestControl(null, null.asInstanceOf[Checksum], Map.empty)"
      ),
      Nil
    )
  }

  test("FillRequest refuses copy with same-shape positive control") {
    assert(
      typeCheckErrors(imports + "null.asInstanceOf[FillRequest].copy(grouping = null)").nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "null.asInstanceOf[ReconstructionFillRequestControl].copy(grouping = null)"
      ),
      Nil
    )
  }

  test("FillRequest refuses fromProduct with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "FillRequest.fromProduct((null, null.asInstanceOf[Checksum], Map.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReconstructionFillRequestControl.fromProduct((null, null.asInstanceOf[Checksum], Map.empty))"
      ),
      Nil
    )
  }

  test("FillRequest refuses Mirror with same-shape positive control") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[FillRequest]]").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[ReconstructionFillRequestControl]]"),
      Nil
    )
  }

  test("Decision refuses constructor with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "new Decision(null.asInstanceOf[RecallUnitId], None, None, None, None, None, None, Origin.RawSourceMaximum)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "new ReconstructionDecisionControl(null.asInstanceOf[RecallUnitId], None, None, None, None, None, None, Origin.RawSourceMaximum)"
      ),
      Nil
    )
  }

  test("Decision refuses apply with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "Decision(null.asInstanceOf[RecallUnitId], None, None, None, None, None, None, Origin.RawSourceMaximum)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReconstructionDecisionControl(null.asInstanceOf[RecallUnitId], None, None, None, None, None, None, Origin.RawSourceMaximum)"
      ),
      Nil
    )
  }

  test("Decision refuses copy with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "null.asInstanceOf[Decision].copy(unit = null.asInstanceOf[RecallUnitId])"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "null.asInstanceOf[ReconstructionDecisionControl].copy(unit = null.asInstanceOf[RecallUnitId])"
      ),
      Nil
    )
  }

  test("Decision refuses fromProduct with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "Decision.fromProduct((null.asInstanceOf[RecallUnitId], None, None, None, None, None, None, Origin.RawSourceMaximum))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReconstructionDecisionControl.fromProduct((null.asInstanceOf[RecallUnitId], None, None, None, None, None, None, Origin.RawSourceMaximum))"
      ),
      Nil
    )
  }

  test("Decision refuses Mirror with same-shape positive control") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[Decision]]").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[ReconstructionDecisionControl]]"),
      Nil
    )
  }

  test("Result refuses constructor with same-shape positive control") {
    assert(
      typeCheckErrors(imports + "new Result(null, null, null, None, null, Vector.empty)").nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "new ReconstructionResultControl(null, null, null, None, null, Vector.empty)"
      ),
      Nil
    )
  }

  test("Result refuses apply with same-shape positive control") {
    assert(typeCheckErrors(imports + "Result(null, null, null, None, null, Vector.empty)").nonEmpty)
    assertEquals(
      typeCheckErrors(
        imports + "ReconstructionResultControl(null, null, null, None, null, Vector.empty)"
      ),
      Nil
    )
  }

  test("Result refuses copy with same-shape positive control") {
    assert(typeCheckErrors(imports + "null.asInstanceOf[Result].copy(evidence = null)").nonEmpty)
    assertEquals(
      typeCheckErrors(
        imports + "null.asInstanceOf[ReconstructionResultControl].copy(evidence = null)"
      ),
      Nil
    )
  }

  test("Result refuses fromProduct with same-shape positive control") {
    assert(
      typeCheckErrors(
        imports + "Result.fromProduct((null, null, null, None, null, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReconstructionResultControl.fromProduct((null, null, null, None, null, Vector.empty))"
      ),
      Nil
    )
  }

  test("Result refuses Mirror with same-shape positive control") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[Result]]").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[ReconstructionResultControl]]"),
      Nil
    )
  }
