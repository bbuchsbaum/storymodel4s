package storymodel4s.align

import cats.data.NonEmptySet
import munit.FunSuite

import scala.collection.immutable.SortedSet

import storymodel4s.core.StorySource
import storymodel4s.core.SurfaceAnalyzer
import storymodel4s.recall.{RecallGraph, RecallUnitId}

/** The gated result is a proof anchored in the mode gate and bounded by the nominated candidates,
  * not a record (forward-review P0; chief re-review; ADR 0001 rev 3 L1; bead `HsmmResult wire`).
  * Admissibility is never written by hand here: it is derived inside `validated` from the recall,
  * the view, and the candidate-anchor set.
  */
class GateProofSuite extends FunSuite:
  import AnnaFixture.{view, e1, e5}

  private lazy val result: HsmmResult =
    GraphHsmm
      .infer(AnnaFixture.recall, view, AnnaFixture.candidates, AnnaFixture.costModel)
      .fold(e => fail(e.message), identity)

  /** The role-reversed foil: its single unit is gated (faithful refused) on the true event. */
  private lazy val foil: AnnaFixture.Foil = AnnaFixture.roleReversed
  private lazy val foilResult: HsmmResult =
    GraphHsmm
      .infer(foil.recall, view, foil.candidates, foil.costModel)
      .fold(e => fail(e.message), identity)
  private lazy val foilUnit: RecallUnitId = foil.recall.ordered.head.id

  /** The foil inferred over an explicitly narrow nomination (e4, e5, sc2): every other node of the
    * view — e1 in particular — is a real, uncontradicted anchor the gate would admit faithfully but
    * that was never nominated for the unit.
    */
  private lazy val narrowFoilResult: HsmmResult =
    GraphHsmm
      .infer(
        foil.recall,
        view,
        Candidates.of(Map(foilUnit -> Vector(AnnaFixture.e4, e5, AnnaFixture.sc2))),
        foil.costModel
      )
      .fold(e => fail(e.message), identity)

  /** What the gate says about `unit` on `ref` — the only legitimate way to obtain a record. */
  private def gateOf(recall: RecallGraph, unit: RecallUnitId, ref: SourceNodeRef): Admissibility =
    ModeGate.assess(recall.byId(unit), view.node(ref).get, view)

  private def revalidate(
      recall: RecallGraph,
      base: HsmmResult,
      candidateAnchors: Option[Map[RecallUnitId, Vector[SourceNodeRef]]] = None,
      posterior: Option[AlignmentMatrix] = None,
      flow: Option[TransitionFlow] = None,
      viterbi: Option[Vector[AlignState]] = None,
      costs: Option[Map[RecallUnitId, Map[AlignState, CostBreakdown]]] = None,
      echo: Option[AdmissibilityEcho] = None,
      against: SourceView = view
  ): Either[AlignError, HsmmResult] =
    HsmmResult.validated(
      recall,
      against,
      candidateAnchors.getOrElse(base.candidateAnchors),
      posterior.getOrElse(base.posterior),
      flow.getOrElse(base.flow),
      viterbi.getOrElse(base.viterbi),
      base.logLikelihood,
      costs.getOrElse(base.costs),
      base.refinementPasses,
      echo
    )

  private def matrix(rows: Vector[AlignmentRow]): AlignmentMatrix =
    AlignmentMatrix.of(rows).fold(e => fail(e.message), identity)
  private def row(unit: RecallUnitId, mass: Map[AlignState, Double]): AlignmentRow =
    AlignmentRow.of(unit, mass).fold(e => fail(e.message), identity)

  /** All-external parts over the full recall (rows in recall order, matching flow and path). */
  private def allExternal(
      anchors: Map[RecallUnitId, Vector[SourceNodeRef]]
  ): Either[AlignError, HsmmResult] =
    val units = AnnaFixture.recall.ordered.map(_.id)
    val ext = AlignState.unranked
    HsmmResult.validated(
      AnnaFixture.recall,
      view,
      anchors,
      matrix(units.map(u => row(u, Map(ext -> 1.0)))),
      TransitionFlow(
        units.zip(units.drop(1)).map { case (a, b) => FlowStep(a, b, Map((ext, ext) -> 1.0)) }
      ),
      units.map(_ => ext),
      0.0,
      Map.empty,
      0
    )

  test("every inferred result re-validates to an equal result, with and without its echo") {
    assertEquals(revalidate(AnnaFixture.recall, result), Right(result))
    assertEquals(
      revalidate(AnnaFixture.recall, result, echo = Some(result.admissibilityEcho)),
      Right(result)
    )
    assertEquals(revalidate(foil.recall, foilResult), Right(foilResult))
    assertEquals(
      revalidate(foil.recall, foilResult, echo = Some(foilResult.admissibilityEcho)),
      Right(foilResult)
    )
  }

  test("the derived fingerprints name the view and the recall the proof was made against") {
    assertEquals(result.viewFingerprint, view.contentFingerprint)
    assertEquals(result.viewFingerprint, ViewFingerprint.of(view))
    assertEquals(result.recallChecksum, AlignWire.recallChecksum(AnnaFixture.recall))
    assertEquals(foilResult.viewFingerprint, result.viewFingerprint)
    assertNotEquals(foilResult.recallChecksum, result.recallChecksum)
    assertEquals(
      AlignWire.matched(result, result.viewFingerprint, result.recallChecksum),
      Right(result)
    )
  }

  test("the candidate anchors are the nominated set in canonical order and cover every unit") {
    val units = AnnaFixture.recall.ordered.map(_.id)
    assertEquals(result.candidateAnchors.keySet, units.toSet)
    units.foreach { u =>
      assertEquals(result.candidateAnchors(u), AnnaFixture.candidates.anchorsOf(u))
      assertEquals(result.candidateAnchors(u), result.candidateAnchors(u).distinct.sorted)
      assertEquals(result.admissibility(u).keySet, result.candidateAnchors(u).toSet)
    }
  }

  test("the gate refuses the faithful mode on the foil's true event") {
    val a = foilResult.admissibility(foilUnit)(e5)
    assert(!a.faithful, "the role-reversed unit must be gated on e5")
    assertEquals(a, gateOf(foil.recall, foilUnit, e5))
  }

  test("a forged faithful row on a gated anchor is rejected (mass)") {
    val forgedRow = row(foilUnit, Map(AlignState.Source(e5) -> 1.0))
    val forged = revalidate(
      foil.recall,
      foilResult,
      posterior = Some(matrix(Vector(forgedRow))),
      viterbi = Some(Vector(AlignState.Source(e5)))
    )
    forged match
      case Left(AlignError.GateViolation(u, s, detail)) =>
        assertEquals(u, foilUnit)
        assertEquals(s, AlignState.Source(e5))
        assert(detail.contains("did not admit"), detail)
      case other => fail(s"expected a gate violation, got $other")
  }

  test(
    "an anchored key outside the nominated set is rejected as not nominated (the chief's probe)"
  ) {
    // e1 is a real, uncontradicted node of the view; the gate would admit it faithfully. It was
    // never nominated for the foil unit, so a key on it — even with zero mass — is refused by
    // nomination, not admitted by silence.
    assert(!narrowFoilResult.candidateAnchors(foilUnit).contains(e1))
    assert(
      gateOf(foil.recall, foilUnit, e1).faithful,
      "e1 must be admissible, so only nomination refuses it"
    )
    val r0 = narrowFoilResult.posterior.rows.head
    val res = revalidate(
      foil.recall,
      narrowFoilResult,
      posterior = Some(matrix(Vector(row(foilUnit, r0.mass.updated(AlignState.Source(e1), 0.0)))))
    )
    res match
      case Left(AlignError.GateViolation(u, s, detail)) =>
        assertEquals(u, foilUnit)
        assertEquals(s, AlignState.Source(e1))
        assert(detail.contains("not nominated"), detail)
      case other => fail(s"expected a nomination violation, got $other")
    // The same probe on the cost map and on the Viterbi path.
    val costs = narrowFoilResult.costs
      .updated(
        foilUnit,
        narrowFoilResult.costs(foilUnit).updated(AlignState.Source(e1), CostBreakdown.unreachable)
      )
    assert(revalidate(foil.recall, narrowFoilResult, costs = Some(costs)).left.exists {
      case AlignError.GateViolation(_, _, d) => d.contains("not nominated")
      case _                                 => false
    })
    assert(
      revalidate(
        foil.recall,
        narrowFoilResult,
        viterbi = Some(Vector(AlignState.Source(e1)))
      ).isLeft
    )
  }

  test("widening the nomination is construction data: the widened result is valid but different") {
    val widened = narrowFoilResult.candidateAnchors
      .updated(foilUnit, (narrowFoilResult.candidateAnchors(foilUnit) :+ e1).distinct.sorted)
    val res = revalidate(foil.recall, narrowFoilResult, candidateAnchors = Some(widened))
    val r = res.fold(e => fail(e.message), identity)
    assertNotEquals(r, narrowFoilResult)
    assertEquals(r.admissibility(foilUnit)(e1), gateOf(foil.recall, foilUnit, e1))
    assertNotEquals(r.admissibilityEcho, narrowFoilResult.admissibilityEcho)
    // the widened result is the one the echo of the widened derivation names, not the narrow one
    assert(
      revalidate(
        foil.recall,
        narrowFoilResult,
        candidateAnchors = Some(widened),
        echo = Some(narrowFoilResult.admissibilityEcho)
      ).isLeft
    )
  }

  test("candidate anchors must name exactly the recall's units, canonical, present in the view") {
    val u0 = result.posterior.rows.head.unit
    val anchors = result.candidateAnchors
    assert(revalidate(AnnaFixture.recall, result, candidateAnchors = Some(anchors - u0)).isLeft)
    val alienUnit = RecallUnitId.unsafe("not-a-unit")
    assert(
      revalidate(
        AnnaFixture.recall,
        result,
        candidateAnchors = Some(anchors.updated(alienUnit, Vector.empty))
      ).isLeft
    )
    val reversed = anchors.updated(u0, anchors(u0).reverse)
    assert(anchors(u0).size >= 2, "fixture must nominate at least two anchors for u0")
    assert(
      revalidate(AnnaFixture.recall, result, candidateAnchors = Some(reversed)).left
        .exists(_.message.contains("canonical order"))
    )
    val duplicated = anchors.updated(u0, anchors(u0) ++ anchors(u0).take(1))
    assert(revalidate(AnnaFixture.recall, result, candidateAnchors = Some(duplicated)).isLeft)
    val alien = SourceNodeRef.Situation(storymodel4s.core.SituationId.unsafe("not-in-view"))
    val withAlien = anchors.updated(u0, (anchors(u0) :+ alien).sorted)
    revalidate(AnnaFixture.recall, result, candidateAnchors = Some(withAlien)) match
      case Left(AlignError.GateViolation(u, s, detail)) =>
        assertEquals(u, u0)
        assertEquals(s, AlignState.Source(alien))
        assert(detail.contains("absent from the source view"), detail)
      case other => fail(s"expected a gate violation for the alien anchor, got $other")
  }

  test("an admissibility echo that is not the gate's digest is rejected as gate drift") {
    val stale = foilResult.admissibilityEcho
    assertNotEquals(stale, result.admissibilityEcho)
    revalidate(AnnaFixture.recall, result, echo = Some(stale)) match
      case Left(AlignError.GateDrift(recorded, derived)) =>
        assertEquals(recorded, stale)
        assertEquals(derived, result.admissibilityEcho)
      case other => fail(s"expected gate drift, got $other")
    // Drift is reported before any key is gated: the parts are untouched.
    assertEquals(
      revalidate(AnnaFixture.recall, result, echo = Some(result.admissibilityEcho)),
      Right(result)
    )
  }

  test(
    "a result validated against a different view carries a different fingerprint, and matched refuses it"
  ) {
    // Same node ids, one lemma more on e1: the gate agrees, the fingerprint does not.
    val altered = InMemorySourceView(
      view.nodes.map(n => if n.ref == e1 then n.copy(lemmas = n.lemmas + "porch") else n),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    assertNotEquals(altered.contentFingerprint, view.contentFingerprint)
    val r2 =
      revalidate(AnnaFixture.recall, result, against = altered).fold(e => fail(e.message), identity)
    assertEquals(r2.viewFingerprint, altered.contentFingerprint)
    assertNotEquals(r2, result)
    AlignWire.matched(r2, result.viewFingerprint, result.recallChecksum) match
      case Left(AlignError.FingerprintMismatch(field, recorded, derived)) =>
        assertEquals(field, "viewFingerprint")
        assertEquals(recorded, result.viewFingerprint.checksum.hex)
        assertEquals(derived, altered.contentFingerprint.checksum.hex)
      case other => fail(s"expected a view fingerprint mismatch, got $other")
  }

  test(
    "a result validated against a different recall carries a different checksum, and matched refuses it"
  ) {
    // Same units and spans, a longer transcript: the canonical text checksum differs.
    val longer = StorySource.fromText(AnnaFixture.recallText + " Then nothing more.").toOption.get
    val recall2 =
      AnnaFixture.recall.copy(transcript = longer, atlas = SurfaceAnalyzer.analyze(longer))
    assertNotEquals(AlignWire.recallChecksum(recall2), result.recallChecksum)
    val r2 = revalidate(recall2, result).fold(e => fail(e.message), identity)
    assertEquals(r2.recallChecksum, AlignWire.recallChecksum(recall2))
    AlignWire.matched(r2, result.viewFingerprint, result.recallChecksum) match
      case Left(AlignError.FingerprintMismatch(field, _, _)) =>
        assertEquals(field, "recallChecksum")
      case other => fail(s"expected a recall checksum mismatch, got $other")
  }

  test("a unit with no nomination admits only external states") {
    val noAnchors = AnnaFixture.recall.ordered.map(_.id -> Vector.empty[SourceNodeRef]).toMap
    val ok = allExternal(noAnchors)
    assert(ok.isRight, s"all-external result rejected: ${ok.left.map(_.message)}")
    val units = AnnaFixture.recall.ordered.map(_.id)
    val ext = AlignState.unranked
    val anchoredFirst = HsmmResult.validated(
      AnnaFixture.recall,
      view,
      noAnchors,
      matrix(
        row(units.head, Map(AlignState.Source(e1) -> 1.0)) +:
          units.tail.map(u => row(u, Map(ext -> 1.0)))
      ),
      TransitionFlow(
        units.zip(units.drop(1)).zipWithIndex.map { case ((a, b), i) =>
          FlowStep(a, b, Map(((if i == 0 then AlignState.Source(e1) else ext), ext) -> 1.0))
        }
      ),
      AlignState.Source(e1) +: units.tail.map(_ => ext),
      0.0,
      Map.empty,
      0
    )
    assert(anchoredFirst.isLeft, "an anchored state with no nomination validated")
  }

  test("a distorted state with a facet set the gate did not derive is rejected") {
    val gated = foilResult.admissibility(foilUnit)(e5)
    val rightFacets = gated.distortion.get
    val wrongFacets = NonEmptySet.fromSetUnsafe(SortedSet(Facet.Outcome))
    assertNotEquals(rightFacets, wrongFacets)
    def attempt(fs: NonEmptySet[Facet]) =
      HsmmResult.validated(
        foil.recall,
        view,
        Map(foilUnit -> Vector(e5)),
        matrix(Vector(row(foilUnit, Map(AlignState.Distorted(e5, fs) -> 1.0)))),
        TransitionFlow(Vector.empty),
        Vector(AlignState.Distorted(e5, fs)),
        0.0,
        Map.empty,
        0
      )
    assert(attempt(wrongFacets).isLeft)
    assert(attempt(rightFacets).isRight)
  }

  test("flow keys, cost entries, and the Viterbi path are gated too (key presence, not mass)") {
    val u0 = result.posterior.rows.head.unit
    val u1 = result.posterior.rows(1).unit
    // A state the gate does not admit for u0 at all: a distorted mode on an anchor the gate admits
    // only faithfully.
    val a0 = result.admissibility(u0)
    val (ref, _) = a0.toVector.sortBy(_._1.key).find(_._2.faithful).get
    val bad = AlignState.Distorted(ref, NonEmptySet.one(Facet.Outcome))
    val step0 = result.flow.steps.head
    val flow = TransitionFlow(
      result.flow.steps.updated(0, step0.copy(mass = step0.mass.updated((bad, bad), 0.0)))
    )
    assert(revalidate(AnnaFixture.recall, result, flow = Some(flow)).isLeft, "zero-mass flow key")
    val costs = result.costs.updated(u0, result.costs(u0).updated(bad, CostBreakdown.unreachable))
    assert(revalidate(AnnaFixture.recall, result, costs = Some(costs)).isLeft, "cost entry")
    val path = result.viterbi.updated(0, bad)
    assert(revalidate(AnnaFixture.recall, result, viterbi = Some(path)).isLeft, "viterbi step")
    val rows = result.posterior.rows
    val zeroKey = rows.updated(1, row(u1, rows(1).mass.updated(bad, 0.0)))
    assert(
      revalidate(AnnaFixture.recall, result, posterior = Some(matrix(zeroKey))).isLeft,
      "zero-mass posterior key"
    )
  }

  test("a cost record whose mode or exclusion disagrees with its key is rejected (cross-record)") {
    // A Distorted key carrying a Faithful breakdown.
    val distortedKey = foilResult
      .costs(foilUnit)
      .keys
      .collectFirst { case s @ AlignState.Distorted(`e5`, _) =>
        s
      }
      .get
    val faithfulRecord = foilResult
      .costs(foilUnit)
      .collectFirst {
        case (AlignState.Source(_), b) if b.mode.exists(_.isFaithful) => b
      }
      .get
    val swapped = foilResult.costs
      .updated(foilUnit, foilResult.costs(foilUnit).updated(distortedKey, faithfulRecord))
    revalidate(foil.recall, foilResult, costs = Some(swapped)) match
      case Left(AlignError.MalformedRecord(record, detail)) =>
        assertEquals(record, "CostBreakdown")
        assert(detail.contains("does not agree with its key"), detail)
      case other => fail(s"expected a malformed cost record, got $other")
    // An External key carrying a source mode.
    val externalKey = AlignState.External(ExternalState.Intrusion)
    assert(foilResult.costs(foilUnit).contains(externalKey))
    val external = foilResult.costs
      .updated(foilUnit, foilResult.costs(foilUnit).updated(externalKey, faithfulRecord))
    assert(revalidate(foil.recall, foilResult, costs = Some(external)).left.exists {
      case AlignError.MalformedRecord(_, d) => d.contains("does not agree with its key")
      case _                                => false
    })
    // A Source key carrying an external (mode-less) record, and an admitted key carrying an
    // excluded record.
    val sourceKey = foilResult
      .costs(foilUnit)
      .collectFirst {
        case (s @ AlignState.Source(_), b) if b.mode.exists(_.isFaithful) => s
      }
      .get
    val externalRecord = foilResult.costs(foilUnit)(externalKey)
    val modeless = foilResult.costs
      .updated(foilUnit, foilResult.costs(foilUnit).updated(sourceKey, externalRecord))
    assert(revalidate(foil.recall, foilResult, costs = Some(modeless)).isLeft)
    val excluded = foilResult.costs
      .updated(foilUnit, foilResult.costs(foilUnit).updated(sourceKey, CostBreakdown.unreachable))
    assert(revalidate(foil.recall, foilResult, costs = Some(excluded)).left.exists {
      case AlignError.MalformedRecord(_, d) => d.contains("excluded")
      case _                                => false
    })
  }

  test("a candidate absent from the view is dropped by infer, never nominated (alien candidate)") {
    val alien = SourceNodeRef.Situation(storymodel4s.core.SituationId.unsafe("not-in-view"))
    val withAlien = Candidates(
      AnnaFixture.candidates.byUnit.map((u, set) =>
        u -> CandidateSet(
          set.nominations :+ Nomination(alien, Channels.unspecified, set.size, None, None, None),
          set.abstained
        )
      )
    )
    val res = GraphHsmm
      .infer(AnnaFixture.recall, view, withAlien, AnnaFixture.costModel)
      .fold(e => fail(s"an alien candidate must not fail inference: ${e.message}"), identity)
    assertEquals(res, result)
    res.candidateAnchors.values.foreach(refs => assert(!refs.contains(alien)))
    res.costs.values.foreach(m => assert(!m.keys.exists(_.anchor.contains(alien))))
    assert(res.costs.values.flatMap(_.values).forall(_.exclusion.isEmpty))
  }

  test("costs recorded for a unit outside the recall are rejected") {
    val stray = RecallUnitId.unsafe("stray")
    val costs = result.costs.updated(stray, Map(AlignState.unranked -> CostBreakdown.unreachable))
    assert(
      revalidate(AnnaFixture.recall, result, costs = Some(costs)).left
        .exists(_.message.contains("unknown unit"))
    )
  }

  test("unnormalized rows and inconsistent flow marginals are rejected") {
    val rows = result.posterior.rows
    val r0 = rows.head
    val scaled = row(r0.unit, r0.mass.view.mapValues(_ * 0.5).toMap)
    assert(
      revalidate(
        AnnaFixture.recall,
        result,
        posterior = Some(matrix(rows.updated(0, scaled)))
      ).isLeft,
      "row summing to 0.5"
    )
    val step0 = result.flow.steps.head
    val (k, m) = step0.mass.toVector.maxBy(_._2)
    val bumped = TransitionFlow(
      result.flow.steps.updated(0, step0.copy(mass = step0.mass.updated(k, m + 1e-3)))
    )
    assert(revalidate(AnnaFixture.recall, result, flow = Some(bumped)).isLeft, "flow marginal")
  }

  test("rows must be the recall's units in recall order (truncation and reordering rejected)") {
    // Consistently truncated: drop the last row, its flow step, its Viterbi state, its costs and
    // its nomination. Every per-row and per-step check still passes; only the row-sequence check
    // can catch it — and it must, or the ordering metrics are fabricable.
    val rows = result.posterior.rows
    assert(rows.size >= 3, "fixture must have at least three units")
    val dropped = rows.last.unit
    val truncated = revalidate(
      AnnaFixture.recall,
      result,
      candidateAnchors = Some(result.candidateAnchors - dropped),
      posterior = Some(matrix(rows.init)),
      flow = Some(TransitionFlow(result.flow.steps.init)),
      viterbi = Some(result.viterbi.init),
      costs = Some(result.costs - dropped)
    )
    assert(truncated.isLeft, "truncated matrix validated")
    assert(
      truncated.left.exists(_.message.contains("unit order")),
      s"expected the row-order error, got ${truncated.left.map(_.message)}"
    )
    // Reordered: swap the first two rows and rebuild the path consistently; the flow endpoints
    // then disagree with the rows, and even if they were rebuilt, the order check rejects it.
    val swappedRows = rows.updated(0, rows(1)).updated(1, rows(0))
    val swappedPath = result.viterbi.updated(0, result.viterbi(1)).updated(1, result.viterbi(0))
    assert(
      revalidate(
        AnnaFixture.recall,
        result,
        posterior = Some(matrix(swappedRows)),
        viterbi = Some(swappedPath)
      ).isLeft,
      "reordered matrix validated"
    )
  }

  test("structural mismatches are rejected before the gate") {
    assert(revalidate(AnnaFixture.recall, result, viterbi = Some(result.viterbi.tail)).isLeft)
    assert(
      revalidate(
        AnnaFixture.recall,
        result,
        flow = Some(TransitionFlow(result.flow.steps.tail))
      ).isLeft
    )
    val swapped = TransitionFlow(result.flow.steps.map(s => s.copy(from = s.to, to = s.from)))
    assert(revalidate(AnnaFixture.recall, result, flow = Some(swapped)).isLeft)
  }

  test("the ablation result has no path into the gated type") {
    import scala.compiletime.testing.typeCheckErrors
    val errors = typeCheckErrors(
      """
      val a: storymodel4s.align.AblationResult = ???
      val recall: storymodel4s.recall.RecallGraph = ???
      val view: storymodel4s.align.SourceView = ???
      storymodel4s.align.RecallSignature.compute(a, recall, view)
      """
    )
    assert(
      errors.exists(_.message.contains("AblationResult")),
      s"an AblationResult must not be accepted where an HsmmResult is required: $errors"
    )
    // Inaccessibility of the constructors (HsmmResult, rows, matrices, Admissibility) from outside
    // `align` is checked in the laws module (LawsSuite), which lives in another package.
  }
