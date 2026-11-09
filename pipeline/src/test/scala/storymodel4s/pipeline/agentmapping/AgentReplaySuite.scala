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

class AgentReplaySuite extends FunSuite:
  import AgentPrompts.RecallUnit
  import AgentReplay.{ChunkReceipt, Failure}

  private def reply(text: String) =
    ModelReply("opus", text, ModelStopReason.EndTurn, ReplyEvidence.Authored)

  private val units = Vector(RecallUnit(3, "a"), RecallUnit(4, "b"), RecallUnit(9, "c"))
  private val ids = Map(3 -> "u3", 4 -> "u4", 9 -> "u9")

  private def serving(table: Map[String, String]): String => Either[ExchangeFailure, ModelReply] =
    p =>
      table
        .get(p)
        .map(reply)
        .toRight(ExchangeFailure.RecordingMissing(TextExchange.key("p", "opus", "s", p)))

  private val first = AgentPrompts.prompt("OUT", units, 3, 4)
  private val second = AgentPrompts.prompt("OUT", units, 9, 9)

  test("the retry suffix is the harness's literal text"):
    assertEquals(AgentReplay.suffix(0), "")
    assertEquals(AgentReplay.suffix(2), "\n\nReply with strictly valid JSON only (attempt 2).")

  test("a fenced reply with prose and row references parses"):
    assertEquals(
      AgentReplay.parseReply("```json\nHere: {\"rows\": [r114, r7], \"u\": 1}\n```"),
      parse("""{"rows": [114, 7], "u": 1}""").toOption
    )

  test("an object is preferred, an array is the fallback, and no brackets is no JSON"):
    assertEquals(AgentReplay.parseReply("x [1, 2] y"), parse("[1, 2]").toOption)
    assertEquals(AgentReplay.parseReply("no json here"), None)
    assertEquals(AgentReplay.parseReply("```no newline"), None)

  test("a row reference inside a word is not rewritten"):
    assertEquals(AgentReplay.parseReply("""{"w": "bar1"}"""), parse("""{"w": "bar1"}""").toOption)

  test("unit keys read as Python int() did"):
    def k(j: String) = AgentReplay.unitKey(parse(j).toOption.get)
    assertEquals(k("""{"u": 12}"""), Some(12))
    assertEquals(k("""{"u": 12.7}"""), Some(12))
    assertEquals(k("""{"u": " 7 "}"""), Some(7))
    assertEquals(k("""{"x": 1}"""), None)

  test("an unparsable first attempt is retried with the recorded suffix; units are joined by key"):
    val table = Map(
      first -> "sorry, no json",
      (first + AgentReplay.suffix(
        1
      )) -> """{"units": [{"u": 4, "narrative": 7}, {"u": 3, "narrative": null}]}""",
      second -> """{"units": [{"u": "9", "narrative": 12}]}"""
    )
    val (doc, receipts) =
      AgentReplay.participant("P", "OUT", units, ids, 2, serving(table)).toOption.get
    assertEquals(receipts, Vector(ChunkReceipt(0, 2, true), ChunkReceipt(1, 1, true)))
    val got = doc.hcursor.get[Vector[Json]]("units").toOption.get
    assertEquals(
      got.map(u =>
        (u.hcursor.get[String]("id").toOption.get, u.hcursor.downField("narrative").focus)
      ),
      Vector("u3" -> Some(Json.Null), "u4" -> Some(Json.fromInt(7)), "u9" -> Some(Json.fromInt(12)))
    )
    assertEquals(got.map(_.hcursor.get[Int]("index").toOption.get), Vector(3, 4, 9))

  test("a unit the reply omits is written as null and listed missing, as the harness did"):
    val table = Map(
      first -> """{"units": [{"u": 3, "narrative": 1}]}""",
      second -> """{"units": [{"u": 9, "narrative": 2}]}"""
    )
    val (doc, _) = AgentReplay.participant("P", "OUT", units, ids, 2, serving(table)).toOption.get
    assertEquals(doc.hcursor.get[Vector[Int]]("missing").toOption, Some(Vector(4)))

  test("three unparsable attempts leave the chunk empty, not refused"):
    val table = Map(
      first -> "x",
      (first + AgentReplay.suffix(1)) -> "y",
      (first + AgentReplay.suffix(2)) -> "z",
      second -> """{"units": [{"u": 9, "narrative": 2}]}"""
    )
    val (doc, receipts) =
      AgentReplay.participant("P", "OUT", units, ids, 2, serving(table)).toOption.get
    assertEquals(receipts.head, ChunkReceipt(0, 3, false))
    assertEquals(doc.hcursor.get[Vector[Int]]("missing").toOption, Some(Vector(3, 4)))

  test("an accepted empty reply is accepted, and a later chunk overwrites an earlier key"):
    val table = Map(
      first -> """{"units": [{"u": 9, "narrative": 1}, {"u": 3, "narrative": 5}]}""",
      second -> """{"units": [{"u": 9, "narrative": 2}]}"""
    )
    val (doc, receipts) =
      AgentReplay.participant("P", "OUT", units, ids, 2, serving(table)).toOption.get
    val nine = doc.hcursor.get[Vector[Json]]("units").toOption.get.last
    assertEquals(nine.hcursor.get[Int]("narrative").toOption, Some(2))
    val empty = Map(first -> """{"units": []}""", second -> """{"units": []}""")
    assertEquals(
      AgentReplay.participant("P", "OUT", units, ids, 2, serving(empty)).map(_._2),
      Right(Vector(ChunkReceipt(0, 1, true), ChunkReceipt(1, 1, true)))
    )

  test("a retry the replay needs but cannot find is an unrecorded call"):
    val table = Map(first -> "x", second -> """{"units": []}""")
    assertEquals(
      AgentReplay.participant("P", "OUT", units, ids, 2, serving(table)),
      Left(Failure.Unrecorded("P", 0, 1))
    )

  test("a unit without a manifest id is refused"):
    val table = Map(
      first -> """{"units": []}""",
      second -> """{"units": []}"""
    )
    assertEquals(
      AgentReplay.participant("P", "OUT", units, ids - 9, 2, serving(table)),
      Left(Failure.MissingId("P", 9))
    )
