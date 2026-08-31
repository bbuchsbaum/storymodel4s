package storymodel4s.documentprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that validated document values have no case-class construction bypass. */
class ConstructionBoundarySuite extends FunSuite:

  // Macro snippets are strings, so these real references make Zinc invalidate the court.
  private val dependsOn: List[Class[?]] = List(
    classOf[storymodel4s.document.MentionGraph],
    classOf[storymodel4s.document.ExactCorefCluster[?]],
    classOf[storymodel4s.document.CorefPartition[?]],
    classOf[storymodel4s.document.MentionTable[?]],
    classOf[storymodel4s.document.MentionForms],
    classOf[storymodel4s.document.Projection[?]]
  )

  /** Sweep 3 slice: the Mirror.ProductOf door across document's private-constructor case classes.
    *
    * `case class X private (...)` still derives `Mirror.ProductOf` in Scala 3, so the private
    * constructor is defeated from outside the package. Pins the measured state; closing any of
    * these makes this FAIL and asks for the name to be removed. Tracked on
    * bd-01M183VBPNEAPT5JNQMBMYMKQ9.
    */
  test("sweep 3: Mirror.ProductOf door across document private-constructor case classes") {
    assert(dependsOn.forall(_ != null))
    assertEquals(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SurfaceUnit]]"),
      Nil,
      "the summon control must compile or every result below is meaningless"
    )
    val results: List[(String, List[scala.compiletime.testing.Error])] = List(
      (
        "MentionGraph",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.MentionGraph]]"
        )
      )
    )
    val forgeable = results.collect { case (n, errs) if errs.isEmpty => n }.toSet
    assertEquals(
      forgeable,
      results.map(_._1).toSet,
      "sweep 3 expects the Mirror door OPEN here; a difference means one was closed (update this " +
        "list) or a new private-constructor case class was added unmeasured"
    )
  }

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what still exposes fromProduct")

  test("probe control detects a case-class fromProduct") {
    assertEquals(
      typeCheckErrors(
        "storymodel4s.document.MentionGraph.fromProduct(EmptyTuple)"
      ),
      Nil
    )
  }

  test("document smart-construction boundaries expose no fromProduct") {
    refused(
      typeCheckErrors("storymodel4s.document.ExactCorefCluster.fromProduct(EmptyTuple)"),
      "ExactCorefCluster"
    )
    refused(
      typeCheckErrors("storymodel4s.document.CorefPartition.fromProduct(EmptyTuple)"),
      "CorefPartition"
    )
    refused(
      typeCheckErrors("storymodel4s.document.MentionTable.fromProduct(EmptyTuple)"),
      "MentionTable"
    )
    refused(
      typeCheckErrors("storymodel4s.document.MentionForms.fromProduct(EmptyTuple)"),
      "MentionForms"
    )
    refused(
      typeCheckErrors("storymodel4s.document.Projection.fromProduct(EmptyTuple)"),
      "Projection"
    )
  }

  test("document observations remain public") {
    assertEquals(
      typeCheckErrors(
        """(x: storymodel4s.document.MentionForms) => x.forms.size + x.positions.size"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """(x: storymodel4s.document.Projection[
             storymodel4s.core.NarrativeKind.EntityK
           ]) => x.sources.length + x.target.value.length + x.mode.ordinal + x.meta.hashCode"""
      ),
      Nil
    )
  }
