package storymodel4s.embedprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Probes from OUTSIDE `storymodel4s.embed`: validating values expose public observations without
  * exposing derived reconstruction paths around their checked factories and policy gate.
  */
class ConstructionProbeSuite extends FunSuite:

  // Macro snippets are strings, so these real references make Zinc invalidate the court.
  private val dependsOn: List[Class[?]] = List(
    classOf[storymodel4s.embed.ReceiptDigest.Plain],
    classOf[storymodel4s.embed.ReceiptDigest.Keyed],
    classOf[storymodel4s.embed.ReceiptDigest.Withheld],
    classOf[storymodel4s.embed.CacheKey],
    classOf[storymodel4s.embed.ItemDigest],
    classOf[storymodel4s.embed.ValidatedVector],
    classOf[storymodel4s.embed.EmbeddingReceipt],
    classOf[storymodel4s.embed.SensitiveDigest],
    classOf[storymodel4s.embed.EmbeddingSpace],
    classOf[storymodel4s.embed.GeometryPair],
    classOf[storymodel4s.embed.RemoteCapability],
    classOf[storymodel4s.embed.AuthorizedRemoteRequest],
    classOf[storymodel4s.embed.EmbedBatch],
    classOf[storymodel4s.embed.AttemptReceipt],
    classOf[storymodel4s.embed.PseudonymizationDetection],
    classOf[storymodel4s.embed.PseudonymizationDetector],
    classOf[storymodel4s.embed.DetectorPolicyIdentity],
    classOf[storymodel4s.embed.PseudonymizedText],
    classOf[storymodel4s.embed.LatePoolingRecipe],
    classOf[storymodel4s.embed.InstructionDigest],
    classOf[storymodel4s.embed.ExecutionFailure.PolicyDenied],
    classOf[storymodel4s.embed.PolicyDecision.Allowed],
    classOf[storymodel4s.embed.PolicyDecision.LocalOnly],
    classOf[storymodel4s.embed.RemotePolicy]
  )

  /** Sweep 3 slice: the Mirror.ProductOf door across embed-core's private-constructor case classes.
    *
    * `case class X private (...)` still derives `Mirror.ProductOf` in Scala 3, so the private
    * constructor is defeated from outside the package. Pins the measured state; closing any of
    * these makes this FAIL and asks for the name to be removed. Tracked on
    * bd-01M183VBPNEAPT5JNQMBMYMKQ9.
    */
  test("sweep 3: Mirror.ProductOf door across embed-core private-constructor case classes") {
    assert(dependsOn.forall(_ != null))
    assertEquals(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SurfaceUnit]]"),
      Nil,
      "the summon control must compile or every result below is meaningless"
    )
    val results: List[(String, List[scala.compiletime.testing.Error])] = List(
      (
        "ReceiptDigest.Plain",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.embed.ReceiptDigest.Plain]]"
        )
      ),
      (
        "ReceiptDigest.Keyed",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.embed.ReceiptDigest.Keyed]]"
        )
      ),
      (
        "ReceiptDigest.Withheld",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.embed.ReceiptDigest.Withheld]]"
        )
      )
    )
    val forgeable = results.collect { case (n, errs) if errs.isEmpty => n }.toSet
    assertEquals(
      forgeable,
      results.map(_._1).toSet,
      "sweep 3 expects the Mirror door OPEN here; a difference means one was closed (update this " +
        "list) or a new private-constructor case class was added unmeasured"
    )
  }

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not be constructible outside storymodel4s.embed")

  test("ReceiptDigest.Plain cannot be constructed outside the package") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.ReceiptDigest.Plain(storymodel4s.core.Checksum.ofText("x"))"""
      ),
      "ReceiptDigest.Plain"
    )
  }

  test("ReceiptDigest.Keyed cannot be constructed outside the package") {
    refused(
      typeCheckErrors(
        """(d: storymodel4s.embed.SensitiveDigest) => storymodel4s.embed.ReceiptDigest.Keyed(d)"""
      ),
      "ReceiptDigest.Keyed"
    )
  }

  test("ReceiptDigest.Withheld cannot be constructed outside the package") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.ReceiptDigest.Withheld(
             storymodel4s.embed.KeyId.unsafe("k"),
             storymodel4s.core.Checksum.ofText("x")
           )"""
      ),
      "ReceiptDigest.Withheld"
    )
  }

  test("CacheKey and ItemDigest cannot be constructed outside the package") {
    refused(
      typeCheckErrors(
        """(g: storymodel4s.embed.GeometryId, d: storymodel4s.embed.ReceiptDigest) =>
             storymodel4s.embed.CacheKey(g, d)"""
      ),
      "CacheKey"
    )
    refused(
      typeCheckErrors(
        """(d: storymodel4s.embed.ReceiptDigest) =>
             storymodel4s.embed.ItemDigest(
               storymodel4s.embed.RequestId.unsafe("r"),
               storymodel4s.embed.Sensitivity.Sensitive,
               d
             )"""
      ),
      "ItemDigest"
    )
  }

  test("ValidatedVector has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.ValidatedVector.fromProduct(EmptyTuple)"""
      ),
      "ValidatedVector.fromProduct"
    )
  }

  test("EmbeddingReceipt has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.EmbeddingReceipt.fromProduct(EmptyTuple)"""
      ),
      "EmbeddingReceipt.fromProduct"
    )
  }

  test("ItemDigest has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.ItemDigest.fromProduct(EmptyTuple)"""
      ),
      "ItemDigest.fromProduct"
    )
  }

  test("SensitiveDigest has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.SensitiveDigest.fromProduct(EmptyTuple)"""
      ),
      "SensitiveDigest.fromProduct"
    )
  }

  test("CacheKey has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.CacheKey.fromProduct(EmptyTuple)"""
      ),
      "CacheKey.fromProduct"
    )
  }

  test("EmbeddingSpace has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.EmbeddingSpace.fromProduct(EmptyTuple)"""
      ),
      "EmbeddingSpace.fromProduct"
    )
  }

  test("GeometryPair has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.GeometryPair.fromProduct(EmptyTuple)"""
      ),
      "GeometryPair.fromProduct"
    )
  }

  test("RemoteCapability has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.RemoteCapability.fromProduct(EmptyTuple)"""
      ),
      "RemoteCapability.fromProduct"
    )
  }

  test("AuthorizedRemoteRequest has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.AuthorizedRemoteRequest.fromProduct(EmptyTuple)"""
      ),
      "AuthorizedRemoteRequest.fromProduct"
    )
  }

  test("EmbedBatch has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.EmbedBatch.fromProduct(EmptyTuple)"""
      ),
      "EmbedBatch.fromProduct"
    )
  }

  test("the ten validating values retain public read access") {
    val reads = List(
      typeCheckErrors(
        """(value: storymodel4s.embed.ValidatedVector) =>
             (value.dimension, value.normalization, value.values)"""
      ),
      typeCheckErrors(
        """(value: storymodel4s.embed.EmbeddingReceipt) =>
             (value.call, value.kind, value.items, value.outputs)"""
      ),
      typeCheckErrors(
        """(value: storymodel4s.embed.ItemDigest) =>
             (value.id, value.sensitivity, value.digest)"""
      ),
      typeCheckErrors(
        """(value: storymodel4s.embed.SensitiveDigest) => (value.keyId, value.hex)"""
      ),
      typeCheckErrors(
        """(value: storymodel4s.embed.CacheKey) => (value.space, value.digest)"""
      ),
      typeCheckErrors(
        """(value: storymodel4s.embed.EmbeddingSpace) =>
             (value.id, value.provider, value.role, value.view, value.instruction,
              value.dimension, value.normalization, value.truncation,
              value.latePooling, value.parent)"""
      ),
      typeCheckErrors(
        """(value: storymodel4s.embed.GeometryPair) =>
             (value.query, value.document, value.rule)"""
      ),
      typeCheckErrors(
        """(value: storymodel4s.embed.RemoteCapability) =>
             (value.provider, value.model, value.purpose, value.policyId,
              value.expiresAtEpochMillis, value.budgetTokens, value.detectorIdentity,
              value.payloadDigest)"""
      ),
      typeCheckErrors(
        """(value: storymodel4s.embed.AuthorizedRemoteRequest) =>
             (value.id, value.space, value.payload, value.capability)"""
      ),
      typeCheckErrors(
        """(value: storymodel4s.embed.EmbedBatch) => value.requests"""
      )
    )
    assert(reads.forall(_.isEmpty), reads.flatten.mkString("\n"))
  }

  test("the checked factories remain the public path") {
    assert(
      typeCheckErrors(
        """(k: storymodel4s.embed.SensitiveKeyProvider) =>
             storymodel4s.embed.ReceiptDigest.of(storymodel4s.embed.Sensitivity.Public, "x", k)"""
      ).isEmpty
    )
  }

  test("AttemptReceipt.public cannot be called outside the package (required (2))") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.AttemptReceipt.public(
             Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty)"""
      ),
      "AttemptReceipt.public"
    )
  }

  test("ExecutionFailure.PolicyDenied cannot carry an Allowed decision") {
    refused(
      typeCheckErrors(
        """(a: storymodel4s.embed.PolicyDecision.Allowed) =>
             storymodel4s.embed.ExecutionFailure.PolicyDenied(a)"""
      ),
      "PolicyDenied(Allowed)"
    )
    refused(
      typeCheckErrors(
        """(a: storymodel4s.embed.PolicyDecision.LocalOnly) =>
             storymodel4s.embed.ExecutionFailure.PolicyDenied(a)"""
      ),
      "PolicyDenied(LocalOnly)"
    )
  }

  test("detector evidence cannot be minted or copied outside embed-core") {
    refused(
      typeCheckErrors(
        """(
             id: storymodel4s.embed.PseudonymizationDetectorId,
             digest: storymodel4s.embed.ReceiptDigest.Keyed
           ) => new storymodel4s.embed.PseudonymizationDetection(
             id,
             digest,
             digest,
             Vector.empty
           )"""
      ),
      "PseudonymizationDetection"
    )
    refused(
      typeCheckErrors(
        """(d: storymodel4s.embed.PseudonymizationDetection) => d.copy(spans = Vector.empty)"""
      ),
      "PseudonymizationDetection.copy"
    )
    refused(
      typeCheckErrors(
        """new storymodel4s.embed.PseudonymizationDetector(
             storymodel4s.embed.PseudonymizationDetectorId.unsafe("detector/v1"),
             "config/v1",
             _ => Vector.empty
           )"""
      ),
      "PseudonymizationDetector"
    )
    refused(
      typeCheckErrors(
        """(
             id: storymodel4s.embed.PseudonymizationDetectorId,
             digest: storymodel4s.embed.ReceiptDigest.Keyed
           ) => new storymodel4s.embed.DetectorPolicyIdentity(id, digest)"""
      ),
      "DetectorPolicyIdentity"
    )
    refused(
      typeCheckErrors(
        """(identity: storymodel4s.embed.DetectorPolicyIdentity) => identity.copy()"""
      ),
      "DetectorPolicyIdentity.copy"
    )
  }

  test("legacy and corruption seams are unavailable outside embed-core") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.PseudonymizedText.legacyV1ForTest _"""
      ),
      "PseudonymizedText.legacyV1ForTest"
    )
    refused(
      typeCheckErrors(
        """storymodel4s.embed.PseudonymizedText.substituteDetectionsForTest _"""
      ),
      "PseudonymizedText.substituteDetectionsForTest"
    )
  }

  test("remote capabilities and authorized requests cannot be forged outside embed-core") {
    refused(
      typeCheckErrors(
        """(
             provider: storymodel4s.embed.ProviderFingerprint,
             model: storymodel4s.embed.PolicyModelIdentity,
             policy: storymodel4s.embed.PrivacyPolicyId,
             detector: storymodel4s.embed.DetectorPolicyIdentity,
             digest: storymodel4s.embed.ReceiptDigest.Keyed
           ) => storymodel4s.embed.RemoteCapability(
             provider,
             model,
             "purpose",
             policy,
             1L,
             1L,
             detector,
             digest
           )"""
      ),
      "RemoteCapability"
    )
    refused(
      typeCheckErrors(
        """(
             id: storymodel4s.embed.RequestId,
             space: storymodel4s.embed.GeometryId,
             payload: storymodel4s.embed.PseudonymizedText,
             capability: storymodel4s.embed.RemoteCapability
           ) => storymodel4s.embed.AuthorizedRemoteRequest(id, space, payload, capability)"""
      ),
      "AuthorizedRemoteRequest"
    )
  }

  test("remote policy callers cannot assert provider or model identity separately") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.PolicyModelIdentity("policy-model/v1|1:a1:1")"""
      ),
      "PolicyModelIdentity.apply"
    )
    refused(
      typeCheckErrors(
        """(
             policy: storymodel4s.embed.RemotePolicy,
             request: storymodel4s.embed.EmbedRequest,
             provider: storymodel4s.embed.ProviderFingerprint
           ) => storymodel4s.embed.RemotePolicy.evaluate(
             policy,
             request,
             provider,
             "model",
             "purpose",
             0L,
             1L
           )"""
      ),
      "caller-asserted provider/model RemotePolicy.evaluate"
    )
    refused(
      typeCheckErrors(
        """(info: storymodel4s.embed.EmbedderInfo) =>
             storymodel4s.embed.PolicyModelIdentity.parse("policy-model/v1|1:a1:1", info)"""
      ),
      "PolicyModelIdentity.parse"
    )
  }

  test("positive control: a remaining case class still has fromProduct") {
    val errors = typeCheckErrors(
      """storymodel4s.embed.InstructionDigest.fromProduct(
           Tuple1(storymodel4s.core.Checksum.ofText("instr"))
         )"""
    )
    assert(
      errors.isEmpty,
      s"if InstructionDigest.fromProduct fails to typecheck, LatePoolingRecipe refusals are meaningless:\n${errors.mkString("\n")}"
    )
  }

  test("LatePoolingRecipe has no derived fromProduct bypass") {
    refused(
      typeCheckErrors("""storymodel4s.embed.LatePoolingRecipe.fromProduct(EmptyTuple)"""),
      "LatePoolingRecipe.fromProduct"
    )
  }

  test("LatePoolingRecipe has no copy door") {
    refused(
      typeCheckErrors("""(r: storymodel4s.embed.LatePoolingRecipe) => r.copy(window = 0)"""),
      "LatePoolingRecipe.copy"
    )
  }

  test("LatePoolingRecipe retains public read access") {
    assert(
      typeCheckErrors(
        """(r: storymodel4s.embed.LatePoolingRecipe) =>
             (r.documentDigest, r.window, r.stride, r.contextLimit)"""
      ).isEmpty
    )
  }

  test("LatePoolingRecipe.of remains the public path and still rejects window = 0") {
    assert(
      typeCheckErrors(
        """storymodel4s.embed.LatePoolingRecipe.of(
             storymodel4s.core.Checksum.ofText("doc"),
             storymodel4s.core.Fingerprint.unsafe("tok"),
             512, 128, 64, "mean",
             storymodel4s.embed.PoolingRule.Mean,
             storymodel4s.embed.UncoveredPolicy.PartialCoverage,
             None
           )"""
      ).isEmpty
    )
    assert(
      storymodel4s.embed.LatePoolingRecipe
        .of(
          storymodel4s.core.Checksum.ofText("doc"),
          storymodel4s.core.Fingerprint.unsafe("tok"),
          512,
          0,
          64,
          "mean",
          storymodel4s.embed.PoolingRule.Mean,
          storymodel4s.embed.UncoveredPolicy.PartialCoverage,
          None
        )
        .isLeft
    )
  }

  test("the public detector factory accepts table data but no executable finder") {
    refused(
      typeCheckErrors(
        """storymodel4s.embed.PseudonymizationDetector.checked(
             storymodel4s.embed.PseudonymizationDetectorId.unsafe("detector/v1"),
             "config/v1",
             _ => Vector.empty
           )"""
      ),
      "PseudonymizationDetector.checked(finder)"
    )
    assert(
      typeCheckErrors(
        """storymodel4s.embed.PseudonymizationDetector.wholeWordTable(
             Vector(
               storymodel4s.embed.PseudonymizationTableEntry("Jane", "[P1]")
             )
           )"""
      ).isEmpty
    )
  }
