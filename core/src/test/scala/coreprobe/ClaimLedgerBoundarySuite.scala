package coreprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Outside-package construction probes protect the ledger's key/order identity relation. */
class ClaimLedgerBoundarySuite extends FunSuite:
  test("ledger direct constructor refuses arbitrary key and order pairs") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.*; case class Control(claims: Map[ClaimId, ClaimMeta], order: Vector[ClaimId]); new Control(Map.empty, Vector.empty)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors("""new storymodel4s.core.ClaimLedger(Map.empty, Vector.empty)""").nonEmpty
    )
  }

  test("ledger has no unchecked apply") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.*; case class Control(claims: Map[ClaimId, ClaimMeta], order: Vector[ClaimId]); Control(Map.empty, Vector.empty)"""
      ),
      Nil
    )
    assert(typeCheckErrors("""storymodel4s.core.ClaimLedger(Map.empty, Vector.empty)""").nonEmpty)
  }

  test("ledger has no copy that can hide existing claims") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.*; case class Control(claims: Map[ClaimId, ClaimMeta], order: Vector[ClaimId]); (x: Control) => x.copy(order = Vector.empty)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """(x: storymodel4s.core.ClaimLedger) => x.copy(order = Vector.empty)"""
      ).nonEmpty
    )
  }

  test("ledger has no fromProduct that can hide or miskey claims") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.*; case class Control(claims: Map[ClaimId, ClaimMeta], order: Vector[ClaimId]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((Map.empty[ClaimId, ClaimMeta], Vector.empty[ClaimId]))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.core.*; ClaimLedger.fromProduct((Map.empty[ClaimId, ClaimMeta], Vector.empty[ClaimId]))"""
      ).nonEmpty
    )
  }

  test("ledger has no product Mirror that can duplicate order entries") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.*; case class Control(claims: Map[ClaimId, ClaimMeta], order: Vector[ClaimId]); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.ClaimLedger]]"""
      ).nonEmpty
    )
  }

  test("ledger builders and existing read access remain public") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.*; (m: ClaimMeta) => ClaimLedger.empty.addAll(Vector(m)).map(l => (l.claims, l.order, l.lookup(m.id), l.contains(m.id), l.byStatus(m.status), l.size, l.all))"""
      ),
      Nil
    )
  }
