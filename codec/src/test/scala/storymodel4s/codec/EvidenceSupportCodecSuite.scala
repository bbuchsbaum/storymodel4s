package storymodel4s.codec

import io.circe.{Json, parser}
import io.circe.syntax.*
import munit.FunSuite
import storymodel4s.core.*
import CoreCodecs.given

class EvidenceSupportCodecSuite extends FunSuite:
  private val film = SourceBundle
    .filmEdition(
      EditionId.unsafe("codec-film"),
      Checksum.ofText("film"),
      0L,
      Long.MaxValue,
      RationalTimebase.Millisecond
    )
    .toOption
    .get
  private val text =
    SourceBundle.writtenText(StorySource.fromText("Alpha.").toOption.get).toOption.get
  private val interval =
    PlaybackInterval.on(film.primaryAxis, 9007199254740993L, 9007199254741001L).toOption.get
  private val set = PlaybackIntervalSet.one(interval)
  private val span = SpanSet.one(TextSpan.unsafe(0, 3))
  private def support(bundle: SourceBundle, anchor: EvidenceAnchor): EvidenceSupport =
    EvidenceSupport.of(bundle, Vector(anchor)).toOption.get
  private def evidence(anchors: Option[EvidenceSupport]): Evidence =
    Evidence(
      EvidenceId.unsafe("e"),
      None,
      Set.empty,
      Fingerprint.unsafe("f"),
      StageId.unsafe("s"),
      anchors
    )
  private val intervalJson = Json.obj(
    "axis" -> film.primaryAxis.id.value.asJson,
    "startTick" -> "9007199254740993".asJson,
    "endExclusiveTick" -> "9007199254741001".asJson
  )

  private def court(value: EvidenceSupport, expectedAnchor: Json): Unit =
    val encoded = value.asJson
    assertEquals(
      encoded,
      Json.obj("schema" -> "evidence-support/v1".asJson, "anchors" -> Json.arr(expectedAnchor))
    )
    val refusal =
      encoded.as[EvidenceSupport].swap.toOption.getOrElse(fail("0.7.0 decoder admitted support"))
    assertEquals(refusal.message, "evidence-support/v1 is unsupported by the 0.7.0 text decoder")
    val record = evidence(Some(value)).asJson
    assertEquals(record.hcursor.downField("anchors").focus, Some(encoded))
    assertEquals(
      record.as[Evidence].swap.toOption.get.message,
      "anchored Evidence is unsupported by the 0.7.0 text decoder"
    )

  test("Text payload is complete and its anchored encoding is refused on decode"):
    court(
      support(text, EvidenceAnchor.Text(text.id, text.streams.head.id, span)),
      Json.obj(
        "type" -> "Text".asJson,
        "bundle" -> text.id.value.asJson,
        "stream" -> text.streams.head.id.value.asJson,
        "spans" -> parser.parse("[{\"span\":{\"start\":0,\"end\":3}}]").toOption.get
      )
    )

  test("MediaTime payload is complete and its anchored encoding is refused on decode"):
    court(
      support(
        film,
        EvidenceAnchor.MediaTime(film.id, film.streams.head.id, film.primaryAxis.id, set)
      ),
      Json.obj(
        "type" -> "MediaTime".asJson,
        "bundle" -> film.id.value.asJson,
        "stream" -> film.streams.head.id.value.asJson,
        "axis" -> film.primaryAxis.id.value.asJson,
        "intervals" -> Json.arr(intervalJson)
      )
    )

  test("Shot payload is complete and its anchored encoding is refused on decode"):
    court(
      support(
        film,
        EvidenceAnchor.Shot(film.id, film.streams.head.id, ShotId.unsafe("shot"), interval)
      ),
      Json.obj(
        "type" -> "Shot".asJson,
        "bundle" -> film.id.value.asJson,
        "stream" -> film.streams.head.id.value.asJson,
        "shot" -> "shot".asJson,
        "interval" -> intervalJson
      )
    )

  test("Track payload is complete and its anchored encoding is refused on decode"):
    court(
      support(
        film,
        EvidenceAnchor.Track(film.id, film.streams.head.id, TrackId.unsafe("track"), set)
      ),
      Json.obj(
        "type" -> "Track".asJson,
        "bundle" -> film.id.value.asJson,
        "stream" -> film.streams.head.id.value.asJson,
        "track" -> "track".asJson,
        "intervals" -> Json.arr(intervalJson)
      )
    )

  test("accepting control: ordinary text evidence keeps its omitted-anchor wire and round trip"):
    val value = evidence(None).copy(spans = Some(span))
    assert(!value.asJson.asObject.get.contains("anchors"))
    assertEquals(value.asJson.as[Evidence], Right(value))
