package storymodel4s.core

/** Source support retains its coordinate family; a projection is derived against a bundle. */
enum TypedSupport:
  case Text(spans: SpanSet)
  case Anchored(support: EvidenceSupport)

  /** Complete physical-support identity, separate from any alignment scoring coordinate. */
  def identity: Checksum = SourceIdentity.support(this)

  /** Text coordinates only; anchored evidence is never cast to character offsets. */
  def textSpans: Option[SpanSet] = this match
    case Text(spans) => Some(spans)
    case Anchored(_) => None

/** Canonical playback union with explicit point observations. Points are retained inside intervals. */
final class PlaybackSupport private (
    val axis: PresentationAxisId,
    val intervals: Vector[PlaybackInterval],
    val points: Vector[PlaybackInstant]
):
  def bounds: (Long, Long) =
    ((intervals.map(_.start) ++ points.map(_.at)).min,
      (intervals.map(_.endExclusive) ++ points.map(_.at)).max)

  def contains(other: PlaybackSupport): Boolean =
    axis == other.axis && other.intervals.forall(c =>
      intervals.exists(p => p.start <= c.start && p.endExclusive >= c.endExclusive)
    ) && other.points.forall(p => points.contains(p) || intervals.exists(_.contains(p.at)))

  override def equals(other: Any): Boolean = other match
    case that: PlaybackSupport => axis == that.axis && intervals == that.intervals && points == that.points
    case _ => false
  override def hashCode(): Int = (axis, intervals, points).hashCode()
  override def toString: String = s"PlaybackSupport(${axis.value}, $intervals, $points)"

object PlaybackSupport:
  def of(
      axis: PresentationAxisId,
      intervals: Vector[PlaybackInterval],
      points: Vector[PlaybackInstant]
  ): Either[DomainError, PlaybackSupport] =
    if intervals.isEmpty && points.isEmpty then
      Left(SourceCanon.inv("playback-support/empty", "support requires an interval or point"))
    else if intervals.exists(_.axis != axis) || points.exists(_.axis != axis) then
      Left(SourceCanon.inv("playback-support/axis", "support has a foreign axis"))
    else
      val merged = intervals.sortBy(i => (i.start, i.endExclusive)).foldLeft(Vector.empty[PlaybackInterval]) {
        (out, interval) => out.lastOption match
          case Some(last) if interval.start <= last.endExclusive =>
            out.init :+ new PlaybackInterval(axis, last.start, last.endExclusive.max(interval.endExclusive))
          case _ => out :+ interval
      }
      Right(new PlaybackSupport(axis, merged, points.distinct.sortBy(_.at)))

/** Support on one declared primary axis. Playback intervals retain gaps. */
enum PrimaryProjection:
  case TextSpans(axis: PresentationAxisId, spans: SpanSet)
  case Playback(axis: PresentationAxisId, support: PlaybackSupport)

  /** Exact hull bounds for ordering, without replacing the support's disjoint members. */
  def bounds: (Long, Long) = this match
    case TextSpans(_, spans)    => (spans.minSpan.start.toLong, spans.minSpan.endExclusive.toLong)
    case Playback(_, support) => support.bounds

object PrimaryProjection:
  /** Checked primary selection. Text extent remains a validator law; native anchors are retained in
    * their original support and are never implicitly mapped onto primary.
    */
  def on(bundle: SourceBundle, support: TypedSupport): Either[DomainError, PrimaryProjection] =
    (bundle.primaryAxis.kind, support) match
      case (AxisKind.TextCharacter, TypedSupport.Text(spans)) =>
        Right(PrimaryProjection.TextSpans(bundle.primaryAxis.id, spans))
      case (AxisKind.EditionPlayback, TypedSupport.Anchored(anchors)) =>
        for
          checked <- anchors.checkedOn(bundle)
          playback <- checked.playbackOn(bundle.primaryAxis.id)
        yield PrimaryProjection.Playback(bundle.primaryAxis.id, playback)
      case (AxisKind.TextCharacter | AxisKind.EditionPlayback, _) =>
        Left(
          SourceCanon.inv(
            "projection/support-kind",
            "support does not match the primary coordinate kind"
          )
        )
      case _ =>
        Left(
          SourceCanon.inv(
            "projection/primary-kind",
            "primary projection requires TextCharacter or EditionPlayback"
          )
        )

