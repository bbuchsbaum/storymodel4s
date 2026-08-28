package storymodel4s.story

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}

/** Coarse entity type; `Custom` admits imported ontologies without widening the closed set. */
enum EntityType:
  case Person, Group, Object, Location, Abstract
  case Custom(namespace: String, label: String)

/** An attribute of an entity that holds only inside a context (e.g. "ghost" under a belief).
  *
  * Why: a group described as warriors in the narrated world and as ghosts in a character's belief
  * is one entity with a scoped attribute, not two entities.
  */
final case class ScopedAttribute(context: ContextId, key: String, value: String, meta: ClaimMeta)

/** A canonical entity: person, group, object, location, or abstraction. */
final case class EntityNode(
    id: EntityId,
    label: Resolved[String],
    entityType: EntityType,
    mentions: NonEmptyVector[MentionId[EntityK]],
    attributes: Vector[ScopedAttribute],
    support: SpanSet,
    meta: ClaimMeta
)

/** Normalized predicate of a situation. External frame identifiers annotate, never define,
  * identity.
  */
final case class Predicate(lemma: String, frame: Option[String], gloss: String)

enum Polarity:
  case Positive, Negative, Unknown

/** Truth-status modality of a situation within its context. `Reported` marks content that the
  * context's source asserts; it is distinct from the narrated-world status of the same content.
  */
enum Modality:
  case Asserted, Possible, Probable, Necessary, Intended, Desired, Counterfactual, Reported, Unknown

enum Aspect:
  case Perfective, Imperfective, Habitual, Inchoative, Unknown

/** An action, occurrence, transition, perception, speech act, or mental act. */
final case class EventNode(
    id: SituationId,
    predicate: Predicate,
    description: String,
    context: ContextId,
    polarity: Polarity,
    modality: Modality,
    aspect: Option[Aspect],
    support: SpanSet,
    mentions: NonEmptyVector[MentionId[SituationK]],
    meta: ClaimMeta
)

/** A condition holding over an interval (fog, not feeling sick, being dead). */
final case class StateNode(
    id: SituationId,
    predicate: Predicate,
    description: String,
    context: ContextId,
    polarity: Polarity,
    modality: Modality,
    support: SpanSet,
    mentions: NonEmptyVector[MentionId[SituationK]],
    meta: ClaimMeta
)

/** Situations are events or states; both are alignable at hierarchy level 0. */
enum SituationNode:
  case Event(node: EventNode)
  case State(node: StateNode)

  def id: SituationId = this match
    case Event(n) => n.id
    case State(n) => n.id
  def predicate: Predicate = this match
    case Event(n) => n.predicate
    case State(n) => n.predicate
  def description: String = this match
    case Event(n) => n.description
    case State(n) => n.description
  def context: ContextId = this match
    case Event(n) => n.context
    case State(n) => n.context
  def polarity: Polarity = this match
    case Event(n) => n.polarity
    case State(n) => n.polarity
  def modality: Modality = this match
    case Event(n) => n.modality
    case State(n) => n.modality
  def support: SpanSet = this match
    case Event(n) => n.support
    case State(n) => n.support
  def mentions: NonEmptyVector[MentionId[SituationK]] = this match
    case Event(n) => n.mentions
    case State(n) => n.mentions
  def meta: ClaimMeta = this match
    case Event(n) => n.meta
    case State(n) => n.meta
  def isState: Boolean = this match
    case State(_) => true
    case Event(_) => false
  def isEvent: Boolean = !isState
  def kindName: String = if isState then "state" else "event"

enum SegmentKind:
  case Scene, Episode, Story, Arc

/** A composite narrative unit. `level` is 1 for scenes, 2 for episodes, 3 for the story root; arcs
  * use the level of the primary segment they most resemble.
  */
final case class SegmentNode(
    id: SegmentId,
    kind: SegmentKind,
    level: Int,
    summary: Resolved[String],
    support: SpanSet
)

/** Kind of an interpretive context. The root is `NarratedWorld`: what the narrative presents in its
  * main world, not "objective reality".
  */
enum ContextKind:
  case NarratedWorld
  case Speech(source: EntityId)
  case Belief(holder: EntityId)
  case Desire(holder: EntityId)
  case Intention(holder: EntityId)
  case Hypothetical
  case Counterfactual
  case Memory(holder: EntityId)
  case Imagination(holder: EntityId)

  /** The entity whose speech, belief, desire, intention, memory, or imagination this is. */
  def holderEntity: Option[EntityId] = this match
    case Speech(e)      => Some(e)
    case Belief(e)      => Some(e)
    case Desire(e)      => Some(e)
    case Intention(e)   => Some(e)
    case Memory(e)      => Some(e)
    case Imagination(e) => Some(e)
    case _              => None

  def label: String = this match
    case NarratedWorld  => "NarratedWorld"
    case Speech(e)      => s"Speech(${e.value})"
    case Belief(e)      => s"Belief(${e.value})"
    case Desire(e)      => s"Desire(${e.value})"
    case Intention(e)   => s"Intention(${e.value})"
    case Hypothetical   => "Hypothetical"
    case Counterfactual => "Counterfactual"
    case Memory(e)      => s"Memory(${e.value})"
    case Imagination(e) => s"Imagination(${e.value})"

/** A nested context frame. Every situation lives in exactly one frame. */
final case class ContextFrame(
    id: ContextId,
    parent: Option[ContextId],
    kind: ContextKind,
    support: SpanSet,
    meta: ClaimMeta
)

enum DescriptorKind:
  case Summary, Theme, Motif

/** A summary, theme, or motif attached to a segment.
  *
  * Why a separate family: descriptors must never masquerade as literal situations.
  */
final case class DescriptorClaim(
    target: SegmentId,
    kind: DescriptorKind,
    text: String,
    meta: ClaimMeta
)
