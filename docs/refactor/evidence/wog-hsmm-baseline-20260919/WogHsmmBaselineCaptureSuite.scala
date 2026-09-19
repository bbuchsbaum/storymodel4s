package storymodel4s.fixtures.wog

import munit.FunSuite
import storymodel4s.codec.Canonical

/** Test-only instrumentation of the existing inferred WOG codec fixture. */
class WogHsmmBaselineCaptureSuite extends FunSuite:
  test("capture the existing canonical WOG HSMM output") {
    val encoded = WarOfTheGhostsCodecGolden.encoded
    assertEquals(Canonical.parse(encoded).map(Canonical.print), Right(encoded))
    println("WOG_HSMM_BASELINE_JSON=" + encoded)
  }
