package storymodel4s.bench.video

import java.time.LocalDate

/** The world-order declarations synthetic fixtures build under. Test material authored in story
  * order says so here, once, so no suite reaches for a default the builder no longer has.
  */
object WorldOrderFixtures:
  val syntheticLinear: WorldOrderInput = WorldOrderInput.SameAsPresentation(
    WorldOrderWitness(
      assertedBy = "fixture",
      basis = "synthetic material authored in story order",
      asserted = LocalDate.of(2026, 9, 4)
    )
  )

  /** The witness for a rank a suite writes by hand. */
  val handRank: WorldOrderWitness =
    WorldOrderWitness("fixture", "rank written by hand in the suite", LocalDate.of(2026, 9, 4))
