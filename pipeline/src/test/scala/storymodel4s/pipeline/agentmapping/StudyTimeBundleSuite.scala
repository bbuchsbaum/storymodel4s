package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.core.Checksum

/** A condition that answers with a film and a time, end to end on an invented source: how each
  * answer is read, judged and recorded, and how a tampered time bundle is refused.
  */
class StudyTimeBundleSuite extends FunSuite:
  import StudyReplay.{RecallUnit, ReplyProtocol, Stated, UnitReply}
  import TimeStudyFixture.{intervals, view}

  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def json(text: String): Json = right(parse(text))

  private val protocol = ReplyProtocol.StrictChunkedTime
  private val protocolName = "strict-chunked-time/v1"
  private val texts = Vector("she came in", "he stood there", "um", "a mouse", "what", "then")
  private val units = texts.zipWithIndex.map((t, i) => RecallUnit(i + 1, Json.fromInt(10 + i), t))
  private lazy val recall =
    right(PreparedRecall.supplied("p1_s1", texts, Checksum.ofText(texts.mkString("|"))))
  private def item(body: String) = UnitReply.Answered(json(body))
  private val replies = Vector(
    // A time inside a scene; and a time on a boundary, which belongs to the later scene.
    item(
      """{"referent_film":1,"referent_time":"0:10","narrative_film":1,"narrative_time":"0:20.5","conf":0.9}"""
    ),
    // After the end of the clip; and exactly at its end, which is inside the last scene.
    item(
      """{"referent_film":3,"referent_time":"0:25","narrative_film":3,"narrative_time":"0:20"}"""
    ),
    // No time: no assignment, or the film alone.
    item(
      """{"referent_film":null,"referent_time":null,"narrative_film":3,"narrative_time":null}"""
    ),
    // A time in the cartoon, which has no clip clock; and a time that is not m:ss.
    item("""{"referent_film":2,"referent_time":"0:05","narrative_film":1,"narrative_time":"5"}"""),
    UnitReply.Omitted,
    item("""{"referent_film":null,"referent_time":"0:05","narrative_film":1}""")
  )
  private lazy val rows = units.zip(replies).zip(recall.unitIds).map { case ((u, r), id) =>
    StudyBundle.row(view, protocol, u, id.value, r, Vector("conf"), Some(intervals))
  }
  private lazy val producer = StudyRecord.producer("cli", "m", "SYSTEM", view, recall)
  private lazy val expected =
    StudyBundle.Expected("p1", "s1", units, producer, Some(intervals))
  private lazy val records =
    right(StudyBundle.records("norecord", protocolName, recall, view, producer, rows))
  private val provenance = StudyBundle.Provenance(
    "p1",
    "s1",
    "norecord",
    "m",
    "cli",
    protocolName,
    Checksum.ofText("SYSTEM").hex,
    "0" * 64,
    PreparedRecall.Segmentation(
      "supplied",
      Checksum.ofText(texts.mkString("|")),
      PreparedRecall.SuppliedRecipe
    ),
    Some("1" * 64)
  )
  private lazy val bundle =
    StudyBundle.encode(provenance, view, records, rows, Some(intervals))

  private def stated(i: Int, f: String): Json = rows(i).stated(f)
  private def kinds(f: String) = rows.map(_.judgement(f).kind)
  private def edited(f: Json => Json) = StudyBundle.decode(f(bundle), view, recall, expected)
  private def unit(i: Int)(f: Json => Json)(b: Json): Json =
    b.mapObject(o => o.add("units", o("units").get.mapArray(us => us.updated(i, f(us(i))))))
  private def field(name: String, key: String, value: Json)(u: Json): Json =
    u.mapObject(o => o.add(name, o(name).get.mapObject(_.add(key, value))))

  test("a film and a time are read strictly, and the raw values are kept on a failure"):
    def read(film: String, time: String) = StudyReplay.timeStated(json(film), json(time))
    assertEquals(read("1", "\"0:10\""), Stated.Time(BigInt(1), "0:10"))
    assertEquals(read("3", "null"), Stated.Collection(BigInt(3)))
    assertEquals(read("null", "null"), Stated.NoAssignment)
    assertEquals(read("\"1\"", "null"), Stated.Invalid("invalid-film"))
    def raw(film: String, time: String) = Vector("film_stated" -> json(film), "time" -> json(time))
    assertEquals(read("1", "\"10\""), Stated.Invalid("invalid-time", raw("1", "\"10\"")))
    assertEquals(read("1", "10"), Stated.Invalid("invalid-time", raw("1", "10")))
    assertEquals(
      read("null", "\"0:10\""),
      Stated.Invalid("time-without-film", raw("null", "\"0:10\""))
    )
    assertEquals(read("1.0", "\"0:10\""), Stated.Invalid("invalid-film", raw("1.0", "\"0:10\"")))
    assertEquals(read("true", "\"0:10\""), Stated.Invalid("invalid-film", raw("true", "\"0:10\"")))
    // A badly formed time is reported before a missing film.
    assertEquals(read("null", "\"x\""), Stated.Invalid("invalid-time", raw("null", "\"x\"")))
    assertEquals(
      StudyReplay.stated(protocol, json("""{"referent_film":1}"""), "referent"),
      Stated.Invalid("missing-field")
    )

  test("a time becomes the scene that contains it, with the scenes near it and the raw answer"):
    assertEquals(
      stated(0, "referent"),
      json(
        """{"status":"answered","kind":"scene","scene":2,"near":[2],"film_stated":1,"time":"0:10","seconds":10.0}"""
      )
    )
    assertEquals(
      stated(0, "narrative"),
      json(
        """{"status":"answered","kind":"scene","scene":3,"near":[2,3],"film_stated":1,"time":"0:20.5","seconds":20.5}"""
      )
    )
    assertEquals(
      stated(1, "referent"),
      json(
        """{"status":"answered","kind":"time_outside","film":3,"near":[],"film_stated":3,"time":"0:25","seconds":25.0}"""
      )
    )
    assertEquals(
      stated(3, "referent"),
      json("""{"status":"failed","reason":"unknown-film","film_stated":2,"time":"0:05"}""")
    )
    assertEquals(
      stated(3, "narrative"),
      json("""{"status":"failed","reason":"invalid-time","film_stated":1,"time":"5"}""")
    )
    assertEquals(stated(2, "narrative"), json("""{"status":"answered","kind":"film","film":3}"""))

  test("each kind of time answer is its own judgement"):
    assertEquals(
      kinds("referent"),
      Vector("scene", "time-outside", "none", "invalid", "omitted", "invalid")
    )
    assertEquals(
      kinds("narrative"),
      Vector("scene", "scene", "film", "invalid", "omitted", "invalid")
    )

  test("a time outside the clip is recorded as a choice of the film, and the row says so"):
    val row = bundle.hcursor.get[Vector[Json]]("units").toOption.get(1).hcursor
    assertEquals(
      row.downField("referent").downField("judgement").focus,
      Some(json("""{"kind":"time-outside","collection":3}"""))
    )
    val outcome = records("referent").outcomes(1)
    assertEquals(
      outcome.decision.flatMap(_.chosen),
      Some(storymodel4s.align.Destination.Target(view.collectionRefs(3)))
    )
    // A film alone and a time outside the film are the same choice in the record, and differ in
    // the row.
    assertEquals(
      records("narrative").outcomes(2).decision.flatMap(_.chosen),
      outcome.decision.flatMap(_.chosen)
    )
    assertEquals(kinds("narrative")(2), "film")

  test("a time with no clip clock is a failure, never a guess"):
    val row = StudyBundle.row(view, protocol, units(0), "x", replies(0), Vector.empty, None)
    assertEquals(row.judgement("referent"), StudyRecord.Judgement.Invalid("no-clip-clock"))
    assertEquals(row.stated("referent").hcursor.get[String]("status").toOption, Some("failed"))

  test("a time bundle carries its scene intervals and reads back to the same records"):
    assertEquals(bundle.hcursor.downField("source").downField("clock").focus, Some(intervals.json))
    assertEquals(
      bundle.hcursor.downField("provenance").get[String]("clockSha256").toOption,
      Some("1" * 64)
    )
    val loaded = right(parse(bundle.noSpaces).left.map(_.message).flatMap { b =>
      StudyBundle.decode(b, view, recall, expected)
    })
    StudyBundle.Fields.foreach(f => assertEquals(loaded.records(f).digest, records(f).digest))

  test("a scene, near list or seconds that its time does not give is refused"):
    val refusal =
      Some("a referent judgement is not what its stated answer gives against this source")
    def restate(key: String, value: String) =
      unit(0)(u =>
        field(
          "referent",
          "stated",
          u.hcursor
            .downField("referent")
            .downField("stated")
            .focus
            .get
            .mapObject(_.add(key, json(value)))
        )(u)
      )
    assertEquals(edited(restate("scene", "3")).left.toOption, refusal)
    assertEquals(edited(restate("near", "[2,3]")).left.toOption, refusal)
    assertEquals(edited(restate("seconds", "11.0")).left.toOption, refusal)
    // The time changed without its scene.
    assertEquals(edited(restate("time", "\"0:30\"")).left.toOption, refusal)

  test("a time and its scene changed together are refused by the record"):
    val moved = json(
      """{"status":"answered","kind":"scene","scene":3,"near":[3],"film_stated":1,"time":"0:30","seconds":30.0}"""
    )
    val judged = json("""{"kind":"scene","scene":3,"collection":1}""")
    val both =
      unit(0)(field("referent", "stated", moved).andThen(field("referent", "judgement", judged)))
    assertEquals(edited(both).left.toOption, Some("a unit row disagrees with the referent record"))

  test("a time moved inside its scene, restated consistently, is still refused"):
    // Scene 2 either way, so the record's choice is unchanged; the near list is not.
    val moved = json(
      """{"status":"answered","kind":"scene","scene":2,"near":[2,3],"film_stated":1,"time":"0:19","seconds":19.0}"""
    )
    assertEquals(
      edited(unit(0)(field("referent", "stated", moved))).left.toOption,
      Some("a unit row disagrees with the referent record")
    )

  test("a film named alone and a time outside its clip cannot be swapped"):
    // Both are a choice of film 3 in the record.
    val alone = json("""{"status":"answered","kind":"film","film":3}""")
    val film = json("""{"kind":"film","collection":3}""")
    val swap =
      unit(1)(field("referent", "stated", alone).andThen(field("referent", "judgement", film)))
    assertEquals(edited(swap).left.toOption, Some("a unit row disagrees with the referent record"))
    val late = json(
      """{"status":"answered","kind":"time_outside","film":3,"near":[],"film_stated":3,"time":"9:59","seconds":599.0}"""
    )
    val outside = json("""{"kind":"time-outside","collection":3}""")
    val back =
      unit(2)(field("narrative", "stated", late).andThen(field("narrative", "judgement", outside)))
    assertEquals(edited(back).left.toOption, Some("a unit row disagrees with the narrative record"))

  test("a scene without a time is refused in a condition that answers with a time"):
    val bare = json("""{"status":"answered","kind":"scene","scene":2,"film_stated":1}""")
    assertEquals(
      edited(unit(0)(field("referent", "stated", bare))).left.toOption,
      Some("a referent judgement is not what its stated answer gives against this source")
    )

  test("the clock in the bundle must be the source's, and a time condition must have one"):
    val widened = bundle.mapObject { o =>
      val source = o("source").get.mapObject { s =>
        s.add("clock", s("clock").get.mapObject(_.add("toleranceSeconds", Json.fromString("5/1"))))
      }
      o.add("source", source)
    }
    assertEquals(
      StudyBundle.decode(widened, view, recall, expected).left.toOption,
      Some("the source dictionary is not this source's")
    )
    assertEquals(
      StudyBundle.decode(bundle, view, recall, expected.copy(intervals = None)).left.toOption,
      Some("a time condition needs a clip clock, and no other has one")
    )

  test("the committed synthetic time bundle is what the writer produces"):
    val actual = bundle.spaces2 + "\n"
    Files.createDirectories(Path.of("target")): Unit
    Files.writeString(Path.of("target/synthetic-study-time.bundle.json"), actual): Unit
    val stream = getClass.getResourceAsStream("/agentmapping/synthetic-study-time.bundle.json")
    assert(stream != null, "copy target/synthetic-study-time.bundle.json into the test resources")
    val committed =
      try new String(stream.readAllBytes(), UTF_8)
      finally stream.close()
    assertEquals(actual, committed)
