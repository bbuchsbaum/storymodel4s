package storymodel4s.bench.video

import munit.FunSuite
import storymodel4s.align.{HsmmConfig, RelationLayer, TransitionKind}

/** Courts for the ablation ladder (ADR 0016): the rungs `gates.md` requires as cumulative sets of
  * admitted feature kinds, the model a rung runs under, the external prior it never touches, and
  * the provenance that names it from one bound `LadderRun`.
  */
class LadderSuite extends FunSuite:
  import RecallOrderControl.*
  import TransitionKind.*

  private def run(ladder: Ladder, scale: Double): LadderRun =
    LadderRun.of(ladder, scale).fold(e => fail(e), identity)

  test("the rungs are cumulative, start from content alone, and end admitting every feature kind") {
    val rungs = Rung.values.toVector
    rungs.zip(rungs.tail).foreach { (lo, hi) =>
      assert(lo.admitted.subsetOf(hi.admitted), s"${lo.label} must be inside ${hi.label}")
    }
    assertEquals(Rung.Content.admitted, Set.empty[TransitionKind])
    assertEquals(Rung.Content.withheld, TransitionKind.features.toSet)
    assertEquals(Rung.Similarity.admitted, TransitionKind.features.toSet)
    assertEquals(
      rungs.map(_.label),
      Vector("content", "+hierarchy", "+order", "+causality", "+similarity")
    )
    assert(Rung.Order.admitted.contains(Stay) && !Rung.Hierarchy.admitted.contains(Stay))
    assert(Rung.Causality.admitted.contains(CauseToEffect))
    // the external logits are on no rung, admitted or withheld
    rungs.foreach { r =>
      assert(!r.admitted.contains(ExternalIn) && !r.withheld.contains(ExternalIn), r.label)
    }
  }

  test("every rung holds the external prior at its shipped value") {
    val shipped = HsmmConfig.default.layerUse
    Rung.values.foreach { r =>
      val use = run(Ladder(Some(r), Set.empty), 1.5).config.layerUse
      assertEquals(use.externalIn, shipped.externalIn, r.label)
      assertEquals(use.externalStay, shipped.externalStay, r.label)
    }
    assertEquals(
      run(Ladder(Some(Rung.Content), Set.empty), 1.5).config.transitions(ExternalIn),
      -1.5
    )
  }

  test("ladderConfig zeroes the withheld feature kinds and keeps the scale on the rest") {
    val cfg = ladderConfig(1.5, Rung.Order.withheld)
    assertEquals(cfg.transitions(DiscourseSuccessor), 2.25)
    assertEquals(cfg.transitions(HierarchyUp), 0.6)
    assertEquals(cfg.transitions(CausalNeighbor), 0.0)
    assertEquals(cfg.transitions(ExternalIn), -1.5)
    assert(!cfg.layerUse.layersRead.contains(RelationLayer.Causal))
    assert(cfg.layerUse.layersRead.contains(RelationLayer.DiscourseSuccession))
    assertEquals(ladderConfig(1.5, Set.empty), scaledConfig(1.5))
    assertEquals(ladderConfig(1.5, Set.empty).fingerprint, scaledConfig(1.5).fingerprint)
    assertNotEquals(cfg.fingerprint, scaledConfig(1.5).fingerprint)
  }

  test("equal configurations have equal fingerprints, negative zero included") {
    // scaledConfig(0.0) turns Backward and LongJump into -0.0; withholding them yields 0.0
    val scaledToZero = scaledConfig(0.0)
    val withheld = ladderConfig(0.0, Set(Backward, LongJump))
    assertEquals(scaledToZero, withheld)
    assertEquals(scaledToZero.layerUse, withheld.layerUse)
    assertEquals(scaledToZero.fingerprint, withheld.fingerprint)
  }

  test("Ladder.parse takes a rung by name and extra feature kinds, and refuses the rest") {
    assertEquals(
      Ladder.parse(Some("causality"), None),
      Right(Ladder(Some(Rung.Causality), Set.empty))
    )
    assertEquals(Ladder.parse(Some("+causality"), None).map(_.rung), Right(Some(Rung.Causality)))
    assertEquals(
      Ladder.parse(None, Some("CausalNeighbor, Backward")),
      Right(Ladder(None, Set(CausalNeighbor, Backward)))
    )
    assertEquals(Ladder.parse(None, None), Right(Ladder.full))
    assert(Ladder.parse(Some("nonsense"), None).isLeft)
    assert(Ladder.parse(Some("external"), None).isLeft, "+external is a named gap, not a rung")
    assert(Ladder.parse(None, Some("CausalNeighbor,Foo")).isLeft)
    assert(Ladder.parse(None, Some("ExternalIn")).isLeft, "a logit cannot be withheld")
    assertEquals(Ladder.full.withheld, Set.empty[TransitionKind])
    assertEquals(Ladder.full.label, "full")
    assertEquals(Ladder(Some(Rung.Content), Set.empty).label, "content")
    assertEquals(Ladder(None, Set(Backward, CausalNeighbor)).label, "full-Backward,CausalNeighbor")
    assertEquals(
      Ladder(Some(Rung.Causality), Set(Stay)).withheld,
      Rung.Causality.withheld + Stay
    )
  }

  test("LadderRun binds the label to the model and refuses a withheld logit or a bad scale") {
    val r = run(Ladder(Some(Rung.Order), Set.empty), 1.5)
    assertEquals(r.config, ladderConfig(1.5, Rung.Order.withheld))
    assertEquals(r.scale, 1.5)
    assert(LadderRun.of(Ladder(None, Set(ExternalStay)), 1.5).isLeft)
    assert(LadderRun.of(Ladder.full, -1.0).isLeft)
    assert(LadderRun.of(Ladder.full, Double.NaN).isLeft)
  }

  test("provenance carries the effective prior scale, the rung, θ and the layer use") {
    val segments = Vector(
      TimedSegment(1, "A man walks through rain.", None),
      TimedSegment(2, "He finds a red door.", None)
    )
    val built = TimedSourceView
      .build(segments, WorldOrderFixtures.syntheticLinear)
      .fold(e => fail(e.message), identity)
    val fullRun = run(Ladder.full, 1.5)
    val full = RecallToVideo.provenanceConfig("lexical", 8, false, fullRun, built.worldOrder)
    assert(full.contains("priorScale=1.5"), full)
    assert(full.contains("rung=full"), full)
    assert(full.contains(s"theta=${fullRun.config.fingerprint.hex}"), full)
    assert(full.contains(fullRun.config.layerUse.render), full)
    assert(full.contains("external=[pIn=0.1824,pStay=0.2994]"), full)
    val contentRun = run(Ladder(Some(Rung.Content), Set.empty), 1.5)
    val content = RecallToVideo.provenanceConfig("lexical", 8, false, contentRun, built.worldOrder)
    assert(content.contains("rung=content"), content)
    assert(content.contains(s"theta=${contentRun.config.fingerprint.hex}"), content)
    assertNotEquals(full, content)
    // the same rung under a different scale is a different derivation
    val rescaled =
      RecallToVideo.provenanceConfig("lexical", 8, false, run(Ladder.full, 1.0), built.worldOrder)
    assertNotEquals(full, rescaled)
  }
