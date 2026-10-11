package storymodel4s.probe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class SampledExtractionBoundarySuite extends FunSuite:

  test("SampledExtraction constructor refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; new SampledExtraction((??? : SampledFrameSet), (??? : Vector[Long]), (??? : String), (??? : Checksum))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(frames: SampledFrameSet, observedPts: Vector[Long], transcript: String, identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control((??? : SampledFrameSet), (??? : Vector[Long]), (??? : String), (??? : Checksum))"""
      ),
      Nil
    )

  test("SampledExtraction apply refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; SampledExtraction((??? : SampledFrameSet), (??? : Vector[Long]), (??? : String), (??? : Checksum))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(frames: SampledFrameSet, observedPts: Vector[Long], transcript: String, identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control((??? : SampledFrameSet), (??? : Vector[Long]), (??? : String), (??? : Checksum))"""
      ),
      Nil
    )

  test("SampledExtraction copy refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; (??? : SampledExtraction).copy(transcript = (??? : String))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(frames: SampledFrameSet, observedPts: Vector[Long], transcript: String, identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy(transcript = (??? : String))"""
      ),
      Nil
    )

  test("SampledExtraction fromProduct refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; SampledExtraction.fromProduct((??? : Product))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(frames: SampledFrameSet, observedPts: Vector[Long], transcript: String, identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : Product))"""
      ),
      Nil
    )

  test("SampledExtraction Mirror refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[SampledExtraction]]"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.media.*; import storymodel4s.core.*; case class Control(frames: SampledFrameSet, observedPts: Vector[Long], transcript: String, identity: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
