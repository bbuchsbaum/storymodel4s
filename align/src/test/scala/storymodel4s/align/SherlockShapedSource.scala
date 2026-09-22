package storymodel4s.align

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.recall.{ModalityTag, PolarityTag}

/** Synthetic coordinates only: no media, annotation corpus or participant content is read. */
object SherlockShapedSource:
  val timebase: RationalTimebase = RationalTimebase.of(1L, 10L).toOption.get
  def part(name: String): SourceBundle = SourceBundle
    .filmEdition(EditionId.unsafe(name), Checksum.ofText(s"synthetic-$name"), 0L, 40L, timebase)
    .toOption
    .get
  val a: SourceBundle = part("part-a")
  val b: SourceBundle = part("part-b")
  val parts: NonEmptyVector[SourceBundle] = NonEmptyVector.of(a, b)
  val bundles: NonEmptyVector[BundleEntry] = parts.map(BundleEntry.media)
  private val streams = parts.toVector.flatMap(_.streams)
  private val authority = streams.map(_.id)
  private val edition = EditionId.unsafe("synthetic-composed")
  private val axis =
    SourceBundle.editionPlaybackAxis(edition, streams, authority, 0L, 80L, timebase).toOption.get
  private val receipt = SourceDerivationReceipt
    .of("synthetic-composition/v1", "a then b", parts.toVector.map(_.identity))
    .toOption
    .get
  val mappings: Vector[TrackComposition] = parts.toVector.zipWithIndex.map { case (part, i) =>
    val segment = CompositionSegment
      .of(
        PlaybackInterval.on(part.primaryAxis, 0L, 40L).toOption.get,
        PlaybackInterval.on(axis, i * 40L, (i + 1) * 40L).toOption.get,
        OccurrenceId.unsafe(s"part-$i")
      )
      .toOption
      .get
    TrackComposition.of(part.primaryAxis.id, axis.id, Vector(segment), receipt).toOption.get
  }
  val composed: SourceBundle = SourceBundle
    .of(Some(edition), SourceKind.FilmEdition, streams, axis, authority, mappings)
    .toOption
    .get
  val composition: DeclaredComposition = DeclaredComposition.of(composed, parts).toOption.get
  def e(i: Int): SourceNodeRef = SourceNodeRef.Situation(SituationId.unsafe(s"e$i"))
  val group: SourceNodeRef = SourceNodeRef.Segment(SegmentId.unsafe("g1"))
  val nodes: Vector[NodeSummary] = (1 to 8).map { i =>
    NodeSummary(
      e(i),
      0,
      Option.when(i == 2 || i == 4)(group),
      i - 1,
      SpanSet.one(TextSpan.unsafe(i - 1, i)),
      None,
      Vector.empty,
      ContextTag.NarratedWorld,
      PolarityTag.Unknown,
      ModalityTag.Unknown,
      Vector.empty,
      Set.empty
    )
  }.toVector :+ NodeSummary(
    group,
    1,
    None,
    1,
    SpanSet.one(TextSpan.unsafe(1, 4)),
    None,
    Vector.empty,
    ContextTag.NarratedWorld,
    PolarityTag.Unknown,
    ModalityTag.Unknown,
    Vector.empty,
    Set.empty
  )
  val view: InMemorySourceView = InMemorySourceView(nodes, Map.empty, None, 8)

  def anchors(
      bundle: SourceBundle,
      part: SourceBundle,
      start: Long,
      end: Long,
      projection: Boolean
  ): Vector[EvidenceAnchor] =
    val stream = part.streams.head.id
    def at(axis: PresentationAxis, offset: Long): EvidenceAnchor =
      if start == end then
        EvidenceAnchor.MediaPoint(
          bundle.id,
          stream,
          PlaybackInstant.on(axis, start + offset).toOption.get
        )
      else
        EvidenceAnchor.MediaTime(
          bundle.id,
          stream,
          axis.id,
          PlaybackIntervalSet.one(
            PlaybackInterval.on(axis, start + offset, end + offset).toOption.get
          )
        )
    Vector(at(part.primaryAxis, 0L)) ++ Option.when(projection)(
      at(composed.primaryAxis, if part == a then 0L else 40L)
    )

  def physical(projected: Boolean): Map[SourceNodeRef, SourceSupportStatus] =
    val entries = (1 to 8).map { i =>
      val status =
        if i == 4 then SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource)
        else
          val part = if i <= 4 then a else b
          val start = if i <= 4 then (i - 1) * 10L else (i - 5) * 10L
          val end = if i == 8 then start else start + 10L
          val bundle = if projected then composed else part
          SourceSupportStatus.located(
            TypedSupport.Anchored(
              EvidenceSupport.of(bundle, anchors(bundle, part, start, end, projected)).toOption.get
            )
          )
      e(i) -> status
    }.toMap
    entries.updated(group, entries(e(2)))
  def source(projected: Boolean): SourceRepresentation = SourceRepresentation
    .of(view, bundles, Option.when(projected)(composition), physical(projected))
    .toOption
    .get

  val cross: SourceNodeRef = SourceNodeRef.Segment(SegmentId.unsafe("cross"))
  val crossView: InMemorySourceView =
    InMemorySourceView(Vector(nodes.last.copy(ref = cross)), Map.empty, None, 8)
  val crossSupport: SourceSupportStatus = SourceSupportStatus.located(
    TypedSupport.Anchored(
      EvidenceSupport
        .of(composed, anchors(composed, a, 10L, 20L, true) ++ anchors(composed, b, 20L, 30L, true))
        .toOption
        .get
    )
  )
