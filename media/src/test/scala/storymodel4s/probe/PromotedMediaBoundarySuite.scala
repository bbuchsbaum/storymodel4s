package storymodel4s.probe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.core.{Checksum, DomainError}
import storymodel4s.media.*

/** External construction courts for media values whose field combinations carry invariants. */
class PromotedMediaBoundarySuite extends FunSuite:
  private def right[A](result: Either[DomainError, A]): A =
    result.fold(error => fail(error.message), identity)

  test("ModelPin direct constructor is inaccessible"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.Checksum; case class Control(repo: String, revision: String, files: Map[String, Checksum]); new Control("repo", "revision", Map.empty[String, Checksum])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors("""new storymodel4s.media.ModelPin("repo", "revision", Map.empty)""").nonEmpty
    )

  test("ModelPin apply is absent"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.Checksum; case class Control(repo: String, revision: String, files: Map[String, Checksum]); Control("repo", "revision", Map.empty[String, Checksum])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors("""storymodel4s.media.ModelPin("repo", "revision", Map.empty)""").nonEmpty
    )

  test("ModelPin copy is absent"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.Checksum; case class Control(repo: String, revision: String, files: Map[String, Checksum]); (value: Control) => value.copy(repo = "other")"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """(value: storymodel4s.media.ModelPin) => value.copy(repo = "other")"""
      ).nonEmpty
    )

  test("ModelPin fromProduct is absent"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.Checksum; case class Control(repo: String, revision: String, files: Map[String, Checksum]); object Control { def fromProduct(product: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(product) }; Control.fromProduct(("repo", "revision", Map.empty[String, Checksum]))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.media.ModelPin.fromProduct(("repo", "revision", Map.empty))"""
      ).nonEmpty
    )

  test("ModelPin product Mirror is absent"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.core.Checksum; case class Control(repo: String, revision: String, files: Map[String, Checksum]); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.media.ModelPin]]"""
      ).nonEmpty
    )

  test("CaptionRecipe direct constructor is inaccessible"):
    assertEquals(
      typeCheckErrors(
        """case class Control(prompt: String, minPixels: Int, maxPixels: Int, maxNewTokens: Int, seed: Int); new Control("prompt", 1, 2, 3, 4)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors("""new storymodel4s.media.CaptionRecipe("prompt", 1, 2, 3, 4)""").nonEmpty
    )

  test("CaptionRecipe apply is absent"):
    assertEquals(
      typeCheckErrors(
        """case class Control(prompt: String, minPixels: Int, maxPixels: Int, maxNewTokens: Int, seed: Int); Control("prompt", 1, 2, 3, 4)"""
      ),
      Nil
    )
    assert(typeCheckErrors("""storymodel4s.media.CaptionRecipe("prompt", 1, 2, 3, 4)""").nonEmpty)

  test("CaptionRecipe copy is absent"):
    assertEquals(
      typeCheckErrors(
        """case class Control(prompt: String, minPixels: Int, maxPixels: Int, maxNewTokens: Int, seed: Int); (value: Control) => value.copy(seed = 5)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """(value: storymodel4s.media.CaptionRecipe) => value.copy(seed = 5)"""
      ).nonEmpty
    )

  test("CaptionRecipe fromProduct is absent"):
    assertEquals(
      typeCheckErrors(
        """case class Control(prompt: String, minPixels: Int, maxPixels: Int, maxNewTokens: Int, seed: Int); object Control { def fromProduct(product: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(product) }; Control.fromProduct(("prompt", 1, 2, 3, 4))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """storymodel4s.media.CaptionRecipe.fromProduct(("prompt", 1, 2, 3, 4))"""
      ).nonEmpty
    )

  test("CaptionRecipe product Mirror is absent"):
    assertEquals(
      typeCheckErrors(
        """case class Control(prompt: String, minPixels: Int, maxPixels: Int, maxNewTokens: Int, seed: Int); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.media.CaptionRecipe]]"""
      ).nonEmpty
    )

  test("PictureGeometry direct constructor is inaccessible"):
    assertEquals(
      typeCheckErrors("""case class Control(width: Int, height: Int); new Control(2, 3)"""),
      Nil
    )
    assert(typeCheckErrors("""new storymodel4s.media.PictureGeometry(2, 3)""").nonEmpty)

  test("PictureGeometry apply is absent"):
    assertEquals(
      typeCheckErrors("""case class Control(width: Int, height: Int); Control(2, 3)"""),
      Nil
    )
    assert(typeCheckErrors("""storymodel4s.media.PictureGeometry(2, 3)""").nonEmpty)

  test("PictureGeometry copy is absent"):
    assertEquals(
      typeCheckErrors(
        """case class Control(width: Int, height: Int); (value: Control) => value.copy(width = 4)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """(value: storymodel4s.media.PictureGeometry) => value.copy(width = 4)"""
      ).nonEmpty
    )

  test("PictureGeometry fromProduct is absent"):
    assertEquals(
      typeCheckErrors(
        """case class Control(width: Int, height: Int); object Control { def fromProduct(product: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(product) }; Control.fromProduct((2, 3))"""
      ),
      Nil
    )
    assert(typeCheckErrors("""storymodel4s.media.PictureGeometry.fromProduct((2, 3))""").nonEmpty)

  test("PictureGeometry product Mirror is absent"):
    assertEquals(
      typeCheckErrors(
        """case class Control(width: Int, height: Int); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.media.PictureGeometry]]"""
      ).nonEmpty
    )

  test("lawful factories retain reads, equality, hashes, and identities"):
    val files = Map(
      "config.json" -> Checksum.ofText("config"),
      "weights.safetensors" -> Checksum.ofText("weights")
    )
    val model = right(ModelPin.of("repo", "revision", files))
    val equalModel = right(ModelPin.of("repo", "revision", files))
    assertEquals(model.repo, "repo")
    assertEquals(model.revision, "revision")
    assertEquals(model.files, files)
    assertEquals(model, equalModel)
    assertEquals(model.hashCode, equalModel.hashCode)
    assertEquals(model.identity, equalModel.identity)

    val recipe = right(CaptionRecipe.of("prompt", 1, 2, 3, 4))
    val equalRecipe = right(CaptionRecipe.of("prompt", 1, 2, 3, 4))
    assertEquals(recipe.prompt, "prompt")
    assertEquals(recipe, equalRecipe)
    assertEquals(recipe.hashCode, equalRecipe.hashCode)
    assertEquals(recipe.identity, equalRecipe.identity)

  test("CaptionRecipe rejects a blank prompt"):
    assert(CaptionRecipe.of("", 1, 1, 1, 0).isLeft)

  test("CaptionRecipe rejects non-positive minimum pixels"):
    assert(CaptionRecipe.of("prompt", 0, 1, 1, 0).isLeft)

  test("CaptionRecipe rejects maximum pixels below the minimum"):
    assert(CaptionRecipe.of("prompt", 2, 1, 1, 0).isLeft)

  test("CaptionRecipe rejects non-positive token limit"):
    assert(CaptionRecipe.of("prompt", 1, 1, 0, 0).isLeft)

  test("PictureGeometry permits an exactly representable frame byte count"):
    assertEquals(right(PictureGeometry.of(2, 3)).frameBytes, 18L)
    assert(PictureGeometry.of(Int.MaxValue, 1).isRight)

  test("PictureGeometry rejects non-positive dimensions"):
    assert(PictureGeometry.of(0, 1).isLeft)

  test("PictureGeometry rejects a frame byte count above Long maximum"):
    assert(PictureGeometry.of(Int.MaxValue, Int.MaxValue).isLeft)

  test("DeclaredFrames permits a last generated PTS at Long maximum"):
    val lastLong = right(DeclaredFrames.of(2, Long.MaxValue, Vector.empty))
    assertEquals(lastLong.expectedPts.last, Long.MaxValue)

  test("DeclaredFrames rejects an unrepresentable last generated PTS"):
    assert(DeclaredFrames.of(3, Long.MaxValue, Vector.empty).isLeft)
