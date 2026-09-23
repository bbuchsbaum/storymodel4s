package storymodel4s.codec

import java.nio.charset.StandardCharsets

import io.circe.Json
import munit.FunSuite
import storymodel4s.acquire.MediaTypeId
import storymodel4s.core.*
import storymodel4s.view.*

object WorkspaceArchiveFixture:
  val mapping = WorkspaceRole.Mapping(ArtifactId.unsafe("policy-a"))
  val feature = WorkspaceRole.Features(ArtifactId.unsafe("denied-feature"))
  val secret = "SYNTHETIC_DENIED_PHRASE_731"
  val model = "{\"text\":\"A bell rang.\"}\n"
  val recall = "{\"text\":\"I heard a bell.\"}\n"
  val receipt = "{\"kind\":\"synthetic owner declaration\"}"

  def manifest(
      inspection: WorkspaceContentGrant = WorkspaceContentGrant.Granted,
      exportPermission: WorkspaceContentGrant = WorkspaceContentGrant.Granted,
      capabilityChange: WorkspaceCapabilities => WorkspaceCapabilities = identity,
      modelText: String = model
  ): WorkspaceManifest =
    val capabilities = capabilityChange(
      WorkspaceCapabilities(
        Checksum.ofText(modelText),
        Checksum.ofText(recall),
        inspection,
        exportPermission,
        Checksum.ofText(receipt)
      )
    )
    val contents = Map[WorkspaceRole, String](
      WorkspaceRole.SourceModel -> modelText,
      WorkspaceRole.Recall -> recall,
      WorkspaceRole.Inventory -> "{\"inventory\":\"synthetic\"}",
      WorkspaceRole.Derivation -> "{\"derivation\":\"not supplied\"}",
      WorkspaceRole.Capabilities -> WorkspaceArchiveCodec.encodeCapabilities(capabilities),
      WorkspaceRole.Receipt -> receipt,
      mapping -> "{\"mapping\":\"synthetic\"}"
    )
    val supplied = contents.toVector.sortBy(_._1.key).zipWithIndex.map { case ((role, text), i) =>
      val path = BundlePath.unsafe(s"artifact-$i.json")
      val bytes = text.getBytes(StandardCharsets.UTF_8).toVector
      WorkspaceEntry(
        role,
        path,
        WorkspaceDisposition.Supplied(
          ArtifactRef.fromBytes(
            ArtifactId.unsafe(s"artifact-$i"),
            role.artifactRole,
            MediaTypeId.unsafe("application/json"),
            None,
            bytes.toArray
          )
        )
      ) -> bytes
    }
    val denied =
      WorkspaceEntry(feature, BundlePath.unsafe("denied.json"), WorkspaceDisposition.Unauthorized)
    WorkspaceManifest
      .of(
        WorkspaceManifest.SchemaVersion,
        supplied.map(_._1) :+ denied,
        supplied.map((entry, bytes) => entry.path -> bytes).toMap
      )
      .toOption
      .get

