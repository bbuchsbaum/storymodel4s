package storymodel4s.consumer

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Public-package proof that NFRD internals do not become an unchecked corpus-loader API. */
class NfrdIntakePublicSuite extends FunSuite:
  test("the aggregate command-line court is not library-public vocabulary") {
    assert(
      typeCheckErrors(
        """storymodel4s.bench.nfrd.NfrdBaseballCourt.main(Array.empty[String])"""
      ).nonEmpty
    )
  }

  test("a consumer cannot obtain or weaken the production intake specification") {
    assert(
      typeCheckErrors(
        """storymodel4s.bench.nfrd.NfrdBaseballSpec.production"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(x: storymodel4s.bench.nfrd.NfrdBaseballSpec) => x.copy()"""
      ).nonEmpty
    )
  }

  test("verified participant and receipt constructors are not public") {
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.bench.nfrd.VerifiedParticipant]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """storymodel4s.bench.nfrd.NfrdBaseballReceipt.fromProduct"""
      ).nonEmpty
    )
  }
