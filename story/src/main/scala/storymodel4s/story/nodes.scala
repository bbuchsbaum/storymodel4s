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

/** Why a context's holder could not be named.
  *
  * Why each case stands apart: "nothing in the text names a speaker" and "two people could have
  * said it" are different states of the evidence, and a reader auditing an attribution must be able
  * to tell them apart. One shared absence would make an unasked question look like an answered one
  * (design contract 7).
  */
enum HolderGap:
  /** No reporting predicate governs the content, so nothing proposes a holder at all. */
  case NoCandidate

  /** Several distinct entities could hold the content and the evidence decides between none. */
  case SeveralCandidates

  /** Exactly one candidate was named and it minted no entity, so there is nothing to point at. */
  case UnresolvedCandidate

  def render: String = this match
    case NoCandidate         => "no-candidate"
    case SeveralCandidates   => "several-candidates"
    case UnresolvedCandidate => "unresolved-candidate"

/** Whose speech, belief, desire, intention, memory, or imagination a context is — when the model
  * derived it — or the reason it could not.
  *
  * Why not a bare `EntityId`: attribution and embedding are two questions, and the second one fails
  * far more often than the first. A quotation whose speaker no rule can name is still reported
  * content, and placing it in the narrated world because nobody could be named is exactly the
  * fabricated license design contract 4 forbids. This type lets a context say "reported, holder
  * unresolved" instead, with the reason attached.
  */
enum ContextHolder:
  /** The holder was derived from evidence and is this entity. */
  case Named(entity: EntityId)

  /** No holder was derived; `gap` says what stopped the derivation. */
  case Unattributed(gap: HolderGap)

  /** The entity this holder names, or nothing when the derivation abstained. */
  def namedEntity: Option[EntityId] = this match
    case Named(e)        => Some(e)
    case Unattributed(_) => None

  def gapReason: Option[HolderGap] = this match
    case Named(_)          => None
    case Unattributed(why) => Some(why)

  def render: String = this match
    case Named(e)          => e.value
    case Unattributed(why) => s"unattributed:${why.render}"

/** Kind of an interpretive context. The root is `NarratedWorld`: what the narrative presents in its
  * main world, not "objective reality".
  */
enum ContextKind:
  case NarratedWorld
  case Speech(source: ContextHolder)
  case Belief(holder: ContextHolder)
  case Desire(holder: ContextHolder)
  case Intention(holder: ContextHolder)
  case Hypothetical
  case Counterfactual
  case Memory(holder: ContextHolder)
  case Imagination(holder: ContextHolder)

  /** Whose speech, belief, desire, intention, memory, or imagination this is, named or not. */
  def heldBy: Option[ContextHolder] = this match
    case Speech(h)      => Some(h)
    case Belief(h)      => Some(h)
    case Desire(h)      => Some(h)
    case Intention(h)   => Some(h)
    case Memory(h)      => Some(h)
    case Imagination(h) => Some(h)
    case _              => None

  /** The entity holding this context, when one was derived. `None` covers both a kind that has no
    * holder at all and a holder the model could not name; [[heldBy]] separates those.
    */
  def holderEntity: Option[EntityId] = heldBy.flatMap(_.namedEntity)

  def label: String = this match
    case NarratedWorld  => "NarratedWorld"
    case Speech(h)      => s"Speech(${h.render})"
    case Belief(h)      => s"Belief(${h.render})"
    case Desire(h)      => s"Desire(${h.render})"
    case Intention(h)   => s"Intention(${h.render})"
    case Hypothetical   => "Hypothetical"
    case Counterfactual => "Counterfactual"
    case Memory(h)      => s"Memory(${h.render})"
    case Imagination(h) => s"Imagination(${h.render})"

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

/** Competing readings of one situation's narrated-world truth that the model refuses to collapse.
  *
  * Why: design record §27.2 requires that an alleged injury reported inside a character's speech
  * stay open at the narrated-world level, with "injury occurred" and "no ordinary injury occurred"
  * both retained. The subject is the hypothesized root-world situation; `reading.value` is the
  * leading reading and `reading.alternatives` its rivals with raw credences. The claim's status
  * must be `Hypothesized` and it must carry at least one alternative.
  */
final case class HypothesisClaim(subject: SituationId, reading: Resolved[String]):
  def meta: ClaimMeta = reading.meta
