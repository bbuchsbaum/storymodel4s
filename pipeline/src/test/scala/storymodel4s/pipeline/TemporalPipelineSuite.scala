package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.{Canonical, MappingExchange, TemporalProjectionCodecs}
import storymodel4s.core.*

/** Ordinary admitted synthetic files exercise the producer boundary without bench dependencies. */
private[pipeline] object TemporalPipelineFixture:
  def write(root: Path, name: String, value: String): Unit = {
    Files.writeString(root.resolve(name), value): Unit
  }
  def setup(
      root: Path,
      timed: Boolean = false,
      failed: Boolean = false,
      admitted: Boolean = true
  ): MappingPipeline.Batch =
    write(root, "recall.txt", "Anna arrived.")
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
    if timed then
      write(
        root,
        "timing.csv",
        StudyPreparation.TimingHeader.mkString(",") + "\np1,s1,Anna,0,1\np1,s1,arrived,2,\n"
      )
    val recalls = Vector(
      Json.obj(
        "participant" -> Json.fromString("p1"),
        "session" -> Json.fromString("s1"),
        "path" -> Json.fromString("recall.txt"),
        "timing" -> (if timed then Json.fromString("timing.csv") else Json.Null),
        "admission" -> (if admitted then
                          Json.obj("kind" -> Json.fromString("project-synthetic-control"))
                        else Json.Null)
      )
    ) ++ Option.when(failed)(
      Json.obj(
        "participant" -> Json.fromString("p2"),
        "session" -> Json.fromString("s1"),
        "path" -> Json.fromString("missing.txt"),
        "timing" -> Json.Null,
        "admission" -> Json.obj("kind" -> Json.fromString("project-synthetic-control"))
      )
    )
    write(
      root,
      "study.json",
      Canonical.printer
        .copy(dropNullValues = false)
        .print(
          Json.obj(
            "schemaVersion" -> Json.fromString(StudyPreparation.Schema),
            "source" -> Json.obj(
              "format" -> Json.fromString("timed-annotations"),
              "path" -> Json.fromString("annotations.csv"),
              "parts" -> Json.fromString("parts.json")
            ),
            "profile" -> Json.fromString(StudyPreparation.RecallProfile),
            "recalls" -> Json.fromValues(recalls)
          )
        )
    )
    write(root, "mapping.json", MappingCli.Template)
    MappingPipeline
      .analyze(root.resolve("study.json"), MappingCli.config(MappingCli.Template).toOption.get)
      .fold(e => throw new IllegalStateException(e.toString), identity)

  def request(batch: MappingPipeline.Batch, window: Boolean = false): TemporalProjection.Request =
    val source = batch.prepared.source.representation
    val bundle = source.bundles.toVector.collectFirst { case e: BundleEntry.Media => e.bundle }.get
    TemporalProjection.Request(
      TemporalSupport.Selection.Part(bundle.identity),
      source.targets.map(t =>
        TemporalQuery.Declaration(
          t.ref,
          TemporalQuery.Assumption.SuppliedSupportContainsReferent,
          TemporalQuery.Allocation.UniformIntervals
        )
      ),
      Vector(0L -> 10L, 10L -> 20L, 20L -> 30L, 30L -> 40L, 40L -> 100L),
      recallWindow = Option.when(window)(
        TemporalProjection.RecallWindow(
          batch.outcomes.head.prepared
            .flatMap(_.timing)
            .map(_.clock.digest)
            .getOrElse(Checksum.ofText("absent-clock")),
          ExactRational.of(0, 1).toOption.get,
          ExactRational.of(4, 1).toOption.get
        )
      )
    )

