package storymodel4s.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.fixtures.WorkspaceFixtures
import storymodel4s.view.*

class WorkspaceMappingExportSuite extends FunSuite:
  import OutputCodecs.given
  private lazy val workspace = WorkspaceFixtures.all("a" * 40).head._2
  private lazy val archive =
    WorkspaceArchiveCodec.encode(workspace.archive.manifest).toOption.get

  private def context(id: ArtifactId): ExpectedMappingContext =
    val view = WorkspaceCodecs.sourceFor(workspace.model).toOption.get._1
    val bytes = workspace.archive.manifest.bytes(WorkspaceRole.Derivation).get
    val derivation = Canonical.parse(new String(bytes.toArray, StandardCharsets.UTF_8)).toOption.get
    val entry = derivation.hcursor
      .get[Vector[Json]]("mappings")
      .toOption
      .get
      .find(_.hcursor.get[String]("id").toOption.contains(id.value))
      .get
    val supplied = entry.hcursor.downField("hsmm").get[String]("value").toOption
    val decoded = supplied.map { text =>
      val result = HsmmResultCodec
        .decode(text, workspace.recall, view)
        .fold(e => fail(e.toString), identity)
      DerivationContext(result, workspace.recall, view)
    }
    ExpectedMappingContext(workspace.inventory, workspace.source, decoded)

  private def temporary[A](run: Path => A): A =
    val root = Files.createTempDirectory("workspace-mapping-export-")
    try run(root)
    finally
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(p => { Files.delete(p): Unit })
      finally paths.close()

  private def input(root: Path, content: String): Path =
    val path = root.resolve("input.json")
    Files.writeString(path, content)

  /** A valid import with a freshly hashed capabilities artifact, not a corrupted archive. */
  private def inspectionOnly: String =
    val manifest = workspace.archive.manifest
    val entry = manifest.entry(WorkspaceRole.Capabilities).get
    val previous = manifest.artifact(WorkspaceRole.Capabilities).get
    val declaration = WorkspaceArchiveCodec.encodeCapabilities(
      workspace.archive.capabilities.copy(exportPermission = WorkspaceContentGrant.Denied)
    )
    val changed = ArtifactRef.fromBytes(
      previous.id,
      previous.role,
      previous.mediaType,
      previous.schemaVersion,
      declaration.getBytes(StandardCharsets.UTF_8)
    )
    val original = Canonical.parse(archive).toOption.get
    val entries = original.hcursor.get[Vector[Json]]("entries").toOption.get.map { item =>
      if item.hcursor.get[String]("path").toOption.contains(entry.path.value) then
        item.mapObject(
          _.add(
            "disposition",
            Json.obj("status" -> Json.fromString("Supplied"), "artifact" -> changed.asJson)
          )
        )
      else item
    }
    val files = original.hcursor.get[Vector[Json]]("files").toOption.get.map { item =>
      if item.hcursor.get[String]("path").toOption.contains(entry.path.value) then
        item.mapObject(_.add("utf8", Json.fromString(declaration)))
      else item
    }
    original
      .mapObject(
        _.add("entries", Json.fromValues(entries)).add("files", Json.fromValues(files))
      )
      .noSpaces

  test("each explicit policy exports its unchanged record and an exact input-byte receipt") {
    temporary { root =>
      val source = input(root, archive + "\n\n")
      assertEquals(workspace.policies.size, 3)
      assertEquals(workspace.policies.map(_.record.digest).distinct.size, 3)
      workspace.policies.zipWithIndex.foreach { (policy, i) =>
        val out = root.resolve(s"policy-$i")
        val receipt = MappingExchangeBuild
          .exportWorkspace(source, policy.id, out)
          .fold(e => fail(e.toString), identity)
        assertEquals(receipt.inputDigest, Checksum.ofBytes(Files.readAllBytes(source)))
        assertNotEquals(receipt.inputDigest, Checksum.ofText(archive))
        assertEquals(receipt.policyId, policy.id)
        assertEquals(receipt.recordDigest, policy.record.digest)
        assertEquals(
          receipt.manifestDigest,
          Checksum.ofBytes(Files.readAllBytes(out.resolve("manifest.json")))
        )
        assertEquals(
          Files.readString(out.resolve("mapping.json")),
          MappingCodecs.encode(policy.record)
        )
        assertEquals(
          MappingExchangeBuild
            .read(out, context(policy.id))
            .fold(e => fail(e.toString), identity)
            .digest,
          policy.record.digest
        )
        val stream = Files.list(out)
        val files =
          try
            stream
              .iterator()
              .asScala
              .toVector
              .sortBy(_.getFileName.toString)
              .map(p => p.getFileName.toString -> Json.fromString(Files.readString(p)))
          finally stream.close()
        println(
          "MAPPING_EXCHANGE_FIXTURE\tworkspace-policy-" + i + "\t" + Json.obj(files*).noSpaces
        )
      }
    }
  }

  test("valid inspection-only workspace refuses before output creation") {
    val incoming = inspectionOnly
    val checked = WorkspaceCodecs.decode(incoming).fold(e => fail(e.toString), identity)
    assert(
      checked.archive.capabilities.permitsInspection(checked.modelArtifact, checked.recallArtifact)
    )
    temporary { root =>
      val source = input(root, incoming)
      Vector(workspace.policies.head.id, ArtifactId.unsafe("unknown-policy")).zipWithIndex.foreach {
        (id, i) =>
          val out = root.resolve(s"denied-$i")
          val result = MappingExchangeBuild.exportWorkspace(source, id, out)
          assertEquals(
            result,
            Left(MappingExchangeBuild.Error.Workspace(WorkspaceRefusal.PermissionDenied))
          )
          assert(!Files.exists(out))
          assert(!result.toString.contains("Two men hunted"))
      }
    }
  }

  test("unknown policy is refused without silently selecting the first record") {
    temporary { root =>
      val source = input(root, archive)
      val out = root.resolve("unknown")
      assertEquals(
        MappingExchangeBuild.exportWorkspace(source, ArtifactId.unsafe("unknown-policy"), out),
        Left(MappingExchangeBuild.Error.Workspace(WorkspaceRefusal.IncompatiblePolicy))
      )
      assert(!Files.exists(out))
    }
  }

  test("malformed UTF8 is refused before any workspace output is created") {
    temporary { root =>
      val source = root.resolve("malformed.json")
      Files.write(source, Array(0xc3.toByte, 0x28.toByte)): Unit
      val out = root.resolve("malformed")
      assertEquals(
        MappingExchangeBuild.exportWorkspace(source, workspace.policies.head.id, out),
        Left(MappingExchangeBuild.Error.InputRead)
      )
      assert(!Files.exists(out))
    }
  }
