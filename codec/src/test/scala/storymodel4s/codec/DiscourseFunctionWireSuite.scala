package storymodel4s.codec

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import storymodel4s.recall.DiscourseFunction

/** The wire names of discourse functions. Adding `NotAssessed` must not rename any other. */
class DiscourseFunctionWireSuite extends FunSuite:
  import RecallCodecs.given

  private val names = Vector(
    "EpisodicAssertion",
    "Summary",
    "Inference",
    "Association",
    "Evaluation",
    "SourceMonitoring",
    "TaskCommentary",
    "Uninterpretable",
    "NotAssessed"
  )

  test("every function has its own stable wire name"):
    assertEquals(DiscourseFunction.values.toVector.map(_.asJson), names.map(Json.fromString))

  test("each wire name reads back as its function, and an unknown name is refused"):
    names.zip(DiscourseFunction.values).foreach { (name, f) =>
      assertEquals(Json.fromString(name).as[DiscourseFunction], Right(f))
    }
    assert(Json.fromString("Unassessed").as[DiscourseFunction].isLeft)
