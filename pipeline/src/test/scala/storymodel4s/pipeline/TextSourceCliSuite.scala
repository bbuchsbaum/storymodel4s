package storymodel4s.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.codec.{Canonical, SurfaceAtlasArtifactCodec, TextSourceArtifactCodec}
import storymodel4s.core.*

/** Synthetic Unicode process-boundary diagnostics; no narrative or participant data. */
class TextSourceCliSuite extends FunSuite:
  private def temporary[A](f: Path => A): A =
    val root = Files.createTempDirectory("text-source-suite-")
    try f(root)
    finally
      val paths = Files.walk(root)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  private def run(args: String*): (Int, Vector[String], Vector[String]) =
    val out = Vector.newBuilder[String]
    val err = Vector.newBuilder[String]
    val status =
      TextSourceCli.run(args.toList, s => { val _ = out += s }, s => { val _ = err += s })
    (status, out.result(), err.result())

  test("UTF-8 intake writes an independently decodable package and bound completion receipt"):
    temporary { root =>
      val input = Files.writeString(root.resolve("source.txt"), "\uFEFFA\u0301😀; B.\r\n\r\nC.")
      val output = root.resolve("out")
      val (status, out, err) = run(input.toString, output.toString)
      assertEquals(status, 0)
      assertEquals(err, Vector.empty)
      val files = Vector("source.json", "segments.tsv", "manifest.json")
        .map(n => n -> Files.readString(output.resolve(n)))
        .toMap
      val value = TextSourceArtifactCodec.decodeExchange(files).toOption.get
      assertEquals(value.source.rawText, Files.readString(input))
      val receipt = Canonical.parse(out.head).toOption.get.hcursor
      assertEquals(
        receipt.get[String]("manifest_sha256").toOption.get,
        Checksum.ofText(files("manifest.json")).hex
      )
    }

  test("supplied atlas uses its own IDs and preserves unavailable granularity"):
    temporary { root =>
      val input = Files.writeString(root.resolve("source.txt"), "A; B.")
      val source = StorySource
        .fromText("A; B.", explicitId = Some(StoryId.unsafe("provided-story")))
        .toOption
        .get
      val unit = SurfaceUnit(
        SurfaceUnitId.unsafe("provided-paragraph"),
        SurfaceUnitKind.Paragraph,
        TextSpan.unsafe(0, 5),
        2,
        None
      )
      val atlas = SurfaceAtlas.of(source, Vector(unit)).toOption.get
      val path =
        Files.writeString(root.resolve("atlas.json"), SurfaceAtlasArtifactCodec.encode(atlas))
      val output = root.resolve("out")
      assertEquals(run(input.toString, output.toString, "--atlas", path.toString)._1, 0)
      val read =
        TextSourceArtifactCodec.decode(Files.readString(output.resolve("source.json"))).toOption.get
      assertEquals(read.atlas.units, Vector(unit))
      assertEquals(read.at(SurfaceUnitKind.Clause), Vector.empty)
    }

  test("malformed UTF-8 and foreign atlas refuse before creating output"):
    temporary { root =>
      val bad = Files.write(root.resolve("bad.txt"), Array(0xc3.toByte, 0x28.toByte))
      val output = root.resolve("out")
      val rejected = run(bad.toString, output.toString)
      assertEquals(rejected._1, 2)
      assertEquals(rejected._2, Vector.empty)
      assert(!Files.exists(output))
      val good = Files.writeString(root.resolve("good.txt"), "A.", StandardCharsets.UTF_8)
      val foreign = SurfaceAnalyzer.analyze(StorySource.fromText("B.").toOption.get)
      val atlas =
        Files.writeString(root.resolve("atlas.json"), SurfaceAtlasArtifactCodec.encode(foreign))
      assertEquals(run(good.toString, output.toString, "--atlas", atlas.toString)._1, 2)
      assert(!Files.exists(output))
    }

  test("existing outputs are never overwritten and errors remain on stderr"):
    temporary { root =>
      val input = Files.writeString(root.resolve("source.txt"), "A.")
      val output = Files.createDirectory(root.resolve("out"))
      val sentinel = Files.writeString(output.resolve("manifest.json"), "untouched")
      val refused = run(input.toString, output.toString)
      assertEquals(refused._1, 2)
      assertEquals(refused._2, Vector.empty)
      assertEquals(Files.readString(sentinel), "untouched")
      assert(refused._3.head.contains("OutputExists"))
    }

  test("help and malformed invocation have distinct exit and output channels"):
    assertEquals(run("--help"), (0, Vector(TextSourceCli.Usage), Vector.empty))
    val bad = run("--atlas")
    assertEquals(bad._1, 2)
    assertEquals(bad._2, Vector.empty)
    assert(bad._3.head.contains("Arguments"))

  test("failed manifest write never publishes a completion marker"):
    temporary { root =>
      val source = TextSourcePackage.fromText("A.").toOption.get
      val output = root.resolve("out")
      val failed = TextSourceCli.publish(
        source,
        output.toString,
        (path, _) => {
          val _ = Files.writeString(path, "partial")
          throw new java.io.IOException("injected write failure")
        }
      )
      assert(failed.isLeft)
      assert(Files.exists(output.resolve("source.json")))
      assert(!Files.exists(output.resolve("manifest.json")))
      val entries = Files.list(root)
      try assertEquals(entries.count(), 1L)
      finally entries.close()
    }
