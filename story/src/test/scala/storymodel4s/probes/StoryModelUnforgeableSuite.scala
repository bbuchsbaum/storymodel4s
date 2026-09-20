package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks

class StoryModelUnforgeableSuite extends FunSuite:
  // Macro snippets are strings, so this real reference is what makes Zinc invalidate the court
  // when StoryModel changes instead of retaining a stale compile-time answer.
  private val dependsOn: Class[?] =
    classOf[storymodel4s.story.StoryModel[?]]

  test("accepting control: typed model consumer and tuple Product/Mirror compile"):
    assert(dependsOn != null)
    assert(typeChecks("""
      import storymodel4s.story.*
      def consume(model: StoryModel[ModelStatus.Draft]): String = model.schemaVersion
      val product: Product = (1, "text")
      summon[scala.deriving.Mirror.ProductOf[(Int, String)]]
    """))

  test("model has no synthesizable Product Mirror"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      summon[scala.deriving.Mirror.ProductOf[StoryModel[ModelStatus.Validated]]]
    """))

  test("external callers cannot invoke internal copy"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def copyModel(model: StoryModel[ModelStatus.Draft]) = model.copy[ModelStatus.Validated]()
    """))

  test("model is not a Product"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def product(model: StoryModel[ModelStatus.Draft]): Product = model
    """))

  test("plain-class replacement preserves value semantics without payload diagnostics") {
    val a = storymodel4s.story.Small.build(n = 1, m = 1).draft()
    val b = storymodel4s.story.Small.build(n = 1, m = 1).draft()

    assertEquals(a, b)
    assertEquals(a.hashCode, b.hashCode)
    assert(!a.toString.contains("Sentence number"))
  }
