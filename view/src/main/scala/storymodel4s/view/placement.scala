package storymodel4s.view

import storymodel4s.core.*
import storymodel4s.story.*

/** Computes the primary-ancestor path without crossing evidence hidden by the active horizon. */
private[view] object VisibleAncestorChain:
  def from(
      member: NarrativeMember,
      primaryParent: Map[NarrativeMember, SegmentId],
      segmentVisible: SegmentId => Boolean
  ): Vector[Address] =
    val addressable = Addressable[StoryRef]

    def loop(
        current: Option[SegmentId],
        seen: Set[SegmentId],
        result: Vector[Address]
    ): Vector[Address] = current match
      case Some(parent) if !seen.contains(parent) && segmentVisible(parent) =>
        loop(
          primaryParent.get(NarrativeMember.Segment(parent)),
          seen + parent,
          result :+ addressable.address(StoryRef.Segment(parent))
        )
      case _ => result

    loop(primaryParent.get(member), Set.empty, Vector.empty)
