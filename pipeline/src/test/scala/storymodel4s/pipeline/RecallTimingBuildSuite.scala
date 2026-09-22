package storymodel4s.pipeline

import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import storymodel4s.codec.{Canonical, RecallTimingCodecs}
import storymodel4s.codec.RecallCodecs.given
import storymodel4s.core.*
import storymodel4s.recall.*

/** Original synthetic text, shared with the independent Python word-clock fixture. */
object RecallTimingFixture:
  val csv: String =
    "Words,princeton-word-onset-seconds,princeton-tr-number,princeton-tr-onset-seconds,openneuro-word-onset-seconds,openneuro-tr-number,openneuro-tr-onset-seconds\nAnna,0,1,0,,6,7.5\n,1,2,1.5,8.5,7,9\narrived.,1.25,2,1.5,8.75,7,9\nBob,2.5,3,3,10,8,10.5\nleft.,3.5,4,4.5,9,9,12\n"
  private val source = StorySource.fromText("Anna arrived. Bob left.").toOption.get
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val spans =
    Vector((0, 4), (5, 13), (14, 17), (18, 23)).map((a, b) => TextSpan.unsafe(a, b))
  private val units = Vector((0, 13), (14, 23)).zipWithIndex.map { case ((a, b), i) =>
    RecallUnit(
      RecallUnitId.unsafe(s"u$i"),
      i,
      SpanSet.one(SpanRef(Some(atlas.sentences(i).id), TextSpan.unsafe(a, b))),
      source.canonicalText.substring(a, b),
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
  }
  val graph = RecallGraph
    .validated(source, atlas, units, RecallRelations.empty)
    .toOption
    .get
  private val artifact =
    Checksum.from("13357e4aff12718d0a5665307beebe7c71155d12f587bec29844ad62904f9f4f").toOption.get
  val inventory =
    RecallInventory.of(graph, spans, WordIdPolicy.inputArtifact(artifact)).toOption.get
  val graphText: String = Canonical.encode(graph)
  private def s(value: String): Json = Json.fromString(value)
  private def input(path: String, text: String): Json =
    Json.obj("path" -> s(path), "sha256" -> s(Checksum.ofBytes(text.getBytes(UTF_8)).hex))
  val job: Json = Json.obj(
    "schemaVersion" -> s(RecallTimingBuild.JobSchema),
    "csv" -> input("words.csv", csv),
    "graph" -> input("recall-graph.json", graphText),
    "parserArtifact" -> s(artifact.hex),
    "inventoryDigest" -> s(inventory.digest.hex),
    "wordSpans" -> Json.fromValues(
      spans.map(span =>
        Json.obj(
          "start" -> Json.fromInt(span.start),
          "endExclusive" -> Json.fromInt(span.endExclusive)
        )
      )
    ),
    "columns" -> Json.obj(
      "header" -> Json.fromValues(csv.takeWhile(_ != '\n').split(',').map(s)),
      "word" -> Json.fromInt(0),
      "onset" -> Json.fromInt(4),
      "clock" -> s("openneuro-word-onset-seconds")
    )
  )
  def write(root: Path): Path =
    Files.writeString(root.resolve("words.csv"), csv): Unit
    Files.writeString(root.resolve("recall-graph.json"), graphText): Unit
    val path = root.resolve("job.json")
    Files.writeString(path, job.spaces2): Unit
    path
  def main(args: Array[String]): Unit =
    require(args.length == 1, "new synthetic fixture directory required")
    val path = Path.of(args(0))
    Files.createDirectory(path): Unit
    write(path): Unit

class RecallTimingBuildSuite extends FunSuite:
  test("interrupted completion-marker write leaves no final manifest") {
    inTemp { (root, _) =>
      val out = root.resolve("interrupted")
      val result = RecallTimingBuild.publish(
        out,
        "{}",
        "{}",
        (path, bytes) =>
          if path.getFileName.toString == ".manifest.pending" then
            Files.write(path, bytes.take(8)): Unit
            throw new java.io.IOException("injected partial write")
          else Files.write(path, bytes): Unit
      )
      assertEquals(result, Left(RecallTimingBuild.Refusal.OutputWrite))
      assert(Files.exists(out.resolve(".manifest.pending")))
      assert(!Files.exists(out.resolve("manifest.json")))
    }
  }
  private def inTemp(f: (Path, Path) => Unit): Unit =
    val root = Files.createTempDirectory("recall-intake-test-")
    try f(root, RecallTimingFixture.write(root))
    finally
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(p => Files.delete(p))
      finally stream.close()
  private def change(job: Path)(f: Json => Json): Unit =
    Files.writeString(job, f(RecallTimingFixture.job).noSpaces): Unit
  test(
    "CLI artifact path emits contextual canonical timing complete records and independently pinned files"
  ) {
    inTemp { (root, job) =>
      val out = root.resolve("output")
      val result = RecallTimingBuild.run(job, out).toOption.get
      assertEquals((result.words, result.records), (4, 5))
      assertEquals(
        result.inventory.hex,
        "1a4915caa8d45c063ec7d5bcd452e0e684eae24ea08056335a82545028fe71b3"
      )
      val timing = RecallTimingCodecs
        .decode(Files.readString(out.resolve("recall-timing.json")), RecallTimingFixture.inventory)
        .toOption
        .get
      assertEquals(timing.digest, result.timing)
      val receipt =
        Canonical.parse(Files.readString(out.resolve("intake-receipt.json"))).toOption.get
      assertEquals(receipt.hcursor.get[Vector[Json]]("records").toOption.get.size, 5)
      val manifest = Canonical.parse(Files.readString(out.resolve("manifest.json"))).toOption.get
      manifest.hcursor.get[Vector[Json]]("files").toOption.get.foreach { file =>
        val name = file.hcursor.get[String]("path").toOption.get
        val hash = file.hcursor.get[String]("sha256").toOption.get
        assertEquals(Checksum.ofBytes(Files.readAllBytes(out.resolve(name))).hex, hash)
      }
      assertEquals(RecallTimingBuild.run(job, out), Left(RecallTimingBuild.Refusal.OutputExists))
      assertEquals(
        RecallTimingCodecs
          .decode(
            Files.readString(out.resolve("recall-timing.json")),
            RecallTimingFixture.inventory
          )
          .toOption
          .get
          .digest,
        result.timing
      )
    }
  }
  test("wrong byte pins and inventory declarations refuse before producing an output directory") {
    inTemp { (root, job) =>
      Files.writeString(
        root.resolve("words.csv"),
        RecallTimingFixture.csv.replace("Anna", "Anne")
      ): Unit
      assertEquals(
        RecallTimingBuild.run(job, root.resolve("output")),
        Left(RecallTimingBuild.Refusal.InputIdentity)
      )
      assert(!Files.exists(root.resolve("output")))
      Files.writeString(root.resolve("words.csv"), RecallTimingFixture.csv): Unit
      change(job)(_.mapObject(_.add("inventoryDigest", Json.fromString("0" * 64))))
      assertEquals(
        RecallTimingBuild.run(job, root.resolve("output")),
        Left(RecallTimingBuild.Refusal.InvalidInventory)
      )
      assert(!Files.exists(root.resolve("output")))
    }
  }
  test("job schema refuses duplicate unknown null and malformed fields") {
    inTemp { (root, job) =>
      val valid = RecallTimingFixture.job.noSpaces
      val bad = Vector(
        valid.dropRight(1) + ",\"csv\":null}",
        RecallTimingFixture.job.mapObject(_.add("ignored", Json.True)).noSpaces,
        RecallTimingFixture.job.mapObject(_.add("columns", Json.Null)).noSpaces,
        "{",
        valid.replace(RecallTimingBuild.JobSchema, "future")
      )
      bad.foreach { text =>
        Files.writeString(job, text): Unit
        assertEquals(
          RecallTimingBuild.run(job, root.resolve("output")),
          Left(RecallTimingBuild.Refusal.InvalidJob)
        )
      }
      change(job)(
        _.mapObject(
          _.add(
            "csv",
            Json.obj(
              "path" -> Json.fromString("bad\u0000path"),
              "sha256" -> Json.fromString("0" * 64)
            )
          )
        )
      )
      assertEquals(
        RecallTimingBuild.run(job, root.resolve("output")),
        Left(RecallTimingBuild.Refusal.InputRead)
      )
    }
  }
