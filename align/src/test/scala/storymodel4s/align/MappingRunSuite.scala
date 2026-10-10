package storymodel4s.align

import munit.FunSuite

/** The execution envelope retains one origin for reference and reconstruction projections. */
class MappingRunSuite extends FunSuite:
  import ReferenceFixture.*
  import StructuredReconstruction.*

  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def source(v: SourceView = view) = MappingMeasureFixture.source(v)
  private def inventory(
      r: storymodel4s.recall.RecallGraph[storymodel4s.recall.RecallGraphStatus.Checked] = recall
  ) =
    MappingMeasureFixture.inventory(r)
  private def execution(
      r: storymodel4s.recall.RecallGraph[storymodel4s.recall.RecallGraphStatus.Checked] = recall,
      v: SourceView = view
  ) =
    val e = evidence(table(r, v), r, v)
    val s = source(v)
    val groups = right(Grouping.of(v, Vector(a -> 1, b -> 2)))
    right(MappingRun.compute(r, v, inventory(r), s, e, profile(v), Profile.sherlock(), groups))

  test("one strict content evidence object supplies both actual projections") {
    val run = execution()
    val reference = right(run.record(MappingRun.Member.Reference))
    val reconstruction = right(run.record(MappingRun.Member.Reconstruction))
    assert(run.reference.evidence eq run.reconstruction.evidence)
    assertEquals(reference.derivation, DerivationSource.NoDerivedValues)
    assert(reconstruction.derivation.isInstanceOf[DerivationSource.Bound])
    assert(reference.outcomes.forall(_.measures.posterior.isEmpty))
    assert(reconstruction.outcomes.forall(_.measures.posterior.nonEmpty))
  }

  test("foreign inventory and source refuse before either projection executes") {
    val e = evidence(table())
    val groups = right(Grouping.of(view, Vector(a -> 1, b -> 2)))
    val foreignRecall =
      graph(Vector(unit.copy(function = storymodel4s.recall.DiscourseFunction.Association)))
    assert(
      MappingRun
        .compute(
          foreignRecall,
          view,
          inventory(recall),
          source(),
          e,
          profile(),
          Profile.sherlock(),
          groups
        )
        .isLeft
    )
    val foreignView = view.copy(scoringLength = view.scoringLength + 1)
    assert(
      MappingRun
        .compute(
          recall,
          foreignView,
          inventory(recall),
          source(),
          e,
          profile(),
          Profile.sherlock(),
          groups
        )
        .isLeft
    )
  }

  test("reference NotComputed rows retain their inventory unit") {
    val unavailable = evidence(StrictSemanticChannel.Unavailable)
    val groups = right(Grouping.of(view, Vector(a -> 1, b -> 2)))
    val run = right(
      MappingRun.compute(
        recall,
        view,
        inventory(),
        source(),
        unavailable,
        profile(),
        Profile.sherlock(),
        groups
      )
    )
    val record = right(run.record(MappingRun.Member.Reference))
    assertEquals(record.outcomes.map(_.unit), inventory().units.map(_.id))
    assert(record.outcomes.forall(_.decision.isEmpty))
  }

  test("genuine derived stages cannot certify changed normalized values") {
    val run = execution()
    val record = right(run.referenceRecord)
    val row = record.outcomes.head
    val mass = row.measures.normalized.get
    val keys = mass.mass.keys.toVector.sorted
    val changed = keys.map(k => k -> (if k == keys.last then 1.0 else 0.0)).toMap
    assertNotEquals(changed, mass.mass)
    val normalized = right(
      NormalizedScoreMass.of(mass.universe, mass.prior, mass.temperature, changed, mass.stage)
    )
    val measures = right(UnitMeasures.of(Vector.empty, Some(normalized), None, None))
    val altered = right(
      UnitOutcome.computed(
        row.unit,
        measures,
        row.links,
        row.decision.get.basis,
        DecisionRequest.RawArgmax,
        row.stages.get
      )
    )
    val result = MappingResult.checked(
      record.inventory,
      record.source,
      record.policies,
      record.roles,
      record.ledger,
      altered +: record.outcomes.tail
    )
    assertEquals(result, Left(MappingRefusal.BindingMismatch("executed projection")))
  }

  test("local evaluated links cannot shed their execution and move to foreign source content") {
    val run = execution()
    val record = right(run.referenceRecord)
    val row = record.outcomes.head
    val normalized = row.measures.normalized.get
    val stages = MappingMeasureFixture.stages
    val candidates = CandidateSetId.of(stages.candidates, row.unit, normalized.mass.keySet)
    val stateKeys =
      run.reference.outcomes.head.asInstanceOf[LocalReference.Computed].mass.keys.toVector.sorted
    val links = stateKeys.map(state =>
      right(
        MappingLink.fromEvidence(
          run.reference.evidence,
          run.recall,
          run.view,
          run.source,
          row.unit,
          state,
          stages,
          candidates
        )
      )
    )
    val measure = right(
      NormalizedScoreMass.of(
        normalized.universe,
        normalized.prior,
        normalized.temperature,
        normalized.mass,
        stages.inference
      )
    )
    val measures = right(UnitMeasures.of(Vector.empty, Some(measure), None, None))
    val detached = right(
      UnitOutcome.computed(
        row.unit,
        measures,
        links,
        row.decision.get.basis,
        DecisionRequest.RawArgmax,
        stages
      )
    )
    val changed = view.copy(nodes = view.nodes.map(n => n.copy(lemmas = Set("changed"))))
    val foreign = source(changed)
    assertNotEquals(foreign.digest, record.source.digest)
    val result = MappingResult.checked(
      record.inventory,
      foreign,
      record.policies,
      record.roles,
      MappingMeasureFixture.ledger,
      Vector(detached)
    )
    assertEquals(result, Left(MappingRefusal.BindingMismatch("local evidence execution required")))
  }

  test("requested fill into an admitted zero-mass candidate retains fill origin and zero mass") {
    val r = graph(
      Vector(
        unit,
        AnnaFixture.u1.copy(
          proposition = unit.proposition.copy(lemmas = Set("leave"), predicate = Some("leave")),
          function = unit.function
        )
      )
    )
    val e = evidence(
      table(
        r,
        view,
        (u, ref) => if (u == unit.id && ref == b) || (u != unit.id && ref == a) then 0.0 else 1.0
      ),
      r,
      view
    )
    val groups = right(Grouping.of(view, Vector(a -> 1, b -> 2)))
    val decision = right(DecisionPolicy.of(OrderPolicy.Nondecreasing, fill = FillPolicy.Requested))
    val reconstruction = right(Profile.of(right(HsmmConfig.of(temperature = 0.000001)), decision))
    val fill = right(FillRequest.of(r, groups, Vector((unit.id -> 1) -> a)))
    val run = right(
      MappingRun.compute(
        r,
        view,
        inventory(r),
        source(),
        e,
        profile(),
        reconstruction,
        groups,
        Some(fill)
      )
    )
    val actual = run.reconstruction.decisions.find(_.unit == unit.id).get
    assertEquals(actual.origin, Origin.RequestedFill)
    assertEquals(actual.chosenMass, Some(0.0))
    val row = right(run.reconstructionRecord).outcome(unit.id).get
    assert(row.measures.posterior.get.mass.keys.exists(_.anchor.contains(a)))
    assert(row.decision.get.origin.isInstanceOf[DecisionOrigin.GapFill])
    assertEquals(
      row.decision.get.decodedMass.asInstanceOf[DecodedTargetMass.InCandidateSupport].value,
      0.0
    )
  }
