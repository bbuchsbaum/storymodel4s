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
