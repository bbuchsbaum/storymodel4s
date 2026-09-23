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
    val voyage =
      WorkspaceVoyage.from(workspace, ArtifactId.unsafe("historical-lexical")).toOption.get
    val projection = Json.obj(
      "schemaVersion" -> Json.fromString("workspace-fixture-voyage/v0.1"),
      "workspace_sha256" -> Json.fromString(Checksum.ofText(archive).hex),
      "policy" -> Json.fromString(voyage.policy.value),
      "mapping_digest" -> Json.fromString(voyage.mappingDigest.hex),
      "units" -> Json.fromValues(
        voyage.units.map(u =>
          Json.obj(
            "unit" -> Json.fromString(u.unit.value),
            "ordinal" -> Json.fromInt(u.ordinal),
            "disposition" -> Json.fromString(u.disposition.toString)
          )
        )
      ),
      "addresses" -> Json.fromValues(voyage.addresses.toVector.sortBy(_._1.render).map {
        (legacy, qualified) =>
          Json.obj(
            "legacy" -> Json.fromString(legacy.render),
            "qualified" -> Json
              .fromValues(qualified.toVector.map(_.render).sorted.map(Json.fromString))
          )
      })
    )
    Vector(
      write(name + ".workspace.json", archive),
      write(name + "-voyage-projection.json", projection.spaces2 + "\n")
    ) ++ exports ++
      voyage.document.toVector.map(d => write(name + ".voyage.json", VoyageCodecs.encode(d)))
  }
  val index = Json.obj(
    "schemaVersion" -> Json.fromString("workspace-fixture-index/v0.1"),
    "producerRevision" -> Json.fromString(producerRevision),
    "artifacts" -> Json.fromValues(artifacts)
  )
  val _ = write("index.json", index.spaces2 + "\n")
  println(s"Wrote ${artifacts.size} fixture artifacts and index.json to $out")
