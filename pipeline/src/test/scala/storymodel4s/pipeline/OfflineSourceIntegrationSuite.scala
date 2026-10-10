package storymodel4s.pipeline

import cats.data.NonEmptyVector
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.align.BundleEntry
import storymodel4s.codec.{StoryModelCodec, SurfaceAtlasArtifactCodec, TextSourceArtifactCodec}
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.SituationK
import storymodel4s.story.*

/** Synthetic controls for offline source preparation; no corpus narrative or participant data. */
class OfflineSourceIntegrationSuite extends FunSuite:
  import StudyPreparation.*

  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  private def temporary(test: Path => Unit): Unit =
    val root = Files.createTempDirectory("offline-source-integration-")
    try test(root)
    finally
      val paths = Files.walk(root)
      try
        paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path): Unit
        }
      finally paths.close()

  private def write(root: Path, name: String, value: String): Unit =
    Files.writeString(root.resolve(name), value, UTF_8): Unit

  private def atlas(raw: String): String =
    val source = right(
      StorySource.fromText(raw, explicitId = Some(StoryId.unsafe("synthetic-atlas")))
    )
    SurfaceAtlasArtifactCodec.encode(SurfaceAnalyzer.analyze(source))

  private def syntheticModel: TextModel[ModelStatus.Validated] =
    val source = right(
      StorySource.fromText("Alpha arrives.", explicitId = Some(StoryId.unsafe("synthetic-model")))
    )
    val surface = SurfaceAnalyzer.analyze(source)
    val span = SpanSet.one(SpanRef(Some(surface.sentences.head.id), surface.sentences.head.span))
    def meta(key: String): ClaimMeta = ClaimMeta.unsafe(
      ClaimId.unsafe(s"synthetic-model:$key"),
      EpistemicStatus.Hypothesized,
      Credence.unmeasured,
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe(s"synthetic-model:evidence:$key"),
          Some(span),
          Set.empty,
          Fingerprint.unsafe("synthetic-model/v1"),
          StageId.unsafe("synthetic")
        )
      ),
      Provenance.deterministic("offline-source-integration/v1", Checksum.ofText("synthetic"))
    )
    val world = ContextId.unsafe("synthetic-model:world")
    val root = SegmentId.unsafe("synthetic-model:story")
    val event = EventNode(
      SituationId.unsafe("synthetic-model:arrival"),
      Predicate("arrive", None, "arrive"),
      "Alpha arrives.",
      world,
      Polarity.Positive,
      Modality.Asserted,
      None,
      span,
      NonEmptyVector.one(MentionId.unsafe[SituationK]("synthetic-model:mention")),
      meta("event")
    )
    val segment = SegmentNode(
      root,
      SegmentKind.Story,
      1,
      meta("segment"),
      SegmentSummary.Unsummarized(SummaryGap.NotProposed),
      span
    )
    val graph = NarrativeGraph(
      Map.empty,
      Map(event.id -> SituationNode.Event(event)),
      Map(root -> segment),
      Map(world -> ContextFrame(world, None, ContextKind.NarratedWorld, span, meta("world"))),
      RelationLayers.empty
    )
    val hierarchy = NarrativeHierarchy(
      Vector(
        ContainmentEdge(
          NarrativeMember.Situation(event.id),
          root,
          HierarchyKind.PrimarySegmentation,
          1.0,
          meta("containment")
        )
      ),
      Vector.empty
    )
    val draft = right(
      StoryModel.draftText(surface, graph, hierarchy, DiscourseTrajectory.empty)
    )
    StoryValidator.validate(draft).validated.getOrElse(fail("synthetic model did not validate"))

  private val mapping = MappingPipeline.Config(
    MappingPipeline.LexicalProfile,
    1.0,
    20,
    200,
    Vector(MappingPipeline.Capability.Mapping)
  )

  test("surface-atlas input preserves exact Unicode offsets and supports the mapping facade") {
    temporary { root =>
      val raw = "\uFEFFA\u0301😀; B.\r\nC."
      write(root, "source.txt", raw)
      write(root, "atlas.json", atlas(raw))
      write(root, "recall.txt", "A\u0301😀; B.")
      val prepared = right(
        prepare(
          root,
          Config(
            SourceInput.SurfaceAtlas("source.txt", "atlas.json", SurfaceUnitKind.Sentence),
            Vector(RecallInput("synthetic", "one", "recall.txt")),
            RecallProfile
          )
        )
      )
      val source = prepared.source
      val text = source.textSourceExchange.getOrElse(fail("text exchange unavailable"))
      val reconstructed = right(TextSourceArtifactCodec.decodeExchange(text.files.toMap))
      assertEquals(reconstructed.source.rawText, raw)
      assertEquals(reconstructed.profile, TextSegmentationProfile.SuppliedAtlasV1)
      assertEquals(source.narrativeModel, None)
      assert(!reconstructed.supports(TextSourceCapability.EncodingSeconds))
      assertEquals(
        source.representation.bundles.toVector.collect { case b: BundleEntry.Media => b },
        Vector.empty
      )
      assert(MappingPipeline.batch(prepared, mapping).isRight)
      val (files, manifest) = StudyCli.artifacts(prepared)
      val published = files.toMap
      assertEquals(published("source.json"), text.files.toMap.apply("source.json"))
      assertEquals(published("source-segments.tsv"), text.files.toMap.apply("segments.tsv"))
      assertEquals(published("source-manifest.json"), text.manifest)
      val destination = root.resolve("prepared")
      assert(StudyArtifacts.publish(destination, files, manifest, false).isRight)
      assert(StudyArtifacts.verify(destination, manifest).isRight)
    }
  }

  test("story-model input revalidates narrative targets without synthesizing a text package") {
    temporary { root =>
      val model = syntheticModel
      write(root, "model.json", StoryModelCodec.encode(model))
      val prepared = right(
        prepare(root, Config(SourceInput.StoryModel("model.json"), Vector.empty, RecallProfile))
      )
      assertEquals(
        prepared.source.narrativeModel.map(StoryModelCodec.contentChecksum),
        Some(StoryModelCodec.contentChecksum(model))
      )
      assertEquals(prepared.source.textSourceExchange, None)
      assert(prepared.source.nodes.nonEmpty)
      assertEquals(prepared.source.representation.bundles.toVector.size, 1)
    }
  }

  test("foreign surface atlas and malformed narrative model refuse") {
    temporary { root =>
      val raw = "A\u0301😀."
      write(root, "source.txt", raw)
      write(root, "foreign-atlas.json", atlas("Other text."))
      write(root, "model.json", "{\"foreign\":true}")
      assertEquals(
        prepare(
          root,
          Config(
            SourceInput.SurfaceAtlas("source.txt", "foreign-atlas.json", SurfaceUnitKind.Sentence),
            Vector.empty,
            RecallProfile
          )
        ),
        Left(Refusal.InvalidSource)
      )
      assertEquals(
        prepare(root, Config(SourceInput.StoryModel("model.json"), Vector.empty, RecallProfile)),
        Left(Refusal.InvalidSource)
      )
    }
  }

  test("config accepts only the declared surface-atlas and story-model source shapes") {
    val atlas = """{
      "schemaVersion":"storymodel-study/v1",
      "source":{
        "format":"surface-atlas","path":"source.txt",
        "atlas":"atlas.json","kind":"Sentence"
      },
      "recalls":[],"profile":"recall-baseline-lexical-words/v1"
    }"""
    val model = """{
      "schemaVersion":"storymodel-study/v1",
      "source":{"format":"story-model","path":"model.json"},
      "recalls":[],"profile":"recall-baseline-lexical-words/v1"
    }"""
    assertEquals(
      config(atlas),
      Right(
        Config(
          SourceInput.SurfaceAtlas("source.txt", "atlas.json", SurfaceUnitKind.Sentence),
          Vector.empty,
          RecallProfile
        )
      )
    )
    assertEquals(
      config(model),
      Right(Config(SourceInput.StoryModel("model.json"), Vector.empty, RecallProfile))
    )
  }

