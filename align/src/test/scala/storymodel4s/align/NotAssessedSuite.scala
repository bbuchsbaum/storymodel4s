package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.DiscourseFunction

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

  test("the default function prior adds nothing for an unassessed unit, and is otherwise unchanged"):
    val prior = FunctionPrior.default
    assertEquals(prior(DiscourseFunction.NotAssessed), 0.0)
    assertEquals(
      DiscourseFunction.values.toVector.map(prior(_)),
      Vector(0.0, 0.0, 0.1, 0.5, 0.5, 0.3, 0.6, 0.6, 0.0)
    )
