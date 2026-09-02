package storymodel4s.story

import storymodel4s.core.*

/** A situation-to-entity participant relation. The relation layer is the sole source of truth for
  * roles; situation nodes carry no role fields.
  */
final case class ParticipantEdge(
    situation: SituationId,
    role: ParticipantRole,
    entity: EntityId,
    meta: ClaimMeta
)

/** What a circumstance says about the situation it is attached to.
  *
  * Closed and small on purpose: these are the role families this project's role vocabulary marks as
  * circumstantial rather than referential. A `Time` says when the situation held, a `Manner` how it
  * was done; neither names anything that participates in it.
  */
enum CircumstanceKind:
  case Time, Manner

  def render: String = this match
    case Time   => "time"
    case Manner => "manner"

/** A time or manner the source attached to a situation, with the words that state it.
  *
  * Why this exists rather than a participant edge: a time is not a referent. Admitting `midnight`
  * or `then` as an entity made the model publish cast members it never observed, and every
  * measurement over the entity layer — turnover above all — counted them. Why it exists rather than
  * nothing: the words are evidence the source really carries, and dropping them would trade one
  * falsehood for a silence.
  *
  * `label` is the source's own word for the circumstance and is never normalized to a date, a
  * duration, or an interval. Placing this on a timeline is a separate claim with a separate
  * licence; this edge asserts only that the situation's own words said this much.
  */
final case class CircumstanceEdge(
    situation: SituationId,
    kind: CircumstanceKind,
    label: String,
    support: SpanSet,
    meta: ClaimMeta
)

/** Allen-style interval relations.
  *
  * Only the canonical forms (`Before`, `Meets`, `Overlaps`, `During`, `Contains`, `Starts`,
  * `Finishes`, `Equal`, `Unclear`) may be stored; the validator rejects stored converse forms. The
  * converse forms exist so that [[TemporalEdge.inverse]] is a total involution — the same fact seen
  * from the other endpoint — and so that flows can report a backward jump as `After`.
  *
  * Converse table: Before↔After, Meets↔MetBy, Overlaps↔OverlappedBy, During↔Contains,
  * Starts↔StartedBy, Finishes↔FinishedBy, Equal↔Equal, Unclear↔Unclear.
  */
enum TemporalRelation:
  case Before, Meets, Overlaps, During, Contains, Starts, Finishes, Equal, Unclear
  case After, MetBy, OverlappedBy, StartedBy, FinishedBy

  def converse: TemporalRelation = this match
    case Before       => After
    case After        => Before
    case Meets        => MetBy
    case MetBy        => Meets
    case Overlaps     => OverlappedBy
    case OverlappedBy => Overlaps
    case During       => Contains
    case Contains     => During
    case Starts       => StartedBy
    case StartedBy    => Starts
    case Finishes     => FinishedBy
    case FinishedBy   => Finishes
    case Equal        => Equal
    case Unclear      => Unclear

  /** True for the forms that may be stored in a model. */
  def isCanonical: Boolean = this match
    case After | MetBy | OverlappedBy | StartedBy | FinishedBy => false
    case _                                                     => true

  /** Strict precedence in world time: the whole of `from` precedes the whole of `to`. */
  def isStrictPrecedence: Boolean = this match
    case Before | Meets => true
    case _              => false

  /** Canonical form of the relation together with whether endpoints must be swapped. */
  def canonicalized: (TemporalRelation, Boolean) =
    if isCanonical then (this, false) else (converse, true)

/** A story-world temporal claim scoped to a context. A later real-valued timeline is only a layout
  * of these edges, never their replacement.
  */
final case class TemporalEdge(
    from: SituationId,
    relation: TemporalRelation,
    to: SituationId,
    context: ContextId,
    meta: ClaimMeta
):
  /** The same fact from the other endpoint's perspective. Law: `inverse.inverse == this`. */
  def inverse: TemporalEdge = copy(from = to, relation = relation.converse, to = from)

  /** Rewritten in canonical form (endpoints swapped if the relation was a converse form). */
  def canonical: TemporalEdge = if relation.isCanonical then this else inverse

enum CausalRelation:
  case Causes, Enables, Prevents, Terminates

/** A causal, enabling, preventing, or terminating relation between situations. Temporal precedence
  * alone never licenses one; the claim's status records how it was licensed.
  */
final case class CausalEdge(
    cause: SituationId,
    relation: CausalRelation,
    effect: SituationId,
    meta: ClaimMeta
)

enum GoalRelation:
  case Motivates, IntendedToAchieve, SubgoalOf, Achieves, FailsToAchieve, Abandons

