package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks

class StoryModelUnforgeableSuite extends FunSuite:
  test("Validated cannot be claimed through Mirror.fromProduct") {
    assert(
      typeChecks("""
        import storymodel4s.story.*
        def consumeValidated(model: StoryModel[ModelStatus.Validated]): String = model.schemaVersion
      """)
    )
    assert(typeChecks("summon[scala.deriving.Mirror.ProductOf[(Int, String)]]"))
    assert(
      !typeChecks("""
        import scala.deriving.Mirror
        import storymodel4s.story.*
        val draft = Small.build(n = 1, m = 1).draft()
        val forged: StoryModel[ModelStatus.Validated] =
          summon[Mirror.ProductOf[StoryModel[ModelStatus.Validated]]].fromProduct(draft)
      """)
    )
    assert(
      !typeChecks("""
        import storymodel4s.story.*
        val draft = Small.build(n = 1, m = 1).draft()
        val forged: StoryModel[ModelStatus.Validated] = draft.copy[ModelStatus.Validated]()
      """)
    )
    assert(
      !typeChecks("""
        import storymodel4s.story.*
        val draft = Small.build(n = 1, m = 1).draft()
        val product: Product = draft
      """)
    )
  }

  test("plain-class replacement preserves value semantics without payload diagnostics") {
    val a = storymodel4s.story.Small.build(n = 1, m = 1).draft()
    val b = storymodel4s.story.Small.build(n = 1, m = 1).draft()

    assertEquals(a, b)
    assertEquals(a.hashCode, b.hashCode)
    assert(!a.toString.contains("Sentence number"))
  }
