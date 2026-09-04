package storymodel4s.view

import munit.FunSuite
import storymodel4s.core.*

/** Fixture-free Atlas contract checks; the WOG laws live in fixtures (WarOfTheGhostsAtlasSuite). */
class AtlasSuite extends FunSuite:
  private def extent(x0: Int, x1Exclusive: Int, lane0: Int, lane1: Int): Extent =
    Extent.of(x0, x1Exclusive, lane0, lane1).fold(error => fail(error.message), identity)

  test("the Discourse Atlas contract declares every channel it uses and no area semantics"):
    val c = ProjectionContract.discourseAtlas
    assertEquals(c.x, AxisMeaning.DiscourseOffset)
    assertEquals(c.y, AxisMeaning.ContextLane)
    assertEquals(c.distance, DistanceMeaning.NoMeaning)
    assertEquals(c.area, None)
    assertEquals(c.legend.map(_.channel).toSet, VisualChannel.values.toSet)
    assert(c.invariants.contains(VisualInvariant.EvidenceBacked))
    assert(c.invariants.contains(VisualInvariant.SelectionPreserved))

  test("extents are checked, half-open in x, inclusive in lanes"):
    assert(Extent.of(-1, 2, 0, 0).isLeft)
    assert(Extent.of(3, 2, 0, 0).isLeft)
    assert(Extent.of(0, 2, 1, 0).isLeft)
    val a = extent(0, 10, 0, 0)
    val b = extent(3, 5, 0, 0)
    assert(a.contains(b) && !b.contains(a))
    assertEquals(a.hull(extent(20, 30, 2, 2)), extent(0, 30, 0, 2))
    assert(a.containsX(9) && !a.containsX(10))

  test("anchors reject negative source offsets and lanes"):
    assert(Anchor.of(-1, 0).isLeft)
    assert(Anchor.of(0, -1).isLeft)
    assertEquals(Anchor.of(3, 2).map(anchor => (anchor.x, anchor.lane)), Right((3, 2)))

  test("scene navigation rejects duplicate mark ids"):
    val addr = Addressable[CoreRef].address(CoreRef.Claim(ClaimId.unsafe("c1")))
    val id = VisualIdentity.of(addr, NarrativeLevel.Story, MarkId.unsafe("m1"))
    val r = VisualPrimitive.Region(id, extent(0, 1, 0, 0), RegionLabel.Summary("x"), None)
    assert(SceneNavigation.from(Vector(r, r)).isLeft)
    assertEquals(SceneNavigation.from(Vector(r)).map(_.marksFor(addr)), Right(Vector(id.mark)))

  test("configuration checksum is order-independent in shared state and sensitive to spec"):
    val a1 = Addressable[CoreRef].address(CoreRef.Claim(ClaimId.unsafe("a")))
    val a2 = Addressable[CoreRef].address(CoreRef.Claim(ClaimId.unsafe("b")))
    val s1 = CommonViewState.of(selection = Set(a1, a2)).toOption.get
    val s2 = CommonViewState.of(selection = Set(a2, a1)).toOption.get
    val story =
      AtlasSpec(ZoomLevel(NarrativeLevel.Story, SurfaceDetail.Hidden), ThreadPolicy.Selected)
    val scene =
      AtlasSpec(ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden), ThreadPolicy.Selected)
    val paragraph = AtlasSpec(
      ZoomLevel(NarrativeLevel.Story, SurfaceDetail.Hidden),
      ThreadPolicy.Selected,
      FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph)
    )
    assertEquals(
      AtlasCompiler.configurationChecksum(s1, story),
      AtlasCompiler.configurationChecksum(s2, story)
    )
    assertNotEquals(
      AtlasCompiler.configurationChecksum(s1, story),
      AtlasCompiler.configurationChecksum(s1, scene)
    )
    assertNotEquals(
      AtlasCompiler.configurationChecksum(s1, story),
      AtlasCompiler.configurationChecksum(s1, paragraph)
    )

  test("Atlas config v2 has a pinned field order and shared escaping rule"):
    val spec =
      AtlasSpec(ZoomLevel(NarrativeLevel.Story, SurfaceDetail.Hidden), ThreadPolicy.Selected)
    val rendering = AtlasCompiler.configurationRendering(CommonViewState.empty, spec)

    assertEquals(spec.featureScale, FeatureScale.Default)
    assertEquals(
      rendering,
      "rendering=atlas-compiler-config/v2|horizon=omniscient|focus=none|feature=none|" +
        "scale=surface:sentence|selection.count=0|relation.count=0|zoom.narrative=story|" +
        "zoom.surface=hidden|threads=selected|projection=discourse-atlas"
    )
    assertEquals(
      AtlasCompiler.configurationChecksum(CommonViewState.empty, spec),
      Checksum.ofText(rendering)
    )
