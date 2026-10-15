package storymodel4s.codec

import munit.FunSuite
import storymodel4s.core.*

/** The ordered writer must preserve every claim observable through ledger lookup. */
class ClaimLedgerIntegritySuite extends FunSuite:
  private val metas =
    Vector("b", "a", "c").map(id => CodecFixture.meta(id, EpistemicStatus.Hypothesized, None))
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  test("builder keys, insertion order and JSONL lookup roundtrip agree exactly") {
    val ledger = right(ClaimLedger.empty.addAll(metas))
    assertEquals(ledger.order, metas.map(_.id))
    assertEquals(ledger.claims.keySet, ledger.order.toSet)
    assertEquals(ledger.size, metas.size)
    assertEquals(ledger.all, metas)
    val text = JsonLines.claims(ledger)
    assertEquals(text.count(_ == '\n'), metas.size)
    val decoded = right(JsonLines.readClaims(text))
    assertEquals(decoded, ledger)
    assertEquals(decoded.hashCode, ledger.hashCode)
    assertEquals(decoded.claims, ledger.claims)
    assertEquals(decoded.order, ledger.order)
    metas.foreach(m => assertEquals(decoded.lookup(m.id), Some(m)))
    assertEquals(JsonLines.claims(decoded), text)
  }

  test("duplicate insertion and duplicate JSONL refuse without changing the original ledger") {
    val ledger = right(ClaimLedger.empty.addAll(metas))
    assert(ledger.add(metas.head).isLeft)
    val text = JsonLines.claims(ledger)
    assert(JsonLines.readClaims(text + text).isLeft)
    assertEquals(ledger.all, metas)
    assertEquals(ledger.claims.size, 3)
  }

  test("ledger equality preserves order and empty roundtrip") {
    val forward = right(ClaimLedger.empty.addAll(metas))
    val backward = right(ClaimLedger.empty.addAll(metas.reverse))
    assertNotEquals(forward, backward)
    assertEquals(JsonLines.claims(ClaimLedger.empty), "")
    assertEquals(right(JsonLines.readClaims("")), ClaimLedger.empty)
    assertEquals(ClaimLedger.empty.toString, "ClaimLedger(0 claims)")
  }
