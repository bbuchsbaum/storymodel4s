package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.align.MappingResult
import storymodel4s.core.Checksum
import storymodel4s.pipeline.TimedAnnotationInput

/** The v2 bundle on an invented two-film source: every kind of judgement in one session, the round
  * trip, and each way a tampered bundle is refused.
  */
class StudyBundleSuite extends FunSuite:
  import StudyReplay.{RecallUnit, ReplyProtocol, UnitReply}

  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def json(text: String): Json = right(parse(text))

  private val parts =
    """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
  // Film 1 has scenes 1 (content) and 2 (a title card, not a target); collection 2 is a cartoon
  // and not a target; film 3 has scene 4.
  private def source(first: String) = right(
    TimedAnnotationInput.prepare(
      (TimedAnnotationInput.HeaderV2.mkString(",") +: Vector(
        s"1,$first,a,10,20,1,scene 1,,Anna,1,Harbour film,",
        "2,A title card.,a,20,30,2,scene 2,,,1,Harbour film,no",
        "3,A mouse dances.,a,40,50,3,scene 3,,,2,Cartoon,no",
        "4,Bob left.,a,50,,4,scene 4,,Bob,3,Station film,"
      )).mkString("", "\n", "\n").getBytes(UTF_8),
      parts.getBytes(UTF_8)
    )
  )
  private lazy val view = source("Anna arrived.")
  private val texts = Vector("she came in", "then the title", "a mouse", "he left", "um", "what")
  private val units = texts.zipWithIndex.map((t, i) => RecallUnit(i + 1, Json.fromInt(10 + i), t))
  private def recallOf(ts: Vector[String]) =
    right(PreparedRecall.supplied("p1_s1", ts, Checksum.ofText(ts.mkString("|"))))
  private lazy val recall = recallOf(texts)
  private val protocol = ReplyProtocol.StrictChunked
  private val protocolName = "strict-chunked/v1"
  private def item(body: String) = UnitReply.Answered(json(body))
  private val replies = Vector(
    item("""{"referent":1,"referent_film":1,"narrative":1,"narrative_film":1,"conf":0.8}"""),
    // A title card: a real scene that is not a target. It keeps its film.
    item("""{"referent":2,"referent_film":1,"narrative":1,"narrative_film":1,"conf":0.5}"""),
    // A film with no scene; and the cartoon, which is not a film a reply may choose.
    item("""{"referent":null,"referent_film":3,"narrative":null,"narrative_film":2,"conf":0.4}"""),
    item("""{"referent":null,"referent_film":null,"narrative":null,"narrative_film":null}"""),
    UnitReply.Omitted,
    item("""{"referent":99,"referent_film":1,"narrative":1}""")
  )
  private lazy val rows = units.zip(replies).zip(recall.unitIds).map { case ((u, r), id) =>
    StudyBundle.row(view, protocol, u, id.value, r, Vector("conf"))
  }
  private val provenance = StudyBundle.Provenance(
    "p1",
    "s1",
    "annotation",
    "m",
    "cli",
    protocolName,
    Checksum.ofText("SYSTEM").hex,
    "0" * 64,
    PreparedRecall.Segmentation(
      "supplied",
      Checksum.ofText(texts.mkString("|")),
      PreparedRecall.SuppliedRecipe
    )
  )
  private lazy val producer = StudyRecord.producer("cli", "m", "SYSTEM", view, recall)
  private lazy val expected = StudyBundle.Expected("p1", "s1", units, producer)
  private def recordsFor(condition: String): Map[String, MappingResult] =
    StudyBundle.Fields.map { f =>
      f -> right(
        StudyRecord
          .record(
            f,
            StudyRecord.policy(condition, protocolName, f),
            recall,
            view,
            producer,
            rows.map(_.judgement(f))
          )
          .left
          .map(_.message)
      )
    }.toMap
  private lazy val records = recordsFor("annotation")
  private lazy val bundle = StudyBundle.encode(provenance, view, records, rows)

  private def kinds(field: String) =
    bundle.hcursor
      .get[Vector[Json]]("units")
      .toOption
      .get
      .map(_.hcursor.downField(field).downField("judgement").get[String]("kind").toOption.get)

  private def edited(f: Json => Json): Either[String, StudyBundle.Loaded] =
    StudyBundle.decode(f(bundle), view, recall, expected)
  private def unit(i: Int)(f: Json => Json)(b: Json): Json =
    b.mapObject(o => o.add("units", o("units").get.mapArray(us => us.updated(i, f(us(i))))))
  private def field(name: String, key: String, value: Json)(u: Json): Json =
    u.mapObject(o => o.add(name, o(name).get.mapObject(_.add(key, value))))

  test("every kind of judgement is its own row kind"):
    assertEquals(
      kinds("referent"),
      Vector("scene", "invalid-scene", "film", "none", "omitted", "unknown-scene")
    )
    assertEquals(
      kinds("narrative"),
      Vector("scene", "scene", "unknown-film", "none", "omitted", "invalid")
    )

  test("a scene that is not a target still names its film, and the row keeps the study's key"):
    val row = bundle.hcursor.get[Vector[Json]]("units").toOption.get(1).hcursor
    assertEquals(
      row.downField("referent").downField("judgement").focus,
      Some(json("""{"kind":"invalid-scene","scene":2,"collection":1}"""))
    )
    assertEquals(row.get[Int]("row").toOption, Some(11))
    assertEquals(row.get[Int]("u").toOption, Some(2))
    assertEquals(row.downField("extras").focus, Some(json("""{"conf":0.5}""")))
    val omitted = bundle.hcursor.get[Vector[Json]]("units").toOption.get(4).hcursor
    assertEquals(omitted.get[String]("processing").toOption, Some("omitted"))
    assertEquals(omitted.downField("extras").focus, Some(Json.obj()))

  test("the source dictionary marks targets and puts each scene in its film"):
    val scenes = bundle.hcursor.downField("source").get[Vector[Json]]("scenes").toOption.get
    assertEquals(
      scenes.map { s =>
        val c = s.hcursor
        (c.get[Int]("scene").toOption.get, c.get[Int]("collection").toOption.get) ->
          c.get[Boolean]("isTarget").toOption.get
      },
      Vector((1, 1) -> true, (2, 1) -> false, (3, 2) -> false, (4, 3) -> true)
    )
    val films = bundle.hcursor.downField("source").get[Vector[Json]]("collections").toOption.get
    assertEquals(
      films.map(_.hcursor.get[Boolean]("isTarget").toOption.get),
      Vector(true, false, true)
    )

  test("a bundle reads back to the same two records, through text"):
    val loaded = right(parse(bundle.noSpaces).left.map(_.message).flatMap { b =>
      StudyBundle.decode(b, view, recall, expected)
    })
    StudyBundle.Fields.foreach(f => assertEquals(loaded.records(f).digest, records(f).digest))
    assertEquals(loaded.units.size, 6)

  test("a judgement changed without its stated answer is refused"):
    val none = json("""{"kind":"none"}""")
    assertEquals(
      edited(unit(0)(field("referent", "judgement", none))).left.toOption,
      Some("a referent judgement is not what its stated answer gives against this source")
    )
    // A failure cannot be relabelled as an answer.
    assertEquals(
      edited(unit(4)(field("narrative", "judgement", none))).left.toOption,
      Some("a narrative judgement is not what its stated answer gives against this source")
    )

  test("a stated answer and judgement changed together are refused by the record"):
    val four = json("""{"status":"answered","kind":"scene","scene":4,"film_stated":3}""")
    val judged = json("""{"kind":"scene","scene":4,"collection":3}""")
    val both =
      unit(0)(field("referent", "stated", four).andThen(field("referent", "judgement", judged)))
    assertEquals(edited(both).left.toOption, Some("a unit row disagrees with the referent record"))
    // An invalid scene promoted to no assignment: the record holds a failure there.
    val quiet = unit(1)(
      field("referent", "stated", json("""{"status":"answered","kind":"none"}"""))
        .andThen(field("referent", "judgement", json("""{"kind":"none"}""")))
    )
    assertEquals(edited(quiet).left.toOption, Some("a unit row disagrees with the referent record"))

  test("records in the wrong slots, a missing unit, and another condition's records are refused"):
    val swapped = bundle.mapObject { o =>
      val i = o("interpretations").get.asObject.get
      o.add(
        "interpretations",
        Json.obj("narrative" -> i("referent").get, "referent" -> i("narrative").get)
      )
    }
    assertEquals(
      StudyBundle.decode(swapped, view, recall, expected).left.toOption,
      Some("the narrative slot does not hold the narrative record")
    )
    val short = bundle.mapObject(o => o.add("units", o("units").get.mapArray(_.dropRight(1))))
    assertEquals(
      StudyBundle.decode(short, view, recall, expected).left.toOption,
      Some("unit rows are not the recall's units")
    )
    val other = StudyBundle.encode(provenance, view, recordsFor("automatic"), rows)
    assertEquals(
      StudyBundle.decode(other, view, recall, expected).left.toOption,
      Some("the narrative slot does not hold the narrative record")
    )

  test("a bundle is refused against another recall or another source"):
    val otherRecall = recallOf(texts.updated(0, "she left"))
    assertEquals(
      StudyBundle.decode(bundle, view, otherRecall, expected).left.toOption,
      Some("the recall is not the study's units")
    )
    assert(StudyBundle.decode(bundle, source("Anna left."), recall, expected).isLeft)
    assertEquals(
      StudyBundle
        .decode(Json.obj("schema" -> Json.fromString("x")), view, recall, expected)
        .left
        .toOption,
      Some("not a v2 mapping bundle")
    )

  private def top(key: String, value: Json)(u: Json): Json = u.mapObject(_.add(key, value))
  private def provenanceSays(key: String, value: String)(b: Json): Json =
    b.mapObject(o => o.add("provenance", o("provenance").get.mapObject(_.add(key, Json.fromString(value)))))

  test("an extra cannot use a row's own key"):
    val smuggled = json("""{"referent":{"status":"answered","kind":"scene","scene":1},"row":99}""")
    assertEquals(
      edited(unit(3)(top("extras", smuggled))).left.toOption,
      Some("an extra uses a row's own key")
    )

  test("a row's number and study key are the study's, in the study's order"):
    val swapped = unit(0)(top("row", Json.fromInt(11))).andThen(unit(1)(top("row", Json.fromInt(10))))
    assertEquals(edited(swapped).left.toOption, Some("unit rows are not the recall's units"))
    assertEquals(
      edited(unit(0)(top("u", Json.fromString("1")))).left.toOption,
      Some("unit rows are not the recall's units")
    )

  test("a failure keeps its own kind and reason, and processing follows the judgements"):
    val invalid = json("""{"kind":"invalid","reason":"missing-field"}""")
    assertEquals(
      edited(unit(4)(field("referent", "judgement", invalid))).left.toOption,
      Some("a referent judgement is not what its stated answer gives against this source")
    )
    assertEquals(
      edited(unit(4)(top("processing", Json.fromString("answered")))).left.toOption,
      Some("a unit's processing does not match its judgements")
    )
    // An unknown scene restated as a known one no longer gives the judgement the row holds.
    val one = json("""{"status":"answered","kind":"scene","scene":1,"film_stated":1}""")
    assertEquals(
      edited(unit(5)(field("referent", "stated", one))).left.toOption,
      Some("a referent judgement is not what its stated answer gives against this source")
    )

  test("a bundle relabelled as another participant, model or instruction is refused"):
    assertEquals(
      edited(provenanceSays("participant", "p2")).left.toOption,
      Some("provenance names another participant")
    )
    assertEquals(
      edited(provenanceSays("model", "other")).left.toOption,
      Some("provenance names another model")
    )
    // The record itself is bound to the producer: another model's records do not pass as this one's.
    val other = expected.copy(producer = StudyRecord.producer("cli", "other", "SYSTEM", view, recall))
    assertEquals(
      StudyBundle.decode(provenanceSays("model", "other")(bundle), view, recall, other).left.toOption,
      Some("a unit row disagrees with the narrative record")
    )
    val reworded = expected.copy(producer = StudyRecord.producer("cli", "m", "OTHER", view, recall))
    assert(StudyBundle.decode(bundle, view, recall, reworded).isLeft)

  test("the committed synthetic bundle is what the writer produces"):
    val actual = bundle.spaces2 + "\n"
    Files.createDirectories(Path.of("target")): Unit
    Files.writeString(Path.of("target/synthetic-study.bundle.json"), actual): Unit
    val stream = getClass.getResourceAsStream("/agentmapping/synthetic-study.bundle.json")
    assert(stream != null, "copy target/synthetic-study.bundle.json into the test resources")
    val committed =
      try new String(stream.readAllBytes(), UTF_8)
      finally stream.close()
    assertEquals(actual, committed)
