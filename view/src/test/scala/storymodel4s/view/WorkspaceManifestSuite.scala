package storymodel4s.view

import java.nio.charset.StandardCharsets
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.acquire.{MediaTypeId, OutputLabel, OutputNamespace}

class WorkspaceManifestSuite extends FunSuite:
  private val mapping = WorkspaceRole.Mapping(ArtifactId.unsafe("policy-a"))
  private val roles = WorkspaceManifest.Required.toVector.sortBy(_.key) :+ mapping
  private val entries = roles.zipWithIndex.map { (role, i) =>
    WorkspaceEntry(
      role,
      BundlePath.unsafe(s"artifact-$i.json"),
      WorkspaceDisposition.Supplied(
        ArtifactRef.fromBytes(
          ArtifactId.unsafe(s"artifact-$i"),
          role.artifactRole,
          MediaTypeId.unsafe("application/json"),
          None,
          s"payload-$i".getBytes(StandardCharsets.UTF_8)
        )
      )
    )
  }
  private val files = entries.zipWithIndex.map { (entry, i) =>
    entry.path -> s"payload-$i".getBytes(StandardCharsets.UTF_8).toVector
  }.toMap
  private def admit(
      es: Vector[WorkspaceEntry] = entries,
      fs: Map[BundlePath, Vector[Byte]] = files
  ): Either[WorkspaceRefusal, WorkspaceManifest] =
    WorkspaceManifest.of(WorkspaceManifest.SchemaVersion, es, fs)

  test("all required roles and exact bytes admit in canonical role order") {
    val result = admit(entries.reverse).toOption.get
    assertEquals(result.entries.map(_.role.key), roles.map(_.key).sorted)
    entries.foreach(e => assertEquals(result.bytes(e.role), files.get(e.path)))
    assertEquals(result.mappings, Vector(mapping))
  }
  test("mapping and feature roles admit the full ArtifactId length without throwing") {
    val id = ArtifactId.unsafe("x" * 256)
    Vector(
      WorkspaceRole.Mapping(id) -> "mapping",
      WorkspaceRole.Features(id) -> "features"
    ).foreach { (role, label) =>
      val expected = ArtifactRole.Custom(
        OutputNamespace.unsafe("workspace"),
        OutputLabel.unsafe(label),
        id
      )
      assertEquals(role.artifactRole, expected)
      val path = BundlePath.unsafe("long-id.json")
      val bytes = "long-id-payload".getBytes(StandardCharsets.UTF_8).toVector
      val extra = WorkspaceEntry(
        role,
        path,
        WorkspaceDisposition.Supplied(
          ArtifactRef.fromBytes(
            ArtifactId.unsafe("long-id-artifact"),
            expected,
            MediaTypeId.unsafe("application/json"),
            None,
            bytes.toArray
          )
        )
      )
      assert(admit(entries :+ extra, files.updated(path, bytes)).isRight)
    }
  }
  test("unsupported version refuses before any body admission") {
    assertEquals(
      WorkspaceManifest.of("workspace-manifest/v999", Vector.empty, Map.empty),
      Left(WorkspaceRefusal.UnsupportedVersion)
    )
  }
  test("omitting each mandatory role and all mappings refuses") {
    entries.foreach { missing =>
      assertEquals(
        admit(entries.filterNot(_ == missing), files - missing.path),
        Left(WorkspaceRefusal.MissingRequiredRole)
      )
    }
  }
  test("mandatory absent, refused and unauthorized states refuse without fabricated bytes") {
    val first = entries.head
    Vector(
      WorkspaceDisposition.Absent,
      WorkspaceDisposition.Refused,
      WorkspaceDisposition.Unauthorized
    ).foreach { state =>
      assertEquals(
        admit(first.copy(disposition = state) +: entries.tail, files - first.path),
        Left(WorkspaceRefusal.UnavailableRequiredRole)
      )
    }
  }
  test("optional feature absence, refusal and denial remain distinct and do not erase mappings") {
    Vector(
      WorkspaceDisposition.Absent,
      WorkspaceDisposition.Refused,
      WorkspaceDisposition.Unauthorized
    ).foreach { state =>
      val extra = WorkspaceEntry(
        WorkspaceRole.Features(ArtifactId.unsafe("optional-features")),
        BundlePath.unsafe("features.json"),
        state
      )
      val result = admit(entries :+ extra).toOption.get
      assertEquals(result.entry(extra.role).map(_.disposition), Some(state))
      assertEquals(result.mappings, Vector(mapping))
      assertEquals(result.bytes(extra.role), None)
    }
  }
  test("duplicate roles and case-folded paths refuse before lookups discard an entry") {
    assertEquals(admit(entries :+ entries.head), Left(WorkspaceRefusal.DuplicateRole))
    val extra = WorkspaceEntry(
      WorkspaceRole.Features(ArtifactId.unsafe("optional-features")),
      BundlePath.unsafe(entries.head.path.value.toUpperCase(java.util.Locale.ROOT)),
      WorkspaceDisposition.Absent
    )
    assertEquals(admit(entries :+ extra), Left(WorkspaceRefusal.DuplicatePath))
  }
  test("duplicate artifact IDs across distinct roles refuse") {
    val first = entries.head.disposition.asInstanceOf[WorkspaceDisposition.Supplied].artifact
    val second = entries(1)
    val duplicate = ArtifactRef.fromBytes(
      first.id,
      second.role.artifactRole,
      MediaTypeId.unsafe("application/json"),
      None,
      files(second.path).toArray
    )
    assertEquals(
      admit(
        entries.updated(1, second.copy(disposition = WorkspaceDisposition.Supplied(duplicate)))
      ),
      Left(WorkspaceRefusal.DuplicateArtifact)
    )
  }
  test("changed bytes with unchanged paths, local IDs and lengths refuse") {
    val first = entries.head.path
    assertEquals(
      admit(fs = files.updated(first, files(first).updated(0, '!'.toByte))),
      Left(WorkspaceRefusal.ArtifactDigestMismatch)
    )
    assertEquals(
      admit(fs = files.updated(first, files(first) :+ 0.toByte)),
      Left(WorkspaceRefusal.ArtifactMetadataMismatch)
    )
  }
  test("unmanifested bytes and missing declared bytes refuse") {
    assertEquals(
      admit(fs = files.updated(BundlePath.unsafe("extra.json"), Vector(1.toByte))),
      Left(WorkspaceRefusal.UnexpectedBytes)
    )
    assertEquals(admit(fs = files - entries.head.path), Left(WorkspaceRefusal.UnexpectedBytes))
  }
  test("an unavailable feature cannot carry a hidden payload") {
    val extra = WorkspaceEntry(
      WorkspaceRole.Features(ArtifactId.unsafe("optional-features")),
      BundlePath.unsafe("features.json"),
      WorkspaceDisposition.Unauthorized
    )
    assertEquals(
      admit(
        entries :+ extra,
        files.updated(extra.path, "denied-synthetic-content".getBytes.toVector)
      ),
      Left(WorkspaceRefusal.UnexpectedBytes)
    )
  }
  test("supplied metadata must identify its actual role") {
    val second = entries(1)
    val wrong = entries.head.disposition.asInstanceOf[WorkspaceDisposition.Supplied].artifact
    val ref = ArtifactRef.fromBytes(
      ArtifactId.unsafe("wrong-role"),
      wrong.role,
      MediaTypeId.unsafe("application/json"),
      None,
      files(second.path).toArray
    )
    assertEquals(
      admit(entries.updated(1, second.copy(disposition = WorkspaceDisposition.Supplied(ref)))),
      Left(WorkspaceRefusal.ArtifactMetadataMismatch)
    )
  }
  test("content declarations require exact identities and explicit inspection/export grants") {
    val model = Checksum.ofText("model")
    val recall = Checksum.ofText("recall")
    val c = WorkspaceCapabilities(
      model,
      recall,
      WorkspaceContentGrant.Granted,
      WorkspaceContentGrant.Denied,
      Checksum.ofText("owner declaration")
    )
    assert(c.permitsInspection(model, recall))
    assert(!c.permitsExport(model, recall))
    assert(!c.permitsInspection(Checksum.ofText("foreign model"), recall))
    assert(!c.permitsInspection(model, Checksum.ofText("foreign recall")))
    assert(!c.copy(inspection = WorkspaceContentGrant.Denied).permitsInspection(model, recall))
    assert(c.copy(exportPermission = WorkspaceContentGrant.Granted).permitsExport(model, recall))
  }
