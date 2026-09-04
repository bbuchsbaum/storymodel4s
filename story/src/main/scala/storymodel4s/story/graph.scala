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
      segments.values.toVector.flatMap(s => s.meta +: s.summary.stated.map(_.meta).toVector) ++
      contexts.values.toVector.map(_.meta) ++
      relations.allMeta

  // ---------------------------------------------------------------------------------------------
  // Read API for views and inspectors (ADR 0002 §4): text -> references, reference -> evidence.
  // ---------------------------------------------------------------------------------------------

  /** Exact evidence support of a reference: node support for nodes and contexts, and the claim's
    * cited spans for relation edges. `None` when the reference is unknown to this graph or when an
    * edge's claim cites no spans (an inferred relation with upstream-only evidence).
    *
    * Why edges use claim spans: a relation is "supported by" the words its claim cites, not by the
    * hull of its endpoints; the evidence law (ADR 0002 V-E3) forbids marking text an edge does not
    * cite.
    */
  def supporting(ref: StoryRef): Option[SpanSet] = ref match
    case StoryRef.Situation(id)           => situations.get(id).map(_.support)
    case StoryRef.Segment(id)             => segments.get(id).map(_.support)
    case StoryRef.Entity(id)              => entities.get(id).map(_.support)
    case StoryRef.Context(id)             => contexts.get(id).map(_.support)
    case StoryRef.Participant(s, role, e) =>
      relations.participants
        .find(p => p.situation == s && p.role == role && p.entity == e)
        .flatMap(_.meta.spans)
    case StoryRef.Temporal(f, r, t, c) =>
      relations.temporal
        .find(x => x.from == f && x.relation == r && x.to == t && x.context == c)
        .flatMap(_.meta.spans)
    case StoryRef.Causal(f, r, t) =>
      relations.causal
        .find(x => x.cause == f && x.relation == r && x.effect == t)
        .flatMap(_.meta.spans)
    case StoryRef.Goal(f, r, t) =>
      relations.goals.find(x => x.from == f && x.relation == r && x.to == t).flatMap(_.meta.spans)
    case StoryRef.StateChange(f, r, t) =>
      relations.stateChanges
        .find(x => x.event == f && x.change == r && x.state == t)
        .flatMap(_.meta.spans)
    case StoryRef.Reference(f, r, t) =>
      relations.references
        .find(x => x.from == f && x.mode == r && x.to == t)
        .flatMap(_.meta.spans)
    case StoryRef.EntityLink(f, r, t) =>
      relations.entityRelations
        .find(x => x.from == f && x.relation == r && x.to == t)
        .flatMap(_.meta.spans)
    case StoryRef.Containment(_, _, _) =>
      // Containment lives in NarrativeHierarchy, not in the graph; resolved by the model.
      None

  /** Every reference of this graph whose evidence support overlaps `span`: nodes and contexts by
    * their support, relation edges by the spans their claims cite. Deterministic order: by address
    * rendering. This is the "select text -> every supported claim" query.
    */
  def covering(span: TextSpan): Vector[StoryRef] =
    def hits(s: SpanSet): Boolean = s.spans.exists(_.overlaps(span))
    val nodes: Vector[StoryRef] =
      situations.values.toVector.filter(s => hits(s.support)).map(s => StoryRef.Situation(s.id)) ++
        segments.values.toVector.filter(s => hits(s.support)).map(s => StoryRef.Segment(s.id)) ++
        entities.values.toVector.filter(e => hits(e.support)).map(e => StoryRef.Entity(e.id)) ++
        contexts.values.toVector.filter(c => hits(c.support)).map(c => StoryRef.Context(c.id))
    def cited(meta: ClaimMeta): Boolean = meta.spans.exists(hits)
    val edges: Vector[StoryRef] =
      relations.participants
        .filter(p => cited(p.meta))
        .map(p => StoryRef.Participant(p.situation, p.role, p.entity)) ++
        relations.temporal
          .filter(t => cited(t.meta))
          .map(t => StoryRef.Temporal(t.from, t.relation, t.to, t.context)) ++
        relations.causal
          .filter(c => cited(c.meta))
          .map(c => StoryRef.Causal(c.cause, c.relation, c.effect)) ++
        relations.goals
          .filter(g => cited(g.meta))
          .map(g => StoryRef.Goal(g.from, g.relation, g.to)) ++
        relations.stateChanges
          .filter(x => cited(x.meta))
          .map(x => StoryRef.StateChange(x.event, x.change, x.state)) ++
        relations.references
          .filter(r => cited(r.meta))
          .map(r => StoryRef.Reference(r.from, r.mode, r.to)) ++
        relations.entityRelations
          .filter(e => cited(e.meta))
          .map(e => StoryRef.EntityLink(e.from, e.relation, e.to))
    val ev = Addressable[StoryRef]
    (nodes ++ edges).distinct.sortBy(r => ev.address(r).render)

  /** Situations whose support overlaps `span`, in discourse order. */
  def situationsCovering(span: TextSpan): Vector[SituationId] =
    discourseOrder.filter(id => situations.get(id).exists(_.support.spans.exists(_.overlaps(span))))

object NarrativeGraph:
  val empty: NarrativeGraph =
    NarrativeGraph(Map.empty, Map.empty, Map.empty, Map.empty, RelationLayers.empty)
