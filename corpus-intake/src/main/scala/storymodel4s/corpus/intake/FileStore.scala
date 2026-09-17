package storymodel4s.corpus.intake

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import storymodel4s.corpus.*

/** An [[ArtifactStore]] over a directory, listing what is actually there.
  *
  * `list` reports the files on disk, not the files a manifest expects, which is what makes the
  * "present in the snapshot but not declared" direction checkable. A store that answered only for
  * ids it was asked about could never surface an unaccounted artifact.
  *
  * It never throws: every filesystem failure is a typed refusal, because a store that throws turns
  * a verification failure into a stack trace.
  */
final class FileStore private (root: Path, names: Vector[String]) extends ArtifactStore:
  def list: Vector[ArtifactId] = names.map(ArtifactId.unsafe)

  def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]] =
    RelativeArtifactPath.from(id.value).flatMap { rel =>
      try
        val p = root.resolve(id.value).normalize()
        if !p.startsWith(root.normalize()) then Left(IntakeRefusal.PathEscapesRoot(id))
        else if !Files.exists(p) then Left(IntakeRefusal.MissingArtifact(id))
        else if !Files.isRegularFile(p) then Left(IntakeRefusal.NotRegularFile(id))
        else Right(Files.readAllBytes(p))
      catch case NonFatal(_) => Left(IntakeRefusal.ReadFailed(id, ReadOperation.Read))
    }

object FileStore:
  /** Opens a store over `root`, listing its regular files one level deep. */
  def at(root: Path): Either[IntakeRefusal, FileStore] =
    try
      if !Files.exists(root) then Left(IntakeRefusal.RootUnavailable(RootIssue.Missing))
      else if !Files.isDirectory(root) then
        Left(IntakeRefusal.RootUnavailable(RootIssue.NotDirectory))
      else
        val names = Files
          .list(root)
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .map(p => root.relativize(p).toString)
          .toVector
          .sorted
        Right(new FileStore(root, names))
    catch
      case _: SecurityException => Left(IntakeRefusal.RootUnavailable(RootIssue.AccessDenied))
      case NonFatal(_)          => Left(IntakeRefusal.RootUnavailable(RootIssue.ResolutionFailed))
