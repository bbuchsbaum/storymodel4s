package storymodel4s.provider.agent

import java.nio.file.{Files, Path, Paths}
import storymodel4s.core.*
import storymodel4s.provider.parser.*

/** Three admitted War of the Ghosts sentences (docs/design/war-of-the-ghosts-boas1901.txt) and the
  * runtime, prompt package, and recordings every provider-agent suite shares.
  */
private[agent] object AgentFixtures:
  val text: String =
    "There were people at Egulac. One night two young men went to hunt seals. " +
      "They came down the river."

  val source: StorySource = StorySource
    .fromText(text, Some("war-of-the-ghosts-three"))
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  val atlas: SurfaceAtlas = SurfaceAnalyzer.analyze(source)

  val batch: ParserBatch = ClaudeParseDriver
    .inputs(atlas)
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  val inputs: Vector[ParserSentenceInput] = batch.inputs

  val prompt: AgentPromptPackage = AgentPromptPackage
    .load()
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  /** The backend every suite here uses unless it names another one. */
  val backend: ModelBackend = ClaudeParserTransport.DefaultBackend

  val runtime: RemoteRuntime = runtimeFor(backend)

  def runtimeFor(chosen: ModelBackend): RemoteRuntime = ClaudeParserTransport
    .runtimeFor(prompt, chosen)
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  val config: ParserConfig = ClaudeParseDriver
    .config(prompt)
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  /** The committed hand-written recordings; each file is named by its content key. */
  lazy val committedRecordingsDir: Path =
    Paths.get(getClass.getResource("/recordings").toURI)

  /** PENMAN the recordings carry, with markers computed from the atlas token lists. */
  val penman: Vector[String] = Vector(
    "(b / be-located-at-91~e.1 :ARG1 (p / person~e.2) " +
      ":ARG2 (c / city~e.4 :name (n / name~e.4 :op1 \"Egulac\")))",
    "(g / go-02~e.5 :ARG0 (m / man~e.4 :quant 2 :mod (y / young~e.3)) " +
      ":purpose (h / hunt-01~e.7 :ARG0 m :ARG1 (s / seal~e.8)) " +
      ":time (n / night~e.1 :quant 1))",
    "(c / come-01~e.1 :ARG1 (t / they~e.0) :direction (d / down~e.2) :path (r / river~e.4))"
  )

  def requestItem(input: ParserSentenceInput): RequestItem = RequestItem.fromInput(input)

  def keyFor(input: ParserSentenceInput, chosen: ModelBackend = backend): RecordingKey =
    ModelRequest
      .render(
        chosen,
        prompt,
        requestItem(input),
        ClaudeParseDriver.MaxTokens,
        ClaudeParseDriver.TimeoutMillis
      )
      .key

  /** The environment a record-mode run needs; the key value is never sent by a scripted client. */
  val recordEnv: Map[String, String] = Map(
    AgentCredentials.LiveVariable -> AgentCredentials.LiveValue,
    AgentCredentials.PrimaryKeyVariable -> "scripted-not-a-key"
  )

  def scripted(
      replies: Map[RecordingKey, ModelReply],
      absent: ExchangeFailure = ExchangeFailure.ServiceError(529)
  ): ExchangeSource.Scripted =
    ExchangeSource.Scripted(new ScriptedModelClient(replies, absent))

  /** A hand-written reply: no accounting, because none was measured. */
  def reply(
      text: String,
      stop: ModelStopReason = ModelStopReason.EndTurn,
      chosen: ModelBackend = backend
  ): ModelReply = ModelReply(chosen.model, text, stop, ReplyEvidence.Authored)

  /** A reply shaped as the live client would record it. */
  def captured(text: String, durationMillis: Long): ModelReply =
    ModelReply(
      runtime.model,
      text,
      ModelStopReason.EndTurn,
      ReplyEvidence.Captured(
        Some("claude-sonnet-5-snapshot"),
        ModelUsage(900L, 60L, Some(800L)),
        durationMillis
      )
    )

  /** A fresh recordings directory holding one reply per input, keyed to the chosen backend. */
  def recordingsWith(
      replies: Map[ParserSentenceInput, ModelReply],
      chosen: ModelBackend = backend
  ): Recordings =
    val dir = Files.createTempDirectory("provider-agent-recordings")
    val store = Recordings
      .at(dir)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    replies.foreach { (input, reply) =>
      store
        .write(keyFor(input, chosen), reply)
        .fold(error => throw new IllegalArgumentException(error.toString), identity)
    }
    store

  def transportOver(exchange: ModelExchange, chosen: ModelBackend = backend): ClaudeParserTransport =
    ClaudeParserTransport
      .from(prompt, exchange, ClaudeParserTransport.DefaultMaxTokens, chosen)
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  def replayProvider(
      exchange: ModelExchange,
      chosen: ModelBackend = backend
  ): AmrCandidateProvider[cats.Id] =
    ClaudeParseDriver.provider(transportOver(exchange, chosen), config)

  def failureOf(attempt: ParserAttempt): ParserFailure = attempt.result match
    case Left(failure) => failure
    case Right(_)      => throw new AssertionError(s"expected failure for ${attempt.id.value}")
