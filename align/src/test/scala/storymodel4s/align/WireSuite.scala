package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.{SpanRef, SpanSet, TextSpan}
import storymodel4s.features.{Coverage, Estimate, MissingReason}
import storymodel4s.recall.RecallUnitId

/** The wire side of a gated result (bead `HsmmResult wire`): validating factories rebuild the
  * records a result carries and refuse malformed or inconsistent ones; fingerprints are content
  * addresses independent of iteration order; a real result round-trips through parts.
  */
class WireSuite extends FunSuite:
  import AnnaFixture.{view, e1, e2, e5, sc1}

  private lazy val result: HsmmResult =
    GraphHsmm
      .infer(AnnaFixture.recall, view, AnnaFixture.candidates, AnnaFixture.costModel)
      .fold(e => fail(e.message), identity)

  private def rebuild(b: CostBreakdown): Either[AlignError, CostBreakdown] =
    AlignWire.costBreakdown(
      b.terms,
      b.mode,
      b.exclusion,
      b.total,
      b.missingTerms,
      b.sourceChartCoverage,
      b.reductions
    )

  private def malformed(e: Either[AlignError, ?]): Boolean = e match
    case Left(AlignError.MalformedRecord(_, _)) => true
    case _                                      => false

  // ---- CostBreakdown ---------------------------------------------------------------------------

  test("every cost breakdown of a real result rebuilds to itself through the factory") {
    val all = result.costs.values.flatMap(_.values)
    assert(all.nonEmpty)
    all.foreach(b => assertEquals(rebuild(b), Right(b)))
    assertEquals(rebuild(CostBreakdown.unreachable), Right(CostBreakdown.unreachable))
  }

  test("the factory refuses non-finite and negative values") {
    val b = result.costs.values.flatMap(_.values).find(_.mode.exists(_.isFaithful)).get
    assert(malformed(rebuild(b.copy(terms = b.terms.updated(CostTerm.Semantic, Double.NaN)))))
    assert(malformed(rebuild(b.copy(terms = b.terms.updated(CostTerm.Entity, -0.1)))))
    assert(malformed(rebuild(b.copy(total = Double.PositiveInfinity))))
    assert(malformed(rebuild(b.copy(total = -1.0))))
  }

  test("the factory refuses term–mode and term–missing inconsistencies") {
    val b = result.costs.values.flatMap(_.values).find(_.mode.exists(_.isFaithful)).get
    // a faithful state with a distortion penalty
    assert(malformed(rebuild(b.copy(terms = b.terms.updated(CostTerm.Distortion, 0.3)))))
    // a mandatory term declared missing, or a term both present and missing
    assert(malformed(rebuild(b.copy(missingTerms = Set(CostTerm.Semantic)))))
    assert(
      malformed(
        rebuild(
          b.copy(terms = b.terms.updated(CostTerm.Chart, 0.1), missingTerms = Set(CostTerm.Chart))
        )
      )
    )
    // a receipt on a term that has no reduction
    assert(
      malformed(rebuild(b.copy(reductions = b.reductions.map((_, r) => CostTerm.Semantic -> r))))
    )
    // an excluded state with content, an external state with content
    assert(
      malformed(rebuild(CostBreakdown.unreachable.copy(terms = Map(CostTerm.Semantic -> 0.1))))
    )
    assert(malformed(rebuild(CostBreakdown.unreachable.copy(mode = Some(FidelityMode.Faithful)))))
    val external = result.costs.values.flatMap(_.values).find(_.mode.isEmpty).get
    assert(malformed(rebuild(external.copy(terms = Map(CostTerm.Semantic -> 0.1)))))
    // malformed coverage
    assert(malformed(rebuild(b.copy(sourceChartCoverage = Some(StructuralCoverage(0, 2, 1))))))
    assert(malformed(rebuild(b.copy(sourceChartCoverage = Some(StructuralCoverage(-1, 0, 1))))))
  }

  // ---- structural reductions -------------------------------------------------------------------

  test("every reduction receipt of a real result rebuilds to itself") {
    val receipts = result.costs.values.flatMap(_.values).flatMap(_.reductions.values)
    assert(receipts.nonEmpty)
    receipts.foreach { r =>
      assertEquals(
        AlignWire.reductionReceipt(
          r.reducer,
          r.members,
          r.excludedMembers,
          r.sourceChartCoverage,
          r.observedEstimateCoverage
        ),
        Right(r)
      )
    }
    // the fixture has no charts: every real reduction is Missing over no members
    val unit = AnnaFixture.recall.ordered.head
    val red = ChartDistance.reduction(unit, view.node(sc1).get, view)
    assert(!red.estimate.isObserved)
    assertEquals(AlignWire.structuralReduction(red.estimate, red.receipt), Right(red))
  }

  private def member(ref: SourceNodeRef, e: Estimate[Double]): StructuralMemberEstimate =
    AlignWire.memberEstimate(ref, e).fold(err => fail(err.message), identity)

  private def receipt(
      members: Vector[StructuralMemberEstimate],
      excluded: Vector[StructuralMemberExclusion] = Vector.empty,
      coverage: Option[Coverage] = None
  ): Either[AlignError, StructuralReductionReceipt] =
    AlignWire.reductionReceipt(
      StructuralReducer.Minimum,
      members,
      excluded,
      StructuralCoverage(1, members.size + excluded.size, members.size + excluded.size),
      coverage.getOrElse(Coverage.unsafe(members.size, members.count(_.estimate.isObserved)))
    )

  test("a reduced scalar must be what the reducer produces over the observed members") {
    val members = Vector(
      member(e1, Estimate.observed(0.4)),
      member(e2, Estimate.observed(0.2)),
      member(e5, Estimate.missing(MissingReason.ProviderAbstained))
    )
    val r = receipt(members).fold(e => fail(e.message), identity)
    assertEquals(r.observedEstimateCoverage, Coverage.unsafe(3, 2))
    assert(AlignWire.structuralReduction(Estimate.observed(0.2), r).isRight)
    assert(malformed(AlignWire.structuralReduction(Estimate.observed(0.4), r)), "not the minimum")
    assert(malformed(AlignWire.structuralReduction(Estimate.observed(0.1), r)), "flattering")
    assert(malformed(AlignWire.structuralReduction(Estimate.observed(Double.NaN), r)))
    assert(AlignWire.structuralReduction(Estimate.missing(MissingReason.Excluded), r).isRight)
    // observed with nothing observed
    val none = receipt(Vector(member(e1, Estimate.missing(MissingReason.ProviderAbstained))))
      .fold(e => fail(e.message), identity)
    assert(malformed(AlignWire.structuralReduction(Estimate.observed(0.0), none)))
  }

  test("members and exclusions must be canonical, disjoint, and counted by the coverage") {
    val a = member(e1, Estimate.observed(0.4))
    val b = member(e2, Estimate.observed(0.2))
    assert(receipt(Vector(a, b)).isRight)
    assert(malformed(receipt(Vector(b, a))), "unsorted")
    assert(malformed(receipt(Vector(a, a))), "duplicate")
    assert(malformed(receipt(Vector(a, b), coverage = Some(Coverage.unsafe(2, 1)))), "coverage")
    val ex = AlignWire
      .memberExclusion(e1, Set(Contradiction.RoleReversal))
      .fold(e => fail(e.message), identity)
    assert(malformed(receipt(Vector(a, b), Vector(ex))), "overlap")
    assert(receipt(Vector(b), Vector(ex)).isRight)
    assert(malformed(AlignWire.memberExclusion(e1, Set.empty)))
    assert(malformed(AlignWire.memberEstimate(e1, Estimate.observed(Double.PositiveInfinity))))
    assert(
      malformed(
        AlignWire.reductionReceipt(
          StructuralReducer.Minimum,
          Vector(a),
          Vector.empty,
          StructuralCoverage(1, 3, 2),
          Coverage.unsafe(1, 1)
        )
      )
    )
  }

  // ---- fingerprints ----------------------------------------------------------------------------

  test("the view fingerprint is independent of node and edge iteration order") {
    val shuffled = InMemorySourceView(
      view.nodes.reverse,
      view.edges.map((l, es) => l -> es.reverse),
      view.worldOrder.map(_.toVector.reverse.toMap),
      view.textLength
    )
    assertEquals(shuffled.contentFingerprint, view.contentFingerprint)
    // a zero-weight edge is the same as no edge (absent pairs are 0 by contract)
    val zero = InMemorySourceView(
      view.nodes,
      view.edges.updated(
        RelationLayer.Semantic,
        view.edges.getOrElse(RelationLayer.Semantic, Vector.empty) :+ (e1, e5, 0.0)
      ),
      view.worldOrder,
      view.textLength
    )
    assertEquals(zero.contentFingerprint, view.contentFingerprint)
  }

  test("the view fingerprint changes under any node-summary field, edge, or order change") {
    def withNode(f: NodeSummary => NodeSummary): ViewFingerprint =
      InMemorySourceView(
        view.nodes.map(n => if n.ref == e1 then f(n) else n),
        view.edges,
        view.worldOrder,
        view.textLength
      ).contentFingerprint
    val base = view.contentFingerprint
    val variants = Vector(
      "level" -> withNode(_.copy(level = 3)),
      "parent" -> withNode(_.copy(parent = None)),
      "position" -> withNode(_.copy(discoursePosition = 9)),
      "support" -> withNode(_.copy(support = SpanSet.one(SpanRef(TextSpan.unsafe(0, 1))))),
      "predicate" -> withNode(_.copy(predicate = Some("leave"))),
      "participants" -> withNode(_.copy(participants = Vector.empty)),
      "context" -> withNode(_.copy(context = ContextTag.Speech)),
      "polarity" -> withNode(_.copy(polarity = storymodel4s.recall.PolarityTag.Negative)),
      "modality" -> withNode(_.copy(modality = storymodel4s.recall.ModalityTag.Intended)),
      "locations" -> withNode(_.copy(locations = Vector.empty)),
      "lemmas" -> withNode(_.copy(lemmas = Set("x"))),
      "outcome" -> withNode(_.copy(outcome = Some("o"))),
      "cause" -> withNode(_.copy(cause = Some("c"))),
      "importance" -> withNode(_.copy(importance = Estimate.observed(0.5))),
      "edge" -> InMemorySourceView(
        view.nodes,
        view.edges.updated(RelationLayer.Causal, Vector((e1, e5, 1.0))),
        view.worldOrder,
        view.textLength
      ).contentFingerprint,
      "weight" -> InMemorySourceView(
        view.nodes,
        view.edges.updated(RelationLayer.Semantic, Vector((e1, e5, 0.25))),
        view.worldOrder,
        view.textLength
      ).contentFingerprint,
      "worldOrder" -> InMemorySourceView(
        view.nodes,
        view.edges,
        None,
        view.textLength
      ).contentFingerprint,
      "textLength" -> InMemorySourceView(
        view.nodes,
        view.edges,
        view.worldOrder,
        view.textLength + 1
      ).contentFingerprint
    )
    variants.foreach { (name, fp) => assertNotEquals(fp, base, s"$name change not fingerprinted") }
    assertEquals(variants.map(_._2).distinct.size, variants.size, "variants collide")
  }

  test("the recall checksum names the transcript and the units' ids, order, and spans") {
    val recall = AnnaFixture.recall
    val base = AlignWire.recallChecksum(recall)
    assertEquals(AlignWire.recallChecksum(recall.copy(units = recall.units.reverse)), base)
    val u0 = recall.ordered.head
    val shifted = u0.copy(span = SpanSet.one(SpanRef(TextSpan.unsafe(0, 1))))
    val moved = recall.copy(units = recall.units.map(u => if u.id == u0.id then shifted else u))
    assertNotEquals(AlignWire.recallChecksum(moved), base)
    val renamed = u0.copy(id = RecallUnitId.unsafe("renamed"))
    val withRenamed =
      recall.copy(units = recall.units.map(u => if u.id == u0.id then renamed else u))
    assertNotEquals(AlignWire.recallChecksum(withRenamed), base)
    val u1 = recall.ordered(1)
    val swapped = recall.copy(units =
      recall.units.map(u =>
        if u.id == u0.id then u.copy(ordinal = u1.ordinal)
        else if u.id == u1.id then u.copy(ordinal = u0.ordinal)
        else u
      )
    )
    assertNotEquals(AlignWire.recallChecksum(swapped), base)
  }

  test("the recall checksum binds the full unit content, not only its boundaries") {
    val recall = AnnaFixture.recall
    val base = AlignWire.recallChecksum(recall)
    val u0 = recall.ordered.head
    def swap(u: storymodel4s.recall.RecallUnit) =
      AlignWire.recallChecksum(
        recall.copy(units = recall.units.map(x => if x.id == u0.id then u else x))
      )
    val variants = Vector(
      "text" -> swap(u0.copy(text = u0.text + " indeed")),
      "function" -> swap(u0.copy(function = storymodel4s.recall.DiscourseFunction.Association)),
      "uncertainty" -> swap(
        u0.copy(expressedUncertainty = storymodel4s.recall.ExpressedUncertainty.Hedged(u0.span))
      ),
      "predicate" -> swap(u0.copy(proposition = u0.proposition.copy(predicate = Some("zzz")))),
      "participants" -> swap(
        u0.copy(proposition = u0.proposition.copy(participants = Vector.empty))
      ),
      "polarity" -> swap(
        u0.copy(proposition =
          u0.proposition.copy(polarity = storymodel4s.recall.PolarityTag.Negative)
        )
      ),
      "modality" -> swap(
        u0.copy(proposition =
          u0.proposition.copy(modality = storymodel4s.recall.ModalityTag.Reported)
        )
      ),
      "locations" -> swap(u0.copy(proposition = u0.proposition.copy(locations = Vector("zzz")))),
      "times" -> swap(u0.copy(proposition = u0.proposition.copy(times = Vector("zzz")))),
      "sensory" -> swap(u0.copy(proposition = u0.proposition.copy(sensoryTerms = Vector("zzz")))),
      "lemmas" -> swap(
        u0.copy(proposition = u0.proposition.copy(lemmas = u0.proposition.lemmas + "zzz"))
      ),
      "outcome" -> swap(u0.copy(proposition = u0.proposition.copy(outcome = Some("zzz")))),
      "cause" -> swap(u0.copy(proposition = u0.proposition.copy(cause = Some("zzz")))),
      "grounding" -> swap(u0.copy(grounding = Some(storymodel4s.core.Probability.unsafe(0.5)))),
      "causal relation" -> AlignWire.recallChecksum(
        recall.copy(relations =
          recall.relations.copy(causal =
            Vector(
              storymodel4s.recall.RecallCausalEdge(u0.id, recall.ordered(1).id, None)
            )
          )
        )
      )
    )
    variants.foreach { (name, c) => assertNotEquals(c, base, s"$name change not in the checksum") }
    assertEquals(variants.map(_._2).distinct.size, variants.size, "variants collide")
  }

  test("a receipt-inconsistent optional term is rejected by the factory") {
    val b = result.costs.values.flatMap(_.values).find(_.mode.exists(_.isFaithful)).get
    // the fixture's receipts reduce to nothing: a present Chart term contradicts its receipt
    assert(b.missingTerms.contains(CostTerm.Chart) && b.reductions.contains(CostTerm.Chart))
    assert(
      malformed(
        rebuild(
          b.copy(
            terms = b.terms.updated(CostTerm.Chart, 0.2),
            missingTerms = b.missingTerms - CostTerm.Chart
          )
        )
      ),
      "present term over an empty reduction"
    )
    // a term that reduces to nothing must be recorded missing
    assert(
      malformed(rebuild(b.copy(missingTerms = b.missingTerms - CostTerm.Chart))),
      "unrecorded missing term"
    )
    // a present optional term without any receipt
    assert(
      malformed(
        rebuild(
          b.copy(
            terms = b.terms.updated(CostTerm.Chart, 0.2),
            missingTerms = b.missingTerms - CostTerm.Chart,
            reductions = b.reductions - CostTerm.Chart
          )
        )
      ),
      "present term without a receipt"
    )
    // a receipt with observed members: the term must be the (clamped) reducer value
    val members = Vector(member(e1, Estimate.observed(0.4)), member(e2, Estimate.observed(0.2)))
    val cov = b.sourceChartCoverage.get
    val rc = AlignWire
      .reductionReceipt(
        StructuralReducer.Minimum,
        members,
        Vector.empty,
        cov,
        Coverage.unsafe(2, 2)
      )
      .fold(e => fail(e.message), identity)
    def withChart(v: Double, receipt: StructuralReductionReceipt) =
      rebuild(
        b.copy(
          terms = b.terms.updated(CostTerm.Chart, v),
          missingTerms = b.missingTerms - CostTerm.Chart,
          reductions = b.reductions.updated(CostTerm.Chart, receipt)
        )
      )
    assert(withChart(0.2, rc).isRight)
    assert(malformed(withChart(0.4, rc)), "not the reducer value")
    assert(malformed(withChart(0.1, rc)), "flattering term")
    assert(
      malformed(rebuild(b.copy(reductions = b.reductions.updated(CostTerm.Chart, rc)))),
      "receipt reduces to a value but the term is recorded missing"
    )
    // the receipt's source-chart coverage must be the breakdown's
    val other = StructuralCoverage(cov.level, cov.membersWithEvidence, cov.members + 1)
    val rcOther = AlignWire
      .reductionReceipt(
        StructuralReducer.Minimum,
        members,
        Vector.empty,
        other,
        Coverage.unsafe(2, 2)
      )
      .fold(e => fail(e.message), identity)
    assert(malformed(withChart(0.2, rcOther)), "receipt coverage differs from the breakdown's")
  }

  test("importance is fingerprinted as the full estimate variant, credence included") {
    def withImportance(e: Estimate[Double]): ViewFingerprint =
      InMemorySourceView(
        view.nodes.map(n => if n.ref == e1 then n.copy(importance = e) else n),
        view.edges,
        view.worldOrder,
        view.textLength
      ).contentFingerprint
    val raw = storymodel4s.core.Credence.raw(0.7).toOption.get
    val calibrated = storymodel4s.core.Credence
      .calibrated(0.7, storymodel4s.core.Probability.unsafe(0.6), "m")
      .toOption
      .get
    val fps = Vector(
      withImportance(Estimate.observed(1.0)),
      withImportance(Estimate.Observed(1.0, Some(raw))),
      withImportance(Estimate.Observed(1.0, Some(calibrated))),
      withImportance(Estimate.missing(MissingReason.Unknown)),
      withImportance(Estimate.missing(MissingReason.ProviderAbstained))
    )
    assertEquals(fps.head, view.contentFingerprint)
    assertEquals(fps.distinct.size, fps.size)
  }

  // ---- round trip ------------------------------------------------------------------------------

  test("a real result rebuilt from parts through the factories validates to an equal result") {
    val costs = result.costs.map { (u, m) =>
      u -> m.map { (s, b) =>
        val receipts = b.reductions.map { (t, r) =>
          t -> AlignWire
            .reductionReceipt(
              r.reducer,
              r.members.map(m => member(m.member, m.estimate)),
              r.excludedMembers.map(x =>
                AlignWire
                  .memberExclusion(x.member, x.contradictions)
                  .fold(e => fail(e.message), identity)
              ),
              r.sourceChartCoverage,
              r.observedEstimateCoverage
            )
            .fold(e => fail(e.message), identity)
        }
        s -> AlignWire
          .costBreakdown(
            b.terms,
            b.mode,
            b.exclusion,
            b.total,
            b.missingTerms,
            b.sourceChartCoverage,
            receipts
          )
          .fold(e => fail(e.message), identity)
      }
    }
    val rows = result.posterior.rows.map(r =>
      AlignmentRow.of(r.unit, r.mass).fold(e => fail(e.message), identity)
    )
    val again = HsmmResult.validated(
      AnnaFixture.recall,
      view,
      result.candidateAnchors,
      AlignmentMatrix.of(rows).fold(e => fail(e.message), identity),
      TransitionFlow(result.flow.steps.map(s => FlowStep(s.from, s.to, s.mass))),
      result.viterbi,
      result.logLikelihood,
      costs,
      result.refinementPasses,
      Some(AdmissibilityEcho.fromChecksum(result.admissibilityEcho.checksum))
    )
    assertEquals(again, Right(result))
    val r = again.fold(e => fail(e.message), identity)
    assertEquals(
      AlignWire.matched(
        r,
        ViewFingerprint.fromChecksum(result.viewFingerprint.checksum),
        result.recallChecksum
      ),
      Right(result)
    )
  }
