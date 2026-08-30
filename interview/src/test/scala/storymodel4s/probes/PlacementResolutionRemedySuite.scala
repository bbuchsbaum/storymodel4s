package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks

/** Does the sweep-1 remedy STILL defeat Mirror derivation in Scala 3.7.4?
  *
  * bd-01M19JBF0X827AS3J863TX1J8E asks for the four Structural* types to be sealed with "a plain
  * final class with a private constructor and explicit accessors", and the chief's instruction was
  * explicit: CONFIRM THE REMEDY BEFORE APPLYING IT FOUR TIMES, rather than assuming it from the
  * sweep-1 write-up. This is that confirmation, and it is run against a type that ALREADY HAS the
  * remedy rather than a synthetic example: `PlacementResolution` was converted to a plain final
  * class with a private constructor precisely because a case class with a private constructor still
  * derives a Mirror whose fromProduct walks past `of`.
  *
  * If the remedy has drifted, sealing four more types the same way would be futile AND
  * PlacementResolution is already affected - it carries an abstention threshold and three masses
  * whose partition-of-unity invariant `of` enforces and fromProduct would not.
  *
  * Probed from `storymodel4s.probes`, outside the type's own package, because that is where the
  * boundary actually bites.
  */
class PlacementResolutionRemedySuite extends FunSuite:

  test("the remedy still defeats Mirror derivation in this Scala version") {
    // MATCHED CONTROLS FIRST. typeChecks returns false for ANY compile failure, so a bare negative
    // proves nothing: one control must show the probe can see the package, and one must show it
    // returns false for something genuinely absent.
    assert(
      typeChecks("import storymodel4s.interview.scoring.*; classOf[PlacementResolution]"),
      "control: the type must be visible from the probe package"
    )
    assert(
      !typeChecks("import storymodel4s.interview.scoring.*; classOf[NoSuchTypeExistsHere]"),
      "control: typeChecks must return false for a type that does not exist"
    )
    // A case class in the same package DOES derive a Mirror from here, which proves the summon
    // itself is expressible in this context - without this, the negative below could be failing
    // because Mirror.ProductOf is unavailable rather than because the remedy works.
    assert(
      typeChecks(
        "import storymodel4s.interview.*; summon[scala.deriving.Mirror.ProductOf[DetailAssessment]]"
      ),
      "control: a case class here DOES derive a Mirror, so the summon is expressible"
    )

    // THE QUESTION.
    assert(
      !typeChecks(
        "import storymodel4s.interview.scoring.*; summon[scala.deriving.Mirror.ProductOf[PlacementResolution]]"
      ),
      "THE SWEEP-1 REMEDY HAS DRIFTED: a plain final class with a private constructor now derives a Mirror"
    )
    assert(
      !typeChecks(
        "import storymodel4s.interview.scoring.*; PlacementResolution.fromProduct(???)"
      ),
      "the companion exposes fromProduct despite the remedy"
    )
  }
