package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.codec.{Canonical, MappingExchange}
import storymodel4s.core.SurfaceUnitKind

/** Reuses the admitted synthetic Anna/Bob parser controls; no participant or corpus inputs. */
class PairedExchangeSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val config = MappingPipeline.Config(
    MappingPipeline.LexicalProfile,
    1.0,
    20,
    200,
    Vector(MappingPipeline.Capability.Mapping)
  )
  private def inTemp(f: Path => Unit): Unit =
    val root = Files.createTempDirectory("paired-exchange-")
    try f(root)
    finally
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(p => {
            Files.deleteIfExists(p): Unit
          })
      finally stream.close()
  private def write(root: Path, name: String, text: String): Unit = {
    Files.writeString(root.resolve(name), text): Unit
  }
  private def batch(root: Path): MappingPipeline.Batch =
    write(root, "source.txt", "Anna arrived. Bob left.")
    write(root, "recall.txt", "Anna arrived.")
    val prepared = right(
      StudyPreparation.prepare(
        root,
        StudyPreparation.Config(
          StudyPreparation.SourceInput.Text("source.txt", SurfaceUnitKind.Sentence),
          Vector(
            StudyPreparation.RecallInput("p1", "s1", "recall.txt"),
            StudyPreparation.RecallInput("p2", "s1", "recall.txt"),
            StudyPreparation.RecallInput("p3", "s1", "missing.txt")
          ),
          StudyPreparation.RecallProfile
        )
      )
    )
    right(MappingPipeline.batch(prepared, config))
  private def emit(name: String, value: MappingPipeline.Batch): Unit =
    val (files, manifest) = right(MappingPipeline.exchangeArtifacts(value))
    val json =
      Json.obj((("manifest.json" -> manifest) +: files).map((n, s) => n -> Json.fromString(s))*)
    println(s"PAIRED_EXCHANGE_FIXTURE\t$name\t${Canonical.print(json)}")

  test("paired exchange retains exact execution and complete participant outcomes") {
    inTemp { root =>
      val value = batch(root)
      val run = value.outcomes.head.run.get
      val encoded = right(MappingExchange.encode(run.execution))
      assertEquals(right(MappingExchange.decodePair(encoded.files, run.execution)), run.execution)
      val output = root.resolve("exchange")
      val receipt = right(MappingPipeline.publishExchange(value, output, false))
      assertEquals(right(MappingPipeline.publishExchange(value, output, true)), receipt)
      assertEquals(value.outcomes.map(_.run.nonEmpty), Vector(true, true, false))
      emit("text", value)
    }
  }

  test("paired context refuses foreign executions duplicate files and swapped members") {
    inTemp { root =>
      val value = batch(root)
      val a = value.outcomes(0).run.get.execution
      val b = value.outcomes(1).run.get.execution
      val encoded = right(MappingExchange.encode(a))
      assert(MappingExchange.decodePair(encoded.files, b).isLeft)
      assert(MappingExchange.decodePair(encoded.files :+ encoded.files.last, a).isLeft)
      assert(MappingExchange.decodePair(encoded.files.filterNot(_._1 == "manifest.json"), a).isLeft)
      val swapped = encoded.files.map { (name, text) =>
        name -> (if name == "reference--mapping.json" then
                   encoded.files.toMap.apply("reconstruction--mapping.json")
                 else text)
      }
      assert(MappingExchange.decodePair(swapped, a).isLeft)
    }
  }

  test("public exchange CLI refuses changed bytes on resume and preserves prior output") {
    inTemp { root =>
      val _ = batch(root)
      write(root, "study.json", StudyCli.Template)
      write(root, "config.json", MappingCli.Template)
      val output = root.resolve("published")
      val args = List(
        "exchange",
        root.resolve("study.json").toString,
        root.resolve("config.json").toString,
        output.toString
      )
      var errors = Vector.empty[String]
      assertEquals(MappingCli.run(args, _ => (), e => errors :+= e), 0, errors.mkString)
      assertEquals(MappingCli.run(args :+ "--resume", _ => (), _ => ()), 0)
      assertEquals(MappingCli.run(args, _ => (), _ => ()), 2)
      write(output, "foreign.json", "{}")
      assertEquals(MappingCli.run(args :+ "--resume", _ => (), _ => ()), 2)
      Files.delete(output.resolve("foreign.json"))
      assertEquals(MappingCli.run(args :+ "--resume", _ => (), _ => ()), 0)
      val stream = Files.list(output)
      val member =
        try
          stream
            .iterator()
            .asScala
            .find(_.getFileName.toString.endsWith("reference--alternatives.tsv"))
            .get
        finally stream.close()
      Files.writeString(member, "corrupt"): Unit
      assertEquals(MappingCli.run(args :+ "--resume", _ => (), _ => ()), 2)
      assertEquals(Files.readString(member), "corrupt")
    }
  }

  test("interrupted paired publication has no completed manifest") {
    inTemp { root =>
      val (files, manifest) = right(MappingPipeline.exchangeArtifacts(batch(root)))
      val output = root.resolve("interrupted")
      val result = StudyArtifacts.publish(
        output,
        files,
        manifest,
        false,
        (path, bytes) => {
          if path.getFileName.toString == ".manifest.pending" then
            throw new java.io.IOException("control")
          Files.write(path, bytes): Unit
        }
      )
      assertEquals(result, Left(StudyArtifacts.Refusal.OutputWrite))
      assert(!Files.exists(output.resolve("manifest.json")))
      assertEquals(
        StudyArtifacts.publish(output, files, manifest, true),
        Left(StudyArtifacts.Refusal.Incomplete)
      )
    }
  }

  test("paired annotation exchange retains exact disjoint support points and missing descendants") {
    inTemp { root =>
      write(root, "recall.txt", "Anna arrived.")
      val tick = 9007199254740993L
      write(
        root,
        "parts.json",
        s"""[{"part":"a","start":"$tick","end":"${tick + 100}","numerator":"1","denominator":"10"}]"""
      )
      write(
        root,
        "annotations.csv",
        TimedAnnotationInput.Header.mkString(",") + "\n" +
          s"1,Anna arrived.,a,${tick + 10},${tick + 20},1,Scene,,Anna\n" +
          s"2,Bob left.,a,${tick + 30},${tick + 40},1,Scene,,Bob\n" +
          s"3,Anna arrived.,a,${tick + 25},,1,Scene,,Anna\n" +
          "4,Bob left.,,,,1,Scene,,Bob\n"
      )
      val prepared = right(
        StudyPreparation.prepare(
          root,
          StudyPreparation.Config(
            StudyPreparation.SourceInput.TimedAnnotations("annotations.csv", "parts.json"),
            Vector(StudyPreparation.RecallInput("p1", "s1", "recall.txt")),
            StudyPreparation.RecallProfile
          )
        )
      )
      val value = right(MappingPipeline.batch(prepared, config))
      assertEquals(value.outcomes.count(_.run.nonEmpty), 1)
      val run = value.outcomes.head.run.get
      assertEquals(
        right(
          MappingExchange
            .decodePair(right(MappingExchange.encode(run.execution)).files, run.execution)
        ),
        run.execution
      )
      emit("annotation", value)
    }
  }
