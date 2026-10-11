package storymodel4s.codecprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class BoundCaptionSourceBoundarySuite extends FunSuite:

  test("joined source constructor refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; new BoundCaptionSourceCodec.Imported((??? : TimedSourceView), (??? : TimedSourceView), (??? : MediaCaptionSourceCodec.Imported), (??? : String))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; case class Control(view: TimedSourceView, annotations: TimedSourceView, media: MediaCaptionSourceCodec.Imported, artifact: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control((??? : TimedSourceView), (??? : TimedSourceView), (??? : MediaCaptionSourceCodec.Imported), (??? : String))"""
      ),
      Nil
    )

  test("joined source apply refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; BoundCaptionSourceCodec.Imported((??? : TimedSourceView), (??? : TimedSourceView), (??? : MediaCaptionSourceCodec.Imported), (??? : String))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; case class Control(view: TimedSourceView, annotations: TimedSourceView, media: MediaCaptionSourceCodec.Imported, artifact: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control((??? : TimedSourceView), (??? : TimedSourceView), (??? : MediaCaptionSourceCodec.Imported), (??? : String))"""
      ),
      Nil
    )

  test("joined source copy refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; (??? : BoundCaptionSourceCodec.Imported).copy(artifact = "x")"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; case class Control(view: TimedSourceView, annotations: TimedSourceView, media: MediaCaptionSourceCodec.Imported, artifact: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy(artifact = "x")"""
      ),
      Nil
    )

  test("joined source fromProduct refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; BoundCaptionSourceCodec.Imported.fromProduct((??? : Product))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; case class Control(view: TimedSourceView, annotations: TimedSourceView, media: MediaCaptionSourceCodec.Imported, artifact: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : Product))"""
      ),
      Nil
    )

  test("joined source Mirror refuses with positive control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; summon[scala.deriving.Mirror.ProductOf[BoundCaptionSourceCodec.Imported]]"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.bridge.*; case class Control(view: TimedSourceView, annotations: TimedSourceView, media: MediaCaptionSourceCodec.Imported, artifact: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
