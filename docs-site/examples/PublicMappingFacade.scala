package docsprobe

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import storymodel4s.pipeline.{FacadeFailure, MappingPipeline}

/** Ordinary-file journey using the existing project synthetic parser control, not participant text.
  */
@main def mappingFacade(): Unit =
  def checked[A](result: Either[FacadeFailure, A]): A =
    result.fold(error => throw new IllegalStateException(error.diagnostic.noSpaces), identity)
  val root = Files.createTempDirectory("mapping-facade-example-")
  try
    val _ = Files.writeString(root.resolve("source.txt"), "Anna arrived. Bob left.")
    val _ = Files.writeString(root.resolve("recall.txt"), "Anna arrived.")
    val study = """{
      "schemaVersion":"storymodel-study/v1",
      "source":{"format":"text","path":"source.txt","kind":"Sentence"},
      "profile":"recall-baseline-lexical-words/v1",
      "recalls":[{"participant":"synthetic","session":"one","path":"recall.txt",
        "timing":null,"admission":{"kind":"project-synthetic-control"}}]
    }"""
    val input = root.resolve("study.json")
    val _ = Files.writeString(input, study)
    val config = MappingPipeline.Config(
      MappingPipeline.LexicalProfile,
      1.0,
      20,
      200,
      Vector(MappingPipeline.Capability.Mapping)
    )
    val batch = checked(MappingPipeline.analyze(input, config))
    val inspection = MappingPipeline.inspect(batch).hcursor
    println(s"attempts: ${inspection.get[Int]("attempts").toOption.get}")
    println(s"completed: ${inspection.get[Int]("completed").toOption.get}")
    println(s"failed: ${inspection.get[Int]("failed").toOption.get}")
    println(
      s"missing capabilities: ${inspection.get[Vector[String]]("missingCapabilities").toOption.get.size}"
    )
    val output = root.resolve("exchange")
    val receipt = checked(MappingPipeline.exportResults(batch, output))
    println(s"paired exchange complete: ${Files.isRegularFile(output.resolve("manifest.json"))}")
    println(
      s"resume verifies identical bytes: ${MappingPipeline.exportResults(batch, output, resume = true) == Right(receipt)}"
    )
    println(
      s"existing output refuses overwrite: ${MappingPipeline.exportResults(batch, output).isLeft}"
    )
  finally
    val paths = Files.walk(root)
    try
      paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
        val _ = Files.deleteIfExists(path)
      }
    finally paths.close()
