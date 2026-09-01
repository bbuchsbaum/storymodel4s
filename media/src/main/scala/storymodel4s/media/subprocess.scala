package storymodel4s.media

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import storymodel4s.core.DomainError

/** The only process boundary in the project (ADR 0007 §4 amendment 2026-09-01). Why one place: a
  * subprocess is untrusted output plus an exit code, and every caller must see both. Stdout and
  * stderr are captured to temporary files so a chatty tool cannot deadlock the pipe; a timeout
  * kills the process and refuses rather than returning a partial stream.
  */
private[media] object Subprocess:
  final case class Outcome(exitCode: Int, stdout: String, stderr: String)

  def run(
      command: Vector[String],
      timeout: FiniteDuration = 120.seconds
  ): Either[DomainError, Outcome] =
    if command.isEmpty then
      Left(DomainError.InvariantViolation("subprocess/command", "empty command"))
    else
      val out = Files.createTempFile("storymodel4s-media-", ".out")
      val err = Files.createTempFile("storymodel4s-media-", ".err")
      try
        val pb = new ProcessBuilder(command*)
        pb.redirectInput(ProcessBuilder.Redirect.from(devNull))
        pb.redirectOutput(out.toFile)
        pb.redirectError(err.toFile)
        val process = pb.start()
        val finished = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
        if !finished then
          process.destroyForcibly()
          Left(
            DomainError.InvariantViolation(
              "subprocess/timeout",
              s"${command.head} did not exit within ${timeout.toSeconds} s"
            )
          )
        else
          Right(
            Outcome(
              process.exitValue(),
              new String(Files.readAllBytes(out), StandardCharsets.UTF_8),
              new String(Files.readAllBytes(err), StandardCharsets.UTF_8)
            )
          )
      catch
        case e: java.io.IOException =>
          Left(
            DomainError.InvariantViolation("subprocess/start", s"${command.head}: ${e.getMessage}")
          )
      finally
        Files.deleteIfExists(out)
        Files.deleteIfExists(err)
        ()

  private def devNull: java.io.File =
    val candidate = new java.io.File("/dev/null")
    if candidate.exists() then candidate else new java.io.File("NUL")

  /** First executable named `name` on `PATH`, or the explicit override in `envVar`. */
  def locate(name: String, envVar: String): Option[Path] =
    val explicit = Option(System.getenv(envVar)).map(_.trim).filter(_.nonEmpty).map(Path.of(_))
    explicit.filter(p => Files.isExecutable(p)).orElse {
      val pathEnv = Option(System.getenv("PATH")).getOrElse("")
      pathEnv
        .split(java.io.File.pathSeparator)
        .iterator
        .filter(_.nonEmpty)
        .map(dir => Path.of(dir, name))
        .find(p => Files.isRegularFile(p) && Files.isExecutable(p))
    }
