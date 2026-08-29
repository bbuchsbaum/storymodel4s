package storymodel4s.align

import munit.FunSuite

/** External mass must not let our failure to align be read as the participant's behaviour.
  *
  * `unrankedMass` is the destination of a unit the aligner could not rank at all. The other five
  * external terms are claims about the person. Summing them produces a plausible number that moves
  * in one direction exactly when alignment is hardest — which is exactly when a study's groups
  * differ.
  */
class SignatureSuite extends FunSuite:

  private val eps = 1e-12

  private def report(attributed: Double, unranked: Double) =
    ExternalMassReport(attributed, unranked)

  test("attributed mass and unranked mass are reported separately") {
    val r = report(0.2, 0.5)
    assertEqualsDouble(r.attributed, 0.2, eps)
    assertEqualsDouble(r.unranked, 0.5, eps)
  }

  test("two accounts with identical participant behaviour report the same attributed mass") {
    // The only difference is how much the aligner could rank. If unranked mass were folded in, the
    // harder-to-align account would look like it produced more external content, which is the
    // manufactured group difference this split exists to prevent.
    val easy = report(attributed = 0.2, unranked = 0.0)
    val hard = report(attributed = 0.2, unranked = 0.6)
    assertEqualsDouble(easy.attributed, hard.attributed, eps)
    assert(easy.unranked < hard.unranked)
    assert(easy.rankedMass > hard.rankedMass)
  }

  test("ranked mass is the coverage of the attributed number") {
    assertEqualsDouble(report(0.1, 0.0).rankedMass, 1.0, eps)
    assertEqualsDouble(report(0.1, 0.25).rankedMass, 0.75, eps)
    assertEqualsDouble(report(0.0, 1.0).rankedMass, 0.0, eps)
  }

  test("the render carries the caveat with the number, never the number alone") {
    val r = report(0.2, 0.6).render
    assert(r.contains("unranked"), r)
    assert(r.contains("ranked"), r)
  }

  test("there is no accessor that returns the conflated sum") {
    // A structural tripwire, not a comment: if a `total` (or any accessor handing back
    // attributed + unranked) is ever added, the number becomes quotable without its caveat again
    // and this assertion fails. Checked at compile time so it holds on every platform.
    //
    // Caveat, learned the hard way: a compile-time check only re-evaluates when THIS FILE is
    // recompiled. Adding `total` to signature.scala alone will not trip it in an incremental loop
    // (touching the file is not enough - sbt tracks content, not mtime). A clean build or any edit
    // here does. Verified by mutation: with `total` present and this file recompiled, the check
    // reports true and the test fails.
    assert(
      !scala.compiletime.testing.typeChecks("ExternalMassReport(0.1, 0.2).total"),
      "ExternalMassReport.total exists; it re-creates the conflation this type prevents"
    )
    assert(
      !scala.compiletime.testing.typeChecks("ExternalMassReport(0.1, 0.2).externalMass"),
      "an accessor named externalMass on the report would invite the same misreading"
    )
    // The control: the accessors that SHOULD exist do.
    assert(scala.compiletime.testing.typeChecks("ExternalMassReport(0.1, 0.2).attributed"))
    assert(scala.compiletime.testing.typeChecks("ExternalMassReport(0.1, 0.2).unranked"))
  }
