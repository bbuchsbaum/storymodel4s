package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId

class MappingVocabularySuite extends FunSuite:
  private val unknown = StageProvenance.unknown(UnknownProvenanceReason.HistoricalArtifact, None)
  private def entry(stage: Stage): StageEntry = StageEntry.of(stage, unknown).toOption.get
  private def entries: Vector[StageEntry] = Stage.values.toVector.map(entry)
  private def ledger: StageLedger = StageLedger.of(entries).toOption.get
  private val e1 = SourceNodeRef.Situation(SituationId.unsafe("e1"))
  private val e2 = SourceNodeRef.Situation(SituationId.unsafe("e2"))
  private val checksum = Checksum.ofText("configuration")

  test("historical ledger accounts for eight unknown stages") {
    assertEquals(ledger.entries.map(_.stage), Stage.values.toVector)
    ledger.entries.foreach { e =>
      val p = e.provenance.asInstanceOf[StageProvenance.Unknown]
      assertEquals(p.reason, UnknownProvenanceReason.HistoricalArtifact)
      assertEquals(p.asserted, None)
      assertEquals(StageEntryId.of(e.stage, p).toOption.get, e.id)
    }
  }
  test("duplicate stage entries refuse") {
    val es = entries
    assertEquals(
      StageLedger
        .of((es :+ es.head).sortBy(e => (e.stage.ordinal, e.id.digest.hex)))
        .swap
        .toOption
        .get,
      MappingRefusal.DuplicateStageEntry(es.head.id)
    )
    assert(StageLedger.of(es).isRight)
  }
  test("ledger total over stages") {
    assertEquals(
      StageLedger.of(entries.drop(1)).swap.toOption.get,
      MappingRefusal.MissingStages(Vector(Stage.Candidates))
    )
    assert(StageLedger.of(entries).isRight)
  }
  test("ledger order canonical") {
    assertEquals(
      StageLedger.of(entries.reverse).swap.toOption.get,
      MappingRefusal.NonCanonicalLedger
    )
    assert(StageLedger.of(entries).isRight)
  }
  test("asserted receipts remain unknown and several entries per stage are retained") {
    val provider = ProviderIdentity
      .of("synthetic-provider", "declared-model", Checksum.ofText("model"))
      .toOption
      .get
    val receipt = StageReceipt
      .of(
        Stage.Scoring,
        "declared-policy",
        checksum,
        Vector(Checksum.ofText("input")),
        ProviderStatus.Identified(provider)
      )
      .toOption
      .get
    val asserted =
      StageProvenance.unknown(UnknownProvenanceReason.NominationProvenanceUnproven, Some(receipt))
    val extra = StageEntry.of(Stage.Scoring, asserted).toOption.get
    val all = (entries :+ extra).sortBy(e => (e.stage.ordinal, e.id.digest.hex))
    val value = StageLedger.of(all).toOption.get
    assertEquals(value.at(Stage.Scoring).size, 2)
    assert(value.get(extra.id).get.provenance.isInstanceOf[StageProvenance.Unknown])
    assertEquals(asserted.asserted, Some(receipt))
    assertNotEquals(extra.id, entry(Stage.Scoring).id)
    assertEquals(
      StageEntry.of(Stage.Rendering, asserted).swap.toOption.get,
      MappingRefusal.StageMismatch(Stage.Rendering, Stage.Scoring)
    )
  }
  test(
    "receipt identity retains provider, model, config, inputs, optional presence and code units"
  ) {
    def id(
        policy: String,
        inputs: Vector[Checksum],
        provider: ProviderStatus,
        config: Checksum = checksum
    ): StageEntryId =
      val r = StageReceipt.of(Stage.Scoring, policy, config, inputs, provider).toOption.get
      StageEntry
        .of(Stage.Scoring, StageProvenance.unknown(UnknownProvenanceReason.NotRun, Some(r)))
        .toOption
        .get
        .id
    val absent = ProviderStatus.NotApplicable("not used")
    val a = id("p", Vector.empty, absent)
    assertNotEquals(a, id("p", Vector.empty, absent, Checksum.ofText("other configuration")))
    assertNotEquals(a, id("p", Vector(checksum), absent))
    assertNotEquals(a, id("other", Vector.empty, absent))
    assertNotEquals(a, id("p", Vector.empty, ProviderStatus.NotApplicable("other")))
    assertNotEquals(
      id("p" + String.valueOf(0xd800.toChar), Vector.empty, absent),
      id("p" + String.valueOf(0xd801.toChar), Vector.empty, absent)
    )
    val p1 = ProviderIdentity.of("a", "b", checksum).toOption.get
    val p2 = ProviderIdentity.of("a", "c", checksum).toOption.get
    assertNotEquals(
      id("p", Vector.empty, ProviderStatus.Identified(p1)),
      id("p", Vector.empty, ProviderStatus.Identified(p2))
    )
    assertNotEquals(
      a,
      StageEntry
        .of(Stage.Scoring, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
        .toOption
        .get
        .id
    )
  }
  test("unit stage references resolve to their required kinds") {
    val l = ledger
    val inference = l.at(Stage.Inference).head.id
    val candidates = l.at(Stage.Candidates).head.id
    val decision = l.at(Stage.Decision).head.id
    assert(UnitStageRefs.of(l, inference, candidates, decision).isRight)
    assertEquals(
      UnitStageRefs.of(l, candidates, inference, decision).swap.toOption.get,
      MappingRefusal.StageMismatch(Stage.Inference, Stage.Candidates)
    )
    val foreign = StageEntry
      .of(Stage.Inference, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
      .toOption
      .get
      .id
    assertEquals(
      UnitStageRefs.of(l, foreign, candidates, decision).swap.toOption.get,
      MappingRefusal.DanglingStageEntry(foreign)
    )
  }
  test("candidate identity binds stage, unit and all basis keys including external") {
    val stage = entry(Stage.Candidates).id
    val a = RecallUnitId.unsafe("a")
    val b = RecallUnitId.unsafe("b")
    val keys =
      Set[Destination](Destination.Target(e1), Destination.External(ExternalState.Intrusion))
    val id = CandidateSetId.of(stage, a, keys)
    assertNotEquals(id, CandidateSetId.of(stage, b, keys))
    assertNotEquals(id, CandidateSetId.of(stage, a, Set(Destination.Target(e1))))
    assertNotEquals(id, CandidateSetId.of(entry(Stage.Scoring).id, a, keys))
    assertEquals(id, CandidateSetId.of(stage, a, keys.toVector.reverse.toSet))
  }
  test("universe identity is canonical, grain-bound and duplicate-refusing") {
    val a = DeclaredUniverse.of(Vector(e2, e1), TargetGrain.SingleLevel(0)).toOption.get
    val b = DeclaredUniverse.of(Vector(e1, e2), TargetGrain.SingleLevel(0)).toOption.get
    assertEquals(a.targets, Vector(e1, e2))
    assertEquals(a.id, b.id)
    assertNotEquals(
      a.id,
      DeclaredUniverse.of(Vector(e1, e2), TargetGrain.Hierarchy(Vector(0, 1))).toOption.get.id
    )
    assertEquals(
      DeclaredUniverse.of(Vector(e1, e1), TargetGrain.SingleLevel(0)).swap.toOption.get,
      MappingRefusal.DuplicateTarget(e1)
    )
    assert(DeclaredUniverse.of(Vector(e1), TargetGrain.SingleLevel(-1)).isLeft)
    assert(DeclaredUniverse.of(Vector(e1), TargetGrain.Hierarchy(Vector(1, 0))).isLeft)
    assert(DeclaredUniverse.of(Vector.empty, TargetGrain.SingleLevel(0)).isRight)
  }
  test("policies preserve truncated coverage and explicit absences") {
    val universe = DeclaredUniverse.of(Vector(e1), TargetGrain.SingleLevel(0)).toOption.get
    val candidate =
      CandidatePolicy.Declared(CandidatePolicyId.unsafe("top-k"), CandidateCoverage.Truncated(8))
    val p = MappingPolicies
      .of(
        InferencePolicy.HistoricalReconstruction("fixture"),
        ContextPolicy.Unspecified("unknown"),
        candidate,
        ReferencePrior.NotApplicable("not normalized"),
        DecisionPolicy.NotApplicable("argmax"),
        universe
      )
      .toOption
      .get
    assertEquals(p.candidate, candidate)
    assertEquals(p.referencePrior, ReferencePrior.NotApplicable("not normalized"))
    assert(
      MappingPolicies
        .of(
          p.inference,
          p.context,
          CandidatePolicy.Declared(CandidatePolicyId.unsafe("bad"), CandidateCoverage.Truncated(0)),
          p.referencePrior,
          p.decision,
          universe
        )
        .isLeft
    )
  }
