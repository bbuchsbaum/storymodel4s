package storymodel4s.document

import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.*
import storymodel4s.proposition.ConceptId

/** `document`'s typed references (ADR 0002 §4): a chart node, a kind-indexed mention, or a
  * kind-indexed canonical object. Kinds are separate cases rather than a type parameter so that the
  * address is a closed, serializable sum and an entity cluster can never parse as a situation one.
  */
enum DocRef:
  case ChartNode(ref: ChartNodeRef)
  case EntityMention(id: MentionId[EntityK])
  case SituationMention(id: MentionId[SituationK])
  case SegmentMention(id: MentionId[SegmentK])
  case ContextMention(id: MentionId[ContextK])
  case EntityCanonical(id: CanonicalId[EntityK])
  case SituationCanonical(id: CanonicalId[SituationK])
  case SegmentCanonical(id: CanonicalId[SegmentK])
  case ContextCanonical(id: CanonicalId[ContextK])

object DocRef:
  val Tag: ModuleTag = ModuleTag.unsafe("document")

  private object Kinds:
    val chartNode = AddressKind.unsafe("chart-node")
    val mention = AddressKind.unsafe("mention")
    val canonical = AddressKind.unsafe("canonical")

  private val Entity = "entity"
  private val Situation = "situation"
  private val Segment = "segment"
  private val Context = "context"

  given Addressable[DocRef] with
    val tag: ModuleTag = Tag

    def address(a: DocRef): Address = a match
      case DocRef.ChartNode(r) =>
        Address(Tag, Kinds.chartNode, AddressKey.of(r.sentence.value, r.concept.value))
      case DocRef.EntityMention(id) => Address(Tag, Kinds.mention, AddressKey.of(Entity, id.value))
      case DocRef.SituationMention(id) =>
        Address(Tag, Kinds.mention, AddressKey.of(Situation, id.value))
      case DocRef.SegmentMention(id) =>
        Address(Tag, Kinds.mention, AddressKey.of(Segment, id.value))
      case DocRef.ContextMention(id) =>
        Address(Tag, Kinds.mention, AddressKey.of(Context, id.value))
      case DocRef.EntityCanonical(id) =>
        Address(Tag, Kinds.canonical, AddressKey.of(Entity, id.value))
      case DocRef.SituationCanonical(id) =>
        Address(Tag, Kinds.canonical, AddressKey.of(Situation, id.value))
      case DocRef.SegmentCanonical(id) =>
        Address(Tag, Kinds.canonical, AddressKey.of(Segment, id.value))
      case DocRef.ContextCanonical(id) =>
        Address(Tag, Kinds.canonical, AddressKey.of(Context, id.value))

    def parse(addr: Address): Option[DocRef] = Addressable.guarded[DocRef](Tag) { (kind, key) =>
      key.parts.toVector match
        case Vector(s, c) if kind == Kinds.chartNode =>
          for
            sentence <- SurfaceUnitId.from(s).toOption
            concept <- ConceptId.from(c).toOption
          yield DocRef.ChartNode(ChartNodeRef(sentence, concept))
        case Vector(k, id) if kind == Kinds.mention =>
          k match
            case Entity    => MentionId.from[EntityK](id).toOption.map(DocRef.EntityMention.apply)
            case Situation =>
              MentionId.from[SituationK](id).toOption.map(DocRef.SituationMention.apply)
            case Segment => MentionId.from[SegmentK](id).toOption.map(DocRef.SegmentMention.apply)
            case Context => MentionId.from[ContextK](id).toOption.map(DocRef.ContextMention.apply)
            case _       => None
        case Vector(k, id) if kind == Kinds.canonical =>
          k match
            case Entity => CanonicalId.from[EntityK](id).toOption.map(DocRef.EntityCanonical.apply)
            case Situation =>
              CanonicalId.from[SituationK](id).toOption.map(DocRef.SituationCanonical.apply)
            case Segment =>
              CanonicalId.from[SegmentK](id).toOption.map(DocRef.SegmentCanonical.apply)
            case Context =>
              CanonicalId.from[ContextK](id).toOption.map(DocRef.ContextCanonical.apply)
            case _ => None
        case _ => None
    }(addr)
