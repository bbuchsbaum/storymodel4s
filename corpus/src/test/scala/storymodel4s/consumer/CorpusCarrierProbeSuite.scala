package storymodel4s.consumer

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Out-of-package proof that the intake carriers cannot be forged (ADR 0018 §2).
  *
  * This suite is deliberately NOT in `storymodel4s.corpus`. `private[corpus]` admits the whole
  * `storymodel4s.corpus.*` tree, so a probe inside that tree cannot tell a closed door from an open
  * one — it would have access either way.
  *
  * Every negative assertion is paired with a POSITIVE CONTROL in the same file and scope. A bare
  * `typeCheckErrors(...).nonEmpty` passes on *any* compile error, including a typo in the probe
  * itself or a package that does not resolve, so without a control that compiles, a probe can pass
  * for a reason unrelated to the door it claims to test. That has already happened twice in this
  * repository (`docs/design/unforgeable-types.md`).
  */
class CorpusCarrierProbeSuite extends FunSuite:

  test("positive control: the public surface this suite names does resolve") {
    assertEquals(
      typeCheckErrors("""storymodel4s.corpus.ArtifactId.unsafe("a.xlsx")"""),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """(c: storymodel4s.corpus.SourceCoordinate) => (c.row, c.column, c.toString)"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("""(r: storymodel4s.corpus.Raw[Int]) => (r.value, r.literal, r.at)"""),
      Nil
    )
  }

  test("a consumer cannot construct a SourceCoordinate") {
    assert(
      typeCheckErrors(
        """new storymodel4s.corpus.SourceCoordinate(
             storymodel4s.corpus.ArtifactId.unsafe("a.xlsx"), "s1", 1, "c")"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """storymodel4s.corpus.SourceCoordinate.at(
             storymodel4s.corpus.ArtifactId.unsafe("a.xlsx"), "s1", 1, "c")"""
      ).nonEmpty
    )
  }

  test("a consumer cannot reach a SourceCoordinate through Mirror or copy") {
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.corpus.SourceCoordinate]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(c: storymodel4s.corpus.SourceCoordinate) => c.copy()"""
      ).nonEmpty
    )
  }

  /** The joined claim is the thing being protected: that THIS value is what THIS literal decoded
    * to, at THIS place. A consumer holding a genuine coordinate must not be able to re-use it under
    * a value the source never carried.
    */
  test("a consumer cannot re-join a genuine coordinate to a fabricated value") {
    assert(
      typeCheckErrors(
        """(c: storymodel4s.corpus.SourceCoordinate) =>
             new storymodel4s.corpus.Raw[Int](99, c, "anything")"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(c: storymodel4s.corpus.SourceCoordinate) =>
             storymodel4s.corpus.Raw.of[Int](99, c, "anything")"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(r: storymodel4s.corpus.Raw[Int]) => r.copy(value = 99)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.corpus.Raw[Int]]]"""
      ).nonEmpty
    )
  }
