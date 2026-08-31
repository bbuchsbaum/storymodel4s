package storymodel4s.provider.parser

import io.circe.parser.parse
import munit.FunSuite

class EnvelopeSuite extends FunSuite:
  import TestFixtures.*

  test("request JSON is deterministic and carries the exact atlas token vector") {
    val reversedConfig = ParserConfig
      .from(config.params.toVector.reverse.toMap, config.seed, config.timeoutMillis)
      .toOption
      .get
    val first = ParserEnvelope.encodeRequest(batch, runtime, config)
    val second = ParserEnvelope.encodeRequest(batch, runtime, reversedConfig)
    assertEquals(first, second)

    val json = parse(first).toOption.get
    val cursor = json.hcursor
    assertEquals(cursor.get[String]("schema").toOption, Some(ParserEnvelope.RequestSchema))
    assertEquals(
      cursor.get[String]("runtimeFingerprint").toOption,
      Some(runtime.fingerprint.value)
    )
    val item = cursor.downField("items").downArray
    assertEquals(item.get[String]("id").toOption, Some(inputs.head.id.value))
    assertEquals(item.get[String]("text").toOption, Some(inputs.head.text))
    val tokenTexts = item.downField("tokens").as[Vector[io.circe.Json]].toOption.get.map { token =>
      token.hcursor.get[String]("text").toOption.get
    }
    assertEquals(tokenTexts, inputs.head.tokens.map(_.text))
  }

  test("response decoder refuses unknown status and blank provider failure code") {
    val unknownStatus = successResponse.replace("\"proposed\"", "\"invented\"")
    assert(ParserEnvelope.decodeResponse(unknownStatus).isLeft)

    val blankCode = response(
      Vector(
        WireItem(
          inputs.head.id,
          modelInput(inputs.head),
          wireTokens(inputs.head),
          WireItemResult.Failed(ProviderFailureCode.from("x").toOption.get)
        )
      )
    ).replace("\"code\":\"x\"", "\"code\":\" \"")
    assert(ParserEnvelope.decodeResponse(blankCode).isLeft)
  }

  test("response decoder refuses negative diagnostic sizes") {
    val negative = successResponse.replace("\"stderrBytes\":0", "\"stderrBytes\":-1")
    assert(ParserEnvelope.decodeResponse(negative).isLeft)
  }

  test("response v2 requires an explicit marker sidecar field") {
    val markerOnly = successResponse.replace("\"alignments\":", "\"discardedAlignments\":")
    assert(ParserEnvelope.decodeResponse(markerOnly).isLeft)
  }
