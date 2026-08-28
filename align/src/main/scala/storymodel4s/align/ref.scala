package storymodel4s.align

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId

/** `align`'s typed references (ADR 0002 §4): one cell of `P` (a recall unit's mass on a source node
  * or external state) and one transition of `F` (mass moving between states across consecutive
  * recall units). Every recall-view mark is generated from these (ADR 0002 D10).
  */
enum AlignRef:
  case Cell(unit: RecallUnitId, state: AlignState)
  case Transition(from: RecallUnitId, to: RecallUnitId, fromState: AlignState, toState: AlignState)

/** Canonical key encoding of an [[AlignState]]: `situation/<id>`, `segment/<id>`, or
  * `external/<ExternalState>`.
  */
object AlignStateKey:
  def parts(s: AlignState): Vector[String] = s match
    case AlignState.Source(SourceNodeRef.Situation(id)) => Vector("situation", id.value)
    case AlignState.Source(SourceNodeRef.Segment(id))   => Vector("segment", id.value)
    case AlignState.External(e)                         => Vector("external", e.toString)

  def parse(parts: Vector[String]): Option[AlignState] = parts match
    case Vector("situation", id) =>
      SituationId.from(id).toOption.map(i => AlignState.Source(SourceNodeRef.Situation(i)))
    case Vector("segment", id) =>
      SegmentId.from(id).toOption.map(i => AlignState.Source(SourceNodeRef.Segment(i)))
    case Vector("external", e) =>
      ExternalState.values.find(_.toString == e).map(AlignState.External.apply)
    case _ => None

object AlignRef:
  val Tag: ModuleTag = ModuleTag.unsafe("align")

  private object Kinds:
    val cell = AddressKind.unsafe("cell")
    val transition = AddressKind.unsafe("transition")

  private def key(parts: Vector[String]): AddressKey =
    AddressKey.of(NonEmptyVector.fromVectorUnsafe(parts))

  given Addressable[AlignRef] with
    val tag: ModuleTag = Tag

    def address(a: AlignRef): Address = a match
      case AlignRef.Cell(u, s) => Address(Tag, Kinds.cell, key(u.value +: AlignStateKey.parts(s)))
      case AlignRef.Transition(f, t, a, b) =>
        Address(
          Tag,
          Kinds.transition,
          key(Vector(f.value, t.value) ++ AlignStateKey.parts(a) ++ AlignStateKey.parts(b))
        )

    def parse(addr: Address): Option[AlignRef] = Addressable.guarded[AlignRef](Tag) { (kind, k) =>
      val parts = k.parts.toVector
      def u(s: String) = RecallUnitId.from(s).toOption
      kind match
        case Kinds.cell =>
          if parts.length != 3 then None
          else
            for
              unit <- u(parts(0))
              st <- AlignStateKey.parse(parts.drop(1))
            yield AlignRef.Cell(unit, st)
        case Kinds.transition =>
          if parts.length != 6 then None
          else
            for
              f <- u(parts(0))
              t <- u(parts(1))
              a <- AlignStateKey.parse(parts.slice(2, 4))
              b <- AlignStateKey.parse(parts.slice(4, 6))
            yield AlignRef.Transition(f, t, a, b)
        case _ => None
    }(addr)
