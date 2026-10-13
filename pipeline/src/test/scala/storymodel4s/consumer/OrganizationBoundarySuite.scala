package storymodel4s.consumer

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.pipeline.MappingPipeline

final case class RowControl(
    unit: RecallUnitId,
    status: OrganizationReadout.RowStatus,
    mass: Map[Destination, Double],
    admissible: Vector[Destination]
)
final case class OrderControl(
    positions: Map[SourceNodeRef, Double],
    source: Option[SourceRepresentation],
    axis: MeasurementCompatibility.Axis
)
final case class JointControl(
    left: RecallUnitId,
    right: RecallUnitId,
    mass: Map[(Destination, Destination), Double]
)
final case class RequestControl(
    axis: MeasurementCompatibility.Axis,
    organization: AnalysisGrain,
    level: Int,
    unknown: MeasurementCompatibility.UnknownTransitions
)
final case class ResultControl(
    rows: Vector[OrganizationReadout.Row],
    order: OrganizationReadout.Order,
    dependence: OrganizationReadout.Dependence,
    transitions: Vector[OrganizationReadout.Transition],
    bounds: Option[Map[OrganizationReadout.Category, OrganizationReadout.Bounds]]
)
final case class OrganizationControl(
    run: MappingPipeline.Run,
    request: MeasurementCompatibility.Request,
    result: OrganizationReadout.Result,
    stages: Vector[StageReceipt]
)

object RowControl:
  def fromProduct(value: Product): RowControl =
    summon[scala.deriving.Mirror.ProductOf[RowControl]].fromProduct(value)

object OrderControl:
  def fromProduct(value: Product): OrderControl =
    summon[scala.deriving.Mirror.ProductOf[OrderControl]].fromProduct(value)

object JointControl:
  def fromProduct(value: Product): JointControl =
    summon[scala.deriving.Mirror.ProductOf[JointControl]].fromProduct(value)

object RequestControl:
  def fromProduct(value: Product): RequestControl =
    summon[scala.deriving.Mirror.ProductOf[RequestControl]].fromProduct(value)

object ResultControl:
  def fromProduct(value: Product): ResultControl =
    summon[scala.deriving.Mirror.ProductOf[ResultControl]].fromProduct(value)

object OrganizationControl:
  def fromProduct(value: Product): OrganizationControl =
    summon[scala.deriving.Mirror.ProductOf[OrganizationControl]].fromProduct(value)

