package storymodel4s.pipeline

import io.circe.Json
import io.circe.jawn.parse
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite

class AgentArmImportSuite extends FunSuite:
  import AgentArmImport.Refusal

  private def unit(id: String, ordinal: Int): Json =
    Json.obj(
      "id" -> Json.fromString(id),
      "ordinal" -> Json.fromInt(ordinal),
      "textSha256" -> Json.fromString("t" * 64),
      "onsetSeconds" -> Json.fromString(s"$ordinal.5")
    )

  private def manifest(dir: Path): Path =
    val doc = Json.obj(
      "schema" -> Json.fromString(AgentArmImport.ManifestSchema),
      "sourceInputSha256" -> Json.fromString("s" * 64),
      "participants" -> Json.arr(
        Json.obj(
          "participant" -> Json.fromString("NN03_x"),
          "partition" -> Json.fromString("development"),
          "units" -> Json.arr(unit("a0", 0), unit("a1", 1), unit("a2", 2), unit("a3", 3))
        ),
        Json.obj(
          "participant" -> Json.fromString("NN04_x"),
          "partition" -> Json.fromString("development"),
          "units" -> Json.arr(unit("b0", 0))
        ),
        Json.obj(
          "participant" -> Json.fromString("NN09_x"),
          "partition" -> Json.fromString("untouchedTest"),
          "units" -> Json.arr(unit("c0", 0))
        )
      )
    )
    write(dir.resolve("manifest.json"), doc)

  private def write(path: Path, j: Json): Path =
    Files.write(path, j.noSpaces.getBytes(StandardCharsets.UTF_8))

  private def reply(index: Int, id: String, narrative: Json): Json =
    Json.obj(
      "index" -> Json.fromInt(index),
      "id" -> Json.fromString(id),
      "narrative" -> narrative
    )

  private def replies(dir: Path, name: String, units: Json*): Unit =
    write(dir.resolve(s"$name.json"), Json.obj("units" -> Json.arr(units*))): Unit

  private def fixture(): (Path, Path, Path) =
    val dir = Files.createTempDirectory("agent-arm")
    val m = manifest(dir)
    val r = Files.createDirectory(dir.resolve("replies"))
    replies(
      r,
      "NN03_x",
      reply(0, "a0", Json.fromInt(30)),
      reply(1, "a1", Json.Null),
      reply(2, "a2", Json.fromInt(51))
    )
    (m, r, dir.resolve("arm.json"))

  private def outcomes(arm: Path): Map[String, Json] =
    val j = parse(Files.readString(arm)).toOption.get
    j.hcursor
      .get[Vector[Json]]("participants")
      .toOption
      .get
      .flatMap(_.hcursor.get[Vector[Json]]("units").toOption.get)
      .map(u =>
        u.hcursor.get[String]("id").toOption.get -> u.hcursor.downField("outcome").focus.get
      )
      .toMap

  private def kind(o: Json): String = o.hcursor.get[String]("kind").toOption.get

  test("every manifest unit receives an outcome by the frozen grammar"):
    val (m, r, out) = fixture()
    val w = AgentArmImport.run(m, r, "narrative", "development", out).toOption.get
    val o = outcomes(out)
    assertEquals(o.keySet, Set("a0", "a1", "a2", "a3", "b0"))
    assertEquals(o("a0").hcursor.get[Int]("scene").toOption, Some(30))
    assertEquals(kind(o("a0")), "label")
    assertEquals(kind(o("a1")), "abstention")
    assertEquals(kind(o("a2")), "invalid")
    assertEquals(kind(o("a3")), "provider-failure")
    assertEquals(kind(o("b0")), "provider-failure")
    assertEquals((w.labels, w.abstentions, w.invalid, w.providerFailures), (1, 1, 1, 2))

  test("the partition selects participants; untouched units stay out of a development arm"):
    val (m, r, out) = fixture()
    AgentArmImport.run(m, r, "narrative", "development", out).toOption.get: Unit
    assert(!outcomes(out).contains("c0"))

  test("the arm binds the manifest hash and source identity"):
    val (m, r, out) = fixture()
    AgentArmImport.run(m, r, "narrative", "development", out).toOption.get: Unit
    val j = parse(Files.readString(out)).toOption.get
    assertEquals(
      j.hcursor.get[String]("unitManifestSha256").toOption,
      Some(storymodel4s.core.Checksum.ofBytes(Files.readAllBytes(m)).hex)
    )
    assertEquals(j.hcursor.get[String]("schema").toOption, Some(AgentArmImport.ArmSchema))

  test("a reply for a unit not in the manifest is refused"):
    val (m, r, out) = fixture()
    replies(r, "NN04_x", reply(0, "b0", Json.fromInt(2)), reply(1, "zz", Json.fromInt(3)))
    assertEquals(
      AgentArmImport.run(m, r, "narrative", "development", out),
      Left(Refusal.UnknownUnit("NN04_x", "zz"))
    )

  test("a reply whose index disagrees with the manifest ordinal is refused"):
    val (m, r, out) = fixture()
    replies(r, "NN04_x", reply(5, "b0", Json.fromInt(2)))
    assertEquals(
      AgentArmImport.run(m, r, "narrative", "development", out),
      Left(Refusal.OrdinalMismatch("NN04_x", "b0"))
    )

  test("duplicate replies for one unit are refused"):
    val (m, r, out) = fixture()
    replies(r, "NN04_x", reply(0, "b0", Json.fromInt(2)), reply(0, "b0", Json.fromInt(3)))
    assertEquals(
      AgentArmImport.run(m, r, "narrative", "development", out),
      Left(Refusal.DuplicateUnit("NN04_x", "b0"))
    )

  test("an existing output is never overwritten"):
    val (m, r, out) = fixture()
    Files.writeString(out, "keep"): Unit
    assertEquals(
      AgentArmImport.run(m, r, "narrative", "development", out),
      Left(Refusal.OutputExists)
    )
    assertEquals(Files.readString(out), "keep")

  test("a manifest of another schema is refused"):
    val (m, r, out) = fixture()
    write(m, Json.obj("schema" -> Json.fromString("other"))): Unit
    assertEquals(
      AgentArmImport.run(m, r, "narrative", "development", out),
      Left(Refusal.InvalidManifest)
    )