class WorkspaceArchiveSuite extends FunSuite:
  import WorkspaceArchiveFixture.*
  private val admitted = manifest()
  private val encoded = WorkspaceArchiveCodec.encode(admitted).toOption.get
  private val json = Canonical.parse(encoded).toOption.get
  private def decode(value: Json) = WorkspaceArchiveCodec.decode(MappingJson.print(value))
  private def fields(value: Json, changes: (String, Json)*): Json =
    value.mapObject(o => changes.foldLeft(o)((result, field) => result.add(field._1, field._2)))
  private def changedArray(key: String)(change: Vector[Json] => Vector[Json]): Json =
    fields(json, key -> Json.fromValues(change(json.hcursor.get[Vector[Json]](key).toOption.get)))

  test("one-file archive preserves exact artifact bytes and explicit optional denial") {
    val result = WorkspaceArchiveCodec.decode(encoded).toOption.get
    admitted.entries.foreach(e =>
      assertEquals(result.manifest.bytes(e.role), admitted.bytes(e.role))
    )
    assertEquals(result.manifest.entry(feature).get.disposition, WorkspaceDisposition.Unauthorized)
    assertEquals(result.manifest.bytes(feature), None)
    assertEquals(WorkspaceArchiveCodec.encode(result.manifest), Right(encoded))
    assert(result.capabilities.permitsExport(Checksum.ofText(model), Checksum.ofText(recall)))
    assert(!encoded.contains(secret))
  }
  test(
    "inspection denial prevents serialization of synthetic secret and returns content-free refusal"
  ) {
    val denied = manifest(inspection = WorkspaceContentGrant.Denied, modelText = secret)
    val result = WorkspaceArchiveCodec.encode(denied)
    assertEquals(result, Left(WorkspaceRefusal.PermissionDenied))
    assert(!result.toString.contains(secret))
    assertEquals(WorkspaceArchive.of(denied), Left(WorkspaceRefusal.PermissionDenied))
  }
  test(
    "export denial blocks transferable packet serialization while inspection remains permitted"
  ) {
    val denied = manifest(exportPermission = WorkspaceContentGrant.Denied, modelText = secret)
    val result = WorkspaceArchive.of(denied).toOption.get
    assert(result.capabilities.permitsInspection(Checksum.ofText(secret), Checksum.ofText(recall)))
    assert(!result.capabilities.permitsExport(Checksum.ofText(secret), Checksum.ofText(recall)))
    val exported = WorkspaceArchiveCodec.encode(denied)
    assertEquals(exported, Left(WorkspaceRefusal.PermissionDenied))
    assert(!exported.toString.contains(secret))
  }
  test("capability identities and declaration receipt must match actual artifacts") {
    Vector[WorkspaceCapabilities => WorkspaceCapabilities](
      _.copy(model = Checksum.ofText("foreign model")),
      _.copy(recall = Checksum.ofText("foreign recall")),
      _.copy(declaration = Checksum.ofText("foreign receipt"))
    ).foreach(change =>
      assertEquals(
        WorkspaceArchiveCodec.encode(manifest(capabilityChange = change)),
        Left(WorkspaceRefusal.PermissionDenied)
      )
    )
  }
  test("unsupported archive and manifest schemas refuse") {
    assertEquals(
      decode(fields(json, "schemaVersion" -> Json.fromString("future"))),
      Left(WorkspaceRefusal.UnsupportedVersion)
    )
    assertEquals(
      decode(fields(json, "manifestVersion" -> Json.fromString("future"))),
      Left(WorkspaceRefusal.UnsupportedVersion)
    )
  }
  test("unknown and null fields refuse instead of disappearing") {
    assertEquals(
      decode(fields(json, "hidden" -> Json.Null)),
      Left(WorkspaceRefusal.UnsupportedContent)
    )
    assertEquals(
      decode(changedArray("entries")(es => es.updated(0, fields(es.head, "hidden" -> Json.Null)))),
      Left(WorkspaceRefusal.UnsupportedContent)
    )
    assertEquals(
      decode(changedArray("files")(fs => fs.updated(0, fields(fs.head, "hidden" -> Json.Null)))),
      Left(WorkspaceRefusal.UnsupportedContent)
    )
  }
  test("duplicate JSON object keys refuse before a parser discards one") {
    val duplicate = "{\"schemaVersion\":\"workspace-archive/v0.1\"," + encoded.drop(1)
    assertEquals(WorkspaceArchiveCodec.decode(duplicate), Left(WorkspaceRefusal.UnsupportedContent))
    assertEquals(
      WorkspaceArchiveCodec.decode("{\"hidden\":\"" + secret + "\",bad}"),
      Left(WorkspaceRefusal.UnsupportedContent)
    )
  }
  test("duplicate file paths and entry roles refuse") {
    assertEquals(
      decode(changedArray("files")(fs => fs :+ fs.head)),
      Left(WorkspaceRefusal.DuplicatePath)
    )
    assertEquals(
      decode(changedArray("entries")(es => es :+ es.head)),
      Left(WorkspaceRefusal.DuplicateRole)
    )
  }
  test("changed artifact text refuses under the original manifest") {
    val changed = changedArray("files")(fs =>
      fs.updated(0, fields(fs.head, "utf8" -> Json.fromString("changed")))
    )
    assertEquals(decode(changed), Left(WorkspaceRefusal.ArtifactMetadataMismatch))
  }
  test("missing files and hidden denied payloads refuse") {
    assertEquals(decode(changedArray("files")(_.tail)), Left(WorkspaceRefusal.UnexpectedBytes))
    val hidden =
      Json.obj("path" -> Json.fromString("denied.json"), "utf8" -> Json.fromString(secret))
    val result = decode(changedArray("files")(_ :+ hidden))
    assertEquals(result, Left(WorkspaceRefusal.UnexpectedBytes))
    assert(!result.toString.contains(secret))
  }
  test("malformed UTF-8 is refused on the producer path without replacement") {
    val bytes = Vector(0xc3.toByte, 0x28.toByte)
    assertEquals(WorkspaceArchiveCodec.utf8(bytes), Left(WorkspaceRefusal.UnsupportedContent))
    assertEquals(
      WorkspaceArchiveCodec.utf8("\u03bb\r\n".getBytes(StandardCharsets.UTF_8).toVector),
      Right("\u03bb\r\n")
    )
  }
  test("capabilities have no default grants and reject extra fields and unknown schemas") {
    val value = WorkspaceArchive.of(admitted).toOption.get.capabilities
    val encodedCapabilities = WorkspaceArchiveCodec.encodeCapabilities(value)
    val parsed = Canonical.parse(encodedCapabilities).toOption.get
    assertEquals(
      WorkspaceArchiveCodec.decodeCapabilities(
        MappingJson.print(parsed.mapObject(_.remove("inspection")))
      ),
      Left(WorkspaceRefusal.UnsupportedContent)
    )
    assertEquals(
      WorkspaceArchiveCodec.decodeCapabilities(
        MappingJson.print(fields(parsed, "hidden" -> Json.Null))
      ),
      Left(WorkspaceRefusal.UnsupportedContent)
    )
    assertEquals(
      WorkspaceArchiveCodec.decodeCapabilities(
        MappingJson.print(fields(parsed, "schemaVersion" -> Json.fromString("future")))
      ),
      Left(WorkspaceRefusal.UnsupportedVersion)
    )
  }
