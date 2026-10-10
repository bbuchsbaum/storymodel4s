package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Outside-package courts keep disclosure inputs and their bases unforgeable. */
class RecallDisclosureBoundarySuite extends FunSuite:

  test("Input constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(text: String, basis: Basis, scope: Scope); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control("x", Basis.LocalOnly, Scope.LocalWorkspace)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; new Input("x", Basis.LocalOnly, Scope.LocalWorkspace)"""
      ).nonEmpty
    )
  }

  test("Input apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(text: String, basis: Basis, scope: Scope); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control("x", Basis.LocalOnly, Scope.LocalWorkspace)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; Input("x", Basis.LocalOnly, Scope.LocalWorkspace)"""
      ).nonEmpty
    )
  }

  test("Input copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(text: String, basis: Basis, scope: Scope); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; (??? : Input).copy()"""
      ).nonEmpty
    )
  }

  test("Input fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(text: String, basis: Basis, scope: Scope); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple3("x", Basis.LocalOnly, Scope.LocalWorkspace))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; Input.fromProduct(Tuple3("x", Basis.LocalOnly, Scope.LocalWorkspace))"""
      ).nonEmpty
    )
  }

  test("Input mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(text: String, basis: Basis, scope: Scope); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; summon[scala.deriving.Mirror.ProductOf[Input]]"""
      ).nonEmpty
    )
  }

  test("PublicRecord constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(recordDigest: Checksum, upstreamRelease: String, sourceId: String, transformVersion: String, textDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : Checksum, "release", "id", "v1", ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; new PublicRecord(??? : Checksum, "release", "id", "v1", ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("PublicRecord apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(recordDigest: Checksum, upstreamRelease: String, sourceId: String, transformVersion: String, textDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : Checksum, "release", "id", "v1", ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; PublicRecord(??? : Checksum, "release", "id", "v1", ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("PublicRecord copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(recordDigest: Checksum, upstreamRelease: String, sourceId: String, transformVersion: String, textDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; (??? : PublicRecord).copy()"""
      ).nonEmpty
    )
  }

  test("PublicRecord fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(recordDigest: Checksum, upstreamRelease: String, sourceId: String, transformVersion: String, textDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple5(??? : Checksum, "release", "id", "v1", ??? : Checksum))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; PublicRecord.fromProduct(Tuple5(??? : Checksum, "release", "id", "v1", ??? : Checksum))"""
      ).nonEmpty
    )
  }

  test("PublicRecord mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(recordDigest: Checksum, upstreamRelease: String, sourceId: String, transformVersion: String, textDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; summon[scala.deriving.Mirror.ProductOf[PublicRecord]]"""
      ).nonEmpty
    )
  }

  test("Basis.PublicCorpusAdmitted constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(record: PublicRecord); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : PublicRecord)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; new Basis.PublicCorpusAdmitted(??? : PublicRecord)"""
      ).nonEmpty
    )
  }

  test("Basis.PublicCorpusAdmitted apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(record: PublicRecord); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : PublicRecord)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; Basis.PublicCorpusAdmitted(??? : PublicRecord)"""
      ).nonEmpty
    )
  }

  test("Basis.PublicCorpusAdmitted copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(record: PublicRecord); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; (??? : Basis.PublicCorpusAdmitted).copy()"""
      ).nonEmpty
    )
  }

  test("Basis.PublicCorpusAdmitted fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(record: PublicRecord); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple1(??? : PublicRecord))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; Basis.PublicCorpusAdmitted.fromProduct(Tuple1(??? : PublicRecord))"""
      ).nonEmpty
    )
  }

  test("Basis.PublicCorpusAdmitted mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(record: PublicRecord); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; summon[scala.deriving.Mirror.ProductOf[Basis.PublicCorpusAdmitted]]"""
      ).nonEmpty
    )
  }

  test("Basis.CertifiedPseudonymized constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(payload: PseudonymizedText); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : PseudonymizedText)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; new Basis.CertifiedPseudonymized(??? : PseudonymizedText)"""
      ).nonEmpty
    )
  }

  test("Basis.CertifiedPseudonymized apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(payload: PseudonymizedText); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : PseudonymizedText)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; Basis.CertifiedPseudonymized(??? : PseudonymizedText)"""
      ).nonEmpty
    )
  }

  test("Basis.CertifiedPseudonymized copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(payload: PseudonymizedText); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; (??? : Basis.CertifiedPseudonymized).copy()"""
      ).nonEmpty
    )
  }

  test("Basis.CertifiedPseudonymized fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(payload: PseudonymizedText); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple1(??? : PseudonymizedText))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; Basis.CertifiedPseudonymized.fromProduct(Tuple1(??? : PseudonymizedText))"""
      ).nonEmpty
    )
  }

  test("Basis.CertifiedPseudonymized mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; case class Control(payload: PseudonymizedText); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure.*; import storymodel4s.core.Checksum; import storymodel4s.embed.PseudonymizedText; summon[scala.deriving.Mirror.ProductOf[Basis.CertifiedPseudonymized]]"""
      ).nonEmpty
    )
  }
