package storymodel4s.codec

import io.circe.{Decoder, Json}
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.features.CanonicalDouble
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Supplied context is checked during decode; carrying it is not execution authority. */
final case class DerivationContext(
    result: HsmmResult,
    recall: RecallGraph[Checked],
    view: SourceView
)
final case class ExpectedMappingContext(
    inventory: RecallInventory,
    source: SourceRepresentation,
    derivation: Option[DerivationContext] = None
)

enum MappingCodecError:
  case Wire(error: CodecError)
  case Rejected(error: MappingRefusal)
  case NumericTick(path: String)
  case Reserved(tag: String)
  case DigestMismatch(field: String)
  case ValueMismatch(field: String)
  case ContextRequired(field: String)
  def message: String = this match
    case Wire(error)            => error.message
    case Rejected(error)        => error.message
    case NumericTick(path)      => s"$path requires an exact decimal string"
    case Reserved(tag)          => s"reserved mapping value: $tag"
    case DigestMismatch(field)  => s"mapping digest mismatch: $field"
    case ValueMismatch(field)   => s"mapping value mismatch: $field"
    case ContextRequired(field) => s"mapping derivation context required: $field"

private[codec] object MappingJson:
  type Result[A] = Either[MappingCodecError, A]
  // ASCII escaping preserves even unmatched UTF-16 code units through a UTF-8 file boundary.
  private val printer = Canonical.printer.copy(escapeNonAscii = true)
  def print(value: Json): String = printer.print(value)
  def obj(fields: (String, Json)*): Json = Json.obj(fields*)
  def str(value: String): Json = Json.fromString(value)
  def int(value: Int): Json = Json.fromInt(value)
  def long(value: Long): Json = str(value.toString)
  def number(value: Double): Json = str(CanonicalDouble.render(value))
  def array(values: Iterable[Json]): Json = Json.fromValues(values)
  def strings(values: Iterable[String]): Json = array(values.map(str))
  def tagged(status: String, fields: (String, Json)*): Json = obj(
    ("status" -> str(status)) +: fields*
  )
  def optional[A](value: Option[A])(encode: A => Json): Json = value match
    case Some(value) => tagged("present", "value" -> encode(value))
    case None        => tagged("absent", "reason" -> str("not-supplied"))
  def domain[A](value: Either[DomainError, A]): Result[A] =
    value.left.map(e => MappingCodecError.Wire(CodecError.Domain(e)))
  def checked[A](value: Either[MappingRefusal, A]): Result[A] =
    value.left.map(MappingCodecError.Rejected(_))
  def read[A: Decoder](value: Json): Result[A] =
    Canonical.decodeJson[A](value).left.map(MappingCodecError.Wire(_))
  def field[A: Decoder](value: Json, name: String): Result[A] = value.hcursor
    .get[A](name)
    .left
    .map(e => MappingCodecError.Wire(CodecError.Decode(s"$$.$name", e.message)))
  def status(value: Json): Result[String] = field[String](value, "status")
  def invalid[A](name: String, detail: String): Result[A] = Left(
    MappingCodecError.Wire(CodecError.Decode(name, detail))
  )
  def enumValue[A](value: String, values: Iterable[A], name: String): Result[A] =
    values
      .find(_.toString == value)
      .toRight(MappingCodecError.Wire(CodecError.Decode(name, s"unknown value $value")))
  def readOptional[A](value: Json)(decode: Json => Result[A]): Result[Option[A]] =
    status(value).flatMap {
      case "absent"  => Right(None)
      case "present" => field[Json](value, "value").flatMap(decode).map(Some(_))
      case other     => invalid("optional", s"unknown status $other")
    }
  def decimal(value: Json, path: String): Result[Long] =
    if value.isNumber then Left(MappingCodecError.NumericTick(path))
    else
      read[String](value).flatMap(s =>
        scala.util
          .Try(s.toLong)
          .toOption
          .toRight(MappingCodecError.Wire(CodecError.Decode(path, "invalid decimal Long")))
      )
  def exact(actual: Json, expected: Json, field: String): Result[Unit] =
    Either.cond(actual == expected, (), MappingCodecError.ValueMismatch(field))
  def unique[K, V](entries: Vector[(K, V)], name: String): Result[Map[K, V]] =
    if entries.map(_._1).distinct.size == entries.size then Right(entries.toMap)
    else invalid(name, "duplicate key")
  def sourceRef(value: String): Result[SourceNodeRef] =
    if value.startsWith("sit:") then
      domain(SituationId.from(value.drop(4))).map(SourceNodeRef.Situation(_))
    else if value.startsWith("seg:") then
      domain(SegmentId.from(value.drop(4))).map(SourceNodeRef.Segment(_))
    else invalid("target_id", "unknown source-node prefix")
  def destination(value: String): Result[Destination] =
    if value.startsWith("ext:") then
      enumValue(value.drop(4), ExternalState.values.toVector, "destination")
        .map(Destination.External(_))
    else sourceRef(value).map(Destination.Target(_))
  def capture(view: SourceView): SourceView =
    val capturedNodes = view.nodes
    val capturedAdjacency =
      RelationLayer.values.toVector.map(layer => layer -> view.adjacency(layer)).toMap
    val capturedWorldOrder = view.worldOrder
    val capturedScoringLength = view.scoringLength
    new SourceView:
      val nodes: Vector[NodeSummary] = capturedNodes
      private val index = nodes.map(n => n.ref -> n).toMap
      def node(ref: SourceNodeRef): Option[NodeSummary] = index.get(ref)
      def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
        capturedAdjacency(layer)
      val worldOrder: Option[Map[SourceNodeRef, Int]] = capturedWorldOrder
      val scoringLength: Int = capturedScoringLength
