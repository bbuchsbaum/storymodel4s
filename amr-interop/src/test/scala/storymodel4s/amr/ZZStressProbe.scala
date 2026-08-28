package storymodel4s.amr

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.amr.graph.*
import storymodel4s.amr.interop.*
import storymodel4s.amr.schema.*
import storymodel4s.proposition as p

/** Higher-iteration round-trip and canonical-form laws over the twin-bearing generator; this is
  * where the "first relation only" embedding-loss bug surfaced (after ~1,400 cases).
  */
class ZZStressProbe extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(500)

  private val lexicon: FrameLexicon = StarterLexicon.lexicon

  property("stress: FromChart(ToChart(g)) ≅ g") {
    forAll(Gens.canonicalGraph) { g =>
      val ch = ToChart.convert(g, None, lexicon, None).fold(e => fail(e.message), identity)
      val g2 =
        FromChart.convert(ch, lexicon).fold(e => fail(e.message + "\n" + g.renderTriples), identity)
      assert(AmrIsomorphism.isomorphic(g, g2), g.renderTriples + "\n---\n" + g2.renderTriples)
      true
    }
  }

  property("stress: ToChart(FromChart(c)) ≅ c") {
    forAll(Gens.canonicalGraph) { g =>
      val ch = ToChart.convert(g, None, lexicon, None).fold(e => fail(e.message), identity)
      val g2 =
        FromChart.convert(ch, lexicon).fold(e => fail(e.message + "\n" + g.renderTriples), identity)
      val ch2 = ToChart.convert(g2, None, lexicon, None).fold(e => fail(e.message), identity)
      assert(p.ChartIsomorphism.isomorphic(ch, ch2), g.renderTriples)
      true
    }
  }

  property("stress: canonical form laws") {
    forAll(Gens.canonicalGraph.flatMap(g => Gens.alphaVariant(g).map(g -> _))) {
      case (g, variant) =>
        val v = RoleCanonicalizer.canonicalize(AmrValidator.validateOrThrow(variant))
        assert(AmrIsomorphism.isomorphic(g, v), g.renderTriples)
        assertEquals(
          Canonical.form(g),
          Canonical.form(v),
          g.renderTriples + "\n---\n" + v.renderTriples
        )
        true
    }
  }
