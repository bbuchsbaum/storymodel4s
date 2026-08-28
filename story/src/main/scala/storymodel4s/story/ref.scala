package storymodel4s.story

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.proposition.ParticipantRole

/** `story`'s typed references (ADR 0002 §4): nodes, contexts, and every stored relation edge.
  *
  * Why: views, critic findings, and validator paths must be able to name an edge, not only a node;
  * an edge is addressed by its endpoints and relation (and context for temporal edges), which is
  * exactly the identity the validator's uniqueness laws use.
  */
enum StoryRef:
  case Situation(id: SituationId)
  case Segment(id: SegmentId)
  case Entity(id: EntityId)
  case Context(id: ContextId)
  case Participant(situation: SituationId, role: ParticipantRole, entity: EntityId)
  case Temporal(from: SituationId, relation: TemporalRelation, to: SituationId, context: ContextId)
  case Causal(cause: SituationId, relation: CausalRelation, effect: SituationId)
  case Goal(from: SituationId, relation: GoalRelation, to: SituationId)
  case StateChange(event: SituationId, change: StateChangeKind, state: SituationId)
  case Reference(from: SituationId, mode: NarrativeReference, to: SituationId)
  case Containment(member: NarrativeMember, parent: SegmentId, kind: HierarchyKind)
  case EntityLink(from: EntityId, relation: EntityRelation, to: EntityId)

