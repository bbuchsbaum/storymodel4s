package storymodel4s.align

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Courts for the layer-use ledger and the configuration fingerprint (ADR 0016). The ledger is
  * derived from θ inside the configuration and never supplied, so it can only say what the model it
  * belongs to actually weights; the fingerprint makes a change to θ visible to identity.
  */
class LayerUseSuite extends munit.ScalaCheckSuite:
  import TransitionKind.*

  private def model(overrides: (TransitionKind, Double)*): TransitionModel =
    TransitionModel(TransitionModel.default.theta ++ overrides.toMap)

  test("the shipped model reads five layers by name, hierarchy through parents, and positions") {
    val use = LayerUse.of(TransitionModel.default)
    assertEquals(
      use.layersRead,
      Set(
        RelationLayer.DiscourseSuccession,
        RelationLayer.WorldTime,
        RelationLayer.Causal,
        RelationLayer.EntityContinuity,
        RelationLayer.Semantic,
        RelationLayer.Hierarchy
      )
    )
    assert(use.positionsRead)
    assertEquals(use.withheld, Set(CauseToEffect, EffectToCause))
    assertEquals(use.weighted ++ use.withheld, TransitionKind.features.toSet)
    assert(!use.weighted.contains(ExternalIn) && !use.withheld.contains(ExternalIn))
    // the external logits are carried as the probabilities they set, sigma(-1.5) and sigma(-0.85)
    assertEqualsDouble(use.externalIn, 1.0 / (1.0 + math.exp(1.5)), 1e-12)
    assertEqualsDouble(use.externalStay, 1.0 / (1.0 + math.exp(0.85)), 1e-12)
  }

  test("a zero external logit is the prior one half, never a withheld feature") {
    val use = LayerUse.of(model(ExternalIn -> 0.0, ExternalStay -> 0.0))
    assertEquals(use.externalIn, 0.5)
    assertEquals(use.externalStay, 0.5)
    assertEquals(use.withheld, Set(CauseToEffect, EffectToCause))
    assert(use.render.contains("external=[pIn=0.5000,pStay=0.5000]"), use.render)
    assert(!use.render.contains("ExternalIn"), use.render)
  }

  test("negative zero is normalized by the configuration, so one model has one address") {
    val minus = HsmmConfig.unsafe(transitions = model(Backward -> -0.0))
    val plus = HsmmConfig.unsafe(transitions = model(Backward -> 0.0))
    assertEquals(minus, plus)
    assertEquals(minus.layerUse, plus.layerUse)
    assertEquals(minus.fingerprint, plus.fingerprint)
    assertEquals(
      java.lang.Double.doubleToRawLongBits(minus.transitions(Backward)),
      java.lang.Double.doubleToRawLongBits(0.0)
    )
    assertEquals(
      HsmmConfig.unsafe(refinementWeight = -0.0).fingerprint,
      HsmmConfig.unsafe(refinementWeight = 0.0).fingerprint
    )
  }

  test("zeroing a kind withdraws its layer, and only its layer") {
    val noDiscourse = LayerUse.of(model(DiscourseSuccessor -> 0.0))
    assert(!noDiscourse.layersRead.contains(RelationLayer.DiscourseSuccession))
    assert(noDiscourse.layersRead.contains(RelationLayer.WorldTime))
    assert(noDiscourse.withheld.contains(DiscourseSuccessor))

    val noCausal = LayerUse.of(model(CausalNeighbor -> 0.0))
    assert(!noCausal.layersRead.contains(RelationLayer.Causal))
    val directedOnly = LayerUse.of(model(CausalNeighbor -> 0.0, CauseToEffect -> 0.8))
    assert(directedOnly.layersRead.contains(RelationLayer.Causal))

    // hierarchy is reached through parents by the hierarchy kinds and by Backward
    val noHierarchyKinds = LayerUse.of(model(HierarchyUp -> 0.0, HierarchyDown -> 0.0))
    assert(noHierarchyKinds.layersRead.contains(RelationLayer.Hierarchy), "Backward still reads it")
    val noHierarchy = LayerUse.of(model(HierarchyUp -> 0.0, HierarchyDown -> 0.0, Backward -> 0.0))
    assert(!noHierarchy.layersRead.contains(RelationLayer.Hierarchy))
    assert(noHierarchy.positionsRead, "LongJump still reads positions")

    val nothing = LayerUse.of(TransitionModel(Map.empty))
    assertEquals(nothing.layersRead, Set.empty[RelationLayer])
    assert(!nothing.positionsRead)
    assertEquals(nothing.withheld, TransitionKind.features.toSet)
    assertEquals(nothing.externalIn, 0.5)
  }

  property("weighted and withheld partition the kinds for any θ, and an absent kind is a zero") {
    val genTheta: Gen[Map[TransitionKind, Double]] =
      Gen.mapOf(Gen.zip(Gen.oneOf(TransitionKind.values.toSeq), Gen.choose(-2.0, 2.0)))
    forAll(genTheta) { theta =>
      val use = LayerUse.of(TransitionModel(theta))
      val explicit = LayerUse.of(
        TransitionModel(
          TransitionKind.values.map(k => k -> theta.getOrElse(k, 0.0)).toMap
        )
      )
      use.weighted.intersect(use.withheld).isEmpty &&
      (use.weighted ++ use.withheld) == TransitionKind.features.toSet &&
      use == explicit
    }
  }

  test("the configuration fingerprint separates θ, temperature and refinement") {
    val base = HsmmConfig.default.fingerprint
    val scaled = HsmmConfig.unsafe(transitions = model(DiscourseSuccessor -> 2.25)).fingerprint
    assertNotEquals(base, scaled)
    assertNotEquals(base, HsmmConfig.unsafe(temperature = 0.2).fingerprint)
    assertNotEquals(base, HsmmConfig.unsafe(refinementPasses = 1).fingerprint)
    assertNotEquals(base, HsmmConfig.unsafe(refinementWeight = 0.4).fingerprint)
    // an explicit zero and an absent kind are the same model, so the same address
    assertEquals(HsmmConfig.unsafe(transitions = model(CauseToEffect -> 0.0)).fingerprint, base)
    assertEquals(HsmmConfig.default.fingerprint, base)
  }

  test("a configuration's ledger is derived from its own transitions") {
    assertEquals(HsmmConfig.default.layerUse, LayerUse.of(TransitionModel.default))
    val custom =
      HsmmConfig.unsafe(transitions = model(CausalNeighbor -> 0.0, SemanticNeighbor -> 0.0))
    assertEquals(custom.layerUse, LayerUse.of(custom.transitions))
    assertEquals(
      custom.layerUse.withheld,
      Set(CausalNeighbor, SemanticNeighbor, CauseToEffect, EffectToCause)
    )
  }

  test("render is sorted and separates two ledgers") {
    val a = HsmmConfig.default.layerUse.render
    val b = HsmmConfig.unsafe(transitions = model(CausalNeighbor -> 0.0)).layerUse.render
    assert(
      a.contains(
        "layers=[Causal,DiscourseSuccession,EntityContinuity,Hierarchy,Semantic,WorldTime]"
      ),
      a
    )
    assert(a.contains("withheld=[CauseToEffect,EffectToCause]"), a)
    assert(a.contains("external=[pIn=0.1824,pStay=0.2994]"), a)
    assertNotEquals(a, b)
  }
