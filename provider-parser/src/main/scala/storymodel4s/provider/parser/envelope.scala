package storymodel4s.provider.parser

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import storymodel4s.core.*

private[parser] final case class WireToken(
    id: SurfaceUnitId,
    start: Int,
    endExclusive: Int,
    text: String
)

private[parser] final case class WireMarkerAlignment(
    ordinal: Int,
    providerNodeId: String,
    tokenIndices: Vector[Int]
)

private[parser] enum WireItemResult:
  case Proposed(
      penman: String,
      alignmentSchema: String,
      alignmentDialect: String,
      alignments: Vector[WireMarkerAlignment]
  )
  case Failed(code: ProviderFailureCode)
  case Abstained(code: ProviderFailureCode)

private[parser] final case class WireItem(
    id: ParserRequestId,
    modelInput: String,
    tokens: Vector[WireToken],
    result: WireItemResult
)

private[parser] final case class WireDiagnostics(
    durationMillis: Long,
    exitCode: Option[Int],
    stderrChecksum: Option[Checksum],
    stderrBytes: Long
)

private[parser] final case class WireResponse(
    schema: String,
    runtimeFingerprint: Fingerprint,
    configChecksum: Checksum,
    items: Vector[WireItem],
    diagnostics: WireDiagnostics
)

