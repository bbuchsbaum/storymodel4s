package storymodel4s.align.bridge

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.{Lexical, ModalityTag, PolarityTag}

enum MediaLocus:
  case Extent(partId: String, interval: PlaybackInterval)
  case Instant(partId: String, at: PlaybackInstant)

  def part: String = this match
    case Extent(p, _)  => p
    case Instant(p, _) => p

  def startTick: Long = this match
    case Extent(_, iv)  => iv.start
    case Instant(_, at) => at.at

  def endTick: Long = this match
    case Extent(_, iv)  => iv.endExclusive
    case Instant(_, at) => at.at

/** One time-aligned description of a video interval: the general input contract of the pipeline.
  *
  * `ordinal` is the segment's 1-based position in video time and must be unique; `locus` is its
  * playback coordinate when the adapter can supply one; `group` is optional coarse structure
  * (scenes, chapters) - a flat annotation simply leaves it empty and the built view has no level-1
  * nodes. `extraLemmas` carries adapter-known match material beyond the text itself (character
  * names, locations), already stemmed.
  */
final case class TimedSegment(
    ordinal: Int,
    text: String,
    locus: Option[MediaLocus],
    group: Option[TimedSegment.Group] = None,
    extraLemmas: Set[String] = Set.empty,
    locations: Vector[String] = Vector.empty,
    embedText: Option[String] = None,
    lexicalText: Option[String] = None
)

object TimedSegment:
  /** Coarse grouping of segments (a scene, a chapter). Ordinals are 1-based in video order. */
  /** Coarse grouping of segments. `embedText` is the rendering an embedding channel sees; a bare
    * scene label carries almost no content, so an adapter that can say more about a scene should.
    */
  /** `lexicalText` is appended for the lexical index only and is never seen by the encoder.
    *
    * The two channels fail on opposite inputs, which the study measured rather than assumed. A
    * mean-pooled embedding is diluted by vocabulary that recurs across the episode, while a
    * rarity-weighted lexical index prices exactly that vocabulary correctly. So a signal that is
    * long, or that repeats across neighbouring nodes, belongs here rather than in `embedText`.
    */
  final case class Group(
      ordinal: Int,
      label: String,
      embedText: Option[String] = None,
      lexicalText: Option[String] = None
  )

/** Checked timed annotations keep language coordinates separate from physical support. */
final class TimedSourceView private (
    val segments: Vector[TimedSegment],
    val parts: Map[String, SourceBundle],
    val artifact: Checksum,
    val document: String,
    private val view: InMemorySourceView,
    val representation: SourceRepresentation
) extends SourceView:
  val nodes: Vector[NodeSummary] = view.nodes
  def node(ref: SourceNodeRef): Option[NodeSummary] = view.node(ref)
  def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    view.adjacency(layer)
  val worldOrder: Option[Map[SourceNodeRef, Int]] = None
  val scoringLength: Int = view.scoringLength
  private def identityParts = (segments, parts, artifact, document, representation.digest)
  override def equals(other: Any): Boolean = other match
    case that: TimedSourceView => identityParts == that.identityParts
    case _                     => false
  override def hashCode: Int = identityParts.hashCode
  override def toString: String =
    s"TimedSourceView(${artifact.short()}, ${segments.size} descriptions)"

