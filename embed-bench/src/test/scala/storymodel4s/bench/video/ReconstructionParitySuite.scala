package storymodel4s.bench.video

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.StorySource
import storymodel4s.recall.RecallSegmenter

/** Frozen pre-extraction decisions exercise actual fallback and fill, not just inequality. */
class ReconstructionParitySuite extends FunSuite:
  private def right[E, A](v: Either[E, A]): A =
    v.fold(e => throw new AssertionError(e.toString), identity)
  private val built = right(
    TimedSourceView.build(
      (0 until 4).toVector.map { i =>
        TimedSegment(
          i,
          s"segment $i text",
          None,
          group = Some(TimedSegment.Group(if i < 2 then 1 else 9, s"Scene ${i / 2}"))
        )
      },
      WorldOrderFixtures.syntheticLinear
    )
  )
  private val recall = RecallSegmenter.segment(
    right(
      StorySource.fromText(
        "He walked in. She replied. They left. A car drove off. The door shut.",
        Some("r")
      )
    )
  )
  private val refs = built.segmentByRef.toVector.sortBy(_._2.ordinal).map(_._1)
  private val rows = Vector(
    right(AlignmentRow.of(recall.ordered(0).id, Map(AlignState.Source(refs(2)) -> 1.0))),
    right(AlignmentRow.of(recall.ordered(1).id, Map(AlignState.Source(refs(0)) -> 0.2)))
  )

  test("default decoder matches frozen historical anchors and actually exercises fallback") {
    val old = FrozenMonotoneScene.decide(built, rows)
    val now = MonotoneScene.decide(built, rows)
    assertEquals(
      now.map(d => (d.anchor, d.scene, d.constrained)),
      old.map(d => (d.anchor, d.scene, d.constrained))
    )
    assertEquals(now.map(_.anchor), Vector(Some(refs(2)), Some(refs(0))))
    assertEquals(now.map(_.constrained), Vector(true, false))
  }

  test("requested historical fill matches exact old anchors and actually fills a noncandidate") {
    val fill: (Int, Int) => Option[SourceNodeRef] = (_, g) => if g == 9 then Some(refs(3)) else None
    val old = FrozenMonotoneScene.decide(built, rows, fill)
    val now = MonotoneScene.decide(built, rows, fill)
    assertEquals(
      now.map(d => (d.anchor, d.scene, d.constrained)),
      old.map(d => (d.anchor, d.scene, d.constrained))
    )
    assertEquals(now(1).anchor, Some(refs(3)))
    assert(now(1).constrained)
    assert(!rows(1).anchorMass.contains(refs(3)))
  }

  test("named Sherlock inference config equals frozen historical runner configuration") {
    assertEquals(
      StructuredReconstruction.Profile.sherlock().inference,
      RecallOrderControl.scaledConfig(HistoricalVideoDefaults.orderingScale)
    )
  }
