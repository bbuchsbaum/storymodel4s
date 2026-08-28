package storymodel4s.story

import storymodel4s.core.*

/** An alignable node: an atomic situation (level 0) or a segment (level ≥ 1). */
enum NarrativeNodeId:
  case Situation(id: SituationId)
  case Segment(id: SegmentId)

  def render: String = this match
    case Situation(id) => id.value
    case Segment(id)   => id.value

object NarrativeNodeId:
  given Ordering[NarrativeNodeId] = Ordering.by[NarrativeNodeId, (Int, String)] {
    case Situation(id) => (0, id.value)
    case Segment(id)   => (1, id.value)
  }

/** A sparse directed relation with nonnegative weights; absent pairs are zero. */
type SparseRelation = Map[(NarrativeNodeId, NarrativeNodeId), Double]

final case class ParticipantSummary(role: ParticipantRole, entity: EntityId, label: String)

/** The explicit source-to-recall-aligner contract (design record §31.7). Only a validated or
  * adjudicated model can provide one.
  */
trait AlignmentSource:
  /** All alignable nodes, situations first in discourse order, then segments by level and id. */
  def allNodes: Vector[NarrativeNodeId]

  /** Nodes whose hierarchy level is in `levels` (0 = situations). */
  def alignableNodes(levels: Set[Int]): Vector[NarrativeNodeId]
  def sourceSupport(target: NarrativeNodeId): Option[SpanSet]

  /** Discourse position: situations by order of first mention; segments by their first situation.
    */
  def discoursePosition(target: NarrativeNodeId): Option[Int]
  def relationMatrix(layer: RelationLayer): SparseRelation

  /** Member → parent with containment weight, for every containment edge. */
  def hierarchyMembership: SparseRelation
  def predicateOf(target: NarrativeNodeId): Option[Predicate]
  def participantsOf(target: NarrativeNodeId): Vector[ParticipantSummary]
  def contextOf(target: NarrativeNodeId): Option[ContextKind]
  def polarityOf(target: NarrativeNodeId): Option[Polarity]
  def modalityOf(target: NarrativeNodeId): Option[Modality]
  def levelOf(target: NarrativeNodeId): Int
  def parentOf(target: NarrativeNodeId): Option[SegmentId]
  def descriptionOf(target: NarrativeNodeId): Option[String]
  def entityLabel(id: EntityId): Option[String]

object AlignmentSource:
  def apply(model: StoryModel[ModelStatus.Validated]): AlignmentSource =
    new StoryAlignmentSource(model)

  def adjudicated(model: StoryModel[ModelStatus.Adjudicated]): AlignmentSource =
    new StoryAlignmentSource(model)

