package storymodel4s.probe

import io.circe.Json
import java.nio.file.Files
import munit.FunSuite
import storymodel4s.media.VideoAcquisition

class VideoAcquisitionSuite extends FunSuite:
  test("missing local runtime is actionable and publishes no partial package"):
    val root = Files.createTempDirectory("video-capability-")
    try
      val runtime = Json.obj(
        Vector(
          "ffmpeg",
          "ffprobe",
          "detectorPython",
          "detectorScript",
          "captionPython",
          "captionScript",
          "modelPath"
        ).map(n => n -> Json.fromString(s"missing-$n"))*
      )
      val job = Json.obj(
        "schemaVersion" -> Json.fromString(VideoAcquisition.Schema),
        "edition" -> Json.fromString("f0-job"),
        "runtime" -> runtime,
        "parts" -> Json.arr(),
        "model" -> Json.obj(),
        "captionRecipe" -> Json.obj(),
        "detectorRecipe" -> Json.obj(),
        "samplesPerShot" -> Json.fromInt(3),
        "maxDecodedBytes" -> Json.fromLong(1000000L),
        "timeoutSeconds" -> Json.fromInt(30)
      )
      val output = root.resolve("output")
      val result = VideoAcquisition.run(root, job.noSpaces, output)
      assert(result.left.toOption.exists(_.message.contains("configured ffmpeg is unavailable")))
      assert(!Files.exists(output))
      assert(
        VideoAcquisition
          .run(root, job.noSpaces.replaceFirst("\\{", "{\"parts\":[],"), output)
          .isLeft
      )
    finally
      val _ = Files.delete(root)
