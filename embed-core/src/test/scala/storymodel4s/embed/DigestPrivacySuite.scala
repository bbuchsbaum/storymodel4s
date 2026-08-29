package storymodel4s.embed

import munit.FunSuite

import storymodel4s.core.{Checksum, DomainError, TextSpan}

class DigestPrivacySuite extends FunSuite:

  private val k1 = SensitiveKeyProvider.static(KeyId.unsafe("k1"), "secret-one".getBytes("UTF-8"))
  private val k2 = SensitiveKeyProvider.static(KeyId.unsafe("k2"), "secret-two".getBytes("UTF-8"))

  private def checkedEither(
      policyId: PrivacyPolicyId,
      keyId: KeyId,
      source: String,
      destination: String,
      keys: SensitiveKeyProvider
  ): Either[DomainError, PseudonymizedText] =
    PseudonymizedText.checked(
      policyId,
      keyId,
      source,
      destination,
      Vector(TextSpan.unsafe(0, source.length) -> TextSpan.unsafe(0, destination.length)),
      keys
    )

  private def checkedPayload(
      policyId: PrivacyPolicyId,
      keyId: KeyId,
      source: String,
      destination: String,
      keys: SensitiveKeyProvider
  ): PseudonymizedText =
    checkedEither(policyId, keyId, source, destination, keys).toOption.get

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
    val a = ReceiptDigest.of(Sensitivity.Sensitive, "my sister's wedding", k1).toOption.get
    val b = ReceiptDigest.of(Sensitivity.Sensitive, "my sister's wedding", k2).toOption.get
    val p = ReceiptDigest.of(Sensitivity.Public, "my sister's wedding", k1).toOption.get
    assertNotEquals(a.render, b.render)
    assertNotEquals(a.render, p.render)
    assert(a.render.startsWith("hmac:k1:"))
    assert(p.render.startsWith("plain:"))
    assertEquals(
      ReceiptDigest.of(Sensitivity.Sensitive, "x", k1).toOption.get.render,
      ReceiptDigest.of(Sensitivity.Sensitive, "x", k1).toOption.get.render
    )
    assert(SensitiveDigest.compute(KeyId.unsafe("k"), Array.empty[Byte], "x").isLeft)
  }

  test("a missing key is a typed NoKey error, never a plain fallback") {
    val provider = new SensitiveKeyProvider:
      def currentKeyId: KeyId = KeyId.unsafe("gone")
      def key(id: KeyId): Option[Array[Byte]] = None
    ReceiptDigest.of(Sensitivity.Sensitive, "text", provider) match
      case Left(EmbedError.NoKey("gone")) => ()
      case other                          => fail(s"expected NoKey(gone), got $other")
  }

  test(
    "RemotePolicy.evaluate is the only way to obtain an AuthorizedRemoteRequest and binds the keyed payload identity"
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
    val payload = checkedPayload(policy.id, KeyId.unsafe("k1"), "Jane Smith", "[PERSON_1]", k1)
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
    def eval(
        req: EmbedRequest = request,
        prov: ProviderFingerprint = provider,
        model: String = "text-embedding-x",
        purpose: String = "candidate-retrieval",
        tokens: Long = 42L
    ) = RemotePolicy.evaluate(policy, req, prov, model, purpose, 1000L, tokens)
    val ok = eval()
    assert(ok.isRight)
    val cap = ok.toOption.get.capability
    assertEquals(cap.expiresAtEpochMillis, 61000L)
    assertEquals(cap.payloadDigest, payload.digest)
    assertEquals(cap.payloadDigest.kind, DigestKind.Keyed)
    assertEquals(cap.payloadDigest.keyIdOption, Some(KeyId.unsafe("k1")))
    assertEquals(cap.policyId, policy.id)
    assert(eval(model = "other-model").isLeft)
    assert(eval(prov = ProviderFingerprint.of("x", "t", "i", "r")).isLeft)
    assert(eval(purpose = "training").isLeft)
    assert(eval(tokens = 20000L).isLeft)
    // The pseudonymization key must be available at construction: no key ⇒ no payload, so no
    // capability can ever be minted over a plain identity.
    checkedEither(
      policy.id,
      KeyId.unsafe("k1"),
      "Jane Smith",
      "[PERSON_1]",
      SensitiveKeyProvider.none
    ) match
      case Left(DomainError.InvariantViolation(path, reason)) =>
        assertEquals(path, PseudonymizedText.KeyPath)
        assert(!reason.contains("Jane"))
      case other => fail(s"expected InvariantViolation(KeyPath), got $other")
    // A different store key under the same KeyId ⇒ a different capability payload identity.
    val k1Rotated =
      SensitiveKeyProvider.static(KeyId.unsafe("k1"), "secret-rotated".getBytes("UTF-8"))
    val rotated =
      checkedPayload(policy.id, KeyId.unsafe("k1"), "Jane Smith", "[PERSON_1]", k1Rotated)
    assertNotEquals(
      eval(req = request.copy(payload = EmbedPayload.Sanitized(rotated))).toOption
        .map(_.capability.payloadDigest.render),
      Some(cap.payloadDigest.render)
    )
    val foreign = checkedPayload(
      PrivacyPolicyId.unsafe("pol-2"),
      KeyId.unsafe("k1"),
      "Jane Smith",
      "[PERSON_1]",
      k1
    )
    assert(eval(req = request.copy(payload = EmbedPayload.Sanitized(foreign))).isLeft)
    // Denial reasons and capability renderings never echo payload text.
    val denied = eval(model = "other-model").left.toOption.get
    assert(!denied.reason.contains("PERSON_1"))
    assert(!cap.render.contains("PERSON_1"))
  }

  test(
    "PseudonymizedText digest is keyed and depends on policy, key and text — never on the re-identification key"
  ) {
    val kk = SensitiveKeyProvider.static(KeyId.unsafe("k"), "secret-k".getBytes("UTF-8"))
    val kk2 = SensitiveKeyProvider.static(KeyId.unsafe("k2"), "secret-k2".getBytes("UTF-8"))
    val a =
      checkedPayload(PrivacyPolicyId.unsafe("p"), KeyId.unsafe("k"), "Alice", "[PERSON_1]", kk)
    val b =
      checkedPayload(PrivacyPolicyId.unsafe("p"), KeyId.unsafe("k"), "Alice", "[PERSON_2]", kk)
    val otherPolicy =
      checkedPayload(PrivacyPolicyId.unsafe("p2"), KeyId.unsafe("k"), "Alice", "[PERSON_1]", kk)
    val otherKey =
      checkedPayload(PrivacyPolicyId.unsafe("p"), KeyId.unsafe("k2"), "Alice", "[PERSON_1]", kk2)
    def d(p: PseudonymizedText) = p.digest.render
    assertNotEquals(d(a), d(b))
    assertNotEquals(d(a), d(otherPolicy))
    assertNotEquals(d(a), d(otherKey))
    assert(d(a).startsWith("hmac:k:"))
    assertEquals(a.digest.kind, DigestKind.Keyed)
    assert(
      d(a) != s"plain:${Checksum.ofText(a.text).hex}",
      "digest must not be the plain content hash of the text"
    )
    assert(!d(a).contains("PERSON"))
    // No key under the payload's key id ⇒ no payload at all, never a plain identity.
    assert(
      checkedEither(
        PrivacyPolicyId.unsafe("p"),
        KeyId.unsafe("k"),
        "Alice",
        "[PERSON_1]",
        kk2
      ).isLeft
    )

    val sameTextOneReplacement = PseudonymizedText
      .checked(
        PrivacyPolicyId.unsafe("p"),
        KeyId.unsafe("k"),
        "Alice Bob",
        "[P] [Q]",
        Vector(TextSpan.unsafe(0, 9) -> TextSpan.unsafe(0, 7)),
        kk
      )
      .toOption
      .get
    val sameTextTwoReplacements = PseudonymizedText
      .checked(
        PrivacyPolicyId.unsafe("p"),
        KeyId.unsafe("k"),
        "Alice Bob",
        "[P] [Q]",
        Vector(
          TextSpan.unsafe(0, 5) -> TextSpan.unsafe(0, 3),
          TextSpan.unsafe(6, 9) -> TextSpan.unsafe(4, 7)
        ),
        kk
      )
      .toOption
      .get
    assertNotEquals(sameTextOneReplacement.offsets, sameTextTwoReplacements.offsets)
    assertEquals(d(sameTextOneReplacement), d(sameTextTwoReplacements))
  }
