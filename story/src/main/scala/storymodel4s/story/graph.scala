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

  /** Situations in discourse order: by the start of their earliest supporting span, then by the end
    * of that span, then by id. Uses the hull of the support so a situation whose first stored span
    * is a later one is not misplaced.
    */
  lazy val discourseOrder: Vector[SituationId] =
    situations.values.toVector
      .sortBy { s =>
        val m = s.support.minSpan
        (m.start, m.endExclusive, s.id)
      }
      .map(_.id)

  lazy val discoursePosition: Map[SituationId, Int] = discourseOrder.zipWithIndex.toMap

  lazy val temporalOut: Map[ContextId, Map[SituationId, Vector[TemporalEdge]]] =
    relations.temporal.groupBy(_.context).view.mapValues(_.groupBy(_.from)).toMap

  lazy val temporalIn: Map[ContextId, Map[SituationId, Vector[TemporalEdge]]] =
    relations.temporal.groupBy(_.context).view.mapValues(_.groupBy(_.to)).toMap

  /** Temporal edges scoped to exactly `context`. Story-world chronology of a context is built only
    * from these; an edge scoped to a child context (a character's belief, a speech) never
    * contributes to its parent's chronology (design record §113; plan decision §3 Part 2/5).
    */
  def temporalEdgesIn(context: ContextId): Vector[TemporalEdge] =
    relations.temporal.filter(_.context == context)

  /** The narrated-world root context, when the model has exactly one root. */
  lazy val rootContext: Option[ContextId] = contextRoots match
    case Vector(r) => Some(r)
    case _         => None

  /** Groups each entity is a direct member of (`MemberOf` edges), and the transitive closure. */
  lazy val memberOf: Map[EntityId, Vector[EntityId]] =
    relations.entityRelations
      .filter(_.relation == EntityRelation.MemberOf)
      .groupMap(_.from)(_.to)

  lazy val membersOf: Map[EntityId, Vector[EntityId]] =
    relations.entityRelations
      .filter(_.relation == EntityRelation.MemberOf)
      .groupMap(_.to)(_.from)

  /** All groups an entity belongs to, transitively; cycle-guarded. */
  def groupsOf(e: EntityId): Set[EntityId] =
    var seen = Set.empty[EntityId]
    var frontier = memberOf.getOrElse(e, Vector.empty).toSet
    while frontier.nonEmpty do
      seen ++= frontier
      frontier = frontier.flatMap(g => memberOf.getOrElse(g, Vector.empty)) -- seen
    seen

  /** Situations each entity participates in, in discourse order, including situations of any group
    * the entity is (transitively) a member of.
    */
  lazy val situationsByEntity: Map[EntityId, Vector[SituationId]] =
    val direct = relations.participants.groupMap(_.entity)(_.situation)
    val all = entities.keys.toVector.map { e =>
      val own = direct.getOrElse(e, Vector.empty)
      val viaGroups = groupsOf(e).toVector.flatMap(g => direct.getOrElse(g, Vector.empty))
      e -> (own ++ viaGroups).distinct
        .sortBy(id => discoursePosition.getOrElse(id, Int.MaxValue))
    }
    all.filter(_._2.nonEmpty).toMap

  /** Role/entity pairs of each situation, in edge order. */
  lazy val participantsOf: Map[SituationId, Vector[(ParticipantRole, EntityId)]] =
    relations.participants.groupMap(_.situation)(p => (p.role, p.entity))

  def entitiesOf(id: SituationId): Set[EntityId] =
    participantsOf.getOrElse(id, Vector.empty).map(_._2).toSet

  /** Participating entities plus the groups they belong to and, for group participants, their
    * members. This is the set used for entity continuity so that "the two young men" and "the one
    * who went" are continuous through a `MemberOf` edge.
    */
  def expandedEntitiesOf(id: SituationId): Set[EntityId] =
    val base = entitiesOf(id)
    base ++ base.flatMap(groupsOf) ++ base.flatMap(g => membersOf.getOrElse(g, Vector.empty))

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

  /** Nearest common ancestor-or-self of two contexts, if they share one. */
  def commonContext(a: ContextId, b: ContextId): Option[ContextId] =
    val chainA = a +: contextAncestors(a)
    val setB = (b +: contextAncestors(b)).toSet
    chainA.find(setB.contains)

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

  /** Every inline claim of the graph: node claims, resolved-value claims, scoped attributes, and
    * all relation layers.
    */
  def allMeta: Vector[ClaimMeta] =
    entities.values.toVector.flatMap(e => e.meta +: e.label.meta +: e.attributes.map(_.meta)) ++
      situations.values.toVector.map(_.meta) ++
      segments.values.toVector.map(_.summary.meta) ++
      contexts.values.toVector.map(_.meta) ++
      relations.allMeta

object NarrativeGraph:
  val empty: NarrativeGraph =
    NarrativeGraph(Map.empty, Map.empty, Map.empty, Map.empty, RelationLayers.empty)
