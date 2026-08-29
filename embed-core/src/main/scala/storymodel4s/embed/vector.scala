package storymodel4s.embed

import storymodel4s.features.{Estimate, MissingReason}

/** A vector whose dimension, finiteness and declared normalization were checked at construction.
  *
  * Why: a raw `Array[Double]` of the wrong length or with a NaN would poison every downstream
  * distance silently; checking once here means distances never clamp or guess.
  */
final class ValidatedVector private (
    val dimension: Dimension,
    val normalization: Normalization,
    val values: Vector[Double]
):
  def norm: Double = math.sqrt(values.foldLeft(0.0)((acc, v) => acc + v * v))

  /** Dot product; both vectors must share a dimension (checked). */
  def dot(o: ValidatedVector): Either[EmbedError, Double] =
    if o.dimension != dimension then
      Left(EmbedError.DimensionMismatch(dimension.value, o.dimension.value))
    else
      var acc = 0.0
      var i = 0
      while i < values.length do
        acc += values(i) * o.values(i)
        i += 1
      Right(acc)

  /** Matryoshka truncation followed by L2 re-normalization; the caller records the derived space.
    */
  def truncated(dim: Dimension): Either[EmbedError, ValidatedVector] =
    if dim.value >= dimension.value then
      Left(
        EmbedError.InvalidRecipe(s"truncation ${dim.value} must be smaller than ${dimension.value}")
      )
    else
      val head = values.take(dim.value)
      val n = math.sqrt(head.foldLeft(0.0)((a, v) => a + v * v))
      if n == 0.0 then Left(EmbedError.NotNormalized(0.0, Normalization.L2))
      else ValidatedVector.of(dim, Normalization.L2, head.map(_ / n))

  override def equals(other: Any): Boolean = other match
    case that: ValidatedVector =>
      dimension == that.dimension &&
      normalization == that.normalization &&
      values == that.values
    case _ => false

  override def hashCode(): Int = (dimension, normalization, values).hashCode

  override def toString: String =
    s"ValidatedVector(dimension=${dimension.value}, normalization=${normalization.render}, " +
      s"size=${values.size})"

object ValidatedVector:
  /** Relative tolerance on the unit norm for `L2` vectors.
    *
    * '''It defends a FLOAT32 ROUND TRIP, not float64 arithmetic''', and that is the whole
    * reason for its magnitude. `Dtype.Float32` is a real storage type here
    * (`codec/sidecar.scala:953`, `:978`, `:1068`), so a unit vector written as float32 and
    * widened back to double does not return with norm exactly `1.0` — it returns a few
    * float32 epsilons away. float32 epsilon is `1.1920929e-07`, so `1e-6` is roughly `8.4x`
    * headroom: an ordinary margin, not slack.
    *
    * Stated because the value looks indefensible if you compute against the wrong precision.
    * Accumulated error in a float64 L2 normalization is nearer `1e-15`, which makes `1e-6`
    * appear nine orders of magnitude too wide — and the chief proposed tightening it on
    * exactly that reasoning (2026-08-29) before measuring the premise rather than the
    * arithmetic.
    *
    * It also currently protects a path no caller takes: every `L2` construction in `main`
    * either divides by the norm first ([[l2]], and `:40`) or routes through `l2`. A constant
    * defending an unexercised path, with no statement of what it defends, is the shape that
    * invites a wrong "cleanup". Do not tighten it without first measuring the precision of
    * whatever provider is actually supplying vectors.
    *
    * NOT TRACED: that a decoded float32 sidecar row reaches [[of]] with `Normalization.L2`
    * today. float32 storage exists and this is the right order for it; the end-to-end path is
    * unverified. Permits, not occurs.
    */
  val NormTolerance: Double = 1e-6

  def of(
      dimension: Dimension,
      normalization: Normalization,
      values: Vector[Double]
  ): Either[EmbedError, ValidatedVector] =
    if values.length != dimension.value then
      Left(EmbedError.DimensionMismatch(dimension.value, values.length))
    else
      val bad = values.indexWhere(v => v.isNaN || v.isInfinite)
      if bad >= 0 then Left(EmbedError.NonFiniteValue(bad))
      else
        normalization match
          case Normalization.Unnormalized =>
            Right(new ValidatedVector(dimension, normalization, values))
          case Normalization.L2 =>
            val n = math.sqrt(values.foldLeft(0.0)((acc, x) => acc + x * x))
            // Fail-closed under NaN: abs(NaN - 1) <= tol is false, so the reject branch holds.
            // Absorb slack so Normalization.L2 is a claim the stored coordinates satisfy, not a
            // label on a near-miss. Do not change NormTolerance here.
            // `1.0 - NormTolerance` is not Tolerance away from 1 in IEEE; fold one ulp of 1
            // so the constructed literal is the stated boundary. `1.0 + 2*NormTolerance`
            // still misses.
            if math.abs(n - 1.0) <= NormTolerance + math.ulp(1.0) then
              Right(new ValidatedVector(dimension, normalization, values.map(_ / n)))
            else Left(EmbedError.NotNormalized(n, Normalization.L2))

  /** Normalize to unit length and validate; zero vectors are rejected. */
  def l2(dimension: Dimension, values: Vector[Double]): Either[EmbedError, ValidatedVector] =
    val bad = values.indexWhere(v => v.isNaN || v.isInfinite)
    if bad >= 0 then Left(EmbedError.NonFiniteValue(bad))
    else
      val n = math.sqrt(values.foldLeft(0.0)((a, x) => a + x * x))
      if n == 0.0 then Left(EmbedError.NotNormalized(0.0, Normalization.L2))
      else of(dimension, Normalization.L2, values.map(_ / n))

