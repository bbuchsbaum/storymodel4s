package storymodel4s.embed

import cats.Id
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import storymodel4s.core.Checksum
import storymodel4s.features.Estimate

/** L4 and the contract's failure isolation, checked against a spy embedder. */
class ContractSuite extends ScalaCheckSuite:
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
      BatchResult(outcomes, AttemptReceipt.of(Vector.empty, Vector.empty, Vector.empty))

  private def req(
      i: Int,
      text: String,
      space: EmbeddingSpace = docSpace,
      s: Sensitivity = Sensitivity.Public
  ) =
    EmbedRequest(RequestId.unsafe(s"r$i"), EmbedPayload.Raw(text, s), space.id)

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

  test("Embedder.conforming turns a malformed result into per-item typed failures") {
    val broken: Embedder[Id] = new Embedder[Id]:
      val info: EmbedderInfo = spy().info
      val spaces: Vector[EmbeddingSpace] = spy().spaces
      def embed(batch: EmbedBatch): BatchResult =
        val r = spy().embed(batch)
        r.copy(outcomes = r.outcomes.reverse)
    val batch = EmbedBatch.validated(Vector(req(1, "a"), req(2, "b")), broken.spaceIds).toOption.get
    val res = Embedder.conforming(broken).embed(batch)
    assert(res.conforms(batch).isRight)
    assert(res.outcomes.forall(_.value.isLeft))
    val single = EmbedBatch.validated(Vector(req(1, "only")), broken.spaceIds).toOption.get
    assert(Embedder.conforming(broken).embed(single).outcomes.head.value.isRight)
  }

  test("preflight: raw sensitive text never reaches a remote provider; privacy class is enforced") {
    val remote = spy(Locality.Remote).info
    assert(Embedder.preflight(remote, req(1, "x", s = Sensitivity.Sensitive)).isLeft)
    assert(Embedder.preflight(remote, req(1, "x", s = Sensitivity.Public)).isRight)
    val restricted = remote.copy(privacyClass = PrivacyClass.PublicOnly)
    assert(Embedder.preflight(restricted, req(1, "x", s = Sensitivity.Internal)).isLeft)
    val sanitized = EmbedRequest(
      RequestId.unsafe("s"),
      EmbedPayload.Sanitized(
        PseudonymizedText(
          PrivacyPolicyId.unsafe("p"),
          KeyId.unsafe("k"),
          "[PERSON_1] went",
          Vector.empty
        )
      ),
      docSpace.id
    )
    assert(Embedder.preflight(remote, sanitized).isRight)
  }

  test("AttemptReceipt digest is deterministic and sensitive to its decisions") {
    val a = AttemptReceipt.of(
      Vector.empty,
      Vector(CacheDecision.Bypassed(RequestId.unsafe("r1"), "x")),
      Vector.empty
    )
    val b = AttemptReceipt.of(
      Vector.empty,
      Vector(CacheDecision.Bypassed(RequestId.unsafe("r1"), "x")),
      Vector.empty
    )
    val c = AttemptReceipt.of(
      Vector.empty,
      Vector(CacheDecision.Bypassed(RequestId.unsafe("r1"), "y")),
      Vector.empty
    )
    assertEquals(a.digest, b.digest)
    assertNotEquals(a.digest, c.digest)
    assertEquals(
      AttemptReceipt.of(Vector.empty, Vector.empty, Vector.empty).digest,
      Checksum.ofText("")
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
