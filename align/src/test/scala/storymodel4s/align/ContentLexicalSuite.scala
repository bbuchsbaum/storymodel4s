package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.*

/** Pins the historical lexical provider and the content scorer to the same lemma-set contract. */
class ContentLexicalSuite extends FunSuite:
  private val unit = AnnaFixture.u2
  private val node = AnnaFixture.view.node(AnnaFixture.e5).getOrElse(fail("missing e5"))

  private def pair(a: Set[String], b: Set[String]): (RecallUnit, NodeSummary) =
    (
      unit.copy(proposition = unit.proposition.copy(lemmas = a)),
      node.copy(lemmas = b)
    )

  private val cases = Vector(
    (Set.empty[String], Set.empty[String], 1.0),
    (Set.empty[String], Set("a"), 1.0),
    (Set("a"), Set.empty[String], 1.0),
    (Set("a"), Set("a"), 0.0),
    (Set("a", "b"), Set("b", "c"), 0.6666666666666667),
    (Set("a", "b"), Set("a", "b", "c"), 0.33333333333333337)
  )

  test("the historical provider preserves the pinned Jaccard grid") {
    for (a, b, expected) <- cases do
      val (u, n) = pair(a, b)
      assertEquals(SemanticDistance.lexicalJaccard(u, n).toOption, Some(expected))
  }

  test("typed lexical scoring agrees with the historical provider on each lemma pair") {
    for
      a <- cases.map(_._1).distinct
      b <- cases.map(_._2).distinct
    do
      val (u, n) = pair(a, b)
      val source =
        ContentLexical.score(ContentProjection.sourceUnit(u), ContentProjection.sourceNode(n))
      assertEquals(source, SemanticDistance.lexicalJaccard(u, n))
  }

  test("canonical lexical scoring ignores identities and storage order") {
    val reversedView = AnnaFixture.view.copy(nodes = AnnaFixture.view.nodes.reverse)
    val renamed = unit.copy(id = RecallUnitId.unsafe("lexical-renamed"), ordinal = 99)
    val original = ContentProjection
      .canonical(unit, node, AnnaFixture.view)
      .fold(r => fail(s"canonical projection refused: $r"), identity)
    val reordered = ContentProjection
      .canonical(renamed, reversedView.node(node.ref).get, reversedView)
      .fold(r => fail(s"canonical projection refused: $r"), identity)
    assertEquals(
      ContentLexical.score(original._1, original._2),
      ContentLexical.score(reordered._1, reordered._2)
    )
  }
