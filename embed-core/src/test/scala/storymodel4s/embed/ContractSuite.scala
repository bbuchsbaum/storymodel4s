package storymodel4s.embed

import cats.Id
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{forAll, forAllNoShrink}

import storymodel4s.core.TextSpan
import storymodel4s.features.{Estimate, MalformedReason, MissingReason}

/** L4 and the contract's failure isolation, checked against a spy embedder. */
class ContractSuite extends ScalaCheckSuite:
  private val k1 = SensitiveKeyProvider.static(KeyId.unsafe("contract-k1"), Array[Byte](1, 2, 3))
  private val k2 = SensitiveKeyProvider.static(KeyId.unsafe("contract-k2"), Array[Byte](4, 5, 6))
  private val provider = ProviderFingerprint.of("spy", "none", "1", "test")
  private val d = Dimension.unsafe(3)
  private val docSpace =
    EmbeddingSpace
      .of(
        provider,
        Role.Document,
        SemanticView.Surface,
        None,
        d,
        Normalization.L2,
        TruncationPolicy.Reject
      )
      .toOption
      .get
  private val querySpace =
    EmbeddingSpace
      .of(
        provider,
        Role.Query,
        SemanticView.Surface,
        None,
        d,
        Normalization.L2,
        TruncationPolicy.Reject
      )
      .toOption
      .get

  /** Fails every request whose text contains "boom"; abstains on "quiet"; otherwise a unit vector.
    */
  private def spy(locality: Locality = Locality.Local): Embedder[Id] = new Embedder[Id]:
    val info: EmbedderInfo =
      EmbedderInfo(provider, "spy", "1", locality, PrivacyClass.SensitiveOk, 32, false, false, None)
    val spaces: Vector[EmbeddingSpace] = Vector(docSpace, querySpace)
    def embed(batch: EmbedBatch): BatchResult =
      val outcomes = batch.requests.map { r =>
        val t = r.payload.materialText
        val value: Either[ExecutionFailure, Estimate[ValidatedVector]] =
          if t.contains("boom") then Left(ExecutionFailure.ProviderError("E1", 1))
          else if t.contains("quiet") then
            Right(Estimate.missing(storymodel4s.features.MissingReason.ProviderAbstained))
          else
            Right(
              Estimate.observed(
                ValidatedVector.l2(d, Vector(1.0, 2.0, t.length.toDouble)).toOption.get
              )
            )
        EmbedOutcome(r.id, r.space, value)
      }
      BatchResult(outcomes, AttemptReceipt.empty)

  private def req(
      i: Int,
      text: String,
      space: EmbeddingSpace = docSpace,
      s: Sensitivity = Sensitivity.Public
  ) =
    EmbedRequest(RequestId.unsafe(s"r$i"), EmbedPayload.Raw(text, s), space.id)

  private def batchOf(n: Int, embedder: Embedder[Id]): EmbedBatch =
    EmbedBatch
      .validated((1 to n).map(i => req(i, s"text-$i")).toVector, embedder.spaceIds)
      .toOption
      .get

  private def mutated(
      f: Vector[EmbedOutcome] => Vector[EmbedOutcome]
  ): Embedder[Id] = new Embedder[Id]:
    val info: EmbedderInfo = spy().info
    val spaces: Vector[EmbeddingSpace] = spy().spaces
    def embed(batch: EmbedBatch): BatchResult =
      val result = spy().embed(batch)
      result.copy(outcomes = f(result.outcomes))

  private def isBatchFailure(result: BatchResult): Boolean =
    result.outcomes.forall(_.value match
      case Left(ExecutionFailure.Invalid(_)) => true
      case _                                 => false) && result.receipt.resultDecisions.exists {
      case ResultDecision.BatchRejected(_) => true
      case _                               => false
    }

  test("L4: one outcome per request id, in order; failures and abstentions are per item") {
    val batch = EmbedBatch
      .validated(
        Vector(req(1, "alpha"), req(2, "boom"), req(3, "quiet"), req(4, "delta")),
        spy().spaceIds
      )
      .toOption
      .get
    val res = spy().embed(batch)
    assert(res.conforms(batch).isRight)
    assertEquals(res.outcomes.map(_.id.value), Vector("r1", "r2", "r3", "r4"))
    assert(res.outcomes(0).value.exists(_.isObserved))
    assert(res.outcomes(1).value.isLeft)
    assert(res.outcomes(2).value.exists(e => !e.isObserved))
    assert(res.outcomes(3).value.exists(_.isObserved))
  }

  property("EmbedBatch rejects duplicate ids and unknown spaces") {
    forAll(Gen.choose(1, 6)) { n =>
      val reqs = (1 to n).map(i => req(i, "t")).toVector
      val dup = reqs :+ req(1, "again")
      val unknown = reqs :+ EmbedRequest(
        RequestId.unsafe("zz"),
        EmbedPayload.Raw("x", Sensitivity.Public),
        GeometryId.unsafe("nope")
      )
      EmbedBatch.validated(reqs, spy().spaceIds).isRight &&
      EmbedBatch.validated(dup, spy().spaceIds).isLeft &&
      EmbedBatch.validated(unknown, spy().spaceIds).isLeft
    }
  }

  property("L4: a wrong-space item becomes missing without poisoning valid siblings") {
    forAllNoShrink(Gen.choose(2, 6).flatMap(n => Gen.choose(0, n - 1).map(n -> _))) {
      case (n, bad) =>
        val broken =
          mutated(outcomes => outcomes.updated(bad, outcomes(bad).copy(space = querySpace.id)))
        val batch = batchOf(n, broken)
        val expected = spy().embed(batch)
        val result = Embedder.conforming(broken).embed(batch)

        result.conforms(batch).isRight &&
        result.outcomes.zipWithIndex.forall { case (outcome, index) =>
          if index == bad then
            outcome.value == Right(
              Estimate.Missing(MissingReason.Malformed(MalformedReason.ProviderResult))
            )
          else outcome == expected.outcomes(index)
        } &&
        result.receipt.resultDecisions.exists {
          case ResultDecision.SpaceRejected(id, expectedSpace, actualSpace) =>
            id == batch.requests(bad).id &&
            expectedSpace == docSpace.id &&
            actualSpace == querySpace.id
          case _ => false
        }
    }
  }

  property("L4: a complete id bijection is reordered without misassociation") {
    forAllNoShrink(Gen.choose(2, 6)) { n =>
      val broken = mutated(_.reverse)
      val batch = batchOf(n, broken)
      val expected = spy().embed(batch)
      val result = Embedder.conforming(broken).embed(batch)

      result.conforms(batch).isRight &&
      result.outcomes == expected.outcomes &&
      result.receipt.resultDecisions.count {
        case ResultDecision.Reordered(_, _, _) => true
        case _                                 => false
      } == n - (n % 2)
    }
  }

  property("L4: an unknown replacement id fails the batch closed") {
    forAllNoShrink(Gen.choose(2, 6).flatMap(n => Gen.choose(0, n - 1).map(n -> _))) {
      case (n, bad) =>
        val broken = mutated(outcomes =>
          outcomes.updated(bad, outcomes(bad).copy(id = RequestId.unsafe(s"unknown-$bad")))
        )
        val batch = batchOf(n, broken)
        val result = Embedder.conforming(broken).embed(batch)
        result.conforms(batch).isRight && isBatchFailure(result)
    }
  }

  property("L4: a missing outcome fails the batch closed") {
    forAllNoShrink(Gen.choose(2, 6).flatMap(n => Gen.choose(0, n - 1).map(n -> _))) {
      case (n, bad) =>
        val broken = mutated(_.patch(bad, Vector.empty, 1))
        val batch = batchOf(n, broken)
        val result = Embedder.conforming(broken).embed(batch)
        result.conforms(batch).isRight && isBatchFailure(result)
    }
  }

  property("L4: a duplicate outcome id fails the batch closed") {
    forAllNoShrink(Gen.choose(2, 6).flatMap(n => Gen.choose(1, n - 1).map(n -> _))) {
      case (n, bad) =>
        val broken = mutated(outcomes => outcomes.updated(bad, outcomes.head))
        val batch = batchOf(n, broken)
        val result = Embedder.conforming(broken).embed(batch)
        result.conforms(batch).isRight && isBatchFailure(result)
    }
  }

  property("L4: an extra outcome fails the batch closed") {
    forAllNoShrink(Gen.choose(2, 6)) { n =>
      val broken =
        mutated(outcomes => outcomes :+ outcomes.head.copy(id = RequestId.unsafe("extra")))
      val batch = batchOf(n, broken)
      val result = Embedder.conforming(broken).embed(batch)
      result.conforms(batch).isRight && isBatchFailure(result)
    }
  }

  test("preflight: raw sensitive text never reaches a remote provider; privacy class is enforced") {
    val remote = spy(Locality.Remote).info
    Embedder.preflight(remote, req(1, "x", s = Sensitivity.Sensitive)) match
      case Left(ExecutionFailure.LocalOnly(PolicyDecision.LocalOnly(id, None, _))) =>
        assertEquals(id, RequestId.unsafe("r1"))
      case other => fail(s"expected request-scoped LocalOnly, got $other")
    assert(Embedder.preflight(remote, req(1, "x", s = Sensitivity.Public)).isRight)
    val restricted = remote.copy(privacyClass = PrivacyClass.PublicOnly)
    Embedder.preflight(restricted, req(1, "x", s = Sensitivity.Internal)) match
      case Left(ExecutionFailure.PolicyDenied(PolicyDecision.Denied(id, None, _))) =>
        assertEquals(id, RequestId.unsafe("r1"))
      case other => fail(s"expected request-scoped Denied, got $other")
    val pseudonymizationKey = KeyId.unsafe("k")
    val pseudonymizationKeys =
      SensitiveKeyProvider.static(pseudonymizationKey, "k".getBytes("UTF-8"))
    val detector = PseudonymizationDetector
      .wholeWordTable(Vector(PseudonymizationTableEntry("Jane", "[PERSON_1]")))
      .toOption
      .get
    val sourceDetection = detector
      .detect("Jane went", pseudonymizationKey, pseudonymizationKeys)
      .toOption
      .get
    val sanitized = EmbedRequest(
      RequestId.unsafe("s"),
      EmbedPayload.Sanitized(
        PseudonymizedText
          .checked(
            PrivacyPolicyId.unsafe("p"),
            pseudonymizationKey,
            "Jane went",
            "[PERSON_1] went",
            Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 10)),
            sourceDetection,
            detector,
            pseudonymizationKeys
          )
          .toOption
          .get
      ),
      docSpace.id
    )
    assert(Embedder.preflight(remote, sanitized).isRight)
  }

  test("conforming rejects a foreign keyed receipt under the one batch authority") {
    val foreign = HashedNgramEmbedder[Id](64, 0L, k2)
    var returned: Option[BatchResult] = None
    val delegate = new Embedder[Id]:
      def info: EmbedderInfo = foreign.info
      def spaces: Vector[EmbeddingSpace] = foreign.spaces
      def embed(batch: EmbedBatch): BatchResult =
        val result = foreign.embed(batch)
        val id = batch.requests.head.id
        val injected = AttemptReceipt
          .of(
            result.receipt.providerCalls,
            result.receipt.embeddingReceipts,
            Vector(CacheDecision.Bypassed(id, "foreign-cache-canary")),
            Vector(PolicyDecision.Denied(id, None, "foreign-policy-canary")),
            result.receipt.resultDecisions,
            batch.itemSensitivity,
            k2
          )
          .fold(e => fail(e.message), identity)
        val injectedResult = result.copy(receipt = injected)
        returned = Some(injectedResult)
        injectedResult
    val request = EmbedBatch
      .validated(
        Vector(
          EmbedRequest(
            RequestId.unsafe("foreign-key"),
            EmbedPayload.Raw("private material", Sensitivity.Sensitive),
            foreign.spaces.head.id
          )
        ),
        foreign.spaceIds
      )
      .toOption
      .get

    val result = Embedder.conforming(delegate, k1).embed(request)

    assertEquals(result.receipt.providerCalls, returned.toVector.flatMap(_.receipt.providerCalls))
    assertEquals(result.receipt.embeddingReceipts, Vector.empty)
    assertEquals(result.receipt.cacheDecisions, Vector.empty)
    assertEquals(result.receipt.policyDecisions, Vector.empty)
    result.receipt.digest match
      case ReceiptDigest.Keyed(digest) => assertEquals(digest.keyId.value, "contract-k1")
      case other                       => fail(s"expected keyed contract-k1 receipt, got $other")
    assert(result.receipt.resultDecisions.exists {
      case ResultDecision.ReceiptRejected(
            EmbedError.ReceiptKeyMismatch(expected, found)
          ) =>
        expected.value == "contract-k1" && found.value == "contract-k2"
      case _ => false
    })
    assert(result.outcomes.forall {
      case EmbedOutcome(
            _,
            _,
            Left(ExecutionFailure.Invalid(EmbedError.ReceiptKeyMismatch(expected, found)))
          ) =>
        expected.value == "contract-k1" && found.value == "contract-k2"
      case _ => false
    })
  }

  test("conforming snapshots once before delegation and ignores mid-batch withdrawal") {
    val once = new SensitiveKeyProvider:
      private var served = false
      def currentKeyId: KeyId = KeyId.unsafe("contract-k1")
      def key(id: KeyId): Option[Array[Byte]] =
        Option.when(id == currentKeyId && !served) {
          served = true
          Array[Byte](1, 2, 3)
        }
    val inner = HashedNgramEmbedder[Id](64, 0L, k1)
    val request = EmbedBatch
      .validated(
        Vector(
          EmbedRequest(
            RequestId.unsafe("withdrawn"),
            EmbedPayload.Raw("private material", Sensitivity.Sensitive),
            inner.spaces.head.id
          )
        ),
        inner.spaceIds
      )
      .toOption
      .get

    val result = Embedder.conforming(inner, once).embed(request)

    assert(result.outcomes.forall(_.value.isRight))
    assertEquals(result.receipt.kind, DigestKind.Keyed)
  }

  test("conforming without a non-public key fails before delegate work") {
    val base = HashedNgramEmbedder[Id](64, 0L, k1)
    var calls = 0
    val delegate = new Embedder[Id]:
      def info: EmbedderInfo = base.info
      def spaces: Vector[EmbeddingSpace] = base.spaces
      def embed(batch: EmbedBatch): BatchResult =
        calls += 1
        base.embed(batch)
    val request = EmbedBatch
      .validated(
        Vector(
          EmbedRequest(
            RequestId.unsafe("no-key"),
            EmbedPayload.Raw("private material", Sensitivity.Sensitive),
            base.spaces.head.id
          )
        ),
        base.spaceIds
      )
      .toOption
      .get

    val result = Embedder.conforming(delegate).embed(request)

    assertEquals(calls, 0)
    assertEquals(result.receipt.kind, DigestKind.Withheld)
    assertEquals(result.receipt.providerCalls, Vector.empty)
  }

  test("AttemptReceipt digest is deterministic and sensitive to its decisions") {
    def pub(reason: String): AttemptReceipt =
      AttemptReceipt
        .public(
          Vector.empty,
          Vector(CacheDecision.Bypassed(RequestId.unsafe("r1"), reason)),
          Vector.empty,
          Vector.empty,
          Vector(RequestId.unsafe("r1") -> Sensitivity.Public)
        )
        .fold(e => fail(e.message), identity)
    val a = pub("x")
    val b = pub("x")
    val c = pub("y")
    assertEquals(a.digest, b.digest)
    assertNotEquals(a.digest, c.digest)
    assertEquals(AttemptReceipt.empty.digest.kind, DigestKind.Plain)
  }

  test("a plain receipt refuses sensitivity evidence that is not public (required (2))") {
    val refused = AttemptReceipt.public(
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector(RequestId.unsafe("s1") -> Sensitivity.Sensitive)
    )
    assert(refused.isLeft, "a keyed batch must not be re-receipted as plain")
    assert(
      AttemptReceipt
        .public(
          Vector.empty,
          Vector.empty,
          Vector.empty,
          Vector.empty,
          Vector(RequestId.unsafe("i1") -> Sensitivity.Internal)
        )
        .isLeft,
      "Internal is not plain-admissible either"
    )
  }

  test("L6 shape: a credence from a distance is raw unless a calibration model is named") {
    val dist = 0.3
    val raw = storymodel4s.core.Credence.from(dist, None, None)
    assert(raw.exists(_.calibrated.isEmpty))
    assert(
      storymodel4s.core.Credence
        .from(dist, Some(storymodel4s.core.Probability.unsafe(0.7)), None)
        .isLeft
    )
    assert(
      storymodel4s.core.Credence
        .from(dist, Some(storymodel4s.core.Probability.unsafe(0.7)), Some("iso-v1"))
        .isRight
    )
  }
