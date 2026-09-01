package storymodel4s.codec

import java.nio.charset.StandardCharsets

import io.circe.Json
import munit.FunSuite
import storymodel4s.core.*
import CanonicalPrimitives.given
import CoreCodecs.given

class SurfaceAtlasArtifactCodecSuite extends FunSuite:
  private def source(text: String): StorySource = StorySource.fromText(text).toOption.get

  private def canonicalUnits(units: Vector[SurfaceUnit]): Vector[SurfaceUnit] =
    units.sortBy(unit => (unit.kind.ordinal, unit.ordinal))

  private def wire(source: StorySource, units: Vector[SurfaceUnit]): String =
    Canonical.print(
      Json.obj(
        "schemaVersion" -> Json.fromString(SurfaceAtlasArtifactCodec.SchemaVersion),
        "storyId" -> summon[io.circe.Encoder[StoryId]](source.id),
        "canonicalSourceChecksum" ->
          summon[io.circe.Encoder[Checksum]](source.canonicalChecksum),
        "units" -> summon[io.circe.Encoder[Vector[SurfaceUnit]]](units)
      )
    )

  private def replaceRoot(text: String, name: String, value: Json): String =
    val root = Canonical.parse(text).toOption.flatMap(_.asObject).get
    Canonical.print(Json.fromJsonObject(root.add(name, value)))

  private def dropRoot(text: String, name: String): String =
    val root = Canonical.parse(text).toOption.flatMap(_.asObject).get
    Canonical.print(Json.fromJsonObject(root.remove(name)))

  test("canonical encode, strict UTF-8 decode, checksum, and re-encode are fixed") {
    val src = source("First sentence.\n\nSecond 😀 sentence.")
    val atlas = SurfaceAnalyzer.analyze(src)
    val encoded = SurfaceAtlasArtifactCodec.encode(atlas)
    val decoded = SurfaceAtlasArtifactCodec.decode(src, encoded).toOption.get
    val bytes = encoded.getBytes(StandardCharsets.UTF_8)

    assertEquals(decoded.source, src)
    assertEquals(decoded.units, canonicalUnits(atlas.units))
    assertEquals(SurfaceAtlasArtifactCodec.encode(decoded), encoded)
    assertEquals(SurfaceAtlasArtifactCodec.decode(src, bytes), Right(decoded))
    assertEquals(SurfaceAtlasArtifactCodec.checksum(atlas), Checksum.ofBytes(bytes))
  }

  test("encoding normalizes lawful cross-kind vector order") {
    val src = source("One two. Three four.\n\nFive six.")
    val analyzed = SurfaceAnalyzer.analyze(src)
    val interleaved = analyzed.units.sortBy(unit => (unit.ordinal, unit.kind.ordinal))
    val expected = canonicalUnits(analyzed.units)

    assertNotEquals(interleaved, expected)
    val lawful = SurfaceAtlas.of(src, interleaved).toOption.get
    assertEquals(
      SurfaceAtlasArtifactCodec.encode(lawful),
      SurfaceAtlasArtifactCodec.encode(analyzed)
    )

    val decoded = SurfaceAtlasArtifactCodec
      .decode(src, SurfaceAtlasArtifactCodec.encode(lawful))
      .toOption
      .get
    assertEquals(decoded.units, expected)
    assertEquals(decoded.units.toSet, lawful.units.toSet)
  }

  test("decode refuses noncanonical unit order before SurfaceAtlas can normalize it") {
    val src = source("One two. Three four.\n\nFive six.")
    val atlas = SurfaceAnalyzer.analyze(src)
    val interleaved = atlas.units.sortBy(unit => (unit.ordinal, unit.kind.ordinal))

    assertNotEquals(interleaved, canonicalUnits(interleaved))
    assert(SurfaceAtlas.of(src, interleaved).isRight, "control: core accepts cross-kind order")
    assert(SurfaceAtlasArtifactCodec.decode(src, wire(src, interleaved)).isLeft)
  }

  test("schema, supplied source identity, and required identity fields are checked") {
    val src = source("Identity-bound text.")
    val atlas = SurfaceAnalyzer.analyze(src)
    val encoded = SurfaceAtlasArtifactCodec.encode(atlas)
    val foreign = source("Different text.")

    val badSchema = replaceRoot(encoded, "schemaVersion", Json.fromString("surface-atlas/v999"))
    val badStory = replaceRoot(encoded, "storyId", Json.fromString(foreign.id.value))
    val badChecksum = replaceRoot(
      encoded,
      "canonicalSourceChecksum",
      Json.fromString(foreign.canonicalChecksum.hex)
    )

    assert(
      SurfaceAtlasArtifactCodec
        .decode(src, badSchema)
        .left
        .exists(_.isInstanceOf[CodecError.UnsupportedSchema])
    )
    assert(SurfaceAtlasArtifactCodec.decode(src, badStory).isLeft)
    assert(SurfaceAtlasArtifactCodec.decode(src, badChecksum).isLeft)
    assert(SurfaceAtlasArtifactCodec.decode(src, dropRoot(encoded, "storyId")).isLeft)
    assert(
      SurfaceAtlasArtifactCodec.decode(src, dropRoot(encoded, "canonicalSourceChecksum")).isLeft
    )
  }

  test("duplicate ids and within-kind ordinals are refused") {
    val src = source("One two three.")
    val units = canonicalUnits(SurfaceAnalyzer.analyze(src).units)
    val tokenIndices = units.indices.filter(index => units(index).kind == SurfaceUnitKind.Token)
    val first = tokenIndices(0)
    val second = tokenIndices(1)

    val duplicateId = units.updated(second, units(second).copy(id = units(first).id))
    val duplicateOrdinal = units.updated(second, units(second).copy(ordinal = units(first).ordinal))

    assert(SurfaceAtlasArtifactCodec.decode(src, wire(src, duplicateId)).isLeft)
    assert(SurfaceAtlasArtifactCodec.decode(src, wire(src, duplicateOrdinal)).isLeft)
  }

  test("missing parent and out-of-bounds span are refused") {
    val src = source("One two three.")
    val units = canonicalUnits(SurfaceAnalyzer.analyze(src).units)
    val tokenIndex = units.indexWhere(_.kind == SurfaceUnitKind.Token)
    val missingParent = units.updated(
      tokenIndex,
      units(tokenIndex).copy(parent = Some(SurfaceUnitId.unsafe("surface:missing-parent")))
    )
    val outOfBounds = units.updated(
      tokenIndex,
      units(tokenIndex).copy(span = TextSpan.unsafe(0, src.canonicalText.length + 1))
    )

    assert(SurfaceAtlasArtifactCodec.decode(src, wire(src, missingParent)).isLeft)
    assert(SurfaceAtlasArtifactCodec.decode(src, wire(src, outOfBounds)).isLeft)
  }

  test("astral characters retain UTF-16 coordinates") {
    val src = source("A 😀 B.")
    val encoded = SurfaceAtlasArtifactCodec.encode(SurfaceAnalyzer.analyze(src))
    val decoded = SurfaceAtlasArtifactCodec.decode(src, encoded).toOption.get
    val tokenB = decoded.tokens.find(_.text(src) == "B").get

    assertEquals(src.canonicalText.indexOf("B"), 5)
    assertEquals(tokenB.span, TextSpan.unsafe(5, 6))
  }

  test("artifact contains standoff only and never embeds source text") {
    val marker = "UNIQUE_SOURCE_PAYLOAD_47b9"
    val src = source(s"$marker appears here.")
    val encoded = SurfaceAtlasArtifactCodec.encode(SurfaceAnalyzer.analyze(src))

    assert(!encoded.contains("rawText"), encoded)
    assert(!encoded.contains("canonicalText"), encoded)
    assert(!encoded.contains(marker), encoded)
  }

  test("raw-different canonical-same sources lawfully share one surface artifact") {
    val rawA = "Hello\r\n\r\n\r\nworld  \n"
    val rawB = "Hello\n\nworld"
    val sourceA = source(rawA)
    val sourceB = source(rawB)
    val atlasA = SurfaceAnalyzer.analyze(sourceA)
    val atlasB = SurfaceAnalyzer.analyze(sourceB)

    assertNotEquals(sourceA.rawChecksum, sourceB.rawChecksum)
    assertEquals(sourceA.id, sourceB.id)
    assertEquals(sourceA.canonicalChecksum, sourceB.canonicalChecksum)
    assertEquals(SurfaceAtlasArtifactCodec.encode(atlasA), SurfaceAtlasArtifactCodec.encode(atlasB))
    assertEquals(
      SurfaceAtlasArtifactCodec.checksum(atlasA),
      SurfaceAtlasArtifactCodec.checksum(atlasB)
    )
    assert(
      SurfaceAtlasArtifactCodec.decode(sourceA, SurfaceAtlasArtifactCodec.encode(atlasB)).isRight
    )
    assert(
      SurfaceAtlasArtifactCodec.decode(sourceB, SurfaceAtlasArtifactCodec.encode(atlasA)).isRight
    )
  }

  test("byte decoder refuses malformed UTF-8 instead of replacement decoding") {
    val src = source("Strict UTF-8.")
    val malformed = Array[Byte]('{'.toByte, 0xc3.toByte, '('.toByte, '}'.toByte)
    assert(SurfaceAtlasArtifactCodec.decode(src, malformed).isLeft)
  }
