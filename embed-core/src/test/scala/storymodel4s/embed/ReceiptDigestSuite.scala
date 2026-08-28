package storymodel4s.embed

import cats.Id
import munit.FunSuite

import storymodel4s.core.{Checksum, ProviderCall}

/** P0-2 laws: keyed identities on every receipt surface (cache, pseudo, capability, attempt,
  * provider-call), Plain only for public material, fail-closed without a key, canonical rendering,
  * cross-platform determinism.
  */
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

  test("cross-platform determinism: the golden key over the PRODUCTION material rendering") {
    val space = HashedNgramEmbedder[Id](8, 0L, k1).spaces
      .find(s => s.role == Role.Query && s.view == SemanticView.Surface)
      .get
    // The golden rendering IS what a query-role request without instruction embeds.
    assertEquals(
      Material.render(space, EmbedPayload.Raw(SensitiveDigest.Golden.text, Sensitivity.Sensitive)),
      SensitiveDigest.Golden.rendering
    )
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

  test("canonical rendering escapes every separator so no field can forge one") {
    val e = ReceiptRendering.esc
    assertEquals(e("a|b"), "a\\|b")
    assertEquals(e("a\\b"), "a\\\\b")
    assertEquals(e("a\nb"), "a\\nb")
    assertEquals(e("a\u0000b"), "a\\0b")
    assert(ReceiptRendering.material(Role.Query, None, "x|y\nz").endsWith("|text=x\\|y\\nz"))
    // Two different (policy, key, text) triples never render identically.
    assertNotEquals(
      ReceiptRendering.pseudonymized(PrivacyPolicyId.unsafe("p|x"), KeyId.unsafe("k"), "t"),
      ReceiptRendering.pseudonymized(PrivacyPolicyId.unsafe("p"), KeyId.unsafe("x|k"), "t")
    )
  }

  test("ReceiptDigest.of: Plain iff Public; Internal and Sensitive are Keyed") {
    val pub = ReceiptDigest.of(Sensitivity.Public, canary, k1).toOption.get
    val internal = ReceiptDigest.of(Sensitivity.Internal, canary, k1).toOption.get
    val sens = ReceiptDigest.of(Sensitivity.Sensitive, canary, k1).toOption.get
    assertEquals(pub.kind, DigestKind.Plain)
    assertEquals(internal.kind, DigestKind.Keyed)
    assertEquals(sens.kind, DigestKind.Keyed)
    assertEquals(pub.render, s"plain:${Checksum.ofText(canary).hex}")
    assertEquals(sens.keyIdOption, Some(KeyId.unsafe("k1")))
    Sensitivity.values.foreach { s =>
      val d = ReceiptDigest.of(s, canary, k1).toOption.get
      assertEquals(d.kind == DigestKind.Plain, ReceiptDigest.plainAdmissible.contains(s))
    }
  }

  test("Keyed carries exactly one key id (derived from the digest)") {
    val sens = ReceiptDigest.of(Sensitivity.Sensitive, canary, k1).toOption.get
    sens match
      case k: ReceiptDigest.Keyed =>
        assertEquals(k.keyId, k.digest.keyId)
        assert(k.render.startsWith("hmac:k1:"))
      case other => fail(s"expected Keyed, got $other")
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

  test("Public material stays reproducibly Plain across runs and key providers") {
    val a = ReceiptDigest.of(Sensitivity.Public, "the young man", k1).toOption.get
    val b = ReceiptDigest.of(Sensitivity.Public, "the young man", k2).toOption.get
    assertEquals(a, b)
    assert(a.render.startsWith("plain:"))
  }

  test("Sensitive material with no key fails closed with NoKey, never a Plain digest") {
    ReceiptDigest.of(Sensitivity.Sensitive, canary, SensitiveKeyProvider.none) match
      case Left(EmbedError.NoKey(k)) => assertEquals(k, "none"); leaksNothing(k)
      case other                     => fail(s"expected NoKey, got $other")
    assert(ReceiptDigest.of(Sensitivity.Internal, canary, SensitiveKeyProvider.none).isLeft)
  }

  test("ItemDigest.of refuses a Plain identity for non-public material") {
    val plain = ReceiptDigest.of(Sensitivity.Public, canary, k1).toOption.get
    assert(ItemDigest.of(RequestId.unsafe("x"), Sensitivity.Sensitive, plain).isLeft)
    assert(ItemDigest.of(RequestId.unsafe("x"), Sensitivity.Internal, plain).isLeft)
    assert(ItemDigest.of(RequestId.unsafe("x"), Sensitivity.Public, plain).isRight)
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

  private def receiptOf(r: BatchResult): EmbeddingReceipt = r.receipt.embeddingReceipts.head

  test(
    "provider-call surface: keyed batch identities differ by key; kind is typed on the attempt; nothing leaks"
  ) {
    val e1 = HashedNgramEmbedder[Id](64, 0L, k1)
    val e2 = HashedNgramEmbedder[Id](64, 0L, k2)
    val reqs =
      Vector(("a", "the young man", Sensitivity.Public), ("b", canary, Sensitivity.Sensitive))
    val r1 = e1.embed(batch(e1, reqs))
    val r2 = e2.embed(batch(e2, reqs))
    assert(r1.outcomes.forall(_.value.isRight))
    val rec1 = receiptOf(r1)
    val rec2 = receiptOf(r2)
    assertEquals(rec1.kind, DigestKind.Keyed)
    assertEquals(r1.receipt.kind, DigestKind.Keyed)
    assertEquals(rec1.call.params(EmbeddingReceipt.KindParam), "keyed")
    assertEquals(rec1.call.params(EmbeddingReceipt.KeyParam), "k1")
    assert(rec1.isKeyConsistent)
    assertEquals(rec1.items.map(_.digest.kind), Vector(DigestKind.Plain, DigestKind.Keyed))
    assertEquals(
      r1.receipt.itemSensitivity.map(_._2),
      Vector(Sensitivity.Public, Sensitivity.Sensitive)
    )
    // Same material, different key: input, output and attempt identities all differ.
    assertNotEquals(rec1.call.inputChecksum, rec2.call.inputChecksum)
    assertNotEquals(rec1.call.outputChecksum, rec2.call.outputChecksum)
    assertNotEquals(r1.receipt.digest.render, r2.receipt.digest.render)
    assertEquals(r1.outcomes.map(_.value), r2.outcomes.map(_.value)) // vectors identical
    // Deterministic under the same key.
    val r1b = e1.embed(batch(e1, reqs))
    assertEquals(receiptOf(r1b).call.inputChecksum, rec1.call.inputChecksum)
    assertEquals(r1b.receipt.digest, r1.receipt.digest)
    // No rendered identity leaks material or key bytes.
    (rec1.items.map(_.digest.render) :+ rec1.outputs.render :+ rec1.call.inputChecksum.hex :+
      rec1.call.outputChecksum.hex :+ r1.receipt.digest.render :+ r1.receipt.toString)
      .foreach(leaksNothing)
    rec1.call.params.values.foreach(leaksNothing)
  }

  test("provider-call surface: public-only batches stay Plain and reproducible across embedders") {
    val e1 = HashedNgramEmbedder[Id](64, 0L, k1)
    val e2 = HashedNgramEmbedder[Id](64, 0L, k2)
    val reqs = Vector(
      ("a", "the young man", Sensitivity.Public),
      ("b", "canoes came up", Sensitivity.Public)
    )
    val r1 = e1.embed(batch(e1, reqs))
    val r2 = e2.embed(batch(e2, reqs))
    val rec1 = receiptOf(r1)
    assertEquals(rec1.kind, DigestKind.Plain)
    assertEquals(r1.receipt.kind, DigestKind.Plain)
    assertEquals(rec1.call.params(EmbeddingReceipt.KindParam), "plain")
    assert(!rec1.call.params.contains(EmbeddingReceipt.KeyParam))
    assertEquals(rec1.call.inputChecksum, receiptOf(r2).call.inputChecksum)
    assertEquals(rec1.call.outputChecksum, receiptOf(r2).call.outputChecksum)
    assertEquals(r1.receipt.digest, r2.receipt.digest)
  }

  test(
    "a baseline without keys denies Sensitive items with KeyUnavailable and withholds the receipt"
  ) {
    val e = HashedNgramEmbedder[Id](64, 0L)
    val reqs =
      Vector(("a", "the young man", Sensitivity.Public), ("b", canary, Sensitivity.Sensitive))
    val r = e.embed(batch(e, reqs))
    assert(r.outcomes(0).value.isRight)
    r.outcomes(1).value match
      case Left(ExecutionFailure.PolicyDenied(PolicyDecision.KeyUnavailable(Some(id), k))) =>
        assertEquals(id.value, "b"); assertEquals(k.value, "none")
      case other => fail(s"expected PolicyDenied(KeyUnavailable), got $other")
    assert(r.receipt.policyDecisions.exists {
      case PolicyDecision.KeyUnavailable(Some(id), _) => id.value == "b"
      case _                                          => false
    })
    assertEquals(r.receipt.kind, DigestKind.Withheld)
    assert(r.receipt.digest.render.startsWith("withheld:none:"))
    r.receipt.policyDecisions.map(_.render).foreach(leaksNothing)
  }

  test("HIGH-2: a key that vanishes after the items are keyed fails the whole batch closed") {
    // The key is served exactly once (for the sensitive item), then withdrawn before outputs.
    val onceKeys = new SensitiveKeyProvider:
      private var served = 0
      def currentKeyId: KeyId = KeyId.unsafe("k1")
      def key(id: KeyId): Option[Array[Byte]] =
        if id == currentKeyId && served == 0 then { served += 1; Some(keyOneBytes) }
        else None
    val e = HashedNgramEmbedder[Id](64, 0L, onceKeys)
    val reqs =
      Vector(("a", "the young man", Sensitivity.Public), ("b", canary, Sensitivity.Sensitive))
    val r = e.embed(batch(e, reqs))
    r.outcomes(1).value match
      case Left(ExecutionFailure.PolicyDenied(PolicyDecision.KeyUnavailable(Some(_), _))) => ()
      case other => fail(s"expected fail-closed PolicyDenied(KeyUnavailable), got $other")
    assertEquals(r.receipt.kind, DigestKind.Withheld)
    assert(
      r.receipt.providerCalls.isEmpty,
      "no provider call may be receipted without keyed identity"
    )
    assert(r.receipt.policyDecisions.exists {
      case PolicyDecision.KeyUnavailable(Some(id), _) => id.value == "b"
      case _                                          => false
    })
    (r.receipt.digest.render +: r.receipt.policyDecisions.map(_.render)).foreach(leaksNothing)
  }

  test("attempt surface: the keyed digest changes when result decisions change") {
    val e = HashedNgramEmbedder[Id](64, 0L, k1)
    val b = batch(e, Vector(("b", canary, Sensitivity.Sensitive)))
    val r = e.embed(b)
    assertEquals(r.receipt.kind, DigestKind.Keyed)
    val amended = r.receipt.addResultDecisions(
      Vector(ResultDecision.Reordered(RequestId.unsafe("b"), 0, 1))
    )
    assertEquals(amended.kind, DigestKind.Keyed)
    assertNotEquals(amended.digest, r.receipt.digest)
    assertEquals(amended.resultDecisions.size, r.receipt.resultDecisions.size + 1)
    // Idempotent when nothing is added.
    assertEquals(r.receipt.addResultDecisions(Vector.empty), r.receipt)
  }

  test(
    "cache surface: keys are keyed for Sensitive, Plain for Public, and fail closed without a key"
  ) {
    val inner = HashedNgramEmbedder[Id](64, 0L, k1)
    val sp = space(inner)
    val payload = EmbedPayload.Raw(canary, Sensitivity.Sensitive)
    val keyed1 = CacheKey.of(sp, payload, k1).toOption.get
    val keyed2 = CacheKey.of(sp, payload, k2).toOption.get
    val plain = CacheKey.of(sp, EmbedPayload.Raw(canary, Sensitivity.Public), k1).toOption.get
    assertNotEquals(keyed1.render, keyed2.render)
    assertNotEquals(keyed1.render, plain.render)
    assertEquals(keyed1.digest.kind, DigestKind.Keyed)
    assertEquals(plain.digest.kind, DigestKind.Plain)
    leaksNothing(keyed1.render)
    assertEquals(
      CacheKey.of(sp, payload, SensitiveKeyProvider.none),
      Left(EmbedError.NoKey("none"))
    )
    // A caching embedder whose key store is empty never bypasses to a plain key for Sensitive text.
    val cache = EmbeddingCache.inMemory[Id]
    val e = new CachingEmbedder[Id](inner, cache, SensitiveKeyProvider.none)
    val r = e.embed(batch(e, Vector(("b", canary, Sensitivity.Sensitive))))
    r.outcomes.head.value match
      case Left(ExecutionFailure.PolicyDenied(PolicyDecision.KeyUnavailable(Some(_), _))) => ()
      case other => fail(s"expected PolicyDenied(KeyUnavailable), got $other")
    assertEquals(cache.size, 0)
    assert(r.receipt.cacheDecisions.forall {
      case CacheDecision.Denied(_, PolicyDecision.KeyUnavailable(_, _)) => true
      case _                                                            => false
    })
    assertEquals(r.receipt.kind, DigestKind.Withheld)
    r.receipt.policyDecisions.map(_.render).foreach(leaksNothing)
  }

  test(
    "cache surface: the caching embedder's attempt receipt is keyed and carries the typed kind"
  ) {
    val inner = HashedNgramEmbedder[Id](64, 0L, k1)
    val e = new CachingEmbedder[Id](inner, EmbeddingCache.inMemory[Id], k1)
    val b = batch(
      e,
      Vector(("a", "the young man", Sensitivity.Public), ("b", canary, Sensitivity.Sensitive))
    )
    val r = e.embed(b)
    assertEquals(r.receipt.kind, DigestKind.Keyed)
    assertEquals(r.receipt.embeddingReceipts.map(_.kind), Vector(DigestKind.Keyed))
    val hits = e.embed(b)
    assertEquals(hits.receipt.providerCalls.size, 0)
    assertEquals(hits.receipt.kind, DigestKind.Keyed)
    assert(hits.receipt.cacheDecisions.forall {
      case CacheDecision.Hit(_, k) => k.kind != DigestKind.Withheld
      case _                       => false
    })
    (r.receipt.digest.render +: hits.receipt.digest.render +: r.receipt.cacheDecisions.map(
      _.render
    ))
      .foreach(leaksNothing)
  }

  test("EmbeddingReceipt.of rejects keyed items with plain outputs and withheld identities") {
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
    val plain = ReceiptDigest.of(Sensitivity.Public, "out", k1).toOption.get
    val item = ItemDigest.of(RequestId.unsafe("x"), Sensitivity.Sensitive, keyed).toOption.get
    assert(EmbeddingReceipt.of(base, Vector(item), plain).isLeft)
    val ok = EmbeddingReceipt.of(base, Vector(item), keyed).toOption.get
    assertEquals(ok.kind, DigestKind.Keyed)
    assert(ok.isKeyConsistent)
    assertEquals(ok.call.inputChecksum, Checksum.ofText(ReceiptRendering.items(Vector(item))))
  }
