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
  def parts(s: AlignState): Vector[String] = AlignState.keyParts(s)

  def parse(parts: Vector[String]): Option[AlignState] = AlignState.parseKeyParts(parts)

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
          parts.headOption.flatMap { rawUnit =>
            for
              unit <- u(rawUnit)
              st <- AlignStateKey.parse(parts.drop(1))
            yield AlignRef.Cell(unit, st)
          }
        case Kinds.transition =>
          if parts.length < 6 then None
          else
            for
              f <- u(parts(0))
              t <- u(parts(1))
              states <- Vector(2, 4).flatMap { split =>
                for
                  a <- AlignStateKey.parse(parts.slice(2, 2 + split))
                  b <- AlignStateKey.parse(parts.drop(2 + split))
                yield (a, b)
              }.headOption
            yield AlignRef.Transition(f, t, states._1, states._2)
        case _ => None
    }(addr)