/** Canonical JSON boundary shared by fake and later subprocess transports. */
object ParserEnvelope:
  val RequestSchema: String = "storymodel4s.parser.request/v1"
  val ResultSchema: String = "storymodel4s.parser.result/v2"
  val MarkerSidecarSchema: String = "storymodel4s.parser.marker-sidecar/v1"

  /** Encode a deterministic request whose bytes bind the atlas axis, runtime, and config. */
  def encodeRequest(batch: ParserBatch, runtime: RuntimeIdentity, config: ParserConfig): String =
    val params = config.params.toVector.sortBy(_._1).map { (name, value) =>
      Json.obj("name" -> name.asJson, "value" -> value.asJson)
    }
    Json
      .obj(
        "schema" -> RequestSchema.asJson,
        "runtimeFingerprint" -> runtime.fingerprint.value.asJson,
        "configChecksum" -> config.checksum.hex.asJson,
        "params" -> params.asJson,
        "seed" -> config.seed.asJson,
        "items" -> batch.inputs.map(encodeInput).asJson
      )
      .noSpaces

  /** Decode untrusted response JSON; diagnostics and identifiers are checked on the way in. */
  private[parser] def decodeResponse(raw: String): Either[String, WireResponse] =
    parse(raw).left.map(_.message).flatMap(_.as[WireResponse].left.map(_.message))

  private def encodeInput(input: ParserSentenceInput): Json =
    Json.obj(
      "id" -> input.id.value.asJson,
      "sentenceId" -> input.sentenceId.value.asJson,
      "sentenceStart" -> input.sentenceSpan.start.asJson,
      "sentenceEnd" -> input.sentenceSpan.endExclusive.asJson,
      "text" -> input.text.asJson,
      "textChecksum" -> input.textChecksum.hex.asJson,
      "tokens" -> input.tokens.map { token =>
        Json.obj(
          "id" -> token.id.value.asJson,
          "start" -> token.span.start.asJson,
          "end" -> token.span.endExclusive.asJson,
          "text" -> token.text.asJson
        )
      }.asJson
    )

  private def field[A: Decoder](cursor: HCursor, name: String): Decoder.Result[A] =
    cursor.downField(name).as[A]

  private given Decoder[Checksum] =
    Decoder.decodeString.emap(value => Checksum.from(value).left.map(_.message))

  private given Decoder[SurfaceUnitId] =
    Decoder.decodeString.emap(value => SurfaceUnitId.from(value).left.map(_.message))

  private given Decoder[ParserRequestId] =
    Decoder.decodeString.emap(value => ParserRequestId.from(value).left.map(_.message))

  private given Decoder[Fingerprint] =
    Decoder.decodeString.emap(value => Fingerprint.from(value).left.map(_.message))

  private given Decoder[ProviderFailureCode] =
    Decoder.decodeString.emap(ProviderFailureCode.from)

  private given Decoder[WireToken] = Decoder.instance { cursor =>
    for
      id <- field[SurfaceUnitId](cursor, "id")
      start <- field[Int](cursor, "start")
      end <- field[Int](cursor, "end")
      text <- field[String](cursor, "text")
      _ <- TextSpan
        .of(start, end)
        .left
        .map(error => DecodingFailure(error.message, cursor.history))
    yield WireToken(id, start, end, text)
  }

  private given Decoder[WireMarkerAlignment] = Decoder.instance { cursor =>
    for
      ordinal <- field[Int](cursor, "ordinal")
      providerNodeId <- field[String](cursor, "providerNodeId")
      tokenIndices <- field[Vector[Int]](cursor, "tokenIndices")
    yield WireMarkerAlignment(ordinal, providerNodeId, tokenIndices)
  }

  private given Decoder[WireItemResult] = Decoder.instance { cursor =>
    field[String](cursor, "status").flatMap {
      case "proposed" =>
        for
          penman <- field[String](cursor, "penman")
          schema <- field[String](cursor, "alignmentSchema")
          dialect <- field[String](cursor, "alignmentDialect")
          alignments <- field[Vector[WireMarkerAlignment]](cursor, "alignments")
        yield WireItemResult.Proposed(penman, schema, dialect, alignments)
      case "failed"    => field[ProviderFailureCode](cursor, "code").map(WireItemResult.Failed(_))
      case "abstained" =>
        field[ProviderFailureCode](cursor, "code").map(WireItemResult.Abstained(_))
      case other => Left(DecodingFailure(s"unknown parser item status: $other", cursor.history))
    }
  }

  private given Decoder[WireItem] = Decoder.instance { cursor =>
    for
      id <- field[ParserRequestId](cursor, "id")
      modelInput <- field[String](cursor, "modelInput")
      tokens <- field[Vector[WireToken]](cursor, "tokens")
      result <- field[WireItemResult](cursor, "result")
    yield WireItem(id, modelInput, tokens, result)
  }

  private given Decoder[WireDiagnostics] = Decoder.instance { cursor =>
    for
      duration <- field[Long](cursor, "durationMillis")
      exit <- field[Option[Int]](cursor, "exitCode")
      stderr <- field[Option[Checksum]](cursor, "stderrChecksum")
      bytes <- field[Long](cursor, "stderrBytes")
      _ <-
        if duration >= 0L && bytes >= 0L then Right(())
        else Left(DecodingFailure("negative parser diagnostics", cursor.history))
    yield WireDiagnostics(duration, exit, stderr, bytes)
  }

  private given Decoder[WireResponse] = Decoder.instance { cursor =>
    for
      schema <- field[String](cursor, "schema")
      runtime <- field[Fingerprint](cursor, "runtimeFingerprint")
      config <- field[Checksum](cursor, "configChecksum")
      items <- field[Vector[WireItem]](cursor, "items")
      diagnostics <- field[WireDiagnostics](cursor, "diagnostics")
    yield WireResponse(schema, runtime, config, items, diagnostics)
  }

  private given Encoder[Checksum] = Encoder.encodeString.contramap(_.hex)
  private given Encoder[SurfaceUnitId] = Encoder.encodeString.contramap(_.value)
  private given Encoder[ParserRequestId] = Encoder.encodeString.contramap(_.value)
  private given Encoder[Fingerprint] = Encoder.encodeString.contramap(_.value)
  private given Encoder[ProviderFailureCode] = Encoder.encodeString.contramap(_.value)

  private given Encoder[WireToken] = Encoder.instance { token =>
    Json.obj(
      "id" -> token.id.asJson,
      "start" -> token.start.asJson,
      "end" -> token.endExclusive.asJson,
      "text" -> token.text.asJson
    )
  }

  private given Encoder[WireMarkerAlignment] = Encoder.instance { alignment =>
    Json.obj(
      "ordinal" -> alignment.ordinal.asJson,
      "providerNodeId" -> alignment.providerNodeId.asJson,
      "tokenIndices" -> alignment.tokenIndices.asJson
    )
  }

  private given Encoder[WireItemResult] = Encoder.instance {
    case WireItemResult.Proposed(penman, schema, dialect, alignments) =>
      Json.obj(
        "status" -> "proposed".asJson,
        "penman" -> penman.asJson,
        "alignmentDialect" -> dialect.asJson,
        "alignmentSchema" -> schema.asJson,
        "alignments" -> alignments.asJson
      )
    case WireItemResult.Failed(code) =>
      Json.obj("status" -> "failed".asJson, "code" -> code.asJson)
    case WireItemResult.Abstained(code) =>
      Json.obj("status" -> "abstained".asJson, "code" -> code.asJson)
  }

  private given Encoder[WireItem] = Encoder.instance { item =>
    Json.obj(
      "id" -> item.id.asJson,
      "modelInput" -> item.modelInput.asJson,
      "tokens" -> item.tokens.asJson,
      "result" -> item.result.asJson
    )
  }

  private given Encoder[WireDiagnostics] = Encoder.instance { diagnostics =>
    Json.obj(
      "durationMillis" -> diagnostics.durationMillis.asJson,
      "exitCode" -> diagnostics.exitCode.asJson,
      "stderrChecksum" -> diagnostics.stderrChecksum.asJson,
      "stderrBytes" -> diagnostics.stderrBytes.asJson
    )
  }

  private given Encoder[WireResponse] = Encoder.instance { response =>
    Json.obj(
      "schema" -> response.schema.asJson,
      "runtimeFingerprint" -> response.runtimeFingerprint.asJson,
      "configChecksum" -> response.configChecksum.asJson,
      "items" -> response.items.asJson,
      "diagnostics" -> response.diagnostics.asJson
    )
  }

  /** Test/support encoder for a typed fake response; real adapters write this schema externally. */
  private[parser] def encodeResponse(response: WireResponse): String = response.asJson.noSpaces
