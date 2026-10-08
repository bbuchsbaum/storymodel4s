package storymodel4s.bench.video

import cats.data.NonEmptyVector
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.HsmmResultCodec
import storymodel4s.core.*
import storymodel4s.embed.*
import storymodel4s.embed.onnx.*
import storymodel4s.features.Estimate
import storymodel4s.recall.*

/** Synthetic engineering courts bind the producer, rather than trusting a result/config label. */
class StageTraceOriginSuite extends FunSuite:
  private def value[A, E](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def recall(text: String) = RecallSegmenter.segment(value(StorySource.fromText(text)))
  private def built = value(
    TimedSourceView.build(
      Vector(TimedSegment(1, "hello", None), TimedSegment(2, "world", None)),
      WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied)
    )
  )
  private def candidates(r: RecallGraph[RecallGraphStatus.Checked], b: TimedSourceView.Built) =
    CandidateGenerator(SemanticDistance.lexicalJaccard, 8).generate(r.ordered, b.view)
  private def trace(run: StageTrace.Run): Json = value(
    parse(
      value(
        StageTrace.render(
          run,
          Vector.empty,
          run.result.posterior.rows.map(_.mapSource),
          Checksum.ofBytes(Array.emptyByteArray),
          None
        )
      )
    )
  )
  private def history(
      r: RecallGraph[RecallGraphStatus.Checked],
      b: TimedSourceView.Built,
      config: HsmmConfig = HsmmConfig.default
  ) =
    value(StageTrace.historical(r, b, candidates(r, b), DefaultLocalCostModel(), config))

  test("historical pricing occurs once and repeated rendering adds no calls") {
    val r = recall("Hello. World."); val b = built
    val delegate = DefaultLocalCostModel()
    var calls = 0
    val counted = new LocalCostModel:
      def externalFloor: Double = delegate.externalFloor
      def externalCost(u: RecallUnit, s: ExternalState): Double = delegate.externalCost(u, s)
      def cost(u: RecallUnit, n: NodeSummary, m: FidelityMode, v: SourceView): CostBreakdown =
        calls += 1
        delegate.cost(u, n, m, v)
    val run = value(StageTrace.historical(r, b, candidates(r, b), counted, HsmmConfig.default))
    val priced = run.evidence.breakdowns.flatMap(_.keys).count(_.isSource)
    assert(priced > 0)
    assertEquals(calls, priced)
    val first = trace(run)
    assertEquals(trace(run), first)
    assertEquals(calls, priced)
    assertEquals(
      first.hcursor.downField("localEvidence").get[String]("identity").toOption,
      Some(run.evidence.identity.toString)
    )
    assertEquals(
      first.hcursor
        .downField("localEvidence")
        .downField("provenance")
        .get[String]("kind")
        .toOption,
      Some("Unattested")
    )
  }

  test("one-unit identical results cannot establish the executed transition configuration") {
    val r = recall("hello"); val b = built
    val changed = value(
      HsmmConfig.of(transitions =
        TransitionModel(Map(TransitionKind.Stay -> 20.0, TransitionKind.ExternalIn -> 10.0))
      )
    )
    val a = history(r, b); val z = history(r, b, changed)
    assertEquals(a.result, z.result)
    assertEquals(a.evidence, z.evidence)
    assertNotEquals(a.config.fingerprint, z.config.fingerprint)
    assertNotEquals(a, z)
    assertNotEquals(
      trace(a).hcursor.downField("inferenceConfig").focus,
      trace(z).hcursor.downField("inferenceConfig").focus
    )
  }

  test("actual inference uses the requested competing-state configuration") {
    val r = recall("Hello. World."); val b = built
    assertEquals(r.ordered.size, 2)
    val cfg = value(
      HsmmConfig.of(
        temperature = 0.8,
        transitions = TransitionModel(
          Map(
            TransitionKind.Stay -> 8.0,
            TransitionKind.ExternalIn -> -4.0,
            TransitionKind.ExternalStay -> -4.0
          )
        )
      )
    )
    val run = history(r, b, cfg)
    val oracle = value(GraphHsmm.infer(r, b.view, run.evidence, cfg))
    val other = value(GraphHsmm.infer(r, b.view, run.evidence, HsmmConfig.default))
    assertNotEquals(oracle.posterior, other.posterior, "fixture must discriminate configurations")
    assertEquals(run.result, oracle)
    assert(run.config eq cfg)
    assertEquals(
      trace(run).hcursor.downField("inferenceConfig").get[Double]("temperature").toOption,
      Some(0.8)
    )
  }

  test("equal results retain different originating nomination receipts") {
    val r = recall("hello"); val b = built; val c = candidates(r, b)
    val changed = Candidates(c.byUnit.map { (id, set) =>
      id -> CandidateSet(
        set.nominations.map(
          _.copy(space = Some("declared-space"), receipt = Some("distinct-receipt"))
        ),
        set.abstained
      )
    })
    def run(cs: Candidates) =
      value(StageTrace.historical(r, b, cs, DefaultLocalCostModel(), HsmmConfig.default))
    val a = run(c); val z = run(changed)
    assertEquals(a.result, z.result)
    assertNotEquals(a.evidence.identity, z.evidence.identity)
    val first = trace(z).hcursor.downField("units").downArray.downField("nominations").downArray
    assertEquals(first.get[String]("receipt").toOption, Some("distinct-receipt"))
    assertEquals(first.get[String]("space").toOption, Some("declared-space"))
    assertNotEquals(trace(a), trace(z))
  }

  test("complete base cost records retain support imputation reduction and exclusion fields") {
    val run = history(recall("hello"), built)
    val actual = trace(run).hcursor.downField("baseCosts").focus.get
    val expected = value(HsmmResultCodec.toJson(run.result)).hcursor.downField("costs").focus.get
    assertEquals(actual, expected)
    val cells = actual.asArray.get.flatMap(_.hcursor.downField("costs").focus.get.asArray.get)
    assert(cells.nonEmpty)
    val required = Set(
      "terms",
      "mode",
      "exclusion",
      "total",
      "missingTerms",
      "sourceChartCoverage",
      "reductions",
      "support",
      "imputedTerms"
    )
    cells.foreach { cell =>
      val keys = cell.hcursor.downField("cost").focus.get.asObject.get.keys.toSet
      assert(
        Set("terms", "total", "missingTerms", "reductions", "support", "imputedTerms").subsetOf(
          keys
        )
      )
      assert(keys.subsetOf(required))
    }
  }

  test("positive refinement refuses a trace even when zero weight preserves base numbers") {
    val r = recall("Hello. World."); val b = built
    val cfg = value(HsmmConfig.of(refinementPasses = 1, refinementWeight = 0.0))
    val run = history(r, b, cfg)
    assertEquals(run.result.refinementPasses, 1)
    assertEquals(run.result.costs, history(r, b).result.costs)
    assert(
      StageTrace
        .render(
          run,
          Vector.empty,
          run.result.posterior.rows.map(_.mapSource),
          Checksum.ofBytes(Array.emptyByteArray),
          None
        )
        .left
        .exists(_.message.contains("refined"))
    )
  }

  test("foreign recall source gate and downstream choices refuse instead of relabelling") {
    val r = recall("hello"); val b = built; val run = history(r, b)
    assert(StageTrace.infer(recall("world"), b, run.evidence, HsmmConfig.default).isLeft)
    val other = value(
      TimedSourceView.build(
        Vector(TimedSegment(1, "ghost", None)),
        WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied)
      )
    )
    assert(StageTrace.infer(r, other, run.evidence, HsmmConfig.default).isLeft)
    val ungated =
      value(LocalEvidence.compute(r, b.view, candidates(r, b), DefaultLocalCostModel(), false))
    assert(StageTrace.infer(r, b, ungated, HsmmConfig.default).isLeft)
    assert(
      StageTrace
        .render(run, Vector.empty, Vector.empty, Checksum.ofBytes(Array.emptyByteArray), None)
        .isLeft
    )
    val foreign = SourceNodeRef.Situation(SituationId.unsafe("foreign"))
    assert(
      StageTrace
        .render(
          run,
          Vector.empty,
          Vector(Some(foreign)),
          Checksum.ofBytes(Array.emptyByteArray),
          None
        )
        .isLeft
    )
  }

  private def registeredFixture(query: String = "hello") =
    val b = built
    val r = recall(query)
    val source = value(StorySource.fromText(b.document))
    val representation = value(
      SourceRepresentation.of(
        b.view,
        NonEmptyVector.one(BundleEntry.text(source.canonicalChecksum)),
        None,
        b.view.nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
      )
    )
    val context = value(
      SurfaceRendering.prepare(r, b.view, representation, SurfaceAnalyzer.analyze(source))
    )
    val config = value(
      OnnxMappingRegistration.Config.of(
        value(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(8), false, None)),
        Sensitivity.Public,
        Sensitivity.Public
      )
    )
    val cwd = Path.of("").toAbsolutePath.normalize()
    val root = Vector(cwd, cwd.getParent)
      .find(p => Files.isRegularFile(p.resolve("embed-onnx/src/test/resources/fixture/model.onnx")))
      .get
    val encoder = HistoricalParityFixture.open(root)
    val original =
      try value(OnnxMappingRegistration.record(encoder, context, config))
      finally encoder.close()
    (b, original)

  test("registered and replayed origin survives encoder closure without new provider calls") {
    val (_, original) = registeredFixture()
    val replayed = value(
      original.replay(
        original.context,
        original.config,
        HistoricalParityFixture.model,
        original.queryRecord.provider
      )
    )
    val recorded = value(StageTrace.registered(original, HsmmConfig.default))
    val replay = value(StageTrace.registered(replayed, HsmmConfig.default))
    assert(recorded.evidence eq original.evidence)
    assertEquals(recorded.evidence.identity, replay.evidence.identity)
    assertEquals(recorded.result, replay.result)
    assertEquals(
      trace(recorded).hcursor.downField("registration").get[String]("basis").toOption,
      Some("Recorded")
    )
    assertEquals(
      trace(replay).hcursor.downField("registration").get[String]("basis").toOption,
      Some("Replayed")
    )
    assertEquals(original.queryRecord.result.receipt.providerCalls.size, 1)
    assertEquals(original.documentRecord.result.receipt.providerCalls.size, 1)
    assertEquals(trace(replay), trace(replay))
    assert(!trace(recorded).noSpaces.contains("hello"), "trace leaked submitted prose")
  }

  test("generic strict evidence cannot acquire registered execution authority") {
    val (b, original) = registeredFixture()
    val run =
      value(StageTrace.infer(original.context.recall, b, original.evidence, HsmmConfig.default))
    assertEquals(trace(run).hcursor.downField("registration").focus, Some(Json.Null))
    assertEquals(
      trace(run).hcursor
        .downField("localEvidence")
        .downField("provenance")
        .get[String]("kind")
        .toOption,
      Some("Strict")
    )
  }

  test("execution-failed unranked units retain attempts and no invented local mass") {
    val (_, original) = registeredFixture("hello world ghost")
    val run = value(StageTrace.registered(original, HsmmConfig.default))
    val json = trace(run)
    val unit = json.hcursor.downField("units").downArray
    assertEquals(
      unit.downField("localComparison").get[String]("status").toOption,
      Some("NotComputed")
    )
    val states = unit.downField("states").focus.get.asArray.get
    assertEquals(states.size, 1)
    assertEquals(states.head.hcursor.get[String]("state").toOption, Some("ext:Unranked"))
    assertEquals(states.head.hcursor.downField("localMass").focus, Some(Json.Null))
    assertEquals(states.head.hcursor.get[Double]("posteriorMass").toOption, Some(1.0))
    assertEquals(
      states.head.hcursor.get[Double]("cost").toOption,
      Some(run.evidence.breakdowns.head(AlignState.unranked).total)
    )
    assertEquals(
      json.hcursor
        .downField("registration")
        .downField("queryAttempt")
        .downField("outcomes")
        .downArray
        .downField("outcome")
        .get[String]("kind")
        .toOption,
      Some("TooLong")
    )
    assert(
      unit
        .downField("candidateAccounting")
        .downField("semanticOutcomes")
        .focus
        .get
        .noSpaces
        .contains("endpoint-execution-failed")
    )
  }

  test("unavailable versus uniform withholding retain their distinct populations") {
    val r = recall("hello"); val b = built
    val uniform = value(
      StrictSemanticChannel.ContentTable.projected(
        r,
        b.view,
        b.view.nodes.map(n => (r.ordered.head.id -> n.ref) -> Estimate.observed(0.25))
      )
    )
    def run(channel: StrictSemanticChannel) =
      val cfg = value(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(1), false, None))
      val c = value(StrictCandidateGenerator.canonical(channel, cfg, r, b.view))
      val e = value(LocalEvidence.compute(r, b.view, c, value(StrictCostModel.of(channel))))
      value(StageTrace.infer(r, b, e, HsmmConfig.default))
    val a = run(StrictSemanticChannel.Unavailable); val z = run(uniform)
    assertNotEquals(a.result, z.result)
    assertNotEquals(a.evidence.identity, z.evidence.identity)
    val unavailable = trace(a).hcursor.downField("units").downArray.downField("candidateAccounting")
    val same = trace(z).hcursor.downField("units").downArray.downField("candidateAccounting")
    assert(
      unavailable.downField("semanticOutcomes").focus.get.noSpaces.contains("ChannelUnavailable")
    )
    assertEquals(unavailable.downField("uniformSemantic").focus.get.asArray.get.size, 0)
    assertEquals(same.downField("uniformSemantic").focus.get.asArray.get.size, 1)
    assertEquals(
      same.downField("semanticOutcomes").downArray.get[Int]("observed").toOption,
      Some(2)
    )
  }

  test("tie overflow accounting survives even when every level is withheld") {
    val r = recall("hello")
    val b = value(
      TimedSourceView.build(
        Vector(
          TimedSegment(1, "hello", None),
          TimedSegment(2, "world", None),
          TimedSegment(3, "ghost", None)
        ),
        WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied)
      )
    )
    val table = value(
      StrictSemanticChannel.ContentTable.projected(
        r,
        b.view,
        b.view.nodes.zipWithIndex.map((n, i) =>
          (r.ordered.head.id -> n.ref) ->
            Estimate.observed(if i < 2 then 0.0 else 0.5)
        )
      )
    )
    val cfg = value(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(1), false, None))
    val c = value(StrictCandidateGenerator.canonical(table, cfg, r, b.view))
    val e = value(LocalEvidence.compute(r, b.view, c, value(StrictCostModel.of(table))))
    val run = value(StageTrace.infer(r, b, e, HsmmConfig.default))
    val row = trace(run).hcursor.downField("units").downArray
    val overflow = row.downField("candidateAccounting").downField("tieOverflow").downArray
    assertEquals(overflow.get[Int]("unionSize").toOption, Some(2))
    assertEquals(overflow.get[Int]("budget").toOption, Some(1))
    assertEquals(row.downField("nominations").focus.get.asArray.get.size, 0)
    assertEquals(row.downField("localComparison").get[String]("status").toOption, Some("Computed"))
    assert(
      row
        .downField("states")
        .focus
        .get
        .asArray
        .get
        .forall(_.hcursor.downField("anchor").focus.contains(Json.Null))
    )
  }