/** Every closed door has its own same-shape positive control outside the owning package. */
class OrganizationBoundarySuite extends FunSuite:

  test("Row closes constructor with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new OrganizationReadout.Row(null.asInstanceOf[storymodel4s.recall.RecallUnitId], OrganizationReadout.RowStatus.Available, Map.empty, Vector.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new RowControl(null.asInstanceOf[storymodel4s.recall.RecallUnitId], OrganizationReadout.RowStatus.Available, Map.empty, Vector.empty)"
      ),
      Nil
    )
  }

  test("Row closes apply with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationReadout.Row(null.asInstanceOf[storymodel4s.recall.RecallUnitId], OrganizationReadout.RowStatus.Available, Map.empty, Vector.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; RowControl(null.asInstanceOf[storymodel4s.recall.RecallUnitId], OrganizationReadout.RowStatus.Available, Map.empty, Vector.empty)"
      ),
      Nil
    )
  }

  test("Row closes copy with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[OrganizationReadout.Row].copy(mass = Map.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[RowControl].copy(mass = Map.empty)"
      ),
      Nil
    )
  }

  test("Row closes fromProduct with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationReadout.Row.fromProduct((null, OrganizationReadout.RowStatus.Available, Map.empty, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; RowControl.fromProduct((null, OrganizationReadout.RowStatus.Available, Map.empty, Vector.empty))"
      ),
      Nil
    )
  }

  test("Row closes product construction with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationReadout.Row]].fromProduct((null, OrganizationReadout.RowStatus.Available, Map.empty, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[RowControl]].fromProduct((null, OrganizationReadout.RowStatus.Available, Map.empty, Vector.empty))"
      ),
      Nil
    )
  }

  test("Row closes Mirror with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationReadout.Row]]"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[RowControl]]"
      ),
      Nil
    )
  }

  test("Order closes constructor with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new OrganizationReadout.Order(Map.empty, None, MeasurementCompatibility.Axis.Discourse)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new OrderControl(Map.empty, None, MeasurementCompatibility.Axis.Discourse)"
      ),
      Nil
    )
  }

  test("Order closes apply with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationReadout.Order(Map.empty, None, MeasurementCompatibility.Axis.Discourse)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrderControl(Map.empty, None, MeasurementCompatibility.Axis.Discourse)"
      ),
      Nil
    )
  }

  test("Order closes copy with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[OrganizationReadout.Order].copy(positions = Map.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[OrderControl].copy(positions = Map.empty)"
      ),
      Nil
    )
  }

  test("Order closes fromProduct with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationReadout.Order.fromProduct((Map.empty, None, MeasurementCompatibility.Axis.Discourse))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrderControl.fromProduct((Map.empty, None, MeasurementCompatibility.Axis.Discourse))"
      ),
      Nil
    )
  }

  test("Order closes product construction with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationReadout.Order]].fromProduct((Map.empty, None, MeasurementCompatibility.Axis.Discourse))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrderControl]].fromProduct((Map.empty, None, MeasurementCompatibility.Axis.Discourse))"
      ),
      Nil
    )
  }

  test("Order closes Mirror with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationReadout.Order]]"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrderControl]]"
      ),
      Nil
    )
  }

  test("Joint closes constructor with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new OrganizationReadout.Joint(null.asInstanceOf[storymodel4s.recall.RecallUnitId], null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new JointControl(null.asInstanceOf[storymodel4s.recall.RecallUnitId], null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty)"
      ),
      Nil
    )
  }

  test("Joint closes apply with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationReadout.Joint(null.asInstanceOf[storymodel4s.recall.RecallUnitId], null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; JointControl(null.asInstanceOf[storymodel4s.recall.RecallUnitId], null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty)"
      ),
      Nil
    )
  }

  test("Joint closes copy with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[OrganizationReadout.Joint].copy(mass = Map.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[JointControl].copy(mass = Map.empty)"
      ),
      Nil
    )
  }

  test("Joint closes fromProduct with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationReadout.Joint.fromProduct((null, null, Map.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; JointControl.fromProduct((null, null, Map.empty))"
      ),
      Nil
    )
  }

  test("Joint closes product construction with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationReadout.Joint]].fromProduct((null, null, Map.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[JointControl]].fromProduct((null, null, Map.empty))"
      ),
      Nil
    )
  }

  test("Joint closes Mirror with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationReadout.Joint]]"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[JointControl]]"
      ),
      Nil
    )
  }

  test("Request closes constructor with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new MeasurementCompatibility.Request(MeasurementCompatibility.Axis.Discourse, AnalysisGrain.Word, 0, MeasurementCompatibility.UnknownTransitions.PreserveAdjacencies)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new RequestControl(MeasurementCompatibility.Axis.Discourse, AnalysisGrain.Word, 0, MeasurementCompatibility.UnknownTransitions.PreserveAdjacencies)"
      ),
      Nil
    )
  }

  test("Request closes apply with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; MeasurementCompatibility.Request(MeasurementCompatibility.Axis.Discourse, AnalysisGrain.Word, 0, MeasurementCompatibility.UnknownTransitions.PreserveAdjacencies)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; RequestControl(MeasurementCompatibility.Axis.Discourse, AnalysisGrain.Word, 0, MeasurementCompatibility.UnknownTransitions.PreserveAdjacencies)"
      ),
      Nil
    )
  }

  test("Request closes copy with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[MeasurementCompatibility.Request].copy(level = -1)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[RequestControl].copy(level = -1)"
      ),
      Nil
    )
  }

  test("Request closes fromProduct with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; MeasurementCompatibility.Request.fromProduct((MeasurementCompatibility.Axis.Discourse, AnalysisGrain.Word, 0, MeasurementCompatibility.UnknownTransitions.PreserveAdjacencies))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; RequestControl.fromProduct((MeasurementCompatibility.Axis.Discourse, AnalysisGrain.Word, 0, MeasurementCompatibility.UnknownTransitions.PreserveAdjacencies))"
      ),
      Nil
    )
  }

  test("Request closes product construction with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[MeasurementCompatibility.Request]].fromProduct((MeasurementCompatibility.Axis.Discourse, AnalysisGrain.Word, 0, MeasurementCompatibility.UnknownTransitions.PreserveAdjacencies))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[RequestControl]].fromProduct((MeasurementCompatibility.Axis.Discourse, AnalysisGrain.Word, 0, MeasurementCompatibility.UnknownTransitions.PreserveAdjacencies))"
      ),
      Nil
    )
  }

  test("Request closes Mirror with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[MeasurementCompatibility.Request]]"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[RequestControl]]"
      ),
      Nil
    )
  }

  test("Result closes constructor with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new OrganizationReadout.Result(Vector.empty, null, OrganizationReadout.Dependence.DeclaredIndependentRows, Vector.empty, None)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new ResultControl(Vector.empty, null, OrganizationReadout.Dependence.DeclaredIndependentRows, Vector.empty, None)"
      ),
      Nil
    )
  }

  test("Result closes apply with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationReadout.Result(Vector.empty, null, OrganizationReadout.Dependence.DeclaredIndependentRows, Vector.empty, None)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; ResultControl(Vector.empty, null, OrganizationReadout.Dependence.DeclaredIndependentRows, Vector.empty, None)"
      ),
      Nil
    )
  }

  test("Result closes copy with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[OrganizationReadout.Result].copy(bounds = None)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[ResultControl].copy(bounds = None)"
      ),
      Nil
    )
  }

  test("Result closes fromProduct with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationReadout.Result.fromProduct((Vector.empty, null, OrganizationReadout.Dependence.DeclaredIndependentRows, Vector.empty, None))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; ResultControl.fromProduct((Vector.empty, null, OrganizationReadout.Dependence.DeclaredIndependentRows, Vector.empty, None))"
      ),
      Nil
    )
  }

  test("Result closes product construction with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationReadout.Result]].fromProduct((Vector.empty, null, OrganizationReadout.Dependence.DeclaredIndependentRows, Vector.empty, None))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[ResultControl]].fromProduct((Vector.empty, null, OrganizationReadout.Dependence.DeclaredIndependentRows, Vector.empty, None))"
      ),
      Nil
    )
  }

  test("Result closes Mirror with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationReadout.Result]]"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[ResultControl]]"
      ),
      Nil
    )
  }

  test("Organization closes constructor with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new MappingPipeline.Organization(null, null, null, Vector.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; new OrganizationControl(null, null, null, Vector.empty)"
      ),
      Nil
    )
  }

  test("Organization closes apply with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; MappingPipeline.Organization(null, null, null, Vector.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationControl(null, null, null, Vector.empty)"
      ),
      Nil
    )
  }

  test("Organization closes copy with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[MappingPipeline.Organization].copy(run = null)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; null.asInstanceOf[OrganizationControl].copy(run = null)"
      ),
      Nil
    )
  }

  test("Organization closes fromProduct with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; MappingPipeline.Organization.fromProduct((null, null, null, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; OrganizationControl.fromProduct((null, null, null, Vector.empty))"
      ),
      Nil
    )
  }

  test("Organization closes product construction with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[MappingPipeline.Organization]].fromProduct((null, null, null, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationControl]].fromProduct((null, null, null, Vector.empty))"
      ),
      Nil
    )
  }

  test("Organization closes Mirror with an accepting control") {
    assert(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[MappingPipeline.Organization]]"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.align.*; import storymodel4s.pipeline.MappingPipeline; import storymodel4s.consumer.*; import scala.deriving.Mirror; summon[Mirror.ProductOf[OrganizationControl]]"
      ),
      Nil
    )
  }
