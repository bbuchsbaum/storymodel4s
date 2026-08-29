package storymodel4s.align

import storymodel4s.recall.Lexical

class AnnaFixtureSuite extends munit.FunSuite:

  test("AnnaFixture node lemmas are normalized by the production stemmer") {
    val expected = AnnaFixture.nodeLemmaInputs.map { case (ref, terms) =>
      ref -> terms.flatMap(Lexical.stems).toSet
    }.toMap
    val actual = AnnaFixture.nodes.map(node => node.ref -> node.lemmas).toMap

    assertEquals(actual, expected)
  }

  test("AnnaFixture declares one lemma-input set for every source node") {
    val inputRefs = AnnaFixture.nodeLemmaInputs.map(_._1)
    val nodeRefs = AnnaFixture.nodes.map(_.ref)

    assertEquals(inputRefs.distinct.size, inputRefs.size)
    assertEquals(inputRefs.toSet, nodeRefs.toSet)
  }

  test("AnnaFixture normalization does not pass vacuously with raw lemmas") {
    val firstEvent = AnnaFixture.nodes
      .find(_.ref == AnnaFixture.e1)
      .getOrElse(fail("AnnaFixture is missing its first event"))

    assert(firstEvent.lemmas.contains("isol"))
    assert(!firstEvent.lemmas.contains("isolated"))
  }
