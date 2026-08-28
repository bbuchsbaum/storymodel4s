package storymodel4s.features

import storymodel4s.core.*

/** One aligned observation: the target, its estimate, and — for derived tracks — the exact support
  * and coverage of the aggregate that produced it.
  */
final case class FeatureObservation[+T <: FeatureTarget, +V](
    target: T,
    estimate: Estimate[V],
    support: Option[SpanSet],
    coverage: Option[Coverage]
):
  def map[W](f: V => W): FeatureObservation[T, W] = copy(estimate = estimate.map(f))

/** Where a track came from: a raw provider run or a deterministic derivation. */
final case class TrackProvenance(
    provenance: Provenance,
    storyChecksum: Option[Checksum]
)

/** A feature space bound to observations over a target domain.
  *
  * Invariants (checked by [[FeatureTrack.validated]]): observations are sorted by target, targets
  * are unique, a raw track has no derivation, a derived track has one. Immutable: transformations
  * return new tracks with a recorded derivation.
  */
final case class FeatureTrack[T <: FeatureTarget, V](
    space: FeatureSpace[V],
    observations: Vector[FeatureObservation[T, V]],
    derivation: Option[FeatureDerivation],
    provenance: TrackProvenance
):
  def isRaw: Boolean = derivation.isEmpty
  def size: Int = observations.size

  lazy val byTarget: Map[FeatureTarget, FeatureObservation[T, V]] =
    observations.iterator.map(o => (o.target: FeatureTarget) -> o).toMap

  def get(target: FeatureTarget): Option[Estimate[V]] = byTarget.get(target).map(_.estimate)
  def targets: Vector[T] = observations.map(_.target)
  def observed: Vector[(T, V)] =
    observations.flatMap(o => o.estimate.toOption.map(v => o.target -> v))

  /** Eligible = all observations; observed = those with a value. */
  def coverage: Coverage =
    Coverage.unsafe(observations.size, observations.count(_.estimate.isObserved))

  /** Pair with another track on the same target set (targets present in both). */
  def zip[W](other: FeatureTrack[T, W]): Vector[(T, Estimate[V], Estimate[W])] =
    observations.flatMap(o =>
      other.byTarget.get(o.target).map(p => (o.target, o.estimate, p.estimate))
    )

  /** Observations whose own support (if known) or target position lies inside `spans`. */
  def restrict(spans: SpanSet, resolver: SupportResolver): FeatureTrack[T, V] =
    copy(observations = observations.filter { o =>
      val sup = o.support.orElse(resolver.support(o.target))
      sup.exists(s => s.spans.exists(a => spans.spans.exists(b => b.overlaps(a) || b.contains(a))))
    })

  def mapValues[W](space2: FeatureSpace[W], derivation2: FeatureDerivation)(
      f: V => W
  ): FeatureTrack[T, W] =
    FeatureTrack(space2, observations.map(_.map(f)), Some(derivation2), provenance)

object FeatureTrack:
  def raw[T <: FeatureTarget, V](
      space: FeatureSpace[V],
      observations: Vector[FeatureObservation[T, V]],
      provenance: TrackProvenance
  ): FeatureTrack[T, V] =
    FeatureTrack(space, observations.sortBy(o => o.target: FeatureTarget), None, provenance)

  def validated[T <: FeatureTarget, V](
      t: FeatureTrack[T, V]
  ): Either[DomainError, FeatureTrack[T, V]] =
    val path = s"features/track/${t.space.id.value}"
    val ts = t.observations.map(o => o.target: FeatureTarget)
    if ts.distinct.size != ts.size then
      Left(DomainError.InvariantViolation(path, "duplicate targets"))
    else if ts != ts.sorted then
      Left(DomainError.InvariantViolation(path, "observations not in target order"))
    else if t.observations.exists(o => o.coverage.exists(c => c.observed > c.eligible)) then
      Left(DomainError.InvariantViolation(path, "coverage observed > eligible"))
    else if t.derivation.exists(d => d.inputs.toVector.contains(t.space.id)) then
      Left(DomainError.InvariantViolation(path, "derived track lists itself as an input"))
    else Right(t)

  /** Scalar tracks additionally reject non-finite observed values: NaN is never a measurement. */
  def validatedScores[T <: FeatureTarget](
      t: FeatureTrack[T, Double]
  ): Either[DomainError, FeatureTrack[T, Double]] =
    validated(t).flatMap { ok =>
      ok.observations.find(o => o.estimate.isObserved && Estimate.finite(o.estimate).isEmpty) match
        case Some(o) =>
          Left(
            DomainError.InvariantViolation(
              s"features/track/${t.space.id.value}",
              s"non-finite observed value at ${o.target}"
            )
          )
        case None => Right(ok)
    }
