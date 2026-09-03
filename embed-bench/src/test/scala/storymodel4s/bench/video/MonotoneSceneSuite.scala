package storymodel4s.bench.video

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.StorySource
import storymodel4s.recall.RecallSegmenter

/** Courts for the scene-monotone decode.
  *
  * The claim that makes it safe is structural: the chosen scenes never go backwards, and no unit is
  * given an anchor the model did not already put mass on. If the second broke, the decode would be
  * inventing evidence rather than re-reading it.
  */
class MonotoneSceneSuite extends FunSuite:

  private val segments = (0 until 12).toVector.map { i =>
    TimedSegment(
      i,
      s"segment $i text",
      None,
      group = Some(TimedSegment.Group(i / 3 + 1, s"${i / 3 + 1}. Scene ${i / 3 + 1}"))
    )
  }
  private val built = TimedSourceView.build(segments)

  private val recall = RecallSegmenter.segment(
    StorySource
      .fromText("He walked in. She replied. They left. A car drove off. The door shut.", Some("r"))
      .fold(e => throw new IllegalArgumentException(e.message), identity)
  )

  private def row(unit: storymodel4s.recall.RecallUnitId, mass: Map[AlignState, Double]) =
    AlignmentRow.of(unit, mass).fold(e => throw new IllegalStateException(e.message), identity)

  private def sceneOf(ref: SourceNodeRef): Option[Int] =
    built.groupByRef
      .get(ref)
      .map(_.ordinal)
      .orElse(built.segmentByRef.get(ref).flatMap(_.group).map(_.ordinal))

  /** Deliberately anti-sequential evidence: each unit's heaviest candidate walks backwards, so a
    * per-unit argmax would emit a strictly decreasing scene sequence.
    */
  private def rows: Vector[AlignmentRow] =
    val leaves = built.nodeTexts.map(_._1).filter(r => built.segmentByRef.contains(r))
    recall.ordered.zipWithIndex.map { case (unit, i) =>
      val descending = leaves.reverse.drop(i).take(3)
      row(
        unit.id,
        descending.zipWithIndex.map { case (ref, k) =>
          (AlignState.Source(ref): AlignState) -> (1.0 - 0.2 * k)
        }.toMap
      )
    }

  test("the assigned scene sequence never goes backwards"):
    val decided = MonotoneScene.decide(built, rows)
    assert(decided.nonEmpty, "no decisions were produced")
    decided.map(_.scene).sliding(2).foreach {
      case Vector(a, b) => assert(b >= a, s"assigned scene went backwards: $a then $b")
      case _            => ()
    }

  test("a bound unit's anchor really is inside its assigned scene"):
    MonotoneScene.decide(built, rows).foreach { d =>
      if d.constrained then
        assertEquals(d.anchor.flatMap(sceneOf), Some(d.scene), "bound anchor left its scene")
    }

  test("the decode overrides the per-unit argmax on anti-sequential evidence"):
    val decided = MonotoneScene.decide(built, rows)
    val argmax = rows.flatMap(_.mapSource).flatMap(sceneOf)
    assert(
      argmax.sliding(2).exists { case Vector(a, b) => b < a; case _ => false },
      "fixture is not anti-sequential, so it cannot test the constraint"
    )
    assert(decided.exists(d => d.constrained), "no unit was bound by the constraint")

  test("every anchor is a node the model already put mass on"):
    val out = MonotoneScene.anchors(built, rows)
    out.zip(rows).foreach { case (anchor, row) =>
      anchor.foreach { ref =>
        assert(
          row.anchorMass.get(ref).exists(_ > 0.0),
          s"anchor $ref carried no posterior mass for this unit"
        )
      }
    }

  test("a unit with no source mass keeps its original absent anchor"):
    val empty = rows.map(r => row(r.unit, Map.empty))
    assertEquals(MonotoneScene.anchors(built, empty).flatten, Vector.empty)
