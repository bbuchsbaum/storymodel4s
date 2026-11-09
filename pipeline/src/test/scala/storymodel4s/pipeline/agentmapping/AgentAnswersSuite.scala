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

/** Synthetic distinctions the all-successful development replay cannot exercise: narrative and
  * referent differ, a cited row lies outside the narrative scene, and omitted, unparsable, invalid,
  * absent and explicit-null answers all occur.
  */
class AgentAnswersSuite extends FunSuite:
  import AgentAnswers.{Alternative, Field, Outcome}
  import AgentPrompts.RecallUnit

  private def reply(text: String) =
    ModelReply("opus", text, ModelStopReason.EndTurn, ReplyEvidence.Authored)

  private def serving(table: Map[String, String]): String => Either[ExchangeFailure, ModelReply] =
    p =>
      table
        .get(p)
        .map(reply)
        .toRight(ExchangeFailure.RecordingMissing(TextExchange.key("p", "opus", "s", p)))

  private val units = (1 to 6).toVector.map(i => RecallUnit(i, s"t$i"))
  private val c0 = AgentPrompts.prompt("OUT", units, 1, 3)
  private val c1 = AgentPrompts.prompt("OUT", units, 4, 6)

  private def item(j: String) = parse(j).toOption.get

  test("each field keeps what the reply established"):
    val a = AgentAnswers.answer(
      item("""{"u": 1, "narrative": 7, "referent": 3, "alts": [[7, 0.6], [8, 0.25]],
             "rows": [120, 9], "conf": 0.8}"""),
      50,
      0,
      0,
      "P"
    )
    assertEquals(a.narrative, Field.Value(7))
    assertEquals(a.referent, Field.Value(3))
    assertEquals(
      a.alternatives,
      Field.Value(
        Vector(
          Alternative(7, Json.fromDoubleOrNull(0.6)),
          Alternative(8, Json.fromDoubleOrNull(0.25))
        )
      )
    )
    assertEquals(a.rows, Field.Value(Vector(120, 9)))
    assertEquals(a.confidence, Field.Value(0.8))

  test("absent, null and invalid are three different answers"):
    val a = AgentAnswers.answer(
      item("""{"u": 1, "narrative": null, "referent": 51, "alts": [[7, "high"]], "rows": [-1]}"""),
      50,
      0,
      0,
      "P"
    )
    assertEquals(a.narrative, Field.Null)
    assertEquals(a.referent, Field.Invalid(Json.fromInt(51)))
    assertEquals(a.alternatives, Field.Invalid(item("""[[7, "high"]]""")))
    assertEquals(a.rows, Field.Invalid(item("[-1]")))
    assertEquals(a.confidence, Field.Absent)

  test("a non-integral scene and an out-of-range confidence are invalid, not rounded"):
    val a = AgentAnswers.answer(item("""{"narrative": 7.5, "conf": 1.2}"""), 50, 0, 0, "P")
    assertEquals(a.narrative, Field.Invalid(Json.fromDoubleOrNull(7.5)))
    assertEquals(a.confidence, Field.Invalid(Json.fromDoubleOrNull(1.2)))

  test("an explicit null referent is kept as null, not as a missing answer"):
    val a = AgentAnswers.answer(item("""{"narrative": 4, "referent": null}"""), 50, 0, 0, "P")
    assertEquals(a.referent, Field.Null)

  test("every unit gets an outcome: answered, omitted, or its chunk unparsable"):
    val table = Map(
      c0 -> "no json",
      (c0 + AgentReplay.suffix(1)) -> """{"units": [{"u": 1, "narrative": 2, "referent": 9,
        "rows": [400]}, {"u": 3}]}""",
      c1 -> "x",
      (c1 + AgentReplay.suffix(1)) -> "y",
      (c1 + AgentReplay.suffix(2)) -> "z"
    )
    val (outcomes, replays) =
      AgentAnswers.participant("P", "OUT", units, 3, 50, serving(table)).toOption.get
    assertEquals(outcomes.map(_._1.index), Vector(1, 2, 3, 4, 5, 6))
    outcomes(0)._2 match
      case Outcome.Answered(a) =>
        assertEquals((a.narrative, a.referent), (Field.Value(2), Field.Value(9)))
        assertEquals(a.rows, Field.Value(Vector(400)))
        assertEquals((a.chunk, a.attempt), (0, 1))
        assertEquals(a.prompt, c0 + AgentReplay.suffix(1))
      case other => fail(s"unit 1: $other")
    assertEquals(outcomes(1)._2, Outcome.Omitted(0, 2))
    outcomes(2)._2 match
      case Outcome.Answered(a) => assertEquals(a.narrative, Field.Absent)
      case other               => fail(s"unit 3: $other")
    assertEquals(outcomes.drop(3).map(_._2), Vector.fill(3)(Outcome.Unparsable(1, 3)))
    assertEquals(replays.map(_.receipt.accepted), Vector(true, false))

  test("the typed path and the arm projection agree on narrative for answered units"):
    val table = Map(
      c0 -> """{"units": [{"u": 1, "narrative": 5}, {"u": 2, "narrative": null},
        {"u": 3, "narrative": 6}]}""",
      c1 -> """{"units": [{"u": 4, "narrative": 6}, {"u": 5, "narrative": 7},
        {"u": 6, "narrative": 50}]}"""
    )
    val ids = units.map(u => u.index -> s"id${u.index}").toMap
    val (doc, _) = AgentReplay.participant("P", "OUT", units, ids, 3, serving(table)).toOption.get
    val (typed, _) = AgentAnswers.participant("P", "OUT", units, 3, 50, serving(table)).toOption.get
    val projected = doc.hcursor
      .get[Vector[Json]]("units")
      .toOption
      .get
      .map(_.hcursor.downField("narrative").focus)
    val fromTyped = typed.map {
      case (_, Outcome.Answered(a)) =>
        a.narrative match
          case Field.Value(s) => Some(Json.fromInt(s))
          case Field.Null     => Some(Json.Null)
          case _              => None
      case _ => None
    }
    assertEquals(projected, fromTyped)
