package storymodel4s.acquireprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that output invariants have no generated construction door. */
class OutputConstructionProbeSuite extends FunSuite:
  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what unexpectedly typechecked")

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
