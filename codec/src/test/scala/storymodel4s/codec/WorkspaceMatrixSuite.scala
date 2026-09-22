package storymodel4s.codec

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.laws.MappingMiniature
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.MappingMatrix

/** Projection courts consume independently authored mapping controls and real local posteriors. */
class WorkspaceMatrixSuite extends FunSuite:
  private val record = MappingMiniature.record
  private val matrix = MappingMatrix.from(record)
  private def unit(id: String) = RecallUnitId.unsafe(id)
  private def target(id: String) =
    Destination.Target(SourceNodeRef.Situation(SituationId.unsafe(id)))
  private def cell(packet: String, event: String) =
    matrix.row(unit(packet)).get.cell(target(event)).get

  test("fixed cut includes every declared target and ordinal row, including untimed and failed") {
    assertEquals(matrix.rows.map(_.unit.id.value), (1 to 8).map(i => s"p$i").toVector)
    assertEquals(
      matrix.targets.map(_.ref).toSet,
      ((1 to 8).map(i => SourceNodeRef.Situation(SituationId.unsafe(s"e$i"))) :+
        SourceNodeRef.Segment(SegmentId.unsafe("g1"))).toSet
    )
    assertEquals(matrix.record.policies.universe.id, record.policies.universe.id)
    assertEquals(matrix.row(unit("p3")).get.unit.ordinal, 2)
    assert(matrix.row(unit("p6")).get.outcome.processing.isInstanceOf[ProcessingStatus.Failed])
    assertEquals(matrix.row(unit("p6")).get.outcome.localization, LocalizationStatus.NotComputed)
    assert(
      matrix
        .row(unit("p6"))
        .get
        .cells
        .forall(c =>
          c.raw.isEmpty && c.normalized.isEmpty && c.transport.isEmpty && c.posterior.isEmpty
        )
    )
    assertEquals(matrix.row(unit("foreign")), None)
  }
  test("all authored alternatives survive with original scale and candidate coverage") {
    assertEquals(cell("p7", "e3").raw.map(_._2), Vector(0.5))
    assertEquals(cell("p7", "e8").raw.map(_._2), Vector(0.5))
    assertEquals(cell("p7", "e3").raw.head._1.scale, "arbitrary synthetic control")
    assertEquals(cell("p7", "e3").raw.head._1.direction, ScoreDirection.HigherIsBetter)
    assertEquals(
      matrix.record.policies.candidate,
      CandidatePolicy.Declared(
        CandidatePolicyId.unsafe("synthetic-authored-alternatives/v1"),
        CandidateCoverage.Unknown("Nomination recall is unmeasured")
      )
    )
    assertEquals(cell("p7", "e2").raw, Vector.empty)
    assertEquals(cell("p7", "e3").normalized, None)
  }
  test("external values stay visible and processing failure is not an external destination") {
    assertEquals(matrix.externals, Vector(ExternalState.Intrusion))
    val external =
      matrix.row(unit("p5")).get.cell(Destination.External(ExternalState.Intrusion)).get
    assertEquals(external.raw.map(_._2), Vector(1.0))
    assert(external.chosen)
    assertEquals(matrix.row(unit("p5")).get.outcome.localization, LocalizationStatus.Nonlocalizable)
    assertEquals(cell("p5", "e1").raw, Vector.empty)
  }
  test("decode disagreement and repeated references retain their actual rows") {
    assertEquals(cell("p3", "e3").raw.map(_._2), Vector(0.9))
    assertEquals(cell("p3", "e7").raw.map(_._2), Vector(0.4))
    assert(!cell("p3", "e3").chosen)
    assert(cell("p3", "e7").chosen)
    assertEquals(
      matrix.row(unit("p3")).get.outcome.decision.get.origin,
      DecisionOrigin.StructuredDecode(DecisionPolicyId.unsafe("synthetic-authored-choice/v1"))
    )
    assertEquals(
      Vector("p1", "p8").map(p => cell(p, "e2").raw.map(_._2)),
      Vector.fill(2)(Vector(1.0))
    )
  }
  test("a hierarchy column does not acquire child values") {
    val group = Destination.Target(SourceNodeRef.Segment(SegmentId.unsafe("g1")))
    assertEquals(matrix.row(unit("p1")).get.cell(group).get.raw, Vector.empty)
    assertEquals(cell("p1", "e2").raw.map(_._2), Vector(1.0))
  }
  test(
    "normalized and transport quantities keep missing, zero, budget and external mass distinct"
  ) {
    val first = record.outcomes.head
    val ext = Destination.External(ExternalState.Association)
    val normalized = NormalizedScoreMass
      .of(
        record.policies.universe.id,
        ReferencePriorId.unsafe("synthetic-prior"),
        2.0,
        Map(target("e1") -> 0.0, target("e2") -> 0.25, ext -> 0.75),
        record.ledger.at(Stage.Scoring).head.id
      )
      .toOption
      .get
    val transport = TransportMass
      .of(
        4.0,
        Map(target("e2") -> 0.5, ext -> 1.5),
        record.ledger.at(Stage.Scoring).head.id
      )
      .toOption
      .get
    val measures =
      UnitMeasures.of(Vector.empty, Some(normalized), Some(transport), None).toOption.get
    val stages = first.stages.get
    val candidates = CandidateSetId.of(stages.candidates, first.unit, normalized.mass.keySet)
    val links =
      measures.destinations.toVector.sorted.map(MappingLink.ungated(_, stages, candidates))
    val outcome = UnitOutcome
      .computed(
        first.unit,
        measures,
        links,
        DecisionBasis.of(MeasureKind.NormalizedScoreMass, None).toOption.get,
        DecisionRequest.RawArgmax,
        stages
      )
      .toOption
      .get
    val policies = MappingPolicies
      .of(
        record.policies.inference,
        record.policies.context,
        record.policies.candidate,
        ReferencePrior.Declared(normalized.prior),
        record.policies.decision,
        record.policies.universe
      )
      .toOption
      .get
    val changed = MappingResult
      .checked(
        record.inventory,
        record.source,
        policies,
        record.roles,
        record.ledger,
        record.outcomes.updated(0, outcome)
      )
      .toOption
      .get
    val row = MappingMatrix.from(changed).rows.head
    assertEquals(row.cell(target("e1")).get.normalized, Some(0.0))
    assertEquals(row.cell(target("e3")).get.normalized, None)
    assertEquals(row.cell(target("e2")).get.normalized, Some(0.25))
    assertEquals(row.cell(ext).get.normalized, Some(0.75))
    assertEquals(row.cell(target("e2")).get.transport, Some(0.5))
    assertEquals(row.cell(ext).get.transport, Some(1.5))
    assertEquals(row.outcome.measures.transport.get.rowBudget, 4.0)
    assertEquals(row.outcome.measures.normalized.get.temperature, 2.0)
    assertEquals(row.outcome.measures.normalized.get.prior, normalized.prior)
    // Cropping is only a choice of cells to display; it never changes their values.
    assertEquals(
      row.cells.filter(_.destination == target("e2")).flatMap(_.normalized),
      Vector(0.25)
    )
  }
  test("historical posterior cells preserve every state and exact supplied double") {
    val result = MappingCodecFixture.result
    val projected = MappingMatrix.from(MappingCodecFixture.record())
    projected.rows.foreach { row =>
      val observed = row.cells.flatMap(_.posterior)
      assertEquals(observed.map(_._1).distinct.size, observed.size)
      assertEquals(
        observed.map((state, value) => state -> java.lang.Double.doubleToLongBits(value)).toMap,
        result.posterior
          .row(row.unit.id)
          .get
          .mass
          .map((state, value) => state -> java.lang.Double.doubleToLongBits(value))
      )
    }
  }
