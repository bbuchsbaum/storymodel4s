package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import munit.FunSuite
import storymodel4s.align.DecisionPolicy
import storymodel4s.core.Checksum

/** The typed producer condition: its refused combinations, its JSON, its place in the policy
  * identifier and the stage receipt, and how bundles record it (v3) or read an old name (v2).
  */
class ProducerConditionSuite extends FunSuite:
  import ConditionFixture.*

  private val opaque = ExposedIds.OpaqueSalted("sha256-prefix10", Checksum.ofText("salt"))
  private val two = right(ContextWindow.Preceding.of(2))

  test("a negative window is refused; zero preceding units is a window"):
    assertEquals(ContextWindow.Preceding.of(-1), Left(ProducerCondition.Refusal.NegativeWindow(-1)))
    assertEquals(ContextWindow.Preceding.of(0).map(_.units), Right(0))

  test("hidden order with positional ids is refused, and the same shuffle with opaque ids is not"):
    assertEquals(
      ProducerCondition.of(two, OrderExposure.PooledShuffle(7L), ExposedIds.Positional),
      Left(ProducerCondition.Refusal.PositionalIdsExposeOrder)
    )
    assert(ProducerCondition.of(two, OrderExposure.PooledShuffle(7L), opaque).isRight)

  test("a whole recall cannot hide order; in order, it may use either kind of id"):
    assertEquals(
      ProducerCondition.of(ContextWindow.WholeRecall, OrderExposure.PooledShuffle(7L), opaque),
      Left(ProducerCondition.Refusal.WholeRecallExposesOrder)
    )
    assert(ProducerCondition.of(ContextWindow.WholeRecall, OrderExposure.Visible, opaque).isRight)
    assertEquals(
      ProducerCondition.of(
        ContextWindow.WholeRecall,
        OrderExposure.Visible,
        ExposedIds.Positional
      ),
      Right(wholeRecall)
    )

  test("an id scheme must be a plain name"):
    assertEquals(
      ProducerCondition.of(
        two,
        OrderExposure.Visible,
        ExposedIds.OpaqueSalted("a b", Checksum.ofText("salt"))
      ),
      Left(ProducerCondition.Refusal.BadScheme("a b"))
    )

  test("the JSON round trips, holds the salt's checksum and never the salt"):
    val c = contentLocal(2, seed = 11L, salt = "secret-salt")
    val doc = c.json
    assertEquals(ProducerCondition.decode(doc), Right(c))
    assertEquals(ProducerCondition.decode(right(parse(doc.noSpaces))), Right(c))
    assert(!doc.noSpaces.contains("secret-salt"))
    assert(doc.noSpaces.contains(Checksum.ofText("secret-salt").hex))
    assertEquals(c.rendering.count(_ == ':'), 3)

  test("a stored rendering that is not the fields' rendering is refused"):
    val doc = contentLocal(2).json.mapObject(_.add("rendering", Json.fromString("x")))
    assert(ProducerCondition.decode(doc).isLeft)
    val forged = wholeRecall.json.mapObject(o =>
      o.add(
        "order",
        Json.obj("kind" -> Json.fromString("pooled-shuffle"), "seed" -> Json.fromInt(1))
      )
    )
    assertEquals(
      ProducerCondition.decode(forged),
      Left(ProducerCondition.Refusal.PositionalIdsExposeOrder)
    )
    val wholeShuffled =
      forged.mapObject(_.add("ids", contentLocal(2).json.hcursor.downField("ids").focus.get))
    assertEquals(
      ProducerCondition.decode(wholeShuffled),
      Left(ProducerCondition.Refusal.WholeRecallExposesOrder)
    )

  test("a legacy condition keeps the old policy identifier exactly"):
    assertEquals(
      StudyRecord.policy(legacy("annotation"), protocolName, "referent").value,
      "agent-localization:annotation:strict-chunked/v1:referent"
    )

  test("policy identifiers differ when only the condition differs"):
    def id(c: RecordedCondition) = StudyRecord.policy(c, protocolName, "referent")
    val ids = Vector(
      legacy("annotation"),
      declared("annotation", wholeRecall),
      declared("annotation", contentLocal(2)),
      declared("annotation", contentLocal(0)),
      declared("annotation", contentLocal(2, seed = 1L)),
      declared("annotation", contentLocal(2, salt = "other"))
    ).map(id)
    assertEquals(ids.distinct.size, ids.size)

  test("records under different conditions are different records; the receipt binds the rendering"):
    val local = declared("annotation", contentLocal(2))
    val whole = declared("annotation", wholeRecall)
    val a = records(local)
    val b = records(whole)
    val c = records(legacy("annotation"))
    StudyBundle.Fields.foreach { f =>
      assertNotEquals(a(f).digest, b(f).digest)
      assertNotEquals(a(f).digest, c(f).digest)
      assertEquals(
        a(f).policies.decision,
        DecisionPolicy.Declared(StudyRecord.policy(local, protocolName, f))
      )
    }
    assertEquals(producer(local).renderingSha256, Some(contentLocal(2).digest))
    assertEquals(producer(legacy("annotation")).renderingSha256, None)

  test("a declared bundle is v3, names its condition, and reads back through text"):
    val c = declared("annotation", contentLocal(2))
    val doc = bundle(c)
    assertEquals(doc.hcursor.get[String]("schema").toOption, Some(StudyBundle.SchemaV3))
    assertEquals(
      doc.hcursor.downField("provenance").downField("producerCondition").focus,
      Some(contentLocal(2).json)
    )
    val loaded = right(decode(right(parse(doc.noSpaces)), c))
    assertEquals(loaded.condition, c)
    StudyBundle.Fields.foreach(f => assertEquals(loaded.records(f).digest, records(c)(f).digest))

  test("a legacy bundle is written as v2 exactly as before and reads as the legacy case"):
    val doc = bundle(legacy("annotation"))
    assertEquals(doc.hcursor.get[String]("schema").toOption, Some(StudyBundle.Schema))
    assertEquals(doc.hcursor.downField("provenance").downField("producerCondition").focus, None)
    val loaded = right(
      StudyBundle.decode(
        doc,
        view,
        recall,
        StudyBundle.Expected("p1", "s1", units, producer(legacy("annotation")))
      )
    )
    assertEquals(loaded.condition, legacy("annotation"))
    assertEquals(loaded.condition.producer, None)

  test("a v3 bundle whose producer condition is edited, removed or relabelled v2 is refused"):
    val c = declared("annotation", contentLocal(2))
    val doc = bundle(c)
    def provenance(f: io.circe.JsonObject => io.circe.JsonObject)(d: Json) =
      d.mapObject(o => o.add("provenance", o("provenance").get.mapObject(f)))
    val k3 = provenance(_.add("producerCondition", contentLocal(3).json))(doc)
    val k3Expected = StudyBundle.Expected("p1", "s1", units, producer(c))
    assert(StudyBundle.decode(k3, view, recall, k3Expected).isLeft)
    assert(
      StudyBundle
        .decode(provenance(_.remove("producerCondition"))(doc), view, recall, k3Expected)
        .isLeft
    )
    val v2 = doc.mapObject(_.add("schema", Json.fromString(StudyBundle.Schema)))
    assertEquals(
      StudyBundle.decode(v2, view, recall, k3Expected),
      Left("a v2 bundle cannot declare a producer condition")
    )

  test("a bundle read against another condition, or a producer of another rendering, is refused"):
    val c = declared("annotation", contentLocal(2))
    val doc = bundle(c)
    assertEquals(
      decode(doc, declared("annotation", wholeRecall)).left.toOption,
      Some("the bundle names another condition")
    )
    assertEquals(
      StudyBundle.decode(
        doc,
        view,
        recall,
        StudyBundle.Expected("p1", "s1", units, producer(legacy("annotation")))
      ),
      Left("the producer's rendering is not the bundle's producer condition")
    )

  test("a study export without a producer condition reads as legacy; with one, as declared"):
    val base = Json.obj(
      "schema" -> Json.fromString(StudyExport.Schema),
      "model" -> Json.fromString("m"),
      "condition" -> Json.fromString("local"),
      "system" -> Json.fromString("S"),
      "systemSha256" -> Json.fromString(Checksum.ofText("S").hex),
      "replyProtocol" -> Json.fromString(protocolName),
      "attempts" -> Json.fromInt(3),
      "retrySuffix" -> Json.fromString(" {attempt}"),
      "extras" -> Json.arr(),
      "unitLine" -> Json.fromString("{u} {text}"),
      "chunkLine" -> Json.fromString("{lo}-{hi}"),
      "manifestSha256" -> Json.fromString("0" * 64),
      "sessions" -> Json.arr()
    )
    assertEquals(StudyExport.parseIndex(base).map(_.condition), Right(legacy("local")))
    val withCondition = base.mapObject(_.add("producerCondition", contentLocal(2).json))
    assertEquals(
      StudyExport.parseIndex(withCondition).map(_.condition),
      Right(declared("local", contentLocal(2)))
    )
    val bad = base.mapObject(_.add("producerCondition", Json.obj("window" -> Json.Null)))
    assert(StudyExport.parseIndex(bad).isLeft)
