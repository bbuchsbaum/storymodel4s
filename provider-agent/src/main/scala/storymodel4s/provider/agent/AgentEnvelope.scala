package storymodel4s.provider.agent

import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import storymodel4s.core.Checksum
import storymodel4s.provider.parser.{ParserAlignmentDialect, ParserEnvelope, ProviderFailureCode}

/** One token exactly as the request carried it; echoed back verbatim in the response. */
private[agent] final case class RequestToken(
    id: String,
    start: Int,
    endExclusive: Int,
    text: String
)

/** One sentence item of a parser request as decoded from the wire. */
private[agent] final case class RequestItem(
    id: String,
    sentenceId: String,
    sentenceStart: Int,
    sentenceEnd: Int,
    text: String,
    textChecksum: Checksum,
    tokens: Vector[RequestToken]
):
  /** The model-observed normalized input the court compares against the atlas tokens. */
  def modelInput: String = tokens.map(_.text).mkString(" ")

/** The `storymodel4s.parser.request/v1` envelope decoded with this module's own decoder. */
private[agent] final case class ParserRequest(
    schema: String,
    runtimeFingerprint: String,
    configChecksum: String,
    params: Vector[(String, String)],
    seed: Option[Long],
    items: Vector[RequestItem]
)

/** What the transport concluded about one item after interpreting the model's reply. */
private[agent] enum ItemOutcome:
  case Proposed(penman: String, rows: Vector[SidecarRow])
  case Failed(code: ProviderFailureCode)
  case Abstained(code: ProviderFailureCode)

/** One item's outcome paired with the request item whose tokens it echoes. */
private[agent] final case class ItemResponse(item: RequestItem, outcome: ItemOutcome)

/** This module's own reading of the request schema and writing of the result/v2 schema.
  *
  * Why re-emitted here: the provider-parser wire types are private to that module by design, so a
  * real adapter writes the schema externally and the admission court decodes it as untrusted.
  */
private[agent] object AgentEnvelope:
  private def field[A: Decoder](cursor: HCursor, name: String): Decoder.Result[A] =
    cursor.downField(name).as[A]

  private given Decoder[Checksum] =
    Decoder.decodeString.emap(value => Checksum.from(value).left.map(_.message))

  private given Decoder[RequestToken] = Decoder.instance { cursor =>
    for
      id <- field[String](cursor, "id")
      start <- field[Int](cursor, "start")
      end <- field[Int](cursor, "end")
      text <- field[String](cursor, "text")
      _ <-
        if start >= 0 && end > start then Right(())
        else Left(DecodingFailure("token span must be nonempty and nonnegative", cursor.history))
    yield RequestToken(id, start, end, text)
  }

  private given Decoder[RequestItem] = Decoder.instance { cursor =>
    for
      id <- field[String](cursor, "id")
      sentenceId <- field[String](cursor, "sentenceId")
      sentenceStart <- field[Int](cursor, "sentenceStart")
      sentenceEnd <- field[Int](cursor, "sentenceEnd")
      text <- field[String](cursor, "text")
      textChecksum <- field[Checksum](cursor, "textChecksum")
      tokens <- field[Vector[RequestToken]](cursor, "tokens")
    yield RequestItem(id, sentenceId, sentenceStart, sentenceEnd, text, textChecksum, tokens)
  }

  private given Decoder[(String, String)] = Decoder.instance { cursor =>
    for
      name <- field[String](cursor, "name")
      value <- field[String](cursor, "value")
    yield name -> value
  }

  private given Decoder[ParserRequest] = Decoder.instance { cursor =>
    for
      schema <- field[String](cursor, "schema")
      runtimeFingerprint <- field[String](cursor, "runtimeFingerprint")
      configChecksum <- field[String](cursor, "configChecksum")
      params <- field[Vector[(String, String)]](cursor, "params")
      seed <- field[Option[Long]](cursor, "seed")
      items <- field[Vector[RequestItem]](cursor, "items")
    yield ParserRequest(schema, runtimeFingerprint, configChecksum, params, seed, items)
  }

  def decodeRequest(raw: String): Either[String, ParserRequest] =
    parse(raw).left.map(_.message).flatMap(_.as[ParserRequest].left.map(_.message))

  private def token(token: RequestToken): Json = Json.obj(
    "id" -> token.id.asJson,
    "start" -> token.start.asJson,
    "end" -> token.endExclusive.asJson,
    "text" -> token.text.asJson
  )

  private def row(row: SidecarRow): Json = Json.obj(
    "ordinal" -> row.ordinal.asJson,
    "providerNodeId" -> row.providerNodeId.asJson,
    "tokenIndices" -> row.tokenIndices.asJson
  )

  private def outcome(outcome: ItemOutcome): Json = outcome match
    case ItemOutcome.Proposed(penman, rows) =>
      Json.obj(
        "status" -> "proposed".asJson,
        "penman" -> penman.asJson,
        "alignmentSchema" -> ParserEnvelope.MarkerSidecarSchema.asJson,
        "alignmentDialect" -> ParserAlignmentDialect.ExplicitIndexListV1.wireName.asJson,
        "alignments" -> rows.map(row).asJson
      )
    case ItemOutcome.Failed(code) =>
      Json.obj("status" -> "failed".asJson, "code" -> code.value.asJson)
    case ItemOutcome.Abstained(code) =>
      Json.obj("status" -> "abstained".asJson, "code" -> code.value.asJson)

  private def item(response: ItemResponse): Json = Json.obj(
    "id" -> response.item.id.asJson,
    "modelInput" -> response.item.modelInput.asJson,
    "tokens" -> response.item.tokens.map(token).asJson,
    "result" -> outcome(response.outcome)
  )

  /** Emit result/v2 echoing the request's runtime fingerprint and config checksum verbatim. */
  def encodeResponse(
      runtimeFingerprint: String,
      configChecksum: String,
      items: Vector[ItemResponse],
      durationMillis: Long
  ): String =
    Json
      .obj(
        "schema" -> ParserEnvelope.ResultSchema.asJson,
        "runtimeFingerprint" -> runtimeFingerprint.asJson,
        "configChecksum" -> configChecksum.asJson,
        "items" -> items.map(item).asJson,
        "diagnostics" -> Json.obj(
          "durationMillis" -> durationMillis.asJson,
          "exitCode" -> Json.Null,
          "stderrChecksum" -> Json.Null,
          "stderrBytes" -> 0L.asJson
        )
      )
      .noSpaces
