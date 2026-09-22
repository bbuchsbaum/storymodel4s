package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.MissingReason
import storymodel4s.recall.*

class MappingMeasuresSuite extends FunSuite:
  import MappingMeasureFixture.*
  private val unit = recall.ordered.head.id
  private val destination = Destination.Target(AnnaFixture.e1)
  private val external = Destination.External(ExternalState.Intrusion)
  private val universe =
    DeclaredUniverse.of(Vector(AnnaFixture.e1), TargetGrain.SingleLevel(0)).toOption.get
  private val prior = ReferencePriorId.unsafe("synthetic-prior")
  private val scoring = stage(Stage.Scoring)
  private def invalid(value: Either[MappingRefusal, ?], field: String): Unit = value match
    case Left(MappingRefusal.InvalidValue(`field`, _)) => ()
    case other => fail(s"expected invalid $field, got $other")

  test("raw scores preserve channel direction scale and supplied status") {
    val raw = RawScores
      .of(
        "authored",
        ScoreDirection.HigherIsBetter,
        "arbitrary",
        Map(destination -> 0.9, external -> -0.4),
        scoring
      )
      .toOption
      .get
    assertEquals(raw.values, Map(destination -> 0.9, external -> -0.4))
    assertEquals(raw.channel, "authored")
    assertEquals(raw.direction, ScoreDirection.HigherIsBetter)
    assertEquals(raw.scale, "arbitrary")
    assertEquals(raw.derivation, MeasureDerivation.Supplied)
    Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach(v =>
      invalid(
        RawScores.of("a", ScoreDirection.HigherIsBetter, "b", Map(destination -> v), scoring),
        "rawScores"
      )
    )
    invalid(RawScores.of("", ScoreDirection.HigherIsBetter, "b", Map.empty, scoring), "rawScores")
  }
  test("unnormalized mass refuses") {
    invalid(
      NormalizedScoreMass.of(universe.id, prior, 1.0, Map(destination -> 0.4), scoring),
      "normalizedScoreMass"
    )
    invalid(
      NormalizedScoreMass.of(universe.id, prior, 1.0, Map.empty, scoring),
      "normalizedScoreMass"
    )
    val mass = Map(destination -> 0.4, external -> 0.6)
    assertEquals(
      NormalizedScoreMass.of(universe.id, prior, 0.5, mass, scoring).toOption.get.mass,
      mass
    )
    Vector(Double.NaN, 0.0, -1.0, Double.PositiveInfinity).foreach(t =>
      invalid(NormalizedScoreMass.of(universe.id, prior, t, mass, scoring), "normalizedScoreMass")
    )
    invalid(
      NormalizedScoreMass.of(universe.id, prior, 1.0, Map(destination -> Double.NaN), scoring),
      "normalizedScoreMass"
    )
  }
  test("transport keeps its budget without row normalization") {
    val mass = Map(destination -> 2.0, external -> 3.0)
    val value = TransportMass.of(7.0, mass, scoring).toOption.get
    assertEquals(value.mass, mass)
    assertEquals(value.rowBudget, 7.0)
    assert(TransportMass.of(0.0, Map.empty, scoring).isRight)
    invalid(TransportMass.of(-1.0, mass, scoring), "transportMass")
    invalid(TransportMass.of(Double.NaN, mass, scoring), "transportMass")
    invalid(TransportMass.of(1.0, Map(destination -> -0.1), scoring), "transportMass")
  }
  test("authored links NotComputed(NoCostBreakdown)") {
    val value = MappingLink.ungated(destination, stages, candidate(unit))
    assertEquals(value.gate, GateOutcome.NotGated)
    assertEquals(
      value.fidelity.asInstanceOf[FidelityStatus.NotAssessed].reason,
      FidelityNotAssessedReason.NoGateEvaluation
    )
    assertEquals(
      value.termSupport.asInstanceOf[TermSupportStatus.NotComputed].reason,
      TermSupportNotComputedReason.NoCostBreakdown
    )
    assertEquals(value.derivation, MeasureDerivation.Supplied)
  }
  test("model posterior preserves full state keys and exact values") {
    recall.ordered.foreach { u =>
      val value = ModelPosterior.of(result, u.id, bind(), stage(Stage.Inference)).toOption.get
      assertEquals(value.mass, result.posterior.row(u.id).get.mass)
      assertEquals(value.unit, u.id)
      assert(value.mass.keys.exists(_.isExternal))
    }
  }
  test("raw costs retain their originating unit and binding") {
    val value = RawScores.fromCosts(result, unit, bind(), scoring).toOption.get
    assertEquals(value.values, result.costs(unit).map((s, c) => Destination.of(s) -> c.total))
    assertEquals(value.direction, ScoreDirection.LowerIsBetter)
    val derived = value.derivation.asInstanceOf[MeasureDerivation.FromResult]
    assertEquals(derived.unit, unit)
    assertEquals(derived.binding, bind())
  }
  test("fidelity on the link's own unit") {
    val reports = recall.ordered.flatMap { u =>
      result.costs(u.id).keys.toVector.filter(_.isSource).map { s =>
        val value = link(u.id, s).toOption.get
        val report = value.fidelity.asInstanceOf[FidelityStatus.Assessed].report
        assertEquals(
          report,
          FidelityFacets.assess(u.proposition, view.node(s.anchor.get).get, s.mode.get)
        )
        assertEquals(
          value.termSupport.asInstanceOf[TermSupportStatus.Evaluated].assessment,
          result.costs(u.id)(s).support
        )
        assertEquals(value.derivation.asInstanceOf[MeasureDerivation.FromResult].unit, u.id)
        report
      }
    }
    assert(reports.distinct.size > 1)
  }
  test("undeclared scope NotAssessed and twin view yields NotAssessed") {
    val twin = view.copy(nodes =
      view.nodes.map(
        _.copy(propositional = PropositionalScope.Undeclared(MissingReason.ProviderAbstained))
      )
    )
    assertEquals(ViewFingerprint.of(twin), ViewFingerprint.of(view))
    val binding = bind(v = twin)
    val s = result.costs(unit).keys.find(_.isSource).get
    val value = link(unit, s, binding = binding, v = twin).toOption.get
    assertEquals(
      value.fidelity.asInstanceOf[FidelityStatus.NotAssessed].reason,
      FidelityNotAssessedReason.ScopeUndeclared
    )
    assert(value.termSupport.isInstanceOf[TermSupportStatus.Evaluated])
  }
  test("Declared twin view refuses binding") {
    val twin = view.copy(nodes =
      view.nodes.map(
        _.copy(propositional = PropositionalScope.Undeclared(MissingReason.ProviderAbstained))
      )
    )
    assertEquals(
      DerivationBinding.of(result, recall, inventory(recall), twin, source(view)).left.toOption,
      Some(MappingRefusal.BindingMismatch("scopeDigest"))
    )
    val s = result.costs(unit).keys.find(_.isSource).get
    assertEquals(
      link(unit, s, v = twin).left.toOption,
      Some(MappingRefusal.BindingMismatch("scopeDigest"))
    )
    assert(link(unit, s).isRight)
  }
  test("foreign recall sharing unit IDs refuses") {
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
      DerivationBinding.of(result, changed, inventory(changed), view, source(view)).left.toOption,
      Some(MappingRefusal.BindingMismatch("recallChecksum"))
    )
    val s = result.costs(unit).keys.head
    assertEquals(
      link(unit, s, recalled = changed).left.toOption,
      Some(MappingRefusal.BindingMismatch("recallChecksum"))
    )
  }
  test("posterior from a foreign result refuses") {
    Vector(costVariant, posteriorVariant).foreach { other =>
      assertNotEquals(bind(other).resultDigest, bind().resultDigest)
      assertEquals(
        ModelPosterior.of(other, unit, bind(), stage(Stage.Inference)).left.toOption,
        Some(MappingRefusal.BindingMismatch("resultDigest"))
      )
      assertEquals(
        ModelPosterior.of(other, unit, bind(other), stage(Stage.Inference)).toOption.get.mass,
        other.posterior.row(unit).get.mass
      )
    }
  }
  test("raw costs from a foreign result refuse") {
    Vector(costVariant, posteriorVariant).foreach { other =>
      assertEquals(
        RawScores.fromCosts(other, unit, bind(), scoring).left.toOption,
        Some(MappingRefusal.BindingMismatch("resultDigest"))
      )
      assert(RawScores.fromCosts(other, unit, bind(other), scoring).isRight)
    }
  }
  test("link from a foreign result refuses") {
    val s = result.costs(unit).keys.head
    Vector(costVariant, posteriorVariant).foreach { other =>
      assertEquals(
        link(unit, s, other).left.toOption,
        Some(MappingRefusal.BindingMismatch("resultDigest"))
      )
      assert(link(unit, s, other, bind(other)).isRight)
    }
  }
  test("external links are ungated and have inapplicable fidelity and support") {
    val s = result.costs(unit).keys.find(_.isExternal).get
    val value = link(unit, s).toOption.get
    assertEquals(value.gate, GateOutcome.NotGated)
    assertEquals(value.fidelity, FidelityStatus.NotApplicable)
    assertEquals(
      value.termSupport.asInstanceOf[TermSupportStatus.Evaluated].assessment,
      SupportAssessment.externalState
    )
  }
  test("missing cost refuses and unknown unit refuses") {
    val s = result.costs(unit).keys.head
    val missing = revalidate(result.costs.updated(unit, result.costs(unit).removed(s)))
    assertEquals(
      link(unit, s, missing, bind(missing)).left.toOption,
      Some(MappingRefusal.MissingCost(unit, s))
    )
    val unknown = RecallUnitId.unsafe("absent")
    assertEquals(
      ModelPosterior.of(result, unknown, bind(), stage(Stage.Inference)).left.toOption,
      Some(MappingRefusal.UnknownUnit(unknown))
    )
  }
  test("unit measures preserve every alternative and reject duplicate channels") {
    val raw = RawScores
      .of(
        "authored",
        ScoreDirection.HigherIsBetter,
        "arbitrary",
        Map(destination -> 0.9, external -> 0.4),
        scoring
      )
      .toOption
      .get
    val posterior = ModelPosterior.of(result, unit, bind(), stage(Stage.Inference)).toOption.get
    val value = UnitMeasures.of(Vector(raw), None, None, Some(posterior)).toOption.get
    assertEquals(value.destinations, raw.values.keySet ++ posterior.mass.keys.map(Destination.of))
    invalid(UnitMeasures.of(Vector(raw, raw), None, None, None), "unitMeasures.raw")
  }
