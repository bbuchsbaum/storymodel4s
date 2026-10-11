package storymodel4s.pipelineprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class PackageCaptionBoundarySuite extends FunSuite:

  test("package registration constructor refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; new CaptionMapping.PackageRegistered((??? : CaptionPackage), (??? : BoundCaptionSourceCodec.Imported), (??? : CaptionScoring.Produced))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; case class Control(media: CaptionPackage, joined: BoundCaptionSourceCodec.Imported, produced: CaptionScoring.Produced); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control((??? : CaptionPackage), (??? : BoundCaptionSourceCodec.Imported), (??? : CaptionScoring.Produced))"""
      ),
      Nil
    )

  test("package registration apply refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; CaptionMapping.PackageRegistered((??? : CaptionPackage), (??? : BoundCaptionSourceCodec.Imported), (??? : CaptionScoring.Produced))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; case class Control(media: CaptionPackage, joined: BoundCaptionSourceCodec.Imported, produced: CaptionScoring.Produced); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control((??? : CaptionPackage), (??? : BoundCaptionSourceCodec.Imported), (??? : CaptionScoring.Produced))"""
      ),
      Nil
    )

  test("package registration copy refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; (??? : CaptionMapping.PackageRegistered).copy(media = (??? : CaptionPackage))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; case class Control(media: CaptionPackage, joined: BoundCaptionSourceCodec.Imported, produced: CaptionScoring.Produced); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy(media = (??? : CaptionPackage))"""
      ),
      Nil
    )

  test("package registration fromProduct refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; CaptionMapping.PackageRegistered.fromProduct((??? : Product))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; case class Control(media: CaptionPackage, joined: BoundCaptionSourceCodec.Imported, produced: CaptionScoring.Produced); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : Product))"""
      ),
      Nil
    )

  test("package registration Mirror refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[CaptionMapping.PackageRegistered]]"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.pipeline.*; import storymodel4s.media.*; import storymodel4s.align.*; case class Control(media: CaptionPackage, joined: BoundCaptionSourceCodec.Imported, produced: CaptionScoring.Produced); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
