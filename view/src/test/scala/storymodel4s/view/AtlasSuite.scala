package storymodel4s.view

import munit.FunSuite
import storymodel4s.core.*

/** Fixture-free Atlas contract checks; the WOG laws live in fixtures (WarOfTheGhostsAtlasSuite). */
class AtlasSuite extends FunSuite:
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
    val a = Extent.unsafe(0, 10, 0, 0)
    val b = Extent.unsafe(3, 5, 0, 0)
    assert(a.contains(b) && !b.contains(a))
    assertEquals(a.hull(Extent.unsafe(20, 30, 2, 2)), Extent.unsafe(0, 30, 0, 2))
    assert(a.containsX(9) && !a.containsX(10))

  test("scene navigation rejects duplicate mark ids"):
    val addr = Addressable[CoreRef].address(CoreRef.Claim(ClaimId.unsafe("c1")))
    val id = VisualIdentity(addr, NarrativeLevel.Story, MarkId.unsafe("m1"))
    val r = VisualPrimitive.Region(id, Extent.unsafe(0, 1, 0, 0), "x", None)
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
    assertEquals(
      AtlasCompiler.configurationChecksum(s1, story),
      AtlasCompiler.configurationChecksum(s2, story)
    )
    assertNotEquals(
      AtlasCompiler.configurationChecksum(s1, story),
      AtlasCompiler.configurationChecksum(s1, scene)
    )
