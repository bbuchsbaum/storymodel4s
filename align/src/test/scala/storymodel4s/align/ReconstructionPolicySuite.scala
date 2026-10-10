package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.DiscourseFunction

/** Synthetic posterior decisions distinguish raw evidence from structural choices. */
class ReconstructionPolicySuite extends FunSuite:
  import ReferenceFixture.*
  import StructuredReconstruction.*

  private val grouping = right(Grouping.of(view, Vector(a -> 1, b -> 9)))
  private val hard = right(DecisionPolicy.of(OrderPolicy.Nondecreasing))

  test("profile defaults disable fill and changes have distinct identities") {
    val base = Profile.sherlock()
    assertEquals(base.decision.fill, FillPolicy.Disabled)
    assertNotEquals(base.fingerprint, Profile.sherlock(FillPolicy.Requested).fingerprint)
    val changed = right(Profile.of(right(HsmmConfig.of(temperature = 0.7)), hard))
    assertNotEquals(base.fingerprint, changed.fingerprint)
    assertEquals(base.kind, MeasureKind.ModelPosterior)
  }

  test("reconstruction and reference retain the exact original evidence") {
    val e = evidence(table())
    val reference = run(e)
    val reconstructed =
      right(StructuredReconstruction.compute(recall, view, e, Profile.sherlock(), grouping))
    assert(reconstructed.evidence eq e)
    assertEquals(reconstructed.evidenceId, reference.evidenceId)
    assertEquals(reconstructed.inference.costs, e.units.zip(e.breakdowns).toMap)
    assertEquals(reconstructed.decisions.map(_.unit), e.units)
  }

  test("chosen mass belongs to chosen anchor while raw argmax may be external") {
    val ext = AlignState.External(ExternalState.values.filterNot(_ == ExternalState.Unranked).head)
    val row = right(
      AlignmentRow.of(
        unit.id,
        Map(ext -> 0.5, AlignState.Source(a) -> 0.2, AlignState.Source(b) -> 0.3)
      )
    )
    val d = right(StructuredReconstruction.decide(Vector(row), grouping, hard)).head
    assertEquals(d.rawArgmax, Some(ext))
    assertEquals(d.rawArgmaxMass, Some(0.5))
    assertEquals(d.anchor, Some(b))
    assertEquals(d.chosenMass, Some(0.3))
    assertEquals(row.mass(ext), 0.5)
  }

  test("invalid penalties and ignored combinations refuse") {
    Vector(Double.NaN, Double.PositiveInfinity, -1.0).foreach { bad =>
      assert(DecisionPolicy.of(OrderPolicy.PenalizedBackward, backwardPenalty = bad).isLeft)
      assert(DecisionPolicy.of(OrderPolicy.Nondecreasing, forwardPenalty = bad).isLeft)
    }
    assert(DecisionPolicy.of(OrderPolicy.PenalizedBackward, forwardPenalty = 0.1).isLeft)
  }

  test("foreign source grouping and wrong-group fill refuse") {
    val changed = view.copy(scoringLength = view.scoringLength + 1)
    val foreign = right(Grouping.of(changed, Vector(a -> 1, b -> 9)))
    assert(
      StructuredReconstruction
        .compute(recall, view, evidence(table()), Profile.sherlock(), foreign)
        .isLeft
    )
    assert(FillRequest.of(recall, grouping, Vector((unit.id -> 1) -> b)).isLeft)
    assert(Grouping.of(view, Vector(a -> 1, b -> 1)).isLeft)
  }

  test("disabled fill refuses a supplied request") {
    val row = right(AlignmentRow.of(unit.id, Map(AlignState.Source(a) -> 1.0)))
    val fill = right(FillRequest.of(recall, grouping, Vector((unit.id -> 1) -> a)))
    assert(StructuredReconstruction.decide(Vector(row), grouping, hard, Some(fill)).isLeft)
  }

  test("noncandidate fill has zero posterior mass and preserves original rows") {
    val rows = Vector(
      right(AlignmentRow.of(unit.id, Map(AlignState.Source(b) -> 1.0))),
      right(AlignmentRow.of(AnnaFixture.u1.id, Map(AlignState.Source(a) -> 0.2)))
    )
    val r = graph(Vector(unit, AnnaFixture.u1))
    val requested = right(FillRequest.of(r, grouping, Vector((AnnaFixture.u1.id -> 9) -> b)))
    val policy = right(DecisionPolicy.of(OrderPolicy.Nondecreasing, fill = FillPolicy.Requested))
    val unfilled = right(StructuredReconstruction.decide(rows, grouping, policy))
    val filled = right(StructuredReconstruction.decide(rows, grouping, policy, Some(requested)))
    assertEquals(unfilled(1).anchor, Some(a))
    assertEquals(filled(1).anchor, Some(b))
    assertEquals(filled(1).origin, Origin.RequestedFill)
    assertEquals(filled(1).chosenMass, Some(0.0))
    assertEquals(filled(1).rawSourceMaximum, Some(a))
    assertEquals(rows(1).anchorMass, Map(a -> 0.2))
  }

  test("same unit IDs do not permit foreign recall fill reuse") {
    val changed = graph(Vector(unit.copy(function = DiscourseFunction.Association)))
    val requested = right(FillRequest.of(changed, grouping, Vector((unit.id -> 1) -> a)))
    assert(
      StructuredReconstruction
        .compute(
          recall,
          view,
          evidence(table()),
          Profile.sherlock(FillPolicy.Requested),
          grouping,
          Some(requested)
        )
        .isLeft
    )
  }

  test("forward rank differences cannot overflow into a free leap") {
    val extreme = right(Grouping.of(view, Vector(a -> Int.MinValue, b -> Int.MaxValue)))
    val policy = right(DecisionPolicy.of(OrderPolicy.Nondecreasing, forwardPenalty = 0.1))
    val rows = Vector(
      right(
        AlignmentRow.of(unit.id, Map(AlignState.Source(a) -> 0.9, AlignState.Source(b) -> 0.1))
      ),
      right(
        AlignmentRow.of(
          AnnaFixture.u1.id,
          Map(AlignState.Source(a) -> 0.1, AlignState.Source(b) -> 0.9)
        )
      )
    )
    val decisions = right(StructuredReconstruction.decide(rows, extreme, policy))
    assertEquals(decisions.map(_.assignedGroup), Vector(Some(Int.MinValue), Some(Int.MinValue)))
  }

  test("finite individual masses with overflowing sum refuse") {
    val row = right(
      AlignmentRow.of(
        unit.id,
        Map(AlignState.Source(a) -> Double.MaxValue, AlignState.Source(b) -> Double.MaxValue)
      )
    )
    val result = StructuredReconstruction.decide(Vector(row), grouping, hard)
    assert(result.isLeft)
  }

  test("historical backward penalty uses group index distance") {
    val sparse = right(Grouping.of(view, Vector(a -> 1, b -> 100)))
    val policy = right(DecisionPolicy.of(OrderPolicy.PenalizedBackward, backwardPenalty = 0.1))
    val rows = Vector(
      right(
        AlignmentRow.of(unit.id, Map(AlignState.Source(a) -> 0.1, AlignState.Source(b) -> 0.9))
      ),
      right(
        AlignmentRow.of(
          AnnaFixture.u1.id,
          Map(AlignState.Source(a) -> 0.9, AlignState.Source(b) -> 0.1)
        )
      )
    )
    assertEquals(
      right(StructuredReconstruction.decide(rows, sparse, policy)).map(_.anchor),
      Vector(Some(b), Some(a))
    )
  }

  test("overlapping hierarchy roots refuse while siblings are accepted") {
    val nested = view.copy(nodes =
      view.nodes.map(n => if n.ref == a then n.copy(level = 1) else n.copy(parent = Some(a)))
    )
    assert(Grouping.of(nested, Vector(a -> 1, b -> 9)).isLeft)
    assert(Grouping.of(view, Vector(a -> 1, b -> 9)).isRight)
  }

  test("regrouped finite row mass overflow refuses") {
    val m = Double.MaxValue
    val x = math.ulp(m) * 0.3
    val nested = view.copy(nodes =
      view.nodes.map(n => if n.ref == a then n.copy(level = 1) else n.copy(parent = Some(a)))
    )
    val grouped = right(Grouping.of(nested, Vector(a -> 1)))
    val distorted = AlignState.Distorted(b, cats.data.NonEmptySet.one(Facet.Polarity))
    val row = right(
      AlignmentRow.of(
        unit.id,
        Map(AlignState.Source(a) -> m, AlignState.Source(b) -> x, distorted -> x)
      )
    )
    assert(row.total.isFinite)
    assert(row.anchorMass.values.forall(_.isFinite))
    assert(!(m + (x + x)).isFinite)
    assert(StructuredReconstruction.decide(Vector(row), grouped, hard).isLeft)
  }

  test("overflowing transition penalties refuse") {
    val rows = Vector(
      right(AlignmentRow.of(unit.id, Map(AlignState.Source(a) -> 1.0))),
      right(AlignmentRow.of(AnnaFixture.u1.id, Map(AlignState.Source(b) -> 1.0)))
    )
    val policy =
      right(DecisionPolicy.of(OrderPolicy.Nondecreasing, forwardPenalty = Double.MaxValue))
    assert(StructuredReconstruction.decide(rows, grouping, policy).isLeft)
  }
