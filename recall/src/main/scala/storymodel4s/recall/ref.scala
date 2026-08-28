package storymodel4s.recall

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** `recall`'s typed references (ADR 0002 §4): idea units, recall-side entities, and the three
  * recall-side relation edges.
  */
enum RecallRef:
  case Unit(id: RecallUnitId)
  case Entity(id: RecallEntityId)
  case Temporal(from: RecallUnitId, relation: RecallTemporalRelation, to: RecallUnitId)
  case Causal(cause: RecallUnitId, effect: RecallUnitId)
  case Elaboration(parent: RecallUnitId, child: RecallUnitId)

object RecallRef:
  val Tag: ModuleTag = ModuleTag.unsafe("recall")

  private object Kinds:
    val unit = AddressKind.unsafe("unit")
    val entity = AddressKind.unsafe("entity")
    val temporal = AddressKind.unsafe("temporal")
    val causal = AddressKind.unsafe("causal")
    val elaboration = AddressKind.unsafe("elaboration")

  private def key(parts: String*): AddressKey =
    AddressKey.of(NonEmptyVector.fromVectorUnsafe(parts.toVector))

  given Addressable[RecallRef] with
    val tag: ModuleTag = Tag

    def address(a: RecallRef): Address = a match
      case RecallRef.Unit(id)          => Address(Tag, Kinds.unit, key(id.value))
      case RecallRef.Entity(id)        => Address(Tag, Kinds.entity, key(id.value))
      case RecallRef.Temporal(f, r, t) =>
        Address(Tag, Kinds.temporal, key(f.value, r.toString, t.value))
      case RecallRef.Causal(f, t)      => Address(Tag, Kinds.causal, key(f.value, t.value))
      case RecallRef.Elaboration(p, c) => Address(Tag, Kinds.elaboration, key(p.value, c.value))

    def parse(addr: Address): Option[RecallRef] = Addressable.guarded[RecallRef](Tag) { (kind, k) =>
      val parts = k.parts.toVector
      def u(s: String) = RecallUnitId.from(s).toOption
      kind match
        case Kinds.unit =>
          parts match
            case Vector(s) => u(s).map(RecallRef.Unit.apply)
            case _         => None
        case Kinds.entity =>
          parts match
            case Vector(s) => RecallEntityId.from(s).toOption.map(RecallRef.Entity.apply)
            case _         => None
        case Kinds.temporal =>
          parts match
            case Vector(f, r, t) =>
              for
                a <- u(f)
                rel <- RecallTemporalRelation.values.find(_.toString == r)
                b <- u(t)
              yield RecallRef.Temporal(a, rel, b)
            case _ => None
        case Kinds.causal =>
          parts match
            case Vector(f, t) =>
              for
                a <- u(f)
                b <- u(t)
              yield RecallRef.Causal(a, b)
            case _ => None
        case Kinds.elaboration =>
          parts match
            case Vector(p, c) =>
              for
                a <- u(p)
                b <- u(c)
              yield RecallRef.Elaboration(a, b)
            case _ => None
        case _ => None
    }(addr)
