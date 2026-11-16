package storymodel4s.pipeline.agentmapping

import java.nio.file.Path

/** Run one exported study live or offline, with an immutable input/configuration descriptor.
  * Usage: `agentStudyExecute live|replay SOURCE PARTS EXPORT RUN OUTPUT [MAX_TOKENS TIMEOUT_MS MAX_NEW_CALLS]`.
  * Output contains bundles plus a per-invocation receipt; retries/resume use the same run directory.
  */
@main def agentStudyExecute(
    mode: String,
    source: String,
    parts: String,
    exportDir: String,
    runDir: String,
    outputDir: String,
    maxTokens: Long = 4096L,
    timeoutMillis: Long = 60000L,
    maxNewCalls: Int = 100
): Unit =
  def refuse(reason: String): Nothing =
    System.err.println(s"refused: $reason")
    sys.exit(2)
  val selected = mode match
    case "live" => StudyExecution.Mode.Live
    case "replay" => StudyExecution.Mode.Replay
    case _ => refuse("mode must be live or replay")
  val receipt = StudyExecution.run(
    Path.of(source), Path.of(parts), Path.of(exportDir), Path.of(runDir), Path.of(outputDir),
    selected, if selected == StudyExecution.Mode.Live then sys.env else Map.empty,
    maxTokens, timeoutMillis, maxNewCalls
  ).fold(refuse, identity)
  val c = receipt.hcursor
  val calls = c.get[Int]("modelCalls").fold(_ => refuse("receipt has no invocation count"), identity)
  val attempts = c.get[Int]("httpAttempts").fold(_ => refuse("receipt has no HTTP count"), identity)
  println(s"study complete modelCalls=$calls httpAttempts=$attempts")
