package storymodel4s.pipeline

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import io.circe.Json
import munit.FunSuite
import storymodel4s.core.{Checksum, DomainError, EditionId, ObservationAuthority, SourceKind}
import storymodel4s.codec.{MediaCaptionSourceCodec, SurfaceWorkspaceCodecs}
import storymodel4s.media.*

/** Synthetic replay courts exercise multipart membership independently of extraction execution. */
class VideoSourceSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A =
    value.fold(e => fail(e.toString), identity)
  private def resource(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/f0/$name")
    assert(in != null, s"missing test fixture $name")
    try in.readAllBytes()
    finally in.close()
  private def json(name: String): Json =
    io.circe.parser.parse(new String(resource(name), UTF_8)).toOption.get
  private def field(j: Json, name: String, value: Json): Json = j.mapObject(_.add(name, value))
  private val edition = right(EditionId.from("multipart-test"))

  private final class Fixture(val root: Path, var document: Json):
    def bytes: Array[Byte] = document.noSpaces.getBytes(UTF_8)
    def read: Either[DomainError, CaptionPackage] =
      CaptionPackage.load(root, bytes, Checksum.ofBytes(bytes), edition)
    def repin(name: String, bytes: Array[Byte]): Unit =
      val _ = Files.write(root.resolve(name), bytes)
      val pins = document.hcursor.downField("files").focus.get
      document =
        field(document, "files", field(pins, name, Json.fromString(Checksum.ofBytes(bytes).hex)))
    def change(name: String)(f: Json => Json): Unit =
      val before = io.circe.parser.parse(Files.readString(root.resolve(name))).toOption.get
      repin(name, f(before).noSpaces.getBytes(UTF_8))

  private def withFixture(test: Fixture => Unit): Unit =
    val root = Files.createTempDirectory("caption-package-court-")
    try test(build(root))
    finally
      val paths = Files.walk(root)
      try
        paths.iterator().asScala.toVector.reverse.foreach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  private def build(root: Path): Fixture =
    var pins = Map.empty[String, Json]
    def put(name: String, bytes: Array[Byte]): Unit =
      val _ = Files.createDirectories(root.resolve(name).getParent)
      val _ = Files.write(root.resolve(name), bytes)
      pins = pins.updated(name, Json.fromString(Checksum.ofBytes(bytes).hex))
    def putJson(name: String, value: Json): Unit = put(name, value.noSpaces.getBytes(UTF_8))
    val baseline = right(
      CaptionRequest.parse(new String(resource("f0-v1.caption-request.json"), UTF_8))
    )
    val baseEnvelope = right(
      CaptionEnvelope.parse(new String(resource("f0-v1.caption-envelope.json"), UTF_8))
    )
    val partDefs = Vector(("a", Vector(0, 4, 9), Vector(0, 1)), ("b", Vector(2, 13), Vector(0, 1)))
    val parts = partDefs.map { (name, selected, examined) =>
      val localId = s"local-$name"
      val manifestBase = field(
        field(json("f0-v1.manifest.json"), "fixtureId", Json.fromString(localId)),
        "file",
        Json.fromString(s"$name/media.mov")
      )
      // Part b is a counterfactual replay clock, not an executed probe of the original video.
      val manifestJson =
        if name == "a" then manifestBase
        else
          field(
            manifestBase,
            "streams",
            Json.arr(manifestBase.hcursor.downField("streams").focus.get.asArray.get.map { stream =>
              if stream.hcursor.get[Int]("index").toOption.contains(0) then
                field(stream, "timebase", Json.fromString("1/48000"))
              else stream
            }*)
          )
      val manifest = right(FixtureManifest.parse(manifestJson.noSpaces))
      val peBase = field(
        field(json("f0-v1.ffprobe-envelope.json"), "fixtureId", Json.fromString(localId)),
        "stdoutFile",
        Json.fromString(s"$name/probe.json")
      )
      val stdoutBase = json("f0-v1.ffprobe-stdout.json")
      val stdoutJson =
        if name == "a" then stdoutBase
        else
          field(
            field(
              stdoutBase,
              "streams",
              Json.arr(stdoutBase.hcursor.downField("streams").focus.get.asArray.get.map { stream =>
                if stream.hcursor.get[Int]("index").toOption.contains(0) then
                  field(
                    field(stream, "time_base", Json.fromString("1/48000")),
                    "start_pts",
                    Json.fromLong(1000L)
                  )
                else stream
              }*)
            ),
            "packets",
            Json.arr(stdoutBase.hcursor.downField("packets").focus.get.asArray.get.map { packet =>
              if packet.hcursor.get[Int]("stream_index").toOption.contains(0) then
                field(
                  field(
                    packet,
                    "pts",
                    Json.fromLong(packet.hcursor.get[Long]("pts").toOption.get + 1000L)
                  ),
                  "dts",
                  Json.fromLong(packet.hcursor.get[Long]("dts").toOption.get + 1000L)
                )
              else packet
            }*)
          )
      val stdout = stdoutJson.noSpaces.getBytes(UTF_8)
      val peJson = field(
        field(peBase, "stdoutSha256", Json.fromString(Checksum.ofBytes(stdout).hex)),
        "tool",
        field(
          peBase.hcursor.downField("tool").focus.get,
          "versionLine",
          Json.fromString("synthetic replay clock court; not an executed probe")
        )
      )
      val pe = right(ProbeEnvelope.parse(peJson.noSpaces))
      val probe = right(
        MediaProbe.join(
          manifest,
          right(manifest.verify(resource("f0-v1.mov"))),
          pe.tool,
          pe.args,
          right(FfprobeJson.parse(new String(stdout, UTF_8)))
        )
      )
      val fe =
        right(FramesEnvelope.parse(new String(resource("f0-v1.frames-envelope.json"), UTF_8)))
      // Project-authored colour bytes; this is a declared replay, not an extractor run.
      val frameBytes = selected.flatMap { ordinal =>
        val generated = manifest.streams.head.declaredFrames.get.expectedPts(ordinal) / 1000
        val bgr =
          if generated < 16 then Vector[Byte](30, 30, 200.toByte)
          else if generated < 32 then Vector[Byte](30, 200.toByte, 30)
          else Vector[Byte](200.toByte, 30, 30)
        Vector.fill(32 * 18)(bgr).flatten
      }.toArray
      val sha = Checksum.ofBytes(frameBytes)
      val frames = right(
        SampledFrameSet.join(
          probe,
          0,
          fe.geometry,
          fe.tool,
          fe.args,
          selected,
          frameBytes.length.toLong,
          sha
        )
      )
      val extent = right(CaptionExtent.of("cross-part", examined))
      val request = right(
        CaptionRequest.issue(
          frames,
          Vector(extent),
          baseline.model,
          baseline.recipe,
          s"request-$name"
        )
      )
      val outcome0 = json("f0-v1.caption-outcome.json")
      val result0 = outcome0.hcursor.downField("extents").focus.get.asArray.get.head
      val result = field(
        field(
          field(result0, "id", Json.fromString(extent.id)),
          "ordinals",
          Json.arr(examined.map(Json.fromInt)*)
        ),
        "imageGridThw",
        Json.arr(examined.map(_ => Json.arr(Json.fromInt(1), Json.fromInt(6), Json.fromInt(12)))*)
      )
      val outcome = field(
        field(
          field(
            field(outcome0, "requestId", Json.fromString(request.requestId)),
            "frames",
            field(
              field(
                outcome0.hcursor.downField("frames").focus.get,
                "count",
                Json.fromInt(selected.size)
              ),
              "sha256",
              Json.fromString(sha.hex)
            )
          ),
          "extents",
          Json.arr(result)
        ),
        "coverage",
        Json.obj("requested" -> Json.fromInt(1), "observed" -> Json.fromInt(1))
      )
      val captionEnvelope = field(
        field(
          field(
            field(json("f0-v1.caption-envelope.json"), "fixtureId", Json.fromString(localId)),
            "requestFile",
            Json.fromString(s"$name/request.json")
          ),
          "outcomeFile",
          Json.fromString(s"$name/outcome.json")
        ),
        "outcomeSha256",
        Json.fromString(Checksum.ofText(outcome.noSpaces).hex)
      )
      val frameEnvelope = field(
        field(
          field(json("f0-v1.frames-envelope.json"), "fixtureId", Json.fromString(localId)),
          "frames",
          Json.obj(
            "count" -> Json.fromInt(selected.size),
            "width" -> Json.fromInt(32),
            "height" -> Json.fromInt(18),
            "pixelFormat" -> Json.fromString("bgr24"),
            "byteLength" -> Json.fromLong(frameBytes.length.toLong),
            "sha256" -> Json.fromString(sha.hex)
          )
        ),
        "inputSha256",
        Json.fromString(manifest.sha256.hex)
      )
      assertEquals(
        baseEnvelope.worker,
        right(CaptionEnvelope.parse(captionEnvelope.noSpaces)).worker
      )
      put(s"$name/media.mov", resource("f0-v1.mov"))
      putJson(s"$name/manifest.json", manifestJson)
      putJson(s"$name/probe-envelope.json", peJson)
      put(s"$name/probe.json", stdout)
      putJson(s"$name/frames-envelope.json", frameEnvelope)
      put(s"$name/frames.bgr", frameBytes)
      putJson(s"$name/caption-envelope.json", captionEnvelope)
      put(
        s"$name/request.json",
        request.render(s"$name/frames.bgr", "unused-model").getBytes(UTF_8)
      )
      putJson(s"$name/outcome.json", outcome)
      Json.obj(
        "occurrence" -> Json.fromString(name),
        "manifest" -> Json.fromString(s"$name/manifest.json"),
        "probeEnvelope" -> Json.fromString(s"$name/probe-envelope.json"),
        "framesEnvelope" -> Json.fromString(s"$name/frames-envelope.json"),
        "framesFile" -> Json.fromString(s"$name/frames.bgr"),
        "captionEnvelope" -> Json.fromString(s"$name/caption-envelope.json"),
        "selectedPresentationOrdinals" -> Json.arr(selected.map(Json.fromInt)*)
      )
    }
    new Fixture(
      root,
      Json.obj(
        "schema" -> Json.fromString(CaptionPackage.Schema),
        "schemaVersion" -> Json.fromInt(2),
        "edition" -> Json.fromString(edition.value),
        "files" -> Json.obj(pins.toVector*),
        "parts" -> Json.arr(parts*),
        "extents" -> Json.arr(
          Json.obj(
            "id" -> Json.fromString("cross-part"),
            "ordinals" -> Json.arr(Vector(0, 1, 3, 4).map(Json.fromInt)*)
          )
        )
      )
    )

  test(
    "verified multipart replay enters study and workspace with native clocks and Draft coverage"
  ):
    withFixture { f =>
      val pack = right(f.read)
      val imported = right(VideoSourceInput.project(pack))
      assertEquals(imported.authority, ObservationAuthority.Draft)
      assertEquals(imported.view.parts.values.map(_.sourceKind).toSet, Set(SourceKind.FilmEdition))
      assertEquals(imported.view.parts("a").primaryAxis, pack.parts(0).captions.axis)
      assertEquals(imported.view.parts("b").primaryAxis, pack.parts(1).captions.axis)
      assertEquals(imported.examinedOrdinals("a"), Some(Vector(0, 4)))
      assertEquals(imported.sampledOrdinals("a"), Some(Vector(0, 4, 9)))
      assertEquals(imported.unexaminedOrdinalRanges("a"), Some(Vector(1 until 4, 5 until 45)))
      assertEquals(
        imported.view.segments.map(_.text),
        pack.parts.flatMap(_.captions.proposals.map(_.text))
      )
      val _ = Files.writeString(f.root.resolve("source.json"), imported.artifact)
      val _ = Files.writeString(f.root.resolve("recall.txt"), "Anna arrived.")
      val config = StudyPreparation.Config(
        StudyPreparation.SourceInput.CheckedVideo("source.json"),
        Vector(
          StudyPreparation.RecallInput(
            "p1",
            "s1",
            "recall.txt",
            None,
            StudyPreparation.Admission.ProjectSyntheticControl
          )
        ),
        StudyPreparation.RecallProfile
      )
      val prepared = right(StudyPreparation.prepare(f.root, config))
      assertEquals(prepared.source.mediaCaptions, Some(imported))
      val batch =
        right(MappingPipeline.batch(prepared, right(MappingCli.config(MappingCli.Template))))
      val artifacts = right(StudyWorkspaceProducer.artifacts(batch, "a" * 40))._1
      val packet = artifacts.find(_._1.endsWith("workspace.json")).get._2
      sys.props.get("storymodel4s.videoWorkspace.fixture").foreach { path =>
        val _ = Files.writeString(Path.of(path), packet)
      }
      val readback = right(SurfaceWorkspaceCodecs.decode(packet))
      assertEquals(readback.sourceKind, SurfaceWorkspaceCodecs.SourceKind.VideoCaptions)
      assertEquals(readback.source.digest, imported.view.representation.digest)
      assertEquals(readback.mediaCaptions, Some(imported))
      assertEquals(
        right(MediaCaptionSourceCodec.decode(imported.artifact)).evidence,
        readback.mediaCaptions.get.evidence
      )
    }

  test("public replay command publishes a prepared source and refuses wrong caller pins"):
    withFixture { f =>
      val _ = Files.write(f.root.resolve("manifest.json"), f.bytes)
      val output = f.root.resolve("delivery")
      val args = Vector(
        "replay",
        f.root.toString,
        Checksum.ofBytes(f.bytes).hex,
        edition.value,
        output.toString
      )
      assert(VideoSourceCli.run(args.updated(2, Checksum.ofText("foreign").hex)).isLeft)
      assert(!Files.exists(output))
      right(VideoSourceCli.run(args))
      val imported =
        right(MediaCaptionSourceCodec.decode(Files.readString(output.resolve("source.json"))))
      assertEquals(imported.examinedOrdinals("a"), Some(Vector(0, 4)))
      val before = Files.readString(output.resolve("completion.json"))
      assert(VideoSourceCli.run(args).isLeft)
      assertEquals(Files.readString(output.resolve("completion.json")), before)
    }
