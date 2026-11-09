package storymodel4s.pipeline.agentmapping

import io.circe.Json
import java.nio.charset.StandardCharsets
import munit.FunSuite
import storymodel4s.core.Checksum
import storymodel4s.corpus.intake.{SherlockAnnotations, TimebaseRepair}

/** The film packet on a synthetic six-row table (two runs, an instant row, a scan-break row). No
  * real annotation, transcript or recall enters the repository.
  */
class FilmOutlineSuite extends FunSuite:
  import FilmOutline.{Refusal, Speech}

  private val header =
    "Segment Number\tStart Time (s) \tEnd Time (s) \tStart TR\tEnd TR\tScene Segments\t" +
      "Scene Details - A Level \tSpace-In/Outdoor\tName - All\tName - Focus\tName - Speaking\t" +
      "Location\tCamera Angle\tMusic Presence \tWords on Screen "

  private def rows(firstLabel: String) = Vector(
    s"1\t0\t10\t1\t7\t$firstLabel\tA man walks in the rain.\tOutdoor\tMan,  Dog\tMan\t\tStreet\tLong\tNo\t",
    "2\t10\t20\t8\t14\t\tThe man finds a red door and knocks twice.\tOutdoor\tMan\t\tMan\tStreet\tMedium\tNo\t",
    "3\t20\t20\t14\t14\t\tSmash cut to black.\tIndoor\t\t\t\t\tClose\tNo\t",
    "4\t20\t30\t\t\t\tBlack screen while the projector is switched.\tIndoor\t\t\t\t\tLong\tNo\t",
    "5\t0\t5\t21\t24\t2. Cartoon\tPeople in costumes parade and sing.\tIndoor\tSingers\tSingers\tSingers\tCartoon World\tLong\tYes\t",
    "6\t5\t12\t25\t29\t\tPopcorn pops in a glass machine.\u00a0 \tIndoor\tSinger\t\tSinger\tCartoon World\tMedium\tYes\t"
  )

  private def atlas(firstLabel: String = "1. Opening"): SherlockAnnotations.Atlas =
    val bytes = (header +: rows(firstLabel)).mkString("\n").getBytes(StandardCharsets.UTF_8)
    val stream = getClass.getResourceAsStream("/agentmapping/sherlock-synthetic-repair.json")
    val json =
      try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    val record = TimebaseRepair
      .parse(json.replace("0" * 64, Checksum.ofBytes(bytes).hex))
      .fold(e => fail(e.message), identity)
    SherlockAnnotations.parse(bytes, record).fold(e => fail(e.message), identity)

  private val speechA = Vector(
    Speech(12, 14, "Knock knock."),
    Speech(19, 21, "Hello?"),
    Speech(30, 40, "Later words.")
  )
  private val speechB = Vector(Speech(1, 2, "Sing."))

  private def speech(a: SherlockAnnotations.Atlas) =
    Map(a.manifest.partA.partId -> speechA, a.manifest.partB.partId -> speechB)

  private val expected = Vector(
    "=== SCENE 1: Opening (part a, 0:00-0:30)",
    "[visual summary] Rain on a street.",
    "r1 0:00 A man walks in the rain.  (Street; people: Man,  Dog)",
    "r2 0:10 The man finds a red door and knocks twice.  (Street; people: Man)",
    "    dialogue: Knock knock. Hello?",
    "r3 0:20 Smash cut to black.",
    "r4 0:20 Black screen while the projector is switched.",
    "    dialogue: Later words.",
    "",
    "=== SCENE 2: Cartoon (part b, 0:00-0:12)",
    "r5 0:00 People in costumes parade and sing.  (Cartoon World; people: Singers)",
    "    dialogue: Sing.",
    "r6 0:05 Popcorn pops in a glass machine.  (Cartoon World; people: Singer)"
  ).mkString("\n")

  test("the packet renders rows, scene headers, captions and attached dialogue exactly"):
    val a = atlas()
    assertEquals(
      FilmOutline.render(a, speech(a), Map(1 -> "Rain on a street.")),
      Right(expected)
    )

  test("the cast cell is rendered as written, not rebuilt from the split list"):
    val a = atlas()
    assertEquals(a.rows.head.namesAll, Vector("Man", "Dog"))
    assertEquals(a.rows.head.namesAllCell, Some("Man,  Dog"))

  test("a midpoint inside several rows goes to the earliest; one in no row to the nearest"):
    val a = atlas()
    val out = FilmOutline.render(a, speech(a), Map.empty).toOption.get
    assert(out.contains("knocks twice.  (Street; people: Man)\n    dialogue: Knock knock. Hello?"))
    assert(out.contains("switched.\n    dialogue: Later words."))

  test("a scene label whose number disagrees with the scene ordinal is refused"):
    val a = atlas("7. Opening")
    assertEquals(
      FilmOutline.render(a, speech(a), Map.empty),
      Left(Refusal.SceneLabelMismatch(1))
    )

  test("a part without a transcript is refused, never rendered without dialogue"):
    val a = atlas()
    assertEquals(
      FilmOutline.render(a, Map(a.manifest.partA.partId -> speechA), Map.empty),
      Left(Refusal.MissingPart(a.manifest.partB.partId))
    )

  test("transcript segments marked suspect are dropped and text is stripped"):
    val doc = Json.obj(
      "segments" -> Json.arr(
        Json.obj(
          "start_seconds" -> Json.fromDouble(0.5).get,
          "end_seconds" -> Json.fromDouble(1.5).get,
          "text" -> Json.fromString(" kept "),
          "suspect" -> Json.False
        ),
        Json.obj(
          "start_seconds" -> Json.fromInt(2),
          "end_seconds" -> Json.fromInt(3),
          "text" -> Json.fromString("dropped"),
          "suspect" -> Json.True
        )
      )
    )
    assertEquals(
      FilmOutline.transcript(doc.noSpaces.getBytes(StandardCharsets.UTF_8)),
      Right(Vector(Speech(0.5, 1.5, "kept")))
    )

  test("a transcript segment without a suspect flag is refused"):
    val doc = Json.obj(
      "segments" -> Json.arr(
        Json.obj(
          "start_seconds" -> Json.fromInt(0),
          "end_seconds" -> Json.fromInt(1),
          "text" -> Json.fromString("x")
        )
      )
    )
    assertEquals(
      FilmOutline.transcript(doc.noSpaces.getBytes(StandardCharsets.UTF_8)),
      Left(Refusal.InvalidTranscript("segment 0"))
    )

  test("captions are keyed by scene number; a non-numeric key is refused"):
    def parse(s: String) = FilmOutline.captions(s.getBytes(StandardCharsets.UTF_8))
    assertEquals(parse("""{"captions":{"3":"x"}}"""), Right(Map(3 -> "x")))
    assertEquals(parse("""{"captions":{"x":"y"}}"""), Left(Refusal.InvalidCaptions("entry x")))

  test("the trailing no-break space after a description is stripped as the harness did"):
    val a = atlas("1. Opening")
    assert(a.rows(5).description.endsWith("machine.\u00a0"))
    val out = FilmOutline.render(a, speech(a), Map.empty).toOption.get
    assert(
      out.endsWith("r6 0:05 Popcorn pops in a glass machine.  (Cartoon World; people: Singer)")
    )

  test("harness whitespace includes the separators Java keeps"):
    assertEquals(FilmOutline.harnessStrip("\u00a0\u2003 x \u3000\u00a0"), "x")
    assertEquals(FilmOutline.harnessStrip("x\u200b"), "x\u200b")

  test("clock times round half to even"):
    assertEquals(FilmOutline.clock(6250L, 2500L), "0:02")
    assertEquals(FilmOutline.clock(8750L, 2500L), "0:04")
    assertEquals(FilmOutline.clock(152500L * 2, 2500L), "2:02")
