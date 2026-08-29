package storymodel4s.features

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.core.*

/** A sample inside a reduction: its position on the axis (token index or ordinal), its estimate,
  * and a nonnegative finite weight. Weights are checked by [[Reduction.reduce]].
  */
final case class Sample[+V](position: Int, estimate: Estimate[V], weight: Double):
  def hasValidWeight: Boolean = Estimate.isFinite(weight) && weight >= 0.0

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

  /** A zero bandwidth is a declared point mass: the value at (or nearest to) the centre. */
  def isPointMass: Boolean = bw <= 0.0

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
    case Kernel(s) => s"kernel-${s.name}-${CanonicalDouble.render(s.bw)}"
    case other     => other.toString.toLowerCase)

  def weighting: WeightingPolicy = this match
    case Kernel(s)    => WeightingPolicy.Kernel(s.name, s.bw)
    case WeightedMean => WeightingPolicy.Provided("sample weights")
    case _            => WeightingPolicy.Uniform

/** Reduces a nonempty set of samples to one estimate. Implementations must return `Missing` (never
  * zero) when no sample is observed, and must say why.
  */
trait WindowReducer[V, O]:
  def reduce(samples: NonEmptyVector[Sample[V]]): Estimate[O]

object WindowReducer:
  /** Observed samples with finite values; non-finite observations are treated as absent. */
  private def observed(samples: NonEmptyVector[Sample[Double]]): Vector[Sample[Double]] =
    samples.toVector.filter(s => Estimate.finite(s.estimate).isDefined)

  /** Observed samples paired with their finite values, or the `Missing` that explains why there are
    * none (`NotFinite` when something was observed but nothing finite).
    */
  private def finiteSamples(
      samples: NonEmptyVector[Sample[Double]]
  ): Either[Estimate[Double], Vector[(Sample[Double], Double)]] =
    val obs = samples.toVector.flatMap(s => Estimate.finite(s.estimate).map(v => (s, v)))
    if obs.nonEmpty then Right(obs)
    else if samples.exists(_.estimate.isObserved) then Left(undefined(UndefinedReason.NotFinite))
    else Left(Estimate.Missing(MissingReason.AllMissing))

  private def values(obs: Vector[Sample[Double]]): Vector[Double] =
    obs.flatMap(s => Estimate.finite(s.estimate))

  private def undefined(r: UndefinedReason): Estimate[Double] =
    Estimate.Missing(MissingReason.Undefined(r))

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

  /** Same weight contract as `Reduction.reduce`. The public `reduce` door must refuse here;
    * pointing at the gated caller does not close this door.
    */
  private def refuseInvalidWeights[V, O](
      samples: NonEmptyVector[Sample[V]]
  ): Option[Estimate[O]] =
    if samples.exists(!_.hasValidWeight) then
      Some(
        Estimate.Missing(
          MissingReason.Undefined(UndefinedReason.Custom("features", "invalid-sample-weight"))
        )
      )
    else None

  /** Input weights can each be finite and still overflow the total or the quotient.
    * `Estimate.observed` does not validate finiteness; refuse rather than publish NaN.
    */
  private def finiteWeightedMean(
      values: Vector[Double],
      weights: Vector[Double]
  ): Estimate[Double] =
    val tw = weights.sum
    if !Estimate.isFinite(tw) then undefined(UndefinedReason.NotFinite)
    else if tw > 0.0 then
      val q = values.zip(weights).map(_ * _).sum / tw
      if Estimate.isFinite(q) then Estimate.observed(q)
      else undefined(UndefinedReason.NotFinite)
    else undefined(UndefinedReason.ZeroTotalWeight)

  def scalar(reducer: ScalarReducer): WindowReducer[Double, Double] =
    (samples: NonEmptyVector[Sample[Double]]) =>
      refuseInvalidWeights[Double, Double](samples).getOrElse {
        val obs = observed(samples)
        if obs.isEmpty then
          val anyNonFinite = samples.exists(s => s.estimate.isObserved)
          if anyNonFinite then undefined(UndefinedReason.NotFinite)
          else Estimate.Missing(MissingReason.AllMissing)
        else
          val vs = values(obs)
          reducer match
            case ScalarReducer.Sum      => Estimate.observed(vs.sum)
            case ScalarReducer.Mean     => Estimate.observed(vs.sum / vs.size)
            case ScalarReducer.Maximum  => Estimate.observed(vs.max)
            case ScalarReducer.Variance => Estimate.observed(variance(vs))
            case ScalarReducer.Slope    =>
              slope(obs).fold(undefined(UndefinedReason.SlopeNeedsTwoPositions))(Estimate.observed)
            case ScalarReducer.WeightedMean =>
              finiteWeightedMean(vs, obs.map(_.weight))
            case ScalarReducer.Kernel(shape) =>
              val positions = samples.toVector.map(_.position)
              val centre = (positions.min + positions.max) / 2.0
              val weighted = obs.map(s => (s, shape.weight(s.position - centre)))
              val tw = weighted.map(_._2).sum
              if !Estimate.isFinite(tw) then undefined(UndefinedReason.NotFinite)
              else if tw > 0.0 then
                val q = weighted.map((s, w) => Estimate.finite(s.estimate).get * w).sum / tw
                if Estimate.isFinite(q) then Estimate.observed(q)
                else undefined(UndefinedReason.NotFinite)
              else if shape.isPointMass then
                // a declared point mass with no sample exactly at the centre (even-length window):
                // the nearest observed sample is the value at the centre
                obs.minBy(s => math.abs(s.position - centre)).estimate
              else
                // positive bandwidth but every observed sample lies outside the kernel support:
                // never substitute a value from outside the support
                undefined(UndefinedReason.OutsideKernelSupport)
      }

  /** Kernel smoothing where the distance of a sample from the window centre is supplied by the
    * caller (`distance`, in basis positions) instead of read off `Sample.position`: the reducer for
    * centred windows over narrative units, whose bandwidth counts units, not tokens. A declared
    * point mass whose centre has no observed sample yields the mean of every observed sample at the
    * minimal distance (the nearest unit), never a single arbitrary sample.
    */
  private[features] def kernelAt(
      shape: KernelShape,
      distance: Sample[Double] => Double
  ): WindowReducer[Double, Double] =
    (samples: NonEmptyVector[Sample[Double]]) =>
      refuseInvalidWeights[Double, Double](samples).getOrElse {
        finiteSamples(samples) match
          case Left(missing) => missing
          case Right(obs)    =>
            val weighted = obs.map((s, v) => (v, shape.weight(distance(s))))
            finiteWeightedMean(weighted.map(_._1), weighted.map(_._2)) match
              case Estimate.Missing(MissingReason.Undefined(UndefinedReason.ZeroTotalWeight))
                  if shape.isPointMass =>
                // a declared point mass whose centre unit has no observed sample: the mean of all
                // observed samples at the minimal distance, i.e. the whole nearest unit
                val nearest = obs.map((s, _) => distance(s)).min
                val vs = obs.collect { case (s, v) if distance(s) == nearest => v }
                Estimate.observed(vs.sum / vs.size)
              case Estimate.Missing(MissingReason.Undefined(UndefinedReason.ZeroTotalWeight)) =>
                undefined(UndefinedReason.OutsideKernelSupport)
              case other => other
      }

