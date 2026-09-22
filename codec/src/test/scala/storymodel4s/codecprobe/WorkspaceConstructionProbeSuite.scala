package storymodel4s.codecprobe

import scala.compiletime.testing.typeCheckErrors
import munit.FunSuite

class WorkspaceConstructionProbeSuite extends FunSuite:
  test("archive, investigation, policy and subset payload have no unchecked construction door") {
    val errors = Vector(
      typeCheckErrors("new storymodel4s.codec.WorkspaceArchive(null, null)"),
      typeCheckErrors(
        "new storymodel4s.codec.WorkspaceCodecs.Investigation(null, null, null, null, null, null, null, null, null, \"revision\")"
      ),
      typeCheckErrors("new storymodel4s.codec.WorkspaceCodecs.Policy(null, null)"),
      typeCheckErrors("new storymodel4s.codec.WorkspaceSubsetCodec.Payload(\"\", \"\", \"\", \"\")")
    )
    errors.foreach(result => assert(result.exists(_.message.contains("private"))))
    assertEquals(typeCheckErrors("storymodel4s.codec.WorkspaceArchive.of(null)"), Nil)
    assertEquals(typeCheckErrors("storymodel4s.codec.WorkspaceCodecs.decode(\"{}\")"), Nil)
  }
