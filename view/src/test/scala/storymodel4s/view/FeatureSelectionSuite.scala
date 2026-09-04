package storymodel4s.view

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.*

class FeatureSelectionSuite extends FunSuite:
  test("a produced sentence aggregate resolves only under its recorded ordered basis"):
    val source = StorySource.fromText("One word. Three other words.").toOption.get
    val sequence = SurfaceSequence(SurfaceAnalyzer.analyze(source))
    val raw = TokenTracks.measure(sequence, TokenLength).toOption.get
    val track = TokenTracks.perSentence(raw, sequence).toOption.get
    val recipe = track.derivation.get.derivationId
    val basis = track.provenance.basisId.get.checksum
    assertEquals(FeatureSelection.Derived(recipe, Some(basis)).resolveSpace, Right(track.space.id))
    assertNotEquals(FeatureSelection.Derived(recipe).resolveSpace, Right(track.space.id))
    assertNotEquals(
      FeatureSelection.Derived(recipe, Some(Checksum.ofText("another ordered basis"))).resolveSpace,
      Right(track.space.id)
    )

  test("raw spaces retain their exact identity"):
    assertEquals(
      FeatureSelection.Raw(TokenLength.space.id).resolveSpace,
      Right(TokenLength.space.id)
    )

  test("display domains keep finite endpoint fractions for subnormal and overflowing ranges"):
    Vector((0.0, Double.MinPositiveValue), (-Double.MaxValue, Double.MaxValue), (-3.0, 7.0))
      .foreach { (lo, hi) =>
        val domain = new FeatureDomain(lo, hi)
        assertEquals(domain.fraction(lo), 0.0)
        assertEquals(domain.fraction(hi), 1.0)
      }
    assertEquals(new FeatureDomain(0.0, 0.0).fraction(0.0), 0.5)
