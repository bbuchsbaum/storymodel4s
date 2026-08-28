package storymodel4s.laws

import org.scalacheck.Arbitrary
import org.scalacheck.Prop.*
import org.typelevel.discipline.Laws
import storymodel4s.core.*

/** Discipline rule set for an [[Addressable]] instance (ADR 0002 §4).
  *
  * Laws: `parse ∘ address = Some`; addresses carry the instance's tag; a foreign-tagged address
  * never parses; `address` is injective; and the wire rendering round-trips through `Address.parse`
  * so the same address survives codec and URL boundaries unchanged.
  */
object AddressableLaws extends Laws:
  def addressable[A](name: String)(using
      arb: Arbitrary[A],
      ev: Addressable[A],
      addr: Arbitrary[Address]
  ): RuleSet =
    new DefaultRuleSet(
      s"addressable.$name",
      None,
      "parse(address(a)) == Some(a)" -> forAll { (a: A) => ev.parse(ev.address(a)) == Some(a) },
      "address(a).tag == tag" -> forAll { (a: A) => ev.address(a).tag == ev.tag },
      "foreign tag parses to None" -> forAll { (x: Address) =>
        (x.tag != ev.tag) ==> (ev.parse(x) == None)
      },
      "address is injective" -> forAll { (a: A, b: A) =>
        (a == b) == (ev.address(a) == ev.address(b))
      },
      "wire form round-trips" -> forAll { (a: A) =>
        val x = ev.address(a)
        Address.parse(x.render) == Right(x)
      },
      "wire form has no whitespace or control characters" -> forAll { (a: A) =>
        !ev.address(a).render.exists(c => c.isWhitespace || c.isControl)
      }
    )
