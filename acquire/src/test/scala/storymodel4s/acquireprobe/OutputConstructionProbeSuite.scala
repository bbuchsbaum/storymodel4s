package storymodel4s.acquireprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

final case class AuthorityProductControl(
    kind: storymodel4s.acquire.AcquisitionViewAuthorityKind,
    sourceChecksum: storymodel4s.core.Checksum,
    buildReceiptChecksum: Option[storymodel4s.core.Checksum],
    evidenceChecksum: Option[storymodel4s.core.Checksum],
    adjudicationReceipt: Option[storymodel4s.acquire.AdjudicationReceiptId],
    fixtureReceipt: Option[storymodel4s.acquire.FixtureAdmissionReceiptId],
    admittedEvidence: Option[storymodel4s.acquire.AdmittedViewEvidence]
)
object AuthorityProductControl:
  def fromProduct(product: Product): AuthorityProductControl =
    summon[scala.deriving.Mirror.ProductOf[AuthorityProductControl]].fromProduct(product)

final case class EvidenceProductControl(
    kind: storymodel4s.acquire.AcquisitionViewAuthorityKind,
    sourceChecksum: storymodel4s.core.Checksum,
    buildReceiptChecksum: Option[storymodel4s.core.Checksum],
    evidenceChecksum: storymodel4s.core.Checksum,
    reviewer: storymodel4s.core.Fingerprint,
    evidence: cats.data.NonEmptyVector[storymodel4s.core.Evidence],
    provenance: storymodel4s.core.Provenance,
    adjudicationReceipt: Option[storymodel4s.acquire.AdjudicationReceiptId],
    fixtureReceipt: Option[storymodel4s.acquire.FixtureAdmissionReceiptId]
)
object EvidenceProductControl:
  def fromProduct(product: Product): EvidenceProductControl =
    summon[scala.deriving.Mirror.ProductOf[EvidenceProductControl]].fromProduct(product)

object EvidenceProbeValues:
  val kind = storymodel4s.acquire.AcquisitionViewAuthorityKind.FixtureReview
  val source = storymodel4s.core.Checksum.ofText("source")
  val build = Option.empty[storymodel4s.core.Checksum]
  val checksum = storymodel4s.core.Checksum.ofText("evidence")
  val reviewer = storymodel4s.core.Fingerprint.unsafe("reviewer:test:v1")
  val evidence = cats.data.NonEmptyVector.one(
    storymodel4s.core.Evidence(
      storymodel4s.core.EvidenceId.unsafe("evidence"),
      Some(storymodel4s.core.SpanSet.one(storymodel4s.core.TextSpan.unsafe(0, 1))),
      Set.empty,
      reviewer,
      storymodel4s.core.StageId.unsafe("review")
    )
  )
  val provenance = storymodel4s.core.Provenance.human("reviewer", "review/v1")
  val adjudication = Option.empty[storymodel4s.acquire.AdjudicationReceiptId]
  val fixture = Some(storymodel4s.acquire.FixtureAdmissionReceiptId.unsafe("fixture-receipt"))

