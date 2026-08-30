package storymodel4s.align

import munit.FunSuite

import storymodel4s.core.SituationId

class TransitionFeaturesSuite extends FunSuite:
  import AnnaFixture.*

  test("unmeasured positions contribute no directional transition evidence") {
    val phantom = SourceNodeRef.Situation(SituationId.unsafe("unpositioned"))
    object Foil extends SourceView:
      val nodes: Vector[NodeSummary] = view.nodes
      def node(ref: SourceNodeRef): Option[NodeSummary] = view.node(ref)
      def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
        view.adjacency(layer).updated(phantom, Map.empty)
      def worldOrder: Option[Map[SourceNodeRef, Int]] = view.worldOrder
      def textLength: Int = view.textLength

    val placed = view.leaves.maxBy(node => view.relativePosition(node.ref)).ref
    val intoUnmeasured = TransitionFeatures.between(Foil, placed, phantom)
    val fromUnmeasured = TransitionFeatures.between(Foil, phantom, placed)

    for features <- Vector(intoUnmeasured, fromUnmeasured) do
      assert(!features.values.contains(TransitionKind.Backward))
      assert(!features.values.contains(TransitionKind.LongJump))
      assert(features.values.contains(TransitionKind.Stay), "unrelated features remain available")

    // A measured zero is evidence, not absence. Keeping both keys distinguishes a real adjacent
    // move from an unmeasurable one even though both contribute zero to the transition score.
    val leading = view.leaves.minBy(node => view.relativePosition(node.ref)).ref
    val measuredZero = TransitionFeatures.between(view, leading, leading)
    assertEquals(measuredZero.values.get(TransitionKind.Backward), Some(0.0))
    assertEquals(measuredZero.values.get(TransitionKind.LongJump), Some(0.0))
  }
