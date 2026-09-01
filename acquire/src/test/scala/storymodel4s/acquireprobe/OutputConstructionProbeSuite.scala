package storymodel4s.consumerattack {

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

  final case class EstablishedRateProductControl(numerator: Int, denominator: Int)
  object EstablishedRateProductControl:
    def fromProduct(product: Product): EstablishedRateProductControl =
      summon[scala.deriving.Mirror.ProductOf[EstablishedRateProductControl]].fromProduct(product)

  final case class OutputFailureProductControl(
      code: storymodel4s.acquire.OutputFailureCode,
      receipt: storymodel4s.acquire.OutputReceiptId,
      stage: Option[storymodel4s.core.StageId],
      evidence: Vector[storymodel4s.acquire.OutputReceiptId],
      detail: Option[storymodel4s.acquire.OutputFailureDetail]
  )
  object OutputFailureProductControl:
    def fromProduct(product: Product): OutputFailureProductControl =
      summon[scala.deriving.Mirror.ProductOf[OutputFailureProductControl]].fromProduct(product)

  final case class StrictDecodeFailureProductControl(
      receipt: storymodel4s.acquire.OutputReceiptId,
      decoder: storymodel4s.acquire.DecoderId,
      charset: storymodel4s.acquire.CharsetId,
      policy: storymodel4s.acquire.DecodePolicyId,
      configChecksum: storymodel4s.core.Checksum,
      originalChecksum: storymodel4s.core.Checksum,
      bytePosition: Long,
      reason: storymodel4s.acquire.StrictDecodeFailureReason
  )
  object StrictDecodeFailureProductControl:
    def fromProduct(product: Product): StrictDecodeFailureProductControl =
      summon[scala.deriving.Mirror.ProductOf[StrictDecodeFailureProductControl]]
        .fromProduct(product)

  final case class DecodeReceiptProductControl(
      id: storymodel4s.acquire.OutputReceiptId,
      decoder: storymodel4s.acquire.DecoderId,
      charset: storymodel4s.acquire.CharsetId,
      policy: storymodel4s.acquire.DecodePolicyId,
      configChecksum: storymodel4s.core.Checksum,
      originalChecksum: storymodel4s.core.Checksum,
      decodedChecksum: storymodel4s.core.Checksum
  )
  object DecodeReceiptProductControl:
    def fromProduct(product: Product): DecodeReceiptProductControl =
      summon[scala.deriving.Mirror.ProductOf[DecodeReceiptProductControl]].fromProduct(product)

  final case class DecodedSourceIdentityProductControl(
      utf16Length: Int,
      checksum: storymodel4s.core.Checksum,
      decodeReceipt: storymodel4s.acquire.DecodeReceipt
  )
  object DecodedSourceIdentityProductControl:
    def fromProduct(product: Product): DecodedSourceIdentityProductControl =
      summon[scala.deriving.Mirror.ProductOf[DecodedSourceIdentityProductControl]]
        .fromProduct(product)

  final case class SourceAdmissionProductControl(
      source: Option[storymodel4s.core.StorySource],
      outcome: storymodel4s.acquire.SourceOutcome
  )
  object SourceAdmissionProductControl:
    def fromProduct(product: Product): SourceAdmissionProductControl =
      summon[scala.deriving.Mirror.ProductOf[SourceAdmissionProductControl]].fromProduct(product)

  /** External-package proof that output invariants have no generated construction door. */
  class OutputConstructionProbeSuite extends FunSuite:
    private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
      assert(errors.nonEmpty, s"$what unexpectedly typechecked")

    test("authority same-shape control exposes all four construction mechanisms") {
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.AuthorityProductControl(storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None)"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors("(x: storymodel4s.consumerattack.AuthorityProductControl) => x.copy()"),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.AuthorityProductControl.fromProduct((storymodel4s.acquire.AcquisitionViewAuthorityKind.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), Some(storymodel4s.core.Checksum.ofText(\"build\")), None, None, None))"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.consumerattack.AuthorityProductControl]]"
        ),
        Nil
      )
    }

    test("established-rate same-shape control exposes all four construction mechanisms") {
      assertEquals(
        typeCheckErrors("storymodel4s.consumerattack.EstablishedRateProductControl(1, 2)"),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "(x: storymodel4s.consumerattack.EstablishedRateProductControl) => x.copy(numerator = -1)"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.EstablishedRateProductControl.fromProduct((1, 2))"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.consumerattack.EstablishedRateProductControl]]"
        ),
        Nil
      )
    }

    test("new failure same-shape controls expose all four construction mechanisms") {
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.OutputFailureProductControl(storymodel4s.acquire.OutputFailureCode.DecodeFailed, storymodel4s.acquire.OutputReceiptId.unsafe(\"r\"), None, Vector.empty, None)"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors("(x: storymodel4s.consumerattack.OutputFailureProductControl) => x.copy()"),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.OutputFailureProductControl.fromProduct((storymodel4s.acquire.OutputFailureCode.DecodeFailed, storymodel4s.acquire.OutputReceiptId.unsafe(\"r\"), None, Vector.empty, None))"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.consumerattack.OutputFailureProductControl]]"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.StrictDecodeFailureProductControl(storymodel4s.acquire.OutputReceiptId.unsafe(\"r\"), storymodel4s.acquire.DecoderId.unsafe(\"d\"), storymodel4s.acquire.CharsetId.unsafe(\"UTF-8\"), storymodel4s.acquire.DecodePolicyId.unsafe(\"p\"), storymodel4s.core.Checksum.ofText(\"c\"), storymodel4s.core.Checksum.ofText(\"o\"), 0L, storymodel4s.acquire.StrictDecodeFailureReason.InvalidLeadingByte)"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "(x: storymodel4s.consumerattack.StrictDecodeFailureProductControl) => x.copy(bytePosition = -1L)"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.StrictDecodeFailureProductControl.fromProduct((storymodel4s.acquire.OutputReceiptId.unsafe(\"r\"), storymodel4s.acquire.DecoderId.unsafe(\"d\"), storymodel4s.acquire.CharsetId.unsafe(\"UTF-8\"), storymodel4s.acquire.DecodePolicyId.unsafe(\"p\"), storymodel4s.core.Checksum.ofText(\"c\"), storymodel4s.core.Checksum.ofText(\"o\"), 0L, storymodel4s.acquire.StrictDecodeFailureReason.InvalidLeadingByte))"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.consumerattack.StrictDecodeFailureProductControl]]"
        ),
        Nil
      )
    }

    test("decode success same-shape controls expose all four construction mechanisms") {
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.DecodeReceiptProductControl(???, ???, ???, ???, ???, ???, ???)"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors("(x: storymodel4s.consumerattack.DecodeReceiptProductControl) => x.copy()"),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.DecodeReceiptProductControl.fromProduct((???, ???, ???, ???, ???, ???, ???))"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.consumerattack.DecodeReceiptProductControl]]"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.DecodedSourceIdentityProductControl(0, ???, ???)"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "(x: storymodel4s.consumerattack.DecodedSourceIdentityProductControl) => x.copy(utf16Length = -1)"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.DecodedSourceIdentityProductControl.fromProduct((0, ???, ???))"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.consumerattack.DecodedSourceIdentityProductControl]]"
        ),
        Nil
      )
    }

    test("checked failures close all four product construction doors") {
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.OutputFailure(storymodel4s.acquire.OutputFailureCode.DecodeFailed, storymodel4s.acquire.OutputReceiptId.unsafe(\"r\"), None, Vector.empty, None)"
        ),
        "OutputFailure.apply"
      )
      refused(
        typeCheckErrors("(x: storymodel4s.acquire.OutputFailure) => x.copy()"),
        "OutputFailure.copy"
      )
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.OutputFailure.fromProduct((storymodel4s.acquire.OutputFailureCode.DecodeFailed, storymodel4s.acquire.OutputReceiptId.unsafe(\"r\"), None, Vector.empty, None))"
        ),
        "OutputFailure.fromProduct"
      )
      refused(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.OutputFailure]]"
        ),
        "OutputFailure Mirror"
      )
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.StrictDecodeFailure(storymodel4s.acquire.OutputReceiptId.unsafe(\"r\"), storymodel4s.acquire.DecoderId.unsafe(\"d\"), storymodel4s.acquire.CharsetId.unsafe(\"UTF-8\"), storymodel4s.acquire.DecodePolicyId.unsafe(\"p\"), storymodel4s.core.Checksum.ofText(\"c\"), storymodel4s.core.Checksum.ofText(\"o\"), 0L, storymodel4s.acquire.StrictDecodeFailureReason.InvalidLeadingByte)"
        ),
        "StrictDecodeFailure.apply"
      )
      refused(
        typeCheckErrors("(x: storymodel4s.acquire.StrictDecodeFailure) => x.copy()"),
        "StrictDecodeFailure.copy"
      )
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.StrictDecodeFailure.fromProduct((storymodel4s.acquire.OutputReceiptId.unsafe(\"r\"), storymodel4s.acquire.DecoderId.unsafe(\"d\"), storymodel4s.acquire.CharsetId.unsafe(\"UTF-8\"), storymodel4s.acquire.DecodePolicyId.unsafe(\"p\"), storymodel4s.core.Checksum.ofText(\"c\"), storymodel4s.core.Checksum.ofText(\"o\"), 0L, storymodel4s.acquire.StrictDecodeFailureReason.InvalidLeadingByte))"
        ),
        "StrictDecodeFailure.fromProduct"
      )
      refused(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.StrictDecodeFailure]]"
        ),
        "StrictDecodeFailure Mirror"
      )
    }

    test("source-admission same-shape control exposes all four construction mechanisms") {
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.SourceAdmissionProductControl(None, storymodel4s.acquire.SourceOutcome.Refused(storymodel4s.acquire.RefusedSourceProgress.BeforeIntake, ???))"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "(x: storymodel4s.consumerattack.SourceAdmissionProductControl) => x.copy(source = None)"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "storymodel4s.consumerattack.SourceAdmissionProductControl.fromProduct((None, storymodel4s.acquire.SourceOutcome.Refused(storymodel4s.acquire.RefusedSourceProgress.BeforeIntake, ???)))"
        ),
        Nil
      )
      assertEquals(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.consumerattack.SourceAdmissionProductControl]]"
        ),
        Nil
      )
    }

    test("checked source admission closes all four product construction doors") {
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.SourceAdmission(None, storymodel4s.acquire.SourceOutcome.Refused(storymodel4s.acquire.RefusedSourceProgress.BeforeIntake, ???))"
        ),
        "SourceAdmission.apply"
      )
      refused(
        typeCheckErrors("(x: storymodel4s.acquire.SourceAdmission) => x.copy(source = None)"),
        "SourceAdmission.copy"
      )
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.SourceAdmission.fromProduct((None, storymodel4s.acquire.SourceOutcome.Refused(storymodel4s.acquire.RefusedSourceProgress.BeforeIntake, ???)))"
        ),
        "SourceAdmission.fromProduct"
      )
      refused(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.SourceAdmission]]"
        ),
        "SourceAdmission Mirror"
      )
    }

    test("checked decode success types close all four product construction doors") {
      refused(
        typeCheckErrors("storymodel4s.acquire.DecodeReceipt(???, ???, ???, ???, ???, ???, ???)"),
        "DecodeReceipt.apply"
      )
      refused(
        typeCheckErrors("(x: storymodel4s.acquire.DecodeReceipt) => x.copy()"),
        "DecodeReceipt.copy"
      )
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.DecodeReceipt.fromProduct((???, ???, ???, ???, ???, ???, ???))"
        ),
        "DecodeReceipt.fromProduct"
      )
      refused(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.DecodeReceipt]]"
        ),
        "DecodeReceipt Mirror"
      )
      refused(
        typeCheckErrors("storymodel4s.acquire.DecodedSourceIdentity(0, ???, ???)"),
        "DecodedSourceIdentity.apply"
      )
      refused(
        typeCheckErrors("(x: storymodel4s.acquire.DecodedSourceIdentity) => x.copy()"),
        "DecodedSourceIdentity.copy"
      )
      refused(
        typeCheckErrors("storymodel4s.acquire.DecodedSourceIdentity.fromProduct((0, ???, ???))"),
        "DecodedSourceIdentity.fromProduct"
      )
      refused(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.DecodedSourceIdentity]]"
        ),
        "DecodedSourceIdentity Mirror"
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

    test("established rate closes all four product construction doors") {
      refused(
        typeCheckErrors("storymodel4s.acquire.EstablishedRate(1, 2)"),
        "EstablishedRate.apply"
      )
      refused(
        typeCheckErrors("(x: storymodel4s.acquire.EstablishedRate) => x.copy(numerator = -1)"),
        "EstablishedRate.copy"
      )
      refused(
        typeCheckErrors("storymodel4s.acquire.EstablishedRate.fromProduct((1, 2))"),
        "EstablishedRate.fromProduct"
      )
      refused(
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.EstablishedRate]]"
        ),
        "EstablishedRate Mirror"
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
}