private[core] object SourceSupportChecks:
  private def invalid(rule: String, detail: String): Left[DomainError, Nothing] =
    Left(SourceCanon.inv(s"support/$rule", detail))

  def anchor(bundle: SourceBundle, anchor: EvidenceAnchor): Either[DomainError, Unit] =
    if anchor.anchorBundle != bundle.id then invalid("bundle", "foreign bundle identity")
    else
      bundle.stream(anchor.anchorStream) match
        case None         => invalid("stream", "anchor stream is foreign to the bundle")
        case Some(stream) =>
          val kindMatches = anchor match
            case _: EvidenceAnchor.Text =>
              stream.kind match
                case StreamKind.CanonicalText | StreamKind.Subtitle | StreamKind.TimedText |
                    StreamKind.Annotation =>
                  stream.extent.isInstanceOf[AxisExtent.TextChars]
                case _ => false
            case _: EvidenceAnchor.MediaTime | _: EvidenceAnchor.MediaPoint =>
              stream.kind match
                case StreamKind.Picture | StreamKind.Audio | StreamKind.Subtitle |
                    StreamKind.TimedText | StreamKind.Annotation =>
                  stream.extent.isInstanceOf[AxisExtent.PlaybackTicks]
                case _ => false
            case _: EvidenceAnchor.Shot =>
              stream.kind == StreamKind.Picture && stream.extent
                .isInstanceOf[AxisExtent.PlaybackTicks]
            case _: EvidenceAnchor.Track =>
              (stream.kind == StreamKind.Picture || stream.kind == StreamKind.Audio) &&
              stream.extent.isInstanceOf[AxisExtent.PlaybackTicks]
          if !kindMatches then
            invalid("kind", "anchor kind does not match the stream kind and extent")
          else
            anchor match
              case EvidenceAnchor.Text(_, _, spans) =>
                stream.extent match
                  case extent: AxisExtent.TextChars
                      if spans.spans.forall(_.endExclusive <= extent.length) =>
                    Right(())
                  case _ => invalid("extent", "text support escapes the selected stream extent")
              case EvidenceAnchor.MediaTime(_, _, axis, intervals) if axis != intervals.axis =>
                invalid("interval-axis", "MediaTime axis differs from its intervals")
              case EvidenceAnchor.MediaTime(_, _, axis, intervals) =>
                playback(bundle, stream, axis, intervals.intervals.toVector)
              case EvidenceAnchor.MediaPoint(_, _, at) =>
                playback(bundle, stream, at.axis, Vector.empty, Vector(at))
              case EvidenceAnchor.Shot(_, _, _, interval) =>
                playback(bundle, stream, interval.axis, Vector(interval))
              case EvidenceAnchor.Track(_, _, _, intervals) =>
                playback(bundle, stream, intervals.axis, intervals.intervals.toVector)

  private def within(interval: PlaybackInterval, extent: AxisExtent.PlaybackTicks): Boolean =
    interval.start >= extent.start && interval.endExclusive <= extent.endExclusive

  private def playback(
      bundle: SourceBundle,
      stream: SourceStream,
      axis: PresentationAxisId,
      intervals: Vector[PlaybackInterval],
      points: Vector[PlaybackInstant] = Vector.empty
  ): Either[DomainError, Unit] =
    if axis == stream.nativeAxis then
      stream.extent match
        case extent: AxisExtent.PlaybackTicks if intervals.forall(within(_, extent)) && points.forall(p => p.at >= extent.start && p.at < extent.endExclusive) => Right(())
        case _ => invalid("extent", "playback support escapes the selected native extent")
    else if axis != bundle.primaryAxis.id then
      invalid("stream-axis", "anchor axis does not belong to the selected stream")
    else
      val maps = bundle.mappings
        .filter {
          case repair: ClockRepair =>
            repair.relation.sourceAxis == stream.nativeAxis && repair.relation.targetAxis == axis
          case composition: TrackComposition =>
            composition.relation.sourceAxis == stream.nativeAxis && composition.relation.targetAxis == axis
          case _: EditionCorrespondence => false
        }
        .distinctBy(_.identity)
      maps match
        case Vector(mapping) =>
          bundle.primaryAxis.extent match
            case extent: AxisExtent.PlaybackTicks if intervals.forall(within(_, extent)) && points.forall(p => p.at >= extent.start && p.at < extent.endExclusive) =>
              if imageCovers(mapping, stream, intervals, points) then Right(())
              else
                invalid(
                  "mapping-image",
                  "support is not wholly covered by the selected stream mapping"
                )
            case _ => invalid("extent", "playback support escapes the primary extent")
        case _ =>
          invalid(
            "stream-axis",
            "support requires one unambiguous coordinate mapping from its stream"
          )

  // BigInt rational comparisons avoid rounding or overflow at image boundaries.
  private final case class Fraction(n: BigInt, d: BigInt):
    def *(that: Fraction): Fraction = Fraction(n * that.n, d * that.d)
    def +(that: Fraction): Fraction = Fraction(n * that.d + that.n * d, d * that.d)
    def atMost(value: Long): Boolean = n <= BigInt(value) * d
    def atLeast(value: Long): Boolean = n >= BigInt(value) * d
    def greaterThan(value: Long): Boolean = n > BigInt(value) * d

  private def fraction(value: ExactRational): Fraction =
    Fraction(BigInt(value.numerator), BigInt(value.denominator))

  private def imageCovers(
      mapping: CheckedMapping,
      stream: SourceStream,
      intervals: Vector[PlaybackInterval],
      points: Vector[PlaybackInstant]
  ): Boolean = stream.extent match
    case extent: AxisExtent.PlaybackTicks =>
      mapping match
        case repair: ClockRepair =>
          val factor = fraction(extent.timebase.scale) * fraction(repair.scale)
          val offset = fraction(repair.offset)
          val start = Fraction(BigInt(extent.start), 1) * factor + offset
          val end = Fraction(BigInt(extent.endExclusive), 1) * factor + offset
          intervals.forall(i => start.atMost(i.start) && end.atLeast(i.endExclusive)) &&
            points.forall(p => start.atMost(p.at) && end.greaterThan(p.at))
        case composition: TrackComposition =>
          val segments = composition.segments.toVector
          segments.forall(segment => within(segment.source, extent)) &&
          points.forall(p => segments.exists(_.target.contains(p.at))) &&
          intervals.forall { interval =>
            val image = segments.map(_.target).sortBy(i => (i.start, i.endExclusive))
            val coveredUntil = image.foldLeft(interval.start) { (covered, part) =>
              if part.start <= covered && part.endExclusive > covered then part.endExclusive
              else covered
            }
            coveredUntil >= interval.endExclusive
          }
        case _: EditionCorrespondence => false
    case _ => false
