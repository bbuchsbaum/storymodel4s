package storymodel4s.core

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

class HashSuite extends ScalaCheckSuite:
  test("SHA-256 known vectors"):
    assertEquals(
      Sha256.hexDigest(""),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )
    assertEquals(
      Sha256.hexDigest("abc"),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
    assertEquals(
      Sha256.hexDigest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    )
    // one million 'a' (multi-block, exercises length padding)
    assertEquals(
      Sha256.hexDigest("a" * 1000000),
      "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
    )

  test("SHA-256 of a message whose length is exactly 55, 56, 64 bytes (padding edges)"):
    // Cross-checked against standard implementations.
    assertEquals(
      Sha256.hexDigest("a" * 55),
      "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318"
    )
    assertEquals(
      Sha256.hexDigest("a" * 56),
      "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a"
    )
    assertEquals(
      Sha256.hexDigest("a" * 64),
      "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb"
    )

  test("Checksum accepts only 64 lowercase hex digits"):
    assert(Checksum.from("ab").isLeft)
    assert(Checksum.from("A" * 64).isLeft)
    assert(Checksum.from("a" * 64).isRight)
    assertEquals(Checksum.ofText("x").short(), Sha256.hexDigest("x").take(12))

  test("ContentAddress format and separator sensitivity"):
    val id = ContentAddress.of("ent", "a", "b")
    assert(id.matches("^ent:[0-9a-f]{12}$"), id)
    assertNotEquals(ContentAddress.of("ent", "a", "b"), ContentAddress.of("ent", "ab"))
    assertNotEquals(ContentAddress.of("ent", "a"), ContentAddress.of("sit", "a"))

  property("ContentAddress is deterministic"):
    forAll { (parts: List[String]) =>
      ContentAddress.of("k", parts*) == ContentAddress.of("k", parts*)
    }

  property("canonical ids are invariant under member order"):
    forAll { (members: List[String]) =>
      val s = StoryId.unsafe("story")
      DeterministicId.forCanonical(s, "entity", members) ==
        DeterministicId.forCanonical(s, "entity", members.reverse)
    }

  test("mention ids depend on the span"):
    val s = StoryId.unsafe("story")
    assertNotEquals(
      DeterministicId.forMention(s, "entity", TextSpan.unsafe(0, 3)),
      DeterministicId.forMention(s, "entity", TextSpan.unsafe(0, 4))
    )
