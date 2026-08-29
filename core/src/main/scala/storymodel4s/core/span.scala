package storymodel4s.core

import cats.Order
import cats.data.NonEmptyVector

/** A zero-based, half-open span measured in UTF-16 code units of a canonical text.
  *
  * Why UTF-16: it is the native string index on both the JVM and JavaScript, so offsets round-trip
  * between Scala backends and browser review tools without conversion. Non-case so `fromProduct`
  * cannot mint a negative start or an inverted interval.
  */
final class TextSpan private (val start: Int, val endExclusive: Int):
  def length: Int = endExclusive - start
  def isEmpty: Boolean = length == 0

  def contains(offset: Int): Boolean = offset >= start && offset < endExclusive
  def contains(other: TextSpan): Boolean =
    other.start >= start && other.endExclusive <= endExclusive

  /** True when the intersection is nonempty; empty spans overlap nothing. */
  def overlaps(other: TextSpan): Boolean =
    !isEmpty && !other.isEmpty && start < other.endExclusive && other.start < endExclusive
  def touches(other: TextSpan): Boolean =
    overlaps(other) || endExclusive == other.start || other.endExclusive == start

  /** Union when the spans overlap or abut; `None` when there is a gap between them. */
  def union(other: TextSpan): Option[TextSpan] =
    if touches(other) then
      Some(new TextSpan(math.min(start, other.start), math.max(endExclusive, other.endExclusive)))
    else None

  /** Smallest span covering both. */
  def hull(other: TextSpan): TextSpan =
    new TextSpan(math.min(start, other.start), math.max(endExclusive, other.endExclusive))

  def slice(text: String): Either[DomainError, String] =
    if endExclusive <= text.length then Right(text.substring(start, endExclusive))
    else Left(DomainError.InvalidSpan(start, endExclusive, s"exceeds text length ${text.length}"))

  def shift(delta: Int): Either[DomainError, TextSpan] =
    TextSpan.of(start + delta, endExclusive + delta)

  override def equals(other: Any): Boolean = other match
    case that: TextSpan => start == that.start && endExclusive == that.endExclusive
    case _              => false

  override def hashCode(): Int = (start, endExclusive).hashCode()

  override def toString: String = s"[$start, $endExclusive)"

object TextSpan:
  def of(start: Int, endExclusive: Int): Either[DomainError, TextSpan] =
    if start < 0 then Left(DomainError.InvalidSpan(start, endExclusive, "negative start"))
    else if endExclusive < start then
      Left(DomainError.InvalidSpan(start, endExclusive, "end precedes start"))
    else Right(new TextSpan(start, endExclusive))

  def unsafe(start: Int, endExclusive: Int): TextSpan =
    of(start, endExclusive).fold(e => throw new IllegalArgumentException(e.message), identity)

  given Order[TextSpan] = Order.by(s => (s.start, s.endExclusive))
  given Ordering[TextSpan] = Order[TextSpan].toOrdering

/** A span optionally anchored to the surface unit it was measured against. */
final case class SpanRef(unit: Option[SurfaceUnitId], span: TextSpan)

object SpanRef:
  def apply(span: TextSpan): SpanRef = SpanRef(None, span)
  given Order[SpanRef] =
    Order.by(r => (r.span.start, r.span.endExclusive, r.unit.map(_.value)))
  given Ordering[SpanRef] = Order[SpanRef].toOrdering

/** Nonempty, sorted, deduplicated evidence support that may be discontinuous.
  *
  * Why: a canonical event can be supported by several distant mentions (a battle, its later
  * retelling); evidence must be able to point at all of them at once. Non-case so `fromProduct`
  * cannot keep unsorted or duplicate refs.
  */
final class SpanSet private (val refs: NonEmptyVector[SpanRef]):
  def ++(other: SpanSet): SpanSet = SpanSet.of(refs.toVector ++ other.refs.toVector).get
  def add(ref: SpanRef): SpanSet = SpanSet.of(refs.toVector :+ ref).get
  def size: Int = refs.length
  def spans: NonEmptyVector[TextSpan] = refs.map(_.span)

  /** Smallest single span covering all members. */
  def minSpan: TextSpan = spans.reduceLeft(_.hull(_))

  /** Number of UTF-16 units covered, counting overlapping regions once. */
  def coveredLength: Int =
    spans.toVector
      .foldLeft((0, -1)) { case ((acc, reach), s) =>
        val from = math.max(s.start, reach)
        val add = math.max(0, s.endExclusive - from)
        (acc + add, math.max(reach, s.endExclusive))
      }
      ._1

  /** True when the members form one connected run (overlapping or abutting), tracking the furthest
    * reach so that a nested member cannot break the chain. Law: `isContiguous` iff
    * `coveredLength == minSpan.length`.
    */
  def isContiguous: Boolean =
    val sorted = spans.toVector
    var reach = sorted.head.endExclusive
    var ok = true
    var i = 1
    while ok && i < sorted.length do
      val s = sorted(i)
      if s.start > reach then ok = false
      else reach = math.max(reach, s.endExclusive)
      i += 1
    ok

  def units: Set[SurfaceUnitId] = refs.toVector.flatMap(_.unit).toSet

  override def equals(other: Any): Boolean = other match
    case that: SpanSet => refs.toVector == that.refs.toVector
    case _             => false

  override def hashCode(): Int = refs.toVector.hashCode()

  override def toString: String = s"SpanSet(${refs.toVector.mkString(", ")})"

object SpanSet:
  def of(refs: Iterable[SpanRef]): Option[SpanSet] =
    NonEmptyVector.fromVector(refs.toVector.distinct.sorted).map(new SpanSet(_))
  def one(span: TextSpan): SpanSet = new SpanSet(NonEmptyVector.one(SpanRef(span)))
  def one(ref: SpanRef): SpanSet = new SpanSet(NonEmptyVector.one(ref))
  def nev(refs: NonEmptyVector[SpanRef]): SpanSet = of(refs.toVector).get
  def unsafe(refs: SpanRef*): SpanSet =
    of(refs).getOrElse(throw new IllegalArgumentException("SpanSet requires at least one span"))
  given Order[SpanSet] = Order.by(_.refs.toVector)
  given Ordering[SpanSet] = Order[SpanSet].toOrdering
