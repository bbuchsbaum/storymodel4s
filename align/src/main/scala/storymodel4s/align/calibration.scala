package storymodel4s.align

import storymodel4s.core.{Probability, StoryId}

/** A fitted mapping from raw scores to probabilities, named so credences can cite it. */
trait CalibrationModel:
  def name: String
  def apply(score: Double): Probability

/** Fits a [[CalibrationModel]] from `(score, label)` pairs. */
trait Calibrator:
  def fit(scores: Vector[Double], labels: Vector[Boolean]): CalibrationModel

private[align] object Logistic:
  def sigmoid(x: Double): Double = 1.0 / (1.0 + math.exp(-x))
  def clampP(p: Double): Double = math.max(1e-9, math.min(1.0 - 1e-9, p))
  def nll(ps: Vector[Double], labels: Vector[Boolean]): Double =
    ps.zip(labels)
      .map { case (p, y) =>
        val q = clampP(p)
        if y then -math.log(q) else -math.log(1.0 - q)
      }
      .sum

/** Temperature scaling on logits: `p = σ(logit(score) / T)`, `T` chosen by grid search on NLL.
  * Scores are treated as unnormalized probabilities in `(0, 1)`.
  */
object TemperatureScaling extends Calibrator:
  private val grid: Vector[Double] = (1 to 60).toVector.map(i => math.exp((i - 30) / 10.0))

  def fit(scores: Vector[Double], labels: Vector[Boolean]): CalibrationModel =
    require(scores.size == labels.size, "scores and labels must align")
    val logits = scores.map(s => math.log(Logistic.clampP(s) / (1.0 - Logistic.clampP(s))))
    val best = grid.minBy(t => Logistic.nll(logits.map(l => Logistic.sigmoid(l / t)), labels))
    new CalibrationModel:
      val name = f"temperature(T=$best%.4f)"
      def apply(score: Double): Probability =
        val l = math.log(Logistic.clampP(score) / (1.0 - Logistic.clampP(score)))
        Probability.unsafe(Logistic.sigmoid(l / best))

/** Platt scaling: `p = σ(a·score + b)` fitted by gradient descent on NLL. */
final case class PlattScaling(steps: Int = 2000, learningRate: Double = 0.1) extends Calibrator:
  def fit(scores: Vector[Double], labels: Vector[Boolean]): CalibrationModel =
    require(scores.size == labels.size, "scores and labels must align")
    var a = 1.0
    var b = 0.0
    val n = math.max(1, scores.size)
    var i = 0
    while i < steps do
      var ga = 0.0
      var gb = 0.0
      scores.zip(labels).foreach { case (s, y) =>
        val p = Logistic.sigmoid(a * s + b)
        val err = p - (if y then 1.0 else 0.0)
        ga += err * s
        gb += err
      }
      a -= learningRate * ga / n
      b -= learningRate * gb / n
      i += 1
    val (fa, fb) = (a, b)
    new CalibrationModel:
      val name = f"platt(a=$fa%.4f,b=$fb%.4f)"
      def apply(score: Double): Probability = Probability.unsafe(Logistic.sigmoid(fa * score + fb))

/** Expected calibration error over equal-width bins. */
object Ece:
  def compute(probabilities: Vector[Double], labels: Vector[Boolean], bins: Int = 10): Double =
    require(probabilities.size == labels.size, "probabilities and labels must align")
    val n = probabilities.size
    if n == 0 then 0.0
    else
      val grouped = probabilities.zip(labels).groupBy { case (p, _) =>
        math.min(bins - 1, math.max(0, (p * bins).toInt))
      }
      grouped.values.map { xs =>
        val conf = xs.map(_._1).sum / xs.size
        val acc = xs.count(_._2).toDouble / xs.size
        (xs.size.toDouble / n) * math.abs(acc - conf)
      }.sum

object Brier:
  def compute(probabilities: Vector[Double], labels: Vector[Boolean]): Double =
    require(probabilities.size == labels.size, "probabilities and labels must align")
    if probabilities.isEmpty then 0.0
    else
      probabilities
        .zip(labels)
        .map { case (p, y) =>
          val d = p - (if y then 1.0 else 0.0)
          d * d
        }
        .sum / probabilities.size

/** Leave-story-out folds: each fold holds out every item from one story. Generalization must be
  * measured across stories, not merely across subjects.
  */
object LeaveStoryOut:
  final case class Fold(heldOut: StoryId, train: Vector[Int], test: Vector[Int])

  def folds(storyOfItem: Vector[StoryId]): Vector[Fold] =
    storyOfItem.distinct.sorted.map { s =>
      val idx = storyOfItem.zipWithIndex
      Fold(
        s,
        idx.collect { case (x, i) if x != s => i },
        idx.collect { case (x, i) if x == s => i }
      )
    }
