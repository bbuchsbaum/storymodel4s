package storymodel4s.features

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.core.*

/** A sample inside a reduction: its position on the axis (token index or ordinal), its estimate,
  * and a nonnegative weight.
  */
final case class Sample[+V](position: Int, estimate: Estimate[V], weight: Double)

/** Kernel shapes for smoothing reducers; `bandwidth` is in axis positions. */
enum KernelShape:
  case Rectangular(bandwidth: Double)
  case Triangular(bandwidth: Double)
  case Gaussian(bandwidth: Double)

  def bw: Double = this match
    case Rectangular(b) => b
    case Triangular(b)  => b
    case Gaussian(b)    => b

  def name: String = this match
    case _: Rectangular => "rectangular"
    case _: Triangular  => "triangular"
    case _: Gaussian    => "gaussian"

  /** Weight at signed distance `d` from the window centre. A zero bandwidth is a point mass at the
    * centre, so smoothing with it is the identity.
    */
  def weight(d: Double): Double =
    val b = bw
    if b <= 0.0 then (if d == 0.0 then 1.0 else 0.0)
    else
      this match
        case _: Rectangular => if math.abs(d) <= b then 1.0 else 0.0
        case _: Triangular  => math.max(0.0, 1.0 - math.abs(d) / b)
        case _: Gaussian    => math.exp(-(d * d) / (2.0 * b * b))

/** Declared scalar reducers. Sum and mean answer different questions and are never conflated. */
enum ScalarReducer:
  case Sum, Mean, WeightedMean, Maximum, Variance, Slope
  case Kernel(shape: KernelShape)

  def id: ReducerId = ReducerId.unsafe(this match
    case Kernel(s) => s"kernel-${s.name}-${s.bw}"
    case other     => other.toString.toLowerCase)

  def weighting: WeightingPolicy = this match
    case Kernel(s)    => WeightingPolicy.Kernel(s.name, s.bw)
    case WeightedMean => WeightingPolicy.Provided("sample weights")
    case _            => WeightingPolicy.Uniform

/** Reduces a nonempty set of samples to one estimate. Implementations must return `Missing` (never
  * zero) when no sample is observed.
  */
trait WindowReducer[V, O]:
  def reduce(samples: NonEmptyVector[Sample[V]]): Estimate[O]

object WindowReducer:
  private def observed(samples: NonEmptyVector[Sample[Double]]): Vector[Sample[Double]] =
    samples.toVector.filter(_.estimate.isObserved)

  private def values(obs: Vector[Sample[Double]]): Vector[Double] =
    obs.flatMap(_.estimate.toOption)

  /** Population variance. */
  private def variance(xs: Vector[Double]): Double =
    val m = xs.sum / xs.size
    xs.map(x => (x - m) * (x - m)).sum / xs.size

  /** Ordinary least-squares slope of value against position. */
  private def slope(obs: Vector[Sample[Double]]): Option[Double] =
    val xs = obs.map(_.position.toDouble)
    val ys = values(obs)
    if xs.distinct.size < 2 then None
    else
      val mx = xs.sum / xs.size
      val my = ys.sum / ys.size
      val sxx = xs.map(x => (x - mx) * (x - mx)).sum
      val sxy = xs.zip(ys).map((x, y) => (x - mx) * (y - my)).sum
      Some(sxy / sxx)

  def scalar(reducer: ScalarReducer): WindowReducer[Double, Double] =
    (samples: NonEmptyVector[Sample[Double]]) =>
      val obs = observed(samples)
      if obs.isEmpty then Estimate.Missing(MissingReason.Excluded)
      else
        val vs = values(obs)
        reducer match
          case ScalarReducer.Sum      => Estimate.observed(vs.sum)
          case ScalarReducer.Mean     => Estimate.observed(vs.sum / vs.size)
          case ScalarReducer.Maximum  => Estimate.observed(vs.max)
          case ScalarReducer.Variance => Estimate.observed(variance(vs))
          case ScalarReducer.Slope    =>
            slope(obs).fold[Estimate[Double]](Estimate.Missing(MissingReason.Excluded))(
              Estimate.observed
            )
          case ScalarReducer.WeightedMean =>
            val w = obs.map(_.weight)
            val tw = w.sum
            if tw <= 0.0 then Estimate.Missing(MissingReason.Excluded)
            else Estimate.observed(vs.zip(w).map(_ * _).sum / tw)
          case ScalarReducer.Kernel(shape) =>
            val positions = samples.toVector.map(_.position)
            val centre = (positions.min + positions.max) / 2.0
            val weighted = obs.map(s => (s, shape.weight(s.position - centre)))
            val tw = weighted.map(_._2).sum
            if tw <= 0.0 then
              // zero bandwidth with no sample exactly at the centre: nearest observed sample
              val nearest = obs.minBy(s => math.abs(s.position - centre))
              nearest.estimate
            else Estimate.observed(weighted.map((s, w) => s.estimate.toOption.get * w).sum / tw)

