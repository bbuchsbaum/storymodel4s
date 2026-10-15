package semanticviewprobe

import munit.FunSuite
import storymodel4s.embed.*

/** Custom view names must not merge unequal semantic spaces at a delimiter boundary. */
class SemanticViewIdentitySuite extends FunSuite:
  private val provider = ProviderFingerprint.of("model", "tokenizer", "implementation", "runtime")
  private def space(view: SemanticView): EmbeddingSpace =
    EmbeddingSpace
      .of(
        provider,
        Role.Document,
        view,
        None,
        Dimension.unsafe(4),
        Normalization.L2,
        TruncationPolicy.Reject
      )
      .fold(error => fail(error.message), identity)

  test("the old namespace/name collision has distinct versioned renderings and geometry ids") {
    val left = SemanticView.Custom("a:b", "c")
    val right = SemanticView.Custom("a", "b:c")
    assertNotEquals(left, right)
    assertEquals(left.render, "custom/v2:3:a:b:1:c")
    assertEquals(right.render, "custom/v2:1:a:3:b:c")
    assertNotEquals(left.render, right.render)
    assertNotEquals(space(left).id, space(right).id)
    assertEquals(space(left).id, space(SemanticView.Custom("a:b", "c")).id)
  }

  test("empty, Unicode and separator-bearing component pairs retain separate identities") {
    val components = Vector("", ":", "a", "a:b", "b:c", "\\", "λ", "\u0000", "\n", "12:a", "😀")
    val views = for namespace <- components; name <- components
    yield SemanticView.Custom(namespace, name)
    println("PHASE views-created")
    assertEquals(views.distinct.size, components.size * components.size)
    println("PHASE values-distinct")
    assertEquals(views.map(_.render).distinct.size, views.size)
    println("PHASE renders-distinct")
    assertEquals(views.map { view =>
      println("SPACE " + view.render.map(_.toInt).mkString(","))
      space(view).id
    }.distinct.size, views.size)
    println("PHASE ids-distinct")
  }

  test("built-in semantic views retain their existing rendering") {
    assertEquals(
      Vector(
        SemanticView.Surface,
        SemanticView.Gloss,
        SemanticView.ContextualTemplate,
        SemanticView.ContextualLatePooled,
        SemanticView.Segment
      ).map(_.render),
      Vector("surface", "gloss", "contextual.template", "contextual.latepooled", "segment")
    )
  }
