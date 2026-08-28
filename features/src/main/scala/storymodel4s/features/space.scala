package storymodel4s.features

import cats.Order
import storymodel4s.core.*

/** Shape of the values a feature space holds. Kept as runtime data because spaces are open and
  * versioned, while the Scala type parameter of a track prevents mixing scalars with vectors.
  */
enum FeatureValueSchema:
  case Scalar(units: Option[String])
  case Vector(dimension: Int)
  case Categorical(labels: scala.Vector[String])
  case Distribution(support: scala.Vector[String])

/** A declared feature space: what is measured, by which provider/version, and whether values were
  * normalized (and against what population, if known).
  */
final case class FeatureSpace[V](
    id: FeatureSpaceId,
    description: String,
    valueSchema: FeatureValueSchema,
    units: Option[String],
    provider: Fingerprint,
    normalized: Boolean,
    normalizationPopulation: Option[String] = None
)

/** Where a feature observation is attached on the surface axis or in the narrative model.
  *
  * Positional order is total for `Token` and `Window`; identifier-based targets sort by ID unless a
  * resolver supplies positions.
  */
enum FeatureTarget:
  case Token(index: TokenIndex)
  case Sentence(unit: SurfaceUnitId)
  case Situation(id: SituationId)
  case Segment(id: SegmentId)

  /** The gap after `afterUnit` (a sentence or clause) — a candidate boundary. */
  case Boundary(afterUnit: SurfaceUnitId)
  case Turn(id: TurnId)
  case Window(range: TokenRange)

object FeatureTarget:
  private def rank(t: FeatureTarget): Int = t match
    case _: Token     => 0
    case _: Window    => 1
    case _: Sentence  => 2
    case _: Boundary  => 3
    case _: Turn      => 4
    case _: Situation => 5
    case _: Segment   => 6

  private def key(t: FeatureTarget): (Int, Int, String) = t match
    case Token(i)     => (0, i.value, "")
    case Window(r)    => (1, r.start.value, r.endExclusive.value.toString)
    case Sentence(u)  => (2, 0, u.value)
    case Boundary(u)  => (3, 0, u.value)
    case Turn(i)      => (4, 0, i.value)
    case Situation(i) => (5, 0, i.value)
    case Segment(i)   => (6, 0, i.value)

  given Order[FeatureTarget] = Order.by(key)
  given Ordering[FeatureTarget] = Order[FeatureTarget].toOrdering

  def rankOf(t: FeatureTarget): Int = rank(t)

/** Resolves a target to its exact text support. Surface targets resolve from the atlas; narrative
  * and transcript targets need the owning model, supplied as functions so this module never depends
  * on `story`.
  */
final case class SupportResolver(
    sequence: SurfaceSequence,
    situation: SituationId => Option[SpanSet] = _ => None,
    segment: SegmentId => Option[SpanSet] = _ => None,
    turn: TurnId => Option[SpanSet] = _ => None
):
  def support(target: FeatureTarget): Option[SpanSet] = target match
    case FeatureTarget.Token(i)  => sequence.at(i).map(t => SpanSet.one(t.span))
    case FeatureTarget.Window(r) =>
      if r.isEmpty || r.endExclusive.value > sequence.size then None
      else
        val first = sequence.tokens(r.start.value).span
        val last = sequence.tokens(r.endExclusive.value - 1).span
        Some(SpanSet.one(first.hull(last)))
    case FeatureTarget.Sentence(u) => sequence.atlas.byId.get(u).map(s => SpanSet.one(s.span))
    case FeatureTarget.Boundary(u) =>
      sequence.atlas.byId
        .get(u)
        .map(s => SpanSet.one(TextSpan.unsafe(s.span.endExclusive, s.span.endExclusive)))
    case FeatureTarget.Situation(i) => situation(i)
    case FeatureTarget.Segment(i)   => segment(i)
    case FeatureTarget.Turn(i)      => turn(i)

  /** Discourse position (start offset) of a target, when its support is known. */
  def position(target: FeatureTarget): Option[Int] = support(target).map(_.minSpan.start)
