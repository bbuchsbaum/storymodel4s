package storymodel4s.media

import java.nio.file.{Files, Path}
import storymodel4s.core.{Checksum, DomainError}

/** The boundary worker as a realized tool: the script bytes that will run and the runtime the
  * interpreter reports when asked directly. Why: an outcome's own `runtime` block is the worker's
  * claim about itself; joining under a realization observed independently of the outcome makes the
  * join's runtime check a comparison between two sources, not a self-comparison.
  */
object WorkerRealization:
  val ToolName: String = "scenedetect-worker"

  /** One-line program the interpreter runs to report the four versions in the canonical form. */
  private val VersionProgram: String =
    "import platform, scenedetect, numpy, cv2; " +
      "print('scenedetect ' + scenedetect.__version__ + ' python ' + platform.python_version() + " +
      "' numpy ' + numpy.__version__ + ' opencv ' + cv2.__version__)"

  val CaptionToolName: String = "caption-worker"

  /** The captioning worker's runtime line: mlx-vlm and mlx versions plus the interpreter. */
  private val CaptionVersionProgram: String =
    "import platform, mlx_vlm, mlx.core as mx; " +
      "print('mlx_vlm ' + mlx_vlm.__version__ + ' mlx ' + mx.__version__ + ' python ' + platform.python_version())"

  /** Observe the boundary worker: hash the script and ask `python` for its runtime line. Refuses
    * when the script is unreadable or the interpreter does not answer cleanly with one line.
    */
  def observe(python: Path, script: Path): Either[DomainError, ToolRealization] =
    observe(python, script, ToolName, VersionProgram)

  /** Observe the captioning worker the same way, under its own runtime line. */
  def observeCaption(python: Path, script: Path): Either[DomainError, ToolRealization] =
    observe(python, script, CaptionToolName, CaptionVersionProgram)

  private def observe(
      python: Path,
      script: Path,
      name: String,
      program: String
  ): Either[DomainError, ToolRealization] =
    for
      bytes <- scriptBytes(script)
      outcome <- Subprocess.run(Vector(python.toString, "-c", program))
      line <- outcome.stdout.linesIterator.find(_.trim.nonEmpty) match
        case Some(l) if outcome.exitCode == 0 => Right(l.trim)
        case _                                =>
          Left(
            DomainError.InvariantViolation(
              "worker/runtime",
              s"interpreter exited ${outcome.exitCode} without a runtime line: ${outcome.stderr.trim.take(200)}"
            )
          )
      tool <- ToolRealization.of(name, line, Checksum.ofBytes(bytes))
    yield tool

  private def scriptBytes(script: Path): Either[DomainError, Array[Byte]] =
    try
      if Files.isRegularFile(script) then Right(Files.readAllBytes(script))
      else Left(DomainError.InvariantViolation("worker/script", s"$script is not a file"))
    catch
      case e: java.io.IOException =>
        Left(DomainError.InvariantViolation("worker/script", s"$script: ${e.getMessage}"))
