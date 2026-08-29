package storymodel4s.core

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.*

class AddressSuite extends ScalaCheckSuite:
  import AddressKey.*
  import ModuleTag.*

  val anyString: Gen[String] =
    Gen.oneOf(
      Gen.asciiStr,
      Gen
        .listOf(Gen.chooseNum(0x20, 0xffff).map(_.toChar).suchThat(c => !Character.isSurrogate(c)))
        .map(_.mkString),
      Gen.oneOf("", "/", "%", "%2F", "a/b", "tag:kind", "x y", String.valueOf(0.toChar), "é/ß%🙂")
    )

  val tagGen: Gen[ModuleTag] =
    for
      h <- Gen.alphaLowerChar
      t <- Gen.listOf(Gen.oneOf(Gen.alphaLowerChar, Gen.numChar, Gen.const('-'))).map(_.take(31))
    yield ModuleTag.unsafe((h :: t).mkString)

  val kindGen: Gen[AddressKind] = tagGen.map(t => AddressKind.unsafe(t.value))

  val keyGen: Gen[AddressKey] =
    Gen
      .nonEmptyListOf(anyString)
      .map(ps => AddressKey.of(NonEmptyVector.fromVectorUnsafe(ps.toVector)))

  val addressGen: Gen[Address] =
    for
      t <- tagGen
      k <- kindGen
      key <- keyGen
    yield Address(t, k, key)

  val tokenRange: Gen[TokenRange] =
    for
      a <- Gen.chooseNum(0, 1000)
      b <- Gen.chooseNum(0, 1000)
    yield TokenRange.unsafe(math.min(a, b), math.max(a, b))

  val coreRef: Gen[CoreRef] = Gen.oneOf(
    Gens.idString.map(s => CoreRef.Story(StoryId.unsafe(s))),
    Gens.idString.map(s => CoreRef.SurfaceUnit(SurfaceUnitId.unsafe(s))),
    tokenRange.map(CoreRef.Tokens.apply),
    Gens.spanSet.map(CoreRef.Spans.apply),
    Gens.idString.map(s => CoreRef.Claim(ClaimId.unsafe(s))),
    Gens.idString.map(s => CoreRef.Evidence(EvidenceId.unsafe(s)))
  )

  given Arbitrary[Address] = Arbitrary(addressGen)
  given Arbitrary[CoreRef] = Arbitrary(coreRef)

  test("module tags and kinds follow the lexical rule"):
    assert(ModuleTag.from("core").isRight)
    assert(ModuleTag.from("story-view").isRight)
    assert(ModuleTag.from("Core").isLeft)
    assert(ModuleTag.from("").isLeft)
    assert(ModuleTag.from("a/b").isLeft)
    assert(ModuleTag.from("x" * 33).isLeft)
    assert(AddressKind.from("surface-unit").isRight)
    assert(AddressKind.from("1abc").isLeft)

  property("escaping round-trips any string and never emits reserved characters"):
    forAll(anyString) { s =>
      val e = AddressEscape.encode(s)
      (AddressEscape.decode(e) == Right(s)) :| s"decode($e)" &&
      (!e.exists(c => c == '/' || c.isWhitespace || c.isControl)) :| s"reserved in $e"
    }

  test("malformed escapes are rejected, not silently decoded"):
    assert(AddressEscape.decode("%").isLeft)
    assert(AddressEscape.decode("%2").isLeft)
    assert(AddressEscape.decode("%zz").isLeft)
    assertEquals(
      AddressEscape.decode("%２F"),
      Left(DomainError.InvalidFormat("AddressKey", "%２F", "malformed escape"))
    )
    assertEquals(
      AddressEscape.decode("%٢F"),
      Left(DomainError.InvalidFormat("AddressKey", "%٢F", "malformed escape"))
    )
    assert(AddressEscape.decode("a/b").isLeft)
    assert(AddressEscape.decode("a b").isLeft)

  test("malformed UTF-8 escapes are rejected, not replaced"):
    val malformed = Vector(
      "%FF", // illegal leading byte
      "%C0%AF", // overlong slash
      "%E2%82", // truncated three-byte sequence
      "%ED%A0%80", // encoded surrogate
      "%F4%90%80%80" // code point above U+10FFFF
    )
    malformed.foreach { encoded =>
      assertEquals(
        AddressEscape.decode(encoded),
        Left(DomainError.InvalidFormat("AddressKey", encoded, "malformed UTF-8"))
      )
    }
    assertEquals(AddressEscape.decode("%F0%9F%99%82"), Right("🙂"))

  property("address render/parse is a round trip"):
    forAll { (a: Address) => Address.parse(a.render) == Right(a) }

  property("rendered addresses have exactly tag/kind then escaped parts, no whitespace"):
    forAll { (a: Address) =>
      val r = a.render
      val pieces = r.split("/", -1)
      (pieces(0) == a.tag.value) && (pieces(1) == a.kind.value) &&
      (pieces.length == 2 + a.key.parts.length) && !r.exists(_.isWhitespace)
    }

  test("address parse rejects missing components and bad tags"):
    assert(Address.parse("core").isLeft)
    assert(Address.parse("core/claim").isLeft)
    assert(Address.parse("Core/claim/x").isLeft)
    assert(Address.parse("core/claim/x y").isLeft)
    assertEquals(
      Address.parse("core/claim/c1").map(_.key.parts.toVector),
      Right(Vector("c1"))
    )
    assertEquals(
      Address.parse("core/spans/0-5/7-9%40s1").map(_.key.parts.toVector),
      Right(Vector("0-5", "7-9@s1"))
    )

  property("CoreRef is addressable: parse(address(r)) == Some(r)"):
    forAll { (r: CoreRef) =>
      val ev = Addressable[CoreRef]
      val a = ev.address(r)
      (a.tag == CoreRef.Tag) :| "tag" && (ev.parse(a) == Some(r)) :| s"round trip via ${a.render}"
    }

  property("CoreRef addresses are injective"):
    forAll { (x: CoreRef, y: CoreRef) =>
      val ev = Addressable[CoreRef]
      (x == y) == (ev.address(x) == ev.address(y))
    }

  property("foreign tags and unknown kinds never parse as CoreRef"):
    forAll { (a: Address) =>
      val ev = Addressable[CoreRef]
      (a.tag != CoreRef.Tag) ==> (ev.parse(a) == None)
    }

  test("CoreRef rejects malformed keys of a known kind"):
    val ev = Addressable[CoreRef]
    def addr(kind: String, parts: String*) =
      Address(CoreRef.Tag, AddressKind.unsafe(kind), AddressKey.of(parts.head, parts.tail*))
    assertEquals(ev.parse(addr("tokens", "5", "2")), None)
    assertEquals(ev.parse(addr("tokens", "5")), None)
    assertEquals(ev.parse(addr("spans", "9-3")), None)
    assertEquals(ev.parse(addr("spans", "x")), None)
    assertEquals(ev.parse(addr("claim", "a", "b")), None)
    assertEquals(ev.parse(addr("nope", "a")), None)

  test("identifiers containing separators survive the wire form"):
    val r = CoreRef.Claim(ClaimId.unsafe("wog:1/claim%7"))
    val a = Addressable[CoreRef].address(r)
    assertEquals(a.render, "core/claim/wog:1%2Fclaim%257")
    assertEquals(Address.parse(a.render).toOption.flatMap(Addressable[CoreRef].parse), Some(r))

  test("addresses order by their canonical rendering"):
    val xs =
      List("core/claim/b", "core/claim/a", "core/spans/0-1").map(Address.parse(_).toOption.get)
    assertEquals(xs.sorted.map(_.render), List("core/claim/a", "core/claim/b", "core/spans/0-1"))
