package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.MissingReason
import storymodel4s.recall.*

class MappingResultSuite extends FunSuite:
  import MappingMeasureFixture.{recall, view, stage, stages, ledger}
  private val inventory = MappingMeasureFixture.inventory(recall)
  private val source = MappingMeasureFixture.source(view)
  private val grain = TargetGrain.Hierarchy(source.targets.map(_.level).distinct.sorted)
  private val universe = DeclaredUniverse.of(source.targets.map(_.ref), grain).toOption.get
  private val external = Destination.External(ExternalState.Intrusion)
  private val target = Destination.Target(AnnaFixture.e1)
  private val basis = DecisionBasis.of(MeasureKind.RawScore, Some("fixture")).toOption.get
  private def roles(inv: RecallInventory = inventory): UnitRoles = UnitRoles
    .of(
      AnalysisGrain.InferenceUnit(inv.segmentation),
      AnalysisGrain.InferenceUnit(inv.segmentation),
      AnalysisGrain.Targets(grain)
    )
    .toOption
    .get
  private def policies(
      u: DeclaredUniverse = universe,
      prior: ReferencePrior = ReferencePrior.NotApplicable("raw controls")
  ): MappingPolicies =
    MappingPolicies
      .of(
        InferencePolicy.Unspecified("synthetic contract"),
        ContextPolicy.Unspecified("none"),
        CandidatePolicy.Declared(CandidatePolicyId.unsafe("test"), CandidateCoverage.Truncated(8)),
        prior,
        DecisionPolicy.NotApplicable("raw argmax"),
        u
      )
      .toOption
      .get
  private def row(
      id: RecallUnitId,
      values: Map[Destination, Double] = Map(target -> 0.6, external -> 0.4),
      scoring: StageEntryId = stage(Stage.Scoring),
      refs: UnitStageRefs = stages
  ): UnitOutcome =
    val raw = RawScores
      .of("fixture", ScoreDirection.HigherIsBetter, "arbitrary", values, scoring)
      .toOption
      .get
    val measures = UnitMeasures.of(Vector(raw), None, None, None).toOption.get
    val candidate = CandidateSetId.of(refs.candidates, id, values.keySet)
    val links = values.keys.toVector.sorted.map(MappingLink.ungated(_, refs, candidate))
    UnitOutcome.computed(id, measures, links, basis, DecisionRequest.RawArgmax, refs).toOption.get
  private def rows: Vector[UnitOutcome] = recall.ordered.map(u =>
    if u.id == recall.ordered.last.id then
      UnitOutcome.failed(u.id, ProcessingFailure.ProviderFailure("synthetic failure"))
    else row(u.id)
  )
  private def checked(
      outcomes: Vector[UnitOutcome] = rows,
      inv: RecallInventory = inventory,
      representation: SourceRepresentation = source,
      policy: MappingPolicies = policies(),
      unitRoles: UnitRoles = roles(),
      stagesLedger: StageLedger = ledger
  ): Either[MappingRefusal, MappingResult] =
    MappingResult.checked(inv, representation, policy, unitRoles, stagesLedger, outcomes)
  private def refused(value: Either[MappingRefusal, ?], expected: MappingRefusal): Unit =
    assertEquals(value.left.toOption, Some(expected))

  test("missing unit refuses") {
    refused(checked(rows.dropRight(1)), MappingRefusal.OutcomeInventoryMismatch)
    refused(checked(rows :+ rows.head), MappingRefusal.OutcomeInventoryMismatch)
    refused(
      checked(
        rows.updated(
          0,
          UnitOutcome
            .failed(RecallUnitId.unsafe("foreign"), ProcessingFailure.InvalidOutput("test"))
        )
      ),
      MappingRefusal.OutcomeInventoryMismatch
    )
  }
  test("reordered outcomes refuse") {
    refused(checked(rows.reverse), MappingRefusal.OutcomeInventoryMismatch)
  }
  test("failed outcomes are retained and counted") {
    val result = checked().toOption.get
    assertEquals(result.outcomes.map(_.unit), inventory.units.map(_.id))
    assertEquals(result.outcomes.size, 4)
    assertEquals(result.outcomes.count(_.processing.isInstanceOf[ProcessingStatus.Failed]), 1)
    assertEquals(
      result.outcome(recall.ordered.last.id).get.localization,
      LocalizationStatus.NotComputed
    )
    assertEquals(result.derivation, DerivationSource.NoDerivedValues)
  }
  test("universe outside dictionary refuses") {
    val unknown = SourceNodeRef.Situation(SituationId.unsafe("foreign"))
    val wrong = DeclaredUniverse.of(universe.targets :+ unknown, grain).toOption.get
    refused(checked(policy = policies(wrong)), MappingRefusal.UnknownTarget(unknown))
  }
  test("measured targets outside the declared universe refuse while externals are exempt") {
    val empty = DeclaredUniverse.of(Vector.empty, TargetGrain.SingleLevel(0)).toOption.get
    refused(checked(policy = policies(empty)), MappingRefusal.UniverseMismatch)
    val allExternal = recall.ordered.map(u => row(u.id, Map(external -> 1.0)))
    val admitted = checked(allExternal, policy = policies(empty)).toOption.get
    assert(admitted.outcomes.forall(_.localization == LocalizationStatus.Nonlocalizable))
  }
  test("the target universe agrees with actual target levels") {
    val wrong = DeclaredUniverse.of(universe.targets, TargetGrain.SingleLevel(99)).toOption.get
    refused(checked(policy = policies(wrong)), MappingRefusal.UniverseMismatch)
  }
  test("an unmeasured decoded target must belong to the universe") {
    val value = rows.head
    val chosen = Destination.Target(AnnaFixture.e2)
    val changed = UnitOutcome
      .computed(
        value.unit,
        value.measures,
        value.links,
        basis,
        DecisionRequest.ExternalDecode(chosen, DecisionPolicyId.unsafe("supplied")),
        stages
      )
      .toOption
      .get
    val restricted =
      DeclaredUniverse.of(Vector(AnnaFixture.e1), TargetGrain.SingleLevel(0)).toOption.get
    refused(
      checked(rows.updated(0, changed), policy = policies(restricted)),
      MappingRefusal.UniverseMismatch
    )
  }
  test("unknown stage entry refuses") {
    val foreign = StageEntry
      .of(Stage.Scoring, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
      .toOption
      .get
      .id
    refused(
      checked(rows.updated(0, row(rows.head.unit, scoring = foreign))),
      MappingRefusal.DanglingStageEntry(foreign)
    )
  }
  test("empty computed rows retain and validate their stage references") {
    val foreign = StageEntry
      .of(Stage.Inference, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
      .toOption
      .get
    val otherLedger = StageLedger
      .of((ledger.entries :+ foreign).sortBy(e => (e.stage.ordinal, e.id.digest.hex)))
      .toOption
      .get
    val otherRefs =
      UnitStageRefs.of(otherLedger, foreign.id, stages.candidates, stages.decision).toOption.get
    val changed = rows.updated(0, row(rows.head.unit, Map.empty, refs = otherRefs))
    refused(checked(changed), MappingRefusal.DanglingStageEntry(foreign.id))
    assert(checked(changed, stagesLedger = otherLedger).isRight)
  }
  test("roles are bound to the inventory segmentation") {
    val changedUnits =
      recall.ordered.map(u => u.copy(id = RecallUnitId.unsafe("other-" + u.id.value)))
    val changed = RecallGraph
      .validated(recall.transcript, recall.atlas, changedUnits, RecallRelations.empty)
      .toOption
      .get
    val other = MappingMeasureFixture.inventory(changed)
    refused(checked(unitRoles = roles(other)), MappingRefusal.RolesMismatch)
  }
  test("normalized mass matches universe and prior") {
    val prior = ReferencePriorId.unsafe("prior")
    val values = Map(target -> 0.4, external -> 0.6)
    def normalized(u: TargetUniverseId): UnitOutcome =
      val measure = NormalizedScoreMass.of(u, prior, 1.0, values, stage(Stage.Scoring)).toOption.get
      val measures = UnitMeasures.of(Vector.empty, Some(measure), None, None).toOption.get
      val candidate = CandidateSetId.of(stages.candidates, rows.head.unit, values.keySet)
      val links = values.keys.toVector.sorted.map(MappingLink.ungated(_, stages, candidate))
      UnitOutcome
        .computed(
          rows.head.unit,
          measures,
          links,
          DecisionBasis.of(MeasureKind.NormalizedScoreMass, None).toOption.get,
          DecisionRequest.RawArgmax,
          stages
        )
        .toOption
        .get
    val correct = rows.updated(0, normalized(universe.id))
    assert(checked(correct, policy = policies(prior = ReferencePrior.Declared(prior))).isRight)
    refused(checked(correct), MappingRefusal.PriorMismatch)
    refused(
      checked(
        correct,
        policy = policies(prior = ReferencePrior.Declared(ReferencePriorId.unsafe("foreign")))
      ),
      MappingRefusal.PriorMismatch
    )
    val other = DeclaredUniverse.of(Vector(AnnaFixture.e1), TargetGrain.SingleLevel(0)).toOption.get
    refused(
      checked(
        rows.updated(0, normalized(other.id)),
        policy = policies(prior = ReferencePrior.Declared(prior))
      ),
      MappingRefusal.UniverseMismatch
    )
  }
  test("Truncated(8) is retained") {
    checked().toOption.get.policies.candidate match
      case CandidatePolicy.Declared(_, coverage) =>
        assertEquals(coverage, CandidateCoverage.Truncated(8))
      case _ => fail("declared candidate policy lost")
  }

  private def derivedRow(
      id: RecallUnitId,
      result: HsmmResult = MappingMeasureFixture.result,
      boundInventory: RecallInventory = inventory,
      assessed: Boolean = false
  ): UnitOutcome =
    val binding = DerivationBinding.of(result, recall, boundInventory, view, source).toOption.get
    val raw = RawScores.fromCosts(result, id, binding, stage(Stage.Scoring)).toOption.get
    val measures = UnitMeasures.of(Vector(raw), None, None, None).toOption.get
    val candidate = CandidateSetId.of(stages.candidates, id, raw.values.keySet)
    val links =
      if assessed then
        result
          .costs(id)
          .keys
          .toVector
          .map(s =>
            MappingLink
              .fromResult(result, binding, recall, view, source, id, s, stages, candidate)
              .toOption
              .get
          )
      else raw.values.keys.toVector.map(MappingLink.ungated(_, stages, candidate))
    UnitOutcome
      .computed(
        id,
        measures,
        links,
        DecisionBasis.of(MeasureKind.RawScore, Some(raw.channel)).toOption.get,
        DecisionRequest.RawArgmax,
        stages
      )
      .toOption
      .get
  test("a single derived result binds the record") {
    val result = checked(recall.ordered.map(u => derivedRow(u.id))).toOption.get
    assertEquals(
      result.derivation.asInstanceOf[DerivationSource.Bound].binding,
      MappingMeasureFixture.bind()
    )
    assertEquals(result.outcomes.size, inventory.units.size)
  }
  test("record mixing two results refuses") {
    val original = MappingMeasureFixture.result
    val variant = MappingMeasureFixture.costVariant
    assert(checked(recall.ordered.map(u => derivedRow(u.id, original))).isRight)
    assert(checked(recall.ordered.map(u => derivedRow(u.id, variant))).isRight)
    val mixed = recall.ordered.zipWithIndex.map((u, i) =>
      derivedRow(u.id, if i == 0 then original else variant)
    )
    refused(checked(mixed), MappingRefusal.BindingMismatch("record.derivation"))
  }
  test("foreign inventory binding refuses even when unit ids and segmentation match") {
    val foreign = RecallInventory
      .of(
        recall,
        inventory.words.map(_.span),
        WordIdPolicy.inputArtifact(Checksum.ofText("another-parser-input-artifact"))
      )
      .toOption
      .get
    assertEquals(foreign.segmentation, inventory.segmentation)
    assertNotEquals(foreign.digest, inventory.digest)
    refused(
      checked(recall.ordered.map(u => derivedRow(u.id)), inv = foreign),
      MappingRefusal.BindingMismatch("inventoryDigest")
    )
    assert(
      checked(
        recall.ordered.map(u => derivedRow(u.id, boundInventory = foreign)),
        inv = foreign
      ).isRight
    )
  }
  test("bound values refuse a different source scope") {
    val twin = view.copy(nodes =
      view.nodes.map(
        _.copy(propositional = PropositionalScope.Undeclared(MissingReason.ProviderAbstained))
      )
    )
    val representation = MappingMeasureFixture.source(twin)
    assertEquals(representation.viewFingerprint, source.viewFingerprint)
    refused(
      checked(recall.ordered.map(u => derivedRow(u.id)), representation = representation),
      MappingRefusal.BindingMismatch("scopeDigest")
    )
  }
  test("bound values refuse a different source fingerprint") {
    val twin =
      view.copy(nodes = view.nodes.map(n => n.copy(discoursePosition = n.discoursePosition + 1)))
    val representation = MappingMeasureFixture.source(twin)
    refused(
      checked(recall.ordered.map(u => derivedRow(u.id)), representation = representation),
      MappingRefusal.BindingMismatch("viewFingerprint")
    )
  }
  test("Assessed on a non-Declared target refuses") {
    val assessed = recall.ordered.map(u => derivedRow(u.id, assessed = true))
    assert(checked(assessed).isRight)
    val twin = view.copy(nodes =
      view.nodes.map(
        _.copy(propositional = PropositionalScope.Undeclared(MissingReason.ProviderAbstained))
      )
    )
    val first = assessed
      .flatMap(_.links)
      .collect { case l if l.fidelity.isInstanceOf[FidelityStatus.Assessed] => l.destination }
      .collect { case Destination.Target(ref) => ref }
      .sorted
      .head
    refused(
      checked(assessed, representation = MappingMeasureFixture.source(twin)),
      MappingRefusal.ScopeUndeclared(first)
    )
  }
  test("record digest distinguishes values, failure reasons and request identity") {
    val original = checked().toOption.get
    assertEquals(original.digest, checked().toOption.get.digest)
    val changedValue = rows.updated(0, row(rows.head.unit, Map(target -> 0.7, external -> 0.3)))
    assertNotEquals(original.digest, checked(changedValue).toOption.get.digest)
    val changedFailure = rows.updated(
      rows.size - 1,
      UnitOutcome.failed(rows.last.unit, ProcessingFailure.ProviderFailure("different failure"))
    )
    assertNotEquals(original.digest, checked(changedFailure).toOption.get.digest)
    val empty = row(rows.head.unit, Map.empty)
    def requested(destination: Destination): UnitOutcome = UnitOutcome
      .computed(
        empty.unit,
        empty.measures,
        empty.links,
        basis,
        DecisionRequest.ExternalDecode(destination, DecisionPolicyId.unsafe("decoder")),
        stages
      )
      .toOption
      .get
    val a = checked(rows.updated(0, requested(target))).toOption.get
    val b = checked(rows.updated(0, requested(external))).toOption.get
    assertEquals(a.outcomes.head.decision.get.chosen, b.outcomes.head.decision.get.chosen)
    assertNotEquals(a.digest, b.digest)
  }
