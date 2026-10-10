package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.fixtures.WorkspaceFixtures
import storymodel4s.fixtures.wog.WarOfTheGhostsModel

/** Endpoint and public sweep courts reuse admitted WOG and synthetic Anna/Bob controls. */
class WeightedMappingSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val weights = Vector(
    StructuralWeight.Reference,
    right(StructuralWeight.of(1, 2)),
    StructuralWeight.Reconstruction
  )
  private val config = MappingPipeline.Config(
    MappingPipeline.LexicalProfile,
    1.0,
    20,
    200,
    Vector(MappingPipeline.Capability.Mapping)
  )
  private def temporary(f: Path => Unit): Unit =
    val root = Files.createTempDirectory("weighted-mapping-")
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
          .foreach(p => { Files.deleteIfExists(p): Unit })
      finally stream.close()
  private def write(root: Path, name: String, text: String): Unit = {
    Files.writeString(root.resolve(name), text): Unit
  }
  private def parity(run: MappingPipeline.Run): Unit =
    Vector(
      StructuralWeight.Reference -> run.reference,
      StructuralWeight.Reconstruction -> run.reconstruction
    ).foreach { (weight, original) =>
      val selected = right(run.execution.weighted(weight))
      assertEquals(MappingCodecs.encode(right(selected.record)), MappingCodecs.encode(original))
      val bundle = right(WeightedMappingExchange.encode(selected))
      val originalFiles = right(MappingExchange.encode(original)).files.toMap
      originalFiles.foreach { (name, text) =>
        assertEquals(bundle.files.toMap.apply("record--" + name), text)
      }
      assertEquals(right(WeightedMappingExchange.decode(bundle.files, selected)), selected)
    }

  test("WOG miniature dispatch preserves both endpoint canonical byte packages") {
    val workspace = WorkspaceFixtures.all("a" * 40, includeHistorical = false).toMap.apply("wog")
    val (fullView, fullSource) = right(WorkspaceCodecs.sourceFor(workspace.model))
    val selectedTargets = Set(
      SourceNodeRef.Situation(WarOfTheGhostsModel.S.huntSeals),
      SourceNodeRef.Situation(WarOfTheGhostsModel.S.hearWarCries)
    )
    val view = InMemorySourceView(
      fullView.nodes.filter(n => selectedTargets(n.ref)).map(_.copy(parent = None)),
      Map.empty,
      None,
      fullView.scoringLength
    )
    assertEquals(view.nodes.size, 2)
    val source = right(
      SourceRepresentation.of(
        view,
        fullSource.bundles,
        None,
        view.nodes.map(n => n.ref -> fullSource.target(n.ref).get.sourceSupport).toMap
      )
    )
    val run =
      right(MappingPipeline.lexical(workspace.recall, view, workspace.inventory, source, config))
    parity(run)
    val selected = right(run.execution.weighted(weights(1)))
    val record = right(selected.record)
    val context = ExpectedMappingContext(
      run.execution.inventory,
      run.execution.source,
      selected.inference.map(DerivationContext(_, run.execution.recall, run.execution.view)),
      Some(run.execution -> MappingRun.Member.Weighted(selected))
    )
    assertEquals(
      MappingCodecs.encode(right(MappingCodecs.decode(MappingCodecs.encode(record), context))),
      MappingCodecs.encode(record)
    )
    val endpoint = right(run.execution.weighted(StructuralWeight.Reference))
    assert(
      WeightedMappingExchange
        .decode(right(WeightedMappingExchange.encode(selected)).files, endpoint)
        .isLeft
    )
  }

  test("synthetic multipart source preserves endpoint bytes and exact weighted support") {
    temporary { root =>
      write(root, "recall.txt", "Anna arrived. Bob left.")
      write(
        root,
        "parts.json",
        """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"},{"part":"b","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
      )
      write(
        root,
        "annotations.csv",
        TimedAnnotationInput.Header.mkString(",") + "\n" +
          "1,Anna arrived.,a,10,20,1,Scene,,Anna\n2,Bob left.,b,30,40,2,Scene,,Bob\n"
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
      val batch = right(MappingPipeline.batch(prepared, config))
      parity(batch.outcomes.head.run.get)
      val (files, manifest) = right(WeightedMapping.artifacts(batch, weights))
      println(
        "WEIGHTED_MAPPING_FIXTURE\tmultipart\t" + Canonical.print(
          Json.obj((("manifest.json" -> manifest) +: files).map((n, s) => n -> Json.fromString(s))*)
        )
      )
    }
  }

  test("public weighted sweep retains all attempts and exact resume identity") {
    temporary { root =>
      write(root, "source.txt", "Anna arrived. Bob left.")
      write(root, "recall.txt", "Anna arrived.")
      val input = Canonical
        .parse(StudyCli.Template)
        .toOption
        .get
        .mapObject(
          _.add(
            "recalls",
            Json.arr(
              Json.obj(
                "participant" -> Json.fromString("p1"),
                "session" -> Json.fromString("s1"),
                "path" -> Json.fromString("recall.txt"),
                "timing" -> Json.Null
              ),
              Json.obj(
                "participant" -> Json.fromString("p2"),
                "session" -> Json.fromString("s1"),
                "path" -> Json.fromString("recall.txt"),
                "timing" -> Json.Null
              ),
              Json.obj(
                "participant" -> Json.fromString("p3"),
                "session" -> Json.fromString("s1"),
                "path" -> Json.fromString("missing.txt"),
                "timing" -> Json.Null
              )
            )
          )
        )
      write(root, "study.json", input.noSpaces)
      write(root, "config.json", MappingCli.Template)
      val output = root.resolve("output")
      val args = List(
        root.resolve("study.json").toString,
        root.resolve("config.json").toString,
        "0/1,1/2,1/1",
        output.toString
      )
      var errors = Vector.empty[String]
      assertEquals(MappingWeightCli.run(args, _ => (), e => errors :+= e), 3, errors.mkString)
      assertEquals(MappingWeightCli.run(args :+ "--resume", _ => (), _ => ()), 3)
      assertEquals(
        MappingWeightCli.run(args.updated(2, "0/1,1/3,1/1") :+ "--resume", _ => (), _ => ()),
        2
      )
      val stream = Files.list(output)
      val files =
        try
          stream.iterator().asScala.toVector.map(p => p.getFileName.toString -> Files.readString(p))
        finally stream.close()
      println(
        "WEIGHTED_MAPPING_FIXTURE\ttext\t" + Canonical.print(
          Json.obj(files.map((n, s) => n -> Json.fromString(s))*)
        )
      )
    }
  }

  test("invalid weights and organization requests refuse before publication") {
    temporary { root =>
      write(root, "source.txt", "Anna arrived. Bob left.")
      write(root, "recall.txt", "Anna arrived.")
      write(root, "study.json", StudyCli.Template)
      write(root, "config.json", MappingCli.Template)
      val output = root.resolve("output")
      val args = List(
        root.resolve("study.json").toString,
        root.resolve("config.json").toString,
        "NaN",
        output.toString
      )
      Vector("NaN", "1.0", "2/4", "2/1", "1/2,1/2").foreach { weight =>
        assertEquals(MappingWeightCli.run(args.updated(2, weight), _ => (), _ => ()), 2)
        assert(!Files.exists(output))
      }
      write(root, "config.json", MappingCli.Template.replace("\"Mapping\"", "\"Organization\""))
      assertEquals(MappingWeightCli.run(args.updated(2, "1/2"), _ => (), _ => ()), 2)
      assert(!Files.exists(output))
    }
  }
