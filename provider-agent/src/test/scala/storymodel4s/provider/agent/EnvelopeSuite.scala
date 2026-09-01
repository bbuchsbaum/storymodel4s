package storymodel4s.provider.agent

import cats.Id
import munit.FunSuite
import storymodel4s.provider.parser.*

class EnvelopeSuite extends FunSuite:
  import AgentFixtures.*

  private def committed(): Recordings =
    Recordings.at(committedRecordingsDir).fold(error => fail(error.message), identity)

  private def transport(): ClaudeParserTransport =
    ClaudeParserTransport(runtime, prompt, new ModelExchange.Recorded(committed()))

  test("the emitted result/v2 JSON decodes through the real court for a three-item batch") {
    val provider = JsonAmrCandidateProvider[Id](
      ParserRuntime.Remote(runtime),
      config,
      StarterLexiconHolder.lexicon,
      transport()
    )
    val result = provider.parse(batch)
    assertEquals(result.decisions, Vector.empty)
    assertEquals(result.misses, Vector.empty)
    assertEquals(result.covered, 3)

    val charts = result.attempts.map(_.result.toOption.get.evidence.chart)
    assertEquals(inputs.map(_.tokens.size), Vector(6, 10, 6))
    assertEquals(charts.map(_.concepts.size), Vector(4, 6, 4))
    assertEquals(charts.map(_.relations.size), Vector(4, 8, 3))
    assertEquals(charts.map(_.alignments.size), Vector(4, 6, 4))
    charts.zip(inputs).foreach { (chart, input) =>
      assertEquals(chart.sentence, Some(input.sentenceId))
    }

    val call = result.attempts.head.receipt.call.get
    assertEquals(call.params.get("duration-millis"), Some("7035"))
    assertEquals(call.params.get("stderr-bytes"), Some("0"))
    assertEquals(call.params.get("alignment-dialect"), Some("explicit-index-list/v1"))
    assertEquals(call.params.get("result-schema"), Some(ParserEnvelope.ResultSchema))
    assertEquals(call.params.get("runtime-fingerprint"), Some(runtime.fingerprint.value))
    assertEquals(call.params.get("max-tokens"), Some("4096"))
    result.attempts.foreach { attempt =>
      assert(
        attempt.receipt.decisions.exists {
          case ParserAttemptDecision.AlignmentSidecarAccepted(_) => true
          case _                                                 => false
        },
        s"${attempt.id.value} was admitted without an accepted sidecar"
      )
    }
  }

  test("the raw envelope names the result schema, the sidecar schema, and the dialect") {
    val requestJson = ParserEnvelope.encodeRequest(batch, runtime, config)
    val raw = transport()
      .exchange(requestJson, config.timeoutMillis)
      .fold(
        failure => fail(s"transport failed: ${failure.render}"),
        identity
      )
    assert(raw.contains("\"schema\":\"storymodel4s.parser.result/v2\""))
    assert(raw.contains("\"alignmentSchema\":\"storymodel4s.parser.marker-sidecar/v1\""))
    assert(raw.contains("\"alignmentDialect\":\"explicit-index-list/v1\""))
    assert(raw.contains(s"\"runtimeFingerprint\":\"${runtime.fingerprint.value}\""))
    assert(raw.contains(s"\"configChecksum\":\"${config.checksum.hex}\""))
    assert(raw.contains("\"modelInput\":\"There were people at Egulac .\""))
    assert(raw.contains("\"durationMillis\":7035"))
  }

  test("the transport refuses a request whose runtime fingerprint is not its own") {
    val foreign = RemoteRuntime
      .from(
        "anthropic",
        "claude-other",
        runtime.sdkVersion,
        prompt.ref,
        prompt.promptTextChecksum,
        ParserEnvelope.ResultSchema
      )
      .toOption
      .get
    val requestJson = ParserEnvelope.encodeRequest(batch, foreign, config)
    assertEquals(
      transport().exchange(requestJson, config.timeoutMillis),
      Left(TransportFailure.Io(AgentFailureCodes.RuntimeFingerprintMismatch))
    )
  }

  test("the transport refuses undecodable and wrong-schema requests") {
    assertEquals(
      transport().exchange("not json", config.timeoutMillis),
      Left(TransportFailure.Io(AgentFailureCodes.RequestUndecodable))
    )
    val wrongSchema = ParserEnvelope
      .encodeRequest(batch, runtime, config)
      .replace(ParserEnvelope.RequestSchema, "storymodel4s.parser.request/v0")
    assertEquals(
      transport().exchange(wrongSchema, config.timeoutMillis),
      Left(TransportFailure.Io(AgentFailureCodes.RequestSchemaMismatch))
    )
  }

  test("the transport refuses an item whose text checksum does not match its text") {
    val requestJson = ParserEnvelope
      .encodeRequest(batch, runtime, config)
      .replace("\"text\":\"They came down the river.\"", "\"text\":\"They came down the creek.\"")
    assertEquals(
      transport().exchange(requestJson, config.timeoutMillis),
      Left(TransportFailure.Io(AgentFailureCodes.TextChecksumMismatch))
    )
  }

private object StarterLexiconHolder:
  val lexicon: storymodel4s.amr.schema.FrameLexicon = storymodel4s.amr.schema.StarterLexicon.lexicon
