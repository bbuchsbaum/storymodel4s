package storymodel4s.pipeline.agentmapping

import io.circe.Json
import java.nio.file.Files
import munit.FunSuite
import storymodel4s.core.Checksum
import storymodel4s.provider.agent.{
  ExchangeFailure,
  ModelReply,
  ModelStopReason,
  Recordings,
  ReplyEvidence,
  TextExchange
}

/** Reference answers in the case-study schemas: what each field established is kept, weights are
  * kept as written, intervals are checked against the source rows, replay needs no model, and a
  * reference bundle round trips and refuses edits.
  */
class ReferenceAnswersSuite extends FunSuite:
  import AgentAnswers.Field
  import ConditionFixture.{json as parsed, *}
  import ReferenceAnswers.*

  private lazy val rows = SourceRows.of(view)
  private def value[A](f: Field[A]): A = f match
    case Field.Value(v) => v
    case other          => fail(s"not a value: $other")
  private def iv(a: Int, b: Int) = RowInterval.of(a, b, rows).get

  private val v2Item = parsed(
    """{"id":"xa","talk":"episodic","hedge":true,"refs":[
      |{"words":"she came in","form":"specific","alts":[{"w":0.70,"iv":[[1,1]]},{"w":0.6,"iv":[[2,4]]}]},
      |{"words":"then","form":"vague","alts":[{"w":"high","iv":[[1,9]]},{"iv":[[4,1]]},{"w":null,"iv":null}]},
      |{"form":null,"alts":"none"}
      |]}""".stripMargin
  )

  test("v2: each field keeps what the reply established"):
    val a = answer(v2Item, Schema.LocalV2, rows)
    assertEquals(a.talk, TalkAnswer.Whole(Field.Value(Talk.Episodic)))
    assertEquals(a.hedge, HedgeAnswer.Flag(Field.Value(true)))
    val refs = a.refs match
      case Field.Value(rs) => rs
      case other           => fail(s"refs: $other")
    assertEquals(refs.size, 3)
    // An unknown form token is invalid and kept verbatim; a missing field is absent.
    assertEquals(refs(1).shape, Shape.Formed(Field.Invalid(Json.fromString("vague"))))
    assertEquals(refs(2).words, Field.Absent)
    assertEquals(refs(2).shape, Shape.Formed(Field.Null))
    assertEquals(refs(2).alts, Field.Invalid(Json.fromString("none")))
    val odd = refs(1).alts match
      case Field.Value(hs) => hs
      case other           => fail(s"alts: $other")
    assertEquals(odd(0).w, Field.Invalid(Json.fromString("high")))
    // Row 9 is not a row of this source, and a reversed interval is not an interval.
    assertEquals(odd(0).iv, Field.Invalid(parsed("[[1,9]]")))
    assertEquals(odd(1).w, Field.Absent)
    assertEquals(odd(1).iv, Field.Invalid(parsed("[[4,1]]")))
    assertEquals(odd(2), Hypothesis(Field.Null, Field.Null))

  test("weights are kept as written: not renormalized, even when they sum past one"):
    val a = answer(v2Item, Schema.LocalV2, rows)
    val first = a.refs match
      case Field.Value(rs) => rs.head
      case other           => fail(s"refs: $other")
    val hs = first.alts match
      case Field.Value(hs) => hs
      case other           => fail(s"alts: $other")
    assertEquals(hs.map(_.iv), Vector(Field.Value(Vector(iv(1, 1))), Field.Value(Vector(iv(2, 4)))))
    val ws = hs.map(_.w match
      case Field.Value(n) => n.toBigDecimal.get
      case other          => fail(s"w: $other"))
    assertEquals(ws, Vector(BigDecimal("0.70"), BigDecimal("0.6")))
    val encoded = ReferenceAnswers.json(a).noSpaces
    assert(encoded.contains("\"value\":0.70"), encoded)
    assert(encoded.contains("\"value\":0.6"), encoded)

  test("a weight outside [0, 1] is invalid and kept verbatim"):
    val item = parsed("""{"refs":[{"alts":[{"w":1.5,"iv":[[1,2]]},{"w":-0.1,"iv":[[1,2]]}]}]}""")
    val ws = answer(item, Schema.LocalV2, rows).refs match
      case Field.Value(Vector(r)) =>
        r.alts match
          case Field.Value(hs) => hs.map(_.w)
          case other           => fail(s"alts: $other")
      case other => fail(s"refs: $other")
    assertEquals(ws, Vector(Field.Invalid(parsed("1.5")), Field.Invalid(parsed("-0.1"))))

  test("an absent item field is absent, not a default"):
    val a = answer(parsed("""{"id":"xb"}"""), Schema.LocalV2, rows)
    assertEquals(
      a,
      Answer(TalkAnswer.Whole(Field.Absent), HedgeAnswer.Flag(Field.Absent), Field.Absent)
    )

  test("v3: spans, a hedge kind with its words, and faceted references"):
    val item = parsed(
      """{"id":"xc","spans":[{"words":"I think","talk":"metamemory"},{"words":"she ran","talk":"repair"}],
        |"hedge":"memory","hedge_words":"I think",
        |"refs":[{"words":"she ran","extent":"moment","occurrence":"generic","mode":"gist",
        |"details":[{"type":"who","words":"she"},{"type":"smell","words":"x"}],
        |"alts":[{"w":0.5,"iv":[[1,2],[4,4]]}]}]}""".stripMargin
    )
    val a = answer(item, Schema.LocalV3, rows)
    assertEquals(
      a.talk,
      TalkAnswer.Spans(
        Field.Value(
          Vector(
            Span(Field.Value("I think"), Field.Value(Talk.Metamemory)),
            Span(Field.Value("she ran"), Field.Value(Talk.Repair))
          )
        )
      )
    )
    assertEquals(a.hedge, HedgeAnswer.Kind(Field.Value(HedgeKind.Memory), Field.Value("I think")))
    a.refs match
      case Field.Value(Vector(r)) =>
        r.shape match
          case Shape.Faceted(e, o, m, Field.Value(ds)) =>
            assertEquals(
              (e, o, m),
              (Field.Value(Extent.Moment), Field.Value(Occurrence.Generic), Field.Value(Mode.Gist))
            )
            assertEquals(ds(1).kind, Field.Invalid(Json.fromString("smell")))
          case other => fail(s"shape: $other")
        assertEquals(value(r.alts).map(_.iv), Vector(Field.Value(Vector(iv(1, 2), iv(4, 4)))))
      case other => fail(s"refs: $other")
    // A null hedge is an explicit null; repair is not a v2 talk type.
    assertEquals(
      answer(parsed("""{"hedge":null}"""), Schema.LocalV3, rows).hedge,
      HedgeAnswer.Kind(Field.Null, Field.Absent)
    )
    assertEquals(
      answer(parsed("""{"talk":"repair"}"""), Schema.LocalV2, rows).talk,
      TalkAnswer.Whole(Field.Invalid(Json.fromString("repair")))
    )

  // Replay from recorded exchanges.

  private val system = "REFERENCE SYSTEM"
  private def reply(text: String) =
    ModelReply("opus", text, ModelStopReason.EndTurn, ReplyEvidence.Authored)
  private val batches = Vector(
    Batch("ITEMS one", Vector("xa", "xb")),
    Batch("ITEMS two", Vector("xc"))
  )
  private val first =
    """Here: {"items":[{"id":"xa","talk":"episodic","hedge":false,"refs":[{"words":"she came in",
      |"form":"specific","alts":[{"w":0.9,"iv":[[r1, r1]]}]}]},{"id":"zz","talk":"fragment"}]}""".stripMargin

  private def recorded: Recordings =
    val dir = Files.createTempDirectory("reference-replay")
    val rec = right(Recordings.at(dir))
    def put(prompt: String, text: String) =
      right(rec.write(TextExchange.key("cli", "opus", system, prompt), reply(text)))
    put("ITEMS one", first)
    put("ITEMS two", "not json")
    put(
      "ITEMS two" + suffix(1),
      """{"items":[{"id":"xc","talk":"commentary","hedge":false,"refs":[]}]}"""
    )
    rec

  test("replay of recorded exchanges gives identical outcomes twice, with no model client"):
    val rec = recorded
    def serve(p: String): Either[ExchangeFailure, ModelReply] =
      TextExchange.replay(rec, "cli", "opus", system, p)
    val once = right(replay(batches, Schema.LocalV2, rows, serve))
    val twice = right(replay(batches, Schema.LocalV2, rows, serve))
    assertEquals(once, twice)
    val (outcomes, receipts) = once
    assertEquals(outcomes.keySet, Set("xa", "xb", "xc"))
    assertEquals(outcomes("xb"), Outcome.Omitted)
    outcomes("xa") match
      case Outcome.Answered(a, raw) =>
        assertEquals(raw.hcursor.get[String]("id").toOption, Some("xa"))
        assertEquals(
          value(a.refs).map(r => value(r.alts).map(_.iv)),
          Vector(Vector(Field.Value(Vector(iv(1, 1)))))
        )
      case other => fail(s"xa: $other")
    assertEquals(receipts, Vector(BatchReceipt(0, 1, true, 0), BatchReceipt(1, 2, true, 0)))

  test("an attempt the replay needs but has no recording refuses the replay"):
    val rec = recorded
    val missing = Vector(Batch("ITEMS three", Vector("xd")))
    assertEquals(
      replay(missing, Schema.LocalV2, rows, TextExchange.replay(rec, "cli", "opus", system, _)),
      Left(ReplayFailure.Unrecorded(0, 0))
    )

  test("every attempt unparsable marks the batch's items unparsable; a repeated item is refused"):
    val serve: String => Either[ExchangeFailure, ModelReply] = _ => Right(reply("no json"))
    val (out, receipts) =
      right(replay(Vector(Batch("p", Vector("xa"))), Schema.LocalV2, rows, serve))
    assertEquals(out, Map("xa" -> Outcome.Unparsable))
    assertEquals(receipts, Vector(BatchReceipt(0, Attempts, false, 0)))
    assertEquals(
      replay(
        Vector(Batch("p", Vector("xa")), Batch("q", Vector("xa"))),
        Schema.LocalV2,
        rows,
        serve
      ),
      Left(ReplayFailure.RepeatedItem("xa"))
    )

  // The reference bundle.

  private lazy val condition = declared("local", contentLocal(2))
  private lazy val outcomes = right(
    replay(batches, Schema.LocalV2, rows, TextExchange.replay(recorded, "cli", "opus", system, _))
  )._1
  private lazy val unitIds = recall.unitIds.map(_.value)
  private lazy val asked =
    Vector((1, unitIds(0), "xa"), (2, unitIds(1), "xb"), (3, unitIds(2), "xc"))
  private lazy val provenance = ReferenceBundle.Provenance(
    "p1",
    "s1",
    condition,
    "opus",
    "cli",
    Schema.LocalV2,
    Checksum.ofText(system).hex
  )
  private lazy val refUnits =
    asked.map((u, id, item) => ReferenceBundle.UnitAnswer(u, id, item, outcomes(item)))
  private lazy val doc = ReferenceBundle.encode(provenance, rows, refUnits)
  private lazy val expectedRef = ReferenceBundle.Expected(
    "p1",
    "s1",
    asked,
    condition,
    "opus",
    "cli",
    Schema.LocalV2,
    Checksum.ofText(system).hex
  )

  test("a reference bundle round trips through text"):
    val back = right(
      io.circe.jawn
        .parse(doc.noSpaces)
        .left
        .map(_.message)
        .flatMap(ReferenceBundle.decode(_, rows, expectedRef))
    )
    assertEquals(back.units, refUnits)
    assertEquals(back.provenance, provenance)
    assertEquals(
      doc.hcursor.downField("provenance").downField("producerCondition").focus,
      Some(contentLocal(2).json)
    )

  test("a reference bundle refuses an edited answer, an edited reply, or another condition"):
    def unit0(f: Json => Json)(d: Json) =
      d.mapObject(o => o.add("units", o("units").get.mapArray(us => us.updated(0, f(us(0))))))
    val answerEdited = unit0(_.mapObject(_.add("answer", Json.obj())))(doc)
    assert(ReferenceBundle.decode(answerEdited, rows, expectedRef).isLeft)
    val replyEdited = unit0(
      _.mapObject(o => o.add("reply", o("reply").get.mapObject(_.add("hedge", Json.True))))
    )(doc)
    assertEquals(
      ReferenceBundle.decode(replyEdited, rows, expectedRef),
      Left("unit 1: the answer is not what its reply gives against this source")
    )
    val other = expectedRef.copy(condition = declared("local", contentLocal(3)))
    assertEquals(
      ReferenceBundle.decode(doc, rows, other),
      Left("the bundle names another producer condition")
    )
    val omittedWithReply =
      unit0(_.mapObject(_.add("status", Json.fromString("omitted"))))(doc)
    assert(ReferenceBundle.decode(omittedWithReply, rows, expectedRef).isLeft)
