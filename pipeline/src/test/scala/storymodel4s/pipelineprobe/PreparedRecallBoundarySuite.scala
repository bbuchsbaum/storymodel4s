package storymodel4s.pipelineprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** From outside its package, a `PreparedRecall` can be obtained only from its checked factory. */
class PreparedRecallBoundarySuite extends FunSuite:
  private val imports =
    "import storymodel4s.pipeline.agentmapping.PreparedRecall; import storymodel4s.core.Checksum; "

  test("the constructor is closed, and the factory is the accepting control"):
    val closed = typeCheckErrors(
      imports + "new PreparedRecall(null, null, Vector.empty, null)"
    )
    assert(closed.exists(_.message.contains("cannot be accessed")), closed.map(_.message).toString)
    assertEquals(
      typeCheckErrors(imports + """PreparedRecall.supplied("t", Vector("a"), Checksum.ofText("x"))"""),
      Nil
    )

  test("it is not a case class: no copy, no apply"):
    val recall =
      imports + """val r = PreparedRecall.supplied("t", Vector("a"), Checksum.ofText("x")).toOption.get; """
    assertEquals(typeCheckErrors(recall + "r.texts"), Nil)
    assert(typeCheckErrors(recall + "r.copy()").nonEmpty)
    assert(typeCheckErrors(imports + "PreparedRecall(null, null, Vector.empty, null)").nonEmpty)
