package storymodel4s.align

import storymodel4s.features.Estimate

/** Metric refusal preserves the distinction between an absent value and an invalid measurement. */
enum EmbeddingMetricRefusal:
  case NonFinite, OutsideCosineRange

/** Explicit cosine units prevent a raw [0,2] distance being silently clamped into strict [0,1].
  * These declarations grant neither strict-channel registration nor provider execution authority.
  */
enum EmbeddingMetric:
  /** Preserve the historical raw cosine scale for parity comparisons. */
  case CosineDistance

  /** Explicit linear normalization: equal, orthogonal and opposite vectors give 0, 0.5 and 1. */
  case HalfCosineDistance

  def minimum: Double = 0.0
  def maximum: Double = this match
    case CosineDistance     => 2.0
    case HalfCosineDistance => 1.0
  def smallerIsCloser: Boolean = true
  def tag: String = this match
    case CosineDistance     => "cosine-distance/v1"
    case HalfCosineDistance => "half-cosine-distance/v1"

  /** Accept only finite raw cosine distances in [0,2], without clamping or changing units silently.
    */
  def fromCosine(
      distance: Double
  ): Either[EmbeddingMetricRefusal, EmbeddingMetric.CosineDistanceValue] =
    if !distance.isFinite then Left(EmbeddingMetricRefusal.NonFinite)
    else if distance < 0.0 || distance > 2.0 then Left(EmbeddingMetricRefusal.OutsideCosineRange)
    else
      val value = this match
        case CosineDistance     => distance
        case HalfCosineDistance => distance / 2.0
      Right(new EmbeddingMetric.CosineDistanceValue(this, value))

  /** Conversion preserves eligible missing, ineligible, and observation credence separately. */
  def fromCosineEstimate(
      estimate: Estimate[Double]
  ): Either[EmbeddingMetricRefusal, Estimate[EmbeddingMetric.CosineDistanceValue]] = estimate match
    case Estimate.Observed(value, credence) =>
      fromCosine(value).map(v => Estimate.Observed(v, credence))
    case Estimate.Missing(reason) => Right(Estimate.Missing(reason))
    case Estimate.Ineligible      => Right(Estimate.Ineligible)

object EmbeddingMetric:
  /** A distance retains its checked scale, so normalized and raw units cannot be conflated. */
  final class CosineDistanceValue private[EmbeddingMetric] (
      val metric: EmbeddingMetric,
      val value: Double
  ):
    override def equals(other: Any): Boolean = other match
      case d: CosineDistanceValue => metric == d.metric && value == d.value
      case _                      => false
    override def hashCode: Int = (metric, value).hashCode
    override def toString: String = s"CosineDistanceValue(${metric.tag}, $value)"
