package storymodel4s.embed

import munit.FunSuite

import storymodel4s.core.{DomainError, TextSpan}

class PrivacySuite extends FunSuite:

  private val policy = PrivacyPolicyId.unsafe("policy")
  private val key = KeyId.unsafe("key")

  private def checked(
      source: String,
      destination: String,
      offsets: Vector[(TextSpan, TextSpan)]
  ): Either[DomainError, PseudonymizedText] =
    PseudonymizedText.checked(policy, key, source, destination, offsets)

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
    val copy = compileErrors(
      """PseudonymizedText.checked(
        PrivacyPolicyId.unsafe("p"),
        KeyId.unsafe("k"),
        "Jane",
        "[PERSON]",
        Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 8))
      ).toOption.get.copy(text = "raw")"""
    )
    assert(constructor.nonEmpty)
    assert(apply.nonEmpty)
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
    assert(!result.toOption.get.toString.contains("Jane"))
    assert(!result.toOption.get.toString.contains("PERSON"))
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
  }

  test("RemotePolicy never authorizes a raw request") {
    val provider = ProviderFingerprint.of("model", "tokenizer", "implementation", "runtime")
    val policyValue = RemotePolicy(
      policy,
      Set(provider),
      Set("model"),
      Set("research"),
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
