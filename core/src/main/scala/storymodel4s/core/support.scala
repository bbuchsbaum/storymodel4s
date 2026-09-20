package storymodel4s.core

/** Source support retains its coordinate family; a projection is derived against a bundle. */
enum TypedSupport:
  case Text(spans: SpanSet)
  case Anchored(support: EvidenceSupport)

  /** Text coordinates only; anchored evidence is never cast to character offsets. */
  def textSpans: Option[SpanSet] = this match
    case Text(spans) => Some(spans)
    case Anchored(_) => None

/** Support on one declared primary axis. Playback intervals retain gaps. */
enum PrimaryProjection:
  case TextSpans(axis: PresentationAxisId, spans: SpanSet)
  case Playback(axis: PresentationAxisId, intervals: PlaybackIntervalSet)

  /** Exact hull bounds for ordering, without replacing the support's disjoint members. */
  def bounds: (Long, Long) = this match
    case TextSpans(_, spans) => (spans.minSpan.start.toLong, spans.minSpan.endExclusive.toLong)
    case Playback(_, intervals) =>
      (intervals.intervals.toVector.map(_.start).min, intervals.intervals.toVector.map(_.endExclusive).max)

object PrimaryProjection:
  /** S4a's checked text projection. Source extent remains a validator law. */
  def on(bundle: SourceBundle, support: TypedSupport): Either[DomainError, PrimaryProjection] =
    if bundle.primaryAxis.kind != AxisKind.TextCharacter then
      Left(SourceCanon.inv("projection/primary-kind", "text projection requires TextCharacter"))
    else support match
      case TypedSupport.Text(spans) => Right(PrimaryProjection.TextSpans(bundle.primaryAxis.id, spans))
      case TypedSupport.Anchored(_) =>
        Left(SourceCanon.inv("projection/support-kind", "text projection requires Text support"))

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
            case _: EvidenceAnchor.MediaTime =>
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
      intervals: Vector[PlaybackInterval]
  ): Either[DomainError, Unit] =
    if axis == stream.nativeAxis then
      stream.extent match
        case extent: AxisExtent.PlaybackTicks if intervals.forall(within(_, extent)) => Right(())
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
            case extent: AxisExtent.PlaybackTicks if intervals.forall(within(_, extent)) =>
              if imageCovers(mapping, stream, intervals) then Right(())
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

  private def fraction(value: ExactRational): Fraction =
    Fraction(BigInt(value.numerator), BigInt(value.denominator))

  private def imageCovers(
      mapping: CheckedMapping,
      stream: SourceStream,
      intervals: Vector[PlaybackInterval]
  ): Boolean = stream.extent match
    case extent: AxisExtent.PlaybackTicks =>
      mapping match
        case repair: ClockRepair =>
          val factor = fraction(extent.timebase.scale) * fraction(repair.scale)
          val offset = fraction(repair.offset)
          val start = Fraction(BigInt(extent.start), 1) * factor + offset
          val end = Fraction(BigInt(extent.endExclusive), 1) * factor + offset
          intervals.forall(i => start.atMost(i.start) && end.atLeast(i.endExclusive))
        case composition: TrackComposition =>
          val segments = composition.segments.toVector
          segments.forall(segment => within(segment.source, extent)) &&
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
