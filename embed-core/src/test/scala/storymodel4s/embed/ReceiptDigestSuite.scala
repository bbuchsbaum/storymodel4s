package storymodel4s.embed

import cats.Id
import munit.FunSuite

import storymodel4s.core.{Checksum, ProviderCall}

/** P0-2 phase 1 laws: keyed identities on every receipt surface this slice owns. */
class ReceiptDigestSuite extends FunSuite:

  private val canary = "my sister's wedding at the small french restaurant"
  private val keyOneBytes = "store-local-secret-one".getBytes("UTF-8")
  private val keyTwoBytes = "store-local-secret-two".getBytes("UTF-8")
  private val k1 = SensitiveKeyProvider.static(KeyId.unsafe("k1"), keyOneBytes)
  private val k2 = SensitiveKeyProvider.static(KeyId.unsafe("k2"), keyTwoBytes)
  private val keyHexes = Vector(keyOneBytes, keyTwoBytes).map(b => b.map("%02x".format(_)).mkString)

  private def leaksNothing(rendered: String): Unit =
    assert(!rendered.contains("wedding"), rendered)
    assert(!rendered.contains("restaurant"), rendered)
    assert(!rendered.contains("secret"), rendered)
    keyHexes.foreach(h => assert(!rendered.contains(h), rendered))

  test("cross-platform determinism: the golden (key, rendering) yields the golden digest") {
    val d = SensitiveDigest
      .compute(
        SensitiveDigest.Golden.keyId,
        SensitiveDigest.Golden.keyBytes,
        SensitiveDigest.Golden.rendering
      )
      .toOption
      .get
    assertEquals(d.hex, SensitiveDigest.Golden.expectedHex)
    assertEquals(d.render, s"hmac:golden-key-v1:${SensitiveDigest.Golden.expectedHex}")
  }

  test("ReceiptDigest.of: Plain iff Public; Internal and Sensitive are Keyed") {
    val pub = ReceiptDigest.of(Sensitivity.Public, canary, k1).toOption.get
    val internal = ReceiptDigest.of(Sensitivity.Internal, canary, k1).toOption.get
    val sens = ReceiptDigest.of(Sensitivity.Sensitive, canary, k1).toOption.get
    assertEquals(pub.kind, DigestKind.Plain)
    assertEquals(internal.kind, DigestKind.Keyed)
    assertEquals(sens.kind, DigestKind.Keyed)
    assertEquals(pub, ReceiptDigest.Plain(Checksum.ofText(canary)))
    assertEquals(sens.keyIdOption, Some(KeyId.unsafe("k1")))
    Sensitivity.values.foreach { s =>
      val d = ReceiptDigest.of(s, canary, k1).toOption.get
      assertEquals(d.kind == DigestKind.Plain, ReceiptDigest.plainAdmissible.contains(s))
    }
  }

  test("same material, different key ⇒ different Keyed digest; same key ⇒ identical") {
    val a = ReceiptDigest.of(Sensitivity.Sensitive, canary, k1).toOption.get
    val b = ReceiptDigest.of(Sensitivity.Sensitive, canary, k2).toOption.get
    val a2 = ReceiptDigest.of(Sensitivity.Sensitive, canary, k1).toOption.get
    assertNotEquals(a.render, b.render)
    assertEquals(a.render, a2.render)
    leaksNothing(a.render)
    leaksNothing(b.render)
  }

  test("Public material stays reproducibly Plain across runs") {
    val a = ReceiptDigest.of(Sensitivity.Public, "the young man", k1).toOption.get
    val b = ReceiptDigest.of(Sensitivity.Public, "the young man", k2).toOption.get
    assertEquals(a, b)
    assert(a.render.startsWith("plain:"))
  }

  test("Sensitive material with no key fails closed with a typed error, never a Plain digest") {
    val r = ReceiptDigest.of(Sensitivity.Sensitive, canary, SensitiveKeyProvider.none)
    assert(r.isLeft)
    r.left.foreach {
      case EmbedError.InvalidKey(reason) => leaksNothing(reason)
      case other                         => fail(s"expected InvalidKey, got $other")
    }
    assert(ReceiptDigest.of(Sensitivity.Internal, canary, SensitiveKeyProvider.none).isLeft)
  }

  private def space(e: Embedder[Id]): EmbeddingSpace =
    e.spaces.find(s => s.role == Role.Document && s.view == SemanticView.Surface).get

  private def batch(e: Embedder[Id], reqs: Vector[(String, String, Sensitivity)]): EmbedBatch =
    EmbedBatch
      .validated(
        reqs.map { case (id, text, s) =>
          EmbedRequest(RequestId.unsafe(id), EmbedPayload.Raw(text, s), space(e).id)
        },
        e.spaceIds
      )
      .toOption
      .get

  test(
    "provider-call surface: keyed batch identities differ by key; kind is typed; nothing leaks"
  ) {
    val e1 = HashedNgramEmbedder[Id](64, 0L, k1)
    val e2 = HashedNgramEmbedder[Id](64, 0L, k2)
    val reqs =
      Vector(("a", "the young man", Sensitivity.Public), ("b", canary, Sensitivity.Sensitive))
    val (r1, rec1) = e1.embedWithReceipt(batch(e1, reqs))
    val (r2, rec2) = e2.embedWithReceipt(batch(e2, reqs))
    assert(r1.outcomes.forall(_.value.isRight))
    assertEquals(rec1.kind, DigestKind.Keyed)
    assertEquals(rec1.call.params(EmbeddingReceipt.KindParam), "keyed")
    assertEquals(rec1.call.params(EmbeddingReceipt.KeyParam), "k1")
    assert(rec1.isKeyConsistent)
    assertEquals(rec1.items.map(_._2.kind), Vector(DigestKind.Plain, DigestKind.Keyed))
    // Same material, different key: input and output identities both differ.
    assertNotEquals(rec1.call.inputChecksum, rec2.call.inputChecksum)
    assertNotEquals(rec1.call.outputChecksum, rec2.call.outputChecksum)
    assertEquals(
      r1.outcomes.map(_.value),
      r2.outcomes.map(_.value)
    ) // vectors identical, identities not
    // Deterministic under the same key.
    val (_, rec1b) = e1.embedWithReceipt(batch(e1, reqs))
    assertEquals(rec1b.call.inputChecksum, rec1.call.inputChecksum)
    assertEquals(rec1b.call.outputChecksum, rec1.call.outputChecksum)
    // No rendered identity leaks material or key bytes.
    (rec1.items.map(_._2.render) :+ rec1.outputs.render :+ rec1.call.inputChecksum.hex :+
      rec1.call.outputChecksum.hex :+ r1.receipt.digest.hex).foreach(leaksNothing)
    rec1.call.params.values.foreach(leaksNothing)
  }

  test("provider-call surface: public-only batches stay Plain and reproducible across embedders") {
    val e1 = HashedNgramEmbedder[Id](64, 0L, k1)
    val e2 = HashedNgramEmbedder[Id](64, 0L, k2)
    val reqs = Vector(
      ("a", "the young man", Sensitivity.Public),
      ("b", "canoes came up", Sensitivity.Public)
    )
    val (_, rec1) = e1.embedWithReceipt(batch(e1, reqs))
    val (_, rec2) = e2.embedWithReceipt(batch(e2, reqs))
    assertEquals(rec1.kind, DigestKind.Plain)
    assertEquals(rec1.call.params(EmbeddingReceipt.KindParam), "plain")
    assert(!rec1.call.params.contains(EmbeddingReceipt.KeyParam))
    assertEquals(rec1.call.inputChecksum, rec2.call.inputChecksum)
    assertEquals(rec1.call.outputChecksum, rec2.call.outputChecksum)
  }

  test("provider-call surface: a baseline without keys fails closed on Sensitive items only") {
    val e = HashedNgramEmbedder[Id](64, 0L)
    val reqs =
      Vector(("a", "the young man", Sensitivity.Public), ("b", canary, Sensitivity.Sensitive))
    val (r, rec) = e.embedWithReceipt(batch(e, reqs))
    assert(r.outcomes(0).value.isRight)
    r.outcomes(1).value match
      case Left(ExecutionFailure.Invalid(EmbedError.InvalidKey(reason))) => leaksNothing(reason)
      case other => fail(s"expected fail-closed InvalidKey, got $other")
    assertEquals(rec.kind, DigestKind.Plain)
    assertEquals(rec.items.map(_._1.value), Vector("a"))
  }

  test(
    "cache surface: keys are keyed for Sensitive, Plain for Public, and fail closed without a key"
  ) {
    val inner = HashedNgramEmbedder[Id](64, 0L, k1)
    val sp = space(inner)
    val material = Material.render(sp, EmbedPayload.Raw(canary, Sensitivity.Sensitive))
    val keyed1 = CacheKey(sp.id, ReceiptDigest.of(Sensitivity.Sensitive, material, k1).toOption.get)
    val keyed2 = CacheKey(sp.id, ReceiptDigest.of(Sensitivity.Sensitive, material, k2).toOption.get)
    val plain = CacheKey(sp.id, ReceiptDigest.of(Sensitivity.Public, material, k1).toOption.get)
    assertNotEquals(keyed1.render, keyed2.render)
    assertNotEquals(keyed1.render, plain.render)
    assertEquals(keyed1.digest.kind, DigestKind.Keyed)
    assertEquals(plain.digest.kind, DigestKind.Plain)
    leaksNothing(keyed1.render)
    // A caching embedder whose key store is empty never bypasses to a plain key for Sensitive text.
    val cache = EmbeddingCache.inMemory[Id]
    val e = new CachingEmbedder[Id](inner, cache, SensitiveKeyProvider.none)
    val r = e.embed(batch(e, Vector(("b", canary, Sensitivity.Sensitive))))
    r.outcomes.head.value match
      case Left(ExecutionFailure.Invalid(EmbedError.InvalidKey(_))) => ()
      case other => fail(s"expected fail-closed InvalidKey, got $other")
    assertEquals(cache.size, 0)
    assert(r.receipt.cacheDecisions.forall {
      case CacheDecision.Bypassed(_, reason) => !reason.contains("wedding")
      case _                                 => false
    })
  }

  test("EmbeddingReceipt.of rejects keyed items with plain outputs") {
    val base = ProviderCall(
      "p",
      "m",
      "v",
      None,
      Checksum.ofText(""),
      Checksum.ofText(""),
      Map.empty,
      None,
      false
    )
    val keyed = ReceiptDigest.of(Sensitivity.Sensitive, canary, k1).toOption.get
    val plain = ReceiptDigest.Plain(Checksum.ofText("out"))
    assert(EmbeddingReceipt.of(base, Vector(RequestId.unsafe("x") -> keyed), plain).isLeft)
    val ok = EmbeddingReceipt.of(base, Vector(RequestId.unsafe("x") -> keyed), keyed).toOption.get
    assertEquals(ok.kind, DigestKind.Keyed)
    assert(ok.isKeyConsistent)
  }
