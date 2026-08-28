package storymodel4s.embed

import munit.FunSuite

import storymodel4s.core.{DomainError, TextSpan}

class PrivacySuite extends FunSuite:

  private val policy = PrivacyPolicyId.unsafe("policy")
  private val key = KeyId.unsafe("key")
  private val suiteKeys =
    SensitiveKeyProvider.static(key, Array[Byte](3, 5, 7, 11, 13, 17, 19, 23))

  private def detectorFor(
      source: String,
      destination: String,
      sourceSpans: Vector[TextSpan],
      destinationSpans: Vector[TextSpan]
  ): Either[DomainError, PseudonymizationDetector] =
    PseudonymizationDetector.checked(
      PseudonymizationDetectorId.unsafe("storymodel4s.test.detector/v1"),
      "test-detector/v1|mode=exact-fixture",
      text =>
        if text == source then sourceSpans
        else if text == destination then destinationSpans
        else Vector.empty
    )

  private def checked(
      source: String,
      destination: String,
      offsets: Vector[(TextSpan, TextSpan)],
      detectedSourceSpans: Option[Vector[TextSpan]] = None,
      detectedDestinationSpans: Vector[TextSpan] = Vector.empty
  ): Either[DomainError, PseudonymizedText] =
    for
      detector <- detectorFor(
        source,
        destination,
        detectedSourceSpans.getOrElse(offsets.map(_._1)),
        detectedDestinationSpans
      )
      sourceDetection <- detector.detect(source, key, suiteKeys)
      payload <- PseudonymizedText.checked(
        policy,
        key,
        source,
        destination,
        offsets,
        sourceDetection,
        detector,
        suiteKeys
      )
    yield payload

  private def errorOf(result: Either[DomainError, PseudonymizedText]): DomainError =
    result.left.toOption.getOrElse(fail("expected pseudonymization validation to fail"))

  test("PseudonymizedText has no public constructor, apply, or copy escape hatch") {
    val constructor = compileErrors(
      """new PseudonymizedText(
        PrivacyPolicyId.unsafe("p"),
        KeyId.unsafe("k"),
        "raw",
        Vector.empty
      )"""
    )
    val apply = compileErrors(
      """PseudonymizedText(
        PrivacyPolicyId.unsafe("p"),
        KeyId.unsafe("k"),
        "raw",
        Vector.empty
      )"""
    )
    val legacyChecked = compileErrors(
      """PseudonymizedText.checked(
        PrivacyPolicyId.unsafe("p"),
        KeyId.unsafe("k"),
        "Jane",
        "[PERSON]",
        Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 8)),
        SensitiveKeyProvider.static(KeyId.unsafe("k"), "k".getBytes("UTF-8"))
      )"""
    )
    val copy = compileErrors("null.asInstanceOf[PseudonymizedText].copy()")
    assert(constructor.nonEmpty)
    assert(apply.nonEmpty)
    assert(legacyChecked.nonEmpty)
    assert(copy.nonEmpty)
  }

  test("checked accepts a complete ordered map and preserves supplementary code points") {
    val source = "Jane 😀 went home"
    val destination = "[PERSON_1] 😀 went home"
    val result = checked(
      source,
      destination,
      Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 10))
    )
    assertEquals(result.map(_.text), Right(destination))
    assertEquals(result.map(_.offsets.size), Right(1))
    assertEquals(
      result.flatMap(_.sourceDetection.toRight(fail("missing source receipt"))).map(_.spans.size),
      Right(1)
    )
    assertEquals(
      result
        .flatMap(_.destinationDetection.toRight(fail("missing destination receipt")))
        .map(_.spans),
      Right(Vector.empty)
    )
    assert(!result.toOption.get.toString.contains("Jane"))
    assert(!result.toOption.get.toString.contains("PERSON"))
  }

  test("detector factory requires versioned identities and validates minted spans") {
    assert(
      PseudonymizationDetector
        .checked(
          PseudonymizationDetectorId.unsafe("unversioned"),
          "config/v1",
          _ => Vector.empty
        )
        .isLeft
    )
    assert(
      PseudonymizationDetector
        .checked(
          PseudonymizationDetectorId.unsafe("detector/v1"),
          "unversioned",
          _ => Vector.empty
        )
        .isLeft
    )
    assert(
      PseudonymizationDetector
        .checked(
          PseudonymizationDetectorId.unsafe("detector/v1"),
          "config/v1|malformed=\ud83d",
          _ => Vector.empty
        )
        .isLeft
    )

    val invalid = PseudonymizationDetector
      .checked(
        PseudonymizationDetectorId.unsafe("detector/v1"),
        "config/v1|mode=invalid-span-fixture",
        _ => Vector(TextSpan.unsafe(0, 2), TextSpan.unsafe(1, 3))
      )
      .toOption
      .get
    assert(invalid.detect("Jane", key, suiteKeys).isLeft)
  }

  test("detection receipts retain only keyed identities and offsets") {
    val detector = PseudonymizationDetector
      .checked(
        PseudonymizationDetectorId.unsafe("detector.safe/v1"),
        "secret-config/v1|surface=Jane|pseudonym=[PERSON_1]",
        text => if text == "Jane" then Vector(TextSpan.unsafe(0, 4)) else Vector.empty
      )
      .toOption
      .get
    val receipt = detector.detect("Jane", key, suiteKeys).toOption.get

    assertEquals(receipt.configurationDigest.keyId, key)
    assertEquals(receipt.sourceDigest.keyId, key)
    assert(!receipt.render.contains("Jane"))
    assert(!receipt.render.contains("PERSON"))
    assert(!detector.toString.contains("Jane"))
  }

  test("checked snapshots the caller key exactly once before detector verification") {
    val source = "Jane"
    val destination = "[PERSON_1]"
    val offsets = Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 10))
    val detector = detectorFor(source, destination, offsets.map(_._1), Vector.empty).toOption.get
    val sourceDetection = detector.detect(source, key, suiteKeys).toOption.get
    val keyBytes = Array[Byte](3, 5, 7, 11, 13, 17, 19, 23)
    var reads = 0
    val oneReadKeys = new SensitiveKeyProvider:
      def currentKeyId: KeyId = PrivacySuite.this.key
      def key(id: KeyId): Option[Array[Byte]] =
        reads += 1
        Option.when(id == PrivacySuite.this.key && reads == 1)(keyBytes)

    val result = PseudonymizedText.checked(
      policy,
      key,
      source,
      destination,
      offsets,
      sourceDetection,
      detector,
      oneReadKeys
    )

    assert(result.isRight)
    assertEquals(reads, 1)
  }

  test("raw sensitive surface text cannot be laundered through an empty offset map") {
    assert(checked("Jane Smith had cancer", "Jane Smith had cancer", Vector.empty).isLeft)
  }

  test("laundering tricks fail with distinct typed validation evidence") {
    val emptyMap = errorOf(checked("Jane had cancer", "[P1] had cancer", Vector.empty))
    val fictitiousMap = errorOf(
      checked(
        "Jane",
        "Jane",
        Vector(
          TextSpan.unsafe(0, 1) -> TextSpan.unsafe(0, 2),
          TextSpan.unsafe(1, 4) -> TextSpan.unsafe(2, 4)
        )
      )
    )
    val overlap = errorOf(
      checked(
        "JaneBob",
        "[P1][P2]",
        Vector(
          TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4),
          TextSpan.unsafe(2, 7) -> TextSpan.unsafe(4, 8)
        )
      )
    )
    val unchanged = errorOf(
      checked(
        "Jane X",
        "Jane Y",
        Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4))
      )
    )
    val surrogateSplit = errorOf(
      checked(
        "A😀B",
        "A[X]B",
        Vector(TextSpan.unsafe(1, 2) -> TextSpan.unsafe(1, 4))
      )
    )

    assert(emptyMap.isInstanceOf[DomainError.InvariantViolation])
    assert(fictitiousMap.isInstanceOf[DomainError.InvariantViolation])
    assert(overlap.isInstanceOf[DomainError.InvariantViolation])
    assert(unchanged.isInstanceOf[DomainError.InvariantViolation])
    assert(surrogateSplit.isInstanceOf[DomainError.InvalidSpan])
    assertEquals(
      Vector(emptyMap, fictitiousMap, overlap, unchanged, surrogateSplit)
        .map(_.message)
        .distinct
        .size,
      5
    )
    assert(
      Vector(emptyMap, fictitiousMap, overlap, unchanged, surrogateSplit).forall(error =>
        !error.message.contains("Jane") && !error.message.contains("cancer")
      )
    )
  }

  test("a fictitious map cannot bless unchanged mapped text") {
    assert(
      checked(
        "Jane Smith had cancer",
        "Jane Smith had cancer",
        Vector(TextSpan.unsafe(0, 10) -> TextSpan.unsafe(0, 10))
      ).isLeft
    )
    assert(
      checked(
        "Jane",
        "Jane",
        Vector(
          TextSpan.unsafe(0, 1) -> TextSpan.unsafe(0, 2),
          TextSpan.unsafe(1, 4) -> TextSpan.unsafe(2, 4)
        )
      ).isLeft
    )
  }

  test("offsets must account for every change outside replacement spans") {
    assert(
      checked(
        "Jane met Bob",
        "[PERSON_1] saw Bob",
        Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 10))
      ).isLeft
    )
  }

  test("a replacement cannot retain its source span as a substring") {
    assert(
      checked(
        "Jane",
        "Jane S.",
        Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 7))
      ).isLeft
    )
  }

  test("a replacement cannot launder a source surface through case changes") {
    assert(
      checked(
        "Jane",
        "JANE",
        Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4))
      ).isLeft
    )

    val deseretUpper = "\ud801\udc00\ud801\udc01"
    val deseretLower = "\ud801\udc28\ud801\udc29"
    assert(
      checked(
        deseretUpper,
        deseretLower,
        Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4))
      ).isLeft
    )
  }

  test("source and destination offsets must be ordered and nonoverlapping") {
    val sourceOverlap = checked(
      "JaneBob",
      "[P1][P2]",
      Vector(
        TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4),
        TextSpan.unsafe(2, 7) -> TextSpan.unsafe(4, 8)
      )
    )
    val destinationOverlap = checked(
      "JaneBob",
      "[P1][P2]",
      Vector(
        TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4),
        TextSpan.unsafe(4, 7) -> TextSpan.unsafe(2, 6)
      )
    )
    assert(sourceOverlap.isLeft)
    assert(destinationOverlap.isLeft)
  }

  test("source and destination offsets must be in bounds") {
    assert(
      checked(
        "Jane",
        "[P1]",
        Vector(TextSpan.unsafe(0, 5) -> TextSpan.unsafe(0, 4))
      ).isLeft
    )
    assert(
      checked(
        "Jane",
        "[P1]",
        Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 5))
      ).isLeft
    )
  }

  test("source and destination offsets cannot split a UTF-16 surrogate pair") {
    val sourceSplit = checked(
      "A😀B",
      "A[X]B",
      Vector(TextSpan.unsafe(1, 2) -> TextSpan.unsafe(1, 4))
    )
    val destinationSplit = checked(
      "AxxB",
      "A😀B",
      Vector(TextSpan.unsafe(1, 3) -> TextSpan.unsafe(1, 2))
    )
    assert(sourceSplit.isLeft)
    assert(destinationSplit.isLeft)
  }

  test("checked rejects unpaired UTF-16 surrogates") {
    assert(
      checked(
        "A\ud83dB",
        "A[X]B",
        Vector(TextSpan.unsafe(1, 2) -> TextSpan.unsafe(1, 4))
      ).isLeft
    )
  }

  test("detected source spans must exactly equal the mapped source spans") {
    val source = "Jane met Bob"
    val destination = "[P1] met [P2]"
    val offsets = Vector(
      TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4),
      TextSpan.unsafe(9, 12) -> TextSpan.unsafe(9, 13)
    )
    val result = checked(
      source,
      destination,
      offsets,
      detectedSourceSpans = Some(Vector(TextSpan.unsafe(0, 4)))
    )
    assert(result.isLeft)
    assert(!result.left.toOption.get.message.contains("Jane"))
    assert(!result.left.toOption.get.message.contains("Bob"))
  }

  test("destination re-detection catches a deliberately under-detecting source detector") {
    def firstJane(text: String): Vector[TextSpan] =
      val first = text.indexOf("Jane")
      if first < 0 then Vector.empty else Vector(TextSpan.unsafe(first, first + 4))

    val detector = PseudonymizationDetector
      .checked(
        PseudonymizationDetectorId.unsafe("storymodel4s.test.underdetector/v1"),
        "under-detector/v1|surface=redacted",
        firstJane
      )
      .toOption
      .get
    val source = "Jane met Jane"
    val destination = "[P1] met Jane"
    val sourceDetection = detector.detect(source, key, suiteKeys).toOption.get
    val result = PseudonymizedText.checked(
      policy,
      key,
      source,
      destination,
      Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4)),
      sourceDetection,
      detector,
      suiteKeys
    )

    assert(result.isLeft)
    assert(result.left.toOption.get.message.contains("destination"))
    assert(!result.left.toOption.get.message.contains("Jane"))
  }

  test("RemotePolicy authorization uses the sanitized value carried by the request") {
    val provider = ProviderFingerprint.of("model", "tokenizer", "implementation", "runtime")
    val payload = checked(
      "Jane Smith",
      "[PERSON_1]",
      Vector(TextSpan.unsafe(0, 10) -> TextSpan.unsafe(0, 10))
    ).toOption.get
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
    val policyValue = RemotePolicy(
      policy,
      Set(provider),
      Set("model"),
      Set("research"),
      allowedDetectors = Set(payload.sourceDetection.get.policyIdentity),
      maxBudgetTokens = 100,
      ttlMillis = 1000
    )
    val request = EmbedRequest(
      RequestId.unsafe("request"),
      EmbedPayload.Sanitized(payload),
      space.id
    )

    val authorized = RemotePolicy.evaluate(
      policyValue,
      request,
      provider,
      "model",
      "research",
      nowEpochMillis = 10,
      estimatedTokens = 5
    )

    assertEquals(authorized.map(_.payload), Right(payload))
    assertEquals(authorized.map(_.capability.payloadDigest), Right(payload.digest))
    assertEquals(payload.digest.kind, DigestKind.Keyed)
    assertEquals(payload.digest.keyIdOption, Some(key))
    assert(
      RemotePolicy
        .evaluate(
          policyValue.copy(allowedDetectors = Set.empty),
          request,
          provider,
          "model",
          "research",
          nowEpochMillis = 10,
          estimatedTokens = 5
        )
        .isLeft
    )

    val absent = PseudonymizedText
      .legacyV1ForTest(
        policy,
        key,
        "Jane Smith",
        "[PERSON_1]",
        Vector(TextSpan.unsafe(0, 10) -> TextSpan.unsafe(0, 10)),
        suiteKeys
      )
      .toOption
      .get
    val other = checked(
      "Jill Smith",
      "[PERSON_1]",
      Vector(TextSpan.unsafe(0, 10) -> TextSpan.unsafe(0, 10))
    ).toOption.get
    val swapped = PseudonymizedText
      .substituteDetectionsForTest(
        payload,
        other.sourceDetection,
        payload.destinationDetection,
        suiteKeys
      )
      .toOption
      .get

    assertNotEquals(absent.digest, payload.digest)
    assertNotEquals(swapped.digest, payload.digest)
    assert(
      RemotePolicy
        .evaluate(
          policyValue,
          request.copy(payload = EmbedPayload.Sanitized(absent)),
          provider,
          "model",
          "research",
          nowEpochMillis = 10,
          estimatedTokens = 5
        )
        .isLeft
    )
    assert(
      RemotePolicy
        .evaluate(
          policyValue,
          request.copy(payload = EmbedPayload.Sanitized(swapped)),
          provider,
          "model",
          "research",
          nowEpochMillis = 10,
          estimatedTokens = 5
        )
        .isLeft
    )
  }

  test("RemotePolicy never authorizes a raw request") {
    val provider = ProviderFingerprint.of("model", "tokenizer", "implementation", "runtime")
    val policyValue = RemotePolicy(
      policy,
      Set(provider),
      Set("model"),
      Set("research"),
      allowedDetectors = Set.empty,
      maxBudgetTokens = 100,
      ttlMillis = 1000
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
    val request = EmbedRequest(
      RequestId.unsafe("request"),
      EmbedPayload.Raw("Jane Smith had cancer", Sensitivity.Sensitive),
      space.id
    )

    val denied = RemotePolicy.evaluate(
      policyValue,
      request,
      provider,
      "model",
      "research",
      nowEpochMillis = 10,
      estimatedTokens = 5
    )

    assert(denied.isLeft)
    assert(!denied.left.toOption.get.reason.contains("Jane"))
  }
