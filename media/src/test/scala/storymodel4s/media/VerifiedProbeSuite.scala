package storymodel4s.media

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** D7's second half: the probe's check compared a checksum the CALLER supplied.
  *
  * `declaredIdentityAgrees` (formerly named `verifyInput`) establishes that two DECLARATIONS agree
  * -- the caller's number and the manifest's -- not that any bytes hash to either. It cannot detect
  * a caller that computed its checksum over something else. `FixtureManifest.verify(bytes)` has
  * always existed and was never on a main path.
  *
  * The behavioural property -- that a `VerifiedArtifact`'s identity is observed here rather than
  * asserted -- is tested in `corpus-intake`, where a `Verified` is reachable. A `SourceManifest`
  * cannot be minted from this package, and should not be: it is a claim, not a validator.
  */
class VerifiedProbeSuite extends FunSuite:

  test("there is a door that takes bytes this process hashed") {
    assertEquals(
      typeCheckErrors(
        """(m: storymodel4s.media.FixtureManifest,
             v: storymodel4s.corpus.VerifiedArtifact,
             t: storymodel4s.media.ToolRealization,
             o: storymodel4s.media.FfprobeOutput) =>
             storymodel4s.media.MediaProbe.joinVerified(m, v, t, Vector.empty, o)"""
      ),
      Nil
    )
  }

  test("the weaker door is still reachable, and is named for what it does") {
    // retained because a replay envelope legitimately supplies a recorded identity rather than
    // bytes; the name no longer claims verification
    assertEquals(
      typeCheckErrors(
        """(m: storymodel4s.media.FixtureManifest,
             c: storymodel4s.core.Checksum,
             t: storymodel4s.media.ToolRealization,
             o: storymodel4s.media.FfprobeOutput) =>
             storymodel4s.media.MediaProbe.join(m, c, t, Vector.empty, o)"""
      ),
      Nil
    )
    // and the comparison helper is not public vocabulary claiming to verify anything
    assert(
      typeCheckErrors(
        """storymodel4s.media.MediaProbe.declaredIdentityAgrees"""
      ).nonEmpty
    )
  }
