package outputprobe

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

final case class AdmitterProductControl(fingerprint: storymodel4s.core.Fingerprint)
object AdmitterProductControl:
  def fromProduct(product: Product): AdmitterProductControl =
    summon[scala.deriving.Mirror.ProductOf[AdmitterProductControl]].fromProduct(product)

final case class EvidenceProductControl(
    kind: storymodel4s.acquire.AcquisitionViewAuthorityKind,
    issuer: storymodel4s.core.Fingerprint,
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
  val issuer = storymodel4s.core.Fingerprint.unsafe("fixture-admitter:test:v1")
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
        "outputprobe.AuthorityProductControl(storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None, None)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("(x: outputprobe.AuthorityProductControl) => x.copy()"),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "outputprobe.AuthorityProductControl.fromProduct((storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None, None))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[outputprobe.AuthorityProductControl]]"
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
        "outputprobe.EvidenceProductControl(outputprobe.EvidenceProbeValues.kind, outputprobe.EvidenceProbeValues.issuer, outputprobe.EvidenceProbeValues.source, outputprobe.EvidenceProbeValues.build, outputprobe.EvidenceProbeValues.checksum, outputprobe.EvidenceProbeValues.reviewer, outputprobe.EvidenceProbeValues.evidence, outputprobe.EvidenceProbeValues.provenance, outputprobe.EvidenceProbeValues.adjudication, outputprobe.EvidenceProbeValues.fixture)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("(x: outputprobe.EvidenceProductControl) => x.copy()"),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "outputprobe.EvidenceProductControl.fromProduct((outputprobe.EvidenceProbeValues.kind, outputprobe.EvidenceProbeValues.issuer, outputprobe.EvidenceProbeValues.source, outputprobe.EvidenceProbeValues.build, outputprobe.EvidenceProbeValues.checksum, outputprobe.EvidenceProbeValues.reviewer, outputprobe.EvidenceProbeValues.evidence, outputprobe.EvidenceProbeValues.provenance, outputprobe.EvidenceProbeValues.adjudication, outputprobe.EvidenceProbeValues.fixture))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[outputprobe.EvidenceProductControl]]"
      ),
      Nil
    )
  }

  test("admitted evidence closes all four product construction doors") {
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AdmittedViewEvidence(outputprobe.EvidenceProbeValues.kind, outputprobe.EvidenceProbeValues.issuer, outputprobe.EvidenceProbeValues.source, outputprobe.EvidenceProbeValues.build, outputprobe.EvidenceProbeValues.checksum, outputprobe.EvidenceProbeValues.reviewer, outputprobe.EvidenceProbeValues.evidence, outputprobe.EvidenceProbeValues.provenance, outputprobe.EvidenceProbeValues.adjudication, outputprobe.EvidenceProbeValues.fixture)"
      ),
      "AdmittedViewEvidence.apply"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.acquire.AdmittedViewEvidence) => x.copy()"),
      "AdmittedViewEvidence.copy"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AdmittedViewEvidence.fromProduct((outputprobe.EvidenceProbeValues.kind, outputprobe.EvidenceProbeValues.issuer, outputprobe.EvidenceProbeValues.source, outputprobe.EvidenceProbeValues.build, outputprobe.EvidenceProbeValues.checksum, outputprobe.EvidenceProbeValues.reviewer, outputprobe.EvidenceProbeValues.evidence, outputprobe.EvidenceProbeValues.provenance, outputprobe.EvidenceProbeValues.adjudication, outputprobe.EvidenceProbeValues.fixture))"
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

  test("admission capability has same-shape controls and closes all construction doors") {
    assertEquals(
      typeCheckErrors(
        "outputprobe.AdmitterProductControl(storymodel4s.core.Fingerprint.unsafe(\"issuer:v1\"))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("(x: outputprobe.AdmitterProductControl) => x.copy()"),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "outputprobe.AdmitterProductControl.fromProduct(Tuple1(storymodel4s.core.Fingerprint.unsafe(\"issuer:v1\")))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[outputprobe.AdmitterProductControl]]"
      ),
      Nil
    )

    refused(
      typeCheckErrors(
        "storymodel4s.acquire.ViewEvidenceAdmitter(storymodel4s.core.Fingerprint.unsafe(\"issuer:v1\"))"
      ),
      "ViewEvidenceAdmitter.apply"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.acquire.ViewEvidenceAdmitter) => x.copy()"),
      "ViewEvidenceAdmitter.copy"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.ViewEvidenceAdmitter.fromProduct(Tuple1(storymodel4s.core.Fingerprint.unsafe(\"issuer:v1\")))"
      ),
      "ViewEvidenceAdmitter.fromProduct"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.ViewEvidenceAdmitter]]"
      ),
      "ViewEvidenceAdmitter Mirror"
    )
  }

  test("ordinary consumers cannot register an issuer or self-admit matching evidence") {
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.ViewEvidenceAdmitter.trusted(storymodel4s.core.Fingerprint.unsafe(\"caller:v1\"))"
      ),
      "ViewEvidenceAdmitter.trusted"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.acquire.AdmittedViewEvidence.admit(???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "AdmittedViewEvidence.admit"
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
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.ViewEvidenceAdmitter]]"
      ),
      "ViewEvidenceAdmitter Mirror"
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
    refused(
      typeCheckErrors("(x: storymodel4s.acquire.ViewEvidenceAdmitter) => x.copy()"),
      "ViewEvidenceAdmitter.copy"
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
