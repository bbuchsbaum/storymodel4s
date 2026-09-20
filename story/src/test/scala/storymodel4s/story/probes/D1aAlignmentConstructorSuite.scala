package storymodel4s.story.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.story.AlignmentSource

class D1aAlignmentConstructorSuite extends FunSuite:
  private val dependsOn = classOf[AlignmentSource]

  test("accepting control: checked source factories remain available inside story"):
    assert(dependsOn != null)
    assert(typeChecks("""
      import storymodel4s.story.*
      def general(m: StoryModel[ModelStatus.Validated]) = AlignmentSource(m)
      def text(m: TextModel[ModelStatus.Validated]) = AlignmentSource(m)
    """))

  test("general implementation constructor is private even inside story"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(m: StoryModel[ModelStatus.Draft]) = new AlignmentSource.StoryAlignmentSource(m)
    """))

  test("text implementation constructor is private even inside story"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(m: TextModel[ModelStatus.Draft]) = new AlignmentSource.StoryTextAlignmentSource(m)
    """))
