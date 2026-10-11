package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Outside-package probes keep checked header/body bindings unforgeable. */
class AdmittedStoryBoundarySuite extends FunSuite:

  test("AdmissionRecord constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(fields: Map[String, String], documentChecksum: Checksum, textChecksum: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(Map.empty[String, String], Checksum.ofText("x"), Checksum.ofText("x"))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; new AdmissionRecord(Map.empty[String, String], Checksum.ofText("x"), Checksum.ofText("x"))"""
      ).nonEmpty
    )
  }

  test("AdmissionRecord apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(fields: Map[String, String], documentChecksum: Checksum, textChecksum: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(Map.empty[String, String], Checksum.ofText("x"), Checksum.ofText("x"))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; AdmissionRecord(Map.empty[String, String], Checksum.ofText("x"), Checksum.ofText("x"))"""
      ).nonEmpty
    )
  }

  test("AdmissionRecord copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(fields: Map[String, String], documentChecksum: Checksum, textChecksum: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; (??? : AdmissionRecord).copy()"""
      ).nonEmpty
    )
  }

  test("AdmissionRecord fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(fields: Map[String, String], documentChecksum: Checksum, textChecksum: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; AdmissionRecord.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }

  test("AdmissionRecord mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(fields: Map[String, String], documentChecksum: Checksum, textChecksum: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[AdmissionRecord]]"""
      ).nonEmpty
    )
  }

  test("Loaded constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(record: AdmissionRecord, sourcePackage: TextSourcePackage); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : AdmissionRecord, ??? : TextSourcePackage)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; new Loaded(??? : AdmissionRecord, ??? : TextSourcePackage)"""
      ).nonEmpty
    )
  }

  test("Loaded apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(record: AdmissionRecord, sourcePackage: TextSourcePackage); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : AdmissionRecord, ??? : TextSourcePackage)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; Loaded(??? : AdmissionRecord, ??? : TextSourcePackage)"""
      ).nonEmpty
    )
  }

  test("Loaded copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(record: AdmissionRecord, sourcePackage: TextSourcePackage); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; (??? : Loaded).copy()"""
      ).nonEmpty
    )
  }

  test("Loaded fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(record: AdmissionRecord, sourcePackage: TextSourcePackage); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; Loaded.fromProduct(??? : Product)"""
      ).nonEmpty
    )
  }

  test("Loaded mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; case class Control(record: AdmissionRecord, sourcePackage: TextSourcePackage); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.AdmittedStoryInput.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[Loaded]]"""
      ).nonEmpty
    )
  }
