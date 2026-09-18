package storymodel4s.corpus

import storymodel4s.core.{Checksum, ContentAddress, OpaqueId, PresentationAxisId}

object SegmentationId extends OpaqueId("SegmentationId")
type SegmentationId = SegmentationId.T

object WorkId extends OpaqueId("WorkId")
type WorkId = WorkId.T

object CoderId extends OpaqueId("CoderId")
type CoderId = CoderId.T

/** How coarse a segmentation is. One stimulus commonly has several at once: Friends carries 30
  * scenes, 52 events, 54 and 56 event scales, and 694 transcript lines, all on one time axis.
  */
enum GranularityLevel:
  /** `Fine` rather than `Segment`, because `Segment` is the datum below. Sherlock's 1,000
    * annotation rows are the fine level under its 50 scenes.
    */
  case Scene, Event, Fine, Line
  case Custom(namespace: String, label: String)

  def render: String = this match
    case Scene             => "scene"
    case Event             => "event"
    case Fine              => "fine"
    case Line              => "line"
    case Custom(ns, label) => s"$ns:$label"

/** Who drew the boundaries. Not decoration: a segmentation an author declared and one a crowd
  * agreed on support different claims, and an analysis that conflates them is comparing an
  * annotation with a measurement.
  */
enum SegmentationAuthority:
  case AuthorAnnotated(coder: CoderId)
  case Crowd(raters: Int)
  case ParticipantConsensus(participants: Int)
  case Derived(from: SegmentationId)

  def render: String = this match
    case AuthorAnnotated(c)      => s"author:${c.value}"
    case Crowd(n)                => s"crowd:$n"
    case ParticipantConsensus(n) => s"participants:$n"
    case Derived(from)           => s"derived:${from.value}"

/** One segment: its 1-based position, its onset on the segmentation's axis, and its label.
  *
  * Two consecutive segments MAY share an onset. Measured on the admitted Sherlock annotation (1,000
  * rows): row 3 is zero-duration -- `rawStartSeconds == rawEndSeconds == 20` -- and row 4 also
  * starts at 20, so the two share an onset. `SherlockAnnotations` documents this as lawful at
  * :76-77 ("A zero-duration row is lawful here and becomes a media instant"). An earlier version of
  * this type required STRICTLY increasing onsets and would therefore have refused the corpus this
  * repository has already admitted. Order comes from the ordinal; the onset is an observation.
  *
  * What is still refused is a DECREASE. The same table has one at row 483, where run 2 restarts at
  * 0 -- and that is the signal that the annotation is two segmentations, one per media part, not
  * one. Exactly the boundary Film Festival's part-local numbering showed from the other side.
  */
final case class Segment(ordinal: Int, onsetTicks: Long, label: String)

/** A named, ordered, timed partition of one stimulus at one granularity.
  *
  * Nothing in the repository modelled this. Two types come close and both fall short:
  * `view.IndependentCoding` is a named, checksummed, disjointness-proven interval list but on
  * RECALL time with a bare `group: Int`; `core.PlaybackIntervalSet` is an ordered interval set with
  * no identity or semantics. What was missing is exactly this: a segmentation on a STIMULUS axis
  * carrying ordinals, a granularity level and an authority.
  *
  * The axis is referred to by id rather than held, so a segmentation can be built before the
  * question of what a lawful axis for a timed annotation table looks like is settled.
  */
