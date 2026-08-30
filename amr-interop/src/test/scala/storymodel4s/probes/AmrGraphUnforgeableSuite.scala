package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks

class AmrGraphUnforgeableSuite extends FunSuite:
  // Macro snippets are strings, so this real reference is what makes Zinc invalidate the court
  // when AmrGraph changes instead of retaining a stale compile-time answer.
  private val dependsOn: Class[?] =
    classOf[storymodel4s.amr.graph.AmrGraph[?, ?]]

  test("Checked and CanonicalRoles cannot be claimed through Mirror.fromProduct") {
    assert(dependsOn != null)
    assert(
      typeChecks("""
        import storymodel4s.amr.graph.*
        val invalid: AmrGraph[CheckState.Unchecked, RoleForm.SurfaceRoles] =
          AmrGraph.unchecked(NodeId.unsafe("missing"), Vector.empty, Vector.empty)
        val expected = invalid.undefinedReferences
      """)
    )
    assert(typeChecks("summon[scala.deriving.Mirror.ProductOf[(Int, String)]]"))
    assert(
      !typeChecks("""
        import scala.deriving.Mirror
        import storymodel4s.amr.graph.*
        val invalid: AmrGraph[CheckState.Unchecked, RoleForm.SurfaceRoles] =
          AmrGraph.unchecked(NodeId.unsafe("missing"), Vector.empty, Vector.empty)
        val forged: AmrGraph[CheckState.Checked, RoleForm.CanonicalRoles] =
          summon[Mirror.ProductOf[AmrGraph[CheckState.Checked, RoleForm.CanonicalRoles]]]
            .fromProduct(invalid)
      """)
    )
    assert(
      !typeChecks("""
        import storymodel4s.amr.graph.*
        val invalid = AmrGraph.unchecked(NodeId.unsafe("missing"), Vector.empty, Vector.empty)
        val product: Product = invalid
      """)
    )
  }

  test("plain-class replacement preserves value semantics without payload diagnostics") {
    import storymodel4s.amr.graph.*

    val a = AmrGraph.unchecked(
      NodeId.unsafe("n"),
      Vector(NodeId.unsafe("n") -> Concept.unsafe("secret-canary")),
      Vector.empty
    )
    val b = AmrGraph.unchecked(
      NodeId.unsafe("n"),
      Vector(NodeId.unsafe("n") -> Concept.unsafe("secret-canary")),
      Vector.empty
    )

    assertEquals(a, b)
    assertEquals(a.hashCode, b.hashCode)
    assert(!a.toString.contains("secret-canary"))
  }
