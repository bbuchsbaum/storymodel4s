package storymodel4s.probe

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.core.{Checksum, DomainError}
import storymodel4s.media.*

/** Live public F0 courts compare execution with generator-derived timestamps and pixels. */
class SampledExtractionSuite extends FunSuite:
  private def right[A](value: Either[DomainError, A]): A =
    value.fold(e => fail(e.message), identity)
  private def resource(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/f0/$name")
    assert(in != null)
    try in.readAllBytes()
    finally in.close()
  private def text(name: String): String = new String(resource(name), UTF_8)
  private val geometry = PictureGeometry(32, 18)
  private val selected = Vector(0, 4, 5, 14, 29, 44)
  private def probe(shift: Long = 0L): MediaProbe =
    val manifest = right(FixtureManifest.parse(text("f0-v1.manifest.json")))
    val envelope = right(ProbeEnvelope.parse(text("f0-v1.ffprobe-envelope.json")))
    val original = right(FfprobeJson.parse(text("f0-v1.ffprobe-stdout.json")))
    val changed = original.copy(packets =
      original.packets.map(p =>
        if p.streamIndex == 0 then p.copy(pts = p.pts.map(_ + shift)) else p
      )
    )
    right(MediaProbe.join(manifest, manifest.sha256, envelope.tool, envelope.args, changed))
  private def withInput(f: (Path, Path, Path) => Unit): Unit =
    val root = Files.createTempDirectory("sampled-extraction-court-")
    try
      val input = root.resolve("f0.mov")
      val _ = Files.write(input, resource("f0-v1.mov"))
      f(input, root.resolve("selected.bgr"), root)
    finally
      val paths = Files.walk(root)
      try
        paths.iterator().asScala.toVector.reverse.foreach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()
  private def tool: Path =
    assume(Ffmpeg.locate().nonEmpty, "live court requires an installed ffmpeg")
    Ffmpeg.locate().get

  test("executed selection preserves VFR PTS and generator-known BGR pixels"):
    withInput { (input, output, _) =>
      val result = right(SampledExtraction.run(tool, input, output, probe(), 0, geometry, selected))
      assertEquals(result.observedPts, Vector(0L, 4000L, 7000L, 16000L, 32000L, 47000L))
      assertEquals(result.frames.selected, selected)
      val colors = Vector(
        (30, 30, 200),
        (30, 30, 200),
        (30, 30, 200),
        (30, 200, 30),
        (200, 30, 30),
        (200, 30, 30)
      )
      val expected = colors.flatMap { (b, g, r) =>
        Vector.fill(32 * 18)(Vector(b.toByte, g.toByte, r.toByte)).flatten
      }.toArray
      assertEquals(Files.readAllBytes(output).toVector, expected.toVector)
      assertEquals(result.frames.framesSha256, Checksum.ofBytes(expected))
      assert(result.transcript.contains("showinfo"))
      val envelope = right(FramesEnvelope.parse(result.framesEnvelopeJson))
      val execution = io.circe.parser.parse(result.executionJson).toOption.get.hcursor
      assertEquals(
        execution.get[Vector[Int]]("selectedPresentationOrdinals").toOption.get,
        selected
      )
      assertEquals(execution.get[Vector[Long]]("observedPts").toOption.get, result.observedPts)
      val replay = right(
        SampledFrameSet.join(
          result.frames.probe,
          envelope.streamIndex,
          envelope.geometry,
          envelope.tool,
          envelope.args,
          selected,
          envelope.byteLength,
          envelope.framesSha256
        )
      )
      assertEquals(replay.identity, result.frames.identity)
      assertEquals(result.frames.tool, right(ToolRealization.observe("ffmpeg", tool)))
    }

  test("wrong probe PTS refuses despite correct frame count and removes partial output"):
    withInput { (input, output, root) =>
      val result = SampledExtraction.run(tool, input, output, probe(1L), 0, geometry, selected)
      assert(result.left.toOption.exists(_.message.contains("timestamps differ")))
      assert(!Files.exists(output))
      val entries = Files.list(root)
      try assertEquals(entries.iterator().asScala.map(_.getFileName.toString).toSet, Set("f0.mov"))
      finally entries.close()
    }

  test("existing output is preserved"):
    withInput { (input, output, _) =>
      val _ = Files.writeString(output, "owned by someone else")
      assert(
        SampledExtraction
          .run(Path.of("absent-tool"), input, output, probe(), 0, geometry, selected)
          .isLeft
      )
      assertEquals(Files.readString(output), "owned by someone else")
    }

  test("wrong input bytes refuse before attempting tool execution"):
    withInput { (input, output, _) =>
      val _ = Files.writeString(input, "wrong input")
      val result =
        SampledExtraction.run(Path.of("absent-tool"), input, output, probe(), 0, geometry, selected)
      assert(result.left.toOption.exists(_.message.contains("input bytes differ")))
      assert(!Files.exists(output))
    }

  test("selection membership refuses before creating an output"):
    withInput { (input, output, _) =>
      Vector(Vector.empty, Vector(-1), Vector(1, 1), Vector(2, 1), Vector(45)).foreach {
        selection =>
          assert(SampledExtraction.run(tool, input, output, probe(), 0, geometry, selection).isLeft)
          assert(!Files.exists(output))
      }
    }

  test("missing executable is a typed refusal"):
    withInput { (input, output, root) =>
      assert(
        SampledExtraction
          .run(root.resolve("absent-tool"), input, output, probe(), 0, geometry, selected)
          .isLeft
      )
      assert(!Files.exists(output))
    }

  private def command(args: Vector[String], root: Path): Unit =
    val log = root.resolve("generator.log")
    val process =
      new ProcessBuilder(args*).redirectErrorStream(true).redirectOutput(log.toFile).start()
    val completed = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
    if !completed then { val _ = process.destroyForcibly() }
    assert(completed, "fixture generator timed out")
    assertEquals(process.exitValue(), 0, Files.readString(log))

  test("display rotation cannot silently change non-square stored pixel geometry"):
    withInput { (_, output, root) =>
      val rgb = (0 until 32 * 18)
        .flatMap(i => Vector((i % 251).toByte, ((i * 3) % 251).toByte, ((i * 7) % 251).toByte))
        .toArray
      val raw = root.resolve("pattern.rgb")
      val base = root.resolve("pattern.mov")
      val rotated = root.resolve("rotation.mov")
      val _ = Files.write(raw, rgb)
      command(
        Vector(
          tool.toString,
          "-v",
          "error",
          "-f",
          "rawvideo",
          "-pix_fmt",
          "rgb24",
          "-video_size",
          "32x18",
          "-framerate",
          "24",
          "-i",
          raw.toString,
          "-c:v",
          "rawvideo",
          "-video_track_timescale",
          "24000",
          "-output_ts_offset",
          "0.5",
          base.toString
        ),
        root
      )
      command(
        Vector(
          tool.toString,
          "-v",
          "error",
          "-copyts",
          "-display_rotation",
          "90",
          "-i",
          base.toString,
          "-c",
          "copy",
          rotated.toString
        ),
        root
      )
      val bytes = Files.readAllBytes(rotated)
      val j = io.circe.parser.parse(text("f0-v1.manifest.json")).toOption.get
      val m = j.mapObject(
        _.add("sha256", io.circe.Json.fromString(Checksum.ofBytes(bytes).hex))
          .add("byteLength", io.circe.Json.fromLong(bytes.length.toLong))
          .add(
            "streams",
            io.circe.Json.arr(j.hcursor.downField("streams").focus.get.asArray.get.head)
          )
      )
      val manifest = right(FixtureManifest.parse(m.noSpaces))
      assume(Ffprobe.locate().nonEmpty, "rotation court requires installed ffprobe")
      val ffprobe = Ffprobe.locate().get
      val stdout = right(Ffprobe.invoke(ffprobe, rotated))
      assert(stdout.contains("rotation"), "generated movie must actually declare display rotation")
      val observed = right(
        MediaProbe.join(
          manifest,
          manifest.sha256,
          right(ToolRealization.observe("ffprobe", ffprobe)),
          Ffprobe.CanonicalArgs,
          right(FfprobeJson.parse(stdout))
        )
      )
      val result =
        right(SampledExtraction.run(tool, rotated, output, observed, 0, geometry, Vector(0)))
      val expected = rgb.grouped(3).flatMap(p => p.reverse).toVector
      assertEquals(Files.readAllBytes(output).toVector, expected)
      assertEquals(result.frames.geometry, geometry)
      assertEquals(result.observedPts, Vector(12000L))
    }

  test("concat cannot substitute unpinned referenced media"):
    withInput { (input, output, root) =>
      val concat = root.resolve("input.ffconcat")
      val bytes = s"ffconcat version 1.0\nfile '${input.getFileName}'\n".getBytes(UTF_8)
      val _ = Files.write(concat, bytes)
      val j = io.circe.parser.parse(text("f0-v1.manifest.json")).toOption.get
      val m = j.mapObject(
        _.add("sha256", io.circe.Json.fromString(Checksum.ofBytes(bytes).hex))
          .add("byteLength", io.circe.Json.fromLong(bytes.length.toLong))
      )
      val manifest = right(FixtureManifest.parse(m.noSpaces))
      val envelope = right(ProbeEnvelope.parse(text("f0-v1.ffprobe-envelope.json")))
      val declared = right(
        MediaProbe.join(
          manifest,
          manifest.sha256,
          envelope.tool,
          envelope.args,
          right(FfprobeJson.parse(text("f0-v1.ffprobe-stdout.json")))
        )
      )
      val result = SampledExtraction.run(tool, concat, output, declared, 0, geometry, selected)
      assert(result.left.toOption.exists(_.message.contains("ffmpeg exited")))
      assert(!Files.exists(output))
    }

  test("a contiguous selection uses a bounded expression and preserves every decoded frame"):
    withInput { (input, output, _) =>
      val all = (0 until 45).toVector
      val result = right(SampledExtraction.run(tool, input, output, probe(), 0, geometry, all))
      assertEquals(
        result.frames.framesSha256.hex,
        "bb13ca6a6618982c1e3d840eb04e59b5aa2c52f9e2c118ab70762e2cc67968af"
      )
      val large = SampledExtraction.canonicalArgs(0, (0 until 100000).toVector)
      assert(large.mkString(" ").length < 500)
    }

  test("observed geometry refuses a same-area wrong probe declaration"):
    withInput { (input, output, _) =>
      val json = io.circe.parser.parse(text("f0-v1.manifest.json")).toOption.get
      val swapped = json.mapObject(
        _.add(
          "streams",
          io.circe.Json.fromValues(json.hcursor.downField("streams").focus.get.asArray.get.map {
            stream =>
              if stream.hcursor.get[Int]("index").toOption.contains(0) then
                stream.mapObject(
                  _.add("width", io.circe.Json.fromInt(18)).add("height", io.circe.Json.fromInt(32))
                )
              else stream
          })
        )
      )
      val manifest = right(FixtureManifest.parse(swapped.noSpaces))
      val envelope = right(ProbeEnvelope.parse(text("f0-v1.ffprobe-envelope.json")))
      val original = right(FfprobeJson.parse(text("f0-v1.ffprobe-stdout.json")))
      val declared = original.copy(streams =
        original.streams.map(s =>
          if s.index == 0 then s.copy(width = Some(18), height = Some(32)) else s
        )
      )
      val wrong =
        right(MediaProbe.join(manifest, manifest.sha256, envelope.tool, envelope.args, declared))
      val result =
        SampledExtraction.run(tool, input, output, wrong, 0, PictureGeometry(18, 32), selected)
      assert(result.left.toOption.exists(_.message.contains("dimensions differ")))
      assert(!Files.exists(output))
    }

  test("equal tick numbers on a different probe clock refuse"):
    withInput { (input, output, _) =>
      val json = io.circe.parser.parse(text("f0-v1.manifest.json")).toOption.get
      val changed = json.mapObject(
        _.add(
          "streams",
          io.circe.Json.fromValues(json.hcursor.downField("streams").focus.get.asArray.get.map {
            stream =>
              if stream.hcursor.get[Int]("index").toOption.contains(0) then
                stream.mapObject(_.add("timebase", io.circe.Json.fromString("1/48000")))
              else stream
          })
        )
      )
      val manifest = right(FixtureManifest.parse(changed.noSpaces))
      val envelope = right(ProbeEnvelope.parse(text("f0-v1.ffprobe-envelope.json")))
      val original = right(FfprobeJson.parse(text("f0-v1.ffprobe-stdout.json")))
      val declared = original.copy(streams =
        original.streams.map(s => if s.index == 0 then s.copy(timeBase = "1/48000") else s)
      )
      val wrong =
        right(MediaProbe.join(manifest, manifest.sha256, envelope.tool, envelope.args, declared))
      val result = SampledExtraction.run(tool, input, output, wrong, 0, geometry, selected)
      assert(result.left.toOption.exists(_.message.contains("clocks differ")))
      assert(!Files.exists(output))
    }
