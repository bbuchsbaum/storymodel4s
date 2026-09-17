package storymodel4s.corpus

import storymodel4s.core.Checksum

/** A checked relative path whose string form never discloses its filename.
  *
  * Generalized from `embed-bench/.../bench/nfrd/intake.scala:97-120`, which is `private[bench]` and
  * therefore unreachable. The NFRD court itself stays specialized and stays where it is: it is
  * participant-keyed end to end and deliberately retains no bytes, which is the opposite of what a
  * verified snapshot needs. Only the path, request, receipt and refusal primitives move (ADR 0018,
  * rejected alternative 3).
  *
  * The non-disclosing `toString` is the point. A corpus source set is git-ignored precisely because
  * its filenames can identify participants, and a refusal or receipt that renders a path puts that
  * into logs.
  */
final class RelativeArtifactPath private[corpus] (private[corpus] val value: String):
  override def equals(other: Any): Boolean = other match
    case that: RelativeArtifactPath => value == that.value
    case _                          => false
  override def hashCode(): Int = value.hashCode()
  override def toString: String = "<external-artifact>"

object RelativeArtifactPath:
  /** Refuses anything that could leave the snapshot root, or that a filesystem would resolve
    * differently from the string it was given: absolute paths, Windows drive roots, backslashes,
    * NUL and line breaks, and empty, `.` or `..` segments.
    */
  private[corpus] def from(raw: String): Either[IntakeRefusal, RelativeArtifactPath] =
    val segments = raw.split("/", -1).toVector
    val windowsRoot = raw.length >= 2 && raw.charAt(1) == ':'
    val invalid =
      raw.isEmpty || raw.startsWith("/") || windowsRoot || raw.contains('\\') ||
        raw.exists(c => c == 0.toChar || c == '\n' || c == '\r' || c == '\t') ||
        segments.exists(s => s.isEmpty || s == "." || s == "..")
    if invalid then Left(IntakeRefusal.UnsafeRelativePath) else Right(new RelativeArtifactPath(raw))

  private[corpus] def child(
      directory: RelativeArtifactPath,
      filename: String
  ): Either[IntakeRefusal, RelativeArtifactPath] =
    from(s"${directory.value}/$filename")

/** Closed reasons the snapshot root itself cannot be admitted. */
enum RootIssue:
  case Missing, NotDirectory, AccessDenied, InvalidPath, ResolutionFailed

  def render: String = this match
    case Missing          => "missing"
    case NotDirectory     => "not-directory"
    case AccessDenied     => "access-denied"
    case InvalidPath      => "invalid-path"
    case ResolutionFailed => "resolution-failed"

/** Closed filesystem operations that can fail after an artifact has been identified safely. */
enum ReadOperation:
  case Access, Read

  def render: String = productPrefix.toLowerCase

/** The only value a reader receives: an artifact's identity plus a checked relative path. */
final class ArtifactRequest private[corpus] (val id: ArtifactId, val path: RelativeArtifactPath):
  override def toString: String = s"ArtifactRequest(${id.value})"

object ArtifactRequest:
  private[corpus] def of(id: ArtifactId, path: RelativeArtifactPath): ArtifactRequest =
    new ArtifactRequest(id, path)

/** A verified artifact receipt that deliberately renders no external path.
  *
  * `bytes` is a byte COUNT, not the bytes: a receipt is evidence that a read happened and what it
  * found, and it travels into committed records, so it must carry no content.
  */
final class ArtifactReceipt private[corpus] (
    val id: ArtifactId,
    private[corpus] val path: RelativeArtifactPath,
    val bytes: Long,
    val checksum: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: ArtifactReceipt =>
      id == that.id && path == that.path && bytes == that.bytes && checksum == that.checksum
    case _ => false
  override def hashCode(): Int = (id, path, bytes, checksum).hashCode()
  override def toString: String =
    s"ArtifactReceipt(${id.value},bytes=$bytes,checksum=${checksum.short()})"

/** Every refusal an intake can produce, carrying no content, no participant identity and no
  * absolute path.
  */
enum IntakeRefusal:
  case UnsafeRelativePath
  case RootUnavailable(issue: RootIssue)
  case MissingArtifact(id: ArtifactId)
  case NotRegularFile(id: ArtifactId)
  case PathEscapesRoot(id: ArtifactId)
  case ReadFailed(id: ArtifactId, operation: ReadOperation)

  def message: String = this match
    case UnsafeRelativePath     => "artifact path is not a safe relative path"
    case RootUnavailable(issue) => s"snapshot root is ${issue.render}"
    case MissingArtifact(id)    => s"${id.value} is absent from the snapshot"
    case NotRegularFile(id)     => s"${id.value} is not a regular file"
    case PathEscapesRoot(id)    => s"${id.value} resolves outside the snapshot root"
    case ReadFailed(id, op)     => s"${id.value} failed to ${op.render}"
