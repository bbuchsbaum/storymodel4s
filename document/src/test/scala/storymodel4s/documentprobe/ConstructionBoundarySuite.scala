package storymodel4s.documentprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that validated document values have no case-class construction bypass. */
class ConstructionBoundarySuite extends FunSuite:
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
