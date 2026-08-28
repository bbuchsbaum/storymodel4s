package storymodel4s.proposition

import cats.Order
import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.*

/** Hand-rolled `Order` laws (Discipline is not on this module's test classpath): the order must be
  * total, antisymmetric, transitive, and — the review finding — consistent with `==`.
  */
class OrderLawsSuite extends ScalaCheckSuite:
  private val sourceRole: Gen[SourceRole] = Gen.oneOf(
    Gen.chooseNum(0, 9).map(SourceRole.Numbered.apply),
    Gen.oneOf("location", "x", ":x", "time").map(SourceRole.Named.apply),
    Gen.chooseNum(1, 3).map(SourceRole.Operand.apply),
    Gen.oneOf(("", "x"), ("ns", "x"), ("a", "b")).map(SourceRole.Extension.apply)
  )
  private given Arbitrary[SourceRole] = Arbitrary(sourceRole)
  private given Arbitrary[Concept] = Arbitrary(ChartGens.concept)

  private def laws[A: Order: Arbitrary](name: String): Unit =
    property(s"$name: compare == 0 iff equal") {
      forAll { (a: A, b: A) => (Order[A].compare(a, b) == 0) == (a == b) }
    }
    property(s"$name: antisymmetric") {
      forAll { (a: A, b: A) =>
        Order[A].compare(a, b).sign == -Order[A].compare(b, a).sign
      }
    }
    property(s"$name: transitive") {
      forAll { (a: A, b: A, c: A) =>
        !(a <= b && b <= c) || a <= c
      }
    }

  laws[SourceRole]("Order[SourceRole]")
  laws[Concept]("Order[Concept]")

  test("Extension(\"\", x) and Named(\":x\") are neither equal nor Order-equal") {
    val e: SourceRole = SourceRole.Extension("", "x")
    val n: SourceRole = SourceRole.Named(":x")
    assertEquals(e.render, n.render)
    assert(e != n)
    assertNotEquals(Order[SourceRole].compare(e, n), 0)
  }