class TemporalPipelineSuite extends FunSuite:
  import TemporalPipelineFixture.*
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def temporary(run: Path => Unit): Unit =
    val root = Files.createTempDirectory("temporal-pipeline-")
    try run(root)
    finally
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(p => { Files.delete(p): Unit })
      finally stream.close()

  test("temporal batch retains failed attempts and missing timing in cohort counts") {
    temporary { root =>
      val batch = setup(root, failed = true)
      val req = request(batch, window = true)
      val requests = Map(batch.outcomes.head.observation.get -> req)
      val rows = right(MappingPipeline.temporal(batch, requests))
      assertEquals(rows.size, 2)
      assert(rows(1)._2.isLeft)
      val pair = right(rows.head._2)
      assertEquals(pair._1.timingState, TemporalProjection.TimingState.Missing)
      assertEquals(pair._2.timingState, TemporalProjection.TimingState.Missing)
      assert(pair._1.rows.forall(_.grid.isRight))
      val counts = right(MappingPipeline.inspectTemporal(batch, requests)).hcursor
      assertEquals(counts.get[Int]("attempts"), Right(2))
      assertEquals(counts.get[Int]("failed"), Right(1))
      assertEquals(counts.get[Int]("temporalUnavailable"), Right(1))
      assertEquals(counts.get[Int]("missingTiming"), Right(1))
      assert(MappingPipeline.temporal(batch, Map(Checksum.ofText("foreign") -> req)).isLeft)
      assertEquals(
        right(MappingPipeline.inspectTemporal(batch, Map.empty)).hcursor
          .get[Int]("missingRequests"),
        Right(2)
      )
    }
  }

  test("prepared timing retains gaps and the final onset in both paired members") {
    temporary { root =>
      val batch = setup(root, timed = true)
      val pair = right(
        MappingPipeline.temporal(
          batch.outcomes.head.run.get,
          request(batch, true),
          batch.outcomes.head.prepared.get.timing
        )
      )
      Vector(pair._1, pair._2).foreach { p =>
        assertEquals(p.timingState, TemporalProjection.TimingState.Provided)
        assertEquals(p.exposure.get.uncovered, BigInt(3) -> BigInt(1))
        assertEquals(p.rows.flatMap(_.exposure).map(_.duration._1).sum, BigInt(1))
        assertEquals(p.rows.flatMap(_.exposure).flatMap(_.onsetEvents).map(_.numerator), Vector(2L))
      }
    }
  }

  test("temporal exchange publishes exact paired files and rejects corrupt resume") {
    temporary { root =>
      val batch = setup(root)
      val requests = Map(batch.outcomes.head.observation.get -> request(batch))
      val output = root.resolve("exchange")
      val receipt = right(MappingPipeline.exportTemporalResults(batch, requests, output))
      assertEquals(
        right(MappingPipeline.exportTemporalResults(batch, requests, output, true)),
        receipt
      )
      assert(MappingPipeline.exportTemporalResults(batch, requests, output).isLeft)
      val name = batch.outcomes.head.run.get.identity.hex + "--reference--temporal-projection.json"
      val projection = right(Canonical.parse(Files.readString(output.resolve(name))))
      assertEquals(
        projection.hcursor.get[String]("schemaVersion"),
        Right(TemporalProjection.Version)
      )
      write(output, name, "corrupt")
      assert(MappingPipeline.exportTemporalResults(batch, requests, output, true).isLeft)
    }
  }

  test("temporal exchange preserves the existing disclosure refusal") {
    temporary { root =>
      val batch = setup(root, admitted = false)
      val requests = Map(batch.outcomes.head.observation.get -> request(batch))
      val output = root.resolve("denied")
      assert(MappingPipeline.exportTemporalResults(batch, requests, output).isLeft)
      assert(!Files.exists(output))
    }
  }

  test("ordinary temporal CLI exports, reports partial timing and validates exact request fields") {
    temporary { root =>
      val batch = setup(root)
      write(root, "temporal.json", TemporalProjectionCodecs.encodeRequest(request(batch, true)))
      val args = List(
        "temporal",
        root.resolve("study.json").toString,
        root.resolve("mapping.json").toString,
        root.resolve("temporal.json").toString,
        root.resolve("output").toString
      )
      var errors = Vector.empty[String]
      assertEquals(MappingCli.run(args, _ => (), s => errors :+= s), 3, errors.mkString)
      assertEquals(MappingCli.run(args :+ "--resume", _ => (), _ => ()), 3)
      write(
        root,
        "temporal.json",
        "{\"schemaVersion\":\"invalid\",\"private_input\":\"do not echo\"}"
      )
      errors = Vector.empty
      assertEquals(MappingCli.run(args, _ => (), s => errors :+= s), 2)
      assert(!errors.mkString.contains("do not echo"))
    }
  }

  test("contextual paired temporal exchange rejects swapped, foreign and rehashed members") {
    temporary { root =>
      val batch = setup(root, timed = true)
      val run = batch.outcomes.head.run.get
      val timing = batch.outcomes.head.prepared.get.timing
      val pair = right(MappingPipeline.temporal(run, request(batch, true), timing))
      val bundle = right(MappingExchange.encodeTemporal(run.execution, pair._1, pair._2))
      val decoded =
        right(MappingExchange.decodeTemporal(bundle.files, run.execution, timing, timing))
      assertEquals(decoded, pair)
      val legacy = right(MappingExchange.encode(run.execution))
      assertEquals(
        bundle.files.tail.filterNot(_._1.endsWith("--temporal-projection.json")),
        legacy.files.tail
      )
      assertEquals(right(MappingExchange.decodePair(legacy.files, run.execution)), run.execution)
      assert(MappingExchange.encodeTemporal(run.execution, pair._2, pair._1).isLeft)
      val other = right(
        MappingPipeline.batch(batch.prepared, batch.config.copy(temperature = 2.0))
      ).outcomes.head.run.get
      assert(MappingExchange.decodeTemporal(bundle.files, other.execution, timing, timing).isLeft)
      assert(MappingExchange.decodeTemporal(bundle.files, run.execution, None, None).isLeft)
      assert(
        MappingExchange
          .decodeTemporal(bundle.files :+ bundle.files.last, run.execution, timing, timing)
          .isLeft
      )
      val name = "reference--temporal-projection.json"
      val original = bundle.files.toMap.apply(name)
      val changed =
        original.replaceFirst("\"mass\":\"0x[0-9a-f]{16}\"", "\"mass\":\"0x0000000000000000\"")
      assertNotEquals(changed, original)
      val mutated = bundle.files.map((n, t) => n -> (if n == name then changed else t))
      val manifest = right(Canonical.parse(mutated.head._2))
      val entries = manifest.hcursor.get[Vector[Json]]("files").toOption.get.map { entry =>
        if entry.hcursor.get[String]("path").toOption.contains(name) then
          entry.mapObject(
            _.add("sha256", Json.fromString(Checksum.ofText(changed).hex))
              .add("bytes", Json.fromString(changed.getBytes("UTF-8").length.toString))
          )
        else entry
      }
      val rehashed = mutated.updated(
        0,
        "manifest.json" -> Canonical.print(
          manifest.mapObject(_.add("files", Json.fromValues(entries)))
        )
      )
      assert(MappingExchange.decodeTemporal(rehashed, run.execution, timing, timing).isLeft)
    }
  }

