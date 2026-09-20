package storymodel4s.story.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.story.StoryModel

/** Even the story package must use the checked factory/copy, never a caller-provided order. */
class D1aModelConstructorSuite extends FunSuite:
  private val dependsOn = classOf[StoryModel[?]]
  test("accepting control: model factory is available inside the story package"):
    assert(dependsOn != null)
    assert(typeChecks("""
      import storymodel4s.story.*
      def remake(m: StoryModel[ModelStatus.Draft]) =
        StoryModel.draft(m.source, m.atlas, m.graph, m.hierarchy, m.trajectory)
    """))
  test("direct model constructor is private even inside the story package"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def bypass(m: StoryModel[ModelStatus.Draft]) =
        new StoryModel[ModelStatus.Draft](m.schemaVersion, m.source, m.atlas, m.graph,
          m.hierarchy, m.trajectory, m.featureSpaces, m.sidecars, m.featureRefs,
          m.descriptors, m.hypotheses, m.sensoryProfiles, m.receipt, m.discourseOrder)
    """))
