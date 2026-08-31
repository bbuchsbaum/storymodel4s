package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks

class PropositionChartUnforgeableSuite extends FunSuite:
  // Macro snippets are strings, so this real reference is what makes Zinc invalidate the court
  // when PropositionChart changes instead of retaining a stale compile-time answer.
  private val dependsOn: Class[?] =
    classOf[storymodel4s.proposition.PropositionChart[?]]

  test("Checked cannot be claimed through Mirror.fromProduct") {
    assert(dependsOn != null)
    assert(
      typeChecks("""
        import storymodel4s.proposition.*
        val invalid: PropositionChart[Unchecked] = PropositionChart.unchecked(
          Some(ConceptId.unsafe("missing")), Map.empty, Vector.empty
        )
        val expected = invalid.focus
      """)
    )
    assert(typeChecks("summon[scala.deriving.Mirror.ProductOf[(Int, String)]]"))
    assert(
      !typeChecks("""
        import scala.deriving.Mirror
        import storymodel4s.proposition.*
        val invalid: PropositionChart[Unchecked] = PropositionChart.unchecked(
          Some(ConceptId.unsafe("missing")), Map.empty, Vector.empty
        )
        val forged: PropositionChart[Checked] =
          summon[Mirror.ProductOf[PropositionChart[Checked]]].fromProduct(invalid)
      """)
    )
    assert(
      !typeChecks("""
        import storymodel4s.proposition.*
        val invalid = PropositionChart.unchecked(None, Map.empty, Vector.empty)
        val forged: PropositionChart[Checked] = invalid.copy[Checked]()
      """)
    )
    assert(
      !typeChecks("""
        import storymodel4s.proposition.*
        val invalid = PropositionChart.unchecked(None, Map.empty, Vector.empty)
        val product: Product = invalid
      """)
    )
  }

  test("plain-class replacement preserves value semantics without payload diagnostics") {
    import storymodel4s.proposition.*

    val id = ConceptId.unsafe("secret-canary")
    val a = PropositionChart.unchecked(Some(id), Map(id -> Concept.entity("canary")), Vector.empty)
    val b = PropositionChart.unchecked(Some(id), Map(id -> Concept.entity("canary")), Vector.empty)

    assertEquals(a, b)
    assertEquals(a.hashCode, b.hashCode)
    assert(!a.toString.contains("secret-canary"))
  }