/** Windowed reduction of a token-aligned scalar track into a window-aligned track.
  *
  * Every output retains the window's exact support and its coverage (eligible basis tokens vs.
  * observed values). Missing values are never coerced to zero.
  */
object Windowed:
  val implementationVersion = "windowed-1"

  def apply(
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      plan: WindowPlan,
      reducer: ScalarReducer,
      missing: MissingValuePolicy
  ): Either[DomainError, FeatureTrack[FeatureTarget.Window, Double]] =
    val derivation = FeatureDerivation(
      NonEmptyVector.one(track.space.id),
      Some(plan),
      reducer.id,
      reducer.weighting,
      missing,
      None,
      implementationVersion
    )
    val red = WindowReducer.scalar(reducer)
    val outs = sequence.windows(plan).toVector.traverse { w =>
      val eligible = w.tokenRange.indices.toVector.filter { i =>
        plan.basis match
          case WindowBasis.LexicalTokens => sequence.tokens(i).isLexical
          case _                         => true
      }
      val samples = eligible.map { i =>
        val est = track
          .get(FeatureTarget.Token(TokenIndex.unsafe(i)))
          .getOrElse(Estimate.Missing(MissingReason.Unknown))
        Sample(i, est, 1.0)
      }
      Reduction.reduce(samples, red, missing).map { (est, cov) =>
        FeatureObservation[FeatureTarget.Window, Double](
          FeatureTarget.Window(w.tokenRange),
          est,
          Some(w.support),
          Some(cov)
        )
      }
    }
    outs.map { obs =>
      FeatureTrack(
        outputSpace(
          track.space,
          derivation,
          s"${track.space.description} — ${plan.canonicalString} ${reducer.id.value}"
        ),
        obs.sortBy(o => o.target: FeatureTarget),
        Some(derivation),
        track.provenance
      )
    }

  private[features] def outputSpace(
      in: FeatureSpace[Double],
      d: FeatureDerivation,
      description: String
  ): FeatureSpace[Double] =
    FeatureSpace(
      d.outputSpaceId,
      description,
      FeatureValueSchema.Scalar(in.units),
      in.units,
      in.provider,
      d.normalization.isDefined,
      in.normalizationPopulation
    )

/** Shared reduction step with the missing-value policy and coverage bookkeeping. */
object Reduction:
  def reduce[V, O](
      samples: Vector[Sample[V]],
      reducer: WindowReducer[V, O],
      missing: MissingValuePolicy
  ): Either[DomainError, (Estimate[O], Coverage)] =
    val cov = Coverage(samples.size, samples.count(_.estimate.isObserved))
    NonEmptyVector.fromVector(samples) match
      case None      => Right((Estimate.Missing(MissingReason.Excluded), cov))
      case Some(nev) =>
        missing match
          case MissingValuePolicy.Fail if cov.missing > 0 =>
            Left(
              DomainError.InvariantViolation("features/reduce", s"${cov.missing} missing samples")
            )
          case MissingValuePolicy.RequireMinCoverage(f) if cov.fraction < f =>
            Right((Estimate.Missing(MissingReason.Excluded), cov))
          case _ => Right((reducer.reduce(nev), cov))

/** Aggregation of a token-aligned track over arbitrary (possibly discontinuous) supports, e.g. a
  * situation's or scene's `SpanSet`.
  */
object Aggregate:
  val implementationVersion = "aggregate-1"

  def overTargets[T <: FeatureTarget](
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      targets: Vector[(T, SpanSet)],
      reducer: ScalarReducer,
      missing: MissingValuePolicy,
      lexicalOnly: Boolean = true
  ): Either[DomainError, FeatureTrack[T, Double]] =
    val derivation = FeatureDerivation(
      NonEmptyVector.one(track.space.id),
      None,
      reducer.id,
      reducer.weighting,
      missing,
      None,
      implementationVersion
    )
    val red = WindowReducer.scalar(reducer)
    targets
      .traverse { (t, support) =>
        val idx = sequence
          .coveringIndices(support)
          .filter(i => !lexicalOnly || sequence.tokens(i.value).isLexical)
        val samples = idx.map { i =>
          val est =
            track.get(FeatureTarget.Token(i)).getOrElse(Estimate.Missing(MissingReason.Unknown))
          Sample(i.value, est, 1.0)
        }
        Reduction
          .reduce(samples, red, missing)
          .map((est, cov) => FeatureObservation(t, est, Some(support), Some(cov)))
      }
      .map { obs =>
        FeatureTrack(
          Windowed.outputSpace(
            track.space,
            derivation,
            s"${track.space.description} — aggregate ${reducer.id.value}"
          ),
          obs.sortBy(o => o.target: FeatureTarget),
          Some(derivation),
          track.provenance
        )
      }
