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
  *
  * `collection` is optional structure above the group (a film holding scenes); it is declared on
  * the row so one table carries all three levels. `target` says whether the row is content a
  * producer may be asked to choose; a row that is not a target is still source content and still a
  * node. Neither field changes a source that does not use it.
  */
final case class TimedSegment(
    ordinal: Int,
    text: String,
    locus: Option[MediaLocus],
    group: Option[TimedSegment.Group] = None,
    extraLemmas: Set[String] = Set.empty,
    locations: Vector[String] = Vector.empty,
    embedText: Option[String] = None,
    lexicalText: Option[String] = None,
    collection: Option[TimedSegment.Collection] = None,
    target: Boolean = true
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

  /** A set of groups (a film holding scenes). It is its own type, not a second `Group`, so a group
    * cannot be passed where a collection is meant and the two ordinal spaces stay apart. Ordinals
    * are 1-based and unique among collections.
    */
  final case class Collection(ordinal: Int, label: String)

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

  /** The level-1 node of each group ordinal. */
  val groupRefs: Map[Int, SourceNodeRef] =
    segments.flatMap(_.group).map(g => g.ordinal -> TimedSourceView.group(artifact, g)).toMap

  /** The level-2 node of each collection ordinal; empty when the source has no collections. */
  val collectionRefs: Map[Int, SourceNodeRef] = segments
    .flatMap(_.collection)
    .map(c => c.ordinal -> TimedSourceView.collection(artifact, c))
    .toMap

  /** Groups with at least one target row, in ordinal order. They are derived here, from the checked
    * rows, so that a consumer declares its universe from the source and does not re-derive the
    * rule. A row outside every group is in no target set.
    */
  val targetGroups: Vector[SourceNodeRef] =
    segments.filter(_.target).flatMap(_.group).map(_.ordinal).distinct.sorted.map(groupRefs)

  /** Collections with at least one target group, in ordinal order. */
  val targetCollections: Vector[SourceNodeRef] = segments
    .filter(_.target)
    .flatMap(_.collection)
    .map(_.ordinal)
    .distinct
    .sorted
    .map(collectionRefs)
  private def identityParts = (segments, parts, artifact, document, representation.digest)
  override def equals(other: Any): Boolean = other match
    case that: TimedSourceView => identityParts == that.identityParts
    case _                     => false
  override def hashCode: Int = identityParts.hashCode
  override def toString: String =
    s"TimedSourceView(${artifact.short()}, ${segments.size} descriptions)"

object TimedSourceView:
  /** Refusal details of the collection level, public so a caller can tell them apart. */
  val CollectionInvalid = "collections need unique positive ordinals and nonempty labels"
  val CollectionWithoutGroup = "a row with a collection needs a group"
  val GroupInSeveralCollections = "a group must belong to exactly one collection"
  val MixedCollections = "either every group has a collection or none has"

  private def group(artifact: Checksum, g: TimedSegment.Group): SourceNodeRef =
    SourceNodeRef.Segment(SegmentId.unsafe(s"timed-group:${artifact.hex}:${g.ordinal}"))

  /** Collection ids take the group form under their own prefix, so no ordinal can make a group and
    * a collection of one artifact collide.
    */
  private def collection(artifact: Checksum, c: TimedSegment.Collection): SourceNodeRef =
    SourceNodeRef.Segment(SegmentId.unsafe(s"timed-collection:${artifact.hex}:${c.ordinal}"))

  /** Parts are supplied explicitly; no clock, duration or cross-part assembly is inferred. */
  def of(
      segments: Vector[TimedSegment],
      parts: Map[String, SourceBundle],
      artifact: Checksum
  ): Either[MappingRefusal, TimedSourceView] =
    def invalid(detail: String) = MappingRefusal.InvalidValue("timed-source", detail)
    val groups = segments.flatMap(_.group).distinct
    val collections = segments.flatMap(_.collection).distinct
    // What each group's rows say about its collection, absent included.
    val declared = segments
      .flatMap(s => s.group.map(_.ordinal -> s.collection))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.distinct)
      .toMap
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
    else if collections.map(_.ordinal).distinct.size != collections.size || collections.exists(c =>
        c.ordinal <= 0 || c.label.trim.isEmpty
      )
    then Left(invalid(CollectionInvalid))
    else if segments.exists(s => s.collection.nonEmpty && s.group.isEmpty) then
      Left(invalid(CollectionWithoutGroup))
    else if declared.values.exists(_.size != 1) then Left(invalid(GroupInSeveralCollections))
    else if collections.nonEmpty && declared.values.exists(_.head.isEmpty) then
      Left(invalid(MixedCollections))
    else
      val sorted = segments.sortBy(_.ordinal)
      val document = sorted.map(_.text).mkString("\n")
      val offsets = sorted.map(_.text).scanLeft(0)((n, t) => n + t.length + 1).init
      def leaf(s: TimedSegment) =
        SourceNodeRef.Situation(SituationId.unsafe(s"timed:${artifact.hex}:${s.ordinal}"))
      def group(g: TimedSegment.Group) = TimedSourceView.group(artifact, g)
      def collection(c: TimedSegment.Collection) = TimedSourceView.collection(artifact, c)
      def collectionOf(g: TimedSegment.Group) = declared(g.ordinal).head
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
          collectionOf(g).map(collection),
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
      val collectionNodes = collections.sortBy(_.ordinal).zipWithIndex.map { (c, rank) =>
        val own = groupNodes.filter(_.parent.contains(collection(c)))
        val ownRefs = own.map(_.ref).toSet
        val members = leaves.filter(_.parent.exists(ownRefs))
        NodeSummary(
          collection(c),
          2,
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
          Lexical.stemSet(c.label) ++ own.flatMap(_.lemmas),
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
        // A collection follows the group rule: located only when every located row sits in one part.
        physicalCollections <- collections.traverse { c =>
          val located = values.filter(_._1.collection.contains(c)).flatMap(_._2)
          if located.isEmpty || located.map(_._1.identity).distinct.size != 1 then
            Right(collection(c) -> SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource))
          else
            EvidenceSupport
              .of(located.head._1, located.map(_._2))
              .left
              .map(e => invalid(e.message))
              .map(v => collection(c) -> SourceSupportStatus.located(TypedSupport.Anchored(v)))
        }
        edges = leaves.zip(leaves.drop(1)).map((a, b) => (a.ref, b.ref, 1.0))
        view = InMemorySourceView(
          leaves ++ groupNodes ++ collectionNodes,
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
          (physicalLeaves ++ physicalGroups ++ physicalCollections).toMap
        )
      yield new TimedSourceView(sorted, parts, artifact, document, view, representation)
