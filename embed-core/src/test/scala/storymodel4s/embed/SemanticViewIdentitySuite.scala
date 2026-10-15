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
    // Native 0.5.12 crashes hashing a nonempty zero-hash string literal. Preserve the same
    // character coverage with runtime storage; the independent runtime defect stays recorded.
    val nul = new String(Array(0.toChar))
    assertEquals(nul.length, 1)
    assertEquals(nul.charAt(0).toInt, 0)
    assertEquals(nul.hashCode, 0)
    val components = Vector("", ":", "a", "a:b", "b:c", "\\", "λ", nul, "\n", "12:a", "😀")
    val views = for namespace <- components; name <- components
    yield SemanticView.Custom(namespace, name)
    assertEquals(views.distinct.size, components.size * components.size)
    assertEquals(views.map(_.render).distinct.size, views.size)
    assertEquals(views.map(view => space(view).id).distinct.size, views.size)
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
