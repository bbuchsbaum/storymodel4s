package storymodel4s.viewprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class WorkspaceManifestProbeSuite extends FunSuite:
  test("a consumer cannot manufacture an admitted manifest") {
    assert(typeCheckErrors("""
      import storymodel4s.view.*
      new WorkspaceManifest(Vector.empty, Map.empty)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.view.*
      val value: WorkspaceManifest = ???
      value.copy(entries = Vector.empty)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.view.*
      summon[scala.deriving.Mirror.ProductOf[WorkspaceManifest]]
    """).nonEmpty)
  }
