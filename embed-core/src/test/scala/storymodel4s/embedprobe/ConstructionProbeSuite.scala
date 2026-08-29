package storymodel4s.embedprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Probes from OUTSIDE `storymodel4s.embed`: receipt identities can only be minted through the
  * checked factories. A public constructor on any of these would let a caller forge a Plain
  * identity for sensitive material (P0-2 HIGH-1).
  */
class ConstructionProbeSuite extends FunSuite:

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
        """(capability: storymodel4s.embed.RemoteCapability) =>
             capability.copy(model = capability.model)"""
      ),
      "RemoteCapability.copy"
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
    refused(
      typeCheckErrors(
        """(request: storymodel4s.embed.AuthorizedRemoteRequest) => request.copy()"""
      ),
      "AuthorizedRemoteRequest.copy"
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
