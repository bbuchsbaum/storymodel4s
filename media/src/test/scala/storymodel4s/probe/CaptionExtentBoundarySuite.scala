package storymodel4s.probe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Outside-package courts for CaptionExtent's checked construction boundary. */
class CaptionExtentBoundarySuite extends FunSuite:

  test("the checked factory is the public construction door"):
    assertEquals(typeCheckErrors("""storymodel4s.media.CaptionExtent.of("x", Vector(1))"""), Nil)

  test("apply is unavailable while a same-shape control has it"):
    assert(typeCheckErrors("""storymodel4s.media.CaptionExtent("x", Vector(1))""").nonEmpty)
    assertEquals(
      typeCheckErrors(
        """case class Control(id: String, ordinals: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control("x", Vector(1))"""
      ),
      Nil
    )

  test("copy is unavailable while a same-shape control has it"):
    assert(
      typeCheckErrors(
        """storymodel4s.media.CaptionExtent.of("x", Vector(1)).toOption.get.copy(id = "y")"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """case class Control(id: String, ordinals: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy(id = "y")"""
      ),
      Nil
    )

  test("fromProduct is unavailable while a same-shape control has it"):
    assert(
      typeCheckErrors(
        """storymodel4s.media.CaptionExtent.fromProduct(("x", Vector(1)))"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """case class Control(id: String, ordinals: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(("x", Vector(1)))"""
      ),
      Nil
    )

  test("a product Mirror is unavailable while a same-shape control has one"):
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.media.CaptionExtent]]"""
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        """case class Control(id: String, ordinals: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )

  test("the constructor is unavailable while a same-shape control permits it"):
    assert(typeCheckErrors("""new storymodel4s.media.CaptionExtent("x", Vector(1))""").nonEmpty)
    assertEquals(
      typeCheckErrors(
        """case class Control(id: String, ordinals: Vector[Int]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control("x", Vector(1))"""
      ),
      Nil
    )
