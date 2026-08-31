package storymodel4s.consumerprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

final case class AuthorityProductControl(
    kind: storymodel4s.acquire.AcquisitionViewAuthorityKind,
    sourceChecksum: storymodel4s.core.Checksum,
    buildReceiptChecksum: Option[storymodel4s.core.Checksum],
    evidenceChecksum: Option[storymodel4s.core.Checksum],
    adjudicationReceipt: Option[storymodel4s.acquire.AdjudicationReceiptId],
    fixtureReceipt: Option[storymodel4s.acquire.FixtureAdmissionReceiptId]
)
object AuthorityProductControl:
  def fromProduct(product: Product): AuthorityProductControl =
    summon[scala.deriving.Mirror.ProductOf[AuthorityProductControl]].fromProduct(product)

/** External-package proof that output invariants have no generated construction door. */
class OutputConstructionProbeSuite extends FunSuite:
  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what unexpectedly typechecked")

  test("authority same-shape control exposes all four construction mechanisms") {
    assertEquals(
      typeCheckErrors(
        "storymodel4s.consumerprobe.AuthorityProductControl(storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("(x: storymodel4s.consumerprobe.AuthorityProductControl) => x.copy()"),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "storymodel4s.consumerprobe.AuthorityProductControl.fromProduct((storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.consumerprobe.AuthorityProductControl]]"
      ),
      Nil
    )
  }

  test("evidence-issued authority closes all four product construction doors") {
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AcquisitionViewAuthority(storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None)"
      ),
      "AcquisitionViewAuthority.apply"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.acquire.AcquisitionViewAuthority) => x.copy()"),
      "AcquisitionViewAuthority.copy"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AcquisitionViewAuthority.fromProduct((storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None))"
      ),
      "AcquisitionViewAuthority.fromProduct"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.AcquisitionViewAuthority]]"
      ),
      "AcquisitionViewAuthority Mirror"
    )
  }

  test("root-package consumers cannot obtain deferred human or fixture issuance") {
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.ViewEvidenceAdmitter.trusted(storymodel4s.core.Fingerprint.unsafe(\"caller:v1\"))"
      ),
      "ViewEvidenceAdmitter.trusted"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AcquisitionViewAuthority.humanAdjudication(???, ???, ???)"
      ),
      "AcquisitionViewAuthority.humanAdjudication"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AcquisitionViewAuthority.fixtureReview(???, ???, ???)"
      ),
      "AcquisitionViewAuthority.fixtureReview"
    )
  }

  test("output validating classes expose no Mirror.ProductOf") {
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.OriginalSourceIdentity]]"
      ),
      "OriginalSourceIdentity Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.DecodedSourceIdentity]]"
      ),
      "DecodedSourceIdentity Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.DecodeReceipt]]"
      ),
      "DecodeReceipt Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.SourceIdentities]]"
      ),
      "SourceIdentities Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.EstablishedUniverse[String]]]"
      ),
      "EstablishedUniverse Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.AcquisitionAccount[String]]]"
      ),
      "AcquisitionAccount Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.AcquisitionViewAuthority]]"
      ),
      "AcquisitionViewAuthority Mirror"
    )
  }

  test("output validating classes expose no copy door") {
    refused(
      typeCheckErrors(
        "(x: storymodel4s.acquire.DecodedSourceIdentity) => x.copy(utf16Length = -1)"
      ),
      "DecodedSourceIdentity.copy"
    )
    refused(
      typeCheckErrors(
        "(x: storymodel4s.acquire.DecodeReceipt) => x.copy(decoder = storymodel4s.acquire.DecoderId.unsafe(\"different/v1\"))"
      ),
      "DecodeReceipt.copy"
    )
    refused(
      typeCheckErrors(
        "(x: storymodel4s.acquire.SourceIdentities) => x.copy(canonicalUtf16Length = -1)"
      ),
      "SourceIdentities.copy"
    )
    refused(
      typeCheckErrors(
        "(x: storymodel4s.acquire.EstablishedUniverse[String]) => x.copy(members = Vector(\"x\", \"x\"))"
      ),
      "EstablishedUniverse.copy"
    )
    refused(
      typeCheckErrors(
        "(x: storymodel4s.acquire.AcquisitionAccount[String]) => x.copy(targets = Vector.empty)"
      ),
      "AcquisitionAccount.copy"
    )
  }

  test("unestablished union has no denominator helper") {
    refused(
      typeCheckErrors(
        "(x: storymodel4s.acquire.TargetUniverse[String]) => x.rate(0)"
      ),
      "TargetUniverse.rate"
    )
  }

  test("output observations remain public") {
    assertEquals(
      typeCheckErrors(
        "(x: storymodel4s.acquire.SourceIdentities) => (x.canonicalByteLength, x.canonicalUtf16Length, x.canonicalChecksum)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(x: storymodel4s.acquire.AcquisitionAccount[String]) => (x.source, x.universe, x.semantic, x.targets)"
      ),
      Nil
    )
  }
