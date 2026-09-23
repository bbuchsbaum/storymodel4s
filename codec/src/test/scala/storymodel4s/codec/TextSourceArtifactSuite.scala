package storymodel4s.codec

import munit.FunSuite
import io.circe.Json
import storymodel4s.core.*

/** Synthetic Unicode/quoting diagnostics, not narrative or participant data. */
class TextSourceArtifactSuite extends FunSuite:
  private val source =
    TextSourcePackage.fromText("\uFEFFA\u0301😀; B.\r\n\r\nC. \"D\"\tE.").toOption.get
  private def altered(f: Json => Json): String =
    Canonical.print(f(Canonical.parse(TextSourceArtifactCodec.encode(source)).toOption.get))

  test("generated source artifact round-trips raw bytes and exact segments"):
    val encoded = TextSourceArtifactCodec.encode(source)
    val decoded = TextSourceArtifactCodec.decode(encoded).toOption.get
    assertEquals(decoded.source, source.source)
    assertEquals(
      decoded.segments.map(s => (s.id, s.unit)),
      source.segments.map(s => (s.id, s.unit))
    )
    assertEquals(TextSourceArtifactCodec.encode(decoded), encoded)

  test("supplied atlas round-trips nonstandard IDs and missing grain"):
    val unit = source.atlas.paragraphs.head.copy(id = SurfaceUnitId.unsafe("given-id"), ordinal = 4)
    val atlas = SurfaceAtlas.of(source.source, Vector(unit)).toOption.get
    val supplied = TextSourcePackage.fromAtlas(atlas).toOption.get
    val read = TextSourceArtifactCodec.decode(TextSourceArtifactCodec.encode(supplied)).toOption.get
    assertEquals(read.atlas.units, Vector(unit))
    assertEquals(read.at(SurfaceUnitKind.Clause), Vector.empty)

  test("forged target IDs and capabilities are refused by reconstruction"):
    val badIds = altered(_.mapObject(_.add("segments", Json.arr())))
    val badCapabilities = altered(_.mapObject(_.add("capabilities", Json.obj())))
    assert(TextSourceArtifactCodec.decode(badIds).isLeft)
    assert(TextSourceArtifactCodec.decode(badCapabilities).isLeft)

  test("unsupported or mislabeled profiles and foreign source checksums are refused"):
    assert(
      TextSourceArtifactCodec
        .decode(altered(_.mapObject(_.add("profile", Json.fromString("unknown/v1")))))
        .isLeft
    )
    assert(
      TextSourceArtifactCodec
        .decode(altered(_.mapObject(_.add("profile", Json.fromString("supplied-atlas/v1")))))
        .isLeft
    )
    assert(TextSourceArtifactCodec.decode(altered(_.mapObject(_.add("source", Json.obj())))).isLeft)

  test("complete exchange round-trips but tampered or additional files are refused"):
    val files = TextSourceArtifactCodec.exchange(source).files.toMap
    assert(TextSourceArtifactCodec.decodeExchange(files).isRight)
    assert(TextSourceArtifactCodec.decodeExchange(files.updated("segments.tsv", "")).isLeft)
    assert(TextSourceArtifactCodec.decodeExchange(files - "manifest.json").isLeft)
    assert(TextSourceArtifactCodec.decodeExchange(files.updated("extra.tsv", "")).isLeft)

  test("standalone table qualifies heuristic kinds and declares canonical UTF-16"):
    val tsv = TextSourceArtifactCodec.exchange(source).files.toMap.apply("segments.tsv")
    assert(tsv.contains("\"surface-semicolon/v1:Clause\""))
    assert(tsv.contains("\"utf-16-code-units\""))
    assert(tsv.contains(source.source.canonicalChecksum.hex))
    assert(tsv.contains("\"\"D\"\""))

  test("malformed input bytes are refused before JSON decoding"):
    assert(TextSourceArtifactCodec.decode(Array(0xed.toByte, 0xa0.toByte, 0x80.toByte)).isLeft)
