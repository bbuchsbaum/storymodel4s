package storymodel4s.pipeline

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.core.{TextSpan, SurfaceUnitKind}
import storymodel4s.embed.*

/** Synthetic courts for the disclosure witness carried through prepared recall publication. */
class RecallPublicationSuite extends FunSuite:
  import StudyPreparation.*

  private val policy = PrivacyPolicyId.unsafe("recall-publication-policy")
  private val mapping = MappingPipeline.Config(
    MappingPipeline.LexicalProfile,
    1.0,
    20,
    200,
    Vector(MappingPipeline.Capability.Mapping)
  )
  private val input = RecallInput("synthetic", "one", "recall.txt")
  private val config = Config(
    SourceInput.Text("source.txt", SurfaceUnitKind.Sentence),
    Vector(input),
    RecallProfile
  )

  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  private def temporary(test: Path => Unit): Unit =
    val root = Files.createTempDirectory("recall-publication-")
    try test(root)
    finally
      val paths = Files.walk(root)
      try
        paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path): Unit
        }
      finally paths.close()

  private def write(root: Path, name: String, text: String): Unit =
    Files.writeString(root.resolve(name), text, UTF_8): Unit

  private def payload(keyName: String): PseudonymizedText =
    val key = KeyId.unsafe(keyName)
    val keys = SensitiveKeyProvider.static(key, Array[Byte](3, 5, 7, 11))
    val detector = PseudonymizationDetector
      .wholeWordTable(Vector(PseudonymizationTableEntry("Jane", "[P1]")))
      .toOption
      .get
    val source = "Jane arrived."
    val text = "[P1] arrived."
    val offsets = Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4))
    val detected = detector.detect(source, key, keys).toOption.get
    PseudonymizedText
      .checked(policy, key, source, text, offsets, detected, detector, keys)
      .toOption
      .get

  private def prepareWith(
      root: Path,
      disclosure: RecallDisclosure.Input
  ): StudyPreparation.Prepared =
    right(
      StudyPreparation.prepare(root, config, Map((input.participant, input.session) -> disclosure))
    )

  private def preparedRecall(prepared: StudyPreparation.Prepared): StudyPreparation.Recall =
    right(prepared.outcomes.head.result)

  test(
    "a certified witness survives preparation, segmentation, publication, and contextual decode"
  ) {
    temporary { root =>
      write(root, "source.txt", "Anna arrived.")
      val actual = payload("recall-publication-key-a")
      val disclosure = RecallDisclosure.certified(actual)
      write(root, "recall.txt", disclosure.text)

      val prepared = prepareWith(root, disclosure)
      val recall = preparedRecall(prepared)
      val encoded = right(StudyPreparation.publishRecall(recall))
      val decoded = right(
        StudyPreparation.decode(encoded, StudyPreparation.edition(prepared.source), disclosure)
      )

      assertEquals(recall.graph.transcript.rawText, actual.text)
      assertEquals(
        recall.disclosure.basis.asInstanceOf[RecallDisclosure.Basis.CertifiedPseudonymized].payload,
        actual
      )
      assertEquals(
        decoded.disclosure.basis
          .asInstanceOf[RecallDisclosure.Basis.CertifiedPseudonymized]
          .payload,
        actual
      )
      assert(StudyPreparation.decode(encoded, StudyPreparation.edition(prepared.source)).isLeft)
      assert(
        StudyPreparation
          .decode(
            encoded,
            StudyPreparation.edition(prepared.source),
            RecallDisclosure.certified(payload("recall-publication-key-b"))
          )
          .isLeft
      )
    }
  }

  test("ordinary local preparation remains usable locally but cannot publish") {
    temporary { root =>
      write(root, "source.txt", "Anna arrived.")
      write(root, "recall.txt", "Anna arrived.")

      val prepared = right(StudyPreparation.prepare(root, config))
      val recall = preparedRecall(prepared)

      assertEquals(recall.disclosure.basis, RecallDisclosure.Basis.LocalOnly)
      assert(StudyPreparation.publishRecall(recall).isLeft)
      assertEquals(
        right(
          StudyPreparation.decode(
            StudyPreparation.encode(recall),
            StudyPreparation.edition(prepared.source)
          )
        ),
        recall
      )
    }
  }

  test("the fixed project-synthetic public control publishes with its retained authority") {
    temporary { root =>
      write(root, "source.txt", "Anna arrived.")
      val disclosure = right(RecallDisclosure.projectSynthetic("Anna arrived."))
      write(root, "recall.txt", disclosure.text)

      val prepared = prepareWith(root, disclosure)
      val recall = preparedRecall(prepared)
      val encoded = right(StudyPreparation.publishRecall(recall))

      assertEquals(
        right(
          StudyPreparation.decode(encoded, StudyPreparation.edition(prepared.source), disclosure)
        ),
        recall
      )
    }
  }

  test("a supplied disclosure must bind the exact recall bytes") {
    temporary { root =>
      write(root, "source.txt", "Anna arrived.")
      val disclosure = RecallDisclosure.certified(payload("recall-publication-key"))
      write(root, "recall.txt", "[P1] left.")

      val prepared = prepareWith(root, disclosure)

      assert(prepared.outcomes.head.result.isLeft)
    }
  }

  test("a changed certified basis changes both attempt and batch identity") {
    temporary { root =>
      write(root, "source.txt", "Anna arrived.")
      val first = RecallDisclosure.certified(payload("recall-publication-key-a"))
      val second = RecallDisclosure.certified(payload("recall-publication-key-b"))
      write(root, "recall.txt", first.text)

      val left = prepareWith(root, first)
      val rightPrepared = prepareWith(root, second)

      assertNotEquals(left.outcomes.head.attempt, rightPrepared.outcomes.head.attempt)
      assertNotEquals(
        right(MappingPipeline.batch(left, mapping)).identity,
        right(MappingPipeline.batch(rightPrepared, mapping)).identity
      )
    }
  }

  test("failed timing still binds each retained certified basis") {
    temporary { root =>
      write(root, "source.txt", "Anna arrived.")
      val first = RecallDisclosure.certified(payload("failure-key-a"))
      val second = RecallDisclosure.certified(payload("failure-key-b"))
      write(root, "recall.txt", first.text)
      write(root, "timing.csv", "invalid timing")
      val timed = config.copy(recalls = Vector(input.copy(timing = Some("timing.csv"))))
      def prepared(d: RecallDisclosure.Input) =
        right(StudyPreparation.prepare(root, timed, Map((input.participant, input.session) -> d)))
      val a = prepared(first)
      val b = prepared(second)
      assertEquals(a.outcomes.head.result, Left(Refusal.InvalidTiming))
      assertEquals(b.outcomes.head.result, Left(Refusal.InvalidTiming))
      assertNotEquals(a.outcomes.head.attempt, b.outcomes.head.attempt)
      assertNotEquals(
        right(MappingPipeline.batch(a, mapping)).identity,
        right(MappingPipeline.batch(b, mapping)).identity
      )
    }
  }

  test("failed admission still binds exact lineage bytes") {
    temporary { root =>
      write(root, "source.txt", "Anna arrived.")
      write(root, "recall.txt", "Anna arrived.")
      val requested = config.copy(recalls =
        Vector(input.copy(admission = Admission.Sherlock("recall-source-01", "lineage.json")))
      )
      write(root, "lineage.json", "first invalid lineage")
      val a = right(StudyPreparation.prepare(root, requested))
      write(root, "lineage.json", "second invalid lineage")
      val b = right(StudyPreparation.prepare(root, requested))
      assertEquals(a.outcomes.head.result, Left(Refusal.DisclosureRefused))
      assertEquals(b.outcomes.head.result, Left(Refusal.DisclosureRefused))
      assertNotEquals(a.outcomes.head.attempt, b.outcomes.head.attempt)
      assertNotEquals(
        right(MappingPipeline.batch(a, mapping)).identity,
        right(MappingPipeline.batch(b, mapping)).identity
      )
    }
  }

  test("exchange and weighted publication refuse local-only recall before creating output") {
    temporary { root =>
      write(root, "source.txt", "Anna arrived.")
      write(root, "recall.txt", "Anna arrived.")
      val local =
        right(MappingPipeline.batch(right(StudyPreparation.prepare(root, config)), mapping))
      val exchange = root.resolve("exchange")
      val weighted = root.resolve("weighted")
      val weights = Vector(storymodel4s.align.StructuralWeight.Reference)
      assertEquals(
        MappingPipeline.publishExchange(local, exchange, false),
        Left("DisclosureRefused")
      )
      assertEquals(
        WeightedMapping.publish(local, weights, weighted, false),
        Left("DisclosureRefused")
      )
      assert(!Files.exists(exchange))
      assert(!Files.exists(weighted))
      val admitted = right(RecallDisclosure.projectSynthetic("Anna arrived."))
      val publicBatch = right(MappingPipeline.batch(prepareWith(root, admitted), mapping))
      assert(MappingPipeline.publishExchange(publicBatch, exchange, false).isRight)
      assert(WeightedMapping.publish(publicBatch, weights, weighted, false).isRight)
    }
  }
