package docsprobe

import storymodel4s.codec.StoryModelCodec
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.story.{StoryValidator, ValidationPolicy}

/** Public-API-only canonical JSON round trip with one rejected mutation. */
@main def saveModel(): Unit =
  val model = WarOfTheGhostsModel.model
  val json = StoryModelCodec.encode(model)
  val checksum = StoryModelCodec.contentChecksum(model)
  val decoded = StoryModelCodec
    .decode(json)
    .fold(error => throw new IllegalStateException(error.message), identity)
  val revalidated = StoryValidator.validate(decoded, ValidationPolicy.default).validated
  val fixedPoint = StoryModelCodec.decode(json).map(StoryModelCodec.encode) == Right(json)
  val sameChecksum = revalidated.exists(StoryModelCodec.contentChecksum(_) == checksum)

  println(s"canonical JSON bytes: ${json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length}")
  println(s"content checksum: ${checksum.hex}")
  println(s"decoded draft revalidates: ${revalidated.isDefined}")
  println(s"canonical JSON is a fixed point: $fixedPoint")
  println(s"round-trip checksum matches: $sameChecksum")

  val unsupported = json.replace(
    "\"schemaVersion\":\"0.1.0\"",
    "\"schemaVersion\":\"99.0.0\""
  )
  val rejected = StoryModelCodec.decode(unsupported).left.toOption
  println(s"unsupported schema rejected: ${rejected.isDefined}")
  println(s"decode error: ${rejected.map(_.message).getOrElse("none")}")