final class Segmentation private (
    val id: SegmentationId,
    val work: WorkId,
    val level: GranularityLevel,
    val authority: SegmentationAuthority,
    val axis: PresentationAxisId,
    val segments: Vector[Segment],
    /** A checksum over everything that makes this segmentation the one it is.
      *
      * A `SegmentationId` is a string and two segmentations can share one, which this file already
      * knew -- `compose` says so in its own comment. What it did NOT have was any way to tell two
      * same-named segmentations apart, so `compose` matched the intermediate BY NAME and would
      * build a link across two different ones. A cold review demonstrated it: onsets 0/10/20
      * composed with onsets 5000/6000/7000 under the shared id `b` produced a mapping asserting a
      * relationship nothing established.
      *
      * Following `CorpusProfile` and ADR 0011's `LexiconTable`: identity is the canonical rendering
      * PLUS the name.
      */
    val identity: Checksum
):
  def size: Int = segments.size
  def ordinals: Set[Int] = segments.map(_.ordinal).toSet
  def segment(ordinal: Int): Option[Segment] = segments.lift(ordinal - 1)

  /** Equality is identity, which is wider than what it replaced.
    *
    * This compared `id` and `segments` only, so a Friends segmentation and a Sherlock one with the
    * same ordinals and onsets were EQUAL -- `work`, `axis`, `level` and `authority` were all
    * invisible to it. That is a bad thing to be true anywhere, and worse now that `SegmentLink.of`
    * refuses a cross-work link: the refusal and the equality disagreed about what "the same
    * segmentation" means.
    */
  override def equals(other: Any): Boolean = other match
    case that: Segmentation => identity == that.identity
    case _                  => false
  override def hashCode(): Int = identity.hashCode()
  override def toString: String =
    s"Segmentation(${id.value}, ${level.render}, ${segments.size} segments)"

object Segmentation:
  private[corpus] def of(
      id: SegmentationId,
      work: WorkId,
      level: GranularityLevel,
      authority: SegmentationAuthority,
      axis: PresentationAxisId,
      segments: Vector[Segment]
  ): Either[SegmentationRefusal, Segmentation] =
    if segments.isEmpty then Left(SegmentationRefusal.Empty(id))
    else if segments.map(_.ordinal) != (1 to segments.size).toVector then
      Left(SegmentationRefusal.OrdinalsNotDense(id))
    else if segments.sliding(2).exists(w => w.sizeIs == 2 && w(1).onsetTicks < w(0).onsetTicks)
    then Left(SegmentationRefusal.OnsetsDecrease(id))
    else
      Right(
        new Segmentation(
          id,
          work,
          level,
          authority,
          axis,
          segments,
          render(id, work, level, authority, axis, segments)
        )
      )

  /** Canonical rendering, framed field by field and NESTED.
    *
    * Both `GranularityLevel.Custom.render` and `SegmentationAuthority.render` join with a colon and
    * have variable arity, which is exactly the shape that collided in `CorpusProfile.render`: a
    * variable-arity group inside a flat vector lets one field absorb the next. So each of them, and
    * each segment, digests to a single fixed-width part of the whole. Length-prefixing alone was
    * necessary and not sufficient there, and there is no reason to relearn that here.
    */
  private def render(
      id: SegmentationId,
      work: WorkId,
      level: GranularityLevel,
      authority: SegmentationAuthority,
      axis: PresentationAxisId,
      segments: Vector[Segment]
  ): Checksum =
    def framed(part: String): String = s"${part.length}:$part"
    def digestOf(parts: Vector[String]): Checksum = ContentAddress.digest(parts.map(framed))
    val levelPart = digestOf(level match
      case GranularityLevel.Custom(ns, label) => Vector("level-custom", ns, label)
      case other                              => Vector("level", other.render)).hex
    val authorityPart = digestOf(authority match
      case SegmentationAuthority.AuthorAnnotated(c)      => Vector("author", c.value)
      case SegmentationAuthority.Crowd(n)                => Vector("crowd", n.toString)
      case SegmentationAuthority.ParticipantConsensus(n) => Vector("participants", n.toString)
      case SegmentationAuthority.Derived(from)           => Vector("derived", from.value)).hex
    val segmentParts = segments.map(seg =>
      digestOf(Vector("segment", seg.ordinal.toString, seg.onsetTicks.toString, seg.label)).hex
    )
    digestOf(
      Vector("segmentation", id.value, work.value, levelPart, authorityPart, axis.value)
        ++ segmentParts
    )

enum SegmentationRefusal:
  case Empty(id: SegmentationId)
  case OrdinalsNotDense(id: SegmentationId)
  case OnsetsDecrease(id: SegmentationId)

  def message: String = this match
    case Empty(id)            => s"${id.value} has no segments"
    case OrdinalsNotDense(id) => s"${id.value} ordinals are not 1..n in order"
    case OnsetsDecrease(id)   => s"${id.value} onsets decrease"
