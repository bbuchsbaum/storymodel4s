package storymodel4s.bench.video

import java.nio.file.{Files, Paths}
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.Checksum
import storymodel4s.embed.onnx.HistoricalParityFixture
import storymodel4s.features.{Estimate, MissingReason}

/** Synthetic historical compatibility is separate from strict registration and learned efficacy. */
class HistoricalEmbeddingParitySuite extends FunSuite:
  import HistoricalEmbeddingParityCapture.*
  private def root =
    val cwd = Paths.get("").toAbsolutePath.normalize()
    Vector(cwd, cwd.getParent)
      .find(p => Files.isRegularFile(p.resolve("embed-onnx/src/test/resources/fixture/model.onnx")))
      .getOrElse(fail("existing project ONNX fixture not found"))
  private def actual = Preset(
    HistoricalVideoDefaults.blendAlpha,
    LexicalBlend.LexicalFields.parse(None),
    HistoricalVideoDefaults.candidatesPerLevel,
    HistoricalVideoDefaults.lexicalOverlap,
    HistoricalVideoDefaults.orderingScale
  )
  private def withChannel[A](
      f: (
          storymodel4s.recall.RecallGraph[storymodel4s.recall.RecallGraphStatus.Checked],
          TimedSourceView.Built,
          storymodel4s.bench.Channel
      ) => A
  ): A =
    val (recall, built) = fixture
    val encoder = HistoricalParityFixture.open(root)
    try f(recall, built, channel(encoder, recall, built))
    finally encoder.close()
  private def leaf(built: TimedSourceView.Built, n: Int) =
    built.segmentByRef.collectFirst {
      case (ref, segment) if segment.ordinal == n =>
        built.view.node(ref).get
    }.get

  test("actual runner defaults reproduce the untouched complete historical body and digest") {
    val stream =
      getClass.getResourceAsStream("/storymodel4s/bench/video/historical-embedding-parity-v1.json")
    val expected =
      try stream.readAllBytes()
      finally stream.close()
    val observed =
      (capture(root, actual).noSpaces + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(
      Checksum.ofBytes(expected).hex,
      "2ed46ce420a187eac6e292cd4932d86889e8e53577fa4cbc65387ec1f63ccc75"
    )
    assertEquals(
      new String(observed, java.nio.charset.StandardCharsets.UTF_8),
      new String(expected, java.nio.charset.StandardCharsets.UTF_8)
    )
  }

  test("default values and full ladder are the frozen recipe rather than a copied alternate") {
    assertEquals(actual, frozen)
    assertEquals(
      checked(RecallOrderControl.Ladder.parse(None, None)),
      RecallOrderControl.Ladder.full
    )
    assertEquals(
      LexicalBlend.LexicalFields.parse(Some("text")),
      LexicalBlend.LexicalFields.TextOnly
    )
    assertEquals(LexicalBlend.LexicalFields.parse(Some("unknown")), actual.fields)
  }

  test("historical overrides empty overrides group labels and lexical-only extras are exact") {
    val (_, built) = fixture
    val texts = built.nodeTexts.toMap
    assertEquals(texts(leaf(built, 1).ref), "hello ghost")
    assertEquals(texts(leaf(built, 2).ref), "warrior")
    assertEquals(texts(leaf(built, 3).ref), "world")
    assertEquals(texts(leaf(built, 9).ref), "")
    assertEquals(texts(leaf(built, 10).ref), "hello world ghost")
    val groups = built.groupByRef.map((ref, group) => group.ordinal -> ref)
    assertEquals(texts(groups(1)), "world")
    assertEquals(texts(groups(2)), "ghost")
    val lexical = built.lexicalTexts.toMap
    assertEquals(lexical(leaf(built, 2).ref), "warrior. hello hello hello")
    assertEquals(lexical(groups(1)), "world. hello")
    assertEquals(
      built.document,
      "alpha\nbeta\nworld\ndelta\nepsilon\nzeta\neta\ntheta\niota\nkappa"
    )
  }

  test("concrete raw cosine and weighted prices match independent scalar oracles") {
    withChannel { (recall, built, base) =>
      val unit = recall.ordered.head
      val node = leaf(built, 3)
      val raw = base.semantic(unit, node).toOption.get
      assertEqualsDouble(raw, 1.0 / 3.0, 1e-7)
      assertEqualsDouble(
        checked(EmbeddingMetric.HalfCosineDistance.fromCosine(raw)).value,
        1.0 / 6.0,
        1e-7
      )
      val model = DefaultLocalCostModel(semantic = base.semantic)
      val cost = model.cost(unit, node, FidelityMode.Faithful, built.view)
      assertEqualsDouble(cost.term(CostTerm.Semantic), 1.0 / 3.0, 1e-7)
      assertEqualsDouble(cost.total, (1.0 / 3.0 + 0.60) * 67.0 / 64.0, 1e-7)
      val group = built.groupByRef.collectFirst {
        case (ref, g) if g.ordinal == 1 => built.view.node(ref).get
      }.get
      assertEqualsDouble(
        model.cost(unit, group, FidelityMode.Faithful, built.view).total,
        (1.0 / 3.0 + 0.45) * 67.0 / 64.0,
        1e-7
      )
      val assessed = cost.support.asInstanceOf[SupportAssessment.Assessed]
      assertEqualsDouble(assessed.basis.measuredWeight, 3.2, 1e-12)
      assertEqualsDouble(assessed.basis.eligibleWeight, 3.35, 1e-12)
      assertEqualsDouble(assessed.share, 64.0 / 67.0, 1e-12)
      assertEquals(cost.missingTerms, Set(CostTerm.Chart, CostTerm.Structural, CostTerm.Sensory))
      assertEquals(cost.imputedTerms, Map.empty[CostTerm, MissingReason])
    }
  }

  test(
    "historical over-one raw scores are nominated raw and priced clamped without half conversion"
  ) {
    val (recall, built) = fixture
    val raw = SemanticDistance((_, _) => Estimate.observed(1.5))
    val candidates = CandidateGenerator(raw, 8, false).generate(recall.ordered, built.view)
    assertEquals(candidates.set(recall.ordered.head.id).nominations.head.rawScore, Some(1.5))
    val cost = DefaultLocalCostModel(semantic = raw).cost(
      recall.ordered.head,
      leaf(built, 3),
      FidelityMode.Faithful,
      built.view
    )
    assertEquals(cost.term(CostTerm.Semantic), 1.0)
    assertEqualsDouble(cost.total, (1.0 + 0.60) * 67.0 / 64.0, 1e-12)
  }

  test(
    "alpha0.8 assigns distinct raw scores to different endpoints rather than merely preserving a multiset"
  ) {
    withChannel { (recall, built, base) =>
      val unit = recall.ordered.head
      val first = leaf(built, 1)
      val second = leaf(built, 2)
      val a = 1.0 - 5.0 / (3.0 * math.sqrt(3.0))
      val b = 1.0 - 4.0 / math.sqrt(18.0)
      assertEqualsDouble(base.semantic(unit, first).toOption.get, a, 1e-7)
      assertEqualsDouble(base.semantic(unit, second).toOption.get, b, 1e-7)
      val blend = LexicalBlend.blended(
        base.semantic,
        recall.ordered,
        built.view,
        built.lexicalTexts,
        actual.alpha,
        actual.fields
      )
      assertEqualsDouble(blend(unit, first).toOption.get, b, 1e-7)
      assertEqualsDouble(blend(unit, second).toOption.get, a, 1e-7)
    }
  }

  test("top-eight cutoff preserves the historical reference-key tie order") {
    withChannel { (recall, built, base) =>
      val blend = LexicalBlend.blended(
        base.semantic,
        recall.ordered,
        built.view,
        built.lexicalTexts,
        actual.alpha,
        actual.fields
      )
      val candidates = CandidateGenerator(blend, actual.perLevel, actual.lexicalOverlap)
        .generate(recall.ordered, built.view)
      val selected = candidates
        .set(recall.ordered.head.id)
        .nominations
        .filter(n => built.view.node(n.ref).get.level == 0)
      assertEquals(selected.map(_.ref), Vector(2, 1, 9, 3, 4, 5, 6, 7).map(n => leaf(built, n).ref))
      assertEquals(selected.map(_.rank), (0 until 8).toVector)
      assert(selected.forall(_.receipt.contains("level:0")))
      assertEquals(
        base.semantic(recall.ordered.head, leaf(built, 7)),
        base.semantic(recall.ordered.head, leaf(built, 8))
      )
    }
  }

  test(
    "both-side failures and the all-abstained query retain historical typed missing and unranked state"
  ) {
    withChannel { (recall, built, base) =>
      val missing = Estimate.missing(MissingReason.ProviderAbstained)
      assertEquals(base.semantic(recall.ordered.head, leaf(built, 10)), missing)
      assertEquals(base.semantic(recall.ordered(1), leaf(built, 3)), missing)
      assertEquals(base.semantic(recall.ordered(1), leaf(built, 10)), missing)
      val candidates =
        CandidateGenerator(base.semantic, 8, false).generate(recall.ordered, built.view)
      assert(candidates.abstained(recall.ordered(1).id))
      val evidence = checked(
        LocalEvidence.compute(
          recall,
          built.view,
          candidates,
          DefaultLocalCostModel(semantic = base.semantic),
          gate = true
        )
      )
      assertEquals(evidence.states(1), Vector(AlignState.External(ExternalState.Unranked)))
      assertEquals(evidence.provenance, CandidateProvenance.Unattested)
    }
  }

  test(
    "ordering scale changes the transition configuration and leaves originating local prices unchanged"
  ) {
    withChannel { (recall, built, base) =>
      val candidates =
        CandidateGenerator(base.semantic, 8, false).generate(recall.ordered, built.view)
      val model = DefaultLocalCostModel(semantic = base.semantic)
      val evidence =
        checked(LocalEvidence.compute(recall, built.view, candidates, model, gate = true))
      val one = RecallOrderControl.scaledConfig(1.0)
      val historical = RecallOrderControl.scaledConfig(actual.orderingScale)
      assertNotEquals(one.fingerprint, historical.fingerprint)
      val a = checked(GraphHsmm.infer(recall, built.view, evidence, one))
      val b = checked(GraphHsmm.infer(recall, built.view, evidence, historical))
      assertEquals(a.costs, b.costs)
      assertEquals(a.costs, evidence.units.zip(evidence.breakdowns).toMap)
      assertEquals(b, checked(GraphHsmm.infer(recall, built.view, candidates, model, historical)))
    }
  }
