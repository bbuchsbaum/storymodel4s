package storymodel4s.media

import java.nio.file.{Files, Path}
import storymodel4s.core.{Checksum, ContentAddress, DomainError}

/** Identity of a locally realized external tool: its name, the first line of `<tool> -version`, and
  * the SHA-256 of the binary that ran. Why: every derivation receipt must bind the exact executable
  * that produced it, so a probe replayed under a different binary can never share a receipt
  * identity with the original.
  *
  * A realization is local and carries `Draft` authority only (admission ledger §6.1). It records
  * which bytes ran; it does not assert that those bytes match the nominated build recipe.
  */
final class ToolRealization private (
    val name: String,
    val versionLine: String,
    val binarySha256: Checksum,
    val identity: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: ToolRealization =>
      name == that.name && versionLine == that.versionLine &&
      binarySha256 == that.binarySha256 && identity == that.identity
    case _ => false
  override def hashCode(): Int = (name, versionLine, binarySha256, identity).hashCode()
  override def toString: String = s"ToolRealization($name, ${identity.short()})"

object ToolRealization:
  private def computeIdentity(name: String, versionLine: String, binary: Checksum): Checksum =
    ContentAddress.digest(Vector("tool-realization", name, versionLine, binary.hex))

  def of(
      name: String,
      versionLine: String,
      binarySha256: Checksum
  ): Either[DomainError, ToolRealization] =
    if name.trim.isEmpty then
      Left(DomainError.InvalidFormat("ToolRealization", name, "empty tool name"))
    else if versionLine.trim.isEmpty || versionLine.exists(c => c == '\n' || c == '\r') then
      Left(
        DomainError.InvalidFormat("ToolRealization", versionLine, "version line must be one line")
      )
    else
      Right(
        new ToolRealization(
          name,
          versionLine,
          binarySha256,
          computeIdentity(name, versionLine, binarySha256)
        )
      )

  /** Observe the executable at `path`: resolve symlinks, hash the bytes that will run, and read the
    * first line `<path> -version` prints. Refuses when the file is unreadable or the tool does not
    * exit cleanly.
    */
  def observe(name: String, path: Path): Either[DomainError, ToolRealization] =
    for
      resolved <- readable(path)
      bytes <- bytesOf(resolved)
      outcome <- Subprocess.run(Vector(resolved.toString, "-version"))
      line <- outcome.stdout.linesIterator.find(_.trim.nonEmpty) match
        case Some(l) if outcome.exitCode == 0 => Right(l.trim)
        case _                                =>
          Left(
            DomainError.InvariantViolation(
              "tool/version",
              s"$name -version exited ${outcome.exitCode} without a version line"
            )
          )
      tool <- of(name, line, Checksum.ofBytes(bytes))
    yield tool

  private def bytesOf(path: Path): Either[DomainError, Array[Byte]] =
    try Right(Files.readAllBytes(path))
    catch
      case e: java.io.IOException =>
        Left(DomainError.InvariantViolation("tool/path", s"$path: ${e.getMessage}"))

  private def readable(path: Path): Either[DomainError, Path] =
    try
      val real = path.toRealPath()
      if Files.isRegularFile(real) && Files.isReadable(real) then Right(real)
      else Left(DomainError.InvariantViolation("tool/path", s"$path is not a readable file"))
    catch
      case e: java.io.IOException =>
        Left(DomainError.InvariantViolation("tool/path", s"$path: ${e.getMessage}"))
