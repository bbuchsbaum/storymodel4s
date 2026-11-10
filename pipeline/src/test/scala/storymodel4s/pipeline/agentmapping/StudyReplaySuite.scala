package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import munit.FunSuite
import storymodel4s.provider.agent.{
  ExchangeFailure,
  ModelReply,
  ModelStopReason,
  ReplyEvidence,
  TextExchange
}

/** The generic replay on invented prompts and replies: the paths no recorded run took (retries,
  * rejected chunks, omitted units) and each rule on which the two reply protocols differ.
  */
class StudyReplaySuite extends FunSuite:
  import StudyReplay.{Chunk, Failure, Receipt, RecallUnit, ReplyProtocol, Stated, UnitReply}
  import ReplyProtocol.{CoercingOpen, StrictChunked}

  private val units = (1 to 5).toVector.map(i => RecallUnit(i, Json.fromInt(i * 10), s"text $i"))
  private def line(u: RecallUnit) = s"[${u.u}] ${u.text}"
  private val shown = units.map(line).mkString("\n")
  private val chunks = Vector(Chunk(1, 3, s"A\n$shown\nmap 1-3"), Chunk(4, 5, s"A\n$shown\nmap 4-5"))
  private def suffix(n: Int) = if n == 0 then "" else s" (attempt $n)"

  private def serving(table: Map[String, String]): String => Either[ExchangeFailure, ModelReply] =
    p =>
      table
        .get(p)
        .map(ModelReply("m", _, ModelStopReason.EndTurn, ReplyEvidence.Authored))
        .toRight(ExchangeFailure.RecordingMissing(TextExchange.key("p", "m", "s", p)))

  private def run(
      protocol: ReplyProtocol,
      table: Map[String, String],
      cs: Vector[Chunk] = chunks,
      us: Vector[RecallUnit] = units
  ) = StudyReplay.session(us, cs, protocol, 3, suffix, line, serving(table))

  private def item(j: String) = parse(j).toOption.get
  private def reply(items: String*) = s"""{"units": [${items.mkString(",")}]}"""
  private val ok = """"narrative": 3, "narrative_film": 1, "referent": null, "referent_film": 2"""
  private val second = chunks(1).prompt -> reply(s"""{"u": 4, $ok}""", s"""{"u": 5, $ok}""")

  test("a retry is asked with the suffix, and an unanswered unit of an accepted chunk is omitted"):
    val table = Map(
      chunks(0).prompt -> "not json",
      (chunks(0).prompt + suffix(1)) -> reply(s"""{"u": 1, $ok}""", s"""{"u": 3, $ok}"""),
      second
    )
    val (replies, receipts) = run(StrictChunked, table).toOption.get
    assertEquals(receipts, Vector(Receipt(0, 2, true, 0, 0), Receipt(1, 1, true, 0, 0)))
    assertEquals(replies(1)._2, UnitReply.Omitted)
    assert(replies(0)._2.isInstanceOf[UnitReply.Answered])
    assertEquals(replies.map(_._1), units)

  test("three unusable replies reject the chunk, and its units are not merely omitted"):
    val bad = Vector("x", "```json {\"units\": []} ```", """{"units": 7}""")
    val table = bad.zipWithIndex.map((t, n) => (chunks(0).prompt + suffix(n)) -> t).toMap + second
    val (replies, receipts) = run(StrictChunked, table).toOption.get
    assertEquals(receipts.head, Receipt(0, 3, false, 0, 0))
    assertEquals(replies.take(3).map(_._2), Vector.fill(3)(UnitReply.UnparsableChunk))
    assert(replies(3)._2.isInstanceOf[UnitReply.Answered])

  test("a recording the replay needs and cannot find refuses the session"):
    assertEquals(run(StrictChunked, Map(second)), Left(Failure.Unrecorded(0, 0)))
    val oneBad = Map(chunks(0).prompt -> "x", second)
    assertEquals(run(StrictChunked, oneBad), Left(Failure.Unrecorded(0, 1)))

  test("the strict protocol drops and counts items that are not for the chunk asked about"):
    val first = chunks(0).prompt -> reply(
      s"""{"u": 1, $ok}""",
      s"""{"u": 4, $ok}""",
      s"""{"u": "2", $ok}""",
      s"""{"u": 3.0, $ok}""",
      s"""{"u": true, $ok}""",
      s"""{"x": 1}""",
      "7"
    )
    val (replies, receipts) = run(StrictChunked, Map(first, chunks(1).prompt -> reply())).toOption.get
    assertEquals(receipts, Vector(Receipt(0, 1, true, 6, 0), Receipt(1, 1, true, 0, 0)))
    assertEquals(replies.map(_._2.isInstanceOf[UnitReply.Answered]), Vector(true, false, false, false, false))
    // Unit 4 was answered only by the first chunk's reply, which it does not belong to.
    assertEquals(replies(3)._2, UnitReply.Omitted)

  test("the open protocol coerces keys, keeps answers from another chunk and refuses a bad key"):
    val first = chunks(0).prompt -> reply(
      s"""{"u": "2", $ok}""",
      s"""{"u": 3.7, $ok}""",
      s"""{"u": 4, "narrative": 9}"""
    )
    val (replies, receipts) = run(CoercingOpen, Map(first, chunks(1).prompt -> reply())).toOption.get
    assertEquals(replies.map(_._2.isInstanceOf[UnitReply.Answered]), Vector(false, true, true, true, false))
    assertEquals(receipts.map(_.itemsOutsideChunk), Vector(0, 0))
    val bad = chunks(0).prompt -> reply("""{"x": 1}""")
    assertEquals(run(CoercingOpen, Map(bad, second)), Left(Failure.BadUnitKey(0)))

  test("a unit answered twice keeps the last answer, and the repeat is counted"):
    val first = chunks(0).prompt -> reply(
      s"""{"u": 1, "narrative": 3, "narrative_film": 1, "referent": 3, "referent_film": 1}""",
      s"""{"u": 1, "narrative": 4, "narrative_film": 1, "referent": 4, "referent_film": 1}"""
    )
    val (replies, receipts) = run(StrictChunked, Map(first, second)).toOption.get
    assertEquals(receipts.head.repeatedUnits, 1)
    replies.head._2 match
      case UnitReply.Answered(i) =>
        assertEquals(StudyReplay.stated(StrictChunked, i, "referent"), Stated.Scene(4, Json.fromInt(1)))
      case other => fail(other.toString)

  test("a prompt that does not show a unit of the session is refused"):
    val hidden = chunks.updated(1, Chunk(4, 5, chunks(1).prompt.replace("[2] text 2", "[2] other")))
    assertEquals(run(StrictChunked, Map.empty, hidden), Left(Failure.PromptOmitsUnit(1, 2)))

  test("a unit that no chunk, or more than one chunk, asks about is refused"):
    assertEquals(run(StrictChunked, Map.empty, chunks.take(1)), Left(Failure.UnitOutsideChunks(4)))
    val overlapping = Vector(chunks(0), Chunk(3, 5, chunks(1).prompt))
    assertEquals(run(StrictChunked, Map.empty, overlapping), Left(Failure.UnitOutsideChunks(3)))

  test("an integer must be written as an integer"):
    def strict(j: String) = StudyReplay.strictInteger(item(j))
    assertEquals(strict("5"), Some(BigInt(5)))
    assertEquals(strict("-2"), Some(BigInt(-2)))
    Vector("5.0", "5e0", "\"5\"", "true", "null", "[5]").foreach(j => assertEquals(strict(j), None, j))

  test("strict judgements: scene, film without a scene, nothing, and three kinds of failure"):
    def read(j: String) = StudyReplay.stated(StrictChunked, item(j), "referent")
    assertEquals(read("""{"referent": 7, "referent_film": 2}"""), Stated.Scene(7, Json.fromInt(2)))
    assertEquals(read("""{"referent": 7, "referent_film": null}"""), Stated.Scene(7, Json.Null))
    assertEquals(read("""{"referent": null, "referent_film": 2}"""), Stated.Collection(2))
    assertEquals(read("""{"referent": null, "referent_film": null}"""), Stated.NoAssignment)
    assertEquals(read("""{"referent": null}"""), Stated.Invalid("missing-field"))
    assertEquals(read("""{"referent_film": 2}"""), Stated.Invalid("missing-field"))
    assertEquals(read("""{"referent": "7", "referent_film": 2}"""), Stated.Invalid("invalid-scene"))
    assertEquals(read("""{"referent": 7.0, "referent_film": 2}"""), Stated.Invalid("invalid-scene"))
    assertEquals(read("""{"referent": null, "referent_film": "two"}"""), Stated.Invalid("invalid-film"))
    assertEquals(read("""{"referent": null, "referent_film": true}"""), Stated.Invalid("invalid-film"))

  test("open judgements read the scene field alone"):
    def read(j: String) = StudyReplay.stated(CoercingOpen, item(j), "narrative")
    assertEquals(read("""{"narrative": 7}"""), Stated.Scene(7, Json.Null))
    assertEquals(read("""{"narrative": null}"""), Stated.NoAssignment)
    assertEquals(read("""{"x": 1}"""), Stated.Invalid("missing-field"))
    assertEquals(read("""{"narrative": "seven"}"""), Stated.Invalid("invalid-scene"))

  test("a reply-file row keeps the study's row key, both judgements and the extras asked for"):
    val answered = UnitReply.Answered(item(s"""{"u": 2, $ok, "conf": 0.5, "rows": [3]}"""))
    val row = StudyReplay.unitRow(StrictChunked, units(1), answered, Vector("alts", "rows", "conf"))
    assertEquals(
      row,
      item(
        """{"u": 2, "row": 20,
          "referent": {"status": "answered", "kind": "film", "film": 2},
          "narrative": {"status": "answered", "kind": "scene", "scene": 3, "film_stated": 1},
          "alts": null, "rows": [3], "conf": 0.5}"""
      )
    )
    val none = item("""{"status": "answered", "kind": "none"}""")
    assertEquals(StudyReplay.prediction(Stated.NoAssignment), none)
    val omitted = StudyReplay.unitRow(StrictChunked, units(1), UnitReply.Omitted, Vector("conf"))
    val failure = item("""{"status": "failed", "reason": "omitted"}""")
    assertEquals(omitted, Json.obj("u" -> Json.fromInt(2), "row" -> Json.fromInt(20), "referent" -> failure, "narrative" -> failure))
    val unparsable = StudyReplay.unitRow(StrictChunked, units(1), UnitReply.UnparsableChunk, Vector.empty)
    assertEquals(unparsable.hcursor.downField("referent").get[String]("reason").toOption, Some("unparsable-chunk"))

  test("protocols are named, and an unknown name is not a protocol"):
    assertEquals(ReplyProtocol.named("strict-chunked/v1"), Some(StrictChunked))
    assertEquals(ReplyProtocol.named("coercing-open/v1"), Some(CoercingOpen))
    assertEquals(ReplyProtocol.named("strict"), None)
