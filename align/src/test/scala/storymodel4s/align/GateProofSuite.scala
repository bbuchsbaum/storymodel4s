package storymodel4s.align

import cats.data.NonEmptySet
import munit.FunSuite

import scala.collection.immutable.SortedSet

import storymodel4s.recall.RecallUnitId

/** The gated result is a proof, not a record (forward-review P0; ADR 0001 rev 3 L1). */
class GateProofSuite extends FunSuite:
  import AnnaFixture.{view, e1, e5}

  private lazy val result: HsmmResult =
    GraphHsmm
      .infer(AnnaFixture.recall, view, AnnaFixture.candidates, AnnaFixture.costModel)
      .fold(e => fail(e.message), identity)

  private def parts(r: HsmmResult) =
    (r.posterior, r.flow, r.viterbi, r.logLikelihood, r.costs, r.admissibility, r.refinementPasses)

  private def revalidate(
      posterior: AlignmentMatrix = result.posterior,
      flow: TransitionFlow = result.flow,
      viterbi: Vector[AlignState] = result.viterbi,
      costs: Map[RecallUnitId, Map[AlignState, CostBreakdown]] = result.costs,
      admissibility: Map[RecallUnitId, Map[SourceNodeRef, Admissibility]] = result.admissibility
  ): Either[AlignError, HsmmResult] =
    HsmmResult.validated(
      posterior,
      flow,
      viterbi,
      result.logLikelihood,
      costs,
      admissibility,
      result.refinementPasses
    )

  test("every inferred result re-validates to an equal result (idempotence)") {
    val (p, f, v, ll, c, a, n) = parts(result)
    assertEquals(HsmmResult.validated(p, f, v, ll, c, a, n), Right(result))
  }

  test("a forged faithful row on an inadmissible anchor is rejected") {
    // The forged row: unit u0 puts all its mass on e5 in the faithful mode, with an admissibility
    // record that admits e5 only distorted — exactly the repro from the review.
    val u0 = result.posterior.rows.head.unit
    val forgedRow = AlignmentRow(u0, Map(AlignState.Source(e5) -> 1.0))
    val gated = Admissibility.of(Vector(Contradiction.RoleReversal))
    assert(!gated.faithful)
    val adm = result.admissibility.updated(u0, Map(e5 -> gated))
    val forged = HsmmResult.validated(
      AlignmentMatrix(forgedRow +: result.posterior.rows.tail),
      result.flow,
      result.viterbi,
      result.logLikelihood,
      result.costs,
      adm,
      result.refinementPasses
    )
    forged match
      case Left(AlignError.GateViolation(u, s, _)) =>
        assertEquals(u, u0)
        assertEquals(s, AlignState.Source(e5))
      case other => fail(s"expected a gate violation, got $other")
  }

  test("an empty admissibility record admits only external states") {
    val u0 = result.posterior.rows.head.unit
    val row = AlignmentRow(u0, Map(AlignState.Source(e1) -> 1.0))
    val res = HsmmResult.validated(
      AlignmentMatrix(Vector(row)),
      TransitionFlow(Vector.empty),
      Vector(AlignState.Source(e1)),
      0.0,
      Map.empty,
      Map.empty,
      0
    )
    assert(res.isLeft)
    val ext = AlignmentRow(u0, Map(AlignState.unranked -> 1.0))
    val ok = HsmmResult.validated(
      AlignmentMatrix(Vector(ext)),
      TransitionFlow(Vector.empty),
      Vector(AlignState.unranked),
      0.0,
      Map.empty,
      Map.empty,
      0
    )
    assert(ok.isRight)
  }

  test("a distorted state with a facet set the gate did not record is rejected") {
    val u0 = result.posterior.rows.head.unit
    val gated = Admissibility.of(Vector(Contradiction.PolarityConflict))
    val wrongFacets = NonEmptySet.fromSetUnsafe(SortedSet(Facet.RoleReversal))
    val row = AlignmentRow(u0, Map(AlignState.Distorted(e5, wrongFacets) -> 1.0))
    val res = HsmmResult.validated(
      AlignmentMatrix(Vector(row)),
      TransitionFlow(Vector.empty),
      Vector(AlignState.Distorted(e5, wrongFacets)),
      0.0,
      Map.empty,
      Map(u0 -> Map(e5 -> gated)),
      0
    )
    assert(res.isLeft)
    val rightFacets = gated.distortion.get
    val good = HsmmResult.validated(
      AlignmentMatrix(Vector(AlignmentRow(u0, Map(AlignState.Distorted(e5, rightFacets) -> 1.0)))),
      TransitionFlow(Vector.empty),
      Vector(AlignState.Distorted(e5, rightFacets)),
      0.0,
      Map.empty,
      Map(u0 -> Map(e5 -> gated)),
      0
    )
    assert(good.isRight)
  }

  test("flow mass, cost entries, and the Viterbi path are gated too") {
    val u0 = result.posterior.rows.head.unit
    val u1 = result.posterior.rows(1).unit
    val gated = Admissibility.of(Vector(Contradiction.RoleReversal))
    val adm = result.admissibility
      .updated(u0, result.admissibility(u0).updated(e5, gated))
      .updated(u1, result.admissibility(u1).updated(e5, gated))
    val bad = AlignState.Source(e5)
    val step0 = result.flow.steps.head
    val flow = TransitionFlow(
      result.flow.steps.updated(0, step0.copy(mass = step0.mass.updated((bad, bad), 0.1)))
    )
    assert(revalidate(flow = flow, admissibility = adm).isLeft)
    val costs = result.costs.updated(u0, result.costs(u0).updated(bad, CostBreakdown.unreachable))
    assert(revalidate(costs = costs, admissibility = adm).isLeft)
    val path = result.viterbi.updated(0, bad)
    assert(revalidate(viterbi = path, admissibility = adm).isLeft)
  }

  test("structural mismatches are rejected before the gate") {
    assert(revalidate(viterbi = result.viterbi.tail).isLeft)
    assert(revalidate(flow = TransitionFlow(result.flow.steps.tail)).isLeft)
    val swapped = TransitionFlow(result.flow.steps.map(s => s.copy(from = s.to, to = s.from)))
    assert(revalidate(flow = swapped).isLeft)
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
    // Inaccessibility of the constructor from outside `align` is checked in the laws module
    // (LawsSuite), which lives in another package.
  }
