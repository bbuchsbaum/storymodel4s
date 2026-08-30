package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks

/** The construction boundary of the four structural-channel types, probed FROM OUTSIDE `align`.
  *
  * THESE COURTS PIN A KNOWN-BAD STATE. Every negative below asserts that a door IS open, because it
  * is. Measured 2026-08-30 from this package:
  *
  * {{{
  * StructuralMemberEstimate    mirror=true  fromProduct=true  copy=false  apply=false
  * StructuralMemberExclusion   mirror=true  fromProduct=true  copy=false  apply=false
  * StructuralReduction         mirror=true  fromProduct=true  copy=false  apply=false
  * StructuralReductionReceipt  mirror=true  fromProduct=true  copy=false  apply=false
  * }}}
  *
  * `private[align]` closes the two doors people look at -- `apply` and `copy` -- and leaves the two
  * they do not. A `Mirror.ProductOf` derives from any package, and `fromProduct` takes a bare
  * tuple, so all four types are constructible from anywhere by anyone who writes one line.
  *
  * WHY THIS MATTERS MORE HERE THAN FOR AN ORDINARY RECORD. `StructuralReductionReceipt` is an AUDIT
  * RECEIPT: its scaladoc says so, and its fields are the attestation -- which reducer ran, which
  * members it estimated, which it excluded, and the coverage over both the source chart and the
  * observed estimates. A forgeable audit receipt is a receipt that can attest a reduction THAT
  * NEVER RAN, with any coverage its author likes. `StructuralReduction` then pairs an `Estimate`
  * with a receipt, so the forge also builds the FALSE TUPLE directly: an estimate from one
  * computation carrying the receipt of another. That is the defect the private constructor was
  * added to prevent, and it does not prevent it.
  *
  * THESE ASSERTIONS ARE MEANT TO FAIL EVENTUALLY. When the boundary is repaired -- see the bead for
  * the fix -- these courts go red, and the repairer must flip each `assert(typeChecks(...))` to
  * `assert(!typeChecks(...))` DELIBERATELY. That is the point: a silent repair and a silent
  * regression should not look alike.
  */
class StructuralChannelUnforgeableSuite extends FunSuite:

  // POSITIVE CONTROLS FIRST, same shape as the assertions below. `typeChecks` returns false when a
  // snippet fails for ANY reason -- a typo, a missing import, an unrelated error -- so a court
  // without a matching control proves nothing about the door it names.
  test("control: the probe mechanism works and the types are visible from here") {
    assert(
      typeChecks("import storymodel4s.align.*; classOf[StructuralReduction]"),
      "control: the type itself is visible outside align, so a false negative below would be about the DOOR"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; NoSuchTypeInAlign42"),
      "control: typeChecks returns FALSE for a snippet that cannot compile, so `true` below means something"
    )
  }

  test("apply and copy ARE closed by private[align] -- the two doors that get looked at") {
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralMemberEstimate(???, ???)"),
      "apply is reachable from outside align"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : StructuralMemberEstimate).copy()"),
      "copy is reachable from outside align"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; StructuralReductionReceipt(???, ???, ???, ???, ???)"
      ),
      "apply is reachable from outside align"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : StructuralReductionReceipt).copy()"),
      "copy is reachable from outside align"
    )
  }

  test("the Mirror door is CLOSED on all four structural types") {
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StructuralMemberEstimate]]"
      ),
      "the Mirror/fromProduct door is open again: the seal has regressed"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StructuralMemberExclusion]]"
      ),
      "the Mirror/fromProduct door is open again: the seal has regressed"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StructuralReduction]]"
      ),
      "the Mirror/fromProduct door is open again: the seal has regressed"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StructuralReductionReceipt]]"
      ),
      "the Mirror/fromProduct door is open again: the seal has regressed"
    )
  }

  test("the companion fromProduct door is CLOSED on all four structural types") {
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralMemberEstimate.fromProduct(???)"),
      "the Mirror/fromProduct door is open again: the seal has regressed"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralMemberExclusion.fromProduct(???)"),
      "the Mirror/fromProduct door is open again: the seal has regressed"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralReduction.fromProduct(???)"),
      "the Mirror/fromProduct door is open again: the seal has regressed"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralReductionReceipt.fromProduct(???)"),
      "the Mirror/fromProduct door is open again: the seal has regressed"
    )
  }
