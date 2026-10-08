package storymodel4s.bench.consumer

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*
import storymodel4s.bench.video.TimedSourceView
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.recall.RecallGraph
import storymodel4s.recall.RecallGraphStatus.Checked

/** Same-shape product controls are outside video and the enclosing StageTrace owner scope. */
final case class TraceRunControl(
    recall: RecallGraph[Checked],
    levels: Map[SourceNodeRef, Int],
    video: Option[TimedSourceView.Built],
    registration: Option[OnnxMappingRegistration.Registered],
    evidence: LocalEvidence,
    config: HsmmConfig,
    result: HsmmResult
)

class StageTraceBoundarySuite extends FunSuite:
  inline val imports = """
    import storymodel4s.bench.video.StageTrace
    import storymodel4s.bench.consumer.TraceRunControl
    import scala.deriving.Mirror
  """

  test("executed run constructor refuses an independently supplied evidence config result tuple") {
    assert(
      typeCheckErrors(
        imports + "new StageTrace.Run(null, Map.empty, None, None, null, null, null)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "new TraceRunControl(null, Map.empty, None, None, null, null, null)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(imports + "StageTrace.historical(null, null, null, null, null)"),
      Nil
    )
  }

  test("executed run has no apply or result replacement copy door") {
    assert(
      typeCheckErrors(
        imports + "StageTrace.Run(null, Map.empty, None, None, null, null, null)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(imports + "TraceRunControl(null, Map.empty, None, None, null, null, null)"),
      Nil
    )
    assert(
      typeCheckErrors(imports + "null.asInstanceOf[StageTrace.Run].copy(result = null)").nonEmpty
    )
    assertEquals(
      typeCheckErrors(imports + "null.asInstanceOf[TraceRunControl].copy(result = null)"),
      Nil
    )
  }

  test("executed run has no fromProduct door") {
    assert(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[StageTrace.Run]].fromProduct((null, Map.empty, None, None, null, null, null))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[TraceRunControl]].fromProduct((null, Map.empty, None, None, null, null, null))"
      ),
      Nil
    )
  }

  test("executed run has no product Mirror door") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[StageTrace.Run]]").nonEmpty)
    assertEquals(typeCheckErrors(imports + "summon[Mirror.ProductOf[TraceRunControl]]"), Nil)
  }

  test("captured recall video levels and registration have no external read doors") {
    assert(typeCheckErrors(imports + "null.asInstanceOf[StageTrace.Run].recall").nonEmpty)
    assertEquals(typeCheckErrors(imports + "null.asInstanceOf[TraceRunControl].recall"), Nil)
    assert(typeCheckErrors(imports + "null.asInstanceOf[StageTrace.Run].video").nonEmpty)
    assertEquals(typeCheckErrors(imports + "null.asInstanceOf[TraceRunControl].video"), Nil)
    assert(typeCheckErrors(imports + "null.asInstanceOf[StageTrace.Run].levels").nonEmpty)
    assertEquals(typeCheckErrors(imports + "null.asInstanceOf[TraceRunControl].levels"), Nil)
    assert(typeCheckErrors(imports + "null.asInstanceOf[StageTrace.Run].registration").nonEmpty)
    assertEquals(typeCheckErrors(imports + "null.asInstanceOf[TraceRunControl].registration"), Nil)
  }
