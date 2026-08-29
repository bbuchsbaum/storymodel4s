package storymodel4s.embedprobe

import munit.FunSuite

import storymodel4s.core.{Checksum, Fingerprint}
import storymodel4s.embed.{LatePoolingRecipe, PoolingRule, UncoveredPolicy}

/** Live hole, demonstrated from OUTSIDE `storymodel4s.embed` before the door is closed.
  *
  * `LatePoolingRecipe.validated` already refuses `window = 0`. `fromProduct` still mints that
  * recipe. This suite is the before-picture.
  */
class LatePoolingForgeSuite extends FunSuite:

  private def invalidWindow: LatePoolingRecipe =
    LatePoolingRecipe(
      Checksum.ofText("query"),
      Fingerprint.unsafe("tok-a"),
      512,
      0,
      64,
      "mean",
      PoolingRule.Mean,
      UncoveredPolicy.PartialCoverage,
      None
    )

  test("validated refuses window = 0") {
    assert(LatePoolingRecipe.validated(invalidWindow).isLeft)
  }

  test("fromProduct mints the recipe that validated refused") {
    val forged = LatePoolingRecipe.fromProduct(
      (
        Checksum.ofText("query"),
        Fingerprint.unsafe("tok-a"),
        512,
        0,
        64,
        "mean",
        PoolingRule.Mean,
        UncoveredPolicy.PartialCoverage,
        None
      )
    )
    assertEquals(forged.window, 0)
    assert(LatePoolingRecipe.validated(forged).isLeft)
  }
