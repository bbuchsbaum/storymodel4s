package storymodel4s.align

import cats.data.NonEmptySet
import munit.FunSuite

import scala.collection.immutable.SortedSet

import storymodel4s.recall.{RecallGraph, RecallUnitId}

/** The gated result is a proof anchored in the mode gate, not a record (forward-review P0; chief
  * re-review; ADR 0001 rev 3 L1). Admissibility records in these tests are never written by hand:
  * they come from [[ModeGate.assess]] over the fixture, or from a real inference.
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

  /** What the gate says about `unit` on `ref` — the only legitimate way to obtain a record. */
  private def gateOf(recall: RecallGraph, unit: RecallUnitId, ref: SourceNodeRef): Admissibility =
    ModeGate.assess(recall.byId(unit), view.node(ref).get, view)

  private def parts(r: HsmmResult) =
    (r.posterior, r.flow, r.viterbi, r.logLikelihood, r.costs, r.admissibility, r.refinementPasses)

  private def revalidate(
      recall: RecallGraph,
      base: HsmmResult,
      posterior: Option[AlignmentMatrix] = None,
      flow: Option[TransitionFlow] = None,
      viterbi: Option[Vector[AlignState]] = None,
      costs: Option[Map[RecallUnitId, Map[AlignState, CostBreakdown]]] = None,
      admissibility: Option[Map[RecallUnitId, Map[SourceNodeRef, Admissibility]]] = None
  ): Either[AlignError, HsmmResult] =
    HsmmResult.validated(
      recall,
      view,
      posterior.getOrElse(base.posterior),
      flow.getOrElse(base.flow),
      viterbi.getOrElse(base.viterbi),
      base.logLikelihood,
      costs.getOrElse(base.costs),
      admissibility.getOrElse(base.admissibility),
      base.refinementPasses
    )

  private def matrix(rows: Vector[AlignmentRow]): AlignmentMatrix =
    AlignmentMatrix.of(rows).fold(e => fail(e.message), identity)
  private def row(unit: RecallUnitId, mass: Map[AlignState, Double]): AlignmentRow =
    AlignmentRow.of(unit, mass).fold(e => fail(e.message), identity)

  test("every inferred result re-validates to an equal result (idempotence)") {
    val (p, f, v, ll, c, a, n) = parts(result)
    assertEquals(
      HsmmResult.validated(AnnaFixture.recall, view, p, f, v, ll, c, a, n),
      Right(result)
    )
    val (fp, ff, fv, fll, fc, fa, fn) = parts(foilResult)
    assertEquals(
      HsmmResult.validated(foil.recall, view, fp, ff, fv, fll, fc, fa, fn),
      Right(foilResult)
    )
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
      case Left(AlignError.GateViolation(u, s, _)) =>
        assertEquals(u, foilUnit)
        assertEquals(s, AlignState.Source(e5))
      case other => fail(s"expected a gate violation, got $other")
  }

  test("the chief's probe: a transplanted faithful-admitting record is rejected by the gate") {
    // Take a genuine, gate-produced faithful record from the full recall (unit u0 on e1) and
    // transplant it onto the foil unit's gated anchor e5, with matching faithful rows. The record
    // itself is authentic; the proof must still fail because ModeGate.assess(foilUnit, e5) does
    // not reproduce it.
    val u0 = result.posterior.rows.head.unit
    val authenticFaithful = result.admissibility(u0)(e1)
    assert(authenticFaithful.faithful)
    val transplanted = foilResult.admissibility
      .updated(foilUnit, foilResult.admissibility(foilUnit).updated(e5, authenticFaithful))
    val rows = Vector(row(foilUnit, Map(AlignState.Source(e5) -> 1.0)))
    val res = revalidate(
      foil.recall,
      foilResult,
      posterior = Some(matrix(rows)),
      viterbi = Some(Vector(AlignState.Source(e5))),
      admissibility = Some(transplanted)
    )
    res match
      case Left(AlignError.GateViolation(u, _, detail)) =>
        assertEquals(u, foilUnit)
        assert(detail.contains("mode gate"), detail)
      case other => fail(s"expected a gate violation from the transplanted record, got $other")
  }

  test("an admissibility entry for an anchor absent from the view is rejected") {
    val alien = SourceNodeRef.Situation(storymodel4s.core.SituationId.unsafe("not-in-view"))
    val authentic = result.admissibility(result.posterior.rows.head.unit)(e1)
    val u0 = result.posterior.rows.head.unit
    val adm = result.admissibility.updated(u0, result.admissibility(u0).updated(alien, authentic))
    assert(revalidate(AnnaFixture.recall, result, admissibility = Some(adm)).isLeft)
  }

  test("an empty admissibility record admits only external states") {
    // Rows must cover every recall unit in order (row-sequence law), so build the full recall:
    // one row per unit, an all-external flow whose marginals match, and an external path.
    val units = AnnaFixture.recall.ordered.map(_.id)
    val ext = AlignState.unranked
    val externalFlow = TransitionFlow(
      units.zip(units.drop(1)).map { case (a, b) => FlowStep(a, b, Map((ext, ext) -> 1.0)) }
    )
    val anchoredFirst = HsmmResult.validated(
      AnnaFixture.recall,
      view,
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
      Map.empty,
      0
    )
    assert(anchoredFirst.isLeft, "an anchored state with no admissibility record validated")
    val ok = HsmmResult.validated(
      AnnaFixture.recall,
      view,
      matrix(units.map(u => row(u, Map(ext -> 1.0)))),
      externalFlow,
      units.map(_ => ext),
      0.0,
      Map.empty,
      Map.empty,
      0
    )
    assert(ok.isRight, s"all-external result rejected: ${ok.left.map(_.message)}")
  }

  test("a distorted state with a facet set the gate did not record is rejected") {
    val gated = foilResult.admissibility(foilUnit)(e5)
    val rightFacets = gated.distortion.get
    val wrongFacets = NonEmptySet.fromSetUnsafe(SortedSet(Facet.Outcome))
    assertNotEquals(rightFacets, wrongFacets)
    def attempt(fs: NonEmptySet[Facet]) =
      HsmmResult.validated(
        foil.recall,
        view,
        matrix(Vector(row(foilUnit, Map(AlignState.Distorted(e5, fs) -> 1.0)))),
        TransitionFlow(Vector.empty),
        Vector(AlignState.Distorted(e5, fs)),
        0.0,
        Map.empty,
        Map(foilUnit -> Map(e5 -> gated)),
        0
      )
    assert(attempt(wrongFacets).isLeft)
    assert(attempt(rightFacets).isRight)
  }

  test("flow keys, cost entries, and the Viterbi path are gated too (key presence, not mass)") {
    val u0 = result.posterior.rows.head.unit
    val u1 = result.posterior.rows(1).unit
    // Splice the foil unit's gated record for e5 onto u0/u1 is not possible (the gate would not
    // reproduce it); instead pick a state the gate does not admit for u0 at all: a distorted mode
    // on an anchor the gate admits only faithfully.
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
    // its admissibility record. Every per-row and per-step check still passes; only the
    // row-sequence check can catch it — and it must, or the ordering metrics are fabricable.
    val rows = result.posterior.rows
    assert(rows.size >= 3, "fixture must have at least three units")
    val dropped = rows.last.unit
    val truncated = revalidate(
      AnnaFixture.recall,
      result,
      posterior = Some(matrix(rows.init)),
      flow = Some(TransitionFlow(result.flow.steps.init)),
      viterbi = Some(result.viterbi.init),
      costs = Some(result.costs - dropped),
      admissibility = Some(result.admissibility - dropped)
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
