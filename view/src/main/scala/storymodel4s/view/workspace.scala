package storymodel4s.view

import storymodel4s.core.*
import storymodel4s.acquire.{OutputLabel, OutputNamespace}

/** Artifact roles in a joined investigation, distinct from a story-build output profile. */
enum WorkspaceRole:
  case SourceModel, Recall, Inventory, Derivation, Capabilities, Receipt
  case Mapping(id: ArtifactId)
  case Features(id: ArtifactId)

  def key: String = this match
    case Mapping(id)  => s"mapping:${id.value}"
    case Features(id) => s"features:${id.value}"
    case other        => other.toString

  def required: Boolean = this match
    case Features(_) => false
    case _           => true

  def artifactRole: ArtifactRole = this match
    case SourceModel => ArtifactRole.SemanticModel
    case other       =>
      ArtifactRole.Custom(
        OutputNamespace.unsafe("workspace"),
        OutputLabel.unsafe(other.key),
        other match
          case Mapping(id)  => id
          case Features(id) => id
          case _            => ArtifactId.unsafe(s"workspace:${other.key}")
      )

/** Unavailable artifacts carry no invented byte identity or payload. */
enum WorkspaceDisposition:
  case Supplied(artifact: ArtifactRef)
  case Absent, Refused, Unauthorized

/** Unchecked manifest input; only WorkspaceManifest.of establishes the byte join. */
final case class WorkspaceEntry(
    role: WorkspaceRole,
    path: BundlePath,
    disposition: WorkspaceDisposition
)

/** Fixed, content-free diagnostics: refusals never echo denied input strings. */
enum WorkspaceRefusal:
  case UnsupportedVersion, DuplicateRole, DuplicatePath, DuplicateArtifact
  case MissingRequiredRole, UnavailableRequiredRole, UnexpectedBytes
  case ArtifactMetadataMismatch, ArtifactDigestMismatch
  case PermissionDenied, SemanticJoinMismatch, UnsupportedContent
  case InvalidSelection, IncompatiblePolicy, StaleArtifacts

/** Exact-byte admission only. Identity is not permission or scientific authority. */
final class WorkspaceManifest private (
    val entries: Vector[WorkspaceEntry],
    private val files: Map[BundlePath, Vector[Byte]]
):
  def entry(role: WorkspaceRole): Option[WorkspaceEntry] = entries.find(_.role == role)
  def bytes(role: WorkspaceRole): Option[Vector[Byte]] = entry(role).flatMap(e => files.get(e.path))
  def artifact(role: WorkspaceRole): Option[ArtifactRef] = entry(role).flatMap {
    case WorkspaceEntry(_, _, WorkspaceDisposition.Supplied(ref)) => Some(ref)
    case _                                                        => None
  }
  def mappings: Vector[WorkspaceRole.Mapping] = entries.map(_.role).collect {
    case mapping: WorkspaceRole.Mapping => mapping
  }

object WorkspaceManifest:
  val SchemaVersion: String = "workspace-manifest/v0.1"
  val Required: Set[WorkspaceRole] = Set(
    WorkspaceRole.SourceModel,
    WorkspaceRole.Recall,
    WorkspaceRole.Inventory,
    WorkspaceRole.Derivation,
    WorkspaceRole.Capabilities,
    WorkspaceRole.Receipt
  )

  /** Admit exactly the supplied files once, with no fallback for absent mandatory roles. */
  def of(
      schemaVersion: String,
      entries: Vector[WorkspaceEntry],
      files: Map[BundlePath, Vector[Byte]]
  ): Either[WorkspaceRefusal, WorkspaceManifest] =
    val supplied = entries.collect {
      case WorkspaceEntry(role, path, WorkspaceDisposition.Supplied(ref)) => (role, path, ref)
    }
    val roles = entries.map(_.role)
    val paths = entries.map(_.path.value.toLowerCase(java.util.Locale.ROOT))
    val ids = supplied.map(_._3.id)
    if schemaVersion != SchemaVersion then Left(WorkspaceRefusal.UnsupportedVersion)
    else if roles.distinct.size != roles.size then Left(WorkspaceRefusal.DuplicateRole)
    else if paths.distinct.size != paths.size then Left(WorkspaceRefusal.DuplicatePath)
    else if ids.distinct.size != ids.size then Left(WorkspaceRefusal.DuplicateArtifact)
    else if !Required.subsetOf(roles.toSet) || !roles.exists {
        case WorkspaceRole.Mapping(_) => true
        case _                        => false
      }
    then Left(WorkspaceRefusal.MissingRequiredRole)
    else if entries.exists(e =>
        e.role.required && !e.disposition.isInstanceOf[WorkspaceDisposition.Supplied]
      )
    then Left(WorkspaceRefusal.UnavailableRequiredRole)
    else if supplied.map(_._2).toSet != files.keySet then Left(WorkspaceRefusal.UnexpectedBytes)
    else if supplied.exists { (role, path, ref) =>
        ref.role != role.artifactRole || ref.byteLength != files(path).size.toLong
      }
    then Left(WorkspaceRefusal.ArtifactMetadataMismatch)
    else if supplied.exists { (_, path, ref) =>
        ref.checksum != Checksum.ofBytes(files(path).toArray)
      }
    then Left(WorkspaceRefusal.ArtifactDigestMismatch)
    else Right(new WorkspaceManifest(entries.sortBy(_.role.key), files))

/** A declaration made by the artifact owner; a digest alone never creates a grant. */
enum WorkspaceContentGrant:
  case Granted, Denied

/** Explicit content permissions bound to exact model/recall artifacts and a declaration receipt.
  * This records the caller's authorization; it does not certify a legal right or authenticate an
  * issuer. A denied mandatory role must be omitted before a deliverable archive is serialized.
  */
final case class WorkspaceCapabilities(
    model: Checksum,
    recall: Checksum,
    inspection: WorkspaceContentGrant,
    exportPermission: WorkspaceContentGrant,
    declaration: Checksum
):
  def permitsInspection(modelArtifact: Checksum, recallArtifact: Checksum): Boolean =
    model == modelArtifact && recall == recallArtifact && inspection == WorkspaceContentGrant.Granted
  def permitsExport(modelArtifact: Checksum, recallArtifact: Checksum): Boolean =
    permitsInspection(
      modelArtifact,
      recallArtifact
    ) && exportPermission == WorkspaceContentGrant.Granted
