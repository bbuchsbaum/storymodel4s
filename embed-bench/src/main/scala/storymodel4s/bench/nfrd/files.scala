package storymodel4s.bench.nfrd

import java.nio.file.{AccessDeniedException, Files, InvalidPathException, NoSuchFileException, Path}
import scala.util.control.NonFatal

/** JVM filesystem boundary for the externally held NFRD Baseball diagnostic court. */
private[bench] object NfrdBaseballFiles:
  /** Parse and verify a CLI-supplied root without allowing `InvalidPathException` to escape. */
  def verify(root: String): Either[NfrdIntakeError, VerifiedNfrdBaseball] =
    try verify(Path.of(root))
    catch
      case _: InvalidPathException =>
        Left(NfrdIntakeError.RootUnavailable(RootIssue.InvalidPath))

  /** Verify one snapshot without retaining participant language in the returned value. */
  def verify(root: Path): Either[NfrdIntakeError, VerifiedNfrdBaseball] =
    verify(root, NfrdBaseballSpec.production)

  private[nfrd] def verify(
      root: Path,
      spec: NfrdBaseballSpec
  ): Either[NfrdIntakeError, VerifiedNfrdBaseball] =
    for
      realRoot <- resolveRoot(root)
      verified <- NfrdBaseballVerifier.verify(
        spec,
        request => readWithin(realRoot, request)
      )
    yield verified

  private def resolveRoot(root: Path): Either[NfrdIntakeError, Path] =
    try
      val real = root.toRealPath()
      if Files.isDirectory(real) then Right(real)
      else Left(NfrdIntakeError.RootUnavailable(RootIssue.NotDirectory))
    catch
      case _: NoSuchFileException =>
        Left(NfrdIntakeError.RootUnavailable(RootIssue.Missing))
      case _: AccessDeniedException =>
        Left(NfrdIntakeError.RootUnavailable(RootIssue.AccessDenied))
      case NonFatal(_) =>
        Left(NfrdIntakeError.RootUnavailable(RootIssue.ResolutionFailed))

  private def readWithin(
      realRoot: Path,
      request: ArtifactRequest
  ): Either[NfrdIntakeError, Array[Byte]] =
    val candidate = realRoot.resolve(request.path.value).normalize()
    if !candidate.startsWith(realRoot) then Left(NfrdIntakeError.PathEscapesRoot(request.label))
    else
      try
        val realCandidate = candidate.toRealPath()
        if !realCandidate.startsWith(realRoot) then
          Left(NfrdIntakeError.PathEscapesRoot(request.label))
        else if !Files.isRegularFile(realCandidate) then
          Left(NfrdIntakeError.NotRegularFile(request.label))
        else Right(Files.readAllBytes(realCandidate))
      catch
        case _: NoSuchFileException   => Left(NfrdIntakeError.MissingArtifact(request.label))
        case _: AccessDeniedException =>
          Left(NfrdIntakeError.ReadFailed(request.label, ReadOperation.Access))
        case NonFatal(_) =>
          Left(NfrdIntakeError.ReadFailed(request.label, ReadOperation.Read))

/** Internal command-line entry point for the real-data court; output is aggregate-only. */
private[bench] object NfrdBaseballCourt:
  def main(args: Array[String]): Unit =
    val exit = run(args)
    if exit != 0 then System.exit(exit)

  private[nfrd] def run(args: Array[String]): Int =
    args.toVector match
      case Vector(rawRoot) =>
        NfrdBaseballFiles.verify(rawRoot) match
          case Right(verified) =>
            println(verified.receipt.render)
            0
          case Left(error) =>
            System.err.println(s"NFRD Baseball input: REFUSED\nreason=${error.message}")
            1
      case _ =>
        System.err.println("usage: NfrdBaseballCourt <external-snapshot-root>")
        2
