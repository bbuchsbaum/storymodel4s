package semanticprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite
import storymodel4s.proposition.*

/** Court for the S2a-1 semantic graph, run from OUTSIDE `storymodel4s`, so neither the
  * `private[proposition]` reads nor the `private[storymodel4s]` source-order projection can be
  * reached by package privilege.
  *
  * Every refusal has a control in the same file that must compile. A refusal beside a failing
  * control proves nothing.
  */
class SemanticGraphCourtSuite extends FunSuite:
  private val chart = ChartValidator
    .check(
      PropositionChart.unchecked(
        Some(ConceptId.unsafe("p")),
        Map(ConceptId.unsafe("p") -> Concept.predicate("see")),
        Vector.empty
      )
    )
    .fold(v => fail(s"invalid chart: $v"), identity)
  private val g = SemanticProjection.canonical(chart).fold(r => fail(s"refused: $r"), identity)
  private val report = SemanticCompatibility.compare(g, g)
  private val chartReport = ChartCompatibility.compare(chart, chart)
  locally((g, report, chartReport))

  private def compiles(snippet: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(snippet.isEmpty, s"control `$what` must compile:\n${snippet.mkString("\n")}")

  private def refused(snippet: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(snippet.nonEmpty, s"`$what` must not compile outside storymodel4s")

  test("controls: the public surface compiles from here") {
    compiles(typeCheckErrors("g.isEmpty"), "g.isEmpty")
    compiles(typeCheckErrors("SemanticProjection.canonical(chart)"), "canonical")
    compiles(typeCheckErrors("SemanticCompatibility.compare(g, g)"), "compare")
    compiles(typeCheckErrors("report.structuralScore + (if report.matched then 1 else 0)"), "read")
  }

  test("no read door on the graph's contents or order") {
    refused(typeCheckErrors("g.kinds"), "g.kinds")
    refused(typeCheckErrors("g.lemmas"), "g.lemmas")
    refused(typeCheckErrors("g.glosses"), "g.glosses")
    refused(typeCheckErrors("g.relations"), "g.relations")
    refused(typeCheckErrors("g.relationsOf"), "g.relationsOf")
    refused(typeCheckErrors("g.polarities"), "g.polarities")
    refused(typeCheckErrors("g.frames"), "g.frames")
    refused(typeCheckErrors("g.embeddings"), "g.embeddings")
  }

  test("the source-order projection is not reachable from outside storymodel4s") {
    refused(typeCheckErrors("SemanticProjection.sourceOrder(chart)"), "sourceOrder")
  }

  test("graphs of different order policies cannot be compared") {
    compiles(
      typeCheckErrors(
        "SemanticCompatibility.compare(g, null.asInstanceOf[SemanticGraph[GraphOrder.Canonical]])"
      ),
      "same-policy compare"
    )
    refused(
      typeCheckErrors(
        "SemanticCompatibility.compare(g, null.asInstanceOf[SemanticGraph[GraphOrder.Source]])"
      ),
      "mixed-policy compare"
    )
  }

  test("construction doors are closed on SemanticGraph and ContentCompatibilityReport") {
    compiles(
      typeCheckErrors(
        "new OpenSemanticGraph[GraphOrder.Canonical](???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "same-field graph constructor"
    )
    compiles(
      typeCheckErrors(
        "new OpenContentReport(0.0, 0.0, 0.0, Set(GateReading(false, false, false)), true)"
      ),
      "same-field content report constructor"
    )
    // The historical report supplies precompiled case-class construction controls.
    compiles(
      typeCheckErrors("new CompatibilityReport(0.0, 0.0, 0.0, false, false, false, Vector.empty)"),
      "new CompatibilityReport"
    )
    compiles(typeCheckErrors("chartReport.copy()"), "CompatibilityReport.copy")
    compiles(
      typeCheckErrors("CompatibilityReport.fromProduct((0.0, 0.0, 0.0, false, false, false, Nil))"),
      "CompatibilityReport.fromProduct"
    )
    compiles(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[CompatibilityReport]]"),
      "Mirror.ProductOf[CompatibilityReport]"
    )
    refused(
      typeCheckErrors(
        "new ContentCompatibilityReport(0.0, 0.0, 0.0, Set(GateReading(false, false, false)), true)"
      ),
      "new ContentCompatibilityReport"
    )
    refused(typeCheckErrors("report.copy()"), "ContentCompatibilityReport.copy")
    refused(
      typeCheckErrors(
        "ContentCompatibilityReport.fromProduct((0.0, 0.0, 0.0, false, false, false, true))"
      ),
      "ContentCompatibilityReport.fromProduct"
    )
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[ContentCompatibilityReport]]"),
      "Mirror.ProductOf[ContentCompatibilityReport]"
    )
    refused(
      typeCheckErrors(
        "new SemanticGraph[GraphOrder.Canonical](???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "new SemanticGraph"
    )
    refused(typeCheckErrors("g.copy()"), "SemanticGraph.copy")
    refused(typeCheckErrors("SemanticGraph.fromProduct(EmptyTuple)"), "SemanticGraph.fromProduct")
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[SemanticGraph[GraphOrder.Canonical]]]"
      ),
      "Mirror.ProductOf[SemanticGraph]"
    )
  }