/** Windowed reduction of a token-aligned scalar track into a window-aligned track.
  *
  * Every output retains the window's exact support and its coverage (eligible basis tokens vs.
  * observed values). Missing values are never coerced to zero. Eligibility is lexical by default
  * regardless of the window basis: punctuation never deflates coverage unless the caller declares
  * `Eligibility.AllTokens`.
  */
object Windowed:
  val implementationVersion = "windowed-2"

  def apply(
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      plan: WindowPlan,
      reducer: ScalarReducer,
      missing: MissingValuePolicy,
      eligibility: Eligibility = Eligibility.LexicalTokens
  ): Either[DomainError, FeatureTrack[FeatureTarget.Window, Double]] =
    val derivation = FeatureDerivation.unsafe(
      NonEmptyVector.one(track.space.id),
      Some(plan),
      reducer.id,
      reducer.weighting,
      missing,
      None,
      implementationVersion,
      eligibility,
      Some(TargetFamily.Window)
    )
    val red = WindowReducer.scalar(reducer)
    val outs = sequence.windows(plan).toVector.traverse { w =>
      val eligible = w.tokenRange.indices.toVector.filter { i =>
        eligibility match
          case Eligibility.LexicalTokens => sequence.tokens(i).isLexical
          case Eligibility.AllTokens     => true
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

  /** Implementation version recorded in narrative-window recipes; bump whenever
    * [[Windowed.overBasis]] changes any output value, so old derivation ids never alias new ones.
    */
  val narrativeImplementationVersion = "narrative-windowed-1"

  /** Centred windows of `±plan.halfWidth` units over a [[NarrativeBasis]]: one observation per unit
    * (an event or a scene), whose support is the union of the member units' supports and whose
    * coverage counts the eligible tokens of that union once. Windows are clipped at the ends of the
    * basis.
    *
    * Decision: a kernel's distance for a token is the offset (in units) of the nearest member unit
    * containing it, so bandwidths count events or scenes, not tokens; `Slope` stays over token
    * position (discourse order).
    */
  def overBasis[T <: FeatureTarget](
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      basis: NarrativeBasis[T],
      plan: NarrativeWindowPlan,
      reducer: ScalarReducer,
      missing: MissingValuePolicy,
      eligibility: Eligibility = Eligibility.LexicalTokens
  ): Either[DomainError, FeatureTrack[T, Double]] =
    val basisId = basis.basisId
    val derivation = FeatureDerivation.unsafe(
      NonEmptyVector.one(track.space.id),
      None,
      reducer.id,
      reducer.weighting,
      missing,
      None,
      narrativeImplementationVersion,
      eligibility,
      Some(basis.family),
      Some(plan)
    )
    val units = basis.units
    val n = units.size
    val outs = units.indices.toVector.traverse { p =>
      val lo = math.max(0, p - plan.halfWidth)
      val hi = math.min(n - 1, p + plan.halfWidth)
      val members = (lo to hi).toVector.map(i => (math.abs(i - p), units(i)._2))
      // a token's distance from the centre is that of the nearest member unit containing it
      val covered: Vector[(TokenIndex, Int)] =
        members.flatMap((d, sup) => sequence.coveringIndices(sup).map(i => (i, d)))
      val distanceOf: Map[Int, Int] = covered.groupMapReduce(_._1.value)(_._2)(math.min)
      val eligible = covered.map(_._1).distinct.sorted.filter { i =>
        eligibility match
          case Eligibility.LexicalTokens => sequence.tokens(i.value).isLexical
          case Eligibility.AllTokens     => true
      }
      val samples = eligible.map { i =>
        val est = track
          .get(FeatureTarget.Token(i))
          .getOrElse(Estimate.Missing(MissingReason.Unknown))
        Sample(i.value, est, 1.0)
      }
      val red = reducer match
        case ScalarReducer.Kernel(shape) =>
          // every sample position is a key of `distanceOf`: samples come from `eligible`, which is
          // drawn from `covered`, the very pairs `distanceOf` was folded from — the default is
          // unreachable and only keeps the lookup total
          WindowReducer.kernelAt(shape, s => distanceOf.getOrElse(s.position, 0).toDouble)
        case other => WindowReducer.scalar(other)
      val support = SpanSet
        .of(members.flatMap((_, sup) => sup.refs.toVector))
        .toRight(DomainError.InvariantViolation("features/narrative-window", "empty window"))
      for
        sup <- support
        reduced <- Reduction.reduce(samples, red, missing)
      yield FeatureObservation[T, Double](units(p)._1, reduced._1, Some(sup), Some(reduced._2))
    }
    outs.map { obs =>
      FeatureTrack(
        outputSpace(
          track.space,
          derivation,
          s"${track.space.description} — ${plan.canonicalString} ${reducer.id.value}",
          Some(basisId)
        ),
        obs.sortBy(o => o.target: FeatureTarget),
        Some(derivation),
        track.provenance.copy(basisId = Some(basisId))
      )
    }

  private[features] def outputSpace(
      in: FeatureSpace[Double],
      d: FeatureDerivation,
      description: String,
      basisId: Option[BasisId] = None
  ): FeatureSpace[Double] =
    FeatureSpace(
      basisId.fold(d.outputSpaceId)(basis => d.outputSpaceId(basis)),
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
    val cov = Coverage.unsafe(samples.size, samples.count(_.estimate.isObserved))
    if samples.exists(!_.hasValidWeight) then
      Left(
        DomainError.InvariantViolation("features/reduce", "negative or non-finite sample weight")
      )
    else
      NonEmptyVector.fromVector(samples) match
        case None      => Right((Estimate.Missing(MissingReason.AllMissing), cov))
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
  val implementationVersion = "aggregate-2"

  def overTargets[T <: FeatureTarget](
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      targets: Vector[(T, SpanSet)],
      reducer: ScalarReducer,
      missing: MissingValuePolicy,
      lexicalOnly: Boolean = true
  ): Either[DomainError, FeatureTrack[T, Double]] =
    targets.headOption
      .map((t, _) => TargetFamily.of(t))
      .toRight(
        DomainError.InvariantViolation(
          "features/aggregate",
          "empty target basis requires an explicit target family"
        )
      )
      .flatMap(family =>
        overTargets(track, sequence, family, targets, reducer, missing, lexicalOnly)
      )

  /** Aggregate over targets with an explicit family, required when the ordered basis is empty. */
  def overTargets[T <: FeatureTarget](
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      family: TargetFamily,
      targets: Vector[(T, SpanSet)],
      reducer: ScalarReducer,
      missing: MissingValuePolicy
  ): Either[DomainError, FeatureTrack[T, Double]] =
    overTargets(track, sequence, family, targets, reducer, missing, lexicalOnly = true)

  /** Aggregate over an explicit target family with a caller-selected eligibility policy. */
  def overTargets[T <: FeatureTarget](
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      family: TargetFamily,
      targets: Vector[(T, SpanSet)],
      reducer: ScalarReducer,
      missing: MissingValuePolicy,
      lexicalOnly: Boolean
  ): Either[DomainError, FeatureTrack[T, Double]] =
    val eligibility = if lexicalOnly then Eligibility.LexicalTokens else Eligibility.AllTokens
    BasisId
      .of(family, targets.map((t, _) => t: FeatureTarget))
      .flatMap(basisId =>
        aggregate(track, sequence, targets, family, basisId, reducer, missing, eligibility)
      )

  /** Per-unit aggregation over a [[NarrativeBasis]]: one observation per situation or segment, with
    * the unit's exact support and coverage. The recipe records the basis family even when the basis
    * is empty.
    */
  def overBasis[T <: FeatureTarget](
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      basis: NarrativeBasis[T],
      reducer: ScalarReducer,
      missing: MissingValuePolicy,
      eligibility: Eligibility = Eligibility.LexicalTokens
  ): Either[DomainError, FeatureTrack[T, Double]] =
    aggregate(
      track,
      sequence,
      basis.units,
      basis.family,
      basis.basisId,
      reducer,
      missing,
      eligibility
    )

  /** Per-situation (event) aggregation; every id must resolve through `resolver`. */
  def overSituations(
      track: FeatureTrack[FeatureTarget.Token, Double],
      resolver: SupportResolver,
      situations: Vector[SituationId],
      reducer: ScalarReducer,
      missing: MissingValuePolicy,
      eligibility: Eligibility = Eligibility.LexicalTokens
  ): Either[DomainError, FeatureTrack[FeatureTarget.Situation, Double]] =
    NarrativeBasis
      .situations(situations, resolver)
      .flatMap(b => overBasis(track, resolver.sequence, b, reducer, missing, eligibility))

  /** Per-segment (scene) aggregation; every id must resolve through `resolver`. */
  def overSegments(
      track: FeatureTrack[FeatureTarget.Token, Double],
      resolver: SupportResolver,
      segments: Vector[SegmentId],
      reducer: ScalarReducer,
      missing: MissingValuePolicy,
      eligibility: Eligibility = Eligibility.LexicalTokens
  ): Either[DomainError, FeatureTrack[FeatureTarget.Segment, Double]] =
    NarrativeBasis
      .segments(segments, resolver)
      .flatMap(b => overBasis(track, resolver.sequence, b, reducer, missing, eligibility))

  private def aggregate[T <: FeatureTarget](
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      targets: Vector[(T, SpanSet)],
      family: TargetFamily,
      basisId: BasisId,
      reducer: ScalarReducer,
      missing: MissingValuePolicy,
      eligibility: Eligibility
  ): Either[DomainError, FeatureTrack[T, Double]] =
    val lexicalOnly = eligibility == Eligibility.LexicalTokens
    val derivation = FeatureDerivation.unsafe(
      NonEmptyVector.one(track.space.id),
      None,
      reducer.id,
      reducer.weighting,
      missing,
      None,
      implementationVersion,
      eligibility,
      Some(family)
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
            s"${track.space.description} — aggregate ${reducer.id.value}",
            Some(basisId)
          ),
          obs.sortBy(o => o.target: FeatureTarget),
          Some(derivation),
          track.provenance.copy(basisId = Some(basisId))
        )
      }

/** An ordered run of narrative units — situations (events) or segments (scenes) — with their
  * resolved text supports: the axis that [[Aggregate.overBasis]] aggregates per unit and
  * [[Windowed.overBasis]] slides over.
  *
  * Why: core's `WindowBasis` counts surface units the atlas knows about; events and scenes are
  * known only to the story model, so their basis is resolved through a [[SupportResolver]] and
  * carried as data (ADR 0002 §9 checkpoint 2). Units keep the caller's order (the model's discourse
  * order). Decision: an id that does not resolve, or appears twice, is a `DomainError` — never
  * silently dropped, so a derived track always covers exactly the units it was asked for.
  */
final case class NarrativeBasis[T <: FeatureTarget] private (
    family: TargetFamily,
    units: Vector[(T, SpanSet)],
    basisId: BasisId
):
  def size: Int = units.size
  def isEmpty: Boolean = units.isEmpty
  def targets: Vector[T] = units.map(_._1)

object NarrativeBasis:
  def situations(
      ids: Vector[SituationId],
      resolver: SupportResolver
  ): Either[DomainError, NarrativeBasis[FeatureTarget.Situation]] =
    resolve(TargetFamily.Situation, ids.map(FeatureTarget.Situation.apply), resolver)

  def segments(
      ids: Vector[SegmentId],
      resolver: SupportResolver
  ): Either[DomainError, NarrativeBasis[FeatureTarget.Segment]] =
    resolve(TargetFamily.Segment, ids.map(FeatureTarget.Segment.apply), resolver)

  private def resolve[T <: FeatureTarget](
      family: TargetFamily,
      targets: Vector[T],
      resolver: SupportResolver
  ): Either[DomainError, NarrativeBasis[T]] =
    val path = "features/narrative-basis"
    // the first repeated target in basis order, not hash order
    targets.diff(targets.distinct).headOption match
      case Some(dup) =>
        Left(DomainError.DuplicateId("FeatureTarget", FeatureTargetKey.parts(dup).mkString("/")))
      case None =>
        targets
          .traverse { t =>
            resolver
              .support(t)
              .map(sup => (t, sup))
              .toRight(
                DomainError.InvariantViolation(
                  path,
                  s"unresolved ${FeatureTargetKey.parts(t).mkString("/")}"
                )
              )
          }
          .flatMap { units =>
            BasisId
              .of(family, units.map((target, _) => target: FeatureTarget))
              .map(NarrativeBasis(family, units, _))
          }
