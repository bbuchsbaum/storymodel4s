package storymodel4s.codec

import io.circe.{Decoder, Encoder, HCursor, Json, KeyDecoder, KeyEncoder, Printer}
import storymodel4s.core.*
import storymodel4s.features.CanonicalDouble

/** Errors raised by the wire layer. `Domain` wraps a smart-constructor failure so that a decoded
  * value can never bypass the invariants its constructor enforces.
  */
enum CodecError:
  case Parse(detail: String)
  case Decode(path: String, detail: String)
  case Domain(error: DomainError)
  case UnsupportedSchema(found: String, supported: Vector[String])

  def message: String = this match
    case Parse(m)                => s"parse: $m"
    case Decode(p, m)            => s"decode at $p: $m"
    case Domain(e)               => e.message
    case UnsupportedSchema(f, s) =>
      s"unsupported schema version $f (supported: ${s.mkString(", ")})"

/** Canonical JSON discipline.
  *
  * Rules (design record §31.5, codec milestone §2):
  *   - object keys sorted, no insignificant whitespace, `null` values dropped (an absent `Option`
  *     is an absent key, never `null`);
  *   - `Double` values are rendered through [[CanonicalDouble]] (IEEE-754 bits as `0x` + 16 hex
  *     digits) so JVM, Scala.js, and Scala Native produce byte-identical text; integers are JSON
  *     integers; `BigDecimal` is its plain scale-stripped form;
  *   - enums are their case names; parameterized cases are objects tagged by `"type"`;
  *   - opaque identifiers are strings; sets are sorted arrays; maps keyed by identifiers are
  *     objects;
  *   - every top-level artifact carries `schemaVersion`.
  *
  * The same value therefore has exactly one canonical text, and `Checksum.ofText(encode(x))` is a
  * stable content address across platforms.
  */
object Canonical:
  val printer: Printer = Printer(dropNullValues = true, indent = "", sortKeys = true)

  def print(json: Json): String = printer.print(json)

  def encode[A](a: A)(using e: Encoder[A]): String = print(e(a))

  def parse(text: String): Either[CodecError, Json] =
    io.circe.parser.parse(text).left.map(f => CodecError.Parse(f.message))

  def decodeJson[A](json: Json)(using d: Decoder[A]): Either[CodecError, A] =
    d.decodeJson(json).left.map(f => CodecError.Decode(pathOf(f), f.message))

  def decode[A](text: String)(using d: Decoder[A]): Either[CodecError, A] =
    parse(text).flatMap(decodeJson[A])

  /** Content address of the canonical text of `a`. */
  def checksum[A](a: A)(using Encoder[A]): Checksum = Checksum.ofText(encode(a))

  /** Law helper: canonical form is a fixed point of encode ∘ decode. */
  def isFixedPoint[A](a: A)(using Encoder[A], Decoder[A]): Boolean =
    val once = encode(a)
    decode[A](once).map(encode(_)) == Right(once)

  /** Law helper: exact round trip. */
  def roundTrips[A](a: A)(using Encoder[A], Decoder[A]): Boolean =
    decode[A](encode(a)) == Right(a)

  private def pathOf(f: io.circe.DecodingFailure): String =
    io.circe.CursorOp.opsToPath(f.history) match
      case "" => "$"
      case p  => p

/** Shared primitive codecs. Import `CanonicalPrimitives.given` in every codec file so that the
  * canonical `Double` rendering shadows circe's default.
  */
object CanonicalPrimitives:
  /** Doubles as `0x` + 16 lowercase hex digits of the IEEE-754 bit pattern. Decoding also accepts a
    * plain JSON number for hand-written inputs, but the canonical form is always the hex string.
    */
  given doubleEncoder: Encoder[Double] = Encoder.encodeString.contramap(CanonicalDouble.render)

  given doubleDecoder: Decoder[Double] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        parseHexDouble(s).toRight(io.circe.DecodingFailure(s"bad double $s", c.history))
      case None => Decoder.decodeDouble(c)
  }

  def parseHexDouble(s: String): Option[Double] =
    if s.length == 18 && s.startsWith("0x") && s.drop(2).forall(isLowerHex) then
      scala.util.Try(java.lang.Double.longBitsToDouble(parseUnsignedHex(s.drop(2)))).toOption
    else None

  private def isLowerHex(c: Char): Boolean = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')

  private def parseUnsignedHex(hex: String): Long =
    var acc = 0L
    var i = 0
    while i < hex.length do
      val c = hex.charAt(i)
      val d = if c <= '9' then c - '0' else c - 'a' + 10
      acc = (acc << 4) | d.toLong
      i += 1
    acc

  given bigDecimalEncoder: Encoder[BigDecimal] =
    Encoder.encodeString.contramap(v => plain(v))
  given bigDecimalDecoder: Decoder[BigDecimal] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        scala.util
          .Try(BigDecimal(s))
          .toOption
          .toRight(io.circe.DecodingFailure(s"bad decimal $s", c.history))
      case None => Decoder.decodeBigDecimal(c)
  }

  def plain(v: BigDecimal): String =
    val stripped = v.bigDecimal.stripTrailingZeros
    if stripped.signum == 0 then "0" else stripped.toPlainString

  given checksumEncoder: Encoder[Checksum] = Encoder.encodeString.contramap(_.hex)
  given checksumDecoder: Decoder[Checksum] =
    Decoder.decodeString.emap(s => Checksum.from(s).left.map(_.message))

  given probabilityEncoder: Encoder[Probability] = doubleEncoder.contramap(_.value)
  given probabilityDecoder: Decoder[Probability] =
    doubleDecoder.emap(d => Probability.from(d).left.map(_.message))

  /** Codec for an [[OpaqueId]] kind: the identifier as a string, validated on decode. */
  def opaqueEncoder(o: OpaqueId): Encoder[o.T] = Encoder.encodeString.contramap(id => o.value(id))
  def opaqueDecoder(o: OpaqueId): Decoder[o.T] =
    Decoder.decodeString.emap(s => o.from(s).left.map(_.message))
  def opaqueKeyEncoder(o: OpaqueId): KeyEncoder[o.T] = KeyEncoder.instance(id => o.value(id))
  def opaqueKeyDecoder(o: OpaqueId): KeyDecoder[o.T] =
    KeyDecoder.instance(s => o.from(s).toOption)

  /** Codec for a parameterless enum: the case name. */
  def enumEncoder[E](name: E => String): Encoder[E] = Encoder.encodeString.contramap(name)
  def enumDecoder[E](kind: String, values: Iterable[E], name: E => String): Decoder[E] =
    val byName = values.map(v => name(v) -> v).toMap
    Decoder.decodeString.emap(s => byName.get(s).toRight(s"unknown $kind: $s"))

  /** Sets are encoded as sorted arrays so that equal sets have equal text. */
  def sortedSetEncoder[A: Ordering](using e: Encoder[A]): Encoder[Set[A]] =
    Encoder.encodeVector[A].contramap(_.toVector.sorted)

  /** Read a required field, mapping a smart-constructor failure into a decoding failure. */
  def field[A](c: HCursor, name: String)(using d: Decoder[A]): Decoder.Result[A] =
    c.downField(name).as[A]

  def domain[A](c: HCursor, e: Either[DomainError, A]): Decoder.Result[A] =
    e.left.map(err => io.circe.DecodingFailure(err.message, c.history))

  /** Build an object, omitting `None` fields entirely. */
  def obj(fields: (String, Json)*): Json = Json.obj(fields.filterNot(_._2.isNull)*)

  def opt[A](a: Option[A])(using e: Encoder[A]): Json = a.fold(Json.Null)(e.apply)
