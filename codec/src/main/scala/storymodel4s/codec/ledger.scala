package storymodel4s.codec

import io.circe.{Decoder, Encoder, Json}
import storymodel4s.core.*
import CoreCodecs.given

/** Append-only JSON Lines: one canonical object per line, never rewritten.
  *
  * Why: claim and candidate ledgers (design record §31.5 `claims.jsonl`, `candidates.jsonl`,
  * `patches.jsonl`) must be diffable by line and safe to append to concurrently.
  */
object JsonLines:
  def encodeLine[A](a: A)(using Encoder[A]): String = Canonical.encode(a)

  def encodeAll[A](as: Iterable[A])(using Encoder[A]): String =
    as.iterator.map(encodeLine[A]).mkString("", "\n", if as.isEmpty then "" else "\n")

  /** Appends `as` to an existing JSONL text; the result always ends with a newline. */
  def append[A](existing: String, as: Iterable[A])(using Encoder[A]): String =
    val base =
      if existing.isEmpty || existing.endsWith("\n") then existing else existing + "\n"
    base + encodeAll(as)

  /** Decodes every non-blank line; the first failure names its 1-based line number. */
  def decodeAll[A](text: String)(using Decoder[A]): Either[CodecError, Vector[A]] =
    val lines = text.split("\n", -1).toVector.zipWithIndex.filter(_._1.trim.nonEmpty)
    lines.foldLeft[Either[CodecError, Vector[A]]](Right(Vector.empty)) { case (acc, (line, i)) =>
      acc.flatMap { v =>
        Canonical
          .decode[A](line)
          .left
          .map {
            case CodecError.Decode(p, m) => CodecError.Decode(s"line ${i + 1} $p", m)
            case CodecError.Parse(m)     => CodecError.Parse(s"line ${i + 1}: $m")
            case other                   => other
          }
          .map(v :+ _)
      }
    }

  /** Claim ledger lines: one [[ClaimMeta]] per line in ledger order. */
  def claims(ledger: ClaimLedger): String = encodeAll(ledger.all)

  def readClaims(text: String): Either[CodecError, ClaimLedger] =
    decodeAll[ClaimMeta](text).flatMap(ms =>
      ClaimLedger.empty.addAll(ms).left.map(CodecError.Domain.apply)
    )

/** Schema migration skeleton keyed by `schemaVersion`.
  *
  * The current version migrates by identity; unknown versions are a typed error rather than a
  * best-effort read, so a consumer never silently interprets an artifact under the wrong schema.
  */
object Migration:
  final case class Step(from: String, to: String, apply: Json => Either[CodecError, Json])

  /** Registered steps, oldest first. Empty while only one schema version exists. */
  val steps: Vector[Step] = Vector.empty

  def versionOf(json: Json): Either[CodecError, String] =
    json.hcursor
      .downField("schemaVersion")
      .as[String]
      .left
      .map(_ => CodecError.Decode("$.schemaVersion", "missing schemaVersion"))

  /** Migrates `json` to the current schema, or fails with [[CodecError.UnsupportedSchema]]. */
  def toCurrent(json: Json): Either[CodecError, Json] =
    versionOf(json).flatMap { v =>
      if v == SchemaVersions.Current then Right(json)
      else
        steps.find(_.from == v) match
          case Some(step) => step.apply(json).flatMap(toCurrent)
          case None       =>
            Left(CodecError.UnsupportedSchema(v, SchemaVersions.Supported ++ steps.map(_.from)))
    }

  /** Parse, migrate, decode. */
  def decode[A](text: String)(using Decoder[A]): Either[CodecError, A] =
    Canonical.parse(text).flatMap(toCurrent).flatMap(Canonical.decodeJson[A])

/** Placeholder for the address seam (codec milestone §1 item 1).
  *
  * `core.Address` and the module-local typed refs are being landed by the reference-seam bead;
  * until they are on main, addresses cross the wire as their canonical rendered string and are
  * re-typed by the consumer. TODO(reference-seam): replace with `core.Address` codec
  * (`Address.parse` as the decoder) once `core/address.scala` is committed.
  */
opaque type AddressString = String
object AddressString:
  def apply(rendered: String): AddressString = rendered
  extension (a: AddressString) def rendered: String = a
  given Encoder[AddressString] = Encoder.encodeString.contramap(_.rendered)
  given Decoder[AddressString] = Decoder.decodeString.map(AddressString.apply)
