package storymodel4s.codec

import java.nio.charset.StandardCharsets

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import storymodel4s.core.*
import storymodel4s.view.*

import CanonicalPrimitives.given
import OutputCodecs.given

/** Exact-byte, permission-checked transport. This alone establishes no scientific join. Only the
  * joined-workspace decoder may establish that the contained model, inventory and mappings describe
  * one investigation. This archive performs no I/O and logs no input text.
  */
final class WorkspaceArchive private (
    val manifest: WorkspaceManifest,
    val capabilities: WorkspaceCapabilities
)
object WorkspaceArchive:
  /** Validate the declaration read from the actual capability artifact, never a second argument. */
  def of(manifest: WorkspaceManifest): Either[WorkspaceRefusal, WorkspaceArchive] =
    for
      bytes <- manifest
        .bytes(WorkspaceRole.Capabilities)
        .toRight(WorkspaceRefusal.MissingRequiredRole)
      text <- WorkspaceArchiveCodec.utf8(bytes)
      capabilities <- WorkspaceArchiveCodec.decodeCapabilities(text)
      model <- manifest
        .artifact(WorkspaceRole.SourceModel)
        .toRight(WorkspaceRefusal.MissingRequiredRole)
      recall <- manifest
        .artifact(WorkspaceRole.Recall)
        .toRight(WorkspaceRefusal.MissingRequiredRole)
      receipt <- manifest
        .artifact(WorkspaceRole.Receipt)
        .toRight(WorkspaceRefusal.MissingRequiredRole)
      _ <- Either.cond(
        capabilities.permitsInspection(model.checksum, recall.checksum) &&
          capabilities.declaration == receipt.checksum,
        (),
        WorkspaceRefusal.PermissionDenied
      )
    yield new WorkspaceArchive(manifest, capabilities)

/** A single offline JSON file containing exact UTF-8 artifact text. Binary artifacts require a
  * future declared transport; malformed UTF-8 is refused rather than repaired. Diagnostic values
  * are content-free even when the archive or a denied artifact contains hostile text.
  */
