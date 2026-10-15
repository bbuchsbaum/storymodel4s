package receiptboundaryprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.core.Checksum
import storymodel4s.embed.*

/** External construction courts preserve receipt provenance through the named factories. */
class ReceiptDigestBoundarySuite extends FunSuite:

  test("Plain refuses external new with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : storymodel4s.core.Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """new storymodel4s.embed.ReceiptDigest.Plain(??? : storymodel4s.core.Checksum)"""
      ).nonEmpty
    )
  }

  test("Plain refuses external apply with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : storymodel4s.core.Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.embed.ReceiptDigest.Plain(??? : storymodel4s.core.Checksum)"""
      ).nonEmpty
    )
  }

  test("Plain refuses external copy with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(typeCheckErrors("""(??? : storymodel4s.embed.ReceiptDigest.Plain).copy()""").nonEmpty)
  }

  test("Plain refuses external fromProduct with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.embed.ReceiptDigest.Plain.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }

  test("Plain refuses external Mirror with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.embed.ReceiptDigest.Plain]]"""
      ).nonEmpty
    )
  }

  test("Keyed refuses external new with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(digest: storymodel4s.embed.SensitiveDigest); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : storymodel4s.embed.SensitiveDigest)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """new storymodel4s.embed.ReceiptDigest.Keyed(??? : storymodel4s.embed.SensitiveDigest)"""
      ).nonEmpty
    )
  }

  test("Keyed refuses external apply with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(digest: storymodel4s.embed.SensitiveDigest); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : storymodel4s.embed.SensitiveDigest)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.embed.ReceiptDigest.Keyed(??? : storymodel4s.embed.SensitiveDigest)"""
      ).nonEmpty
    )
  }

  test("Keyed refuses external copy with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(digest: storymodel4s.embed.SensitiveDigest); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(typeCheckErrors("""(??? : storymodel4s.embed.ReceiptDigest.Keyed).copy()""").nonEmpty)
  }

  test("Keyed refuses external fromProduct with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(digest: storymodel4s.embed.SensitiveDigest); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.embed.ReceiptDigest.Keyed.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }

  test("Keyed refuses external Mirror with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(digest: storymodel4s.embed.SensitiveDigest); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.embed.ReceiptDigest.Keyed]]"""
      ).nonEmpty
    )
  }

  test("Withheld refuses external new with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(missingKey: storymodel4s.embed.KeyId, checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : storymodel4s.embed.KeyId, ??? : storymodel4s.core.Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """new storymodel4s.embed.ReceiptDigest.Withheld(??? : storymodel4s.embed.KeyId, ??? : storymodel4s.core.Checksum)"""
      ).nonEmpty
    )
  }

  test("Withheld refuses external apply with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(missingKey: storymodel4s.embed.KeyId, checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : storymodel4s.embed.KeyId, ??? : storymodel4s.core.Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.embed.ReceiptDigest.Withheld(??? : storymodel4s.embed.KeyId, ??? : storymodel4s.core.Checksum)"""
      ).nonEmpty
    )
  }

  test("Withheld refuses external copy with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(missingKey: storymodel4s.embed.KeyId, checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(typeCheckErrors("""(??? : storymodel4s.embed.ReceiptDigest.Withheld).copy()""").nonEmpty)
  }

  test("Withheld refuses external fromProduct with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(missingKey: storymodel4s.embed.KeyId, checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.embed.ReceiptDigest.Withheld.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }

  test("Withheld refuses external Mirror with a same-shape positive control") {
    assertEquals(
      typeCheckErrors(
        """case class Control(missingKey: storymodel4s.embed.KeyId, checksum: storymodel4s.core.Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.embed.ReceiptDigest.Withheld]]"""
      ).nonEmpty
    )
  }

  test("lawful receipt digests retain reads, equality, hashes, rendering and extractors") {
    val public =
      ReceiptDigest.of(Sensitivity.Public, "public", SensitiveKeyProvider.none).toOption.get
    val same =
      ReceiptDigest.of(Sensitivity.Public, "public", SensitiveKeyProvider.none).toOption.get
    assertEquals(public, same)
    assertEquals(public.hashCode, same.hashCode)
    public match
      case ReceiptDigest.Plain(checksum) => assertEquals(checksum, Checksum.ofText("public"))
      case other                         => fail(s"expected plain identity, got $other")
    val keys = SensitiveKeyProvider.static(KeyId.unsafe("test"), "synthetic-key".getBytes("UTF-8"))
    val keyed = ReceiptDigest.keyed("material", keys).toOption.get
    val keyedAgain = ReceiptDigest.keyed("material", keys).toOption.get
    assertEquals(keyed, keyedAgain)
    assertEquals(keyed.hashCode, keyedAgain.hashCode)
    keyed match
      case ReceiptDigest.Keyed(digest) => assertEquals(digest.render, keyed.render)
      case other                       => fail(s"expected keyed identity, got $other")
  }
