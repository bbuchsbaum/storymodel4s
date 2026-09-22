package storymodel4s.fixtures

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import io.circe.Json
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.view.ArtifactId

/** Narrow offline fixture writer; the calling shell supplies the exact reachable producer SHA. */
@main def writeWorkspaceFixtures(out: String, producerRevision: String): Unit =
  require(
    producerRevision.matches("[0-9a-f]{40}"),
    "producerRevision must be a full lowercase commit SHA"
  )
  val directory = Path.of(out)
  val _ = Files.createDirectories(directory)
  def write(name: String, text: String): Json =
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    val _ = Files.write(directory.resolve(name), bytes)
    Json.obj(
      "path" -> Json.fromString(name),
      "sha256" -> Json.fromString(Checksum.ofBytes(bytes).hex),
      "byteLength" -> Json.fromInt(bytes.length)
    )
  val artifacts = WorkspaceFixtures.all(producerRevision).flatMap { (name, workspace) =>
    val archive = WorkspaceArchiveCodec.encode(workspace.archive.manifest).toOption.get
    val selection = Set(workspace.recallAddress(workspace.inventory.units.head.id).get)
    val exports = Vector("authored-a", "authored-b").flatMap { policy =>
      val payload =
        WorkspaceSubsetCodec.selected(workspace, ArtifactId.unsafe(policy), selection).toOption.get
      val prefix = s"$name-$policy-u0"
      Vector(
        write(prefix + ".json", payload.dataJson),
        write(prefix + ".csv", payload.tableCsv),
        write(prefix + ".txt", payload.accessibleText),
        write(prefix + ".receipt.json", payload.receiptJson)
      )
    }
    write(name + ".workspace.json", archive) +: exports
  }
  val index = Json.obj(
    "schemaVersion" -> Json.fromString("workspace-fixture-index/v0.1"),
    "producerRevision" -> Json.fromString(producerRevision),
    "artifacts" -> Json.fromValues(artifacts)
  )
  val _ = write("index.json", index.spaces2 + "\n")
  println(s"Wrote ${artifacts.size} fixture artifacts and index.json to $out")
