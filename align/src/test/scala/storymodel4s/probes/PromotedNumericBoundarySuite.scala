package promotednumericprobe

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.recall.RecallUnitId

/** Public standalone producers must uphold finite derived values, not just finite input fields. */
class PromotedNumericBoundarySuite extends FunSuite:
  private val unit = RecallUnitId.unsafe("row")
  private val first = AlignState.External(ExternalState.Association)
  private val second = AlignState.External(ExternalState.Commentary)

  test("standalone layer use refuses every nonfinite transition weight") {
    for
      kind <- TransitionKind.values;
      value <- Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)
    do
      assertEquals(
        LayerUse.of(TransitionModel(Map(kind -> value))),
        Left(AlignError.InvalidConfig("LayerUse.transitions", "weights must be finite"))
      )
  }

  test("finite extreme logits and default models yield finite renderable ledgers") {
    for value <- Vector(-Double.MaxValue, -0.0, 0.0, Double.MaxValue) do
      val model = TransitionModel(
        Map(TransitionKind.ExternalIn -> value, TransitionKind.ExternalStay -> value)
      )
      val use = LayerUse.of(model).fold(error => fail(error.message), identity)
      assert(use.externalIn >= 0.0 && use.externalIn <= 1.0)
      assert(use.externalStay >= 0.0 && use.externalStay <= 1.0)
      assertEquals(use, HsmmConfig.of(transitions = model).toOption.get.layerUse)
      assert(use.render.contains("external=[pIn="))
    assertEquals(LayerUse.of(TransitionModel.default), Right(HsmmConfig.default.layerUse))
  }

  test("finite masses whose aggregate overflows are refused") {
    assertEquals(
      AlignmentRow.of(unit, Map(first -> Double.MaxValue, second -> Double.MaxValue)),
      Left(AlignError.MalformedRow(unit, "mass and total must be nonnegative and finite"))
    )
  }

  test("largest representable total normalizes without losing either state") {
    val half = Double.MaxValue / 2.0
    val row = AlignmentRow.of(unit, Map(first -> half, second -> half)).toOption.get
    assertEquals(row.total, Double.MaxValue)
    assert(row.isWellFormed)
    assertEquals(row.normalized.mass, Map(first -> 0.5, second -> 0.5))
    assertEquals(row.normalized.total, 1.0)
    assertEquals(row.normalized.externalMass, 1.0)
  }

  test("empty and zero-mass rows preserve their lawful zero convention") {
    Vector(Map.empty[AlignState, Double], Map(first -> 0.0)).foreach { mass =>
      val row = AlignmentRow.of(unit, mass).toOption.get
      assertEquals(row.total, 0.0)
      assertEquals(row.normalized, row)
    }
  }
