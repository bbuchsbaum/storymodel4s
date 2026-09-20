package storymodel4s.bench

import java.nio.file.Paths

import scala.compiletime.testing.typeCheckErrors
import scala.io.Source

import cats.data.NonEmptyVector
import munit.FunSuite

import storymodel4s.align.*
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.embed.onnx.{OnnxSentenceArtifacts, OnnxSentenceEmbedder, OnnxSentenceModel}
import storymodel4s.fixtures.wog.{WarOfTheGhostsExpectations, WarOfTheGhostsText}
import storymodel4s.recall.{RecallGraph, RecallRelations}

/** End-to-end: the WOG diagnostic cases through the free channels. The numbers are regression
  * material, not evidence (WOG never selects); what the suite pins is that the harness runs the
  * proven pipeline, labels honestly, reports absence honestly, and leaks no text.
  */
class WogDiagnosticSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private lazy val report: BenchReport =
    Bench
      .run(
        WogDiagnostic.cases,
        WogDiagnostic.factories(dimension = 256, seed = 7L),
        ProtocolDocument.pinned,
        BenchConfig(seed = 11L, resamples = 50)
      )
      .fold(e => fail(e.message), identity)

  private def routeCase(id: String, goldAnchors: Vector[SourceNodeRef]): BenchCase =
    val base = WogDiagnostic.fullRecallCase
    val units = base.recall.ordered.take(goldAnchors.size)
    assertEquals(units.size, goldAnchors.size, "the fixture has too few recall units")
    val recall = RecallGraph
      .validated(
        base.recall.transcript,
        base.recall.atlas,
        units,
        RecallRelations.empty.copy(entities = base.recall.relations.entities)
      )
      .fold(errors => fail(s"invalid route recall: $errors"), identity)
    val gold = Gold
      .validated(
        units.zip(goldAnchors).map { case (unit, anchor) =>
          val level =
            base.view.node(anchor).fold(fail(s"unknown source node ${anchor.key}"))(_.level)
          GoldUnit.anchored(unit.id, NonEmptyVector.one(GoldTarget(anchor, level)))
        },
        base.view
      )
      .fold(e => fail(e.message), identity)
    BenchCase(id, base.storyId, base.family, base.origin, base.story, base.view, recall, gold)

  private def resultFor(
      c: BenchCase,
      inferredAnchors: Vector[Option[SourceNodeRef]],
      absentState: ExternalState = ExternalState.Unranked
  ): HsmmResult =
    val units = c.recall.ordered
    assertEquals(units.size, inferredAnchors.size, "one inferred anchor is required per unit")
    val states = units.zip(inferredAnchors).map { case (unit, anchor) =>
      anchor match
        case None      => AlignState.External(absentState)
        case Some(ref) =>
          val node = c.view.node(ref).fold(fail(s"unknown source node ${ref.key}"))(identity)
          val mode = ModeGate
            .assess(unit, node, c.view)
            .modes
            .headOption
            .fold(fail(s"no admissible mode for ${unit.id.value} -> ${ref.key}"))(identity)
          AlignState.anchored(ref, mode)
    }
    val rows = units.zip(states).map { case (unit, state) =>
      AlignmentRow.of(unit.id, Map(state -> 1.0)).fold(e => fail(e.message), identity)
    }
    val posterior = AlignmentMatrix.of(rows).fold(e => fail(e.message), identity)
    val flow = TransitionFlow(
      units.indices
        .drop(1)
        .map { i =>
          FlowStep(units(i - 1).id, units(i).id, Map((states(i - 1), states(i)) -> 1.0))
        }
        .toVector
    )
    val candidates = units
      .zip(inferredAnchors)
      .map { case (unit, anchor) =>
        unit.id -> anchor.toVector
      }
      .toMap
    HsmmResult
      .validated(
        c.recall,
        c.view,
        candidates,
        posterior,
        flow,
        states,
        logLikelihood = 0.0,
        costs = Map.empty,
        refinementPasses = 0
      )
      .fold(e => fail(e.message), identity)

  private def observedValue(observation: MetricObservation): Double = observation match
    case MetricObservation.Observed(value) => value
    case other                             => fail(s"expected an observed value, got $other")

  private lazy val leafRefs: Vector[SourceNodeRef] =
    WogDiagnostic.view.nodes
      .filter(_.level == 0)
      .sortBy(n => WogDiagnostic.view.measuredPosition(n.ref).get)
      .map(_.ref)

  test("the neural facade admits the closed ONNX encoder, not a caller-labelled lexical embedder") {
    val admitted = typeCheckErrors(
      """(embedder: storymodel4s.embed.onnx.OnnxSentenceEmbedder) =>
        storymodel4s.bench.BenchChannels.neural(
          embedder,
          Vector.empty[storymodel4s.recall.RecallUnit],
          Vector.empty[(storymodel4s.align.SourceNodeRef, String)]
        )"""
    )
    assert(admitted.isEmpty, admitted.mkString("\n"))

    val relabelled = typeCheckErrors(
      """storymodel4s.bench.BenchChannels.neural(
        storymodel4s.embed.HashedNgramEmbedder[cats.Id](32, 0L),
        Vector.empty[storymodel4s.recall.RecallUnit],
        Vector.empty[(storymodel4s.align.SourceNodeRef, String)]
      )"""
    )
    assert(relabelled.nonEmpty, "a lexical embedder was admitted as a neural encoder")
  }

  test("channel checksums use full identities even when report labels share display prefixes") {
    def channel(suffix: Char): Channel =
      val shared = "0123456789ab"
      val provider = storymodel4s.embed.ProviderFingerprint(
        storymodel4s.core.Checksum.unsafe(shared + suffix.toString * 52)
      )
      Channel(
        "collision-court",
        SemanticDistance.abstaining,
        SemanticIdentity(
          provider,
          storymodel4s.embed.GeometryId.unsafe(shared + s"-query-$suffix"),
          storymodel4s.embed.GeometryId.unsafe(shared + s"-document-$suffix"),
          storymodel4s.embed.GeometryPairRule.IdenticalModelling,
          0,
          SemanticChannelKind.NeuralEncoder
        ),
        StructuralDistance.missing,
        StructuralIdentity.Absent("collision court"),
        ChannelExposure.Memorizing
      )

    val left = channel('a')
    val right = channel('b')
    assertEquals(left.render, right.render, "court must collide in the truncated display")
    assertNotEquals(left.identityChecksum, right.identityChecksum)
  }

  test("WOG is wired as diagnostic cases: one per paraphrase plus one full recall") {
    val cases = WogDiagnostic.cases
    assertEquals(cases.size, WarOfTheGhostsExpectations.recallParaphrases.size + 1)
    assertEquals(WogDiagnostic.paraphraseCases.map(_.recall.size).distinct, Vector(1))
    assert(cases.forall(!_.origin.isFrozen))
    assert(
      WogDiagnostic.fullRecallCase.recall.size >= WarOfTheGhostsExpectations.recallParaphrases.size
    )
    assert(WogDiagnostic.fullRecallCase.gold.size == WogDiagnostic.fullRecallCase.recall.size)
  }

  test("the report is Diagnostic because of the cases' origin, even under the pinned protocol") {
    report match
      case BenchReport.Diagnostic(DiagnosticReason.DiagnosticOrigin(ids), channels, _) =>
        assertEquals(ids.size, WogDiagnostic.cases.size)
        assertEquals(channels.size, 2)
      case other => fail(s"expected DiagnosticOrigin, got ${other.label}")
  }

  test("the committed WOG comparison names lexical controls and the real MiniLM encoder") {
    val source = Source.fromResource("onnx/wog-minilm-comparison.txt")
    val golden =
      try source.mkString.trim
      finally source.close()
    assert(golden.startsWith("embed-bench report: DIAGNOSTIC"))
    assert(golden.contains("semantic=lexical-baseline:"))
    assert(golden.contains("semantic=neural-encoder:"))
    assert(golden.contains("comparison delta"))
    assert(
      golden.linesIterator
        .find(_.contains("semantic=neural-encoder:"))
        .exists(_.endsWith("; memorizing]"))
    )

    val supplied = for
      model <- sys.env.get("STORYMODEL4S_ONNX_MODEL")
      tokenizer <- sys.env.get("STORYMODEL4S_ONNX_TOKENIZER")
    yield (Paths.get(model), Paths.get(tokenizer))
    supplied.foreach { case (modelPath, tokenizerPath) =>
      val embedder = OnnxSentenceEmbedder
        .open(
          OnnxSentenceModel.AllMiniLmL6V2,
          OnnxSentenceArtifacts(modelPath, tokenizerPath)
        )
        .fold(error => fail(error.message), identity)
      try
        val actual = Bench
          .run(
            WogDiagnostic.cases,
            WogDiagnostic.comparisonFactories(embedder, dimension = 256, seed = 7L),
            ProtocolDocument.pinned,
            BenchConfig(seed = 11L, resamples = 50)
          )
          .fold(error => fail(error.message), identity)
        val neural = actual.channelReports
          .find(_.channel.semanticIdentity.kind == SemanticChannelKind.NeuralEncoder)
          .getOrElse(fail("missing neural channel"))
        assertEquals(neural.channel.exposure, ChannelExposure.Memorizing)
        assertEquals(WogDiagnostic.comparisonRendering(actual).trim, golden)
      finally embedder.close()
    }
  }

  test("every case ran through the proof: no failures, fingerprints recorded, metrics observed") {
    report.channelReports.foreach { cr =>
      assertEquals(cr.failures, Vector.empty, cr.failures.toString)
      assertEquals(cr.runs.size, WogDiagnostic.cases.size)
      cr.runs.foreach(r =>
        assertEquals(r.viewFingerprint, storymodel4s.align.ViewFingerprint.of(WogDiagnostic.view))
      )
      val observed = (cr.metrics ++ cr.openWorld).filter(_.value.isObserved).map(_.name).toSet
      Metrics.Ks.foreach(k => assert(observed.contains(Metrics.Names.strictRecall(k)), observed))
      assert(observed.contains(Metrics.Names.mrr))
      assert(observed.contains(Metrics.Names.candidateBurden))
      assert(observed.contains(Metrics.Names.falseGating))
      assert(observed.contains(Metrics.Names.externalRule))
      assert(observed.contains(Metrics.Names.blendCoverage))
    }
  }

  test("an aggregate importance overflow is recorded as an exact case failure") {
    val base = WogDiagnostic.fullRecallCase
    val overflowingLeaves = base.view.leaves.take(2).map(_.ref).toSet
    assertEquals(overflowingLeaves.size, 2, "WOG fixture needs two leaves for overflow")
    val edges = RelationLayer.values.toVector.map { layer =>
      val triples = base.view.adjacency(layer).toVector.flatMap { case (from, targets) =>
        targets.toVector.map { case (to, weight) => (from, to, weight) }
      }
      layer -> triples
    }.toMap
    val overflowingView = InMemorySourceView(
      base.view.nodes.map(n =>
        if overflowingLeaves(n.ref) then
          n.copy(importance = ImportanceWeight.unsafe(Estimate.observed(Double.MaxValue)))
        else n
      ),
      edges,
      base.view.worldOrder,
      base.view.scoringLength
    )
    val overflowingCase = base.copy(id = "wog:overflowing-importance", view = overflowingView)
    val result = Bench
      .run(
        Vector(overflowingCase),
        WogDiagnostic.factories(dimension = 32, seed = 7L).take(1),
        ProtocolDocument.pinned,
        BenchConfig(seed = 11L, resamples = 1)
      )
      .fold(e => fail(e.message), identity)
    val channel = result.channelReports.headOption.getOrElse(fail("bench produced no channel"))

    assertEquals(channel.runs, Vector.empty, "a refused signature became a successful case run")
    channel.failures match
      case Vector(
            ("wog:overflowing-importance", AlignError.MalformedRecord("weightedCoverage", d))
          ) =>
        assert(d.contains("not finite"), d)
      case other => fail(s"signature refusal was dropped or changed: $other")
  }

  test(
    "absence is reported, not scored: WOG carries no charts, so the structural term has zero coverage"
  ) {
    report.channelReports.foreach { cr =>
      assert(
        cr.channel.structuralIdentity.isInstanceOf[StructuralIdentity.Absent],
        cr.channel.render
      )
      val structural = cr.metrics.find(_.name == Metrics.Names.structuralTermCoverage).get
      assertEquals(structural.value.toOption, Some(0.0))
      val semantic = cr.metrics.find(_.name == Metrics.Names.semanticTermCoverage).get
      assert(semantic.value.toOption.exists(_ > 0.0), semantic.render)
    }
  }

  test("open-world metrics are never pooled with source-anchor metrics") {
    report.channelReports.foreach { cr =>
      assert(cr.openWorld.forall(m => Metrics.Names.openWorld.contains(m.name)))
      assert(cr.metrics.forall(m => !Metrics.Names.openWorld.contains(m.name)))
      val rule = cr.openWorld.find(_.name == Metrics.Names.externalRule).get
      // Eligibility is exactly the units whose gold groundedness this rule can score (here: the
      // association paraphrase and its unit(s) inside the full recall), never every recall unit.
      val expected = WogDiagnostic.cases
        .flatMap(_.gold.byUnit.values)
        .count(g =>
          g.groundedness == Groundedness.Association || g.groundedness == Groundedness.Intrusion ||
            g.groundedness == Groundedness.Uninterpretable
        )
      assert(expected >= 2, expected)
      assertEquals(rule.coverage.observed, expected, rule.render)
      assertEquals(rule.coverage.eligible, expected, rule.render)
    }
  }

  private def unrankedObservations(c: BenchCase): CaseObservations =
    val semantic = SemanticDistance.abstaining
    val candidates = CandidateGenerator(semantic, lexicalOverlap = false)
      .generate(c.recall.ordered, c.view)
    assert(c.recall.ordered.forall(u => candidates.abstained(u.id)))
    val result = GraphHsmm
      .infer(c.recall, c.view, candidates, DefaultLocalCostModel(semantic = semantic))
      .fold(e => fail(e.message), identity)
    Metrics.observe(c, result)

  private def caseWith(groundedness: Groundedness): BenchCase =
    WogDiagnostic.paraphraseCases
      .find(_.gold.byUnit.values.exists(_.groundedness == groundedness))
      .getOrElse(fail(s"no $groundedness diagnostic case"))

  private def assertMissing(c: BenchCase, observations: CaseObservations, metric: String): Unit =
    val values = observations.byMetric(metric)
    assertEquals(values.size, 1)
    assertEquals(
      values.head.observation,
      MetricObservation.Missing(MissingReason.ProviderAbstained),
      metric
    )
    val aggregate = Metrics.aggregate(metric, Vector(observations), Vector(c.inputChecksum), 1L)
    assertEquals(aggregate.value, Estimate.missing(MissingReason.ProviderAbstained), metric)
    assertEquals(aggregate.coverage.eligible, 1, metric)
    assertEquals(aggregate.coverage.observed, 0, metric)

  test("unrankable open-world units abstain from every eligible metric") {
    val association = caseWith(Groundedness.Association)
    val associationObs = unrankedObservations(association)
    assertMissing(association, associationObs, Metrics.Names.externalRule)
    assertMissing(association, associationObs, Metrics.Names.externalSubtype)

    val inference = caseWith(Groundedness.Inference)
    val inferenceObs = unrankedObservations(inference)
    assertMissing(inference, inferenceObs, Metrics.Names.externalSubtype)
    assertMissing(inference, inferenceObs, Metrics.Names.inferenceMass)
    assertEquals(
      inferenceObs.byMetric(Metrics.Names.externalRule).head.observation,
      MetricObservation.Ineligible
    )
  }

  test(
    "the free channels recover the precise paraphrase's anchor within the top 10 (regression floor)"
  ) {
    report.channelReports.foreach { cr =>
      val r10 = cr.metrics.find(_.name == Metrics.Names.strictRecall(10)).get
      assert(r10.value.toOption.exists(_ > 0.0), s"${cr.channel.name}: ${r10.render}")
      val fg = cr.metrics.find(_.name == Metrics.Names.falseGating).get
      assertEquals(fg.value.toOption, Some(0.0), s"${cr.channel.name}: ${fg.render}")
    }
  }

  test("determinism: the same seed gives an identical rendered report") {
    val again = Bench
      .run(
        WogDiagnostic.cases,
        WogDiagnostic.factories(dimension = 256, seed = 7L),
        ProtocolDocument.pinned,
        BenchConfig(seed = 11L, resamples = 50)
      )
      .fold(e => fail(e.message), identity)
    assertEquals(again.render, report.render)
  }

  /** Sentences and shorter phrases of the source, so a partial leak is caught too: a report that
    * printed half a sentence would pass a whole-sentence check.
    */
  private lazy val storyProbes: Vector[String] =
    val sentences = WarOfTheGhostsText.text
      .split("(?<=[.!?])\\s+")
      .map(_.trim)
      .filter(_.length > 12)
      .toVector
    // Windows over the WHOLE word stream, not per sentence: a fragment that straddles a sentence
    // boundary is still the story's text, and a per-sentence probe set cannot see it. That gap was
    // real — a six-word slice spanning the title and the opening line passed the earlier version.
    val words = WarOfTheGhostsText.text.split("\\s+").toVector.filter(_.nonEmpty)
    val windows =
      if words.size < 6 then Vector.empty else words.sliding(6).map(_.mkString(" ")).toVector
    sentences ++ windows

  private def leaks(text: String): Option[String] =
    storyProbes
      .find(text.contains)
      .orElse(WarOfTheGhostsExpectations.recallParaphrases.map(_.text).find(text.contains))

  test("the rendered report carries no story or recall text") {
    val rendered = report.render
    // The check must be capable of firing: a vacuous probe set, or a render that produced nothing,
    // would make every assertion below pass while looking rigorous.
    assert(storyProbes.size > 20, s"probe set is too small to be evidence: ${storyProbes.size}")
    assert(rendered.length > 200, s"render is too short to have been checked: ${rendered.length}")
    // Positive control: the detector finds a leak when there is one to find.
    assert(
      leaks(rendered + " " + storyProbes.head).contains(storyProbes.head),
      "the canary cannot detect a leak it is shown, so its silence means nothing"
    )
    assertEquals(leaks(rendered), None, s"leaked: ${leaks(rendered).map(_.take(30))}")
    assert(rendered.contains("DIAGNOSTIC"))
    assert(rendered.contains("three clocks"))
  }

  test("the route metric actually fires on the multi-unit case, and abstains elsewhere") {
    // A metric that is always Missing is not a measurement. The full-recall case is the only one
    // with transitions, so it is the only place a route can exist.
    val runs = report.channelReports.flatMap(_.runs)
    val full = runs.filter(_.caseId == WogDiagnostic.fullRecallCase.id)
    assert(full.nonEmpty, "the full-recall case produced no run")
    val observed =
      full.flatMap(_.observations.byMetric(Metrics.Names.routeSupportMidpointDirection))
    assert(observed.nonEmpty, "route metric absent from the full-recall case")
    assert(
      observed.exists(_.observation.isInstanceOf[MetricObservation.Observed]),
      "route metric is Missing on every unit of the only case that has a route"
    )
    // The first unit of any case has no predecessor, so it must abstain rather than score.
    full.foreach { r =>
      val first =
        r.observations.byMetric(Metrics.Names.routeSupportMidpointDirection).head
      assertEquals(
        first.observation,
        MetricObservation.Ineligible,
        "the first unit cannot have a transition"
      )
    }
    // Single-unit paraphrase cases have no transition at all.
    val single = runs.filter(_.caseId != WogDiagnostic.fullRecallCase.id)
    single.foreach { r =>
      val vs = r.observations.byMetric(Metrics.Names.routeSupportMidpointDirection)
      assert(
        vs.forall(_.observation == MetricObservation.Ineligible),
        s"a single-unit case scored a route: ${r.caseId}"
      )
    }
  }

  test("the route metric uses the source-support midpoint axis on live WOG transitions") {
    val hashed = report.channelReports.find(_.channel.name.startsWith("hashed-ngram:")).getOrElse {
      fail("the deterministic hashed n-gram channel is absent")
    }
    val full = hashed.runs.find(_.caseId == WogDiagnostic.fullRecallCase.id).getOrElse {
      fail("the hashed n-gram channel did not run the full-recall case")
    }
    val route =
      full.observations.byMetric(Metrics.Names.routeSupportMidpointDirection)

    // These three deterministic transitions agree on the source-support midpoint axis. The retired
    // per-level-rank metric scored every one as disagreement: its gold and inferred anchors drew
    // their positions from independently restarting level scales.
    Vector(2, 4, 8).foreach { laterUnitIndex =>
      route(laterUnitIndex).observation match
        case MetricObservation.Observed(value) => assertEquals(value, 1.0)
        case other => fail(s"transition at index $laterUnitIndex was not observed: $other")
    }
  }

  test("present inferred anchor without scoring feature stays eligible missing") {
    val original = routeCase("missing-scoring-position", Vector(leafRefs(0), leafRefs(1)))
    val inferred = leafRefs(2)
    val refs = Vector(Some(inferred), Some(inferred))
    val control = Metrics.observe(original, resultFor(original, refs))
    assert(
      control
        .byMetric(Metrics.Names.routeSupportMidpointDirection)(1)
        .observation
        .isInstanceOf[MetricObservation.Observed]
    )
    val edges = RelationLayer.values.toVector.map { layer =>
      layer -> original.view.adjacency(layer).toVector.flatMap { (from, row) =>
        row.toVector.map { (to, weight) => (from, to, weight) }
      }
    }.toMap
    val view = InMemorySourceView(
      original.view.nodes.map { node =>
        if node.ref == inferred then node.copy(scoringPosition = None) else node
      },
      edges,
      original.view.worldOrder,
      original.view.scoringLength
    )
    val c = original.copy(view = view)
    assert(
      c.gold(c.recall.ordered.head.id)
        .flatMap(_.primary)
        .flatMap(g => view.measuredPosition(g.node))
        .nonEmpty
    )
    val observations = Metrics.observe(c, resultFor(c, refs))
    assertEquals(
      observations.byMetric(Metrics.Names.routeSupportMidpointDirection)(1).observation,
      MetricObservation.Missing(MissingReason.AllMissing)
    )
    val aggregate = Metrics.aggregate(
      Metrics.Names.routeSupportMidpointDirection,
      Vector(observations),
      Vector(c.inputChecksum),
      seed = 1L
    )
    assertEquals(aggregate.coverage.eligible, 1)
    assertEquals(aggregate.coverage.observed, 0)
    val noGoldPosition =
      c.copy(view = view.copy(nodes = view.nodes.map(_.copy(scoringPosition = None))))
    val external = Metrics.observe(
      noGoldPosition,
      resultFor(noGoldPosition, Vector(None, None), ExternalState.Intrusion)
    )
    assertEquals(
      external.byMetric(Metrics.Names.routeSupportMidpointDirection)(1).observation,
      MetricObservation.Ineligible
    )
    val unranked = Metrics.observe(noGoldPosition, resultFor(noGoldPosition, Vector(None, None)))
    assertEquals(
      unranked.byMetric(Metrics.Names.routeSupportMidpointDirection)(1).observation,
      MetricObservation.Missing(MissingReason.ProviderAbstained)
    )
    val sourceMissing = Metrics.observe(noGoldPosition, resultFor(noGoldPosition, refs))
    assertEquals(
      sourceMissing.byMetric(Metrics.Names.routeSupportMidpointDirection)(1).observation,
      MetricObservation.Missing(MissingReason.AllMissing)
    )
    for observation <- Vector(observations, external, sourceMissing) do
      assertEquals(
        observation.byMetric(Metrics.Names.routeTransitionDisplacementCloseness)(1).observation,
        MetricObservation.Missing(MissingReason.AllMissing)
      )
    assertEquals(
      unranked.byMetric(Metrics.Names.routeTransitionDisplacementCloseness)(1).observation,
      MetricObservation.Missing(MissingReason.ProviderAbstained)
    )
  }

  test("an Unranked route step is eligible missing, never ineligible") {
    val c = WogDiagnostic.fullRecallCase
    val candidates = Candidates(
      c.recall.ordered.iterator.map(u => u.id -> CandidateSet.unranked).toMap
    )
    val result = GraphHsmm
      .infer(
        c.recall,
        c.view,
        candidates,
        DefaultLocalCostModel(semantic = SemanticDistance.abstaining)
      )
      .fold(e => fail(e.message), identity)
    val observations = Metrics.observe(c, result)
    val route = observations.byMetric(Metrics.Names.routeSupportMidpointDirection)
    val missing = MetricObservation.Missing(MissingReason.ProviderAbstained)

    assertEquals(route.head.observation, MetricObservation.Ineligible)
    val eligibleIndexes = c.recall.ordered.indices.drop(1).filter { i =>
      c.gold(c.recall.ordered(i - 1).id).flatMap(_.primary).nonEmpty &&
      c.gold(c.recall.ordered(i).id).flatMap(_.primary).nonEmpty
    }
    assert(eligibleIndexes.nonEmpty, "the fixture has no gold source-to-source transition")
    eligibleIndexes.foreach(i => assertEquals(route(i).observation, missing))

    val aggregate = Metrics.aggregate(
      Metrics.Names.routeSupportMidpointDirection,
      Vector(observations),
      Vector(c.inputChecksum),
      seed = 1L
    )
    assertEquals(aggregate.value, Estimate.missing(MissingReason.ProviderAbstained))
    assertEquals(aggregate.coverage.eligible, eligibleIndexes.size)
    assertEquals(aggregate.coverage.observed, 0)
  }

  test("revisit pair recall and precision score exact recurrence shape, not dwell") {
    assert(leafRefs.size >= 4, s"need four distinct leaves, found ${leafRefs.size}")
    val a = leafRefs(0)
    val b = leafRefs(1)
    val c = leafRefs(2)
    val d = leafRefs(3)
    val route = routeCase("route:revisit", Vector(a, b, a, c, d))
    val exact = Metrics.observe(
      route,
      resultFor(route, Vector(a, b, a, c, d).map(Some(_)))
    )
    assertEquals(
      exact.byMetric(Metrics.Names.routeRevisitPairRecall).map(o => observedValue(o.observation)),
      Vector(1.0)
    )
    assertEquals(
      exact
        .byMetric(Metrics.Names.routeRevisitPairPrecision)
        .map(o => observedValue(o.observation)),
      Vector(1.0)
    )

    // The first recurrence is correct, while the alternating tail invents two more revisits.
    val alternating = Metrics.observe(
      route,
      resultFor(route, Vector(a, b, a, b, a).map(Some(_)))
    )
    val recall = Metrics.aggregate(
      Metrics.Names.routeRevisitPairRecall,
      Vector(alternating),
      Vector(route.inputChecksum),
      seed = 1L
    )
    val precision = Metrics.aggregate(
      Metrics.Names.routeRevisitPairPrecision,
      Vector(alternating),
      Vector(route.inputChecksum),
      seed = 1L
    )
    assertEquals(recall.value, Estimate.observed(1.0))
    assertEquals(precision.value, Estimate.observed(1.0 / 3.0))
    assertEquals(precision.coverage.eligible, 3)

    val noRevisit = routeCase("route:no-revisit", Vector(a, b, c))
    val noRevisitObs = Metrics.observe(
      noRevisit,
      resultFor(noRevisit, Vector(a, b, c).map(Some(_)))
    )
    val empty = Metrics.aggregate(
      Metrics.Names.routeRevisitPairRecall,
      Vector(noRevisitObs),
      Vector(noRevisit.inputChecksum),
      seed = 1L
    )
    assertEquals(empty.value, Estimate.missing(MissingReason.AllMissing))
    assertEquals(empty.coverage.eligible, 0)
    assertEquals(empty.coverage.observed, 0)
  }

  test("Unranked inside a revisit interval is missing, never survivor-renormalized") {
    assert(leafRefs.size >= 2, s"need two distinct leaves, found ${leafRefs.size}")
    val a = leafRefs.head
    val b = leafRefs(1)
    val route = routeCase("route:unranked-revisit", Vector(a, b, a))
    val observations = Metrics.observe(route, resultFor(route, Vector(Some(a), None, Some(a))))
    val missing = MetricObservation.Missing(MissingReason.ProviderAbstained)
    assertEquals(
      observations.byMetric(Metrics.Names.routeRevisitPairRecall).map(_.observation),
      Vector(missing)
    )
    assertEquals(
      observations.byMetric(Metrics.Names.routeRevisitPairPrecision).map(_.observation),
      Vector(missing)
    )
  }

  test("gold-level route metrics report magnitude and signed overcompression") {
    val view = WogDiagnostic.view
    assert(view.maxLevel > 0, "the fixture has no hierarchy to test")
    val leaf = leafRefs.head
    val coarse = view.nodes.maxBy(_.level).ref
    val route = routeCase("route:levels", Vector(leaf))
    val observations = Metrics.observe(route, resultFor(route, Vector(Some(coarse))))
    val inferredLevel = view.node(coarse).fold(fail("coarse node missing"))(_.level)
    val goldLevel = view.node(leaf).fold(fail("leaf node missing"))(_.level)
    assertEquals(inferredLevel, view.maxLevel)
    assertEquals(goldLevel, 0)
    assertEquals(
      observedValue(observations.byMetric(Metrics.Names.routeGoldLevelCloseness).head.observation),
      0.0
    )
    assertEquals(
      observedValue(observations.byMetric(Metrics.Names.routeGoldLevelSignedBias).head.observation),
      1.0
    )

    val reverse = routeCase("route:levels-reverse", Vector(coarse))
    val reverseObservations = Metrics.observe(reverse, resultFor(reverse, Vector(Some(leaf))))
    assertEquals(
      observedValue(
        reverseObservations.byMetric(Metrics.Names.routeGoldLevelCloseness).head.observation
      ),
      0.0
    )
    assertEquals(
      observedValue(
        reverseObservations.byMetric(Metrics.Names.routeGoldLevelSignedBias).head.observation
      ),
      -1.0
    )

    val intermediate = view.nodes
      .find(node => node.level > 0 && node.level < view.maxLevel)
      .fold(fail("the fixture cannot distinguish graded closeness from level exactness"))(_.ref)
    val gradient = routeCase("route:levels-gradient", Vector(leaf))
    val gradientObservations = Metrics.observe(
      gradient,
      resultFor(gradient, Vector(Some(intermediate)))
    )
    val intermediateLevel = view.node(intermediate).fold(fail("intermediate node missing"))(_.level)
    assertEquals(intermediateLevel, 1)
    assertEquals(view.maxLevel, 3)
    // Hand-pinned: one level of error across a three-level hierarchy is 1 - 1/3, not exact-match 0.
    val expectedCloseness = 0.6666666666666667
    assertEquals(
      observedValue(
        gradientObservations.byMetric(Metrics.Names.routeGoldLevelCloseness).head.observation
      ),
      expectedCloseness
    )
  }

  test("transition displacement distinguishes a short inferred step from a long gold step") {
    assert(leafRefs.size >= 3, s"need three distinct leaves, found ${leafRefs.size}")
    val first = leafRefs.head
    val middle = leafRefs(leafRefs.size / 2)
    val last = leafRefs.last
    val route = routeCase("route:displacement", Vector(first, last))
    val observations = Metrics.observe(
      route,
      resultFor(route, Vector(Some(first), Some(middle)))
    )
    // Hand-pinned from the WOG support-midpoint positions. A direction-only mutant returns 1.0.
    val expected = 0.5483944954128441
    val displacement = observations
      .byMetric(Metrics.Names.routeTransitionDisplacementCloseness)
      .map(_.observation)
    assertEquals(displacement.head, MetricObservation.Ineligible)
    assertEquals(observedValue(displacement(1)), expected)
    assert(
      expected > 0.0 && expected < 1.0,
      s"fixture does not distinguish displacement: $expected"
    )
  }

  test("Unranked is typed missing for hierarchy and displacement route metrics") {
    val a = leafRefs.head
    val b = leafRefs.last
    val route = routeCase("route:unranked", Vector(a, b))
    val observations = Metrics.observe(route, resultFor(route, Vector(Some(a), None)))
    val missing = MetricObservation.Missing(MissingReason.ProviderAbstained)
    assertEquals(
      observations.byMetric(Metrics.Names.routeGoldLevelCloseness)(1).observation,
      missing
    )
    assertEquals(
      observations.byMetric(Metrics.Names.routeGoldLevelSignedBias)(1).observation,
      missing
    )
    assertEquals(
      observations.byMetric(Metrics.Names.routeTransitionDisplacementCloseness)(1).observation,
      missing
    )

    val unsupported = Metrics.observe(
      route,
      resultFor(route, Vector(Some(a), None), absentState = ExternalState.Intrusion)
    )
    val allMissing = MetricObservation.Missing(MissingReason.AllMissing)
    assertEquals(
      unsupported.byMetric(Metrics.Names.routeGoldLevelCloseness)(1).observation,
      allMissing
    )
    assertEquals(
      unsupported.byMetric(Metrics.Names.routeGoldLevelSignedBias)(1).observation,
      allMissing
    )
    assertEquals(
      unsupported.byMetric(Metrics.Names.routeTransitionDisplacementCloseness)(1).observation,
      allMissing
    )
  }
