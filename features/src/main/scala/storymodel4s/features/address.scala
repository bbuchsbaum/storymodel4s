package storymodel4s.features

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** `features`' typed references (ADR 0002 §4): a feature space, one observation on a target, or a
  * derivation recipe. A view colours text by observations and audits them by derivation id, so both
  * must be addressable without the view depending on how tracks are stored.
  */
enum FeatureAddress:
  case Space(id: FeatureSpaceId)
  case Observation(space: FeatureSpaceId, target: FeatureTarget)
  case Derivation(id: Checksum)

/** Canonical key encoding of a [[FeatureTarget]], shared by every address that embeds a target. */
object FeatureTargetKey:
  def parts(t: FeatureTarget): Vector[String] = t match
    case FeatureTarget.Token(i)     => Vector("token", i.value.toString)
    case FeatureTarget.Sentence(u)  => Vector("sentence", u.value)
    case FeatureTarget.Situation(i) => Vector("situation", i.value)
    case FeatureTarget.Segment(i)   => Vector("segment", i.value)
    case FeatureTarget.Boundary(u)  => Vector("boundary", u.value)
    case FeatureTarget.Turn(i)      => Vector("turn", i.value)
    case FeatureTarget.Window(r)    =>
      Vector("window", r.start.value.toString, r.endExclusive.value.toString)
    case FeatureTarget.Unit(u) => Vector("unit", u.value)

  /** Only the canonical decimal rendering is accepted (no sign, no leading zeros), so that every
    * accepted key renders back to itself.
    */
  private def canonicalInt(s: String): Option[Int] = s.toIntOption.filter(_.toString == s)

  def parse(parts: Vector[String]): Option[FeatureTarget] = parts match
    case Vector("token", i) =>
      canonicalInt(i).flatMap(TokenIndex.from(_).toOption).map(FeatureTarget.Token.apply)
    case Vector("sentence", u)  => SurfaceUnitId.from(u).toOption.map(FeatureTarget.Sentence.apply)
    case Vector("situation", i) => SituationId.from(i).toOption.map(FeatureTarget.Situation.apply)
    case Vector("segment", i)   => SegmentId.from(i).toOption.map(FeatureTarget.Segment.apply)
    case Vector("boundary", u)  => SurfaceUnitId.from(u).toOption.map(FeatureTarget.Boundary.apply)
    case Vector("turn", i)      => TurnId.from(i).toOption.map(FeatureTarget.Turn.apply)
    case Vector("window", s, e) =>
      for
        a <- canonicalInt(s)
        b <- canonicalInt(e)
        r <- TokenRange.of(a, b).toOption
      yield FeatureTarget.Window(r)
    case Vector("unit", u) => SurfaceUnitId.from(u).toOption.map(FeatureTarget.Unit.apply)
    case _                 => None

object FeatureAddress:
  val Tag: ModuleTag = ModuleTag.unsafe("features")

  private object Kinds:
    val space = AddressKind.unsafe("space")
    val observation = AddressKind.unsafe("observation")
    val derivation = AddressKind.unsafe("derivation")

  given Addressable[FeatureAddress] with
    val tag: ModuleTag = Tag

    def address(a: FeatureAddress): Address = a match
      case FeatureAddress.Space(id) => Address(Tag, Kinds.space, AddressKey.of(id.value))
      case FeatureAddress.Observation(space, target) =>
        Address(
          Tag,
          Kinds.observation,
          AddressKey.of(NonEmptyVector(space.value, FeatureTargetKey.parts(target)))
        )
      case FeatureAddress.Derivation(id) => Address(Tag, Kinds.derivation, AddressKey.of(id.hex))

    def parse(addr: Address): Option[FeatureAddress] =
      Addressable.guarded[FeatureAddress](Tag) { (kind, key) =>
        val parts = key.parts.toVector
        kind match
          case Kinds.space =>
            if parts.length == 1 then
              FeatureSpaceId.from(parts.head).toOption.map(FeatureAddress.Space.apply)
            else None
          case Kinds.derivation =>
            if parts.length == 1 then
              Checksum.from(parts.head).toOption.map(FeatureAddress.Derivation.apply)
            else None
          case Kinds.observation =>
            if parts.length < 2 then None
            else
              for
                space <- FeatureSpaceId.from(parts.head).toOption
                target <- FeatureTargetKey.parse(parts.tail)
              yield FeatureAddress.Observation(space, target)
          case _ => None
      }(addr)
