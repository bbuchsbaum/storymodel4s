package storymodel4s.codec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Explicit synthetic evidence export; no ordinary test writes files. */
object RecallTimingArtifacts:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "one output directory is required")
    val directory = Path.of(args(0))
    val _ = Files.createDirectories(directory)
    Vector("onsets" -> RecallTimingFixture.build(), "exact" -> RecallTimingFixture.exact).foreach {
      (name, value) =>
        val _ = Files.writeString(
          directory.resolve(name + ".json"),
          RecallTimingCodecs.encode(value),
          StandardCharsets.UTF_8
        )
    }
