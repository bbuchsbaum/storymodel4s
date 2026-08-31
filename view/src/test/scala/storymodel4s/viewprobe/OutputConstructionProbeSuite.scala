package storymodel4s.viewprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

final case class BasisProductControl(
    basis: storymodel4s.view.ViewBasis,
    sourceChecksum: storymodel4s.core.Checksum,
    buildReceiptChecksum: Option[storymodel4s.core.Checksum],
    authority: storymodel4s.view.BasisAuthority
)
object BasisProductControl:
  def fromProduct(product: Product): BasisProductControl =
    summon[scala.deriving.Mirror.ProductOf[BasisProductControl]].fromProduct(product)

final case class ProfileReceiptProductControl(receipt: storymodel4s.view.ProfileReceipt)
object ProfileReceiptProductControl:
  def fromProduct(product: Product): ProfileReceiptProductControl =
    summon[scala.deriving.Mirror.ProductOf[ProfileReceiptProductControl]].fromProduct(product)

final case class ReportInputProductControl(checksum: storymodel4s.core.Checksum)
object ReportInputProductControl:
  def fromProduct(product: Product): ReportInputProductControl =
    summon[scala.deriving.Mirror.ProductOf[ReportInputProductControl]].fromProduct(product)

final case class ReportReceiptProductControl(
    id: storymodel4s.acquire.OutputReceiptId,
    renderer: storymodel4s.view.RendererId,
    software: storymodel4s.view.OutputSoftwareId,
    input: storymodel4s.view.ReportInputIdentity,
    configChecksum: storymodel4s.core.Checksum
)
object ReportReceiptProductControl:
  def fromProduct(product: Product): ReportReceiptProductControl =
    summon[scala.deriving.Mirror.ProductOf[ReportReceiptProductControl]].fromProduct(product)

final class MissingOutputTargetIdentity

