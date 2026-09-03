package storymodel4s.codec

import io.circe.Json
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.RecallGraph
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.story.*
import CoreCodecs.given
import RecallCodecs.given
import StoryModelCodec.given

class StoryModelCodecSuite extends FunSuite:
  import CodecFixture.*

  /** Golden content checksum of the fixture model, computed on the JVM and asserted on every
    * platform: this is the cross-platform stability law for the canonical form. Update only when
    * the wire format deliberately changes.
    *
    * Moved once, deliberately, when schema 0.3.0 replaced a holder-bearing context kind's `entity`
    * field with a `holder` object that can also say the holder was not derived. The fixture's
    * speech context is the only claim that moved.
    */
  val GoldenChecksum: String = "9b52dee0591a9e644bd24f141c115f270b0532deaf9fb6868998e7cc1604196d"

  test("fixture validates") {
    assert(validated.claims.nonEmpty)
  }

  test("StoryModel: decode(encode(m)) revalidates and carries identical content") {
    val text = StoryModelCodec.encode(validated)
    val decoded = StoryModelCodec.decode(text).fold(e => fail(e.message), identity)
    assertEquals(decoded.source, validated.source)
    assertEquals(decoded.atlas, validated.atlas)
    assertEquals(decoded.graph, validated.graph)
    assertEquals(decoded.hierarchy, validated.hierarchy)
    assertEquals(decoded.trajectory, validated.trajectory)
    assertEquals(decoded.featureSpaces, validated.featureSpaces)
    assertEquals(decoded.sidecars, validated.sidecars)
    assertEquals(decoded.featureRefs, validated.featureRefs)
    assertEquals(decoded.descriptors, validated.descriptors)
    assertEquals(decoded.hypotheses, validated.hypotheses)
    assertEquals(decoded.sensoryProfiles, validated.sensoryProfiles)
    assertEquals(decoded.receipt, validated.receipt)
    val revalidated = StoryValidator.validate(decoded, ValidationPolicy.default).validated
    assert(revalidated.isDefined, "decoded draft must revalidate")
    assertEquals(
      StoryModelCodec.contentChecksum(revalidated.get),
      StoryModelCodec.contentChecksum(validated)
    )
  }

  test("StoryModel: status is excluded — Draft, Validated, and Adjudicated hash identically") {
    val d = StoryModelCodec.contentChecksum(draft)
    val v = StoryModelCodec.contentChecksum(validated)
    val a = StoryModelCodec.contentChecksum(StoryModel.adjudicated(validated))
    assertEquals(d, v)
    assertEquals(v, a)
    assertEquals(StoryModelCodec.encode(draft), StoryModelCodec.encode(validated))
  }

  test("StoryModel: canonical form is a fixed point") {
    val once = StoryModelCodec.encode(validated)
    val twice = StoryModelCodec.decode(once).map(StoryModelCodec.encode(_))
    assertEquals(twice, Right(once))
  }

  test("StoryModel: canonical text carries the text exactly once and no null") {
    val text = StoryModelCodec.encode(validated)
    assertEquals(countOf(text, "\"canonicalText\""), 1)
    assert(!text.contains(":null"))
    assert(text.startsWith("{\"atlas\""), text.take(20))
  }

  test("StoryModel: golden content checksum is platform-stable") {
    val sum = StoryModelCodec.contentChecksum(validated).hex
    assertEquals(sum, GoldenChecksum, s"content checksum changed: $sum")
  }

  test("StoryModel: an atlas over a different source is rejected") {
    val json = summon[io.circe.Encoder[StoryModel[ModelStatus.Validated]]](validated)
    // units from a longer text exceed the model source: rejected at decode by atlas validation
    val longer = StorySource
      .fromText("Something else entirely, and considerably longer than the fixture text is.")
      .toOption
      .get
    val longerAtlas = SurfaceAnalyzer.analyze(longer)
    val tampered = json.mapObject(_.add("atlas", CoreCodecs.atlasUnitsEncoder(longerAtlas)))
    assert(Canonical.decodeJson[StoryModel[ModelStatus.Draft]](tampered).isLeft)
    // units from a shorter text fit the bounds and decode, but the model no longer revalidates
    val shorter = SurfaceAnalyzer.analyze(StorySource.fromText("Short one.").toOption.get)
    val decodable = json.mapObject(_.add("atlas", CoreCodecs.atlasUnitsEncoder(shorter)))
    val decoded = Canonical.decodeJson[StoryModel[ModelStatus.Draft]](decodable)
    assert(decoded.isRight)
    assert(
      StoryValidator.validate(decoded.toOption.get, ValidationPolicy.default).validated.isEmpty
    )
  }

  test("StoryModel: tampered source checksum is rejected") {
    val json = summon[io.circe.Encoder[StoryModel[ModelStatus.Validated]]](validated)
    val tampered = json.hcursor
      .downField("source")
      .downField("canonicalChecksum")
      .set(Json.fromString("0" * 64))
      .top
      .get
    assert(Canonical.decodeJson[StoryModel[ModelStatus.Draft]](tampered).isLeft)
  }

  test("StoryModel: unknown schema version is rejected") {
    val json = summon[io.circe.Encoder[StoryModel[ModelStatus.Validated]]](validated)
      .mapObject(_.add("schemaVersion", Json.fromString("2.0.0")))
    Canonical.decodeJson[StoryModel[ModelStatus.Draft]](json) match
      case Left(CodecError.Decode(_, m)) => assert(m.contains("unsupported schema"), m)
      case other                         => fail(s"expected schema rejection, got $other")
  }

  test("RecallGraph: exact round trip and fixed point") {
    val text = Canonical.encode(recall)
    assertEquals(Canonical.decode[RecallGraph[Checked]](text), Right(recall))
    assert(Canonical.isFixedPoint(recall))
  }

  test("TranscriptAtlas: exact round trip and fixed point") {
    val text = Canonical.encode(transcriptAtlas)
    assertEquals(Canonical.decode[TranscriptAtlas](text), Right(transcriptAtlas))
    assert(Canonical.isFixedPoint(transcriptAtlas))
  }

  test("Migration.decode reads a current-version model") {
    val text = StoryModelCodec.encode(validated)
    assert(Migration.decode[StoryModel[ModelStatus.Draft]](text).isRight)
  }

  private def countOf(text: String, needle: String): Int =
    var n = 0
    var i = text.indexOf(needle)
    while i >= 0 do
      n += 1
      i = text.indexOf(needle, i + needle.length)
    n