/** A finite, non-negative distance. Cosine distance lies in [0, 2]; euclidean in [0, ∞). */
object ValidatedDistance:
  opaque type ValidatedDistance = Double
  def of(d: Double): Either[EmbedError, ValidatedDistance] =
    if d.isNaN || d.isInfinite || d < 0.0 then Left(EmbedError.InvalidDistance(d)) else Right(d)
  extension (d: ValidatedDistance) def value: Double = d
type ValidatedDistance = ValidatedDistance.ValidatedDistance

/** Distances over validated vectors. Abstention on either side is `Estimate.Missing`, never a
  * substituted number.
  */
object Distances:
  def cosine(a: ValidatedVector, b: ValidatedVector): Either[EmbedError, ValidatedDistance] =
    a.dot(b).flatMap { d =>
      val denom = a.norm * b.norm
      if denom == 0.0 then Left(EmbedError.InvalidDistance(Double.NaN))
      else
        val cos = math.max(-1.0, math.min(1.0, d / denom))
        ValidatedDistance.of(1.0 - cos)
    }

  def euclidean(a: ValidatedVector, b: ValidatedVector): Either[EmbedError, ValidatedDistance] =
    if a.dimension != b.dimension then
      Left(EmbedError.DimensionMismatch(a.dimension.value, b.dimension.value))
    else
      var acc = 0.0
      var i = 0
      while i < a.values.length do
        val diff = a.values(i) - b.values(i)
        acc += diff * diff
        i += 1
      ValidatedDistance.of(math.sqrt(acc))

  /** Lift a distance over estimates: either side Missing ⇒ Missing (its reason); an invalid pair
    * (dimension mismatch) is `Missing(Undefined)` rather than an exception.
    */
  def cosineEstimate(
      a: Estimate[ValidatedVector],
      b: Estimate[ValidatedVector]
  ): Estimate[ValidatedDistance] =
    (a, b) match
      case (Estimate.Observed(x, _), Estimate.Observed(y, _)) =>
        cosine(x, y).fold(
          _ =>
            Estimate.Missing(
              MissingReason.Undefined(storymodel4s.features.UndefinedReason.NotFinite)
            ),
          Estimate.observed
        )
      case (Estimate.Missing(r), _) => Estimate.Missing(r)
      case (_, Estimate.Missing(r)) => Estimate.Missing(r)
