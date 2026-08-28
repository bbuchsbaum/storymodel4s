package storymodel4s.embed

import munit.FunSuite

import storymodel4s.core.Checksum

class DigestPrivacySuite extends FunSuite:

  test("HMAC-SHA256 matches RFC 4231 test cases 1 and 2") {
    val key1 = Array.fill[Byte](20)(0x0b)
    assertEquals(
      Hmac.hex(key1, "Hi There".getBytes("UTF-8")),
      "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
    )
    assertEquals(
      Hmac.hex("Jefe".getBytes("UTF-8"), "what do ya want for nothing?".getBytes("UTF-8")),
      "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
    )
  }

  test("HMAC-SHA256 handles keys longer than the block size (RFC 4231 case 6)") {
    val key = Array.fill[Byte](131)(0xaa.toByte)
    assertEquals(
      Hmac.hex(key, "Test Using Larger Than Block-Size Key - Hash Key First".getBytes("UTF-8")),
      "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
    )
  }

  test(
    "sensitive digests are keyed: same text, different key ⇒ different digest; plain digest differs"
  ) {
    val k1 = SensitiveKeyProvider.static(KeyId.unsafe("k1"), "secret-one".getBytes("UTF-8"))
    val k2 = SensitiveKeyProvider.static(KeyId.unsafe("k2"), "secret-two".getBytes("UTF-8"))
    val a = MaterialDigest.of(Sensitivity.Sensitive, "my sister's wedding", k1).toOption.get
    val b = MaterialDigest.of(Sensitivity.Sensitive, "my sister's wedding", k2).toOption.get
    val p = MaterialDigest.of(Sensitivity.Public, "my sister's wedding", k1).toOption.get
    assertNotEquals(a.render, b.render)
    assertNotEquals(a.render, p.render)
    assert(a.render.startsWith("hmac:k1:"))
    assert(p.render.startsWith("plain:"))
    assertEquals(
      MaterialDigest.of(Sensitivity.Sensitive, "x", k1).toOption.get.render,
      MaterialDigest.of(Sensitivity.Sensitive, "x", k1).toOption.get.render
    )
    assert(SensitiveDigest.compute(KeyId.unsafe("k"), Array.empty[Byte], "x").isLeft)
  }

  test("a missing key is a typed error, never a plain fallback") {
    val provider = new SensitiveKeyProvider:
      def currentKeyId: KeyId = KeyId.unsafe("gone")
      def key(id: KeyId): Option[Array[Byte]] = None
    assert(MaterialDigest.of(Sensitivity.Sensitive, "text", provider).isLeft)
  }

  test(
    "RemotePolicy.evaluate is the only way to obtain an AuthorizedRemoteRequest and binds a capability"
  ) {
    val provider = ProviderFingerprint.of("m", "t", "i", "r")
    val policy = RemotePolicy(
      PrivacyPolicyId.unsafe("pol-1"),
      allowedProviders = Set(provider),
      allowedModels = Set("text-embedding-x"),
      allowedPurposes = Set("candidate-retrieval"),
      maxBudgetTokens = 10000,
      ttlMillis = 60000
    )
    val payload = PseudonymizedText(
      policy.id,
      KeyId.unsafe("k1"),
      "[PERSON_1] danced until midnight",
      Vector.empty
    )
    val space = EmbeddingSpace
      .of(
        provider,
        Role.Query,
        SemanticView.Surface,
        None,
        Dimension.unsafe(3),
        Normalization.L2,
        TruncationPolicy.Reject
      )
      .toOption
      .get
    val request = EmbedRequest(RequestId.unsafe("r1"), EmbedPayload.Sanitized(payload), space.id)
    val ok = RemotePolicy.evaluate(
      policy,
      request,
      payload,
      provider,
      "text-embedding-x",
      "candidate-retrieval",
      1000L,
      42L
    )
    assert(ok.isRight)
    val cap = ok.toOption.get.capability
    assertEquals(cap.expiresAtEpochMillis, 61000L)
    assertEquals(cap.payloadDigest, payload.digest)
    assertEquals(cap.policyId, policy.id)
    assert(
      RemotePolicy
        .evaluate(
          policy,
          request,
          payload,
          provider,
          "other-model",
          "candidate-retrieval",
          1000L,
          42L
        )
        .isLeft
    )
    assert(
      RemotePolicy
        .evaluate(
          policy,
          request,
          payload,
          ProviderFingerprint.of("x", "t", "i", "r"),
          "text-embedding-x",
          "candidate-retrieval",
          1000L,
          42L
        )
        .isLeft
    )
    assert(
      RemotePolicy
        .evaluate(policy, request, payload, provider, "text-embedding-x", "training", 1000L, 42L)
        .isLeft
    )
    assert(
      RemotePolicy
        .evaluate(
          policy,
          request,
          payload,
          provider,
          "text-embedding-x",
          "candidate-retrieval",
          1000L,
          20000L
        )
        .isLeft
    )
    val foreign = payload.copy(policyId = PrivacyPolicyId.unsafe("pol-2"))
    assert(
      RemotePolicy
        .evaluate(
          policy,
          request,
          foreign,
          provider,
          "text-embedding-x",
          "candidate-retrieval",
          1000L,
          42L
        )
        .isLeft
    )
    // Denial reasons never echo payload text.
    val denied = RemotePolicy
      .evaluate(
        policy,
        request,
        payload,
        provider,
        "other-model",
        "candidate-retrieval",
        1000L,
        42L
      )
      .left
      .toOption
      .get
    assert(!denied.reason.contains("danced"))
  }

  test(
    "PseudonymizedText digest depends on policy, key and text — never on the re-identification key"
  ) {
    val a =
      PseudonymizedText(PrivacyPolicyId.unsafe("p"), KeyId.unsafe("k"), "[PERSON_1]", Vector.empty)
    val b = a.copy(text = "[PERSON_2]")
    assertNotEquals(a.digest, b.digest)
    assertNotEquals(a.digest, a.copy(policyId = PrivacyPolicyId.unsafe("p2")).digest)
    assertNotEquals(a.digest, a.copy(keyId = KeyId.unsafe("k2")).digest)
    assertEquals(a.digest, a.copy(offsets = Vector.empty).digest)
    assert(
      a.digest != Checksum.ofText(a.text),
      "digest must not be the plain content hash of the text"
    )
  }
