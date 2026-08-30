package storymodel4s.embed.onnx

import java.nio.file.{Path, Paths}

import scala.io.Source

import munit.FunSuite

import storymodel4s.core.Checksum
import storymodel4s.embed.*

/** Offline execution evidence for the native tokenizer/runtime boundary. */
class OnnxSentenceEmbedderSuite extends FunSuite:
  private final case class Golden(id: String, tokens: Vector[Long], vector: Vector[Double])

  private val modelChecksum =
    Checksum.unsafe("3e21121e42719ab61e888e8dbb9559591278701bec9193729bd2d1de38daca02")
  private val tokenizerChecksum =
    Checksum.unsafe("0237b6bbc55d00142f9fa04557641e8ce5ea3abaf89e957aac5bca7dbd3bbf9a")

  private def resource(name: String): Path =
    Paths.get(getClass.getResource(s"/fixture/$name").toURI)

  private def fixture(maxTokens: Int): OnnxSentenceModel =
    OnnxSentenceModel.testFixture(modelChecksum, tokenizerChecksum, dimension = 4, maxTokens)

  private def open(
      maxTokens: Int = 4,
      keys: SensitiveKeyProvider = SensitiveKeyProvider.none
  ): OnnxSentenceEmbedder =
    OnnxSentenceEmbedder
      .open(
        fixture(maxTokens),
        OnnxSentenceArtifacts(resource("model.onnx"), resource("tokenizer.json")),
        keys
      )
      .fold(error => fail(error.message), identity)

  private def batch(
      embedder: OnnxSentenceEmbedder,
      texts: Vector[(String, String, Sensitivity)]
  ): EmbedBatch =
    val query = embedder.spaces.find(_.role == Role.Query).getOrElse(fail("no query space"))
    EmbedBatch
      .validated(
        texts.map { case (id, text, sensitivity) =>
          EmbedRequest(
            RequestId.unsafe(id),
            EmbedPayload.Raw(text, sensitivity),
            query.id
          )
        },
        embedder.spaceIds
      )
      .fold(error => fail(error.message), identity)

  private def vector(outcome: EmbedOutcome): Vector[Double] =
    outcome.value
      .fold(failure => fail(failure.render), identity)
      .toOption
      .fold(fail("expected an observed vector"))(_.values)

  test("native startup requires process-level telemetry disablement") {
    assertEquals(sys.env.get("ORT_DISABLE_TELEMETRY"), Some("1"))
    assertEquals(
      OnnxSentenceEmbedder.validateEnvironment(Map.empty),
      Left(OnnxEmbedderError.TelemetryNotDisabled)
    )
    assertEquals(
      OnnxSentenceEmbedder.validateEnvironment(Map("ORT_DISABLE_TELEMETRY" -> "true")),
      Left(OnnxEmbedderError.TelemetryNotDisabled)
    )
    assertEquals(
      OnnxSentenceEmbedder.validateEnvironment(Map("ORT_DISABLE_TELEMETRY" -> "1")),
      Right(())
    )
  }

  private def goldens: Vector[Golden] =
    val source = Source.fromResource("golden/all-minilm-l6-v2.tsv")
    try
      source
        .getLines()
        .filterNot(_.startsWith("#"))
        .map { line =>
          line.split("\t", -1).toVector match
            case Vector(id, tokens, values) =>
              Golden(
                id,
                tokens.split(",").toVector.map(_.toLong),
                values.split(",").toVector.map(_.toDouble)
              )
            case _ => fail("malformed MiniLM differential golden")
        }
        .toVector
    finally source.close()

  test("a padded batch uses attention-mask mean pooling and returns L2 vectors") {
    val embedder = open()
    try
      val result = embedder.embed(
        batch(
          embedder,
          Vector(
            ("hello-world", "hello world", Sensitivity.Public),
            ("ghost", "ghost", Sensitivity.Public)
          )
        )
      )
      assertEquals(result.outcomes.map(_.id.value), Vector("hello-world", "ghost"))
      assertEqualsDouble(vector(result.outcomes(0))(0), 0.5, 1e-7)
      assertEqualsDouble(vector(result.outcomes(0))(1), 0.5, 1e-7)
      assertEqualsDouble(vector(result.outcomes(0))(2), 0.5, 1e-7)
      assertEqualsDouble(vector(result.outcomes(0))(3), 0.5, 1e-7)
      val rootHalf = math.sqrt(0.5)
      assertEqualsDouble(vector(result.outcomes(1))(0), rootHalf, 1e-7)
      assertEqualsDouble(vector(result.outcomes(1))(1), rootHalf, 1e-7)
      assertEqualsDouble(vector(result.outcomes(1))(2), 0.0, 1e-7)
      assertEqualsDouble(vector(result.outcomes(1))(3), 0.0, 1e-7)
      assertEquals(result.receipt.providerCalls.size, 1)
      val call = result.receipt.providerCalls.head
      assertEquals(call.params.get("semantic-kind"), Some("neural-encoder"))
      assertEquals(call.params.get("pooling"), Some("attention-mask-mean+l2"))
      assertEquals(call.version, embedder.info.provider.render)
      assertEquals(result.receipt.embeddingReceipts.head.items.size, 2)
    finally embedder.close()
  }

  test("reject truncation returns TooLong for one item without losing its valid sibling") {
    val embedder = open()
    try
      val result = embedder.embed(
        batch(
          embedder,
          Vector(
            ("long", "hello world ghost", Sensitivity.Public),
            ("valid", "warrior", Sensitivity.Public)
          )
        )
      )
      result.outcomes.head.value match
        case Left(ExecutionFailure.TooLong(tokens, max)) =>
          assertEquals(tokens, 5)
          assertEquals(max, 4)
        case other => fail(s"expected TooLong, got $other")
      assertEquals(vector(result.outcomes(1)).size, 4)
      assertEquals(result.receipt.providerCalls.size, 1)
    finally embedder.close()
  }

  test("a keyless sensitive batch fails before tokenization or ONNX execution") {
    val embedder = open()
    try
      val result = embedder.embed(
        batch(embedder, Vector(("sensitive", "hello", Sensitivity.Sensitive)))
      )
      result.outcomes.head.value match
        case Left(ExecutionFailure.PolicyDenied(_: PolicyDecision.KeyUnavailable)) => ()
        case other => fail(s"expected key-unavailable denial, got $other")
      assertEquals(result.receipt.providerCalls, Vector.empty)
      assertEquals(result.receipt.kind, DigestKind.Withheld)
    finally embedder.close()
  }

  test("artifact substitution is rejected before native loading") {
    val wrong = OnnxSentenceModel.testFixture(
      Checksum.ofText("not-the-model"),
      tokenizerChecksum,
      dimension = 4,
      maxTokens = 4
    )
    OnnxSentenceEmbedder.open(
      wrong,
      OnnxSentenceArtifacts(resource("model.onnx"), resource("tokenizer.json"))
    ) match
      case Left(OnnxEmbedderError.ArtifactChecksumMismatch(OnnxArtifactKind.Model, _, actual)) =>
        assertEquals(actual, modelChecksum)
      case other => fail(s"expected model checksum mismatch, got $other")
  }

  test("pinned MiniLM differential goldens are complete and match supplied real artifacts") {
    val expected = goldens
    assertEquals(expected.map(_.id), Vector("river", "negation", "war-cries"))
    assert(expected.forall(_.tokens.nonEmpty))
    assert(expected.forall(_.vector.size == 384))
    val phrases = Map(
      "river" -> "The warriors went down the river.",
      "negation" -> "He did not feel sick.",
      "war-cries" -> "They heard war cries behind them."
    )
    val supplied = (
      sys.env.get("STORYMODEL4S_ONNX_MODEL"),
      sys.env.get("STORYMODEL4S_ONNX_TOKENIZER")
    ) match
      case (None, None)               => None
      case (Some(model), Some(token)) => Some((Paths.get(model), Paths.get(token)))
      case _ => fail("supply both STORYMODEL4S_ONNX_MODEL and STORYMODEL4S_ONNX_TOKENIZER")
    supplied.foreach { case (modelPath, tokenizerPath) =>
      val embedder = OnnxSentenceEmbedder
        .open(
          OnnxSentenceModel.AllMiniLmL6V2,
          OnnxSentenceArtifacts(modelPath, tokenizerPath)
        )
        .fold(error => fail(error.message), identity)
      try
        expected.foreach { golden =>
          assertEquals(
            embedder.tokenIds(phrases(golden.id)).fold(code => fail(code), identity),
            golden.tokens,
            golden.id
          )
        }
        val result = embedder.embed(
          batch(
            embedder,
            expected.map(golden => (golden.id, phrases(golden.id), Sensitivity.Public))
          )
        )
        result.outcomes.zip(expected).foreach { case (outcome, golden) =>
          val actual = vector(outcome)
          actual.zip(golden.vector).zipWithIndex.foreach { case ((found, wanted), index) =>
            assertEqualsDouble(found, wanted, 2e-5, s"${golden.id} coordinate $index")
          }
        }
      finally embedder.close()
    }
  }
