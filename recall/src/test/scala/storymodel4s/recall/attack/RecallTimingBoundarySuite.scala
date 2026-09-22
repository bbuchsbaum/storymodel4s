package storymodel4s.recall.attack

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class RecallTimingBoundarySuite extends FunSuite:
  test("checked timing cannot be forged by a recall subpackage") {
    assert(typeCheckErrors("""new storymodel4s.recall.RecallTiming(???, ???, ???, ???)""").nonEmpty)
    assert(typeCheckErrors("""val t: storymodel4s.recall.RecallTiming = ???; t.copy()""").nonEmpty)
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.recall.RecallTiming]]"""
      ).nonEmpty
    )
  }
