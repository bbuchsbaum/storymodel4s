package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.StoryId

class CalibrationSuite extends FunSuite:

  // Deterministic synthetic data: score = noisy sigmoid of a hidden logit.
  private val scores: Vector[Double] = (0 until 200).toVector.map(i => (i + 0.5) / 200.0)
  private val labels: Vector[Boolean] = scores.zipWithIndex.map { case (s, i) =>
    // Overconfident scores: true rate is a squashed version of the score.
    val trueP = 0.5 + 0.3 * (s - 0.5) * 2
    ((i * 37) % 100) / 100.0 < trueP
  }

  test("Platt scaling reduces expected calibration error on overconfident scores") {
    val before = Ece.compute(scores, labels)
    val model = PlattScaling().fit(scores, labels)
    val after = Ece.compute(scores.map(model(_).value), labels)
    assert(after < before, s"before=$before after=$after")
    assert(model.name.startsWith("platt("))
  }

  test("temperature scaling returns probabilities and does not worsen NLL beyond identity") {
    val model = TemperatureScaling.fit(scores, labels)
    val ps = scores.map(model(_).value)
    assert(ps.forall(p => p >= 0.0 && p <= 1.0))
    assert(model.name.startsWith("temperature("))
    assert(Brier.compute(ps, labels) <= Brier.compute(scores, labels) + 1e-9)
  }

  test("Brier and ECE are zero for perfect confident predictions") {
    val ps = Vector(1.0, 0.0, 1.0, 0.0)
    val ys = Vector(true, false, true, false)
    assertEqualsDouble(Brier.compute(ps, ys), 0.0, 1e-12)
    assertEqualsDouble(Ece.compute(ps, ys), 0.0, 1e-12)
  }

  test("leave-story-out folds partition the items by story") {
    val stories = Vector("a", "b", "a", "c", "b").map(StoryId.unsafe)
    val folds = LeaveStoryOut.folds(stories)
    assertEquals(folds.map(_.heldOut.value), Vector("a", "b", "c"))
    folds.foreach { f =>
      assert(f.test.forall(i => stories(i) == f.heldOut))
      assert(f.train.forall(i => stories(i) != f.heldOut))
      assertEquals((f.train ++ f.test).sorted, stories.indices.toVector)
    }
  }
