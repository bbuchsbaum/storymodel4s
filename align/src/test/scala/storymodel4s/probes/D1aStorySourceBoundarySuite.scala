package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.align.bridge.StorySourceView

class D1aStorySourceBoundarySuite extends FunSuite:
  private val dependsOn = classOf[StorySourceView]

  test("accepting control: text sources and status checked text models construct a view"):
    assert(dependsOn != null)
    assert(typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.align.bridge.StorySourceView
      def source(s: TextAlignmentSource) = StorySourceView(s)
      def validated(m: TextModel[ModelStatus.Validated]) = StorySourceView.validated(m)
      def adjudicated(m: TextModel[ModelStatus.Adjudicated]) = StorySourceView.adjudicated(m)
    """))

  test("general source cannot construct a text view"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.align.bridge.StorySourceView
      def forge(s: AlignmentSource) = StorySourceView(s)
    """))

  test("source cannot be paired with an independent text model"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.align.bridge.StorySourceView
      def forge(s: TextAlignmentSource, unrelated: TextModel[ModelStatus.Validated]) =
        StorySourceView(s, unrelated)
    """))

  test("validated view factory refuses Draft"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.align.bridge.StorySourceView
      def forge(m: TextModel[ModelStatus.Draft]) = StorySourceView.validated(m)
    """))

  test("adjudicated view factory refuses Draft"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.align.bridge.StorySourceView
      def forge(m: TextModel[ModelStatus.Draft]) = StorySourceView.adjudicated(m)
    """))
