package storymodel4s.codec

import io.circe.Json
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*

/** Stdout export keeps the synthetic fixture generator portable across all codec backends. */
object TemporalSupportFixture:
  def main(args: Array[String]): Unit =
    require(args.isEmpty, "no arguments")
    import MappingCompositionFixture.*
    val large = largeCoordinates.source
    val largeBundle = large.bundles.head.asInstanceOf[BundleEntry.Media].bundle
    Vector(
      "group" -> TemporalSupport.read(
        source(true),
        group,
        TemporalSupport.Selection.Part(a.identity)
      ),
      "point" -> TemporalSupport.read(
        source(true),
        e(8),
        TemporalSupport.Selection.Occurrence(mappings(1).identity, OccurrenceId.unsafe("part-1"))
      ),
      "large" -> TemporalSupport.read(
        large,
        large.targets.head.ref,
        TemporalSupport.Selection.Part(largeBundle.identity)
      )
    ).foreach { (name, result) =>
      println(
        "TEMPORAL_SUPPORT_FIXTURE\t" + name + "\t" + TemporalSupportCodecs.encode(
          result.toOption.get
        )
      )
    }

class TemporalSupportCodecSuite extends FunSuite:
  import MappingCompositionFixture.*
  private def value = TemporalSupport
    .read(source(true), group, TemporalSupport.Selection.Part(a.identity))
    .toOption
    .get
  test(
    "contextual support query round-trip retains missing descendants and original source digest"
  ) {
    val result = value
    val text = TemporalSupportCodecs.encode(result)
    val decoded = TemporalSupportCodecs.decode(text, source(true)).toOption.get
    assertEquals(TemporalSupportCodecs.encode(decoded), text)
    assertEquals(decoded.unlocatedDescendants, Vector(e(4)))
    assertEquals(decoded.sourceDigest, source(true).digest)
    assert(TemporalSupportCodecs.decode(text, source(false)).isLeft)
    val occurrence = TemporalSupport
      .read(
        source(true),
        e(8),
        TemporalSupport.Selection.Occurrence(mappings(1).identity, OccurrenceId.unsafe("part-1"))
      )
      .toOption
      .get
    val wire = TemporalSupportCodecs.encode(occurrence)
    assertEquals(
      TemporalSupportCodecs.encode(TemporalSupportCodecs.decode(wire, source(true)).toOption.get),
      wire
    )
  }
  test("query wire rejects forged geometry completeness unknown fields and duplicate keys") {
    val json = TemporalSupportCodecs.toJson(value)
    val alterations = Vector(
      json.mapObject(_.add("unlocated_descendants", Json.arr())),
      json.mapObject(_.add("source_digest", Json.fromString("0" * 64))),
      json.mapObject(_.add("nodes", Json.arr())),
      json.mapObject(_.add("ignored", Json.Null)),
      json.mapObject(_.add("schemaVersion", Json.fromString("future")))
    )
    alterations.foreach(j => assert(TemporalSupportCodecs.decode(j.noSpaces, source(true)).isLeft))
    assert(
      TemporalSupportCodecs
        .decode(json.noSpaces.dropRight(1) + ",\"nodes\":[]}", source(true))
        .isLeft
    )
  }
  test("large coordinate query uses exact string ticks and refuses rounded numeric geometry") {
    val s = largeCoordinates.source
    val bundle = s.bundles.head.asInstanceOf[BundleEntry.Media].bundle
    val result = TemporalSupport
      .read(s, s.targets.head.ref, TemporalSupport.Selection.Part(bundle.identity))
      .toOption
      .get
    val encoded = TemporalSupportCodecs.encode(result)
    assert(encoded.contains("\"9007199254740993\""))
    assert(TemporalSupportCodecs.decode(encoded, s).isRight)
    assert(
      TemporalSupportCodecs
        .decode(encoded.replace("\"9007199254740993\"", "9007199254740992"), s)
        .isLeft
    )
  }
