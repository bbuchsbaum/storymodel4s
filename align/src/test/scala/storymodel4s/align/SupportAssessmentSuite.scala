package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.MissingReason
import storymodel4s.recall.RecallUnitId

/** Support honesty (bd-01M19956MFSG7076QE4J66T7E9): AN EMPTY ELIGIBLE DENOMINATOR CANNOT PUBLISH
  * THE NUMERIC CLAIM "FULLY SUPPORTED".
  *
  * Until `hsmm/v4` support was one `Double` with three meanings. `supportOf` returned `1.0` when
  * the eligible set was empty and again when the eligible weight was zero, and every external cell
  * took the default `1.0` — so on the WOG golden the ten external cells published `1.0` with zero
  * terms while the real anchors published `0.955`. These courts pin the three-state replacement:
  * which state each kind of record gets, that shares and reasons are derived rather than chosen,
  * and that support must describe the record it sits on.
  */
class SupportAssessmentSuite extends FunSuite:
  import AnnaFixture.{candidates, costModel, recall, view}

  private def weights(ws: Double*): CostWeights =
    CostWeights
      .of(ws(0), ws(1), ws(2), ws(3), ws(4), ws(5), ws(6), ws(7))
      .fold(e => fail(e.message), identity)

  private def assessed(support: SupportAssessment): SupportAssessment.Assessed = support match
    case value: SupportAssessment.Assessed => value
    case other                             => fail(s"expected Assessed, got $other")

  private def unestablished(support: SupportAssessment): SupportAssessment.Unestablished =
    support match
      case value: SupportAssessment.Unestablished => value
      case other                                  => fail(s"expected Unestablished, got $other")

  private def evidence(
      measured: Set[CostTerm],
      eligible: Map[CostTerm, Double]
  ): SupportAssessment =
    SupportAssessment
      .fromEvidence(measured, eligible.keySet, eligible)
      .fold(e => fail(e.message), identity)

  private lazy val result: HsmmResult =
    GraphHsmm.infer(recall, view, candidates, costModel).fold(e => fail(e.message), identity)

  private def revalidate(
      r: HsmmResult,
      costs: Map[RecallUnitId, Map[AlignState, CostBreakdown]]
  ): Either[AlignError, HsmmResult] =
    HsmmResult.validated(
      recall,
      view,
      r.candidateAnchors,
      r.posterior,
      r.flow,
      r.viterbi,
      r.logLikelihood,
      costs,
      r.refinementPasses
    )

  // ---- derivation ------------------------------------------------------------------------------

  test("fully and partially measured populations derive the analytic shares") {
    val eligible = Map(CostTerm.Semantic -> 1.0, CostTerm.Propositional -> 3.0)
    val full = assessed(evidence(eligible.keySet, eligible))
    assertEquals(full.share, 1.0)
    assertEquals(full.measuredTerms.toSet, eligible.keySet)
    assertEquals(full.eligibleTerms.toSortedSet.toSet, eligible.keySet)
    assertEquals(full.eligibleWeights.toMap, eligible)
    assertEquals(assessed(evidence(Set(CostTerm.Semantic), eligible)).share, 0.25)
    assertEquals(assessed(evidence(Set(CostTerm.Propositional), eligible)).share, 0.75)
    assertEquals(assessed(evidence(Set.empty, eligible)).share, 0.0)
  }

  test("an empty eligible population is Unestablished, never fully supported") {
    // The first of the two sites that returned 1.0. MUTATION: deriving Assessed(1.0) for an empty
    // population (under any fabricated eligible set) fails here.
    val empty = unestablished(evidence(Set.empty, Map.empty))
    assertEquals(empty.reason, SupportUnestablishedReason.EmptyEligibility)
    assert(empty.basis.eligibleTerms.isEmpty && empty.basis.measuredTerms.isEmpty)
    // The producer's basis for an empty DECLARED population must not assess either - not even
    // when it measured something. Widening eligibility to the measured terms would let an empty
    // declaration publish 1.0 (and hide a producer pricing a term it calls ineligible).
    Vector(Set.empty[CostTerm], Set(CostTerm.Semantic, CostTerm.Entity)).foreach { measured =>
      val producer = SupportAssessment.derive(
        CellSupportBasis.fromWeights(measured, Set.empty, CostWeights.default)
      )
      assertEquals(
        unestablished(producer).reason,
        SupportUnestablishedReason.EmptyEligibility,
        s"the producer's basis for an empty declared population assessed (measured $measured)"
      )
    }
  }

  test("a producer that prices a term it did not declare eligible fails the checked rebuild") {
    // The present-but-not-eligible defect (once: Chart on segment cells) must stay VISIBLE. The
    // basis counts only declared-eligible measurements, so the record's own support disagrees with
    // its priced terms and the checked factory - the rebuild law every real result passes - refuses.
    val b = CostBreakdown.derived(
      Map(CostTerm.Semantic -> 0.2, CostTerm.Sensory -> 0.3),
      FidelityMode.Faithful,
      Set.empty,
      None,
      Map.empty,
      Map.empty,
      Set(CostTerm.Semantic),
      CostWeights.default,
      1.0
    )
    assertEquals(assessed(b.support).measuredTerms.toSet, Set(CostTerm.Semantic))
    refusedRecord(
      "a record priced over an undeclared term",
      AlignWire.costBreakdown(
        b.terms,
        b.mode,
        b.exclusion,
        b.total,
        b.missingTerms,
        b.sourceChartCoverage,
        b.reductions,
        b.support,
        b.imputedTerms
      )
    )
  }

  test("zero eligible weight is Unestablished, never fully supported") {
    // The second site. `CostWeights.of` admits the all-zero vector, so this is lawful input.
    // MUTATION: restoring `if !(wEligible > 0.0) then 1.0` fails here.
    val eligible = Map(CostTerm.Semantic -> 0.0, CostTerm.Propositional -> 0.0)
    val zero = unestablished(evidence(eligible.keySet, eligible))
    assertEquals(zero.reason, SupportUnestablishedReason.ZeroEligibleWeight)
    assertEquals(zero.basis.eligibleWeights.toMap, eligible)
  }

  test("LAW 4: all-zero weights publish no share on any cell and keep today's totals") {
    // The zero-weight boundary as the aligner reaches it. Every ranked source cell's eligible weight
    // is zero, so none may carry a number - before hsmm/v4 every one of them published 1.0. The
    // PRICE is deliberately unchanged: the blend is 0 and the scale leaves it at 0, so each total is
    // exactly the unit's function prior, as it was. Whether such a cell should be priced at all is
    // an estimand question this representation change does not decide.
    val zero = weights(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val model = costModel.copy(weights = zero)
    val r = GraphHsmm
      .infer(recall, view, candidates, model)
      .fold(e => fail(s"lawful all-zero weights must still infer: ${e.message}"), identity)
    val sources = r.costs.toVector.flatMap((u, m) =>
      m.toVector.collect {
        case (s, b) if !s.isExternal => (u, b)
      }
    )
    assert(sources.nonEmpty, "fixture: no source cell was priced")
    sources.foreach { (u, b) =>
      assert(!b.excluded, s"a zero-weight cell was excluded, which moves its price: $b")
      assertEquals(unestablished(b.support).reason, SupportUnestablishedReason.ZeroEligibleWeight)
      val function = recall.byId(u).function
      assertEquals(b.total, FunctionPrior.default(function), s"unit ${u.value} moved its price")
    }
  }

  test("a basis whose every eligible term was measured derives exactly 1.0 for any weights") {
    // The all-measured-but-0.5 attack, at the source: no weights can make a complete measurement
    // derive anything but exactly one, because the numerator and denominator are the same sum.
    val vectors = Vector(
      Vector(1.0, 0.5, 0.4, 0.15, 0.3, 1.0, 0.5, 0.5),
      Vector(java.lang.Double.MIN_VALUE, 1e-300, 0.0, 3.0, 0.1, 0.2, 0.7, 1e300),
      Vector(Double.MaxValue / 8, Double.MaxValue / 8, 0.0, 0.0, 0.0, 0.0, 1.0, 0.1)
    ) ++ (1 to 32).map { seed =>
      val rng = new scala.util.Random(seed)
      Vector.fill(8)(if rng.nextInt(4) == 0 then 0.0 else rng.nextDouble() * 10)
    }
    vectors.foreach { ws =>
      val w = weights(ws*)
      val eligible = CostTerm.values.toSet.filter(t => w(t) > 0.0)
      if eligible.nonEmpty then
        val support = SupportAssessment.derive(CellSupportBasis.fromWeights(eligible, eligible, w))
        assertEquals(assessed(support).share, 1.0, s"weights $ws")
    }
  }

  test("finite extreme weights cannot overflow the share") {
    val w = weights(Double.MaxValue / 2, Double.MaxValue / 2, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val support = SupportAssessment.derive(
      CellSupportBasis.fromWeights(
        Set(CostTerm.Semantic),
        Set(CostTerm.Semantic, CostTerm.Propositional),
        w
      )
    )
    assertEquals(assessed(support).share, 0.5)
  }

  test("wire evidence is checked before any share is derived") {
    val eligible = Map(CostTerm.Semantic -> 1.0, CostTerm.Propositional -> 3.0)
    def refused(
        label: String,
        measured: Set[CostTerm],
        terms: Set[CostTerm],
        ws: Map[CostTerm, Double]
    ): Unit =
      SupportAssessment.fromEvidence(measured, terms, ws) match
        case Left(AlignError.MalformedRecord("CellSupportBasis", _)) => ()
        case other => fail(s"$label was accepted: $other")
    refused("a measured term outside eligibility", Set(CostTerm.Entity), eligible.keySet, eligible)
    refused(
      "a missing eligible weight",
      Set(CostTerm.Semantic),
      eligible.keySet,
      eligible - CostTerm.Propositional
    )
    refused(
      "an extra weight",
      Set(CostTerm.Semantic),
      eligible.keySet,
      eligible + (CostTerm.Entity -> 1.0)
    )
    Vector(Double.NaN, Double.PositiveInfinity, -0.25).foreach { bad =>
      refused(s"weight $bad", Set.empty, eligible.keySet, eligible.updated(CostTerm.Semantic, bad))
    }
    val huge = Map(CostTerm.Semantic -> Double.MaxValue, CostTerm.Propositional -> Double.MaxValue)
    refused("a non-representable eligible sum", Set(CostTerm.Semantic), huge.keySet, huge)
  }

  // ---- which state each record gets ------------------------------------------------------------

  test(
    "external cells are NotApplicable(ExternalState); unreachable is NotApplicable(Unreachable)"
  ) {
    // MUTATION: giving external records the Unreachable reason fails here.
    val external = result.costs.values.flatMap(_.toVector).collect {
      case (s, b) if s.isExternal => b.support
    }
    assert(external.nonEmpty)
    external.foreach {
      case value: SupportAssessment.NotApplicable =>
        assertEquals(value.reason, SupportNotApplicableReason.ExternalState)
      case other => fail(s"an external cell published $other")
    }
    CostBreakdown.unreachable.support match
      case value: SupportAssessment.NotApplicable =>
        assertEquals(value.reason, SupportNotApplicableReason.Unreachable)
      case other => fail(s"the unreachable record published $other")
  }

  test("every ranked source cell of a real result carries its own derived, assessed basis") {
    val ranked = result.costs.values.flatMap(_.values).filter(b => b.mode.nonEmpty)
    assert(ranked.nonEmpty)
    ranked.foreach { b =>
      val a = assessed(b.support)
      assertEquals(a.measuredTerms.toSet, b.terms.keySet -- b.imputedTerms.keySet)
      assert(b.terms.keySet.subsetOf(a.eligibleTerms.toSortedSet.toSet))
      assertEquals(
        a.eligibleWeights.toMap,
        a.eligibleTerms.toSortedSet.toVector.map(t => t -> costModel.weights(t)).toMap
      )
    }
    // Control: the fixture has fully measured cells, and they derive exactly one.
    assert(
      ranked.exists(b => assessed(b.support).share == 1.0),
      "fixture: no fully measured cell, so the exact-1.0 path is untested here"
    )
    assert(ranked.exists(b => assessed(b.support).share < 1.0))
  }

  // ---- support must describe its record --------------------------------------------------------

  /** A faithful ranked cell that assumed something: an eligible term went unmeasured. */
  private lazy val source: CostBreakdown =
    result.costs.values
      .flatMap(_.values)
      .find(b => b.mode.exists(_.isFaithful) && assessed(b.support).share < 1.0)
      .getOrElse(fail("fixture: no partially measured faithful cell"))
  private lazy val external: CostBreakdown =
    result.costs.values.flatMap(_.toVector).collectFirst { case (s, b) if s.isExternal => b }.get

  private def rebuilt(
      b: CostBreakdown,
      support: SupportAssessment,
      terms: Map[CostTerm, Double] = Map.empty,
      exclusion: Option[Exclusion] = None,
      useTerms: Boolean = false
  ): Either[AlignError, CostBreakdown] =
    AlignWire.costBreakdown(
      if useTerms then terms else b.terms,
      b.mode,
      exclusion.orElse(b.exclusion),
      b.total,
      b.missingTerms,
      b.sourceChartCoverage,
      b.reductions,
      support,
      b.imputedTerms
    )

  private def refusedRecord(label: String, e: Either[AlignError, CostBreakdown]): Unit = e match
    case Left(AlignError.MalformedRecord("CostBreakdown", _)) => ()
    case other => fail(s"$label was accepted: $other")

  test("an external record cannot carry measured support, nor the unreachable reason") {
    refusedRecord("external + Assessed", rebuilt(external, source.support))
    refusedRecord(
      "external + Unestablished",
      rebuilt(external, evidence(Set.empty, Map(CostTerm.Semantic -> 0.0)))
    )
    refusedRecord(
      "external + NotApplicable(Unreachable)",
      rebuilt(external, SupportAssessment.unreachable)
    )
    assertEquals(rebuilt(external, SupportAssessment.externalState), Right(external), "control")
  }

  test("an unreachable record carries no measurement") {
    val u = CostBreakdown.unreachable
    refusedRecord("unreachable + Assessed", rebuilt(u, source.support))
    refusedRecord(
      "unreachable + Unestablished",
      rebuilt(u, evidence(Set.empty, Map(CostTerm.Semantic -> 0.0)))
    )
    refusedRecord("unreachable + ExternalState", rebuilt(u, SupportAssessment.externalState))
    assertEquals(rebuilt(u, SupportAssessment.unreachable), Right(u), "control")
  }

  test("a ranked source record's support must be its own") {
    refusedRecord("source + ExternalState", rebuilt(source, SupportAssessment.externalState))
    refusedRecord("source + Unreachable", rebuilt(source, SupportAssessment.unreachable))
    val basis = assessed(source.support).basis
    val weightsOf = basis.eligibleWeights.toMap
    // Measured population that is not the priced-minus-imputed terms: claim one priced term was not
    // measured (a lower share), and claim a term was measured that the record never priced.
    val dropped = basis.measuredTerms.head
    refusedRecord(
      "a priced term missing from the measured population",
      rebuilt(source, evidence(basis.measuredTerms.toSet - dropped, weightsOf))
    )
    val unpriced = basis.eligibleTerms.toSet
      .diff(source.terms.keySet)
      .headOption
      .getOrElse(
        fail("fixture: the source cell has no eligible-but-unpriced term")
      )
    refusedRecord(
      "an unpriced term in the measured population",
      rebuilt(source, evidence(basis.measuredTerms.toSet + unpriced, weightsOf))
    )
    assertEquals(rebuilt(source, evidence(basis.measuredTerms.toSet, weightsOf)), Right(source))
  }

  test("every priced term must be eligible, including an imputed one") {
    // The eligibility half of the binding can only fire when the measured population agrees:
    // impute Semantic (it leaves the measured set) and drop it from eligibility, so measured =
    // priced - imputed still holds and only "priced but not eligible" is wrong.
    val basis = assessed(source.support).basis
    val weightsOf = basis.eligibleWeights.toMap
    val imputed = Map(CostTerm.Semantic -> MissingReason.ProviderAbstained)
    def record(eligible: Map[CostTerm, Double]) = AlignWire.costBreakdown(
      source.terms,
      source.mode,
      None,
      source.total,
      source.missingTerms,
      source.sourceChartCoverage,
      source.reductions,
      evidence(basis.measuredTerms.toSet - CostTerm.Semantic, eligible),
      imputed
    )
    record(weightsOf - CostTerm.Semantic) match
      case Left(AlignError.MalformedRecord("CostBreakdown", detail)) =>
        assert(detail.contains("eligible"), detail)
      case other => fail(s"an imputed term outside eligibility was accepted: $other")
    assert(record(weightsOf).isRight, "control: the same imputation over its eligible basis")
  }

  test("a ranked record with zero assessed support is refused; excluded, it is lawful") {
    // Zero assessed support is an exclusion, not a price: ranked, a cell that measured no weighted
    // term would be priced at its function prior, below the external floor, and win.
    val zero = evidence(Set.empty, Map(CostTerm.Semantic -> 1.0))
    val imputed = Map(CostTerm.Semantic -> MissingReason.ProviderAbstained)
    AlignWire.costBreakdown(
      Map(CostTerm.Semantic -> 0.5),
      Some(FidelityMode.Faithful),
      None,
      0.5,
      Set.empty,
      None,
      Map.empty,
      zero,
      imputed
    ) match
      case Left(AlignError.MalformedRecord("CostBreakdown", detail)) =>
        assert(detail.contains("Unassessable"), detail)
      case other => fail(s"a ranked record with zero support was accepted: $other")
    // Control: the same record with one weighted measurement is a lawful ranked record.
    assert(
      AlignWire
        .costBreakdown(
          Map(CostTerm.Semantic -> 0.5),
          Some(FidelityMode.Faithful),
          None,
          0.5,
          Set.empty,
          None,
          Map.empty,
          evidence(Set(CostTerm.Semantic), Map(CostTerm.Semantic -> 1.0)),
          Map.empty[CostTerm, MissingReason]
        )
        .isRight
    )
  }

  test("an unassessable exclusion must rest on exactly zero assessed support") {
    val zero = evidence(Set.empty, Map(CostTerm.Semantic -> 1.0))
    val unassessable = AlignWire.costBreakdown(
      Map.empty,
      None,
      Some(Exclusion.Unassessable),
      Double.MaxValue / 4,
      Set.empty,
      None,
      Map.empty,
      zero,
      Map.empty[CostTerm, MissingReason]
    )
    assert(unassessable.isRight, s"control: zero support is a lawful exclusion: $unassessable")
    val noDenominator = AlignWire.costBreakdown(
      Map.empty,
      None,
      Some(Exclusion.Unassessable),
      Double.MaxValue / 4,
      Set.empty,
      None,
      Map.empty,
      evidence(Set.empty, Map(CostTerm.Semantic -> 0.0)),
      Map.empty[CostTerm, MissingReason]
    )
    assert(noDenominator.isRight, s"control: no denominator is a lawful exclusion: $noDenominator")
    Vector(SupportAssessment.externalState, SupportAssessment.unreachable).foreach { inapplicable =>
      refusedRecord(
        s"an unassessable record with $inapplicable",
        AlignWire.costBreakdown(
          Map.empty,
          None,
          Some(Exclusion.Unassessable),
          Double.MaxValue / 4,
          Set.empty,
          None,
          Map.empty,
          inapplicable,
          Map.empty[CostTerm, MissingReason]
        )
      )
    }
    refusedRecord(
      "an unassessable record with a positive share",
      AlignWire.costBreakdown(
        Map.empty,
        None,
        Some(Exclusion.Unassessable),
        Double.MaxValue / 4,
        Set.empty,
        None,
        Map.empty,
        evidence(Set(CostTerm.Semantic), Map(CostTerm.Semantic -> 1.0)),
        Map.empty[CostTerm, MissingReason]
      )
    )
  }

  test("an external key cannot carry a dropped record with measured support") {
    // An excluded record passes the mode check under any key it was dropped from, so without the
    // external-support check a dropped external state could carry an assessed zero. The key must be
    // unreadable to reach that check: a unit with no ranked candidate has only Unranked as a state,
    // so its Intrusion key is dropped.
    val u0 = recall.ordered.head.id
    val abstaining = Candidates(candidates.byUnit.updated(u0, CandidateSet.unranked))
    val r =
      GraphHsmm.infer(recall, view, abstaining, costModel).fold(e => fail(e.message), identity)
    val intrusion = AlignState.External(ExternalState.Intrusion)
    assert(!r.posterior.rows.head.mass.contains(intrusion), "fixture: Intrusion must be unreadable")
    val dropped = AlignWire
      .costBreakdown(
        Map.empty,
        None,
        Some(Exclusion.Unassessable),
        Double.MaxValue / 4,
        Set.empty,
        None,
        Map.empty,
        evidence(Set.empty, Map(CostTerm.Semantic -> 1.0)),
        Map.empty[CostTerm, MissingReason]
      )
      .fold(e => fail(e.message), identity)
    val forged = r.costs.updated(u0, r.costs(u0).updated(intrusion, dropped))
    revalidate(r, forged) match
      case Left(AlignError.MalformedRecord("CostBreakdown", detail)) =>
        assert(detail.contains("NotApplicable(ExternalState)"), detail)
      case other => fail(s"a dropped external key carried measured support: $other")
    assertEquals(revalidate(r, r.costs), Right(r), "control")
  }

  test("an anchored key is never Unreachable: nomination proved its anchor is in the view") {
    // Reach a DROPPED anchored key (readable keys refuse any excluded record first): with only
    // Sensory weighted and no sensory terms, every source cell is Unassessable and dropped.
    val w = weights(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.5, 0.5)
    val stripped = storymodel4s.recall.RecallGraph
      .validated(recall.copy(units = recall.units.map { u =>
        u.copy(proposition = u.proposition.copy(sensoryTerms = Vector.empty))
      }))
      .fold(e => fail(e.toString), identity)
    val r = GraphHsmm
      .infer(stripped, view, candidates, costModel.copy(weights = w))
      .fold(e => fail(e.message), identity)
    val (unit, key) = r.costs.toVector
      .flatMap((u, m) =>
        m.toVector.collect { case (s, b) if !s.isExternal && b.excluded => (u, s) }
      )
      .headOption
      .getOrElse(fail("fixture: no dropped source key"))
    val forged = r.costs.updated(unit, r.costs(unit).updated(key, CostBreakdown.unreachable))
    HsmmResult.validated(
      stripped,
      view,
      r.candidateAnchors,
      r.posterior,
      r.flow,
      r.viterbi,
      r.logLikelihood,
      forged,
      r.refinementPasses
    ) match
      case Left(AlignError.MalformedRecord("CostBreakdown", detail)) =>
        assert(detail.contains("not Unreachable"), detail)
      case other => fail(s"an anchored key claimed Unreachable: $other")
    assertEquals(
      HsmmResult.validated(
        stripped,
        view,
        r.candidateAnchors,
        r.posterior,
        r.flow,
        r.viterbi,
        r.logLikelihood,
        r.costs,
        r.refinementPasses
      ),
      Right(r),
      "control"
    )
  }
