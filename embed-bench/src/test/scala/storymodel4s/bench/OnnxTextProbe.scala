package storymodel4s.bench

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.security.SecureRandom

import io.circe.Json
import io.circe.parser.parse
import storymodel4s.core.Checksum
import storymodel4s.embed.*
import storymodel4s.embed.onnx.*

/** Export a bounded local diagnostic's actual pinned-encoder attempts, retaining refusals. */
object OnnxTextProbe:
  def main(args: Array[String]): Unit =
    require(args.length == 5, "model tokenizer input expected-input-sha256 output")
    val input = Files.readAllBytes(Paths.get(args(2)))
    require(Checksum.ofBytes(input).hex == args(3), "frozen input changed")
    val output = Paths.get(args(4))
    require(!Files.exists(output), "output already exists")
    val rows = parse(new String(input, UTF_8))
      .flatMap(_.hcursor.get[Vector[Json]]("records"))
      .fold(throw _, identity)
    val key = new Array[Byte](32)
    new SecureRandom().nextBytes(key)
    val encoder = OnnxSentenceEmbedder
      .open(
        OnnxSentenceModel.AllMiniLmL6V2,
        OnnxSentenceArtifacts(Paths.get(args(0)), Paths.get(args(1))),
        SensitiveKeyProvider.static(KeyId.unsafe("local-event-fusion-probe"), key)
      )
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    try
      val requests = rows.map { row =>
        val c = row.hcursor
        val id = c.get[String]("id").fold(throw _, identity)
        val role = c.get[String]("role").fold(throw _, identity) match
          case "query"    => Role.Query
          case "document" => Role.Document
          case _          => throw new IllegalArgumentException("unknown embedding role")
        val text = c.get[String]("text").fold(throw _, identity)
        require(text.trim.nonEmpty, "empty embedding text")
        val space = encoder.spaces.find(_.role == role).get
        EmbedRequest(RequestId.unsafe(id), EmbedPayload.Raw(text, Sensitivity.Sensitive), space.id)
      }
      require(requests.nonEmpty, "empty diagnostic")
      require(requests.map(_.id).distinct.size == requests.size, "duplicate request ids")
      var calls = 0
      val results = requests
        .grouped(32)
        .flatMap { group =>
          val batch = EmbedBatch
            .validated(group, encoder.spaceIds)
            .fold(
              e => throw new IllegalArgumentException(e.message),
              identity
            )
          val record = encoder.record(batch)
          require(
            record.replay(batch, encoder.model, encoder.info.provider) == Right(record.result),
            "concrete attempt replay differs"
          )
          calls += record.result.receipt.providerCalls.size
          record.result.outcomes.map { outcome =>
            val (status, vector, tokens) = outcome.value match
              case Right(estimate) =>
                estimate.toOption match
                  case Some(value) =>
                    ("observed", Json.arr(value.values.map(Json.fromDoubleOrNull)*), Json.Null)
                  case None => ("missing", Json.Null, Json.Null)
              case Left(ExecutionFailure.TooLong(count, limit)) =>
                (
                  "too-long",
                  Json.Null,
                  Json.obj("count" -> Json.fromInt(count), "limit" -> Json.fromInt(limit))
                )
              case Left(_) => ("failed", Json.Null, Json.Null)
            Json.obj(
              "id" -> Json.fromString(outcome.id.value),
              "status" -> Json.fromString(status),
              "vector" -> vector,
              "tokens" -> tokens
            )
          }
        }
        .toVector
      val value = Json.obj(
        "scope" -> Json.fromString(
          "Local native embedding attempts; no mapping admission or accuracy claim."
        ),
        "inputSha256" -> Json.fromString(args(3)),
        "model" -> Json.fromString(encoder.model.coordinate),
        "runtime" -> Json.fromString(encoder.runtimeIdentity),
        "providerCalls" -> Json.fromInt(calls),
        "records" -> Json.arr(results*)
      )
      val _ = Files.writeString(output, value.noSpaces + "\n", UTF_8, StandardOpenOption.CREATE_NEW)
      println(s"Recorded ${results.size} requests in $calls local provider calls")
    finally encoder.close()