/** External-package proof that output/view roots have no generated construction door. */
class OutputConstructionProbeSuite extends FunSuite:
  private val boundaryTypes = Vector(
    classOf[storymodel4s.view.AdmittedViewBasis],
    classOf[storymodel4s.view.VerifiedProfileReceipt],
    classOf[storymodel4s.view.ReportInputIdentity],
    classOf[storymodel4s.view.ReportReceipt]
  )

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what unexpectedly typechecked")

  test("same-shape product controls keep all four construction mechanisms observable") {
    assertEquals(boundaryTypes.map(_.getSimpleName).size, 4)
    assertEquals(
      typeCheckErrors(
        "storymodel4s.viewprobe.BasisProductControl(storymodel4s.view.ViewBasis.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), None, storymodel4s.view.BasisAuthority.ValidatedBuild(storymodel4s.core.Checksum.ofText(\"build\")))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("(x: storymodel4s.viewprobe.BasisProductControl) => x.copy()"),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "storymodel4s.viewprobe.BasisProductControl.fromProduct((storymodel4s.view.ViewBasis.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), None, storymodel4s.view.BasisAuthority.ValidatedBuild(storymodel4s.core.Checksum.ofText(\"build\"))))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.viewprobe.BasisProductControl]]"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(receipt: storymodel4s.view.ProfileReceipt) => storymodel4s.viewprobe.ProfileReceiptProductControl(receipt)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("(x: storymodel4s.viewprobe.ProfileReceiptProductControl) => x.copy()"),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(receipt: storymodel4s.view.ProfileReceipt) => storymodel4s.viewprobe.ProfileReceiptProductControl.fromProduct(Tuple1(receipt))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.viewprobe.ProfileReceiptProductControl]]"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "storymodel4s.viewprobe.ReportInputProductControl(storymodel4s.core.Checksum.ofText(\"x\"))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(x: storymodel4s.viewprobe.ReportInputProductControl) => x.copy(checksum = storymodel4s.core.Checksum.ofText(\"y\"))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "storymodel4s.viewprobe.ReportInputProductControl.fromProduct(Tuple1(storymodel4s.core.Checksum.ofText(\"x\")))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.viewprobe.ReportInputProductControl]]"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(input: storymodel4s.view.ReportInputIdentity) => storymodel4s.viewprobe.ReportReceiptProductControl(storymodel4s.acquire.OutputReceiptId.unsafe(\"receipt\"), storymodel4s.view.RendererId.unsafe(\"renderer\"), storymodel4s.view.OutputSoftwareId.unsafe(\"software\"), input, storymodel4s.core.Checksum.ofText(\"config\"))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("(x: storymodel4s.viewprobe.ReportReceiptProductControl) => x.copy()"),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(input: storymodel4s.view.ReportInputIdentity) => storymodel4s.viewprobe.ReportReceiptProductControl.fromProduct((storymodel4s.acquire.OutputReceiptId.unsafe(\"receipt\"), storymodel4s.view.RendererId.unsafe(\"renderer\"), storymodel4s.view.OutputSoftwareId.unsafe(\"software\"), input, storymodel4s.core.Checksum.ofText(\"config\")))"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.viewprobe.ReportReceiptProductControl]]"
      ),
      Nil
    )
  }

  test("joined-authority roots expose no apply door") {
    refused(
      typeCheckErrors(
        "storymodel4s.view.AdmittedViewBasis(storymodel4s.view.ViewBasis.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), None, storymodel4s.view.BasisAuthority.ValidatedBuild(storymodel4s.core.Checksum.ofText(\"build\")))"
      ),
      "AdmittedViewBasis.apply"
    )
    refused(
      typeCheckErrors(
        "(receipt: storymodel4s.view.ProfileReceipt) => storymodel4s.view.VerifiedProfileReceipt(receipt)"
      ),
      "VerifiedProfileReceipt.apply"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.view.ReportInputIdentity(storymodel4s.core.Checksum.ofText(\"input\"))"
      ),
      "ReportInputIdentity.apply"
    )
    refused(
      typeCheckErrors(
        "(input: storymodel4s.view.ReportInputIdentity) => storymodel4s.view.ReportReceipt(storymodel4s.acquire.OutputReceiptId.unsafe(\"receipt\"), storymodel4s.view.RendererId.unsafe(\"renderer\"), storymodel4s.view.OutputSoftwareId.unsafe(\"software\"), input, storymodel4s.core.Checksum.ofText(\"config\"))"
      ),
      "ReportReceipt.apply"
    )
  }

  test("target identity has an exact String instance and no generic fallback") {
    assertEquals(
      typeCheckErrors(
        "summon[storymodel4s.view.OutputTargetIdentity[String]]"
      ),
      Nil
    )
    refused(
      typeCheckErrors(
        "summon[storymodel4s.view.OutputTargetIdentity[storymodel4s.viewprobe.MissingOutputTargetIdentity]]"
      ),
      "implicit target identity fallback"
    )
  }

  test("validating output/view roots expose no Mirror.ProductOf") {
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.BundlePath]]"),
      "BundlePath Mirror"
    )
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.ArtifactRef]]"),
      "ArtifactRef Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.AdmittedViewBasis]]"
      ),
      "AdmittedViewBasis Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.VerifiedProfileReceipt]]"
      ),
      "VerifiedProfileReceipt Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.ReportInputIdentity]]"
      ),
      "ReportInputIdentity Mirror"
    )
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.ReportReceipt]]"),
      "ReportReceipt Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.StoryOutputResult[String]]]"
      ),
      "StoryOutputResult Mirror"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.BundleManifest[String]]]"
      ),
      "BundleManifest Mirror"
    )
  }

  test("validating output/view roots expose no copy door") {
    refused(
      typeCheckErrors("(x: storymodel4s.view.AdmittedViewBasis) => x.copy()"),
      "AdmittedViewBasis.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.view.VerifiedProfileReceipt) => x.copy()"),
      "VerifiedProfileReceipt.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.view.ReportInputIdentity) => x.copy()"),
      "ReportInputIdentity.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.view.ReportReceipt) => x.copy()"),
      "ReportReceipt.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.view.ArtifactRef) => x.copy(byteLength = -1L)"),
      "ArtifactRef.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.view.StoryOutputResult[String]) => x.copy()"),
      "StoryOutputResult.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.view.BundleManifest[String]) => x.copy()"),
      "BundleManifest.copy"
    )
  }

  test("joined-authority roots expose no companion fromProduct door") {
    refused(
      typeCheckErrors(
        "storymodel4s.view.AdmittedViewBasis.fromProduct((storymodel4s.view.ViewBasis.ValidatedBuild, storymodel4s.core.Checksum.ofText(\"source\"), None, storymodel4s.view.BasisAuthority.ValidatedBuild(storymodel4s.core.Checksum.ofText(\"build\"))))"
      ),
      "AdmittedViewBasis.fromProduct"
    )
    refused(
      typeCheckErrors(
        "(receipt: storymodel4s.view.ProfileReceipt) => storymodel4s.view.VerifiedProfileReceipt.fromProduct(Tuple1(receipt))"
      ),
      "VerifiedProfileReceipt.fromProduct"
    )
    refused(
      typeCheckErrors(
        "storymodel4s.view.ReportInputIdentity.fromProduct(Tuple1(storymodel4s.core.Checksum.ofText(\"input\")))"
      ),
      "ReportInputIdentity.fromProduct"
    )
    refused(
      typeCheckErrors(
        "(input: storymodel4s.view.ReportInputIdentity) => storymodel4s.view.ReportReceipt.fromProduct((storymodel4s.acquire.OutputReceiptId.unsafe(\"receipt\"), storymodel4s.view.RendererId.unsafe(\"renderer\"), storymodel4s.view.OutputSoftwareId.unsafe(\"software\"), input, storymodel4s.core.Checksum.ofText(\"config\")))"
      ),
      "ReportReceipt.fromProduct"
    )
  }

  test("output/view observations remain public") {
    assertEquals(
      typeCheckErrors(
        "(x: storymodel4s.view.AdmittedViewBasis) => (x.basis, x.sourceChecksum, x.buildReceiptChecksum, x.authority)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(x: storymodel4s.view.VerifiedProfileReceipt) => x.receipt"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(x: storymodel4s.view.ReportReceipt) => (x.id, x.renderer, x.software, x.inputChecksum, x.configChecksum)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(x: storymodel4s.view.StoryOutputResult[String]) => (x.acquisition, x.basis, x.reportRequests, x.reportOutcomes)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "(x: storymodel4s.view.BundleManifest[String]) => (x.profileOutcomes, x.entries)"
      ),
      Nil
    )
  }
