package storymodel4s.codec

import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import storymodel4s.laws.MappingMiniature as M

class MappingMiniatureDigestSuite extends munit.FunSuite:
  private def root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p =>
        Files.isRegularFile(p.resolve("tools/recall-study/fixtures/baseline-miniatures.json"))
      )
      .getOrElse(fail("fixture root unavailable"))

  test("transcription equals fixture") {
    val bytes =
      Files.readAllBytes(root.resolve("tools/recall-study/fixtures/baseline-miniatures.json"))
    val hash =
      MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
    assertEquals(hash, M.fixtureSha256)
    val original = io.circe.parser.parse(new String(bytes, StandardCharsets.UTF_8)).toOption.get
    val expected = Json.obj(
      "schema" -> M.schema.asJson,
      "meaning" -> M.meaning.asJson,
      "textSource" -> Json.obj(
        "id" -> M.textId.asJson,
        "join" -> M.textJoin.asJson,
        "events" -> M.events
          .map(e =>
            Json.obj("id" -> e.id.asJson, "order" -> e.order.asJson, "text" -> e.text.asJson)
          )
          .asJson
      ),
      "annotatedVideoSource" -> Json.obj(
        "id" -> M.videoId.asJson,
        "mediaBytes" -> M.mediaBytes.asJson,
        "parts" -> M.parts
          .map(p =>
            Json.obj(
              "part" -> p.id.asJson,
              "axis" -> p.axis.asJson,
              "ticksPerSecond" -> p.ticksPerSecond.asJson,
              "durationTicks" -> p.durationTicks.asJson
            )
          )
          .asJson,
        "loci" -> M.loci
          .map(l =>
            Json.obj(
              "event" -> l.event.asJson,
              "part" -> l.part.asJson,
              "startTick" -> l.startTick.asJson,
              "endTick" -> l.endTick.asJson
            )
          )
          .asJson,
        "partialGroup" -> Json.obj(
          "id" -> M.groupId.asJson,
          "members" -> M.groupMembers.asJson,
          "completePlaybackSupport" -> M.groupCompletePlaybackSupport.asJson
        )
      ),
      "recallPackets" -> M.packets
        .map(p =>
          Json.obj(
            "id" -> p.id.asJson,
            "text" -> p.text.asJson,
            "admissible" -> p.admissible.asJson,
            "onsetSeconds" -> p.onsetSeconds.asJson
          )
        )
        .asJson,
      "expectedCases" -> Json.obj(
        "clearPath" -> M.clearPath.asJson,
        "clearBackward" -> M.clearBackward.asJson,
        "clearForward" -> M.clearForward.asJson,
        "revisitPacket" -> M.revisitPacket.asJson,
        "externalPacket" -> M.externalPacket.asJson,
        "missingTimingPacket" -> M.missingTimingPacket.asJson,
        "processingFailureControl" -> Json.obj(
          "packet" -> M.failurePacket.asJson,
          "status" -> M.failureStatus.asJson,
          "selected" -> M.failureSelected.asJson
        ),
        "argmaxDecodeDisagreement" -> Json.obj(
          "packet" -> M.disagreementPacket.asJson,
          "argmax" -> M.argmax.asJson,
          "decoded" -> M.decoded.asJson,
          "meaning" -> M.disagreementMeaning.asJson
        ),
        "partialSupportTarget" -> M.partialSupportTarget.asJson,
        "ambiguousPacket" -> M.ambiguousPacket.asJson
      )
    )
    assertEquals(original.mapObject(_.remove("_admission")), expected)
  }
