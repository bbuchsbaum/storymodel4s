package storymodel4s.probes

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

import storymodel4s.core.{StorySource, SurfaceAnalyzer}
import storymodel4s.recall.{RecallGraph, RecallRelations, RecallSegmenter}

/** Construction probes from outside `storymodel4s.recall`, where package-private raw assembly is
  * unavailable and a checked witness must come from validation.
  */
class RecallGraphUnforgeableSuite extends FunSuite:

  private val source = StorySource.fromText("secret recalled event").toOption.get
  private val checked = RecallSegmenter.segment(source)

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not produce a checked graph outside storymodel4s.recall")

  test("positive control: a remaining case class still has fromProduct") {
    val errors = typeCheckErrors(
      """storymodel4s.recall.RecallRelations.fromProduct((
        Vector.empty[storymodel4s.recall.RecallTemporalEdge],
        Vector.empty[storymodel4s.recall.RecallCausalEdge],
        Vector.empty[storymodel4s.recall.RecallEntity],
        Vector.empty[storymodel4s.recall.ElaborationEdge],
        Vector.empty[storymodel4s.recall.RecallCorefLink]
      ))"""
    )
    assert(
      errors.isEmpty,
      "if RecallRelations.fromProduct fails, adjacent refusals are meaningless:\n" +
        errors.mkString("\n")
    )
  }

  test("RecallGraph has no derived fromProduct or public raw factory") {
    refused(
      typeCheckErrors(
        """storymodel4s.recall.RecallGraph(
          ??? : storymodel4s.core.StorySource,
          ??? : storymodel4s.core.SurfaceAtlas,
          Vector.empty[storymodel4s.recall.RecallUnit],
          storymodel4s.recall.RecallRelations.empty
        )"""
      ),
      "RecallGraph.apply"
    )
    refused(
      typeCheckErrors(
        """storymodel4s.recall.RecallGraph.fromProduct((
          ??? : storymodel4s.core.StorySource,
          ??? : storymodel4s.core.SurfaceAtlas,
          Vector.empty[storymodel4s.recall.RecallUnit],
          storymodel4s.recall.RecallRelations.empty
        ))"""
      ),
      "RecallGraph.fromProduct"
    )
    refused(
      typeCheckErrors(
        """storymodel4s.recall.RecallGraph.unchecked(
          ??? : storymodel4s.core.StorySource,
          ??? : storymodel4s.core.SurfaceAtlas,
          Vector.empty[storymodel4s.recall.RecallUnit],
          storymodel4s.recall.RecallRelations.empty
        )"""
      ),
      "RecallGraph.unchecked"
    )
  }

  test("copy is an explicit edit door that loses the checked witness") {
    assert(
      typeCheckErrors(
        """(g: storymodel4s.recall.RecallGraph[
          storymodel4s.recall.RecallGraphStatus.Checked
        ]) => g.copy(): storymodel4s.recall.RecallGraph[
          storymodel4s.recall.RecallGraphStatus.Unchecked
        ]"""
      ).isEmpty
    )
    refused(
      typeCheckErrors(
        """(g: storymodel4s.recall.RecallGraph[
          storymodel4s.recall.RecallGraphStatus.Checked
        ]) => g.copy(): storymodel4s.recall.RecallGraph[
          storymodel4s.recall.RecallGraphStatus.Checked
        ]"""
      ),
      "RecallGraph.copy as Checked"
    )
  }

  test("checked graphs retain public read access but are not Products") {
    assert(
      typeCheckErrors(
        """(g: storymodel4s.recall.RecallGraph[
          storymodel4s.recall.RecallGraphStatus.Checked
        ]) => (g.transcript, g.atlas, g.units, g.relations, g.ordered)"""
      ).isEmpty
    )
    refused(
      typeCheckErrors(
        """(g: storymodel4s.recall.RecallGraph[
          storymodel4s.recall.RecallGraphStatus.Checked
        ]) => g: Product"""
      ),
      "RecallGraph as Product"
    )
  }

  test("validation is the public promotion path and value semantics remain structural") {
    assert(
      typeCheckErrors(
        """(storymodel4s.recall.RecallGraph.validated(
          ??? : storymodel4s.core.StorySource,
          ??? : storymodel4s.core.SurfaceAtlas,
          Vector.empty[storymodel4s.recall.RecallUnit],
          storymodel4s.recall.RecallRelations.empty
        ): cats.data.ValidatedNec[
          storymodel4s.core.DomainError,
          storymodel4s.recall.RecallGraph[storymodel4s.recall.RecallGraphStatus.Checked]
        ])"""
      ).isEmpty
    )
    val rebuilt = RecallGraph
      .validated(source, SurfaceAnalyzer.analyze(source), checked.units, RecallRelations.empty)
      .toOption
      .get
    val lying = checked.copy(units = checked.units.map(_.copy(text = "different words")))

    assertEquals(rebuilt, checked)
    assertEquals(rebuilt.hashCode, checked.hashCode)
    assert(!checked.toString.contains(source.canonicalText))
    assert(RecallGraph.validated(lying).isInvalid)
  }
