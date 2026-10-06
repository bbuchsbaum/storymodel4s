package storymodel4s.align

import munit.FunSuite

/** The original diagnostic toString/regex pin is JVM-only; floating fields are pinned portably. */
class ChartedScoringDisplayParityProbe extends FunSuite:
  import ChartedScoringParityFixture.*

  test("chart compatibility over the pinned pair corpus is byte-identical to the pre-port base") {
    assertEquals(pins._1, "a6ca0861c4e3a5a7c9d2008ba8357e67477dc40f7f38a3fa0e2c36ac4e04a3ee")
  }
