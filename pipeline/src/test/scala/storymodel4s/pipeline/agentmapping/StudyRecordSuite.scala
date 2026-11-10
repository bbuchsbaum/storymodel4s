package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.{ExpectedMappingContext, MappingCodecs}
import storymodel4s.core.Checksum
import storymodel4s.pipeline.TimedAnnotationInput
import storymodel4s.recall.DiscourseFunction

/** Supplied recall units and checked records on an invented two-film source: a film-level choice, a
  * scene choice, a non-assignment, and each way a judgement can fail.
  */
class StudyRecordSuite extends FunSuite:
  import StudyRecord.Judgement
  import StudyReplay.{ReplyProtocol, Stated, UnitReply}

  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  private val parts =
    """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
  // Film 1 has scenes 1 (content) and 2 (a title card, not a target); collection 2 is a cartoon
  // and not a target; film 3 has scene 4.
  private def csv(header: Vector[String], rows: Vector[String]) =
    (header.mkString(",") +: rows).mkString("", "\n", "\n").getBytes(UTF_8)
  private lazy val view = right(
    TimedAnnotationInput.prepare(
      csv(
        TimedAnnotationInput.HeaderV2,
        Vector(
          "1,Anna arrived.,a,10,20,1,scene 1,,Anna,1,Harbour film,",
          "2,A title card.,a,20,30,2,scene 2,,,1,Harbour film,no",
          "3,A mouse dances.,a,40,50,3,scene 3,,,2,Cartoon,no",
          "4,Bob left.,a,50,,4,scene 4,,Bob,3,Station film,"
        )
      ),
      parts.getBytes(UTF_8)
    )
  )
  private val texts = Vector("she came in", "then the title", "a mouse", "he left", "um", "what")
  private val artifact = Checksum.ofText("units file")
  private lazy val recall = right(PreparedRecall.supplied("p1_s1", texts, artifact))
  private lazy val producer = StudyRecord.producer("cli", "m", "SYSTEM", view, recall)
  private val policy = StudyRecord.policy("annotation", "strict-chunked/v1", "referent")

  private def judged(s: Stated) = StudyRecord.judge(view, s)
  private lazy val judgements = Vector(
    judged(Stated.Scene(1, Json.fromInt(1))),
    judged(Stated.Scene(2, Json.fromInt(1))),
    judged(Stated.Collection(3)),
    judged(Stated.NoAssignment),
    StudyRecord.judge(view, ReplyProtocol.StrictChunked, UnitReply.Omitted, "referent"),
    judged(Stated.Invalid("missing-field"))
  )
  private lazy val record =
    right(
      StudyRecord.record("referent", policy, recall, view, producer, judgements).left.map(_.message)
    )

  test("supplied units keep their text, order and line, and are marked as not assessed"):
    assertEquals(recall.texts, texts)
    assertEquals(recall.graph.ordered.map(_.text), texts)
    assertEquals(recall.graph.ordered.map(_.ordinal), texts.indices.toVector)
    assert(recall.graph.ordered.forall(_.function == DiscourseFunction.NotAssessed))
    assertEquals(
      recall.unitIds.map(_.value).map(_.split(":u").last),
      texts.indices.map(_.toString).toVector
    )
    assertEquals(
      recall.segmentation,
      PreparedRecall.Segmentation("supplied", artifact, "supplied-units/v1")
    )

  test("the same texts give the same recall, and a changed text or artifact a different one"):
    assertEquals(right(PreparedRecall.supplied("p1_s1", texts, artifact)), recall)
    val other = right(PreparedRecall.supplied("p1_s1", texts.updated(0, "she left"), artifact))
    assertNotEquals(other.inventory.digest, recall.inventory.digest)
    assertNotEquals(other.unitIds, recall.unitIds)
    val moved = right(PreparedRecall.supplied("p1_s1", texts, Checksum.ofText("another file")))
    assertNotEquals(moved, recall)

  test("an empty session, an empty unit and a unit spanning lines are refused"):
    assertEquals(
      PreparedRecall.supplied("t", Vector.empty, artifact).left.toOption,
      Some("a session needs at least one unit")
    )
    assertEquals(
      PreparedRecall.supplied("t", Vector("a", ""), artifact).left.toOption,
      Some("a unit is empty or spans lines")
    )
    assertEquals(
      PreparedRecall.supplied("t", Vector("a\nb"), artifact).left.toOption,
      Some("a unit is empty or spans lines")
    )

  test("a stated scene is a target, not a target, or unknown; a film is a target or unknown"):
    assertEquals(judged(Stated.Scene(1, Json.Null)), Judgement.Scene(1, view.groupRefs(1)))
    assertEquals(judged(Stated.Scene(2, Json.Null)), Judgement.NotATarget(2))
    assertEquals(judged(Stated.Scene(3, Json.Null)), Judgement.NotATarget(3))
    assertEquals(judged(Stated.Scene(9, Json.Null)), Judgement.UnknownScene("9"))
    assertEquals(
      judged(Stated.Scene(BigInt("99999999999999999999"), Json.Null)),
      Judgement.UnknownScene("99999999999999999999")
    )
    assertEquals(judged(Stated.Collection(3)), Judgement.Collection(3, view.collectionRefs(3)))
    // The cartoon is a collection of the source but not a film anyone may choose.
    assertEquals(judged(Stated.Collection(2)), Judgement.UnknownCollection("2"))
    assertEquals(judged(Stated.Collection(7)), Judgement.UnknownCollection("7"))
    assertEquals(judged(Stated.NoAssignment), Judgement.NoAssignment)

  test("the film a reply states beside a scene is not used: the source decides the film"):
    assertEquals(judged(Stated.Scene(1, Json.fromInt(3))), judged(Stated.Scene(1, Json.fromInt(1))))

  test("a film-level choice, a scene choice, a non-assignment and failures are distinct outcomes"):
    val out = record.outcomes
    def chosen(i: Int) = out(i).decision.flatMap(_.chosen)
    assertEquals(chosen(0), Some(Destination.Target(view.groupRefs(1))))
    assertEquals(chosen(2), Some(Destination.Target(view.collectionRefs(3))))
    assertEquals(out(2).localization, LocalizationStatus.Located)
    assertEquals(
      out(3).decision.map(_.origin),
      Some(DecisionOrigin.Abstention("model reported no referent assignment"))
    )
    assertEquals(
      out(1).processing,
      ProcessingStatus.Failed(
        ProcessingFailure.InvalidOutput("referent names scene 2, which is not a target")
      )
    )
    assertEquals(
      out(4).processing,
      ProcessingStatus.Failed(
        ProcessingFailure.ProviderFailure("unit omitted from the accepted reply")
      )
    )
    assertEquals(
      out(5).processing,
      ProcessingStatus.Failed(ProcessingFailure.InvalidOutput("referent: missing-field"))
    )
    assert(out.flatMap(_.decision).forall(_.numerical.isEmpty))
    assertEquals(
      judgements.map(_.kind),
      Vector("scene", "invalid-scene", "film", "none", "omitted", "invalid")
    )

  test("the universe is the source's target scenes and films, and nothing else"):
    val universe = right(StudyRecord.universe(view).left.map(_.message))
    assertEquals(universe.targets.toSet, (view.targetGroups ++ view.targetCollections).toSet)
    assertEquals(universe.targets.size, 4)
    assertEquals(universe.grain, TargetGrain.Hierarchy(Vector(1, 2)))
    assertEquals(record.policies.universe.id, universe.id)

  test("a source with no film level has a single-level universe"):
    val flat = right(
      TimedAnnotationInput.prepare(
        csv(
          TimedAnnotationInput.Header,
          Vector("1,Anna arrived.,a,10,20,1,scene 1,,Anna", "2,Bob left.,a,50,,2,scene 2,,Bob")
        ),
        parts.getBytes(UTF_8)
      )
    )
    val universe = right(StudyRecord.universe(flat).left.map(_.message))
    assertEquals(universe.grain, TargetGrain.SingleLevel(1))
    assertEquals(StudyRecord.judge(flat, Stated.Collection(1)), Judgement.UnknownCollection("1"))

  test("a record needs one judgement per unit"):
    assertEquals(
      StudyRecord
        .record("referent", policy, recall, view, producer, judgements.drop(1))
        .left
        .toOption,
      Some(MappingRefusal.OutcomeInventoryMismatch)
    )

  test("records of two judgements, or of two conditions, have different policies and digests"):
    val narrative = StudyRecord.policy("annotation", "strict-chunked/v1", "narrative")
    assertNotEquals(narrative, policy)
    assertNotEquals(StudyRecord.policy("auto", "strict-chunked/v1", "referent"), policy)
    val other = right(
      StudyRecord
        .record("narrative", narrative, recall, view, producer, judgements)
        .left
        .map(_.message)
    )
    assertNotEquals(other.digest, record.digest)

  test("a record with a film-level choice saves and reloads as a categorical record"):
    val text = MappingCodecs.encode(record)
    val version = parse(text).toOption.flatMap(_.hcursor.get[String]("schemaVersion").toOption)
    assertEquals(version, Some(MappingCodecs.CategoricalSchemaVersion))
    val context = ExpectedMappingContext(recall.inventory, view.representation)
    val decoded = right(MappingCodecs.decode(text, context).left.map(_.message))
    assertEquals(decoded.digest, record.digest)