private[story] final class StoryAlignmentSource(model: StoryModel[?]) extends AlignmentSource:
  private val g = model.graph
  private val h = model.hierarchy

  private val segmentsSorted: Vector[SegmentId] =
    g.segments.values.toVector.sortBy(s => (s.level, s.id)).map(_.id)

  lazy val allNodes: Vector[NarrativeNodeId] =
    g.discourseOrder.map(NarrativeNodeId.Situation(_)) ++
      segmentsSorted.map(NarrativeNodeId.Segment(_))

  def alignableNodes(levels: Set[Int]): Vector[NarrativeNodeId] =
    allNodes.filter(n => levels.contains(levelOf(n)))

  def sourceSupport(target: NarrativeNodeId): Option[SpanSet] = target match
    case NarrativeNodeId.Situation(id) => g.situations.get(id).map(_.support)
    case NarrativeNodeId.Segment(id)   => g.segments.get(id).map(_.support)

  def discoursePosition(target: NarrativeNodeId): Option[Int] = target match
    case NarrativeNodeId.Situation(id) => g.discoursePosition.get(id)
    case NarrativeNodeId.Segment(id)   =>
      h.situationsUnder(id).flatMap(g.discoursePosition.get).minOption

  private def sit(id: SituationId): NarrativeNodeId = NarrativeNodeId.Situation(id)

  def relationMatrix(layer: RelationLayer): SparseRelation = layer match
    case RelationLayer.DiscourseSuccession =>
      g.discourseOrder.zip(g.discourseOrder.drop(1)).map((a, b) => (sit(a), sit(b)) -> 1.0).toMap
    case RelationLayer.WorldTime =>
      g.relations.temporal
        .filter(_.relation.isStrictPrecedence)
        .map(e => (sit(e.from), sit(e.to)) -> 1.0)
        .toMap
    case RelationLayer.Causal =>
      g.relations.causal
        .map(e => (sit(e.cause), sit(e.effect)) -> weightOf(e.meta))
        .groupMapReduce(_._1)(_._2)(math.max)
    case RelationLayer.Goal =>
      g.relations.goals.map(e => (sit(e.from), sit(e.to)) -> 1.0).toMap
    case RelationLayer.StateChange =>
      g.relations.stateChanges.map(e => (sit(e.event), sit(e.state)) -> 1.0).toMap
    case RelationLayer.Reference =>
      g.relations.references.map(e => (sit(e.from), sit(e.to)) -> 1.0).toMap
    case RelationLayer.Hierarchy        => hierarchyMembership
    case RelationLayer.Participant      => Map.empty
    case RelationLayer.Semantic         => Map.empty
    case RelationLayer.EntityContinuity =>
      // Jaccard overlap of participant sets between situations that share at least one entity;
      // built from the entity index so cost is linear in participation, not quadratic in nodes.
      val pairs = for
        (_, sits) <- g.situationsByEntity.toVector
        a <- sits
        b <- sits
        if a != b
      yield (a, b)
      pairs.distinct.map { (a, b) =>
        val ea = g.entitiesOf(a)
        val eb = g.entitiesOf(b)
        (sit(a), sit(b)) -> ea.intersect(eb).size.toDouble / ea.union(eb).size
      }.toMap

  private def weightOf(meta: ClaimMeta): Double =
    meta.credence.calibrated.map(_.value).getOrElse(1.0)

  lazy val hierarchyMembership: SparseRelation =
    h.containment.map { e =>
      val m = e.member match
        case NarrativeMember.Situation(id) => sit(id)
        case NarrativeMember.Segment(id)   => NarrativeNodeId.Segment(id)
      (m, NarrativeNodeId.Segment(e.parent)) -> e.weight
    }.toMap

  def predicateOf(target: NarrativeNodeId): Option[Predicate] = target match
    case NarrativeNodeId.Situation(id) => g.situations.get(id).map(_.predicate)
    case _                             => None

  def participantsOf(target: NarrativeNodeId): Vector[ParticipantSummary] = target match
    case NarrativeNodeId.Situation(id) =>
      g.participantsOf
        .getOrElse(id, Vector.empty)
        .map((r, e) => ParticipantSummary(r, e, entityLabel(e).getOrElse(e.value)))
    case NarrativeNodeId.Segment(id) =>
      h.situationsUnder(id)
        .flatMap(s => g.participantsOf.getOrElse(s, Vector.empty))
        .distinct
        .map((r, e) => ParticipantSummary(r, e, entityLabel(e).getOrElse(e.value)))

  def contextOf(target: NarrativeNodeId): Option[ContextKind] = target match
    case NarrativeNodeId.Situation(id) =>
      g.situations.get(id).flatMap(s => g.contexts.get(s.context)).map(_.kind)
    case _ => None

  def polarityOf(target: NarrativeNodeId): Option[Polarity] = target match
    case NarrativeNodeId.Situation(id) => g.situations.get(id).map(_.polarity)
    case _                             => None

  def modalityOf(target: NarrativeNodeId): Option[Modality] = target match
    case NarrativeNodeId.Situation(id) => g.situations.get(id).map(_.modality)
    case _                             => None

  def levelOf(target: NarrativeNodeId): Int = target match
    case NarrativeNodeId.Situation(_) => 0
    case NarrativeNodeId.Segment(id)  => g.segments.get(id).map(_.level).getOrElse(-1)

  def parentOf(target: NarrativeNodeId): Option[SegmentId] = target match
    case NarrativeNodeId.Situation(id) => h.primaryParent.get(NarrativeMember.Situation(id))
    case NarrativeNodeId.Segment(id)   => h.primaryParent.get(NarrativeMember.Segment(id))

  def descriptionOf(target: NarrativeNodeId): Option[String] = target match
    case NarrativeNodeId.Situation(id) => g.situations.get(id).map(_.description)
    case NarrativeNodeId.Segment(id)   => g.segments.get(id).map(_.summary.value)

  def entityLabel(id: EntityId): Option[String] = g.entities.get(id).map(_.label.value)
