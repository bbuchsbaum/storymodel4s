package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.MappingCodecs
import storymodel4s.core.Checksum
import storymodel4s.corpus.intake.{SherlockAnnotations, TimebaseRepair}

/** The saved bundle on a synthetic two-scene film and an invented recall. It exercises what the
  * all-successful development replay cannot: narrative and referent differ, a cited row lies
  * outside the narrative scene, one citation resolves to no row, and omitted, unparsable, invalid,
  * absent and explicit-null answers all occur. No real annotation or recall is used.
  */
class AgentBundleSuite extends FunSuite:
  import AgentAnswers.{Alternative, Answer, Field, Outcome}
  import AgentMappingRecord.{Interpretation, Producer}

  private val header =
    "Segment Number\tStart Time (s) \tEnd Time (s) \tStart TR\tEnd TR\tScene Segments\t" +
      "Scene Details - A Level \tSpace-In/Outdoor\tName - All\tName - Focus\tName - Speaking\t" +
      "Location\tCamera Angle\tMusic Presence \tWords on Screen "
  private val table = Vector(
    header,
    "1\t0\t10\t1\t7\t1. Opening\tA man walks in the rain.\tOutdoor\tMan\tMan\t\tStreet\tLong\tNo\t",
    "2\t10\t20\t8\t14\t\tThe man knocks on a red door.\tOutdoor\tMan\t\tMan\tStreet\tMedium\tNo\t",
    "3\t20\t20\t14\t14\t\tSmash cut to black.\tIndoor\t\t\t\t\tClose\tNo\t",
    "4\t20\t30\t\t\t\tBlack screen between reels.\tIndoor\t\t\t\t\tLong\tNo\t",
    "5\t0\t5\t21\t24\t2. Cartoon\tPeople in costumes parade.\tIndoor\tSingers\t\t\tStage\tLong\tYes\t",
    "6\t5\t12\t25\t29\t\tPopcorn pops in a machine.\tIndoor\tSinger\t\tSinger\tStage\tMedium\tYes\t"
  ).mkString("\n").getBytes(StandardCharsets.UTF_8)

  private lazy val atlas: SherlockAnnotations.Atlas =
    val stream = getClass.getResourceAsStream("/agentmapping/sherlock-synthetic-repair.json")
    val json =
      try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    val record = TimebaseRepair
      .parse(json.replace("0" * 64, Checksum.ofBytes(table).hex))
      .fold(e => fail(e.message), identity)
    SherlockAnnotations.parse(table, record).fold(e => fail(e.message), identity)

  private lazy val film =
    AgentMappingRecord.film(atlas, Checksum.ofBytes(table)).fold(e => fail(e), identity)

  private val recallCsv = ("Words" +: Vector(
    "A man walked in the rain.",
    "Then he knocked on a door.",
    "I think it was red.",
    "After that there was a parade.",
    "Someone was singing.",
    "Popcorn was popping.",
    "That is all I remember."
  ).flatMap(_.split(' '))).mkString("\n").getBytes(StandardCharsets.UTF_8)

  private lazy val inventory =
    AgentMappingRecord.inventory(recallCsv).fold(e => fail(e), identity)

  private val producer =
    Producer("synthetic-cli", "synthetic-model", Checksum.ofText("system"), Checksum.ofText("outline"))

  private def answered(
      narrative: Field[Int],
      referent: Field[Int],
      alternatives: Field[Vector[Alternative]] = Field.Absent,
      rows: Field[Vector[Int]] = Field.Absent,
      confidence: Field[Double] = Field.Absent
  ) = Outcome.Answered(Answer(narrative, referent, alternatives, rows, confidence, 0, 0, "PROMPT"))

  private lazy val outcomes: Vector[Outcome] =
    val special = Vector(
      answered(
        Field.Value(1),
        Field.Value(2),
        Field.Value(Vector(Alternative(1, Json.fromDoubleOrNull(0.6)), Alternative(2, Json.fromDoubleOrNull(0.3)))),
        Field.Value(Vector(5, 2, 999)),
        Field.Value(0.8)
      ),
      answered(Field.Null, Field.Absent),
      Outcome.Omitted(0, 1),
      Outcome.Unparsable(1, 3),
      answered(Field.Invalid(Json.fromInt(99)), Field.Null)
    )
    assert(inventory.units.size > special.size, s"fixture needs more units: ${inventory.units.size}")
    special ++ Vector.fill(inventory.units.size - special.size)(
      answered(Field.Value(2), Field.Value(2))
    )

  private lazy val keyed = inventory.units.map(_.id).zip(outcomes)
  private def record(i: Interpretation) =
    AgentMappingRecord.record(i, inventory, film, producer, keyed).fold(e => fail(e.message), identity)
  private lazy val narrative = record(Interpretation.Narrative)
  private lazy val referent = record(Interpretation.Referent)
  private lazy val unitRows = inventory.units.zip(outcomes).map { (u, o) =>
    AgentBundle.unitRow(u.ordinal, u.id.value, o, p => Checksum.ofText(p).hex)
  }
  private lazy val bundle =
    val (scenes, rows) = AgentBundleSource.dictionary(atlas, film)
    AgentBundle.encode(
      AgentBundle.Provenance(
        "P00",
        producer.provider,
        producer.model,
        producer.systemSha256.hex,
        producer.outlineSha256.hex,
        Checksum.ofBytes(table).hex,
        Checksum.ofBytes(recallCsv).hex,
        3
      ),
      narrative,
      referent,
      unitRows,
      scenes,
      rows
    )

  private def chosen(r: MappingResult, i: Int) = r.outcomes(i).decision.flatMap(_.chosen)
  private def scene(n: Int) = Some(Destination.Target(film.scenes(n)))

  test("narrative and referent are separate records and can differ for one unit"):
    assertEquals(chosen(narrative, 0), scene(1))
    assertEquals(chosen(referent, 0), scene(2))
    assertNotEquals(narrative.digest, referent.digest)
    assertEquals(
      narrative.policies.decision,
      DecisionPolicy.Declared(DecisionPolicyId.unsafe("agent-localization/v2:narrative"))
    )
    assertEquals(
      referent.policies.decision,
      DecisionPolicy.Declared(DecisionPolicyId.unsafe("agent-localization/v2:referent"))
    )

  test("an explicit null is a no-assignment; an absent or invalid field is invalid output"):
    assertEquals(
      narrative.outcomes(1).decision.map(_.origin),
      Some(DecisionOrigin.Abstention("model reported no narrative assignment"))
    )
    assertEquals(
      referent.outcomes(1).processing,
      ProcessingStatus.Failed(ProcessingFailure.InvalidOutput("reply item has no referent field"))
    )
    assertEquals(
      narrative.outcomes(4).processing,
      ProcessingStatus.Failed(ProcessingFailure.InvalidOutput("narrative is not a scene in range"))
    )
    assertEquals(
      referent.outcomes(4).decision.map(_.origin),
      Some(DecisionOrigin.Abstention("model reported no specific referent"))
    )

  test("an omitted unit and an unparsable chunk are different processing failures"):
    assertEquals(
      narrative.outcomes(2).processing,
      ProcessingStatus.Failed(
        ProcessingFailure.ProviderFailure("unit omitted from the accepted reply (chunk 0, 1 attempts)")
      )
    )
    assertEquals(
      narrative.outcomes(3).processing,
      ProcessingStatus.Failed(
        ProcessingFailure.InvalidOutput("no parsable reply for chunk 1 after 3 attempts")
      )
    )

  test("every inventory unit has an outcome, and no decision carries a number"):
    Vector(narrative, referent).foreach { r =>
      assertEquals(r.outcomes.map(_.unit), inventory.units.map(_.id))
      assert(r.outcomes.flatMap(_.decision).forall(_.numerical.isEmpty))
      assert(r.outcomes.forall(_.measures.destinations.isEmpty))
    }
    val version = parse(MappingCodecs.encode(narrative)).toOption
      .flatMap(_.hcursor.get[String]("schemaVersion").toOption)
    assertEquals(version, Some(MappingCodecs.CategoricalSchemaVersion))

  test("outcomes that skip an inventory unit refuse"):
    assertEquals(
      AgentMappingRecord
        .record(Interpretation.Narrative, inventory, film, producer, keyed.drop(1))
        .left
        .toOption,
      Some(MappingRefusal.OutcomeInventoryMismatch)
    )

  test("the bundle reloads to the same records and the same unit rows"):
    val text = bundle.spaces2
    val loaded = parse(text).left
      .map(_.message)
      .flatMap(AgentBundle.decode(_, inventory, film))
      .fold(e => fail(e), identity)
    assertEquals(loaded.narrative.digest, narrative.digest)
    assertEquals(loaded.referent.digest, referent.digest)
    assertEquals(loaded.units, unitRows)
    assertEquals(loaded.units(0).confidence, Field.Value(0.8))
    assertEquals(loaded.units(1).confidence, Field.Absent)
    assertEquals(loaded.units(2).status, "omitted")
    assertEquals(loaded.units(3).status, "unparsable")

  test("a bundle whose record was edited does not reload"):
    val edited = parse(
      bundle.noSpaces.replace(film.scenes(1).key, film.scenes(2).key)
    ).toOption.get
    assert(AgentBundle.decode(edited, inventory, film).isLeft)

  test("a cited row resolves through the source dictionary, outside the narrative scene"):
    val rows = bundle.hcursor.downField("source").get[Vector[Json]]("rows").toOption.get
    val five = rows.find(_.hcursor.get[Int]("row").contains(5)).get.hcursor
    assertEquals(five.get[Int]("scene").toOption, Some(2))
    assertEquals(five.get[String]("start").toOption, Some("0:00"))
    assertEquals(unitRows(0).narrative, Field.Value(1))
    assertEquals(rows.exists(_.hcursor.get[Int]("row").contains(999)), false)

  test("the committed synthetic bundle is what the writer produces"):
    val actual = bundle.spaces2 + "\n"
    Files.createDirectories(Path.of("target")): Unit
    Files.writeString(Path.of("target/synthetic.bundle.json"), actual): Unit
    val stream = getClass.getResourceAsStream("/agentmapping/synthetic.bundle.json")
    assert(stream != null, "copy target/synthetic.bundle.json into the test resources")
    val committed =
      try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    assertEquals(actual, committed)
