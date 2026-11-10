package storymodel4s.pipelineprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** From outside its package, a `PreparedRecall` can be obtained only from its checked factory. */
class PreparedRecallBoundarySuite extends FunSuite:

  test("the constructor is closed, and the factory is the accepting control"):
    val closed = typeCheckErrors(
      "import storymodel4s.pipeline.agentmapping.PreparedRecall; new PreparedRecall(null, null, Vector.empty, null)"
    )
    assert(closed.exists(_.message.contains("cannot be accessed")), closed.map(_.message).toString)
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.pipeline.agentmapping.PreparedRecall; import storymodel4s.core.Checksum; PreparedRecall.supplied(\"t\", Vector(\"a\"), Checksum.ofText(\"x\"))"
      ),
      Nil
    )

  test("it is not a case class: no copy, no apply"):
    assertEquals(
      typeCheckErrors(
        "import storymodel4s.pipeline.agentmapping.PreparedRecall; def f(r: PreparedRecall) = r.texts"
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.pipeline.agentmapping.PreparedRecall; def f(r: PreparedRecall) = r.copy()"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.pipeline.agentmapping.PreparedRecall; PreparedRecall(null, null, Vector.empty, null)"
      ).nonEmpty
    )