object StoryRef:
  val Tag: ModuleTag = ModuleTag.unsafe("story")

  private object Kinds:
    val situation = AddressKind.unsafe("situation")
    val segment = AddressKind.unsafe("segment")
    val entity = AddressKind.unsafe("entity")
    val context = AddressKind.unsafe("context")
    val participant = AddressKind.unsafe("participant")
    val temporal = AddressKind.unsafe("temporal")
    val causal = AddressKind.unsafe("causal")
    val goal = AddressKind.unsafe("goal")
    val stateChange = AddressKind.unsafe("state-change")
    val reference = AddressKind.unsafe("reference")
    val containment = AddressKind.unsafe("containment")
    val entityLink = AddressKind.unsafe("entity-link")

  /** Singleton participant roles, by case name. `Custom` is encoded structurally. */
  private val roles: Map[String, ParticipantRole] =
    import ParticipantRole.*
    Vector(
      Agent,
      Patient,
      Theme,
      Experiencer,
      Stimulus,
      Instrument,
      Beneficiary,
      Source,
      Destination,
      Location,
      Time,
      Manner,
      Cause,
      Result
    ).map(r => r.toString -> r).toMap

  private val entityRelations: Map[String, EntityRelation] =
    import EntityRelation.*
    Vector(MemberOf, PartOf, SameAs).map(r => r.toString -> r).toMap

  private def byName[E](all: Iterable[E])(s: String): Option[E] = all.find(_.toString == s)

  private def roleParts(r: ParticipantRole): Vector[String] = r match
    case ParticipantRole.Custom(ns, label) => Vector("custom", ns, label)
    case other                             => Vector(other.toString)
  private def parseRole(parts: Vector[String]): Option[ParticipantRole] = parts match
    case Vector(name)                => roles.get(name)
    case Vector("custom", ns, label) => Some(ParticipantRole.Custom(ns, label))
    case _                           => None

  private def entityRelationParts(r: EntityRelation): Vector[String] = r match
    case EntityRelation.Custom(ns, label) => Vector("custom", ns, label)
    case other                            => Vector(other.toString)
  private def parseEntityRelation(parts: Vector[String]): Option[EntityRelation] = parts match
    case Vector(name)                => entityRelations.get(name)
    case Vector("custom", ns, label) => Some(EntityRelation.Custom(ns, label))
    case _                           => None

  private def memberParts(m: NarrativeMember): Vector[String] = m match
    case NarrativeMember.Situation(id) => Vector("situation", id.value)
    case NarrativeMember.Segment(id)   => Vector("segment", id.value)
  private def parseMember(kind: String, id: String): Option[NarrativeMember] = kind match
    case "situation" => SituationId.from(id).toOption.map(NarrativeMember.Situation.apply)
    case "segment"   => SegmentId.from(id).toOption.map(NarrativeMember.Segment.apply)
    case _           => None

  private def key(parts: Vector[String]): AddressKey =
    AddressKey.of(NonEmptyVector.fromVectorUnsafe(parts))

  given Addressable[StoryRef] with
    val tag: ModuleTag = Tag

    def address(a: StoryRef): Address = a match
      case StoryRef.Situation(id) => Address(Tag, Kinds.situation, AddressKey.of(id.value))
      case StoryRef.Segment(id)   => Address(Tag, Kinds.segment, AddressKey.of(id.value))
      case StoryRef.Entity(id)    => Address(Tag, Kinds.entity, AddressKey.of(id.value))
      case StoryRef.Context(id)   => Address(Tag, Kinds.context, AddressKey.of(id.value))
      case StoryRef.Participant(s, role, e) =>
        Address(Tag, Kinds.participant, key(Vector(s.value, e.value) ++ roleParts(role)))
      case StoryRef.Temporal(f, r, t, c) =>
        Address(Tag, Kinds.temporal, key(Vector(f.value, r.toString, t.value, c.value)))
      case StoryRef.Causal(f, r, t) =>
        Address(Tag, Kinds.causal, key(Vector(f.value, r.toString, t.value)))
      case StoryRef.Goal(f, r, t) =>
        Address(Tag, Kinds.goal, key(Vector(f.value, r.toString, t.value)))
      case StoryRef.StateChange(f, r, t) =>
        Address(Tag, Kinds.stateChange, key(Vector(f.value, r.toString, t.value)))
      case StoryRef.Reference(f, r, t) =>
        Address(Tag, Kinds.reference, key(Vector(f.value, r.toString, t.value)))
      case StoryRef.Containment(m, p, k) =>
        Address(Tag, Kinds.containment, key(memberParts(m) ++ Vector(p.value, k.toString)))
      case StoryRef.EntityLink(f, r, t) =>
        Address(Tag, Kinds.entityLink, key(Vector(f.value, t.value) ++ entityRelationParts(r)))

    def parse(addr: Address): Option[StoryRef] = Addressable.guarded[StoryRef](Tag) { (kind, k) =>
      val parts = k.parts.toVector
      def sit(s: String) = SituationId.from(s).toOption
      def single[I](mk: String => Either[DomainError, I]): Option[I] =
        if parts.length == 1 then mk(parts.head).toOption else None
      def triple[R](all: Iterable[R])(mk: (SituationId, R, SituationId) => StoryRef) =
        parts match
          case Vector(f, r, t) =>
            for
              a <- sit(f)
              rel <- byName(all)(r)
              b <- sit(t)
            yield mk(a, rel, b)
          case _ => None
      kind match
        case Kinds.situation   => single(SituationId.from).map(StoryRef.Situation.apply)
        case Kinds.segment     => single(SegmentId.from).map(StoryRef.Segment.apply)
        case Kinds.entity      => single(EntityId.from).map(StoryRef.Entity.apply)
        case Kinds.context     => single(ContextId.from).map(StoryRef.Context.apply)
        case Kinds.participant =>
          if parts.length < 3 then None
          else
            for
              s <- sit(parts(0))
              e <- EntityId.from(parts(1)).toOption
              role <- parseRole(parts.drop(2))
            yield StoryRef.Participant(s, role, e)
        case Kinds.temporal =>
          parts match
            case Vector(f, r, t, c) =>
              for
                a <- sit(f)
                rel <- byName(TemporalRelation.values)(r)
                b <- sit(t)
                ctx <- ContextId.from(c).toOption
              yield StoryRef.Temporal(a, rel, b, ctx)
            case _ => None
        case Kinds.causal      => triple(CausalRelation.values)(StoryRef.Causal.apply)
        case Kinds.goal        => triple(GoalRelation.values)(StoryRef.Goal.apply)
        case Kinds.stateChange =>
          triple(StateChangeKind.values)(StoryRef.StateChange.apply)
        case Kinds.reference =>
          triple(NarrativeReference.values)(StoryRef.Reference.apply)
        case Kinds.containment =>
          parts match
            case Vector(mk, mid, p, hk) =>
              for
                m <- parseMember(mk, mid)
                parent <- SegmentId.from(p).toOption
                kind <- byName(HierarchyKind.values)(hk)
              yield StoryRef.Containment(m, parent, kind)
            case _ => None
        case Kinds.entityLink =>
          if parts.length < 3 then None
          else
            for
              f <- EntityId.from(parts(0)).toOption
              t <- EntityId.from(parts(1)).toOption
              rel <- parseEntityRelation(parts.drop(2))
            yield StoryRef.EntityLink(f, rel, t)
        case _ => None
    }(addr)
