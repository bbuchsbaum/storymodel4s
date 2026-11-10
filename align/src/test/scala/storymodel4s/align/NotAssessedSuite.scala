package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.{SpanSet, TextSpan}
import storymodel4s.recall.{
  DiscourseFunction,
  ExpressedUncertainty,
  PropositionSketch,
  RecallUnit,
  RecallUnitId
}

/** A unit whose discourse function nobody assessed gets no preference from the cost model: no
  * natural external state and no prior. The assessed functions keep the values they had.
  */
class NotAssessedSuite extends FunSuite:

  test("an unassessed unit presupposes no external state"):
    assertEquals(ExternalStates.natural(DiscourseFunction.NotAssessed), None)

  test("every assessed function keeps its natural external state"):
    val expected = Map(
      DiscourseFunction.Association -> ExternalState.Association,
      DiscourseFunction.Evaluation -> ExternalState.Commentary,
      DiscourseFunction.TaskCommentary -> ExternalState.Commentary,
      DiscourseFunction.SourceMonitoring -> ExternalState.Commentary,
      DiscourseFunction.Inference -> ExternalState.SourceConsistentInference,
      DiscourseFunction.Uninterpretable -> ExternalState.Uninterpretable,
      DiscourseFunction.EpisodicAssertion -> ExternalState.Intrusion,
      DiscourseFunction.Summary -> ExternalState.Intrusion
    )
    assertEquals(expected.keySet + DiscourseFunction.NotAssessed, DiscourseFunction.values.toSet)
    expected.foreach((f, state) => assertEquals(ExternalStates.natural(f), Some(state), f.toString))

  test(
    "the default function prior adds nothing for an unassessed unit, and is otherwise unchanged"
  ):
    val prior = FunctionPrior.default
    assertEquals(prior(DiscourseFunction.NotAssessed), 0.0)
    assertEquals(
      DiscourseFunction.values.toVector.map(prior(_)),
      Vector(0.0, 0.0, 0.1, 0.5, 0.5, 0.3, 0.6, 0.6, 0.0)
    )

  test("for an unassessed unit every external state is at the floor; assessed units are unchanged"):
    val named = ExternalState.values.toVector
    assert(named.forall(ExternalStates.atFloor(DiscourseFunction.NotAssessed, _)))
    DiscourseFunction.values.filterNot(_ == DiscourseFunction.NotAssessed).foreach { f =>
      val natural = ExternalStates.natural(f).get
      assertEquals(
        named.filter(ExternalStates.atFloor(f, _)).toSet,
        Set(natural, ExternalState.Unranked),
        f.toString
      )
    }

  private def unit(f: DiscourseFunction) = RecallUnit(
    RecallUnitId.unsafe("s:u0"),
    0,
    SpanSet.one(TextSpan.unsafe(0, 1)),
    "a",
    f,
    ExpressedUncertainty.Unmarked,
    PropositionSketch.empty,
    None
  )

  test("the default cost model prices every external state at the floor for an unassessed unit"):
    val model = DefaultLocalCostModel()
    ExternalState.values.foreach { state =>
      assertEquals(
        model.externalCost(unit(DiscourseFunction.NotAssessed), state),
        model.externalFloor,
        state.toString
      )
    }
    // An assessed unit still pays the mismatch away from its natural state.
    val assertion = unit(DiscourseFunction.EpisodicAssertion)
    assertEquals(model.externalCost(assertion, ExternalState.Intrusion), model.externalFloor)
    assertEquals(
      model.externalCost(assertion, ExternalState.Commentary),
      model.externalFloor + model.externalMismatch
    )
