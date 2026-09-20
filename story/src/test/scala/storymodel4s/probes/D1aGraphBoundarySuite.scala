package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.story.NarrativeGraph

class D1aGraphBoundarySuite extends FunSuite:
  private val dependsOn = classOf[NarrativeGraph]
  test("accepting control: external checked pre-model ordering and model reads compile"):
    assert(dependsOn != null)
    assert(typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def checked(graph: NarrativeGraph, bundle: SourceBundle) = graph.discourseOrderOn(bundle)
      def read(model: StoryModel[ModelStatus.Draft], context: ContextId) =
        (model.discourseOrder, model.discoursePosition, model.situationsByEntity,
          model.situationsByContext, model.situationsWithin(context))
      def textRead(model: TextModel[ModelStatus.Draft], span: TextSpan) =
        model.situationsCovering(span)
    """))

  test("unbound graph discourse order is internal"):
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def read(g: NarrativeGraph, b: SourceBundle) = g.discourseOrder(b)
    """))
  test("unbound graph discourse position is internal"):
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def read(g: NarrativeGraph, order: Vector[SituationId]) = g.discoursePosition(order)
    """))
  test("unbound graph entity ordering is internal"):
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def read(g: NarrativeGraph, order: Vector[SituationId]) = g.situationsByEntity(order)
    """))
  test("unbound graph context ordering is internal"):
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def read(g: NarrativeGraph, order: Vector[SituationId]) = g.situationsByContext(order)
    """))
  test("unbound graph within ordering is internal"):
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def read(g: NarrativeGraph, c: ContextId, order: Vector[SituationId]) = g.situationsWithin(c, order)
    """))
  test("unbound graph covering ordering is internal"):
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def read(g: NarrativeGraph, s: TextSpan, order: Vector[SituationId]) = g.situationsCovering(s, order)
    """))