/** Emits a StudyPreparation surface-atlas exchange for the independent Python reader. */
object OfflineSourceExchangeWitness:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected a new witness directory")
    val root = Files.createDirectory(Path.of(args(0)))
    val raw = "\uFEFFA\u0301😀; B.\r\nC."
    val source = StorySource
      .fromText(raw, explicitId = Some(StoryId.unsafe("synthetic-atlas")))
      .toOption
      .get
    Files.writeString(root.resolve("source.txt"), raw, UTF_8): Unit
    Files.writeString(
      root.resolve("atlas.json"),
      SurfaceAtlasArtifactCodec.encode(SurfaceAnalyzer.analyze(source)),
      UTF_8
    ): Unit
    val config = """{
      "schemaVersion":"storymodel-study/v1",
      "source":{"format":"surface-atlas","path":"source.txt","atlas":"atlas.json","kind":"Sentence"},
      "recalls":[],"profile":"recall-baseline-lexical-words/v1"
    }"""
    Files.writeString(root.resolve("study.json"), config, UTF_8): Unit
    val destination = root.resolve("exchange")
    val status = StudyCli.run(
      List("prepare", root.resolve("study.json").toString, destination.toString),
      println,
      System.err.println
    )
    require(status == 0, "public preparation refused the synthetic atlas")
    println(destination)