object TimedSourceView:
  /** Parts are supplied explicitly; no clock, duration or cross-part assembly is inferred. */
  def of(
      segments: Vector[TimedSegment],
      parts: Map[String, SourceBundle],
      artifact: Checksum
  ): Either[MappingRefusal, TimedSourceView] =
    def invalid(detail: String) = MappingRefusal.InvalidValue("timed-source", detail)
    val groups = segments.flatMap(_.group).distinct
    if segments.isEmpty || segments.exists(s => s.ordinal <= 0 || s.text.trim.isEmpty) ||
      segments.map(_.ordinal).distinct.size != segments.size ||
      groups.map(_.ordinal).distinct.size != groups.size || groups.exists(g =>
        g.ordinal <= 0 || g.label.trim.isEmpty
      ) ||
      parts.isEmpty || parts.keys.exists(_.trim.isEmpty) ||
      parts.values.map(_.identity).toSet.size != parts.size
    then
      Left(
        invalid(
          "nonempty text, unique positive ordinals/groups and distinct declared parts required"
        )
      )
    else
      val sorted = segments.sortBy(_.ordinal)
      val document = sorted.map(_.text).mkString("\n")
      val offsets = sorted.map(_.text).scanLeft(0)((n, t) => n + t.length + 1).init
      def leaf(s: TimedSegment) =
        SourceNodeRef.Situation(SituationId.unsafe(s"timed:${artifact.hex}:${s.ordinal}"))
      def group(g: TimedSegment.Group) =
        SourceNodeRef.Segment(SegmentId.unsafe(s"timed-group:${artifact.hex}:${g.ordinal}"))
      val leaves = sorted.zip(offsets).zipWithIndex.map { case ((s, offset), rank) =>
        NodeSummary(
          leaf(s),
          0,
          s.group.map(group),
          rank,
          SpanSet.one(TextSpan.unsafe(offset, offset + s.text.length)),
          None,
          Vector.empty,
          ContextTag.NarratedWorld,
          PolarityTag.Unknown,
          ModalityTag.Unknown,
          s.locations,
          Lexical.stemSet(s.lexicalText.getOrElse(s.text)) ++ s.extraLemmas,
          propositional = PropositionalScope.unstated
        )
      }
      val groupNodes = groups.sortBy(_.ordinal).zipWithIndex.map { (g, rank) =>
        val members = leaves.filter(_.parent.contains(group(g)))
        NodeSummary(
          group(g),
          1,
          None,
          rank,
          SpanSet.one(
            TextSpan.unsafe(
              members.head.scoringPosition.get.spans.minSpan.start,
              members.last.scoringPosition.get.spans.minSpan.endExclusive
            )
          ),
          None,
          Vector.empty,
          ContextTag.NarratedWorld,
          PolarityTag.Unknown,
          ModalityTag.Unknown,
          members.flatMap(_.locations).distinct,
          Lexical.stemSet(g.lexicalText.getOrElse(g.label)) ++ members.flatMap(_.lemmas),
          propositional = PropositionalScope.unstated
        )
      }
      def anchors(s: TimedSegment): Either[MappingRefusal, Option[(SourceBundle, EvidenceAnchor)]] =
        s.locus match
          case None        => Right(None)
          case Some(locus) =>
            parts.get(locus.part).toRight(invalid("foreign part")).flatMap { bundle =>
              val anchor = locus match
                case MediaLocus.Extent(_, interval) =>
                  EvidenceAnchor.MediaTime(
                    bundle.id,
                    bundle.streams.head.id,
                    interval.axis,
                    PlaybackIntervalSet.one(interval)
                  )
                case MediaLocus.Instant(_, at) =>
                  EvidenceAnchor.MediaPoint(bundle.id, bundle.streams.head.id, at)
              EvidenceSupport
                .of(bundle, Vector(anchor))
                .left
                .map(e => invalid(e.message))
                .map(_ => Some(bundle -> anchor))
            }
      for
        values <- sorted.traverse(s => anchors(s).map(s -> _))
        physicalLeaves <- values.traverse { (s, located) =>
          located match
            case None =>
              Right(leaf(s) -> SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource))
            case Some((bundle, anchor)) =>
              EvidenceSupport
                .of(bundle, Vector(anchor))
                .left
                .map(e => invalid(e.message))
                .map(v => leaf(s) -> SourceSupportStatus.located(TypedSupport.Anchored(v)))
        }
        physicalGroups <- groups.traverse { g =>
          val located = values.filter(_._1.group.contains(g)).flatMap(_._2)
          if located.isEmpty || located.map(_._1.identity).distinct.size != 1 then
            Right(group(g) -> SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource))
          else
            EvidenceSupport
              .of(located.head._1, located.map(_._2))
              .left
              .map(e => invalid(e.message))
              .map(v => group(g) -> SourceSupportStatus.located(TypedSupport.Anchored(v)))
        }
        edges = leaves.zip(leaves.drop(1)).map((a, b) => (a.ref, b.ref, 1.0))
        view = InMemorySourceView(
          leaves ++ groupNodes,
          Map(RelationLayer.DiscourseSuccession -> edges),
          None,
          document.length
        )
        bundles = NonEmptyVector.fromVectorUnsafe(
          parts.toVector.sortBy(_._1).map(p => BundleEntry.media(p._2))
        )
        representation <- SourceRepresentation.of(
          view,
          bundles,
          None,
          (physicalLeaves ++ physicalGroups).toMap
        )
      yield new TimedSourceView(sorted, parts, artifact, document, view, representation)
