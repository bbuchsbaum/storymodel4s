package storymodel4s.codecprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class MediaCaptionSourceBoundarySuite extends FunSuite:

  test("Imported media source constructor refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; new MediaCaptionSourceCodec.Imported((??? : TimedSourceView), (??? : Json), (??? : Vector[CaptionEvidence]), (??? : Map[String,Vector[Int]]), (??? : Map[String,Vector[Int]]), (??? : Map[String,Int]))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; case class Control(view: TimedSourceView, document: Json, evidence: Vector[CaptionEvidence], sampled: Map[String,Vector[Int]], examined: Map[String,Vector[Int]], presented: Map[String,Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control((??? : TimedSourceView), (??? : Json), (??? : Vector[CaptionEvidence]), (??? : Map[String,Vector[Int]]), (??? : Map[String,Vector[Int]]), (??? : Map[String,Int]))"""
      ),
      Nil
    )

  test("Imported media source apply refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; MediaCaptionSourceCodec.Imported((??? : TimedSourceView), (??? : Json), (??? : Vector[CaptionEvidence]), (??? : Map[String,Vector[Int]]), (??? : Map[String,Vector[Int]]), (??? : Map[String,Int]))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; case class Control(view: TimedSourceView, document: Json, evidence: Vector[CaptionEvidence], sampled: Map[String,Vector[Int]], examined: Map[String,Vector[Int]], presented: Map[String,Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control((??? : TimedSourceView), (??? : Json), (??? : Vector[CaptionEvidence]), (??? : Map[String,Vector[Int]]), (??? : Map[String,Vector[Int]]), (??? : Map[String,Int]))"""
      ),
      Nil
    )

  test("Imported media source copy refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; (??? : MediaCaptionSourceCodec.Imported).copy(document = (??? : Json))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; case class Control(view: TimedSourceView, document: Json, evidence: Vector[CaptionEvidence], sampled: Map[String,Vector[Int]], examined: Map[String,Vector[Int]], presented: Map[String,Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy(document = (??? : Json))"""
      ),
      Nil
    )

  test("Imported media source fromProduct refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; MediaCaptionSourceCodec.Imported.fromProduct((??? : Product))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; case class Control(view: TimedSourceView, document: Json, evidence: Vector[CaptionEvidence], sampled: Map[String,Vector[Int]], examined: Map[String,Vector[Int]], presented: Map[String,Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct((??? : Product))"""
      ),
      Nil
    )

  test("Imported media source Mirror refuses with same-shape control"):
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; summon[scala.deriving.Mirror.ProductOf[MediaCaptionSourceCodec.Imported]]"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.align.bridge.*; import io.circe.Json; case class Control(view: TimedSourceView, document: Json, evidence: Vector[CaptionEvidence], sampled: Map[String,Vector[Int]], examined: Map[String,Vector[Int]], presented: Map[String,Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
