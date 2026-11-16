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
    assertEquals(receipts, Vector(BatchReceipt(0, 1, true, 0, 0), BatchReceipt(1, 2, true, 0, 0)))

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
    assertEquals(receipts, Vector(BatchReceipt(0, Attempts, false, 0, 0)))
    assertEquals(
      replay(
        Vector(Batch("p", Vector("xa")), Batch("q", Vector("xa"))),
        Schema.LocalV2,
        rows,
        serve
      ),
      Left(ReplayFailure.RepeatedItem("xa"))
    )

  test("a repeated reply id within one batch keeps the last item and is counted"):
    val text = """{"items":[{"id":"xa","talk":"fragment"},{"id":"xa","talk":"commentary"},""" +
      """{"id":5,"talk":"episodic"},{"talk":"episodic"}]}"""
    val serve: String => Either[ExchangeFailure, ModelReply] = _ => Right(reply(text))
    val (out, receipts) =
      right(replay(Vector(Batch("p", Vector("xa", "5"))), Schema.LocalV2, rows, serve))
    assertEquals(receipts, Vector(BatchReceipt(0, 1, true, 1, 1)))
    out("xa") match
      case Outcome.Answered(a, _) =>
        assertEquals(a.talk, TalkAnswer.Whole(Field.Value(Talk.Commentary)))
      case other => fail(s"xa: $other")
    // A numeric id answers no item, even one asked as "5": it is refused, not coerced.
    assertEquals(out("5"), Outcome.Omitted)

  // The reference bundle.

  private lazy val condition = declared("local", contentLocal(2))
  private lazy val run = referenceRun(condition, referenceBatches(2))

  test("a reference bundle round trips through text, with its batches and rendering"):
    val doc = right(run.doc)
    val back = right(
      io.circe.jawn
        .parse(doc.noSpaces)
        .left
        .map(_.message)
        .flatMap(ReferenceBundle.decode(_, rows, run.expected))
    )
    assertEquals(back.units, run.units)
    assertEquals(back.provenance, run.provenance)
    assertEquals(
      doc.hcursor.downField("provenance").downField("producerCondition").focus,
      Some(contentLocal(2).json)
    )
    val batches = doc.hcursor.get[Vector[Json]]("batches").toOption.get
    assertEquals(
      batches.map(_.hcursor.get[String]("promptSha256").toOption),
      run.recorded.map(r => Some(Checksum.ofText(r.batch.prompt).hex))
    )
    // Only this session's items are listed; the foreign item stays out.
    assertEquals(
      batches.head.hcursor.get[Vector[String]]("items").toOption,
      Some(Vector("xb", "xa"))
    )
    assertEquals(
      doc.hcursor.downField("rendering").get[String]("sha256").toOption,
      Some(back.rendering.sha256.hex)
    )

  test("the rendering identity is derived from the prompts, not the condition alone"):
    val other =
      referenceRun(condition, referenceBatches(2).map(b => b.copy(prompt = b.prompt + "\n")))
    assertNotEquals(right(other.loaded).rendering.sha256, right(run.loaded).rendering.sha256)
    assertNotEquals(right(run.loaded).rendering.sha256, contentLocal(2).digest)

  test("a unit whose ITEM line is not in its batch prompt is refused"):
    val missing = referenceBatches(2).updated(
      1,
      Batch(harnessPrompt(Vector(itemBlock("xq", Some("ctx"), "a mouse"))), Vector("xc"))
    )
    assertEquals(
      referenceRun(condition, missing).doc,
      Left("unit 3: its ITEM line is not once in its prompt")
    )

  test("context lines must match the declared window"):
    // Prompts with context, declared as target-only; and prompts without, declared as k = 2.
    assertEquals(
      referenceRun(declared("local", contentLocal(0)), referenceBatches(2)).doc.left.toOption,
      Some("unit 1: the window is the target only but its prompt shows context")
    )
    assertEquals(
      referenceRun(condition, referenceBatches(0)).doc.left.toOption,
      Some("unit 1: the window has preceding units but its prompt shows no context")
    )
    assert(referenceRun(declared("local", contentLocal(0)), referenceBatches(0)).doc.isRight)

  test("under declared opaque ids, an id that encodes the unit's position is refused"):
    val positional = Vector("u1", "u2", "u3")
    assertEquals(
      referenceRun(condition, referenceBatches(2, positional), positional).doc,
      Left("unit 1: its item id encodes its position, under declared opaque ids")
    )
    assert(ReferenceBundle.encodesPosition("3", 3, 2, "unit-x"))
    assert(ReferenceBundle.encodesPosition("item_002", 9, 1, "unit-x"))
    assert(ReferenceBundle.encodesPosition("x-unit-x", 9, 1, "unit-x"))
    assert(!ReferenceBundle.encodesPosition("x3f9a01c2be", 3, 2, "unit-x"))

  test("a whole-recall condition is refused on the reference path"):
    assertEquals(
      referenceRun(declared("local", wholeRecall), referenceBatches(2)).doc,
      Left(ProducerCondition.Refusal.NotRendered("reference path", wholeRecall.rendering).message)
    )

  test("a reference bundle refuses an edited answer, reply, receipt, rendering or condition"):
    val doc = right(run.doc)
    def unit0(f: Json => Json)(d: Json) =
      d.mapObject(o => o.add("units", o("units").get.mapArray(us => us.updated(1, f(us(1))))))
    def decodeIt(d: Json) = ReferenceBundle.decode(d, rows, run.expected)
    // Unit 1 is in position 0; edit the answered unit xa.
    def first(f: Json => Json)(d: Json) =
      d.mapObject(o => o.add("units", o("units").get.mapArray(us => us.updated(0, f(us(0))))))
    assert(decodeIt(first(_.mapObject(_.add("answer", Json.obj())))(doc)).isLeft)
    assertEquals(
      decodeIt(
        first(
          _.mapObject(o => o.add("reply", o("reply").get.mapObject(_.add("hedge", Json.True))))
        )(doc)
      ),
      Left("unit 1: the answer is not what its reply gives against this source")
    )
    assert(decodeIt(unit0(_.mapObject(_.add("reply", Json.obj())))(doc)).isLeft)
    val receipt = doc.mapObject(o =>
      o.add(
        "batches",
        o("batches").get.mapArray(bs =>
          bs.updated(0, bs(0).mapObject(_.add("attempts", Json.fromInt(2))))
        )
      )
    )
    assertEquals(decodeIt(receipt), Left("the batches are not the expected prompts and receipts"))
    val rendering = doc.mapObject(o =>
      o.add("rendering", o("rendering").get.mapObject(_.add("sha256", Json.fromString("0" * 64))))
    )
    assertEquals(decodeIt(rendering), Left("the rendering is not what the prompts give"))
    val other = run.expected.copy(condition = declared("local", contentLocal(3)))
    assertEquals(
      ReferenceBundle.decode(doc, rows, other),
      Left("the bundle names another producer condition")
    )
