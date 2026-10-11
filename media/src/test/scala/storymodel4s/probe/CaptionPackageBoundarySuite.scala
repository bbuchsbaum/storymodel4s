package storymodel4s.probe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Outside-package same-shape controls protect all checked package construction doors. */
class CaptionPackageBoundarySuite extends FunSuite:

  test("CaptionPackage constructor refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; new CaptionPackage((??? : EditionId), (??? : Checksum), (??? : Vector[CaptionPackage.Part]), (??? : Vector[(String, Vector[(Int, CaptionProposal)])]), (??? : Checksum))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(edition: EditionId, manifestChecksum: Checksum, parts: Vector[CaptionPackage.Part], extents: Vector[(String, Vector[(Int, CaptionProposal)])], identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control((??? : EditionId), (??? : Checksum), (??? : Vector[CaptionPackage.Part]), (??? : Vector[(String, Vector[(Int, CaptionProposal)])]), (??? : Checksum))"""
      ),
      Nil
    )

  test("CaptionPackage apply refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; CaptionPackage((??? : EditionId), (??? : Checksum), (??? : Vector[CaptionPackage.Part]), (??? : Vector[(String, Vector[(Int, CaptionProposal)])]), (??? : Checksum))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(edition: EditionId, manifestChecksum: Checksum, parts: Vector[CaptionPackage.Part], extents: Vector[(String, Vector[(Int, CaptionProposal)])], identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control((??? : EditionId), (??? : Checksum), (??? : Vector[CaptionPackage.Part]), (??? : Vector[(String, Vector[(Int, CaptionProposal)])]), (??? : Checksum))"""
      ),
      Nil
    )

  test("CaptionPackage copy refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; (??? : CaptionPackage).copy(edition = (??? : EditionId))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(edition: EditionId, manifestChecksum: Checksum, parts: Vector[CaptionPackage.Part], extents: Vector[(String, Vector[(Int, CaptionProposal)])], identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy(edition = (??? : EditionId))"""
      ),
      Nil
    )

  test("CaptionPackage fromProduct refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; CaptionPackage.fromProduct((??? : Product))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(edition: EditionId, manifestChecksum: Checksum, parts: Vector[CaptionPackage.Part], extents: Vector[(String, Vector[(Int, CaptionProposal)])], identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : Product))"""
      ),
      Nil
    )

  test("CaptionPackage Mirror refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[CaptionPackage]]"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(edition: EditionId, manifestChecksum: Checksum, parts: Vector[CaptionPackage.Part], extents: Vector[(String, Vector[(Int, CaptionProposal)])], identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )

  test("CaptionPackage.Part constructor refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; new CaptionPackage.Part((??? : Int), (??? : String), (??? : Int), (??? : SampledFrameSet), (??? : CaptionSearchResult), (??? : Vector[Int]))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(index: Int, occurrence: String, globalStart: Int, frames: SampledFrameSet, captions: CaptionSearchResult, examinedSampleIndices: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control((??? : Int), (??? : String), (??? : Int), (??? : SampledFrameSet), (??? : CaptionSearchResult), (??? : Vector[Int]))"""
      ),
      Nil
    )

  test("CaptionPackage.Part apply refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; CaptionPackage.Part((??? : Int), (??? : String), (??? : Int), (??? : SampledFrameSet), (??? : CaptionSearchResult), (??? : Vector[Int]))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(index: Int, occurrence: String, globalStart: Int, frames: SampledFrameSet, captions: CaptionSearchResult, examinedSampleIndices: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control((??? : Int), (??? : String), (??? : Int), (??? : SampledFrameSet), (??? : CaptionSearchResult), (??? : Vector[Int]))"""
      ),
      Nil
    )

  test("CaptionPackage.Part copy refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; (??? : CaptionPackage.Part).copy(index = (??? : Int))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(index: Int, occurrence: String, globalStart: Int, frames: SampledFrameSet, captions: CaptionSearchResult, examinedSampleIndices: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy(index = (??? : Int))"""
      ),
      Nil
    )

  test("CaptionPackage.Part fromProduct refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; CaptionPackage.Part.fromProduct((??? : Product))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(index: Int, occurrence: String, globalStart: Int, frames: SampledFrameSet, captions: CaptionSearchResult, examinedSampleIndices: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : Product))"""
      ),
      Nil
    )

  test("CaptionPackage.Part Mirror refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[CaptionPackage.Part]]"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(index: Int, occurrence: String, globalStart: Int, frames: SampledFrameSet, captions: CaptionSearchResult, examinedSampleIndices: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )

  test("CaptionPackage.Location constructor refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; new CaptionPackage.Location((??? : Checksum), (??? : Int), (??? : String), (??? : Int), (??? : Int))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(packageIdentity: Checksum, partIndex: Int, occurrence: String, localSampleIndex: Int, presentationOrdinal: Int); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control((??? : Checksum), (??? : Int), (??? : String), (??? : Int), (??? : Int))"""
      ),
      Nil
    )

  test("CaptionPackage.Location apply refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; CaptionPackage.Location((??? : Checksum), (??? : Int), (??? : String), (??? : Int), (??? : Int))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(packageIdentity: Checksum, partIndex: Int, occurrence: String, localSampleIndex: Int, presentationOrdinal: Int); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control((??? : Checksum), (??? : Int), (??? : String), (??? : Int), (??? : Int))"""
      ),
      Nil
    )

  test("CaptionPackage.Location copy refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; (??? : CaptionPackage.Location).copy(partIndex = (??? : Int))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(packageIdentity: Checksum, partIndex: Int, occurrence: String, localSampleIndex: Int, presentationOrdinal: Int); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy(partIndex = (??? : Int))"""
      ),
      Nil
    )

  test("CaptionPackage.Location fromProduct refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; CaptionPackage.Location.fromProduct((??? : Product))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(packageIdentity: Checksum, partIndex: Int, occurrence: String, localSampleIndex: Int, presentationOrdinal: Int); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : Product))"""
      ),
      Nil
    )

  test("CaptionPackage.Location Mirror refuses with a same-shape accepting control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[CaptionPackage.Location]]"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(packageIdentity: Checksum, partIndex: Int, occurrence: String, localSampleIndex: Int, presentationOrdinal: Int); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