object WorkspaceArchiveCodec:
  val SchemaVersion: String = "workspace-archive/v0.1"
  val CapabilitiesVersion: String = "workspace-capabilities/v0.1"

  private type Result[A] = Either[WorkspaceRefusal, A]
  private def obj(fields: (String, Json)*): Json = Json.obj(fields*)
  private def str(value: String): Json = Json.fromString(value)
  private def field[A: Decoder](json: Json, key: String): Result[A] =
    json.hcursor.get[A](key).left.map(_ => WorkspaceRefusal.UnsupportedContent)
  private def parse(text: String): Result[Json] =
    for
      json <- Canonical.parse(text).left.map(_ => WorkspaceRefusal.UnsupportedContent)
      _ <- MappingJson.uniqueObjectKeys(text).left.map(_ => WorkspaceRefusal.UnsupportedContent)
    yield json
  private def exact(actual: Json, expected: Json): Result[Unit] =
    Either.cond(
      MappingJson.print(actual) == MappingJson.print(expected),
      (),
      WorkspaceRefusal.UnsupportedContent
    )
  private[codec] def utf8(bytes: Vector[Byte]): Result[String] =
    val text = new String(bytes.toArray, StandardCharsets.UTF_8)
    Either.cond(
      text.getBytes(StandardCharsets.UTF_8).toVector == bytes,
      text,
      WorkspaceRefusal.UnsupportedContent
    )

  private def capabilitiesJson(value: WorkspaceCapabilities): Json = obj(
    "schemaVersion" -> str(CapabilitiesVersion),
    "model" -> value.model.asJson,
    "recall" -> value.recall.asJson,
    "inspection" -> str(value.inspection.toString),
    "export" -> str(value.exportPermission.toString),
    "declaration" -> value.declaration.asJson
  )
  def encodeCapabilities(value: WorkspaceCapabilities): String =
    MappingJson.print(capabilitiesJson(value))
  def decodeCapabilities(text: String): Result[WorkspaceCapabilities] =
    def grant(json: Json, name: String): Result[WorkspaceContentGrant] =
      field[String](json, name).flatMap(s =>
        WorkspaceContentGrant.values
          .find(_.toString == s)
          .toRight(WorkspaceRefusal.UnsupportedContent)
      )
    for
      json <- parse(text)
      version <- field[String](json, "schemaVersion")
      _ <- Either.cond(version == CapabilitiesVersion, (), WorkspaceRefusal.UnsupportedVersion)
      model <- field[Checksum](json, "model")
      recall <- field[Checksum](json, "recall")
      inspection <- grant(json, "inspection")
      exportPermission <- grant(json, "export")
      declaration <- field[Checksum](json, "declaration")
      capabilities = WorkspaceCapabilities(model, recall, inspection, exportPermission, declaration)
      _ <- exact(json, capabilitiesJson(capabilities))
    yield capabilities

  private def roleJson(role: WorkspaceRole): Json = role match
    case WorkspaceRole.Mapping(id)  => obj("kind" -> str("Mapping"), "id" -> id.asJson)
    case WorkspaceRole.Features(id) => obj("kind" -> str("Features"), "id" -> id.asJson)
    case other                      => obj("kind" -> str(other.toString))
  private def readRole(json: Json): Result[WorkspaceRole] =
    field[String](json, "kind").flatMap {
      case "Mapping"  => field[ArtifactId](json, "id").map(WorkspaceRole.Mapping(_))
      case "Features" => field[ArtifactId](json, "id").map(WorkspaceRole.Features(_))
      case other      =>
        WorkspaceManifest.Required
          .find(_.toString == other)
          .toRight(WorkspaceRefusal.UnsupportedContent)
    }
  private def dispositionJson(disposition: WorkspaceDisposition): Json = disposition match
    case WorkspaceDisposition.Supplied(ref) =>
      obj("status" -> str("Supplied"), "artifact" -> ref.asJson)
    case other => obj("status" -> str(other.toString))
  private def readDisposition(json: Json): Result[WorkspaceDisposition] =
    field[String](json, "status").flatMap {
      case "Supplied" => field[ArtifactRef](json, "artifact").map(WorkspaceDisposition.Supplied(_))
      case "Absent"   => Right(WorkspaceDisposition.Absent)
      case "Refused"  => Right(WorkspaceDisposition.Refused)
      case "Unauthorized" => Right(WorkspaceDisposition.Unauthorized)
      case _              => Left(WorkspaceRefusal.UnsupportedContent)
    }
  private def entryJson(entry: WorkspaceEntry): Json = obj(
    "role" -> roleJson(entry.role),
    "path" -> entry.path.asJson,
    "disposition" -> dispositionJson(entry.disposition)
  )
  private def readEntry(json: Json): Result[WorkspaceEntry] =
    for
      role <- field[Json](json, "role").flatMap(readRole)
      path <- field[BundlePath](json, "path")
      disposition <- field[Json](json, "disposition").flatMap(readDisposition)
    yield WorkspaceEntry(role, path, disposition)

  private def archiveJson(manifest: WorkspaceManifest): Result[Json] =
    manifest.entries
      .traverse { entry =>
        manifest
          .bytes(entry.role)
          .traverse(bytes =>
            utf8(bytes).map(text => obj("path" -> entry.path.asJson, "utf8" -> str(text)))
          )
      }
      .map(files =>
        obj(
          "schemaVersion" -> str(SchemaVersion),
          "manifestVersion" -> str(WorkspaceManifest.SchemaVersion),
          "entries" -> Json.fromValues(manifest.entries.map(entryJson)),
          "files" -> Json.fromValues(files.flatten)
        )
      )

  /** Permission is checked before any archive text is returned, including on the producer path. */
  def encode(manifest: WorkspaceManifest): Result[String] =
    WorkspaceArchive.of(manifest).flatMap(_ => archiveJson(manifest).map(MappingJson.print))

  def decode(text: String): Result[WorkspaceArchive] =
    for
      json <- parse(text)
      version <- field[String](json, "schemaVersion")
      _ <- Either.cond(version == SchemaVersion, (), WorkspaceRefusal.UnsupportedVersion)
      manifestVersion <- field[String](json, "manifestVersion")
      entries <- field[Vector[Json]](json, "entries").flatMap(_.traverse(readEntry))
      files <- field[Vector[Json]](json, "files").flatMap(
        _.traverse(value =>
          for
            path <- field[BundlePath](value, "path")
            content <- field[String](value, "utf8")
          yield path -> content.getBytes(StandardCharsets.UTF_8).toVector
        )
      )
      _ <- Either.cond(
        files.map(_._1).distinct.size == files.size,
        (),
        WorkspaceRefusal.DuplicatePath
      )
      manifest <- WorkspaceManifest.of(manifestVersion, entries, files.toMap)
      archive <- WorkspaceArchive.of(manifest)
      canonical <- archiveJson(manifest)
      _ <- exact(json, canonical)
    yield archive