/** Emits genuine facade pairs, bindings and a reproducible producer revision for the Atlas handoff.
  */
object TemporalPipelineWitness:
  def main(args: Array[String]): Unit =
    require(
      args.length == 2 && args(1).matches("[0-9a-f]{40}"),
      "TemporalPipelineWitness OUTPUT PRODUCER_SHA"
    )
    val root = Path.of(args(0))
    Files.createDirectory(root): Unit
    val entries = Vector(false, true).map { timed =>
      val name = if timed then "timed" else "untimed"
      val folder = root.resolve(name)
      Files.createDirectory(folder): Unit
      val batch = TemporalPipelineFixture.setup(folder, timed = timed, failed = true)
      val request = TemporalPipelineFixture.request(batch, window = true)
      val requests = Map(batch.outcomes.head.observation.get -> request)
      val wire = TemporalProjectionCodecs.encodeRequest(request)
      TemporalPipelineFixture.write(folder, "request.json", wire)
      val output = folder.resolve("exchange")
      val manifest = MappingPipeline
        .exportTemporalResults(batch, requests, output)
        .fold(e => throw new IllegalStateException(e.toString), identity)
      val summary = MappingPipeline.inspectTemporal(batch, requests).toOption.get
      val stream = Files.list(output)
      val files =
        try
          stream.iterator().asScala.toVector.sortBy(_.getFileName.toString).map { path =>
            Json.obj(
              "path" -> Json.fromString(root.relativize(path).toString),
              "sha256" -> Json.fromString(Checksum.ofBytes(Files.readAllBytes(path)).hex)
            )
          }
        finally stream.close()
      Json.obj(
        "name" -> Json.fromString(name),
        "manifest" -> Json.fromString(manifest.hex),
        "summary" -> summary,
        "files" -> Json.fromValues(files)
      )
    }
    TemporalPipelineFixture.write(
      root,
      "temporal-fixtures.json",
      Canonical.print(
        Json.obj(
          "schemaVersion" -> Json.fromString("temporal-facade-witness/v1"),
          "producerRevision" -> Json.fromString(args(1)),
          "queryVersion" -> Json.fromString(TemporalQuery.Version),
          "projectionVersion" -> Json.fromString(TemporalProjection.Version),
          "cases" -> Json.fromValues(entries)
        )
      )
    )
