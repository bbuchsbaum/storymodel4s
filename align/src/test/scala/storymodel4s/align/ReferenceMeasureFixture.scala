package storymodel4s.align

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.recall.{ModalityTag, PolarityTag}

/** Synthetic film units for reference measures. No media, annotation or recall content is read. */
object ReferenceMeasureFixture:
  /** One tick is a tenth of a second. */
  val timebase: RationalTimebase = RationalTimebase.of(1L, 10L).toOption.get
  val Ticks: Long = 1000L
  private def part(name: String): SourceBundle = SourceBundle
    .filmEdition(EditionId.unsafe(name), Checksum.ofText(s"synthetic-$name"), 0L, Ticks, timebase)
    .toOption
    .get
  val film: SourceBundle = part("reference-film")
  val other: SourceBundle = part("reference-other")
  val selection: TemporalSupport.Selection = TemporalSupport.Selection.Part(film.identity)
  private val text = BundleEntry.text(Checksum.ofText("reference-measure text"))

  /** Supplied support of one unit. */
  enum UnitSupport:
    /** Each interval is its own anchor, so a unit may carry overlapping or adjacent anchors. */
    case Film(intervals: Vector[(Long, Long)], points: Vector[Long])
    case TextOnly
    case Unlocated
    case OtherPart(start: Long, end: Long)

  def u(i: Int): SourceNodeRef = SourceNodeRef.Situation(SituationId.unsafe(s"u$i"))

  private def node(i: Int): NodeSummary = NodeSummary(
    u(i),
    0,
    None,
    i,
    SpanSet.one(TextSpan.unsafe(i, i + 1)),
    None,
    Vector.empty,
    ContextTag.NarratedWorld,
    PolarityTag.Unknown,
    ModalityTag.Unknown,
    Vector.empty,
    Set.empty
  )

  private def media(bundle: SourceBundle, start: Long, end: Long): EvidenceAnchor =
    EvidenceAnchor.MediaTime(
      bundle.id,
      bundle.streams.head.id,
      bundle.primaryAxis.id,
      PlaybackIntervalSet.one(PlaybackInterval.on(bundle.primaryAxis, start, end).toOption.get)
    )

  private def status(support: UnitSupport, i: Int): SourceSupportStatus = support match
    case UnitSupport.Film(intervals, points) =>
      val anchors = intervals.map((a, b) => media(film, a, b)) ++ points.map(p =>
        EvidenceAnchor.MediaPoint(
          film.id,
          film.streams.head.id,
          PlaybackInstant.on(film.primaryAxis, p).toOption.get
        )
      )
      SourceSupportStatus.located(
        TypedSupport.Anchored(EvidenceSupport.of(film, anchors).toOption.get)
      )
    case UnitSupport.TextOnly =>
      SourceSupportStatus.located(TypedSupport.Text(SpanSet.one(TextSpan.unsafe(i, i + 1))))
    case UnitSupport.Unlocated => SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource)
    case UnitSupport.OtherPart(a, b) =>
      SourceSupportStatus.located(
        TypedSupport.Anchored(EvidenceSupport.of(other, Vector(media(other, a, b))).toOption.get)
      )

  /** Unit `u(i)` carries `supports(i)`. */
  def source(supports: Vector[UnitSupport]): SourceRepresentation =
    val nodes = supports.indices.toVector.map(node)
    SourceRepresentation
      .of(
        InMemorySourceView(nodes, Map.empty, None, supports.size + 1),
        NonEmptyVector.of(BundleEntry.media(film), BundleEntry.media(other), text),
        None,
        supports.zipWithIndex.map((s, i) => u(i) -> status(s, i)).toMap
      )
      .fold(e => throw new AssertionError(s"invalid fixture source: $e"), identity)

  def interval(start: Long, end: Long): UnitSupport =
    UnitSupport.Film(Vector(start -> end), Vector())

  def one(units: SourceNodeRef*): NonEmptyVector[SourceNodeRef] =
    NonEmptyVector.fromVectorUnsafe(units.toVector)

  def single(units: SourceNodeRef*): ProvisionalReference.Hypothesis =
    ProvisionalReference.Hypothesis(
      ProvisionalReference.Shape.Single,
      NonEmptyVector.one(one(units*))
    )

  def located(h: ProvisionalReference.Hypothesis): ProvisionalReference.Member =
    ProvisionalReference.Member.Located(h)

  def reference(
      source: SourceRepresentation,
      declaration: ProvisionalReference.Declaration,
      coverage: ProvisionalReference.WeightCoverage =
        ProvisionalReference.WeightCoverage.RemainderUnknown
  ): ProvisionalReference =
    ProvisionalReference
      .of(source, selection, declaration, coverage)
      .fold(e => throw new AssertionError(s"refused fixture reference: $e"), identity)

  def measures(
      source: SourceRepresentation,
      declaration: ProvisionalReference.Declaration,
      coverage: ProvisionalReference.WeightCoverage =
        ProvisionalReference.WeightCoverage.RemainderUnknown
  ): ReferenceMeasures.Measures =
    ReferenceMeasures.of(reference(source, declaration, coverage))
