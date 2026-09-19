package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import io.circe.Json
import munit.FunSuite

class SherlockGoldRuleSuite extends FunSuite:
  test("foreign participant names remain outside the admitted alias universe") {
    assertEquals(SherlockSceneCoding.participantOf("NN00_synthetic.csv"), None)
    assertEquals(SherlockSceneCoding.participantOf("NN18_synthetic.csv"), None)
    assertEquals(SherlockSceneCoding.participantOf("foreign"), None)
  }

  test("the shared rule preserves every preregistered alias and the TR duration") {
    assertEquals(SherlockSceneCoding.trSeconds, 1.5)
    val expected = Vector(
      None,
      Some(2),
      Some(3),
      Some(4),
      None,
      Some(5),
      Some(6),
      Some(7),
      Some(8),
      Some(9),
      Some(10),
      Some(11),
      Some(12),
      Some(13),
      Some(14),
      Some(15),
      Some(16)
    )
    assertEquals((1 to 17).map(SherlockSceneCoding.subjectOf).toVector, expected)
    Vector(-1, 0, 18, 99).foreach(n => assertEquals(SherlockSceneCoding.subjectOf(n), None))
    (1 to 17).foreach { n =>
      assertEquals(SherlockSceneCoding.participantOf(f"NN$n%02d_synthetic.csv"), Some(n))
    }
    assertEquals(SherlockSceneCoding.participantOf("NN03foreign"), None)
  }

/** Actual Scala observations for the Python differential witness, never a hand-written result. */
object SherlockGoldRuleWitness:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "output.json")
    val output = Paths.get(args(0))
    require(!Files.exists(output), "output already exists")
    val result = Json.obj(
      "ruleSha256" -> Json.fromString(SherlockGoldRule.checksum.hex),
      "trSeconds" -> Json.fromDoubleOrNull(SherlockSceneCoding.trSeconds),
      "participants" -> Json.fromValues((0 to 18).map { n =>
        Json.obj(
          "participant" -> Json.fromInt(n),
          "parsed" -> SherlockSceneCoding
            .participantOf(f"NN$n%02d_synthetic.csv")
            .fold(Json.Null)(Json.fromInt),
          "goldSubject" -> SherlockSceneCoding.subjectOf(n).fold(Json.Null)(Json.fromInt)
        )
      })
    )
    val _ = Files.write(output, result.noSpaces.getBytes(StandardCharsets.UTF_8))
