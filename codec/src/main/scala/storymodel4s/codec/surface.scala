package storymodel4s.codec

import java.nio.charset.StandardCharsets

import io.circe.{Decoder, HCursor, Json}
import storymodel4s.core.*
import CanonicalPrimitives.given
import CoreCodecs.given

/** Canonical, source-relative standoff artifact for a [[SurfaceAtlas]].
  *
  * The root binds its units to one supplied [[StorySource]] by both story id and canonical source
  * checksum. It never embeds raw or canonical text. Unit order on the wire is kind ordinal followed
  * by within-kind ordinal, independent of the input vector order.
  *
  * Decoding therefore needs the actual source: ids and checksums alone never reconstruct text. A
  * lawful in-memory atlas may use another cross-kind vector order, so encoding normalizes that
  * order and decoding returns the canonical wire order while preserving the same logical units.
  */
object SurfaceAtlasArtifactCodec:
  val SchemaVersion: String = "surface-atlas/v1"

  /** Encode canonical UTF-8 JSON text without duplicating source text. */
  def encode(atlas: SurfaceAtlas): String =
    Canonical.print(
      Json.obj(
        "schemaVersion" -> Json.fromString(SchemaVersion),
        "storyId" -> summon[io.circe.Encoder[StoryId]](atlas.source.id),
        "canonicalSourceChecksum" ->
          summon[io.circe.Encoder[Checksum]](atlas.source.canonicalChecksum),
        "units" -> summon[io.circe.Encoder[Vector[SurfaceUnit]]](canonicalUnits(atlas.units))
      )
    )

  /** Decode canonical artifact text against the source that owns its UTF-16 coordinate axis. */
  def decode(source: StorySource, text: String): Either[CodecError, SurfaceAtlas] =
    Canonical.parse(text).flatMap(decodeJson(source, _))

  /** Decode strict UTF-8 bytes; malformed input is refused rather than replacement-decoded. */
  def decode(source: StorySource, bytes: Array[Byte]): Either[CodecError, SurfaceAtlas] =
    val text = new String(bytes, StandardCharsets.UTF_8)
    if text.getBytes(StandardCharsets.UTF_8).sameElements(bytes) then decode(source, text)
    else Left(CodecError.Parse(s"$SchemaVersion artifact is not strict UTF-8"))

  /** Content checksum of the exact canonical UTF-8 bytes returned by [[encode]]. */
  def checksum(atlas: SurfaceAtlas): Checksum =
    Checksum.ofBytes(encode(atlas).getBytes(StandardCharsets.UTF_8))

  private def decodeJson(source: StorySource, json: Json): Either[CodecError, SurfaceAtlas] =
    val cursor = json.hcursor
    for
      version <- field[String](cursor, "schemaVersion")
      _ <-
        if version == SchemaVersion then Right(())
        else Left(CodecError.UnsupportedSchema(version, Vector(SchemaVersion)))
      storyId <- field[StoryId](cursor, "storyId")
      _ <- requireField(
        storyId == source.id,
        "$.storyId",
        "story id does not match the supplied StorySource"
      )
      sourceChecksum <- field[Checksum](cursor, "canonicalSourceChecksum")
      _ <- requireField(
        sourceChecksum == source.canonicalChecksum,
        "$.canonicalSourceChecksum",
        "canonical source checksum does not match the supplied StorySource"
      )
      units <- field[Vector[SurfaceUnit]](cursor, "units")
      _ <- requireField(
        units == canonicalUnits(units),
        "$.units",
        "units are not in canonical kind-ordinal then unit-ordinal order"
      )
      atlas <- SurfaceAtlas.of(source, units).left.map(CodecError.Domain.apply)
    yield atlas

  private def canonicalUnits(units: Vector[SurfaceUnit]): Vector[SurfaceUnit] =
    units.sortBy(unit => (unit.kind.ordinal, unit.ordinal))

  private def field[A: Decoder](cursor: HCursor, name: String): Either[CodecError, A] =
    cursor.downField(name).as[A].left.map { failure =>
      val path = io.circe.CursorOp.opsToPath(failure.history) match
        case ""    => "$"
        case value => value
      CodecError.Decode(path, failure.message)
    }

  private def requireField(
      condition: Boolean,
      path: String,
      detail: => String
  ): Either[CodecError, Unit] =
    Either.cond(condition, (), CodecError.Decode(path, detail))
