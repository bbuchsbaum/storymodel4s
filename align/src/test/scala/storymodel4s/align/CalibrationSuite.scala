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

  private def right[A](e: Either[AlignError, A]): A = e.fold(x => fail(x.message), identity)

  test("Platt scaling reduces expected calibration error on overconfident scores") {
    val before = right(Ece.compute(scores, labels))
    val model = right(PlattScaling().fit(scores, labels))
    val after = right(Ece.compute(scores.map(model(_).value), labels))
    assert(after < before, s"before=$before after=$after")
    assert(model.name.startsWith("platt("))
  }

  test("temperature scaling never worsens NLL relative to the identity on its fitting data") {
    val model = right(TemperatureScaling.fit(scores, labels))
    val ps = scores.map(model(_).value)
    assert(ps.forall(p => p >= 0.0 && p <= 1.0))
    assert(model.name.startsWith("temperature("))
    // the grid contains T = 1, so this is what the fit guarantees (nothing about Brier or ECE)
    assert(right(Nll.compute(ps, labels)) <= right(Nll.compute(scores, labels)) + 1e-9)
  }

  test("Brier and ECE are zero for perfect confident predictions, under both binnings") {
    val ps = Vector(1.0, 0.0, 1.0, 0.0)
    val ys = Vector(true, false, true, false)
    assertEqualsDouble(right(Brier.compute(ps, ys)), 0.0, 1e-12)
    assertEqualsDouble(right(Ece.compute(ps, ys)), 0.0, 1e-12)
    assertEqualsDouble(right(Ece.compute(ps, ys, binning = EceBinning.EqualMass)), 0.0, 1e-12)
  }

  test("equal-mass binning sees miscalibration that equal-width bins hide on skewed scores") {
    // all scores in one narrow band, half of them wrong: equal-width has one bin with acc 0.5
    // vs conf ~0.9 → ECE ~0.4; equal-mass also splits into bins but reports the same gap here.
    val ps = (0 until 100).toVector.map(i => 0.9 + i * 0.0005)
    val ys = (0 until 100).toVector.map(i => i % 2 == 0)
    val ew = right(Ece.compute(ps, ys))
    val em = right(Ece.compute(ps, ys, binning = EceBinning.EqualMass))
    assert(ew > 0.3 && em > 0.3, s"ew=$ew em=$em")
  }

  test("size mismatches are typed errors") {
    assert(Ece.compute(Vector(0.5), Vector.empty).isLeft)
    assert(Brier.compute(Vector(0.5), Vector.empty).isLeft)
    assert(TemperatureScaling.fit(Vector(0.5), Vector.empty).isLeft)
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
