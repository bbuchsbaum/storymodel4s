package storymodel4s.bench.video

import java.time.LocalDate

import munit.FunSuite
import storymodel4s.align.{RelationLayer, SourceNodeRef, TransitionFeatures, TransitionKind}

/** Courts for the declared world clock (ADR 0013). The builder no longer manufactures a world order
  * from presentation order: `Unknown` leaves the world-time layer and the order absent together,
  * `SameAsPresentation` reproduces the presentation-derived clock under a named witness, and
  * `Explicit` takes both from the supplied rank alone, ties included.
  */
class WorldOrderInputSuite extends FunSuite:

  private val groupA = TimedSegment.Group(1, "1. Before")
  private val groupB = TimedSegment.Group(2, "2. After")
  private val segments = Vector(
    TimedSegment(1, "A man walks through rain.", None, Some(groupA)),
    TimedSegment(2, "He finds a red door.", None, Some(groupA)),
    TimedSegment(3, "The door opens.", None, Some(groupB)),
    TimedSegment(4, "He steps inside.", None, Some(groupB))
  )

  private def explicit(rank: Map[Int, Int]): WorldOrderInput =
    WorldOrderInput.Explicit(rank, WorldOrderFixtures.handRank)

  private def built(declared: WorldOrderInput): TimedSourceView.Built =
    TimedSourceView.build(segments, declared).fold(e => fail(e.message), identity)

  private def leaf(b: TimedSourceView.Built, ordinal: Int): SourceNodeRef =
    b.segmentByRef.collectFirst { case (r, s) if s.ordinal == ordinal => r }.get

  private def group(b: TimedSourceView.Built, ordinal: Int): SourceNodeRef =
    b.groupByRef.collectFirst { case (r, g) if g.ordinal == ordinal => r }.get

  private def edgesOf(
      b: TimedSourceView.Built,
      layer: RelationLayer
  ): Set[(SourceNodeRef, SourceNodeRef)] =
    b.view.adjacency(layer).toVector.flatMap((a, row) => row.keys.map(a -> _)).toSet

  private def worldEdges(b: TimedSourceView.Built) = edgesOf(b, RelationLayer.WorldTime)

  private def order(b: TimedSourceView.Built): Map[SourceNodeRef, Int] =
    b.view.worldOrder.getOrElse(fail("the view lost its world order; this case is not the foil"))

  test("Unknown drops the world-time layer and the world order together") {
    val b = built(WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied))
    assertEquals(b.view.worldOrder, None)
    assertEquals(
      b.view.adjacency(RelationLayer.WorldTime),
      Map.empty[SourceNodeRef, Map[SourceNodeRef, Double]]
    )
    // the discourse clock is untouched: absence of one clock is not absence of both
    assertEquals(
      edgesOf(b, RelationLayer.DiscourseSuccession),
      Set(
        leaf(b, 1) -> leaf(b, 2),
        leaf(b, 2) -> leaf(b, 3),
        leaf(b, 3) -> leaf(b, 4),
        group(b, 1) -> group(b, 2)
      )
    )
    assertEquals(b.worldOrder, WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied))
  }

  test(
    "SameAsPresentation makes the world-time layer the discourse succession and the order the presentation order"
  ) {
    val b = built(WorldOrderFixtures.syntheticLinear)
    assertEquals(worldEdges(b), edgesOf(b, RelationLayer.DiscourseSuccession))
    assertEquals(order(b)(leaf(b, 1)), 0)
    assertEquals(order(b)(leaf(b, 4)), 3)
    assertEquals(order(b)(group(b, 1)), 0)
    assertEquals(order(b)(group(b, 2)), 2)
    assertEquals(b.worldOrder, WorldOrderFixtures.syntheticLinear)
  }

  test("an explicit identity rank reproduces the presentation clock without consulting it") {
    val identity = built(explicit(Map(1 -> 0, 2 -> 1, 3 -> 2, 4 -> 3)))
    val declared = built(WorldOrderFixtures.syntheticLinear)
    assertEquals(worldEdges(identity), worldEdges(declared))
    assertEquals(identity.view.worldOrder, declared.view.worldOrder)
  }

  test("a reversed explicit rank runs the world clock against the discourse clock") {
    val b = built(explicit(Map(1 -> 3, 2 -> 2, 3 -> 1, 4 -> 0)))
    assertEquals(
      worldEdges(b),
      Set(
        leaf(b, 4) -> leaf(b, 3),
        leaf(b, 3) -> leaf(b, 2),
        leaf(b, 2) -> leaf(b, 1),
        group(b, 2) -> group(b, 1)
      )
    )
    assert(order(b)(leaf(b, 1)) > order(b)(leaf(b, 2)), "world order must be reversed")
    val forward = TransitionFeatures.between(b.view, leaf(b, 3), leaf(b, 4)).values
    val backward = TransitionFeatures.between(b.view, leaf(b, 4), leaf(b, 3)).values
    assertEquals(forward.getOrElse(TransitionKind.DiscourseSuccessor, 0.0), 1.0)
    assertEquals(forward.getOrElse(TransitionKind.WorldTimeSuccessor, 0.0), 0.0)
    assertEquals(backward.getOrElse(TransitionKind.DiscourseSuccessor, 0.0), 0.0)
    assertEquals(backward.getOrElse(TransitionKind.WorldTimeSuccessor, 0.0), 1.0)
  }

  test("a tie connects across ranks only, never within one, and never by presentation order") {
    val b = built(explicit(Map(1 -> 0, 2 -> 0, 3 -> 1, 4 -> 1)))
    assertEquals(
      worldEdges(b),
      Set(
        leaf(b, 1) -> leaf(b, 3),
        leaf(b, 1) -> leaf(b, 4),
        leaf(b, 2) -> leaf(b, 3),
        leaf(b, 2) -> leaf(b, 4),
        group(b, 1) -> group(b, 2)
      )
    )
    assertEquals(order(b)(leaf(b, 1)), order(b)(leaf(b, 2)))
  }

  test("a group sits at its earliest member's rank") {
    val b = built(explicit(Map(1 -> 5, 2 -> 1, 3 -> 2, 4 -> 3)))
    assertEquals(order(b)(group(b, 1)), 1)
    assertEquals(order(b)(group(b, 2)), 2)
    assertEquals(
      worldEdges(b),
      Set(
        leaf(b, 2) -> leaf(b, 3),
        leaf(b, 3) -> leaf(b, 4),
        leaf(b, 4) -> leaf(b, 1),
        group(b, 1) -> group(b, 2)
      )
    )
  }

  test("an explicit rank that misses a leaf, names a foreign leaf, or is empty is refused") {
    assertEquals(
      TimedSourceView.build(segments, explicit(Map(1 -> 0, 2 -> 1, 3 -> 2))).left.toOption,
      Some(WorldOrderRefusal.RankMissingLeaves(Vector(4)))
    )
    assertEquals(
      TimedSourceView
        .build(segments, explicit(Map(1 -> 0, 2 -> 1, 3 -> 2, 4 -> 3, 9 -> 0)))
        .left
        .toOption,
      Some(WorldOrderRefusal.RankNamesUnknownLeaves(Vector(9)))
    )
    assertEquals(
      TimedSourceView.build(segments, explicit(Map.empty)).left.toOption,
      Some(WorldOrderRefusal.EmptyRank)
    )
  }

  test("the run provenance carries the declaration, so two clocks are two derivations") {
    val declared = built(WorldOrderFixtures.syntheticLinear)
    val unknown = built(WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied))
    val run = RecallOrderControl.LadderRun
      .of(RecallOrderControl.Ladder.full, 1.5)
      .fold(e => fail(e), identity)
    val a = RecallToVideo.provenanceConfig("lexical", 8, false, run, declared.worldOrder)
    val b = RecallToVideo.provenanceConfig("lexical", 8, false, run, unknown.worldOrder)
    assert(a.contains(s"worldOrder=${declared.worldOrder.render}"), a)
    assert(b.contains("worldOrder=unknown(NotSupplied)"), b)
    assertNotEquals(a, b)
  }

  test("render is canonical, carries every witness, and separates the three declarations") {
    val a = explicit(Map(1 -> 0, 2 -> 1)).render
    val b = explicit(Map(2 -> 1, 1 -> 0)).render
    assertEquals(a, b)
    assert(a.contains("rank written by hand in the suite") && a.contains("2026-09-04"), a)
    val witness = WorldOrderWitness("owner", "linear cut", LocalDate.of(2026, 9, 4))
    val same = WorldOrderInput.SameAsPresentation(witness).render
    val unknown = WorldOrderInput.Unknown(WorldOrderAbsence.EditionNonlinear).render
    assertEquals(unknown, "unknown(EditionNonlinear)")
    assert(
      same.contains("owner") && same.contains("linear cut") && same.contains("2026-09-04"),
      same
    )
    assertEquals(Set(a, same, unknown).size, 3)
    assertNotEquals(a, explicit(Map(1 -> 1, 2 -> 0)).render)
    assertNotEquals(a, WorldOrderInput.Explicit(Map(1 -> 0, 2 -> 1), witness).render)
  }
