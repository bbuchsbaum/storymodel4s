package storymodel4s.align.attack {

  import scala.compiletime.testing.typeCheckErrors

  /** Construction probes compiled INSIDE an `align` subpackage, where every `private[align]` member
    * is visible (Fray #58, review seq 530). Strict candidates carry the authority of the tie
    * policy, so a subpackage must not be able to rebuild them around an honest binding with the
    * overflow erased.
    *
    * `typeCheckErrors` is a compile-time macro: a production mutation is seen only after a clean
    * test compile.
    */
  private[storymodel4s] object StrictPackageAttack:
    // POSITIVE CONTROL: a qualified-private constructor of the same package IS open here, so the
    // refusals below are about bare privacy, not about reaching the package at all.
    val qualifiedControl = typeCheckErrors(
      "import storymodel4s.align.*; new TieOverflow(1, 3, 2)"
    )
    // The checked issuance path stays reachable.
    val checkedDoor = typeCheckErrors(
      """import storymodel4s.align.*
       StrictCandidateGenerator(???, ???).generate(???, ???)
       """
    )

    val setConstructor = typeCheckErrors(
      "import storymodel4s.align.*; new StrictCandidateSet(???, Vector.empty, Vector.empty, Vector.empty)"
    )
    val candidatesConstructor = typeCheckErrors(
      "import storymodel4s.align.*; new StrictCandidates(???, ???, ???, None)"
    )
    val strictProvenanceConstructor = typeCheckErrors(
      "import storymodel4s.align.*; new CandidateProvenance.Strict(???, Vector.empty, Vector.empty, None, Vector.empty)"
    )
    val setMirror = typeCheckErrors(
      "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StrictCandidateSet]]"
    )
    val candidatesMirror = typeCheckErrors(
      "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StrictCandidates]]"
    )
}

package storymodel4s.probes {

  import munit.FunSuite

  /** The construction boundary of strict candidates, probed from an `align` subpackage
    * (`StrictPackageAttack`). Only checked generation issues
    * [[storymodel4s.align.StrictCandidates]] and their per-unit sets, so tie overflow cannot be
    * erased while strict authority is kept.
    */
  class StrictCandidatesUnforgeableSuite extends FunSuite:
    import storymodel4s.align.attack.StrictPackageAttack as attack

    private def refused(door: String, errors: List[scala.compiletime.testing.Error]): Unit =
      assert(errors.nonEmpty, s"$door is open")

    test("an align subpackage: controls compile, every strict-candidate door is closed") {
      assertEquals(attack.qualifiedControl, Nil, "a private[align] constructor must be open here")
      assertEquals(attack.checkedDoor, Nil, "checked generation must stay reachable")
      refused("StrictCandidateSet constructor", attack.setConstructor)
      refused("StrictCandidates constructor", attack.candidatesConstructor)
      refused("CandidateProvenance.Strict constructor", attack.strictProvenanceConstructor)
      refused("Mirror.ProductOf[StrictCandidateSet]", attack.setMirror)
      refused("Mirror.ProductOf[StrictCandidates]", attack.candidatesMirror)
    }
}