package storymodel4s.acquire.attack {

  import scala.compiletime.testing.typeCheckErrors

  import munit.FunSuite

  /** Adversarial child-package proof that deferred evidence admission has no qualified-private
    * seam.
    */
  class AcquirePackageIssuerBypassRefusalSuite extends FunSuite:
    private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
      assert(errors.nonEmpty, s"$what unexpectedly typechecked")

    test("acquire child-package consumers cannot self-admit human or fixture authority") {
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.AdmittedViewEvidence.admit(???, ???, ???, ???, ???, ???, ???, ???, ???)"
        ),
        "AdmittedViewEvidence.admit"
      )
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.ViewEvidenceAdmitter.trusted(storymodel4s.core.Fingerprint.unsafe(\"caller:v1\"))"
        ),
        "ViewEvidenceAdmitter.trusted"
      )
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.AcquisitionViewAuthority.fixtureReview(???, ???, ???)"
        ),
        "AcquisitionViewAuthority.fixtureReview"
      )
    }

    test("acquire child-package consumers have no unchecked established-rate factory") {
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.EstablishedRate.make(-1, 0)"
        ),
        "EstablishedRate.make"
      )
      assert(storymodel4s.acquire.EstablishedRate.of(-1, 0).isLeft)
    }

    test("acquire child-package consumers cannot mint source admissions") {
      refused(
        typeCheckErrors(
          "new storymodel4s.acquire.SourceIdentities.Admission(None, storymodel4s.acquire.SourceOutcome.Refused(storymodel4s.acquire.RefusedSourceProgress.BeforeIntake, ???))"
        ),
        "SourceIdentities.Admission constructor"
      )
    }

    test("acquire child-package consumers cannot mint strict-decoding receipts") {
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.DecodeReceipt.successful(storymodel4s.core.Checksum.ofText(\"original\"), storymodel4s.core.Checksum.ofText(\"decoded\"))"
        ),
        "DecodeReceipt.successful"
      )
      refused(
        typeCheckErrors(
          "storymodel4s.acquire.StrictDecodeFailure.derived(storymodel4s.core.Checksum.ofText(\"original\"), 0, storymodel4s.acquire.StrictDecodeFailureReason.InvalidLeadingByte)"
        ),
        "StrictDecodeFailure.derived"
      )
      refused(
        typeCheckErrors(
          "new storymodel4s.acquire.SourceIdentities.DecodeReceiptValue(???, ???, ???, ???, ???, ???, ???)"
        ),
        "SourceIdentities.DecodeReceiptValue constructor"
      )
      refused(
        typeCheckErrors(
          "new storymodel4s.acquire.SourceIdentities.StrictDecodeFailureValue(???, ???, ???, ???, ???, ???, 0L, ???)"
        ),
        "SourceIdentities.StrictDecodeFailureValue constructor"
      )
      refused(
        typeCheckErrors(
          "new storymodel4s.acquire.SourceIdentities.DecodedSourceIdentityValue(0, ???, ???)"
        ),
        "SourceIdentities.DecodedSourceIdentityValue constructor"
      )
    }
}
