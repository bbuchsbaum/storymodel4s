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

/** One edge of a relation view with its status kept: the matrix weight is a projection of this. */
final case class RelationEdgeView(
    from: NarrativeNodeId,
    to: NarrativeNodeId,
    weight: Double,
    status: EpistemicStatus,
    meta: ClaimMeta
)

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

  /** The relation view of the narrated world. `WorldTime` is built only from temporal edges scoped
    * to the root context; chronology asserted inside a speech or belief never leaks into it.
    */
  def relationMatrix(layer: RelationLayer): SparseRelation

  /** The relation view scoped to one context: `WorldTime` uses edges whose context is exactly
    * `context`; other layers keep edges whose endpoints both lie within `context`.
    */
  def relationMatrixIn(layer: RelationLayer, context: ContextId): SparseRelation

  /** Stored edges of a layer with their epistemic status and claim; derived layers
    * (`DiscourseSuccession`, `EntityContinuity`, `Hierarchy`) carry `StructurallyDerived`.
    */
  def relationEdges(layer: RelationLayer): Vector[RelationEdgeView]

  /** Member → parent with containment weight, for every containment edge. */
  def hierarchyMembership: SparseRelation
  def predicateOf(target: NarrativeNodeId): Option[Predicate]
  def participantsOf(target: NarrativeNodeId): Vector[ParticipantSummary]
  def contextOf(target: NarrativeNodeId): Option[ContextKind]
  def contextIdOf(target: NarrativeNodeId): Option[ContextId]
  def rootContext: Option[ContextId]
  def polarityOf(target: NarrativeNodeId): Option[Polarity]
  def modalityOf(target: NarrativeNodeId): Option[Modality]
  def levelOf(target: NarrativeNodeId): Int
  def parentOf(target: NarrativeNodeId): Option[SegmentId]
  def descriptionOf(target: NarrativeNodeId): Option[String]
  def entityLabel(id: EntityId): Option[String]

object AlignmentSource:
  /** The rule that determines every derived-view claim from the model it reads. */
  val DerivedViewRule: RuleId = RuleId.unsafe("derived-view/v1")

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

  val rootContext: Option[ContextId] = g.rootContext

  private val derivedMeta: ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(ContentAddress.of("derived-view", model.source.canonicalChecksum.hex)),
      EpistemicStatus.StructurallyDerived,
      Credence.unsafeDetermined(AlignmentSource.DerivedViewRule),
      cats.data.NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe(
            ContentAddress.of("derived-view-ev", model.source.canonicalChecksum.hex)
          ),
          None,
          Set.empty,
          DiscourseTrajectory.DeriveFingerprint,
          DiscourseTrajectory.DeriveStage
        )
      ),
      Provenance.deterministic(model.schemaVersion, Checksum.ofText("derived-view"))
    )

  private def derived(from: NarrativeNodeId, to: NarrativeNodeId, w: Double): RelationEdgeView =
    RelationEdgeView(from, to, w, EpistemicStatus.StructurallyDerived, derivedMeta)

  private def within(s: SituationId, context: ContextId): Boolean =
    g.situations.get(s).exists(x => g.contextWithin(x.context, context))

  /** Edges of a layer, optionally restricted to a context. `WorldTime` is restricted by the edge's
    * own context; every other stored layer by the endpoints' contexts.
    */
  private def edgesOf(layer: RelationLayer, context: Option[ContextId]): Vector[RelationEdgeView] =
    def keep(a: SituationId, b: SituationId): Boolean =
      context.forall(c => within(a, c) && within(b, c))
    layer match
      case RelationLayer.WorldTime =>
        val scope = context.orElse(g.rootContext)
        g.relations.temporal
          .filter(e => e.relation.isStrictPrecedence && scope.contains(e.context))
          .map(e => RelationEdgeView(sit(e.from), sit(e.to), 1.0, e.meta.status, e.meta))
      case RelationLayer.Causal =>
        g.relations.causal
          .filter(e => keep(e.cause, e.effect))
          .map(e =>
            RelationEdgeView(
              sit(e.cause),
              sit(e.effect),
              StatusWeight.of(e.meta),
              e.meta.status,
              e.meta
            )
          )
      case RelationLayer.Goal =>
        g.relations.goals
          .filter(e => keep(e.from, e.to))
          .map(e =>
            RelationEdgeView(sit(e.from), sit(e.to), StatusWeight.of(e.meta), e.meta.status, e.meta)
          )
      case RelationLayer.StateChange =>
        g.relations.stateChanges
          .filter(e => keep(e.event, e.state))
          .map(e =>
            RelationEdgeView(
              sit(e.event),
              sit(e.state),
              StatusWeight.of(e.meta),
              e.meta.status,
              e.meta
            )
          )
      case RelationLayer.Reference =>
        g.relations.references
          .filter(e => keep(e.from, e.to))
          .map(e =>
            RelationEdgeView(sit(e.from), sit(e.to), StatusWeight.of(e.meta), e.meta.status, e.meta)
          )
      case RelationLayer.Participant         => Vector.empty
      case RelationLayer.Semantic            => Vector.empty
      case RelationLayer.DiscourseSuccession =>
        val order = context match
          case None    => g.discourseOrder
          case Some(c) => g.situationsWithin(c)
        order.zip(order.drop(1)).map((a, b) => derived(sit(a), sit(b), 1.0))
      case RelationLayer.Hierarchy =>
        hierarchyMembership.toVector.map((k, w) => derived(k._1, k._2, w))
      case RelationLayer.EntityContinuity =>
        // Jaccard overlap of expanded participant sets between situations that share at least one
        // entity (directly or through group membership); built from the entity index so cost is
        // proportional to Σ_e deg(e)², not to the number of situations squared.
        val pairs = for
          (_, sits) <- g.situationsByEntity.toVector
          a <- sits
          b <- sits
          if a != b && keep(a, b)
        yield (a, b)
        pairs.distinct.map { (a, b) =>
          val ea = g.expandedEntitiesOf(a)
          val eb = g.expandedEntitiesOf(b)
          derived(sit(a), sit(b), ea.intersect(eb).size.toDouble / ea.union(eb).size)
        }

  private def toMatrix(edges: Vector[RelationEdgeView]): SparseRelation =
    edges.groupMapReduce(e => (e.from, e.to))(_.weight)(math.max)

  def relationMatrix(layer: RelationLayer): SparseRelation = toMatrix(edgesOf(layer, None))

  def relationMatrixIn(layer: RelationLayer, context: ContextId): SparseRelation =
    toMatrix(edgesOf(layer, Some(context)))

  def relationEdges(layer: RelationLayer): Vector[RelationEdgeView] = edgesOf(layer, None)

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

  def contextIdOf(target: NarrativeNodeId): Option[ContextId] = target match
    case NarrativeNodeId.Situation(id) => g.situations.get(id).map(_.context)
    case _                             => None

  def contextOf(target: NarrativeNodeId): Option[ContextKind] =
    contextIdOf(target).flatMap(g.contexts.get).map(_.kind)

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
    case NarrativeNodeId.Segment(id)   => g.segments.get(id).flatMap(_.summary.text)

  def entityLabel(id: EntityId): Option[String] = g.entities.get(id).map(_.label.value)
