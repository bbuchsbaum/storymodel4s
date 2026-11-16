package storymodel4s.pipeline.agentmapping

import java.nio.file.Path

/** Run one exported study live or offline, with an immutable input/configuration descriptor. Usage:
  * `agentStudyExecute live|replay SOURCE PARTS EXPORT RUN OUTPUT [MAX_TOKENS TIMEOUT_MS MAX_NEW_CALLS]`.
  * Output contains bundles plus a per-invocation receipt; retries/resume use the same run
  * directory.
  */
@main def agentStudyExecute(
    mode: String,
    source: String,
    parts: String,
    exportDir: String,
    runDir: String,
    outputDir: String,
    limits: String*
): Unit =
  def refuse(reason: String): Nothing =
    System.err.println(s"refused: $reason")
    sys.exit(2)
  if limits.size > 3 then refuse("expected at most MAX_TOKENS TIMEOUT_MS MAX_NEW_CALLS")
  val parsed = limits.map(value => value.toLongOption.getOrElse(refuse("limits must be integers")))
  val maxTokens = parsed.lift(0).getOrElse(4096L)
  val timeoutMillis = parsed.lift(1).getOrElse(60000L)
  val budget = parsed.lift(2).getOrElse(100L)
  if maxTokens <= 0L || timeoutMillis <= 0L || budget <= 0L || budget > Int.MaxValue then
    refuse("limits must be positive and MAX_NEW_CALLS must fit an integer")
  val maxNewCalls = budget.toInt
  val selected = mode match
    case "live"   => StudyExecution.Mode.Live
    case "replay" => StudyExecution.Mode.Replay
    case _        => refuse("mode must be live or replay")
  val receipt = StudyExecution
    .run(
      Path.of(source),
      Path.of(parts),
      Path.of(exportDir),
      Path.of(runDir),
      Path.of(outputDir),
      selected,
      if selected == StudyExecution.Mode.Live then sys.env else Map.empty,
      maxTokens,
      timeoutMillis,
      maxNewCalls
    )
    .fold(refuse, identity)
  val c = receipt.hcursor
  val calls =
    c.get[Int]("modelCalls").fold(_ => refuse("receipt has no invocation count"), identity)
  val attempts = c.get[Int]("httpAttempts").fold(_ => refuse("receipt has no HTTP count"), identity)
  println(s"study complete modelCalls=$calls httpAttempts=$attempts")
