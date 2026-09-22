package storymodel4s.codec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import io.circe.Json
import storymodel4s.recall.RecallTiming

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
    val cases = Vector(
      "9007199254740993",
      "-9223372036854775808",
      "1.0000000000000000000",
      ".1",
      "1.",
      "1.e2",
      "-.25E+2",
      "+.0",
      "0e128",
      "-0e-128",
      "8.75",
      "-1.25e-2",
      "9223372036854775808",
      "0.0000000000000000001",
      "1e128",
      "NaN",
      "Infinity",
      "",
      ".",
      ".e2",
      "1/2",
      "--1",
      "1e",
      "1e129",
      "1e-129",
      "1" * 129,
      "0" * 513
    )
    val vectors = Json.fromValues(cases.map { text =>
      val result = RecallTiming.decimalSeconds(text) match
        case Left(reason) => Json.obj("refusal" -> Json.fromString(reason.toString))
        case Right(value) =>
          Json.obj(
            "numerator" -> Json.fromString(value.numerator.toString),
            "denominator" -> Json.fromString(value.denominator.toString)
          )
      Json.obj("text" -> Json.fromString(text), "result" -> result)
    })
    val _ = Files.writeString(
      directory.resolve("decimal-cases.json"),
      MappingJson.print(vectors),
      StandardCharsets.UTF_8
    )
