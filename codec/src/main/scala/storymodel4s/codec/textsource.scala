package storymodel4s.codec

import java.nio.charset.StandardCharsets
import io.circe.{Decoder, HCursor, Json}
import io.circe.syntax.*
import storymodel4s.core.*
import CoreCodecs.given

/** Reconstructs the checked source instead of trusting serialized target IDs or capabilities. */
object TextSourceArtifactCodec:
  val SchemaVersion: String = "text-source/v1"
  val ExchangeSchema: String = "text-source-exchange/v1"

  /** A complete deterministic projection; the manifest is the last publication marker. */
  final class Bundle private[TextSourceArtifactCodec] (val files: Vector[(String, String)]):
    def manifest: String = files.last._2

  def encode(value: TextSourcePackage): String = Canonical.print(json(value))

  def decode(bytes: Array[Byte]): Either[CodecError, TextSourcePackage] =
    val text = new String(bytes, StandardCharsets.UTF_8)
    if !text.getBytes(StandardCharsets.UTF_8).sameElements(bytes) then
      Left(CodecError.Parse("text source is not strict UTF-8"))
    else decode(text)

  def decode(text: String): Either[CodecError, TextSourcePackage] =
    for
      input <- Canonical.parse(text)
      c = input.hcursor
      schema <- field[String](c, "schemaVersion")
      _ <- require(schema == SchemaVersion, "unsupported text-source schema")
      source <- field[StorySource](c, "source")
      tag <- field[String](c, "profile")
      profile <- TextSegmentationProfile.values
        .find(_.tag == tag)
        .toRight(CodecError.Decode("$.profile", "unknown segmentation profile"))
      atlasJson <- field[Json](c, "atlas")
      atlas <- SurfaceAtlasArtifactCodec.decode(source, Canonical.print(atlasJson))
      value <- (profile match
        case TextSegmentationProfile.SurfaceSemicolonV1 => TextSourcePackage.analyze(source)
        case TextSegmentationProfile.SuppliedAtlasV1    => TextSourcePackage.fromAtlas(atlas)
      ).left.map(CodecError.Domain.apply)
      _ <- require(input == json(value), "source artifact differs from checked reconstruction")
    yield value

  private def require(ok: Boolean, reason: String): Either[CodecError, Unit] =
    Either.cond(ok, (), CodecError.Decode("$", reason))

  private def field[A: Decoder](cursor: HCursor, name: String): Either[CodecError, A] =
    cursor.get[A](name).left.map(e => CodecError.Decode(s"$$.$name", e.message))

  private def atlasJson(value: TextSourcePackage): Json =
    // Encoding a checked atlas always produces parseable JSON.
    Canonical.parse(SurfaceAtlasArtifactCodec.encode(value.atlas)).toOption.get

  private def parent(id: Option[SurfaceUnitId]): Json = id match
    case Some(value) => Json.obj("status" -> "present".asJson, "id" -> value.value.asJson)
    case None        => Json.obj("status" -> "absent".asJson)

  private def segment(value: TextSourcePackage, s: TextSourceSegment): Json = Json.obj(
    "target_id" -> s.id.value.asJson,
    "surface_unit_id" -> s.unit.id.value.asJson,
    "kind" -> s"${value.profile.tag}:${s.unit.kind}".asJson,
    "surface_kind" -> s.unit.kind.toString.asJson,
    "ordinal" -> s.unit.ordinal.asJson,
    "start" -> s.unit.span.start.asJson,
    "end_exclusive" -> s.unit.span.endExclusive.asJson,
    "parent" -> parent(s.unit.parent),
    "text" -> s.unit.text(value.source).asJson
  )

  private def json(value: TextSourcePackage): Json = Json.obj(
    "schemaVersion" -> SchemaVersion.asJson,
    "profile" -> value.profile.tag.asJson,
    "canonicalization" -> TextSourcePackage.Canonicalization.asJson,
    "offset_unit" -> TextSourcePackage.OffsetUnit.asJson,
    "coordinate_text" -> "source.canonicalText".asJson,
    "source" -> value.source.asJson,
    "atlas" -> atlasJson(value),
    "capabilities" -> Json.obj(
      "character_offsets" -> Json.obj("status" -> "available".asJson),
      "discourse_ordinals" -> Json.obj("status" -> "available".asJson),
      "narrative_model" -> Json
        .obj("status" -> "unavailable".asJson, "reason" -> "not-supplied".asJson),
      "encoding_seconds" -> Json.obj(
        "status" -> "unavailable".asJson,
        "reason" -> "no-presentation-schedule".asJson
      )
    ),
    "segments" -> Json.fromValues(value.segments.map(segment(value, _)))
  )

  private val Columns: Vector[(String, String)] = Vector(
    "canonical_source_sha256" -> "utf8-string",
    "offset_unit" -> "utf8-string",
    "profile" -> "utf8-string",
    "target_id" -> "utf8-string",
    "surface_unit_id" -> "utf8-string",
    "kind" -> "utf8-string",
    "surface_kind" -> "utf8-string",
    "ordinal" -> "decimal-integer",
    "start" -> "decimal-integer",
    "end_exclusive" -> "decimal-integer",
    "parent" -> "canonical-json",
    "text" -> "utf8-string"
  )

  private def quote(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

  /** Same quoted-tsv/v1 wire as mapping and narrative exports, with explicit text coordinates. */
  def exchange(value: TextSourcePackage): Bundle =
    val rows = value.segments.map { s =>
      Vector(
        value.source.canonicalChecksum.hex,
        TextSourcePackage.OffsetUnit,
        value.profile.tag,
        s.id.value,
        s.unit.id.value,
        s"${value.profile.tag}:${s.unit.kind}",
        s.unit.kind.toString,
        s.unit.ordinal.toString,
        s.unit.span.start.toString,
        s.unit.span.endExclusive.toString,
        Canonical.print(parent(s.unit.parent)),
        s.unit.text(value.source)
      )
    }
    val table =
      (Columns.map(_._1) +: rows).map(_.map(quote).mkString("\t")).mkString("", "\n", "\n")
    val payloads = Vector("source.json" -> encode(value), "segments.tsv" -> table)
    val manifest = Canonical.print(
      Json.obj(
        "schemaVersion" -> ExchangeSchema.asJson,
        "wire" -> "quoted-tsv/v1".asJson,
        "files" -> Json.fromValues(payloads.map { (name, content) =>
          Json.obj(
            "name" -> name.asJson,
            "sha256" -> Checksum.ofText(content).hex.asJson,
            "bytes" -> content.getBytes(StandardCharsets.UTF_8).length.asJson
          )
        }),
        "columns" -> Json.fromValues(Columns.map { (name, kind) =>
          Json.obj("name" -> name.asJson, "type" -> kind.asJson)
        })
      )
    )
    new Bundle(payloads :+ ("manifest.json" -> manifest))

  /** Refuse missing, extra or altered projections rather than independently trusting a table. */
  def decodeExchange(files: Map[String, String]): Either[CodecError, TextSourcePackage] =
    for
      source <- files.get("source.json").toRight(CodecError.Decode("$", "missing source.json"))
      value <- decode(source)
      _ <- require(files == exchange(value).files.toMap, "exchange differs from checked source")
    yield value