final case class GoalEdge(
    from: SituationId,
    relation: GoalRelation,
    to: SituationId,
    meta: ClaimMeta
)

enum StateChangeKind:
  case Initiates, Terminates, Maintains

/** Lightweight event calculus: an event initiates, terminates, or maintains a state. */
final case class StateChangeEdge(
    event: SituationId,
    change: StateChangeKind,
    state: SituationId,
    meta: ClaimMeta
)

/** How one situation refers to another without being the same occurrence.
  *
  * Why: a later retelling of a battle is a new speech event whose content refers to the battle; an
  * announced future battle is a prospective reference. Neither duplicates the occurrence.
  */
enum NarrativeReference:
  case Anaphoric, Prospective, Retrospective, Summary, Partial, Bridging, Thematic

final case class ReferenceEdge(
    from: SituationId,
    mode: NarrativeReference,
    to: SituationId,
    meta: ClaimMeta
)

/** A member of a segment: an atomic situation or a nested segment. */
enum NarrativeMember:
  case Situation(id: SituationId)
  case Segment(id: SegmentId)

  def render: String = this match
    case Situation(id) => id.value
    case Segment(id)   => id.value

enum HierarchyKind:
  case PrimarySegmentation, GoalArc, EntityThread, LocationThread, Theme

/** Membership of a situation or segment in a parent segment. Primary membership normally has weight
  * 1; auxiliary membership may be graded.
  */
final case class ContainmentEdge(
    member: NarrativeMember,
    parent: SegmentId,
    kind: HierarchyKind,
    weight: Double,
    meta: ClaimMeta
):
  def isPrimary: Boolean = kind == HierarchyKind.PrimarySegmentation

/** Relations between entities.
  *
  * Why: a pair of young men acting together is one group entity with two individual members;
  * without `MemberOf` the individuals' participation in the group's situations is invisible to
  * entity continuity and the "separate entities, shared participant edges" requirement (§27.1)
  * cannot be expressed. `SameAs` is an identity hypothesis between entities that were kept
  * separate.
  */
enum EntityRelation:
  case MemberOf, PartOf, SameAs
  case Custom(namespace: String, label: String)

final case class EntityEdge(
    from: EntityId,
    relation: EntityRelation,
    to: EntityId,
    meta: ClaimMeta
)

/** Sparse typed relation layers. Containment lives in [[NarrativeHierarchy]], not here. */
final case class RelationLayers(
    participants: Vector[ParticipantEdge],
    temporal: Vector[TemporalEdge],
    causal: Vector[CausalEdge],
    goals: Vector[GoalEdge],
    stateChanges: Vector[StateChangeEdge],
    references: Vector[ReferenceEdge],
    entityRelations: Vector[EntityEdge] = Vector.empty,
    circumstances: Vector[CircumstanceEdge] = Vector.empty
):
  def allMeta: Vector[ClaimMeta] =
    participants.map(_.meta) ++ temporal.map(_.meta) ++ causal.map(_.meta) ++ goals.map(_.meta) ++
      stateChanges.map(_.meta) ++ references.map(_.meta) ++ entityRelations.map(_.meta) ++
      circumstances.map(_.meta)

object RelationLayers:
  val empty: RelationLayers =
    RelationLayers(
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )

/** Provisional weights by epistemic status used when a relation layer is viewed as a sparse matrix.
  * Status is never discarded: [[AlignmentSource.relationEdges]] exposes it alongside.
  */
object StatusWeight:
  def of(status: EpistemicStatus): Double = status match
    case EpistemicStatus.SurfaceExplicit        => 1.0
    case EpistemicStatus.HumanAdjudicated       => 1.0
    case EpistemicStatus.LinguisticallyEntailed => 1.0
    case EpistemicStatus.StructurallyDerived    => 0.8
    case EpistemicStatus.WorldKnowledgeInferred => 0.5
    case EpistemicStatus.Hypothesized           => 0.25

  /** Calibrated probability when present, else the status weight scaled by nothing else: raw scores
    * are not probabilities and must not enter the matrix.
    */
  def of(meta: ClaimMeta): Double =
    meta.credence.calibrated.map(_.value).getOrElse(of(meta.status))

/** Names of the relation views a consumer can request from an [[AlignmentSource]]. Some are stored
  * layers; `EntityContinuity` and `DiscourseSuccession` are derived; `Semantic` is a sidecar view
  * that a validated model alone cannot supply.
  */
enum RelationLayer:
  case Participant, WorldTime, Causal, Goal, StateChange, Reference, Hierarchy, EntityContinuity,
    DiscourseSuccession, Semantic
