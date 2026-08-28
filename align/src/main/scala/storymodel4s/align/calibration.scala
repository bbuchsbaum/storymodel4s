package storymodel4s.align

import storymodel4s.core.{Probability, StoryId}

/** A fitted mapping from raw scores to probabilities, named so credences can cite it. */
trait CalibrationModel:
  def name: String
  def apply(score: Double): Probability

/** Fits a [[CalibrationModel]] from `(score, label)` pairs. */
trait Calibrator:
  def fit(scores: Vector[Double], labels: Vector[Boolean]): Either[AlignError, CalibrationModel]

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
  * Scores are treated as unnormalized probabilities in `(0, 1)`. The grid contains `T = 1`, so the
  * fitted model never has worse NLL than the identity on the fitting data; nothing else (Brier,
  * ECE) is guaranteed.
  */
object TemperatureScaling extends Calibrator:
  private val grid: Vector[Double] = (1 to 60).toVector.map(i => math.exp((i - 30) / 10.0))

  def fit(scores: Vector[Double], labels: Vector[Boolean]): Either[AlignError, CalibrationModel] =
    if scores.size != labels.size then Left(AlignError.SizeMismatch("scores and labels must align"))
    else
      val logits = scores.map(s => math.log(Logistic.clampP(s) / (1.0 - Logistic.clampP(s))))
      val best = grid.minBy(t => Logistic.nll(logits.map(l => Logistic.sigmoid(l / t)), labels))
      Right(new CalibrationModel:
        val name = f"temperature(T=$best%.4f)"
        def apply(score: Double): Probability =
          val l = math.log(Logistic.clampP(score) / (1.0 - Logistic.clampP(score)))
          Probability.unsafe(Logistic.sigmoid(l / best)))

/** Platt scaling: `p = σ(a·score + b)` fitted by gradient descent on NLL. */
final case class PlattScaling(steps: Int = 2000, learningRate: Double = 0.1) extends Calibrator:
  def fit(scores: Vector[Double], labels: Vector[Boolean]): Either[AlignError, CalibrationModel] =
    if scores.size != labels.size then Left(AlignError.SizeMismatch("scores and labels must align"))
    else
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
      Right(new CalibrationModel:
        val name = f"platt(a=$fa%.4f,b=$fb%.4f)"
        def apply(score: Double): Probability =
          Probability.unsafe(Logistic.sigmoid(fa * score + fb)))

/** How expected-calibration-error bins are formed. Equal-width bins are the textbook choice;
  * equal-mass bins are more informative on skewed score distributions. Empty bins contribute 0.
  */
enum EceBinning:
  case EqualWidth, EqualMass

/** Expected calibration error. */
object Ece:
  def compute(
      probabilities: Vector[Double],
      labels: Vector[Boolean],
      bins: Int = 10,
      binning: EceBinning = EceBinning.EqualWidth
  ): Either[AlignError, Double] =
    if probabilities.size != labels.size then
      Left(AlignError.SizeMismatch("probabilities and labels must align"))
    else if probabilities.isEmpty then Right(0.0)
    else
      val n = probabilities.size
      val pairs = probabilities.zip(labels)
      val grouped: Iterable[Vector[(Double, Boolean)]] = binning match
        case EceBinning.EqualWidth =>
          pairs.groupBy { case (p, _) => math.min(bins - 1, math.max(0, (p * bins).toInt)) }.values
        case EceBinning.EqualMass =>
          val sorted = pairs.sortBy(_._1)
          val size = math.max(1, math.ceil(n.toDouble / bins).toInt)
          sorted.grouped(size).toVector
      Right(grouped.map { xs =>
        val conf = xs.map(_._1).sum / xs.size
        val acc = xs.count(_._2).toDouble / xs.size
        (xs.size.toDouble / n) * math.abs(acc - conf)
      }.sum)

object Brier:
  def compute(probabilities: Vector[Double], labels: Vector[Boolean]): Either[AlignError, Double] =
    if probabilities.size != labels.size then
      Left(AlignError.SizeMismatch("probabilities and labels must align"))
    else if probabilities.isEmpty then Right(0.0)
    else
      Right(
        probabilities
          .zip(labels)
          .map { case (p, y) =>
            val d = p - (if y then 1.0 else 0.0)
            d * d
          }
          .sum / probabilities.size
      )

/** Negative log-likelihood of labels under probabilities (the quantity temperature scaling fits).
  */
object Nll:
  def compute(probabilities: Vector[Double], labels: Vector[Boolean]): Either[AlignError, Double] =
    if probabilities.size != labels.size then
      Left(AlignError.SizeMismatch("probabilities and labels must align"))
    else Right(Logistic.nll(probabilities, labels))

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
