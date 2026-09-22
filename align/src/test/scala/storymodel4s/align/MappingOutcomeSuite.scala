package storymodel4s.align

import munit.FunSuite
import scala.collection.immutable.VectorMap
import storymodel4s.core.*
import storymodel4s.recall.*

class MappingOutcomeSuite extends FunSuite:
  import MappingMeasureFixture.{stages, stage}
  private val unit = RecallUnitId.unsafe("p3")
  private val policy = DecisionPolicyId.unsafe("synthetic-decoder")
  private val e3 = Destination.Target(SourceNodeRef.Situation(SituationId.unsafe("e3")))
  private val e7 = Destination.Target(SourceNodeRef.Situation(SituationId.unsafe("e7")))
  private val e8 = Destination.Target(SourceNodeRef.Situation(SituationId.unsafe("e8")))
  private val external = Destination.External(ExternalState.Intrusion)
  private val basis = DecisionBasis.of(MeasureKind.RawScore, Some("synthetic")).toOption.get
  private def raw(
      values: Map[Destination, Double],
      channel: String = "synthetic",
      direction: ScoreDirection = ScoreDirection.HigherIsBetter
  ): RawScores =
    RawScores.of(channel, direction, "arbitrary", values, stage(Stage.Scoring)).toOption.get
  private def measures(
      values: Map[Destination, Double],
      extra: Vector[RawScores] = Vector.empty,
      direction: ScoreDirection = ScoreDirection.HigherIsBetter
  ): UnitMeasures =
    UnitMeasures.of(raw(values, direction = direction) +: extra, None, None, None).toOption.get
  private def links(
      id: RecallUnitId,
      destinations: Set[Destination],
      keys: Set[Destination]
  ): Vector[MappingLink] =
    val candidates = CandidateSetId.of(stages.candidates, id, keys)
    destinations.toVector.sorted.map(MappingLink.ungated(_, stages, candidates))
  private def outcome(
      values: Map[Destination, Double],
      request: DecisionRequest = DecisionRequest.RawArgmax,
      extra: Vector[RawScores] = Vector.empty,
      direction: ScoreDirection = ScoreDirection.HigherIsBetter
  ): UnitOutcome =
    val m = measures(values, extra, direction)
    UnitOutcome
      .computed(unit, m, links(unit, m.destinations, values.keySet), basis, request, stages)
      .toOption
      .get

  test("p3 keeps e7's value") {
    val row = outcome(Map(e3 -> 0.9, e7 -> 0.4), DecisionRequest.ExternalDecode(e7, policy))
    val decision = row.decision.get
    assertEquals(decision.rawArgmax, Some(e3 -> 0.9))
    assertEquals(decision.chosen, Some(e7))
    assertEquals(decision.decodedMass, DecodedTargetMass.InCandidateSupport(0.4))
    assertEquals(decision.origin, DecisionOrigin.StructuredDecode(policy))
    assertEquals(row.localization, LocalizationStatus.Located)
    assertEquals(outcome(Map(e3 -> 0.9, e7 -> 0.4)).decision.get.chosen, Some(e3))
  }
  test("fill is OutsideCandidateSupport") {
    val decision =
      outcome(Map(e3 -> 0.9, e7 -> 0.4), DecisionRequest.ExternalDecode(e8, policy)).decision.get
    assertEquals(decision.origin, DecisionOrigin.GapFill(policy))
    assertEquals(decision.chosen, Some(e8))
    assertEquals(decision.decodedMass, DecodedTargetMass.OutsideCandidateSupport)
  }
  test("in-support zero stays") {
    val decision =
      outcome(Map(e3 -> 1.0, e7 -> 0.0), DecisionRequest.ExternalDecode(e7, policy)).decision.get
    assertEquals(decision.origin, DecisionOrigin.StructuredDecode(policy))
    assertEquals(decision.decodedMass, DecodedTargetMass.InCandidateSupport(0.0))
  }
  test("listed fill target is still GapFill") {
    val row = outcome(
      Map(e3 -> 0.9, e7 -> 0.4),
      DecisionRequest.ExternalDecode(e8, policy),
      Vector(raw(Map(e8 -> 8.0), "other"))
    )
    assertEquals(row.links.map(_.destination).toSet, Set(e3, e7, e8))
    assertEquals(row.decision.get.origin, DecisionOrigin.GapFill(policy))
    assertEquals(row.decision.get.decodedMass, DecodedTargetMass.OutsideCandidateSupport)
  }
  test("agreeing decode stays StructuredDecode") {
    val decision =
      outcome(Map(e3 -> 0.9, e7 -> 0.4), DecisionRequest.ExternalDecode(e3, policy)).decision.get
    assertEquals(decision.origin, DecisionOrigin.StructuredDecode(policy))
    assertEquals(decision.policy, Some(policy))
  }
  test("unranked stays") {
    val value = Destination.External(ExternalState.Unranked)
    val row = outcome(Map(value -> 1.0))
    assertEquals(row.localization, LocalizationStatus.Unranked)
    assertEquals(row.decision.get.chosen, Some(value))
  }
  test("p7 tie by key, both kept") {
    Vector(VectorMap(e8 -> 0.5, e3 -> 0.5), VectorMap(e3 -> 0.5, e8 -> 0.5)).foreach { values =>
      val row = outcome(values)
      assertEquals(row.decision.get.rawArgmax, Some(e3 -> 0.5))
      assertEquals(row.links.map(_.destination), Vector(e3, e8))
    }
    assertEquals(outcome(VectorMap(e8 -> 0.0, e3 -> -0.0)).decision.get.chosen, Some(e3))
  }
  test("empty basis is Abstention, NoDecision, Unranked") {
    Vector(DecisionRequest.RawArgmax, DecisionRequest.ExternalDecode(e7, policy)).foreach {
      request =>
        val row = outcome(Map.empty, request, Vector(raw(Map(e8 -> 8.0), "other")))
        assertEquals(row.decision.get.chosen, None)
        assertEquals(row.decision.get.rawArgmax, None)
        assertEquals(row.decision.get.origin, DecisionOrigin.Abstention("EmptyDecisionBasis"))
        assertEquals(row.decision.get.decodedMass, DecodedTargetMass.NoDecision)
        assertEquals(row.localization, LocalizationStatus.Unranked)
        assertEquals(row.links.map(_.destination), Vector(e8))
    }
  }
  test("Abstain yields NoDecision, NotComputed") {
    val row = outcome(Map(e3 -> 0.9), DecisionRequest.Abstain(policy, "decoder declined"))
    assertEquals(row.processing, ProcessingStatus.Complete)
    assertEquals(row.localization, LocalizationStatus.NotComputed)
    assertEquals(row.decision.get.chosen, None)
    assertEquals(row.decision.get.rawArgmax, Some(e3 -> 0.9))
    assertEquals(row.decision.get.decodedMass, DecodedTargetMass.NoDecision)
    assertEquals(row.decision.get.origin, DecisionOrigin.Abstention("decoder declined"))
    assertEquals(row.decision.get.policy, Some(policy))
    assertEquals(row.stages, Some(stages))
  }
  test("external choice Nonlocalizable and full-space argmax") {
    Vector(Map(external -> 1.0), Map(e3 -> 0.1, external -> 0.9)).foreach { values =>
      val row = outcome(values)
      assertEquals(row.localization, LocalizationStatus.Nonlocalizable)
      assertEquals(row.decision.get.chosen, Some(external))
      assertEquals(row.decision.get.rawArgmax, Some(external -> values(external)))
    }
  }
  test("raw direction is respected including negative scores") {
    val row = outcome(Map(e3 -> -0.9, external -> -1.2), direction = ScoreDirection.LowerIsBetter)
    assertEquals(row.decision.get.rawArgmax, Some(external -> -1.2))
    assertEquals(row.decision.get.decodedMass, DecodedTargetMass.InCandidateSupport(-1.2))
  }
  test("failed and excluded units carry no invented decision or measures") {
    val failure = ProcessingFailure.ProviderFailure("synthetic refusal")
    val failed = UnitOutcome.failed(RecallUnitId.unsafe("p6"), failure)
    assertEquals(failed.processing, ProcessingStatus.Failed(failure))
    val excluded = UnitOutcome.excluded(unit, "input policy")
    assertEquals(excluded.processing, ProcessingStatus.ExcludedByInputPolicy("input policy"))
    Vector(failed, excluded).foreach { row =>
      assertEquals(row.localization, LocalizationStatus.NotComputed)
      assertEquals(row.measures.destinations, Set.empty[Destination])
      assertEquals(row.links, Vector.empty[MappingLink])
      assertEquals(row.decision, None)
      assertEquals(row.stages, None)
    }
  }
  test("links equal measured alternatives") {
    val values = Map(e3 -> 0.9, e7 -> 0.4)
    val m = measures(values)
    val valid = links(unit, values.keySet, values.keySet)
    Vector(
      valid.take(1),
      valid :+ valid.head,
      valid :+ MappingLink.ungated(e8, stages, valid.head.candidateSet)
    ).foreach { invalid =>
      assertEquals(
        UnitOutcome
          .computed(unit, m, invalid, basis, DecisionRequest.RawArgmax, stages)
          .left
          .toOption,
        Some(MappingRefusal.LinkDestinationsMismatch)
      )
    }
    assert(UnitOutcome.computed(unit, m, valid, basis, DecisionRequest.RawArgmax, stages).isRight)
  }
  test("forged candidate-set id refuses foreign unit and foreign basis") {
    val values = Map(e3 -> 0.9, e7 -> 0.4)
    val m = measures(values)
    Vector(
      links(RecallUnitId.unsafe("other"), values.keySet, values.keySet),
      links(unit, values.keySet, Set(e3))
    ).foreach { invalid =>
      assertEquals(
        UnitOutcome
          .computed(unit, m, invalid, basis, DecisionRequest.RawArgmax, stages)
          .left
          .toOption,
        Some(MappingRefusal.CandidateSetMismatch(unit))
      )
    }
  }
  test("basis must name a present measure") {
    assertEquals(
      UnitOutcome
        .computed(unit, UnitMeasures.empty, Vector.empty, basis, DecisionRequest.RawArgmax, stages)
        .left
        .toOption,
      Some(MappingRefusal.MeasureMissing(MeasureKind.RawScore, Some("synthetic")))
    )
    Vector(MeasureKind.NormalizedScoreMass, MeasureKind.TransportMass, MeasureKind.ModelPosterior)
      .foreach { kind =>
        val b = DecisionBasis.of(kind, None).toOption.get
        assertEquals(
          UnitOutcome
            .computed(unit, UnitMeasures.empty, Vector.empty, b, DecisionRequest.RawArgmax, stages)
            .left
            .toOption,
          Some(MappingRefusal.MeasureMissing(kind, None))
        )
      }
  }
  test("basis channel and reserved calibration are checked") {
    Vector(
      MeasureKind.RawScore -> None,
      MeasureKind.RawScore -> Some(" "),
      MeasureKind.TransportMass -> Some("raw")
    ).foreach { (kind, channel) =>
      DecisionBasis.of(kind, channel) match
        case Left(MappingRefusal.InvalidValue("decisionBasis", _)) => ()
        case other => fail(s"expected invalid basis, got $other")
    }
    assertEquals(
      DecisionBasis.of(MeasureKind.CalibratedProbability, None).left.toOption,
      Some(MappingRefusal.Reserved("calibrated"))
    )
    assertEquals(
      outcome(Map(e3 -> 0.9)).decision.get.calibration,
      DecisionCalibration.Unavailable(DecisionCalibrationUnavailableReason.NoCalibrationArtifact)
    )
  }
  test("empty abstention reason refuses") {
    val values = Map(e3 -> 0.9)
    UnitOutcome.computed(
      unit,
      measures(values),
      links(unit, values.keySet, values.keySet),
      basis,
      DecisionRequest.Abstain(policy, " "),
      stages
    ) match
      case Left(MappingRefusal.InvalidValue("decision.abstention", _)) => ()
      case other => fail(s"expected invalid abstention, got $other")
  }
  test("normalized and transport bases preserve supplied values") {
    val values = Map(e3 -> 0.4, external -> 0.6)
    val universe =
      DeclaredUniverse.of(Vector(AnnaFixture.e1), TargetGrain.SingleLevel(0)).toOption.get
    val normalized = NormalizedScoreMass
      .of(universe.id, ReferencePriorId.unsafe("prior"), 1.0, values, stage(Stage.Scoring))
      .toOption
      .get
    val transport = TransportMass
      .of(5.0, values.view.mapValues(_ * 2.0).toMap, stage(Stage.Inference))
      .toOption
      .get
    Vector(
      (
        MeasureKind.NormalizedScoreMass,
        UnitMeasures.of(Vector.empty, Some(normalized), None, None).toOption.get,
        0.6
      ),
      (
        MeasureKind.TransportMass,
        UnitMeasures.of(Vector.empty, None, Some(transport), None).toOption.get,
        1.2
      )
    ).foreach { (kind, m, value) =>
      val b = DecisionBasis.of(kind, None).toOption.get
      val row = UnitOutcome
        .computed(
          unit,
          m,
          links(unit, values.keySet, values.keySet),
          b,
          DecisionRequest.RawArgmax,
          stages
        )
        .toOption
        .get
      assertEquals(row.decision.get.decodedMass, DecodedTargetMass.InCandidateSupport(value))
    }
  }

  private lazy val derivedResult = GraphHsmm
    .infer(
      MappingMeasureFixture.recall,
      MappingMeasureFixture.view,
      Candidates.of(
        MappingMeasureFixture.recall.ordered
          .map(u => u.id -> MappingMeasureFixture.view.nodes.map(_.ref))
          .toMap
      ),
      AnnaFixture.costModel
    )
    .toOption
    .get
  private lazy val binding = MappingMeasureFixture.bind(r = derivedResult)
  private val first = MappingMeasureFixture.recall.ordered(0).id
  private val second = MappingMeasureFixture.recall.ordered(1).id
  private def derivedRaw(id: RecallUnitId): RawScores =
    RawScores.fromCosts(derivedResult, id, binding, stage(Stage.Scoring)).toOption.get
  private def posterior(id: RecallUnitId): ModelPosterior =
    ModelPosterior.of(derivedResult, id, binding, stages.inference).toOption.get
  private def computedDerived(
      id: RecallUnitId,
      m: UnitMeasures,
      b: DecisionBasis,
      keys: Set[Destination],
      ls: Option[Vector[MappingLink]] = None
  ) =
    UnitOutcome.computed(
      id,
      m,
      ls.getOrElse(links(id, m.destinations, keys)),
      b,
      DecisionRequest.RawArgmax,
      stages
    )
  test("unequal derived raw rows with identical candidate keys cannot move between units") {
    val a = derivedRaw(first)
    val b = derivedRaw(second)
    assertEquals(a.values.keySet, b.values.keySet)
    assertNotEquals(a.values, b.values)
    val measure = UnitMeasures.of(Vector(a), None, None, None).toOption.get
    val basis = DecisionBasis.of(MeasureKind.RawScore, Some(a.channel)).toOption.get
    assert(computedDerived(first, measure, basis, a.values.keySet).isRight)
    assertEquals(
      computedDerived(second, measure, basis, a.values.keySet).left.toOption,
      Some(MappingRefusal.BindingMismatch("outcome.unit"))
    )
  }
  test("unequal derived posterior rows with identical candidate keys cannot move between units") {
    val a = posterior(first)
    val b = posterior(second)
    assertEquals(a.mass.keySet.map(Destination.of), b.mass.keySet.map(Destination.of))
    assertNotEquals(a.mass, b.mass)
    val measure = UnitMeasures.of(Vector.empty, None, None, Some(a)).toOption.get
    val basis = DecisionBasis.of(MeasureKind.ModelPosterior, None).toOption.get
    assert(computedDerived(first, measure, basis, measure.destinations).isRight)
    assertEquals(
      computedDerived(second, measure, basis, measure.destinations).left.toOption,
      Some(MappingRefusal.BindingMismatch("outcome.unit"))
    )
  }
  test("derived links cannot move between units even with the receiving candidate id") {
    val own = derivedRaw(second)
    val measure = UnitMeasures.of(Vector(own), None, None, None).toOption.get
    val basis = DecisionBasis.of(MeasureKind.RawScore, Some(own.channel)).toOption.get
    val candidateId = CandidateSetId.of(stages.candidates, second, own.values.keySet)
    def linksFrom(id: RecallUnitId) = derivedResult.costs(id).keys.toVector.map { state =>
      MappingLink
        .fromResult(
          derivedResult,
          binding,
          MappingMeasureFixture.recall,
          MappingMeasureFixture.view,
          MappingMeasureFixture.source(MappingMeasureFixture.view),
          id,
          state,
          stages,
          candidateId
        )
        .toOption
        .get
    }
    assert(
      computedDerived(second, measure, basis, own.values.keySet, Some(linksFrom(second))).isRight
    )
    assertEquals(
      computedDerived(
        second,
        measure,
        basis,
        own.values.keySet,
        Some(linksFrom(first))
      ).left.toOption,
      Some(MappingRefusal.BindingMismatch("outcome.unit"))
    )
  }
  test("links retain the declared inference stage") {
    val otherEntry = StageEntry
      .of(Stage.Inference, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
      .toOption
      .get
    val ledger = StageLedger
      .of(
        (MappingMeasureFixture.ledger.entries :+ otherEntry).sortBy(e =>
          (e.stage.ordinal, e.id.digest.hex)
        )
      )
      .toOption
      .get
    val other =
      UnitStageRefs.of(ledger, otherEntry.id, stages.candidates, stages.decision).toOption.get
    val values = Map(e3 -> 0.9)
    assertEquals(
      UnitOutcome
        .computed(
          unit,
          measures(values),
          links(unit, values.keySet, values.keySet),
          basis,
          DecisionRequest.RawArgmax,
          other
        )
        .left
        .toOption,
      Some(MappingRefusal.BindingMismatch("outcome.inferenceStage"))
    )
  }
