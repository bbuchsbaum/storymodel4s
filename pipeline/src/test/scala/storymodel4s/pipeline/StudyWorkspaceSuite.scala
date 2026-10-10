package storymodel4s.pipeline

import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import storymodel4s.codec.{Canonical, SurfaceWorkspaceCodecs, WorkspaceArchiveCodec}
import storymodel4s.core.SurfaceUnitKind
import storymodel4s.codec.OutputCodecs.given
import storymodel4s.view.{ArtifactRef, WorkspaceContentGrant, WorkspaceRole}

/** End-to-end courts for the local-only study-workspace publication boundary. */
class StudyWorkspaceSuite extends FunSuite:
  private val revision = "a" * 40
  private val mapping = MappingCli.Template
  private val sourceText = "Anna arrived. Bob left."
  private val admittedRecall = "Anna arrived."

  private def temporary(test: Path => Unit): Unit =
    val root = Files.createTempDirectory("study-workspace-")
    try test(root)
    finally
      val paths = Files.walk(root)
      try
        paths
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(p => { Files.deleteIfExists(p): Unit })
      finally paths.close()

  private def write(root: Path, name: String, content: String): Unit =
    val path = root.resolve(name)
    Files.writeString(path, content, UTF_8): Unit

  private def study(
      source: Json,
      recalls: Vector[(String, String, String, Boolean)]
  ): String =
    Canonical.printer
      .copy(dropNullValues = false)
      .print(
        Json.obj(
          "schemaVersion" -> Json.fromString(StudyPreparation.Schema),
          "source" -> source,
          "profile" -> Json.fromString(StudyPreparation.RecallProfile),
          "recalls" -> Json.fromValues(recalls.map { (participant, session, path, admitted) =>
            Json
              .obj(
                "participant" -> Json.fromString(participant),
                "session" -> Json.fromString(session),
                "path" -> Json.fromString(path),
                "timing" -> Json.Null
              )
              .deepMerge(
                if admitted then
                  Json.obj(
                    "admission" -> Json.obj("kind" -> Json.fromString("project-synthetic-control"))
                  )
                else Json.obj()
              )
          })
        )
      )

  private def textSource(path: String = "source.txt"): Json =
    Json.obj(
      "format" -> Json.fromString("text"),
      "path" -> Json.fromString(path),
      "kind" -> Json.fromString(SurfaceUnitKind.Sentence.toString)
    )

  private def timedSource: Json =
    Json.obj(
      "format" -> Json.fromString("timed-annotations"),
      "path" -> Json.fromString("annotations.csv"),
      "parts" -> Json.fromString("parts.json")
    )

  private def invoke(args: String*): (Int, Vector[String], Vector[String]) =
    val out = Vector.newBuilder[String]
    val err = Vector.newBuilder[String]
    val status =
      StudyWorkspaceCli.run(args.toList, s => { out.addOne(s): Unit }, s => { err.addOne(s): Unit })
    (status, out.result(), err.result())

  private def runArgs(root: Path, cache: Path, output: Path): List[String] =
    List(
      "run",
      root.resolve("study.json").toString,
      root.resolve("mapping.json").toString,
      cache.toString,
      output.toString,
      revision
    )

  private def packet(output: Path): Path =
    val stream = Files.list(output)
    try
      val found = stream
        .iterator()
        .asScala
        .filter(_.getFileName.toString.endsWith("-workspace.json"))
        .toVector
      assertEquals(found.size, 1)
      found.head
    finally stream.close()

  private def workspace(output: Path): String = Files.readString(packet(output), UTF_8)

  private def outputBytes(output: Path): Map[String, Vector[Byte]] =
    val stream = Files.list(output)
    try
      stream
        .iterator()
        .asScala
        .toVector
        .map(p => p.getFileName.toString -> Files.readAllBytes(p).toVector)
        .toMap
    finally stream.close()

  private def childDirectories(path: Path): Int =
    val stream = Files.list(path)
    try stream.iterator().asScala.count(Files.isDirectory(_))
    finally stream.close()

  private def writeBasic(root: Path, admitted: Boolean, people: Vector[String]): Unit =
    write(root, "source.txt", sourceText)
    write(root, "recall.txt", admittedRecall)
    write(root, "mapping.json", mapping)
    write(
      root,
      "study.json",
      study(textSource(), people.map(p => (p, "s1", "recall.txt", admitted)))
    )

  private def permissions(
      packet: String,
      inspection: WorkspaceContentGrant,
      exportPermission: WorkspaceContentGrant
  ): String =
    val checked = SurfaceWorkspaceCodecs.decode(packet).fold(e => fail(e.toString), identity)
    val manifest = checked.archive.manifest
    val entry = manifest.entry(WorkspaceRole.Capabilities).getOrElse(fail("missing capabilities"))
    val previous =
      manifest.artifact(WorkspaceRole.Capabilities).getOrElse(fail("missing capability artifact"))
    val declaration = WorkspaceArchiveCodec.encodeCapabilities(
      checked.archive.capabilities
        .copy(inspection = inspection, exportPermission = exportPermission)
    )
    val changed = ArtifactRef.fromBytes(
      previous.id,
      previous.role,
      previous.mediaType,
      previous.schemaVersion,
      declaration.getBytes(UTF_8)
    )
    val json = Canonical.parse(packet).fold(e => fail(e.toString), identity)
    val entries = json.hcursor
      .get[Vector[Json]]("entries")
      .fold(e => fail(e.toString), identity)
      .map { value =>
        if value.hcursor.get[String]("path").contains(entry.path.value) then
          value.mapObject(
            _.add(
              "disposition",
              Json.obj(
                "status" -> Json.fromString("Supplied"),
                "artifact" -> changed.asJson
              )
            )
          )
        else value
      }
    val files =
      json.hcursor.get[Vector[Json]]("files").fold(e => fail(e.toString), identity).map { value =>
        if value.hcursor.get[String]("path").contains(entry.path.value) then
          value.mapObject(_.add("utf8", Json.fromString(declaration)))
        else value
      }
    json
      .mapObject(_.add("entries", Json.fromValues(entries)).add("files", Json.fromValues(files)))
      .noSpaces

  private def changedRecord(packet: String)(change: Json => Json): String =
    val checked = SurfaceWorkspaceCodecs.decode(packet).toOption.get
    val manifest = checked.archive.manifest
    val mappingRole = WorkspaceRole.Mapping(storymodel4s.view.ArtifactId.unsafe("reconstruction"))
    val original = manifest.entries
      .map(e => e.role -> new String(manifest.bytes(e.role).get.toArray, UTF_8))
      .toMap
    val changed = Canonical.print(change(Canonical.parse(original(mappingRole)).toOption.get))
    val receipt = Canonical.parse(original(WorkspaceRole.Receipt)).toOption.get
    val members = receipt.hcursor.get[Vector[Json]]("members").toOption.get.map { member =>
      if member.hcursor.get[String]("id").contains("reconstruction") then
        member.mapObject(
          _.add("bytes", Json.fromString(storymodel4s.core.Checksum.ofText(changed).hex))
        )
      else member
    }
    val receiptText = Canonical.print(receipt.mapObject(_.add("members", Json.fromValues(members))))
    val capabilities = WorkspaceArchiveCodec.encodeCapabilities(
      checked.archive.capabilities.copy(
        declaration = storymodel4s.core.Checksum.ofText(receiptText)
      )
    )
    val content = original ++ Map(
      mappingRole -> changed,
      WorkspaceRole.Receipt -> receiptText,
      WorkspaceRole.Capabilities -> capabilities
    )
    val entries = manifest.entries.map { entry =>
      val ref = manifest.artifact(entry.role).get
      val replacement = ArtifactRef.fromBytes(
        ref.id,
        ref.role,
        ref.mediaType,
        ref.schemaVersion,
        content(entry.role).getBytes(UTF_8)
      )
      entry.copy(disposition = storymodel4s.view.WorkspaceDisposition.Supplied(replacement))
    }
    val rebuilt = storymodel4s.view.WorkspaceManifest
      .of(
        manifest.schemaVersion,
        entries,
        entries.map(e => e.path -> content(e.role).getBytes(UTF_8).toVector).toMap
      )
      .toOption
      .get
    WorkspaceArchiveCodec.encode(rebuilt).toOption.get

  private def swappedMembers(packet: String): String =
    val json = Canonical.parse(packet).fold(e => fail(e.toString), identity)
    val entries = json.hcursor.get[Vector[Json]]("entries").fold(e => fail(e.toString), identity)
    val mappings = entries.zipWithIndex.filter { (entry, _) =>
      entry.hcursor.downField("role").get[String]("kind").contains("Mapping")
    }
    assertEquals(mappings.size, 2)
    val swapped = entries.zipWithIndex.map { (entry, index) =>
      mappings.find(_._2 == index) match
        case Some((_, _)) =>
          val other = mappings.find(_._2 != index).get._1.hcursor.downField("role").focus.get
          entry.mapObject(_.add("role", other))
        case None => entry
    }
    json.mapObject(_.add("entries", Json.fromValues(swapped))).noSpaces

  test("admitted recalls publish byte-stable packets, reuse exact cache editions, and resume") {
    temporary { root =>
      writeBasic(root, admitted = true, Vector("p1", "p2"))
      val cache = root.resolve("cache")
      val output = root.resolve("out")
      val args = runArgs(root, cache, output)
      val first = invoke(args*)
      assertEquals(first._1, 0, first._3.mkString("\n"))
      assertEquals(first._3, Vector.empty)
      val initial = outputBytes(output)
      assertEquals(invoke((args :+ "--resume")*)._1, 0)
      assertEquals(outputBytes(output), initial)
      assertEquals(childDirectories(cache), 1)
      val manifest = Canonical.parse(Files.readString(output.resolve("manifest.json"))).toOption.get
      assertEquals(
        manifest.hcursor.downField("metadata").get[Vector[Json]]("outcomes").toOption.get.size,
        2
      )

      val edition = manifest.hcursor.downField("metadata").get[String]("sourceEdition").toOption.get
      val cachedSource = Json.obj(
        "format" -> Json.fromString("checked-text"),
        "path" -> Json.fromString(s"cache/$edition/source.json"),
        "kind" -> Json.fromString(SurfaceUnitKind.Sentence.toString)
      )
      Files.delete(root.resolve("source.txt"))
      write(root, "study.json", study(cachedSource, Vector(("p3", "s1", "recall.txt", true))))
      val later = root.resolve("later")
      val second = invoke(runArgs(root, cache, later)*)
      assertEquals(second._1, 0, second._3.mkString("\n"))
      assertEquals(childDirectories(cache), 1)
      assert(SurfaceWorkspaceCodecs.decode(workspace(later)).isRight)
    }
  }

  test(
    "corrupt foreign and incomplete publications refuse resume, while a new source creates a cache edition"
  ) {
    temporary { root =>
      writeBasic(root, admitted = true, Vector("p1"))
      val cache = root.resolve("cache")
      val output = root.resolve("out")
      val args = runArgs(root, cache, output)
      assertEquals(invoke(args*)._1, 0)
      Files.writeString(packet(output), "corrupt"): Unit
      assertEquals(invoke((args :+ "--resume")*)._1, 2)

      write(root, "source.txt", "Bob left. Anna arrived.")
      val changed = root.resolve("changed")
      val changedArgs = runArgs(root, cache, changed)
      assertEquals(invoke(changedArgs*)._1, 0)
      assertEquals(childDirectories(cache), 2)

      val foreign = root.resolve("foreign")
      Files.createDirectory(foreign): Unit
      outputBytes(changed).foreach { (name, bytes) =>
        Files.write(foreign.resolve(name), bytes.toArray): Unit
      }
      write(root, "source.txt", sourceText)
      assertEquals(invoke((args.updated(4, foreign.toString) :+ "--resume")*)._1, 2)

      val partial = root.resolve("partial")
      Files.createDirectory(partial): Unit
      Files.writeString(partial.resolve("member.json"), "{}"): Unit
      assertEquals(invoke((changedArgs.updated(4, partial.toString) :+ "--resume")*)._1, 2)
    }
  }

  test("a failed participant remains in the completion manifest beside a successful packet") {
    temporary { root =>
      writeBasic(root, admitted = true, Vector("p1"))
      write(
        root,
        "study.json",
        study(
          textSource(),
          Vector(("p1", "s1", "recall.txt", true), ("p2", "s1", "missing.txt", true))
        )
      )
      val output = root.resolve("out")
      assertEquals(invoke(runArgs(root, root.resolve("cache"), output)*)._1, 3)
      val manifest = Canonical.parse(Files.readString(output.resolve("manifest.json"))).toOption.get
      val outcomes =
        manifest.hcursor.downField("metadata").get[Vector[Json]]("outcomes").toOption.get
      assertEquals(
        outcomes.map(_.hcursor.get[String]("participant").toOption.get),
        Vector("p1", "p2")
      )
      assert(outcomes.head.hcursor.get[String]("workspace").isRight)
      assert(outcomes(1).hcursor.get[String]("workspace").isLeft)
      assert(outcomes(1).hcursor.get[String]("failure").isRight)
      assertEquals(outcomes(1).hcursor.get[Vector[Json]]("units").toOption.get, Vector.empty)
      assert(SurfaceWorkspaceCodecs.decode(workspace(output)).isRight)
    }
  }

  test("local-only recalls refuse publication before cache or output creation") {
    temporary { root =>
      writeBasic(root, admitted = false, Vector("private"))
      val cache = root.resolve("cache")
      val output = root.resolve("out")
      val result = invoke(runArgs(root, cache, output)*)
      assertEquals(result._1, 2)
      assert(!Files.exists(cache))
      assert(!Files.exists(output))
    }
  }

  test(
    "timed annotation source retains its exact artifact, support, and record bytes in the packet"
  ) {
    temporary { root =>
      write(root, "recall.txt", admittedRecall)
      write(root, "mapping.json", mapping)
      write(
        root,
        "parts.json",
        """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
      )
      write(
        root,
        "annotations.csv",
        TimedAnnotationInput.Header.mkString(",") + "\n" +
          "1,Anna arrived.,a,10,20,1,Scene,,Anna\n" +
          "2,Bob left.,a,30,40,1,Scene,,Bob\n"
      )
      write(root, "study.json", study(timedSource, Vector(("p1", "s1", "recall.txt", true))))
      val cache = root.resolve("cache")
      val output = root.resolve("out")
      assertEquals(invoke(runArgs(root, cache, output)*)._1, 0)
      val imported =
        SurfaceWorkspaceCodecs.decode(workspace(output)).fold(e => fail(e.toString), identity)
      val prepared =
        StudyCli.load(root.resolve("study.json").toString).fold(e => fail(e.toString), identity)
      val batch = MappingPipeline
        .batch(prepared, MappingCli.config(mapping).fold(e => fail(e.toString), identity))
        .fold(e => fail(e.toString), identity)
      val run = batch.outcomes.head.run.getOrElse(fail(batch.outcomes.head.failure.toString))
      assertEquals(imported.source.digest, prepared.source.representation.digest)
      assertEquals(imported.sourceArtifact, prepared.source.artifact)
      assertEquals(
        imported.policies.map(policy => policy.id.value -> policy.bytes).toMap,
        Map(
          "reference" -> storymodel4s.codec.MappingCodecs.encode(run.reference),
          "reconstruction" -> storymodel4s.codec.MappingCodecs.encode(run.reconstruction)
        )
      )
      val targets = imported.policies.head.document.hcursor
        .downField("source")
        .get[Vector[Json]]("targets")
        .toOption
        .get
      val leafIntervals =
        targets.filter(_.hcursor.get[Int]("level").contains(0)).flatMap { target =>
          target.hcursor
            .downField("support_status")
            .downField("source_support")
            .get[Vector[Json]]("anchors")
            .toOption
            .get
            .flatMap { anchor =>
              anchor.hcursor.get[Vector[Json]]("intervals").toOption.get.map { interval =>
                (
                  interval.hcursor.get[String]("start_tick").toOption.get,
                  interval.hcursor.get[String]("end_exclusive_tick").toOption.get
                )
              }
            }
        }
      assertEquals(leafIntervals.sorted, Vector("10" -> "20", "30" -> "40"))
    }
  }

  test("mapping-member swaps and a valid denied-inspection archive refuse imported inspection") {
    temporary { root =>
      writeBasic(root, admitted = true, Vector("p1"))
      val output = root.resolve("out")
      assertEquals(invoke(runArgs(root, root.resolve("cache"), output)*)._1, 0)
      val actual = workspace(output)
      assert(SurfaceWorkspaceCodecs.decode(actual).isRight)
      assert(SurfaceWorkspaceCodecs.decode(swappedMembers(actual)).isLeft)
      assert(
        SurfaceWorkspaceCodecs
          .decode(permissions(actual, WorkspaceContentGrant.Denied, WorkspaceContentGrant.Granted))
          .isLeft
      )
    }
  }

  test("inspection permission preserves identity while export permission refuses copying") {
    temporary { root =>
      writeBasic(root, admitted = true, Vector("p1"))
      val output = root.resolve("out")
      assertEquals(invoke(runArgs(root, root.resolve("cache"), output)*)._1, 0)
      val denied =
        permissions(workspace(output), WorkspaceContentGrant.Granted, WorkspaceContentGrant.Denied)
      val first = SurfaceWorkspaceCodecs.decode(denied).toOption.get
      val second = SurfaceWorkspaceCodecs.decode(denied).toOption.get
      assertEquals(first.identity, second.identity)
      assertEquals(first.hashCode, second.hashCode)
      assertEquals(first, second)
      assertEquals(first.exportBytes, Left(storymodel4s.view.WorkspaceRefusal.PermissionDenied))
    }
  }

  test("rehashed malformed outcome fields and foreign receipts refuse before inspection") {
    temporary { root =>
      writeBasic(root, admitted = true, Vector("p1"))
      val output = root.resolve("out")
      assertEquals(invoke(runArgs(root, root.resolve("cache"), output)*)._1, 0)
      val actual = workspace(output)
      def outcomes(f: Json => Json)(record: Json): Json = record.mapObject(
        _.add(
          "outcomes",
          Json.fromValues(record.hcursor.get[Vector[Json]]("outcomes").toOption.get.map(f))
        )
      )
      val mutations = Vector[Json => Json](
        outcomes(_.mapObject(_.remove("processing_status"))),
        outcomes(
          _.mapObject(
            _.add(
              "decision",
              Json.obj(
                "status" -> Json.fromString("present"),
                "value" -> Json.obj(
                  "basis" -> Json.obj("measure_kind" -> Json.fromString("ModelPosterior"))
                )
              )
            )
          )
        ),
        outcomes(o =>
          o.mapObject(
            _.add(
              "mapping_links",
              Json.fromValues(
                o.hcursor
                  .get[Vector[Json]]("mapping_links")
                  .toOption
                  .get
                  .map(_.mapObject(_.add("destination", Json.fromString("seg:foreign"))))
              )
            )
          )
        ),
        record =>
          record.mapObject(
            _.add(
              "stage_assumption_receipts",
              Json.fromValues(
                record.hcursor.get[Vector[Json]]("stage_assumption_receipts").toOption.get.map {
                  row =>
                    if row.hcursor.downField("provenance").get[String]("status").contains("derived")
                    then
                      row.mapObject(
                        _.add(
                          "provenance",
                          row.hcursor
                            .downField("provenance")
                            .focus
                            .get
                            .mapObject(p =>
                              p.add(
                                "receipt",
                                p("receipt").get
                                  .mapObject(_.add("stage", Json.fromString("Evidence")))
                              )
                            )
                        )
                      )
                    else row
                }
              )
            )
          )
      )
      assert(SurfaceWorkspaceCodecs.decode(actual).isRight)
      mutations.zipWithIndex.foreach { (mutation, index) =>
        assert(
          SurfaceWorkspaceCodecs.decode(changedRecord(actual)(mutation)).isLeft,
          s"mutation $index"
        )
      }
    }
  }

  test("rehashed decision mass and choice must match the retained alternatives") {
    temporary { root =>
      writeBasic(root, admitted = true, Vector("p1"))
      val output = root.resolve("out")
      assertEquals(invoke(runArgs(root, root.resolve("cache"), output)*)._1, 0)
      val actual = workspace(output)
      def decision(change: Json => Json)(record: Json): Json = record.mapObject(
        _.add(
          "outcomes",
          Json.fromValues(record.hcursor.get[Vector[Json]]("outcomes").toOption.get.map { outcome =>
            val original = outcome.hcursor.downField("decision").focus.get
            outcome.mapObject(
              _.add("decision", original.mapObject(o => o.add("value", change(o("value").get))))
            )
          })
        )
      )
      val absent =
        Json.obj("status" -> Json.fromString("absent"), "reason" -> Json.fromString("forged"))
      val mutations = Vector[Json => Json](
        d =>
          d.mapObject(
            _.add(
              "decoded_target_mass",
              d.hcursor
                .downField("decoded_target_mass")
                .focus
                .get
                .mapObject(_.add("value", Json.fromString("0x4000000000000000")))
            )
          ),
        _.mapObject(_.add("decoded_target_id", absent)),
        d =>
          d.mapObject(
            _.add(
              "raw_argmax",
              d.hcursor
                .downField("raw_argmax")
                .focus
                .get
                .mapObject(o =>
                  o.add(
                    "value",
                    o("value").get.mapObject(_.add("value", Json.fromString("0x4000000000000000")))
                  )
                )
            )
          ),
        _.mapObject(
          _.add("decoded_target_mass", Json.obj("status" -> Json.fromString("no-decision")))
        )
      )
      assert(SurfaceWorkspaceCodecs.decode(actual).isRight)
      mutations.zipWithIndex.foreach { (mutation, index) =>
        assert(
          SurfaceWorkspaceCodecs.decode(changedRecord(actual)(decision(mutation))).isLeft,
          s"decision mutation $index"
        )
      }
    }
  }

/** Produces only synthetic packets for the real StoryAtlas imported-artifact qualification. */
object StudyWorkspaceWitness:
  private val defaultRevision = "a" * 40
  private val source = "Anna arrived. Bob left."
  private val recall = "Anna arrived."

  private def write(root: Path, name: String, value: String): Unit =
    Files.writeString(root.resolve(name), value, UTF_8): Unit

  private def invoke(args: List[String]): Unit =
    val errors = Vector.newBuilder[String]
    val status = StudyWorkspaceCli.run(args, _ => (), s => { errors.addOne(s): Unit })
    require(status == 0, errors.result().mkString("\n"))

  private def config(source: Json): String =
    Canonical.printer
      .copy(dropNullValues = false)
      .print(
        Json.obj(
          "schemaVersion" -> Json.fromString(StudyPreparation.Schema),
          "source" -> source,
          "profile" -> Json.fromString(StudyPreparation.RecallProfile),
          "recalls" -> Json.fromValues(Vector("fixture-a", "fixture-b").map { participant =>
            Json.obj(
              "participant" -> Json.fromString(participant),
              "session" -> Json.fromString("s1"),
              "path" -> Json.fromString("recall.txt"),
              "timing" -> Json.Null,
              "admission" -> Json.obj("kind" -> Json.fromString("project-synthetic-control"))
            )
          })
        )
      )

  /** `StudyWorkspaceWitness OUTPUT_DIR [PRODUCER_SHA]` writes text/timed packet paths in metadata.
    */
  def main(args: Array[String]): Unit =
    require(args.length == 1 || args.length == 2, "StudyWorkspaceWitness OUTPUT_DIR [PRODUCER_SHA]")
    val root = Path.of(args(0))
    val revision =
      args.lift(1).orElse(sys.props.get("storymodel4s.producerRevision")).getOrElse(defaultRevision)
    require(revision.matches("[0-9a-f]{40}"), "producer revision must be a lowercase 40-hex SHA")
    Files.createDirectory(root): Unit
    write(root, "recall.txt", recall)
    write(root, "mapping.json", MappingCli.Template)

    write(root, "source.txt", source)
    write(
      root,
      "text-study.json",
      config(
        Json.obj(
          "format" -> Json.fromString("text"),
          "path" -> Json.fromString("source.txt"),
          "kind" -> Json.fromString(SurfaceUnitKind.Sentence.toString)
        )
      )
    )
    val textCache = root.resolve("text-cache")
    val textOut = root.resolve("text-workspace")
    invoke(
      List(
        "run",
        root.resolve("text-study.json").toString,
        root.resolve("mapping.json").toString,
        textCache.toString,
        textOut.toString,
        revision
      )
    )

    write(
      root,
      "parts.json",
      """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
    )
    write(
      root,
      "annotations.csv",
      TimedAnnotationInput.Header.mkString(",") + "\n" +
        "1,Anna arrived.,a,10,20,1,Scene,,Anna\n2,Bob left.,a,30,40,1,Scene,,Bob\n"
    )
    write(
      root,
      "timed-study.json",
      config(
        Json.obj(
          "format" -> Json.fromString("timed-annotations"),
          "path" -> Json.fromString("annotations.csv"),
          "parts" -> Json.fromString("parts.json")
        )
      )
    )
    val timedCache = root.resolve("timed-cache")
    val timedOut = root.resolve("timed-workspace")
    invoke(
      List(
        "run",
        root.resolve("timed-study.json").toString,
        root.resolve("mapping.json").toString,
        timedCache.toString,
        timedOut.toString,
        revision
      )
    )

    def packets(output: Path): Vector[Path] =
      val stream = Files.list(output)
      try
        val values = stream
          .iterator()
          .asScala
          .filter(_.getFileName.toString.endsWith("-workspace.json"))
          .toVector
        require(values.size == 2, s"expected two workspaces in $output")
        values.sortBy(_.getFileName.toString)
      finally stream.close()
    write(
      root,
      "workspace-fixtures.json",
      Canonical.print(
        Json.obj(
          "schemaVersion" -> Json.fromString("study-workspace-witness/v1"),
          "text" -> Json.fromString(packets(textOut).head.toAbsolutePath.toString),
          "timed" -> Json.fromString(packets(timedOut).head.toAbsolutePath.toString),
          "producerRevision" -> Json.fromString(revision),
          "textCohort" -> Json.fromValues(
            packets(textOut).map(p => Json.fromString(p.toAbsolutePath.toString))
          ),
          "timedCohort" -> Json.fromValues(
            packets(timedOut).map(p => Json.fromString(p.toAbsolutePath.toString))
          )
        )
      )
    )
