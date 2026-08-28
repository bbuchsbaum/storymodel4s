package storymodel4s.story

import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}

/** The typed multiplex narrative graph: node maps plus sparse relation layers, with lazily built
  * indexes. No dense adjacency is ever materialized.
  */
final case class NarrativeGraph(
    entities: Map[EntityId, EntityNode],
    situations: Map[SituationId, SituationNode],
    segments: Map[SegmentId, SegmentNode],
    contexts: Map[ContextId, ContextFrame],
    relations: RelationLayers
):

  /** Situations in discourse order: by the start of their first supporting span, then by id. */
  lazy val discourseOrder: Vector[SituationId] =
    situations.values.toVector
      .sortBy(s => (s.support.refs.head.span.start, s.support.refs.head.span.endExclusive, s.id))
      .map(_.id)

  lazy val discoursePosition: Map[SituationId, Int] = discourseOrder.zipWithIndex.toMap

  lazy val temporalOut: Map[ContextId, Map[SituationId, Vector[TemporalEdge]]] =
    relations.temporal.groupBy(_.context).view.mapValues(_.groupBy(_.from)).toMap

  lazy val temporalIn: Map[ContextId, Map[SituationId, Vector[TemporalEdge]]] =
    relations.temporal.groupBy(_.context).view.mapValues(_.groupBy(_.to)).toMap

  /** Situations each entity participates in, in discourse order. */
  lazy val situationsByEntity: Map[EntityId, Vector[SituationId]] =
    relations.participants
      .groupMap(_.entity)(_.situation)
      .view
      .mapValues(v => v.distinct.sortBy(id => discoursePosition.getOrElse(id, Int.MaxValue)))
      .toMap

  /** Role/entity pairs of each situation, in edge order. */
  lazy val participantsOf: Map[SituationId, Vector[(ParticipantRole, EntityId)]] =
    relations.participants.groupMap(_.situation)(p => (p.role, p.entity))

  def entitiesOf(id: SituationId): Set[EntityId] =
    participantsOf.getOrElse(id, Vector.empty).map(_._2).toSet

  lazy val situationsByContext: Map[ContextId, Vector[SituationId]] =
    situations.values.toVector
      .groupMap(_.context)(_.id)
      .view
      .mapValues(_.sortBy(id => discoursePosition.getOrElse(id, Int.MaxValue)))
      .toMap

  lazy val contextChildren: Map[ContextId, Vector[ContextId]] =
    contexts.values.toVector.flatMap(c => c.parent.map(_ -> c.id)).groupMap(_._1)(_._2)

  /** Root contexts (no parent). A valid model has exactly one, of kind `NarratedWorld`. */
  lazy val contextRoots: Vector[ContextId] =
    contexts.values.toVector.filter(_.parent.isEmpty).map(_.id).sorted

  /** Ancestors of a context from nearest to farthest, excluding itself; cycle-guarded. */
  def contextAncestors(id: ContextId): Vector[ContextId] =
    val out = Vector.newBuilder[ContextId]
    var seen = Set(id)
    var cur = contexts.get(id).flatMap(_.parent)
    while cur.isDefined && !seen.contains(cur.get) do
      val c = cur.get
      out += c
      seen += c
      cur = contexts.get(c).flatMap(_.parent)
    out.result()

  /** True when `descendant` equals `ancestor` or lies below it in the context tree. */
  def contextWithin(descendant: ContextId, ancestor: ContextId): Boolean =
    descendant == ancestor || contextAncestors(descendant).contains(ancestor)

  lazy val entityByMention: Map[MentionId[EntityK], EntityId] =
    entities.values.toVector.flatMap(e => e.mentions.toVector.map(_ -> e.id)).toMap

  lazy val situationByMention: Map[MentionId[SituationK], SituationId] =
    situations.values.toVector.flatMap(s => s.mentions.toVector.map(_ -> s.id)).toMap

  lazy val causalOut: Map[SituationId, Vector[CausalEdge]] = relations.causal.groupBy(_.cause)
  lazy val causalIn: Map[SituationId, Vector[CausalEdge]] = relations.causal.groupBy(_.effect)
  lazy val referencesOut: Map[SituationId, Vector[ReferenceEdge]] =
    relations.references.groupBy(_.from)
  lazy val referencesIn: Map[SituationId, Vector[ReferenceEdge]] =
    relations.references.groupBy(_.to)
  lazy val stateChangesByEvent: Map[SituationId, Vector[StateChangeEdge]] =
    relations.stateChanges.groupBy(_.event)
  lazy val stateChangesByState: Map[SituationId, Vector[StateChangeEdge]] =
    relations.stateChanges.groupBy(_.state)
  lazy val goalsOut: Map[SituationId, Vector[GoalEdge]] = relations.goals.groupBy(_.from)

  /** Situations whose context is (or is within) the given context. */
  def situationsWithin(context: ContextId): Vector[SituationId] =
    discourseOrder.filter(id => situations.get(id).exists(s => contextWithin(s.context, context)))

  def allMeta: Vector[ClaimMeta] =
    entities.values.toVector.flatMap(e => e.meta +: e.attributes.map(_.meta)) ++
      situations.values.toVector.map(_.meta) ++
      contexts.values.toVector.map(_.meta) ++
      relations.allMeta

object NarrativeGraph:
  val empty: NarrativeGraph =
    NarrativeGraph(Map.empty, Map.empty, Map.empty, Map.empty, RelationLayers.empty)
