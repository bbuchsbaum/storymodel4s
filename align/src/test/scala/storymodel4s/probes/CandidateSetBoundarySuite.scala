package candidateprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** External callers must admit historical candidate declarations through the checked factory. */
class CandidateSetBoundarySuite extends FunSuite:
  test("plain-anchor factory and candidate inspection remain public") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; val c = CandidateSet.of(Vector.empty[SourceNodeRef]); (c.nominations, c.abstained, c.ranked, c.without("channel"))"""
      ),
      Nil
    )
  }

  test("candidate set has no direct constructor bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(nominations: Vector[Nomination], abstained: Boolean); new Control(Vector.empty, false)"""
      ),
      Nil
    )
    assert(typeCheckErrors("""new storymodel4s.align.CandidateSet(Vector.empty, false)""").nonEmpty)
  }

  test("candidate set has no unchecked apply bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(nominations: Vector[Nomination], abstained: Boolean); Control(Vector.empty, false)"""
      ),
      Nil
    )
    assert(typeCheckErrors("""storymodel4s.align.CandidateSet(Vector.empty, false)""").nonEmpty)
  }

  test("candidate set has no copy bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(nominations: Vector[Nomination], abstained: Boolean); (c: Control) => c.copy(abstained = true)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """(c: storymodel4s.align.CandidateSet) => c.copy(abstained = true)"""
      ).nonEmpty
    )
  }

  test("candidate set has no fromProduct bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(nominations: Vector[Nomination], abstained: Boolean); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((Vector.empty[Nomination], false))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.align.CandidateSet.fromProduct((Vector.empty[storymodel4s.align.Nomination], false))"""
      ).nonEmpty
    )
  }

  test("candidate set has no Mirror bypass") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; case class Control(nominations: Vector[Nomination], abstained: Boolean); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.CandidateSet]]"""
      ).nonEmpty
    )
  }
