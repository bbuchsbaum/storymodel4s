package storymodel4s.codec

import munit.FunSuite
import io.circe.Json
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallTiming.*

class RecallTimingCodecSuite extends FunSuite:
  import RecallTimingFixture.*
  private val value = build()
  private val encoded = RecallTimingCodecs.encode(value)
  private val json = RecallTimingCodecs.toJson(value)
  private def change(j: Json, name: String)(f: Json => Json): Json =
    j.mapObject(o => o.add(name, f(o(name).get)))
  private def first(j: Json, name: String)(f: Json => Json): Json = change(j, name)(v =>
    Json.fromValues(v.asArray.get.zipWithIndex.map((x, i) => if i == 0 then f(x) else x))
  )
  private def decode(j: Json): Either[RecallTimingCodecError, RecallTiming] =
    RecallTimingCodecs.decode(MappingJson.print(j), inventory)

  test("onset-only and exact interval records round-trip with identical bytes") {
    Vector(value, exact).foreach { original =>
      val text = RecallTimingCodecs.encode(original)
      val decoded = RecallTimingCodecs.decode(text, inventory).toOption.get
      assertEquals(decoded.digest, original.digest)
      assertEquals(RecallTimingCodecs.encode(decoded), text)
      assertEquals(decoded.unitSummaries, original.unitSummaries)
    }
    assert(RecallTimingCodecs.encode(exact).contains("\"9007199254740993\""))
    assert(RecallTimingCodecs.encode(exact).contains("\"-9223372036854775808\""))
  }
  test("decode requires the exact inventory and declared clock unit") {
    val foreign = RecallInventory
      .of(
        MappingCodecFixture.recall,
        inventory.words.map(_.span),
        WordIdPolicy.inputArtifact(Checksum.ofText("different parser"))
      )
      .toOption
      .get
    assert(RecallTimingCodecs.decode(encoded, foreign).isLeft)
    assert(
      decode(change(json, "clock")(c => change(c, "unit")(_ => Json.fromString("ticks")))).isLeft
    )
    assert(
      decode(
        first(json, "entries")(e =>
          change(e, "clock")(_ => Json.fromString(Checksum.ofText("foreign").hex))
        )
      ).isLeft
    )
  }
  test("summary forgery and declaration changes cannot retain the old digest") {
    assert(
      decode(
        first(json, "unit_onset_diagnostics")(s =>
          change(s, "supplied_intervals")(_ => Json.fromInt(1))
        )
      ).isLeft
    )
    assert(
      decode(
        first(json, "entries")(e =>
          change(e, "observation")(_ =>
            Json.obj(
              "status" -> Json.fromString("missing"),
              "reason" -> Json.fromString("NotProvided")
            )
          )
        )
      ).isLeft
    )
    assert(
      decode(change(json, "digest")(_ => Json.fromString(Checksum.ofText("forged").hex))).isLeft
    )
  }
  test("unknown null missing reserved and duplicate fields refuse") {
    val bad = Vector(
      json.mapObject(_.add("unknown", Json.Null)),
      json.mapObject(_.remove("provenance")),
      change(json, "schemaVersion")(_ => Json.fromString("recall-timing/v9")),
      first(json, "entries")(e =>
        change(e, "observation")(o => change(o, "status")(_ => Json.fromString("calibrated")))
      ),
      change(json, "provenance")(p => p.mapObject(_.add("unknown", Json.Null)))
    )
    bad.foreach(j => assert(decode(j).isLeft))
    val duplicates = Vector(
      encoded.replace(
        "\"schemaVersion\":",
        "\"schemaVersion\":\"recall-timing/v0.1\",\"schemaVersion\":"
      ),
      encoded.replace(
        "\"schemaVersion\":",
        "\"schema\\u0056ersion\":\"recall-timing/v0.1\",\"schemaVersion\":"
      ),
      encoded.replace("\"status\":\"missing\"", "\"status\":\"missing\",\"status\":\"missing\"")
    )
    duplicates.foreach(t => assert(RecallTimingCodecs.decode(t, inventory).isLeft))
  }
  test("wire order and membership are exact and complete") {
    assert(decode(change(json, "entries")(j => Json.fromValues(j.asArray.get.reverse))).isLeft)
    assert(decode(change(json, "entries")(j => Json.fromValues(j.asArray.get.drop(1)))).isLeft)
    assert(
      decode(
        change(json, "entries")(j => Json.fromValues(j.asArray.get :+ j.asArray.get.head))
      ).isLeft
    )
  }
  test("rational components must be canonical exact decimal strings") {
    val large = RecallTimingCodecs.toJson(exact)
    def badNumerator(n: Json): Json = first(large, "entries")(e =>
      change(e, "observation")(o => change(o, "seconds")(s => change(s, "numerator")(_ => n)))
    )
    Vector(
      Json.fromLong(9007199254740993L),
      Json.fromString("+9007199254740993"),
      Json.fromString("09007199254740993"),
      Json.fromString("9.007199254740993e15"),
      Json.fromString("9223372036854775808")
    ).foreach(n => assert(decode(badNumerator(n)).isLeft))
    val unreduced = first(large, "entries")(e =>
      change(e, "observation")(o =>
        change(o, "seconds")(_ =>
          Json.obj("numerator" -> Json.fromString("2"), "denominator" -> Json.fromString("2"))
        )
      )
    )
    assert(decode(unreduced).isLeft)
  }
  test("ASCII wire preserves distinct unmatched UTF-16 clock keys") {
    val texts = Vector(0xd800, 0xd801).map { n =>
      val c = Clock.declared(
        clock.artifact,
        ClockKey.unsafe(n.toChar.toString),
        clock.descriptor,
        clock.recording,
        clock.origin
      )
      val t = RecallTimingCodecs.encode(build(c = c))
      assert(t.forall(_ < 128))
      assertEquals(
        RecallTimingCodecs.encode(RecallTimingCodecs.decode(t, inventory).toOption.get),
        t
      )
      t
    }
    assertNotEquals(texts.head, texts.last)
  }