/** External-package proof that output invariants have no generated construction door. */
class OutputConstructionProbeSuite extends FunSuite:
  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what unexpectedly typechecked")

  test("authority same-shape control exposes all four construction mechanisms") {
    assertEquals(
      typeCheckErrors(
        "storymodel4s.acquireprobe.AuthorityProductControl(storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None, None)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("(x: storymodel4s.acquireprobe.AuthorityProductControl) => x.copy()"),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "storymodel4s.acquireprobe.AuthorityProductControl.fromProduct((storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None, None))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquireprobe.AuthorityProductControl]]"
      ),
      Nil
    )
  }

  test("evidence-issued authority closes all four product construction doors") {
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AcquisitionViewAuthority(storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None, None)"
      ),
      "AcquisitionViewAuthority.apply"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.acquire.AcquisitionViewAuthority) => x.copy()"),
      "AcquisitionViewAuthority.copy"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AcquisitionViewAuthority.fromProduct((storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None, None))"
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

  test("admitted evidence same-shape control exposes all four construction mechanisms") {
    assertEquals(
      typeCheckErrors(
        "storymodel4s.acquireprobe.EvidenceProductControl(storymodel4s.acquireprobe.EvidenceProbeValues.kind, storymodel4s.acquireprobe.EvidenceProbeValues.source, storymodel4s.acquireprobe.EvidenceProbeValues.build, storymodel4s.acquireprobe.EvidenceProbeValues.checksum, storymodel4s.acquireprobe.EvidenceProbeValues.reviewer, storymodel4s.acquireprobe.EvidenceProbeValues.evidence, storymodel4s.acquireprobe.EvidenceProbeValues.provenance, storymodel4s.acquireprobe.EvidenceProbeValues.adjudication, storymodel4s.acquireprobe.EvidenceProbeValues.fixture)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("(x: storymodel4s.acquireprobe.EvidenceProductControl) => x.copy()"),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "storymodel4s.acquireprobe.EvidenceProductControl.fromProduct((storymodel4s.acquireprobe.EvidenceProbeValues.kind, storymodel4s.acquireprobe.EvidenceProbeValues.source, storymodel4s.acquireprobe.EvidenceProbeValues.build, storymodel4s.acquireprobe.EvidenceProbeValues.checksum, storymodel4s.acquireprobe.EvidenceProbeValues.reviewer, storymodel4s.acquireprobe.EvidenceProbeValues.evidence, storymodel4s.acquireprobe.EvidenceProbeValues.provenance, storymodel4s.acquireprobe.EvidenceProbeValues.adjudication, storymodel4s.acquireprobe.EvidenceProbeValues.fixture))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquireprobe.EvidenceProductControl]]"
      ),
      Nil
    )
  }

  test("admitted evidence closes all four product construction doors") {
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AdmittedViewEvidence(storymodel4s.acquireprobe.EvidenceProbeValues.kind, storymodel4s.acquireprobe.EvidenceProbeValues.source, storymodel4s.acquireprobe.EvidenceProbeValues.build, storymodel4s.acquireprobe.EvidenceProbeValues.checksum, storymodel4s.acquireprobe.EvidenceProbeValues.reviewer, storymodel4s.acquireprobe.EvidenceProbeValues.evidence, storymodel4s.acquireprobe.EvidenceProbeValues.provenance, storymodel4s.acquireprobe.EvidenceProbeValues.adjudication, storymodel4s.acquireprobe.EvidenceProbeValues.fixture)"
      ),
      "AdmittedViewEvidence.apply"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.acquire.AdmittedViewEvidence) => x.copy()"),
      "AdmittedViewEvidence.copy"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AdmittedViewEvidence.fromProduct((storymodel4s.acquireprobe.EvidenceProbeValues.kind, storymodel4s.acquireprobe.EvidenceProbeValues.source, storymodel4s.acquireprobe.EvidenceProbeValues.build, storymodel4s.acquireprobe.EvidenceProbeValues.checksum, storymodel4s.acquireprobe.EvidenceProbeValues.reviewer, storymodel4s.acquireprobe.EvidenceProbeValues.evidence, storymodel4s.acquireprobe.EvidenceProbeValues.provenance, storymodel4s.acquireprobe.EvidenceProbeValues.adjudication, storymodel4s.acquireprobe.EvidenceProbeValues.fixture))"
      ),
      "AdmittedViewEvidence.fromProduct"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.AdmittedViewEvidence]]"
      ),
      "AdmittedViewEvidence Mirror"
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
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.AdmittedViewEvidence]]"
      ),
      "AdmittedViewEvidence Mirror"
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
    refused(
      typeCheckErrors("(x: storymodel4s.acquire.AdmittedViewEvidence) => x.copy()"),
      "AdmittedViewEvidence.copy"
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
