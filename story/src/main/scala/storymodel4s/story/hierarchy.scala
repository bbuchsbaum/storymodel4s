package storymodel4s.story

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** A candidate boundary after a surface unit at a hierarchy level. Retained whether or not the
  * selected hierarchy uses it, so rejected segmentations remain inspectable.
  */
final case class BoundaryBelief(
    afterUnit: SurfaceUnitId,
    level: Int,
    rawScore: Double,
    calibrated: Option[Probability],
    evidence: NonEmptyVector[Evidence]
)

/** The abstraction hierarchy: containment edges (primary nesting plus auxiliary arcs) and boundary
  * beliefs. Segment nodes themselves live in the graph.
  */
final case class NarrativeHierarchy(
    containment: Vector[ContainmentEdge],
    boundaryBeliefs: Vector[BoundaryBelief]
):
  lazy val primary: Vector[ContainmentEdge] = containment.filter(_.isPrimary)

  /** Primary children of each segment, in edge order. */
  lazy val childrenOf: Map[SegmentId, Vector[NarrativeMember]] =
    primary.groupMap(_.parent)(_.member)

  /** Primary parent of each member; a valid model has at most one. */
  lazy val primaryParent: Map[NarrativeMember, SegmentId] =
    primary.map(e => e.member -> e.parent).toMap

  /** Auxiliary memberships of each member. */
  lazy val auxiliaryOf: Map[NarrativeMember, Vector[ContainmentEdge]] =
    containment.filterNot(_.isPrimary).groupBy(_.member)

  /** Segments that are not a primary member of any segment. */
  def primaryRoots(segments: Iterable[SegmentId]): Vector[SegmentId] =
    segments.toVector.filterNot(s => primaryParent.contains(NarrativeMember.Segment(s))).sorted

  /** The unique primary root, when there is exactly one. */
  def primaryRoot(segments: Iterable[SegmentId]): Option[SegmentId] =
    primaryRoots(segments) match
      case Vector(r) => Some(r)
      case _         => None

  /** Primary ancestors from nearest to farthest; cycle-guarded. */
  def ancestors(member: NarrativeMember): Vector[SegmentId] =
    val out = Vector.newBuilder[SegmentId]
    var seen = Set.empty[SegmentId]
    var cur = primaryParent.get(member)
    while cur.isDefined && !seen.contains(cur.get) do
      val p = cur.get
      out += p
      seen += p
      cur = primaryParent.get(NarrativeMember.Segment(p))
    out.result()

  /** Level of a member: situations are 0, segments carry their own level. */
  def levelOf(member: NarrativeMember, segments: Map[SegmentId, SegmentNode]): Int =
    member match
      case NarrativeMember.Situation(_) => 0
      case NarrativeMember.Segment(id)  => segments.get(id).map(_.level).getOrElse(-1)

  /** All primary descendants of a segment (transitive), depth-first, cycle-guarded. */
  def descendants(segment: SegmentId): Vector[NarrativeMember] =
    val out = Vector.newBuilder[NarrativeMember]
    var seen = Set.empty[SegmentId]
    def go(s: SegmentId): Unit =
      if !seen.contains(s) then
        seen += s
        childrenOf.getOrElse(s, Vector.empty).foreach { m =>
          out += m
          m match
            case NarrativeMember.Segment(c) => go(c)
            case _                          => ()
        }
    go(segment)
    out.result()

  def descendantsAtLevel(
      segment: SegmentId,
      level: Int,
      segments: Map[SegmentId, SegmentNode]
  ): Vector[NarrativeMember] =
    descendants(segment).filter(m => levelOf(m, segments) == level)

  /** Atomic situations under a segment (transitive). */
  def situationsUnder(segment: SegmentId): Vector[SituationId] =
    descendants(segment).collect { case NarrativeMember.Situation(id) => id }

  /** Nearest primary parent of a situation. */
  def parentOfSituation(id: SituationId): Option[SegmentId] =
    primaryParent.get(NarrativeMember.Situation(id))

  def allMeta: Vector[ClaimMeta] = containment.map(_.meta)

object NarrativeHierarchy:
  val empty: NarrativeHierarchy = NarrativeHierarchy(Vector.empty, Vector.empty)
