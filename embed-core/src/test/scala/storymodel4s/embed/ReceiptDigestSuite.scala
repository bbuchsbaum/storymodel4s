package storymodel4s.embed

import cats.Id
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import storymodel4s.core.{Checksum, ProviderCall}

/** P0-2 laws: keyed identities on every receipt surface (cache, pseudo, capability, attempt,
  * provider-call), Plain only for public material, fail-closed without a key, canonical rendering,
  * cross-platform determinism.
  */
class ReceiptDigestSuite extends ScalaCheckSuite:

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

  private def publicItem(id: String, material: String): ItemDigest =
    ItemDigest
      .of(
        RequestId.unsafe(id),
        Sensitivity.Public,
        ReceiptDigest.plainPublic(material)
      )
      .toOption
      .get

  private def baseCall(token: String): ProviderCall =
    ProviderCall(
      provider = s"provider-$token",
      model = s"model-$token",
      version = s"version-$token",
      promptTemplateVersion = Some(s"prompt-$token"),
      inputChecksum = Checksum.ofText("overwritten-input"),
      outputChecksum = Checksum.ofText("overwritten-output"),
      params = Map("z" -> s"last-$token", "a" -> s"first-$token"),
      seed = Some(17L),
      cached = false
    )

  private def embeddingReceipt(
      call: ProviderCall,
      item: ItemDigest,
      outputMaterial: String
  ): EmbeddingReceipt =
    EmbeddingReceipt
      .of(call, Vector(item), ReceiptDigest.plainPublic(outputMaterial))
      .toOption
      .get

  private def attempt(
      receipt: EmbeddingReceipt,
      cache: Vector[CacheDecision],
      policy: Vector[PolicyDecision],
      result: Vector[ResultDecision],
      sensitivity: Option[Vector[(RequestId, Sensitivity)]],
      keys: SensitiveKeyProvider = SensitiveKeyProvider.none
  ): AttemptReceipt =
    val itemSensitivity = sensitivity.getOrElse(receipt.items.map(i => i.id -> i.sensitivity))
    AttemptReceipt
      .of(
        Vector(receipt.call),
        Vector(receipt),
        cache,
        policy,
        result,
        itemSensitivity,
        keys
      )
      .fold(error => fail(error.message), identity)

  property("attempt/v2 changes for every generated equality-field mutation") {
    forAll(Gen.alphaNumStr) { generated =>
      val token = s"mut|$generated"
      val item = publicItem("provider-item", "input-base")
      val call = baseCall("base")
      val receipt = embeddingReceipt(call, item, "output-base")
      val cache = Vector(CacheDecision.Bypassed(RequestId.unsafe("cache-id"), "cache-base"))
      val policy = Vector(PolicyDecision.Denied(None, "policy-base"))
      val result = Vector(ResultDecision.Reordered(RequestId.unsafe("result-id"), 0, 1))
      val sensitivities = Some(Vector(item.id -> Sensitivity.Public))
      val original = attempt(receipt, cache, policy, result, sensitivities)
      val same = attempt(receipt, cache, policy, result, sensitivities)
      val callMutations = Vector(
        call.copy(provider = token),
        call.copy(model = token),
        call.copy(version = token),
        call.copy(promptTemplateVersion = Some(token)),
        call.copy(promptTemplateVersion = None),
        call.copy(params = call.params.updated(token, "value")),
        call.copy(seed = Some(18L)),
        call.copy(seed = None),
        call.copy(cached = true)
      ).map(embeddingReceipt(_, item, "output-base"))
      val receiptMutations = callMutations ++ Vector(
        embeddingReceipt(call, publicItem("provider-item", token), "output-base"),
        embeddingReceipt(call, item, token)
      )
      val mutations =
        receiptMutations.map(attempt(_, cache, policy, result, sensitivities)) ++ Vector(
          attempt(
            receipt,
            Vector(CacheDecision.Bypassed(RequestId.unsafe("cache-id"), token)),
            policy,
            result,
            sensitivities
          ),
          attempt(
            receipt,
            Vector(CacheDecision.Bypassed(RequestId.unsafe(token), "cache-base")),
            policy,
            result,
            sensitivities
          ),
          attempt(
            receipt,
            cache,
            Vector(PolicyDecision.Denied(None, token)),
            result,
            sensitivities
          ),
          attempt(
            receipt,
            cache,
            Vector(PolicyDecision.Denied(Some(PrivacyPolicyId.unsafe(token)), "policy-base")),
            result,
            sensitivities
          ),
          attempt(
            receipt,
            cache,
            policy,
            Vector(ResultDecision.Reordered(RequestId.unsafe("result-id"), 0, 2)),
            sensitivities
          ),
          attempt(
            receipt,
            cache,
            policy,
            Vector(ResultDecision.Reordered(RequestId.unsafe(token), 0, 1)),
            sensitivities
          ),
          attempt(
            receipt,
            cache,
            policy,
            Vector(ResultDecision.Reordered(RequestId.unsafe("result-id"), 2, 1)),
            sensitivities
          )
        )
      val secondItem = publicItem("provider-item-2", "input-2")
      val orderedSensitivity = Some(
        Vector(item.id -> Sensitivity.Public, secondItem.id -> Sensitivity.Public)
      )
      def ordered(items: Vector[ItemDigest]): AttemptReceipt =
        val orderedReceipt = EmbeddingReceipt
          .of(call, items, ReceiptDigest.plainPublic("output-base"))
          .toOption
          .get
        attempt(orderedReceipt, cache, policy, result, orderedSensitivity)

      assertEquals(original.digest, same.digest)
      assert(mutations.forall(_.digest != original.digest))
      assertNotEquals(
        ordered(Vector(item, secondItem)).digest,
        ordered(Vector(secondItem, item)).digest
      )
    }
  }

  test("attempt/v2 escaping defeats parameter and newline separator forgeries") {
    val item = publicItem("separator-item", "separator-input")
    val sensitivity = Some(Vector(item.id -> Sensitivity.Public))
    val paramA = baseCall("separator").copy(params = Map("a|b" -> "c"))
    val paramB = baseCall("separator").copy(params = Map("a" -> "b|c"))
    val naiveParamA = paramA.params.toVector
      .map { case (key, value) =>
        s"call-param|0|$key|$value"
      }
      .mkString("\n")
    val naiveParamB = paramB.params.toVector
      .map { case (key, value) =>
        s"call-param|0|$key|$value"
      }
      .mkString("\n")
    assertEquals(naiveParamA, naiveParamB, "the stripped renderer must collide")
    assertNotEquals(
      attempt(
        embeddingReceipt(paramA, item, "separator-output"),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        sensitivity
      ).digest,
      attempt(
        embeddingReceipt(paramB, item, "separator-output"),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        sensitivity
      ).digest
    )

    val cacheA = Vector(
      CacheDecision.Bypassed(
        RequestId.unsafe("cache-id"),
        "ok\ncache|1|bypassed|forged-id|forged-reason"
      )
    )
    val cacheB = Vector(
      CacheDecision.Bypassed(RequestId.unsafe("cache-id"), "ok"),
      CacheDecision.Bypassed(RequestId.unsafe("forged-id"), "forged-reason")
    )
    def naiveCache(decisions: Vector[CacheDecision]): String =
      decisions.zipWithIndex
        .map {
          case (CacheDecision.Bypassed(id, reason), index) =>
            s"cache|$index|bypassed|${id.value}|$reason"
          case _ => fail("separator fixture must contain only bypass decisions")
        }
        .mkString("\n")
    assertEquals(naiveCache(cacheA), naiveCache(cacheB), "the stripped renderer must collide")
    val receipt = embeddingReceipt(baseCall("newline"), item, "separator-output")
    assertNotEquals(
      attempt(receipt, cacheA, Vector.empty, Vector.empty, sensitivity).digest,
      attempt(receipt, cacheB, Vector.empty, Vector.empty, sensitivity).digest
    )
  }

  test("attempt/v2 commits full capability fields even when human renderings collide") {
    val sharedPrefix = "a" * 12
    val providerA = ProviderFingerprint(Checksum.unsafe(sharedPrefix + ("0" * 52)))
    val providerB = ProviderFingerprint(Checksum.unsafe(sharedPrefix + ("1" * 52)))
    val payload = ReceiptDigest.keyedUnder(KeyId.unsafe("k1"), "payload", k1).toOption.get
    val detectorIdentity = new DetectorPolicyIdentity(
      PseudonymizationDetectorId.unsafe("test-detector/v1"),
      ReceiptDigest.keyedUnder(KeyId.unsafe("k1"), "detector-config", k1).toOption.get
    )
    val policyId = PrivacyPolicyId.unsafe("policy")
    def allowed(provider: ProviderFingerprint): PolicyDecision =
      PolicyDecision.Allowed(
        policyId,
        RemoteCapability(
          provider,
          "model",
          "purpose",
          policyId,
          100L,
          5L,
          detectorIdentity,
          payload
        )
      )
    val decisionA = allowed(providerA)
    val decisionB = allowed(providerB)
    assertEquals(decisionA.render, decisionB.render, "the display rendering is deliberately lossy")
    val id = RequestId.unsafe("capability")
    def receipted(decision: PolicyDecision): AttemptReceipt =
      AttemptReceipt
        .of(
          Vector.empty,
          Vector.empty,
          Vector(CacheDecision.Hit(id, payload)),
          Vector(decision),
          Vector.empty,
          Vector(id -> Sensitivity.Sensitive),
          k1
        )
        .fold(error => fail(error.message), identity)
    assertNotEquals(
      receipted(decisionA).digest,
      receipted(decisionB).digest
    )
  }

  property("attempt/v2 commits every RemoteCapability equality field") {
    forAll(Gen.alphaNumStr) { generated =>
      val token = s"cap|$generated"
      val provider = ProviderFingerprint.of("model", "tokenizer", "impl", "runtime")
      val otherProvider = ProviderFingerprint.of(token, "tokenizer", "impl", "runtime")
      val policyId = PrivacyPolicyId.unsafe("policy")
      val otherPolicyId = PrivacyPolicyId.unsafe(token)
      val payload = ReceiptDigest.keyedUnder(KeyId.unsafe("k1"), "payload", k1).toOption.get
      val otherPayload = ReceiptDigest.keyedUnder(KeyId.unsafe("k1"), token, k1).toOption.get
      val detectorIdentity = new DetectorPolicyIdentity(
        PseudonymizationDetectorId.unsafe("test-detector/v1"),
        ReceiptDigest.keyedUnder(KeyId.unsafe("k1"), "detector-config", k1).toOption.get
      )
      val otherDetectorIdentity = new DetectorPolicyIdentity(
        PseudonymizationDetectorId.unsafe("test-detector/v1"),
        ReceiptDigest.keyedUnder(KeyId.unsafe("k1"), token, k1).toOption.get
      )
      val base =
        RemoteCapability(
          provider,
          "model",
          "purpose",
          policyId,
          100L,
          5L,
          detectorIdentity,
          payload
        )
      val mutations = Vector(
        base.copy(provider = otherProvider),
        base.copy(model = token),
        base.copy(purpose = token),
        base.copy(policyId = otherPolicyId),
        base.copy(expiresAtEpochMillis = 101L),
        base.copy(budgetTokens = 6L),
        base.copy(detectorIdentity = otherDetectorIdentity),
        base.copy(payloadDigest = otherPayload)
      )
      def noCallAttempt(capability: RemoteCapability): AttemptReceipt =
        val id = RequestId.unsafe("capability")
        AttemptReceipt
          .of(
            Vector.empty,
            Vector.empty,
            Vector(CacheDecision.Hit(id, payload)),
            Vector(PolicyDecision.Allowed(policyId, capability)),
            Vector.empty,
            Vector(id -> Sensitivity.Sensitive),
            k1
          )
          .fold(error => fail(error.message), identity)
      val original = noCallAttempt(base)
      assert(mutations.forall(capability => noCallAttempt(capability).digest != original.digest))
    }
  }

  test("attempt construction refuses mismatched or call-bearing withheld provenance") {
    val item = publicItem("provider-item", "input")
    val first = embeddingReceipt(baseCall("first"), item, "output")
    val second = embeddingReceipt(baseCall("second"), item, "output")
    val mismatch = AttemptReceipt.of(
      Vector(first.call),
      Vector(second),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector(second.items.head.id -> Sensitivity.Public),
      SensitiveKeyProvider.none
    )
    assert(mismatch.isLeft)

    val mismatchedSensitivity = AttemptReceipt.of(
      Vector(first.call),
      Vector(first),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector(first.items.head.id -> Sensitivity.Internal),
      k1
    )
    assert(mismatchedSensitivity.isLeft)

    val sensitiveId = RequestId.unsafe("sensitive")
    val missing = KeyId.unsafe("missing")
    val sensitiveDigest = ReceiptDigest
      .of(Sensitivity.Sensitive, "sensitive-input", k1)
      .toOption
      .get
    val sensitiveItem = ItemDigest
      .of(sensitiveId, Sensitivity.Sensitive, sensitiveDigest)
      .toOption
      .get
    val sensitiveOutput = ReceiptDigest.keyed("sensitive-output", k1).toOption.get
    val sensitiveReceipt = EmbeddingReceipt
      .of(baseCall("sensitive"), Vector(sensitiveItem), sensitiveOutput)
      .toOption
      .get
    val callBearingWithheld = AttemptReceipt.of(
      Vector(sensitiveReceipt.call),
      Vector(sensitiveReceipt),
      Vector.empty,
      Vector(PolicyDecision.KeyUnavailable(Some(sensitiveId), missing)),
      Vector.empty,
      Vector(sensitiveId -> Sensitivity.Sensitive),
      SensitiveKeyProvider.none
    )
    assert(callBearingWithheld.isLeft)
  }

  test("non-public attempt construction requires keyed item or explicit coverage evidence") {
    val sensitiveId = RequestId.unsafe("sensitive-uncovered")
    val keyedOutput = ReceiptDigest.keyed("keyed-output", k1).toOption.get
    val emptyReceipt = EmbeddingReceipt
      .of(baseCall("empty-items"), Vector.empty, keyedOutput)
      .toOption
      .get
    val emptyItems = AttemptReceipt.of(
      Vector(emptyReceipt.call),
      Vector(emptyReceipt),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector(sensitiveId -> Sensitivity.Sensitive),
      k1
    )
    assert(emptyItems.swap.exists(_.message.contains("empty-item receipt")))
    val keyedPublicBatch = AttemptReceipt.of(
      Vector(emptyReceipt.call),
      Vector(emptyReceipt),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector(RequestId.unsafe("public-only") -> Sensitivity.Public),
      k1
    )
    assert(keyedPublicBatch.swap.exists(_.message.contains("public batch")))

    val public = publicItem("public-provider-item", "public-input")
    val publicReceipt = embeddingReceipt(baseCall("public-only"), public, "public-output")
    val uncovered = AttemptReceipt.of(
      Vector(publicReceipt.call),
      Vector(publicReceipt),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector(public.id -> Sensitivity.Public, sensitiveId -> Sensitivity.Sensitive),
      k1
    )
    assert(uncovered.swap.exists(_.message.contains(sensitiveId.value)))

    val plainHitDoesNotCover = AttemptReceipt.of(
      Vector(publicReceipt.call),
      Vector(publicReceipt),
      Vector(CacheDecision.Hit(sensitiveId, ReceiptDigest.plainPublic("unsafe-hit"))),
      Vector.empty,
      Vector.empty,
      Vector(public.id -> Sensitivity.Public, sensitiveId -> Sensitivity.Sensitive),
      k1
    )
    assert(plainHitDoesNotCover.swap.exists(_.message.contains(sensitiveId.value)))

    val covered = AttemptReceipt.of(
      Vector(publicReceipt.call),
      Vector(publicReceipt),
      Vector(CacheDecision.Hit(sensitiveId, keyedOutput)),
      Vector.empty,
      Vector.empty,
      Vector(public.id -> Sensitivity.Public, sensitiveId -> Sensitivity.Sensitive),
      k1
    )
    assert(covered.isRight)
  }

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
    assert(r.outcomes.forall {
      case EmbedOutcome(
            _,
            _,
            Left(ExecutionFailure.PolicyDenied(PolicyDecision.KeyUnavailable(Some(_), k)))
          ) =>
        k.value == "none"
      case _ => false
    })
    assert(r.receipt.policyDecisions.exists {
      case PolicyDecision.KeyUnavailable(Some(id), _) => id.value == "b"
      case _                                          => false
    })
    assertEquals(r.receipt.kind, DigestKind.Withheld)
    assert(r.receipt.providerCalls.isEmpty)
    assert(r.receipt.embeddingReceipts.isEmpty)
    assert(r.receipt.digest.render.startsWith("withheld:none:"))
    r.receipt.policyDecisions.map(_.render).foreach(leaksNothing)
  }

  test("an owned baseline key snapshot survives provider withdrawal for the whole batch") {
    // The live key is served exactly once; the baseline owns it before vector computation.
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
    assert(r.outcomes.forall(_.value.isRight))
    assertEquals(r.receipt.kind, DigestKind.Keyed)
    assertEquals(r.receipt.providerCalls.size, 1)
    assertEquals(r.receipt.embeddingReceipts.size, 1)
    assertEquals(r.receipt.providerCalls, r.receipt.embeddingReceipts.map(_.call))
    (r.receipt.digest.render +: r.receipt.policyDecisions.map(_.render)).foreach(leaksNothing)
  }

  test("attempt surface: the keyed digest changes when result decisions change") {
    val e = HashedNgramEmbedder[Id](64, 0L, k1)
    val b = batch(e, Vector(("b", canary, Sensitivity.Sensitive)))
    val r = e.embed(b)
    val snapshot = SensitiveKeySnapshot.capture(k1.currentKeyId, k1).toOption.get
    assertEquals(r.receipt.kind, DigestKind.Keyed)
    val amended = r.receipt
      .addResultDecisions(
        Vector(ResultDecision.Reordered(RequestId.unsafe("b"), 0, 1)),
        Some(snapshot)
      )
      .toOption
      .get
    assertEquals(amended.kind, DigestKind.Keyed)
    assertNotEquals(amended.digest, r.receipt.digest)
    assertEquals(amended.resultDecisions.size, r.receipt.resultDecisions.size + 1)
    // Idempotent when nothing is added.
    assertEquals(r.receipt.addResultDecisions(Vector.empty, Some(snapshot)), Right(r.receipt))

    val wrongSnapshot = SensitiveKeySnapshot.capture(k2.currentKeyId, k2).toOption.get
    val mismatch = r.receipt
      .addResultDecisions(
        Vector(ResultDecision.Reordered(RequestId.unsafe("b"), 0, 1)),
        Some(wrongSnapshot)
      )
      .swap
      .toOption
      .get
    assertEquals(
      mismatch,
      EmbedError.AuthorityMismatch(KeyId.unsafe("k1"), KeyId.unsafe("k2"))
    )
    val rejected = AttemptReceipt.rejectProviderResult(
      r.receipt.providerCalls,
      r.receipt.cacheDecisions,
      r.receipt.policyDecisions,
      r.receipt.itemSensitivity,
      mismatch,
      Some(snapshot)
    )
    assertEquals(rejected.kind, DigestKind.Keyed)
    assertEquals(rejected.providerCalls, r.receipt.providerCalls)
    assertEquals(rejected.embeddingReceipts, Vector.empty)
    assert(rejected.resultDecisions.exists {
      case ResultDecision.ReceiptRejected(EmbedError.AuthorityMismatch(_, _)) => true
      case _                                                                  => false
    })
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

  test(
    "REQUIRED (1): a caching embedder with no key at batch start fails closed before delegating"
  ) {
    var innerCalls = 0
    val inner = new Embedder[Id]:
      private val base = HashedNgramEmbedder[Id](64, 0L, k1)
      def info: EmbedderInfo = base.info
      def spaces: Vector[EmbeddingSpace] = base.spaces
      def embed(b: EmbedBatch): Id[BatchResult] = { innerCalls += 1; base.embed(b) }
    val cache = EmbeddingCache.inMemory[Id]
    val e = new CachingEmbedder[Id](inner, cache, SensitiveKeyProvider.none)
    val r = e.embed(
      batch(
        e,
        Vector(("a", "the young man", Sensitivity.Public), ("b", canary, Sensitivity.Sensitive))
      )
    )
    assertEquals(innerCalls, 0, "no delegation without a key")
    assertEquals(r.receipt.providerCalls.size, 0)
    assertEquals(r.receipt.embeddingReceipts.size, 0)
    assertEquals(cache.size, 0)
    assertEquals(r.receipt.kind, DigestKind.Withheld)
    assert(r.outcomes.forall(_.value.isLeft), "no vectors may leave with a withheld receipt")
    assert(r.outcomes.forall {
      case EmbedOutcome(
            _,
            _,
            Left(ExecutionFailure.PolicyDenied(PolicyDecision.KeyUnavailable(Some(_), _)))
          ) =>
        true
      case _ => false
    })
    (r.receipt.digest.render +: r.receipt.policyDecisions.map(_.render) ++: r.receipt.cacheDecisions
      .map(_.render)).foreach(leaksNothing)
  }

  test(
    "REQUIRED (1): the key is resolved once per batch, so a key withdrawn mid-batch cannot desynchronize identities"
  ) {
    val onceKeys = new SensitiveKeyProvider:
      private var served = 0
      def currentKeyId: KeyId = KeyId.unsafe("k1")
      def key(id: KeyId): Option[Array[Byte]] =
        if id == currentKeyId && served == 0 then { served += 1; Some(keyOneBytes) }
        else None
    val inner = HashedNgramEmbedder[Id](64, 0L, k1)
    val cache = EmbeddingCache.inMemory[Id]
    val e = new CachingEmbedder[Id](inner, cache, onceKeys)
    val r = e.embed(
      batch(
        e,
        Vector(("a", "the young man", Sensitivity.Public), ("b", canary, Sensitivity.Sensitive))
      )
    )
    // One snapshot serves the whole batch: keyed identities, one call, consistent receipt.
    assertEquals(r.receipt.kind, DigestKind.Keyed)
    assertEquals(r.receipt.providerCalls.size, 1)
    assert(r.outcomes.forall(_.value.isRight))
    assertEquals(cache.size, 2)
    assert(r.receipt.cacheDecisions.forall {
      case CacheDecision.Miss(_, k) => k.kind != DigestKind.Withheld
      case _                        => false
    })
  }

  test("a mutable provider array cannot mutate the owned cache-batch key snapshot") {
    val aliased = keyOneBytes.clone()
    val aliasProvider = new SensitiveKeyProvider:
      def currentKeyId: KeyId = KeyId.unsafe("k1")
      def key(id: KeyId): Option[Array[Byte]] =
        Option.when(id == currentKeyId)(aliased)
    val base = HashedNgramEmbedder[Id](64, 0L, k1)
    val mutating = new Embedder[Id]:
      def info: EmbedderInfo = base.info
      def spaces: Vector[EmbeddingSpace] = base.spaces
      def embed(batch: EmbedBatch): BatchResult =
        java.util.Arrays.fill(aliased, 0.toByte)
        base.embed(batch)
    val requests =
      Vector(("a", "the young man", Sensitivity.Public), ("b", canary, Sensitivity.Sensitive))
    val mutated =
      new CachingEmbedder[Id](mutating, EmbeddingCache.inMemory[Id], aliasProvider)
    val control = new CachingEmbedder[Id](base, EmbeddingCache.inMemory[Id], k1)
    val mutatedResult = mutated.embed(batch(mutated, requests))
    val controlResult = control.embed(batch(control, requests))

    assert(aliased.forall(_ == 0.toByte), "the adversary must actually mutate its aliased array")
    assertEquals(mutatedResult.receipt.cacheDecisions, controlResult.receipt.cacheDecisions)
    assertEquals(mutatedResult.receipt.digest, controlResult.receipt.digest)
    assertEquals(mutatedResult.receipt.kind, DigestKind.Keyed)
  }
