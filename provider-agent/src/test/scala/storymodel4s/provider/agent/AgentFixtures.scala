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

  val runtime: RemoteRuntime = ClaudeParseDriver
    .runtime(prompt)
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

  def requestItem(input: ParserSentenceInput): RequestItem =
    RequestItem(
      input.id.value,
      input.sentenceId.value,
      input.sentenceSpan.start,
      input.sentenceSpan.endExclusive,
      input.text,
      input.textChecksum,
      input.tokens.map(token =>
        RequestToken(token.id.value, token.span.start, token.span.endExclusive, token.text)
      )
    )

  def keyFor(input: ParserSentenceInput): RecordingKey =
    ModelRequest
      .render(runtime.model, prompt, requestItem(input), ClaudeParseDriver.MaxTokens, 1000L)
      .key

  def reply(text: String, stop: ModelStopReason = ModelStopReason.EndTurn): ModelReply =
    ModelReply(runtime.model, text, stop, ModelUsage(900L, 60L, 800L), 1500L)

  /** A fresh recordings directory holding one reply per input. */
  def recordingsWith(replies: Map[ParserSentenceInput, ModelReply]): Recordings =
    val dir = Files.createTempDirectory("provider-agent-recordings")
    val store = Recordings
      .at(dir)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    replies.foreach { (input, reply) =>
      store
        .write(keyFor(input), reply)
        .fold(error => throw new IllegalArgumentException(error.toString), identity)
    }
    store

  def replayProvider(exchange: ModelExchange): AmrCandidateProvider[cats.Id] =
    ClaudeParseDriver.provider(runtime, config, prompt, exchange)

  def failureOf(attempt: ParserAttempt): ParserFailure = attempt.result match
    case Left(failure) => failure
    case Right(_)      => throw new AssertionError(s"expected failure for ${attempt.id.value}")
