package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.story.{AlignmentSource, TextAlignmentSource}

class D1aAlignmentBoundarySuite extends FunSuite:
  private val generalDependency = classOf[AlignmentSource]
  private val textDependency = classOf[TextAlignmentSource]

  test("accepting control: validated and adjudicated factories preserve source capabilities"):
    assert(generalDependency != null && textDependency != null)
    assert(typeChecks("""
      import storymodel4s.story.*
      def general(v: StoryModel[ModelStatus.Validated], a: StoryModel[ModelStatus.Adjudicated], n: NarrativeNodeId) =
        (AlignmentSource(v).evidenceOf(n), AlignmentSource.adjudicated(a).primaryOf(n))
      def text(v: TextModel[ModelStatus.Validated], a: TextModel[ModelStatus.Adjudicated], n: NarrativeNodeId) =
        (AlignmentSource(v).sourceSupport(n), AlignmentSource.adjudicated(a).text)
    """))

  test("general alignment source cannot be externally implemented"):
    assert(!typeChecks("""
      abstract class Forged extends storymodel4s.story.AlignmentSource
    """))

  test("text alignment source cannot be externally implemented"):
    assert(!typeChecks("""
      abstract class Forged extends storymodel4s.story.TextAlignmentSource
    """))

  test("general validated factory refuses Draft"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(m: StoryModel[ModelStatus.Draft]) = AlignmentSource(m)
    """))

  test("general adjudicated factory refuses Draft"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(m: StoryModel[ModelStatus.Draft]) = AlignmentSource.adjudicated(m)
    """))

  test("text validated factory refuses Draft"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(m: TextModel[ModelStatus.Draft]) = AlignmentSource(m)
    """))

  test("text adjudicated factory refuses Draft"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(m: TextModel[ModelStatus.Draft]) = AlignmentSource.adjudicated(m)
    """))

  test("general source has no text span accessor"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def text(s: AlignmentSource, n: NarrativeNodeId) = s.sourceSupport(n)
    """))

  test("general source has no canonical text witness"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def text(s: AlignmentSource) = s.text
    """))
