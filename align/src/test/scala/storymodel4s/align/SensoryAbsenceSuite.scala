package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.Lexical

/** Empty `sensoryTerms` is no observation: `d_sens` is missing, not a perfect match of `0.0`.
  *
  * Why a dedicated suite: blending `0.0` with any weight is still `0.0`, so every total-comparison
  * test survives the defect. The discriminating facts are publication (`missingTerms` / `has`) and
  * a nonempty miss whose independently recomputed distance is `1.0`.
  */
class SensoryAbsenceSuite extends FunSuite:
  import AnnaFixture.*

  private val eps = 1e-12
  private val node = view.node(e5).get
  private val probe = "zzz"

  test("capacity: the empty fixture lists no sensory terms; the miss fixture lists one") {
    assert(u2.proposition.sensoryTerms.isEmpty, u2.proposition.sensoryTerms)
    assertEquals(withProbe.proposition.sensoryTerms, Vector(probe))
    assert(!node.lemmas.contains(Lexical.stem(probe)), node.lemmas)
  }

  test("empty sensoryTerms is recorded missing and does not invent a 0.0 term") {
    val b = costModel.cost(u2, node, FidelityMode.Faithful, view)
    assert(u2.proposition.sensoryTerms.isEmpty)
    assert(!b.has(CostTerm.Sensory), b.terms)
    assert(b.missingTerms.contains(CostTerm.Sensory), b.missingTerms)
    assert(!b.reductions.contains(CostTerm.Sensory), b.reductions.keySet)
  }

  test("a looked-up miss is Observed(1.0) and the total rises by the sensory weight") {
    val empty = costModel.cost(u2, node, FidelityMode.Faithful, view)
    val miss = costModel.cost(withProbe, node, FidelityMode.Faithful, view)
    val hits = withProbe.proposition.sensoryTerms.count(t => node.lemmas.contains(Lexical.stem(t)))
    val expected = 1.0 - hits.toDouble / withProbe.proposition.sensoryTerms.size.toDouble
    assertEquals(hits, 0)
    assertEqualsDouble(expected, 1.0, eps)
    assert(miss.has(CostTerm.Sensory))
    assert(!miss.missingTerms.contains(CostTerm.Sensory), miss.missingTerms)
    assertEqualsDouble(miss.term(CostTerm.Sensory), expected, eps)
    assertEqualsDouble(
      miss.total - empty.total,
      CostWeights.default(CostTerm.Sensory) * expected,
      eps
    )
  }

  private def withProbe =
    u2.copy(proposition = u2.proposition.copy(sensoryTerms = Vector(probe)))
