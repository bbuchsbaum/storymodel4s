package storymodel4s.provider.agent

import io.circe.Json
import java.nio.file.Files
import munit.FunSuite
import storymodel4s.core.Checksum

class TextExchangeSuite extends FunSuite:
  import HarnessCacheImport.Refusal

  private val P = TextExchange.SubscriptionCliProvider

  private def rec(fields: (String, Json)*): String =
    val base = Map(
      "model" -> Json.fromString("opus"),
      "systemSha" -> Json.fromString(Checksum.ofText("SYS").hex),
      "promptSha" -> Json.fromString(Checksum.ofText("PROMPT").hex),
      "seconds" -> Json.fromDoubleOrNull(12.3456),
      "usage" -> Json.obj(
        "input_tokens" -> Json.fromInt(3),
        "output_tokens" -> Json.fromInt(40),
        "cache_read_input_tokens" -> Json.fromInt(500)
      ),
      "modelUsage" -> Json.arr(Json.fromString("claude-opus-5-5")),
      "result" -> Json.fromString("{\"units\": []}")
    ) ++ fields
    Json.fromFields(base).noSpaces

  test("the key is derived from the texts, and every identity field moves it"):
    val k = TextExchange.key(P, "opus", "SYS", "PROMPT")
    assertEquals(
      k,
      TextExchange.keyOfHashes(P, "opus", Checksum.ofText("SYS"), Checksum.ofText("PROMPT"))
    )
    val others = Vector(
      TextExchange.key("other", "opus", "SYS", "PROMPT"),
      TextExchange.key(P, "sonnet", "SYS", "PROMPT"),
      TextExchange.key(P, "opus", "SYS2", "PROMPT"),
      TextExchange.key(P, "opus", "SYS", "PROMPT2")
    )
    assert(others.forall(_ != k))
    assertEquals(others.distinct.size, 4)

  test("a harness record imports as a captured reply with an unrecorded stop reason"):
    val (key, reply) = HarnessCacheImport.record("f.json", rec()).toOption.get
    assertEquals(key, TextExchange.key(P, "opus", "SYS", "PROMPT"))
    assertEquals(reply.model, "opus")
    assertEquals(reply.text, "{\"units\": []}")
    assertEquals(reply.stopReason, ModelStopReason.Other("unrecorded"))
    assertEquals(
      reply.evidence,
      ReplyEvidence.Captured(Some("claude-opus-5-5"), ModelUsage(3, 40, Some(500)), 12346L)
    )

  test("a record naming several reported models keeps none rather than choosing one"):
    val two = rec(
      "modelUsage" -> Json.arr(Json.fromString("claude-opus-5-5"), Json.fromString("claude-haiku"))
    )
    HarnessCacheImport.record("f.json", two).toOption.get._2.evidence match
      case ReplyEvidence.Captured(reported, _, _) => assertEquals(reported, None)
      case other                                  => fail(s"not captured: $other")

  test("a record without a prompt hash, with a malformed hash, or negative usage is refused"):
    val noPrompt = rec().replace("\"promptSha\"", "\"other\"")
    assertEquals(
      HarnessCacheImport.record("f.json", noPrompt),
      Left(Refusal.MissingField("f.json", "promptSha"))
    )
    assertEquals(
      HarnessCacheImport.record("f.json", rec("systemSha" -> Json.fromString("ABC"))),
      Left(Refusal.BadHash("f.json", "systemSha"))
    )
    val negative = rec(
      "usage" -> Json.obj("input_tokens" -> Json.fromInt(-1), "output_tokens" -> Json.fromInt(1))
    )
    assertEquals(
      HarnessCacheImport.record("f.json", negative),
      Left(Refusal.Negative("f.json", "usage.input_tokens"))
    )

  test("an imported cache replays only for the exact prompt it recorded"):
    val cache = Files.createTempDirectory("harness-cache")
    Files.writeString(cache.resolve("a.json"), rec()): Unit
    val recordings = Recordings.at(Files.createTempDirectory("rec").resolve("r")).toOption.get
    assertEquals(HarnessCacheImport.run(cache, recordings).map(_.records), Right(1))
    assertEquals(
      TextExchange.replay(recordings, P, "opus", "SYS", "PROMPT").map(_.text),
      Right("{\"units\": []}")
    )
    assert(
      TextExchange.replay(recordings, P, "opus", "SYS", "PROMPT ").left.exists {
        case ExchangeFailure.RecordingMissing(_) => true
        case _                                   => false
      }
    )

  test("a missing cache directory is refused, never imported as empty"):
    val recordings = Recordings.at(Files.createTempDirectory("rec").resolve("r")).toOption.get
    assertEquals(
      HarnessCacheImport.run(Files.createTempDirectory("x").resolve("nope"), recordings),
      Left(Refusal.CacheMissing)
    )
