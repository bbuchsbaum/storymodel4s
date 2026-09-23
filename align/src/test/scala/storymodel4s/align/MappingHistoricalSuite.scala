package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.MissingReason
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

class MappingHistoricalSuite extends FunSuite:
  import MappingMeasureFixture.{recall, view, result}
  private val inventory = MappingMeasureFixture.inventory(recall)
  private val source = MappingMeasureFixture.source(view)
  private def adapt(
      r: HsmmResult = result,
      recalled: RecallGraph[Checked] = recall,
      v: SourceView = view,
      representation: SourceRepresentation = source,
      decode: HistoricalDecode = HistoricalDecode.ArgmaxOnly
  ): Either[MappingRefusal, MappingResult] =
    HistoricalMapping.of(
      r,
      recalled,
      v,
      MappingMeasureFixture.inventory(recalled),
      representation,
      decode
    )
  private def decoded(value: Option[SourceNodeRef]): HistoricalDecode =
    HistoricalDecode.Decoded(inventory.units.map(_.id -> value).toMap, "synthetic-decoder/v1")
  private def bits(values: Map[AlignState, Double]): Map[AlignState, Long] =
    values.map((state, value) => state -> java.lang.Double.doubleToLongBits(value))
  private lazy val unranked: HsmmResult =
    val state = AlignState.External(ExternalState.Unranked)
    val rows = recall.ordered.map(u => AlignmentRow.of(u.id, Map(state -> 1.0)).toOption.get)
    val posterior = AlignmentMatrix.of(rows).toOption.get
    val flow = TransitionFlow(
      rows
        .sliding(2)
        .collect { case Vector(a, b) =>
          FlowStep(a.unit, b.unit, Map((state -> state) -> 1.0))
        }
        .toVector
    )
    val costs = result.costs.map((u, row) =>
      u -> row.updated(state, CostBreakdown.external(0.5).toOption.get)
    )
    HsmmResult
      .validated(
        recall,
        view,
        result.candidateAnchors,
        posterior,
        flow,
        rows.map(_ => state),
        0.0,
        costs,
        0
      )
      .toOption
      .get

  test("historical posterior is bit-equal to the supplied result") {
    val record = adapt().toOption.get
    assertEquals(record.outcomes.map(_.unit), inventory.units.map(_.id))
    record.outcomes.foreach { outcome =>
      assertEquals(outcome.processing, ProcessingStatus.Complete)
      assertEquals(
        bits(outcome.measures.posterior.get.mass),
        bits(result.posterior.row(outcome.unit).get.mass)
      )
      val raw = outcome.measures.raw.head
      assertEquals(raw.channel, "hsmm.local-cost")
      assertEquals(raw.direction, ScoreDirection.LowerIsBetter)
      assertEquals(
        raw.values,
        result.costs(outcome.unit).map((s, c) => Destination.of(s) -> c.total)
      )
      assertEquals(outcome.decision.get.basis.kind, MeasureKind.ModelPosterior)
    }
  }
  test("Unranked row argmax is external") {
    val record = adapt(unranked).toOption.get
    record.outcomes.foreach { row =>
      assertEquals(row.decision.get.chosen, Some(Destination.External(ExternalState.Unranked)))
      assertEquals(
        row.decision.get.rawArgmax,
        Some(Destination.External(ExternalState.Unranked) -> 1.0)
      )
      assertEquals(row.localization, LocalizationStatus.Unranked)
      assert(row.measures.raw.head.values.keys.exists(_.isInstanceOf[Destination.Target]))
    }
  }
  test("external links remain NotApplicable") {
    val external = adapt().toOption.get.outcomes
      .flatMap(_.links)
      .filter(_.destination.isInstanceOf[Destination.External])
    assert(external.nonEmpty)
    external.foreach { link =>
      assertEquals(link.fidelity, FidelityStatus.NotApplicable)
      assertEquals(link.gate, GateOutcome.NotGated)
      assertEquals(
        link.termSupport.asInstanceOf[TermSupportStatus.Evaluated].assessment,
        SupportAssessment.externalState
      )
    }
  }
  test("missing breakdown refuses") {
    val unit = recall.ordered.head.id
    val state = result.posterior.row(unit).get.mass.keys.toVector.sorted.head
    val missing =
      MappingMeasureFixture.revalidate(result.costs.updated(unit, result.costs(unit) - state))
    assertEquals(adapt(missing).left.toOption, Some(MappingRefusal.MissingCost(unit, state)))
    assert(adapt().isRight)
  }
  test("an absent cost row refuses instead of making empty evidence") {
    val unit = recall.ordered.head.id
    val state = result.posterior.row(unit).get.mass.keys.toVector.sorted.head
    val missing = MappingMeasureFixture.revalidate(result.costs - unit)
    assertEquals(adapt(missing).left.toOption, Some(MappingRefusal.MissingCost(unit, state)))
  }
  test("foreign recall with shared unit IDs refuses") {
    val changed = RecallGraph
      .validated(
        recall.copy(units =
          recall.units.map(u =>
            u.copy(proposition = u.proposition.copy(predicate = Some("foreign")))
          )
        )
      )
      .toOption
      .get
    assertEquals(changed.ordered.map(_.id), recall.ordered.map(_.id))
    assertEquals(
      adapt(recalled = changed).left.toOption,
      Some(MappingRefusal.BindingMismatch("recallChecksum"))
    )
  }
  test("source from another view refuses") {
    val other =
      view.copy(nodes = view.nodes.map(n => n.copy(discoursePosition = n.discoursePosition + 1)))
    assertEquals(
      adapt(representation = MappingMeasureFixture.source(other)).left.toOption,
      Some(MappingRefusal.BindingMismatch("viewFingerprint"))
    )
  }
  test("Declared and undeclared twins require their own scope and preserve missingness") {
    // Scope is a per-target contract; one recall unit keeps every target without unrelated rows.
    val singleRecall = RecallGraph
      .validated(
        recall.copy(units = Vector(recall.ordered.head), relations = RecallRelations.empty)
      )
      .toOption
      .get
    val singleResult = GraphHsmm
      .infer(singleRecall, view, AnnaFixture.candidates, AnnaFixture.costModel)
      .toOption
      .get
    val twin = view.copy(nodes =
      view.nodes.map(
        _.copy(propositional = PropositionalScope.Undeclared(MissingReason.ProviderAbstained))
      )
    )
    assertEquals(
      adapt(singleResult, singleRecall, v = twin).left.toOption,
      Some(MappingRefusal.BindingMismatch("scopeDigest"))
    )
    val record = adapt(
      singleResult,
      singleRecall,
      v = twin,
      representation = MappingMeasureFixture.source(twin)
    ).toOption.get
    assertEquals(record.outcomes.map(_.unit), Vector(singleRecall.ordered.head.id))
    val links =
      record.outcomes.flatMap(_.links).filter(_.destination.isInstanceOf[Destination.Target])
    assert(links.nonEmpty)
    assert(links.forall(_.fidelity.isInstanceOf[FidelityStatus.NotAssessed]))
    assert(
      adapt(singleResult, singleRecall).toOption.get.outcomes
        .flatMap(_.links)
        .exists(_.fidelity.isInstanceOf[FidelityStatus.Assessed])
    )
  }
  test("Decoded None is Abstention") {
    adapt(decode = decoded(None)).toOption.get.outcomes.foreach { row =>
      val decision = row.decision.get
      assertEquals(decision.chosen, None)
      assert(decision.origin.isInstanceOf[DecisionOrigin.Abstention])
      assert(decision.decodedMass.isInstanceOf[DecodedTargetMass.NoDecision])
      assert(decision.rawArgmax.nonEmpty)
      assertEquals(row.localization, LocalizationStatus.NotComputed)
    }
  }
  test("decoded choices outside the posterior remain GapFill even when locally scored") {
    val record = adapt(unranked, decode = decoded(Some(AnnaFixture.e1))).toOption.get
    record.outcomes.foreach { row =>
      assertEquals(row.decision.get.chosen, Some(Destination.Target(AnnaFixture.e1)))
      assert(row.decision.get.origin.isInstanceOf[DecisionOrigin.GapFill])
      assert(row.decision.get.decodedMass.isInstanceOf[DecodedTargetMass.OutsideCandidateSupport])
    }
  }
  test("a supplied in-support decode remains StructuredDecode") {
    val singleRecall = RecallGraph
      .validated(
        recall.copy(units = Vector(recall.ordered.head), relations = RecallRelations.empty)
      )
      .toOption
      .get
    val singleResult = GraphHsmm
      .infer(singleRecall, view, AnnaFixture.candidates, AnnaFixture.costModel)
      .toOption
      .get
    val choices = singleResult.posterior.rows
      .map(row => row.unit -> row.mass.keys.toVector.flatMap(_.anchor).sorted.headOption)
      .toMap
    val record = adapt(
      singleResult,
      singleRecall,
      decode = HistoricalDecode.Decoded(choices, "synthetic-decoder/v1")
    ).toOption.get
    assertEquals(record.outcomes.map(_.unit), Vector(singleRecall.ordered.head.id))
    assert(choices(singleRecall.ordered.head.id).nonEmpty)
    record.outcomes.foreach { row =>
      assert(row.decision.get.origin.isInstanceOf[DecisionOrigin.StructuredDecode])
      val chosen = row.decision.get.chosen.get
      val expected = row.measures.posterior.get.mass.collectFirst {
        case (s, m) if Destination.of(s) == chosen => m
      }.get
      assertEquals(
        row.decision.get.decodedMass.asInstanceOf[DecodedTargetMass.InCandidateSupport].value,
        expected
      )
    }
  }
  test("decode must account for exactly the requested units") {
    val values = inventory.units.map(_.id -> Option.empty[SourceNodeRef]).toMap
    Vector(values - inventory.units.head.id, values.updated(RecallUnitId.unsafe("foreign"), None))
      .foreach { changed =>
        assertEquals(
          adapt(decode = HistoricalDecode.Decoded(changed, "decoder")).left.toOption,
          Some(MappingRefusal.OutcomeInventoryMismatch)
        )
      }
  }
  test("historical ledger asserts no receipts") {
    val record = adapt().toOption.get
    assertEquals(record.ledger.entries.map(_.stage), Stage.values.toVector)
    record.ledger.entries.foreach { entry =>
      val provenance = entry.provenance.asInstanceOf[StageProvenance.Unknown]
      assertEquals(provenance.reason, UnknownProvenanceReason.HistoricalArtifact)
      assertEquals(provenance.asserted, None)
    }
    assert(record.policies.inference.isInstanceOf[InferencePolicy.HistoricalReconstruction])
    assert(record.policies.candidate.isInstanceOf[CandidatePolicy.Unknown])
    assert(record.policies.referencePrior.isInstanceOf[ReferencePrior.NotApplicable])
    assert(record.policies.decision.isInstanceOf[DecisionPolicy.NotApplicable])
    assert(
      record.outcomes.forall(
        _.decision.get.calibration.isInstanceOf[DecisionCalibration.Unavailable]
      )
    )
  }
  test("adapter captures a changing view once for binding and all link assessment") {
    // One recall unit still assesses multiple targets, without repeatedly hashing unrelated rows.
    val singleRecall = RecallGraph
      .validated(
        recall.copy(units = Vector(recall.ordered.head), relations = RecallRelations.empty)
      )
      .toOption
      .get
    val singleResult = GraphHsmm
      .infer(singleRecall, view, AnnaFixture.candidates, AnnaFixture.costModel)
      .toOption
      .get
    val expected = adapt(singleResult, singleRecall).toOption.get
    val assessedTargets = expected.outcomes.flatMap(_.links).count { link =>
      link.destination.isInstanceOf[Destination.Target] &&
      link.fidelity.isInstanceOf[FidelityStatus.Assessed]
    }
    assert(assessedTargets > 1)
    var reads = 0
    val changing = new SourceView:
      def nodes: Vector[NodeSummary] =
        reads += 1
        if reads == 1 then view.nodes else view.nodes.map(_.copy(predicate = Some("changed")))
      def node(ref: SourceNodeRef): Option[NodeSummary] = view.node(ref)
      def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
        view.adjacency(layer)
      def worldOrder: Option[Map[SourceNodeRef, Int]] = view.worldOrder
      def scoringLength: Int = view.scoringLength
    val observed = adapt(singleResult, singleRecall, v = changing).toOption.get
    assertEquals(reads, 1)
    assertEquals(observed.digest, expected.digest)
  }
