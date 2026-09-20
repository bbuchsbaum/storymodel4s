package storymodel4s.corpus.intake

import storymodel4s.core.*

/** One checked composed edition, with original part-native evidence retained for every unit. */
final class SherlockSourceAtlas private (
    val atlas: AnchoredNarrativeAtlas,
    val rows: Map[Int, NarrativeProposalUnit],
    val scenes: Map[Int, NarrativeProposalUnit],
    val compositions: Map[String, TrackComposition]
)

object SherlockSourceAtlas:
  private def refuse(detail: String): Left[DomainError, Nothing] =
    Left(DomainError.InvariantViolation("sherlock/source-atlas", detail))

  private def asLong(value: BigInt): Either[DomainError, Long] =
    if value.isValidLong then Right(value.toLong) else refuse("composed coordinate overflows Long")

  private def project(tick: Long, segment: CompositionSegment): Either[DomainError, Long] =
    val source = segment.source
    val target = segment.target
    if tick < source.start || tick > source.endExclusive then refuse("coordinate escapes occurrence")
    else
      val numerator = (BigInt(tick) - source.start) * (BigInt(target.endExclusive) - target.start)
      val denominator = BigInt(source.endExclusive) - source.start
      if numerator % denominator != 0 then refuse("composition requires an integral target tick")
      else asLong(BigInt(target.start) + numerator / denominator)

  def of(input: SherlockAnnotations.Atlas): Either[DomainError, SherlockSourceAtlas] =
    val parts = Vector(input.manifest.partA -> input.partABundle,
      input.manifest.partB -> input.partBBundle).sortBy(_._1.presentationOrdinal)
    if parts.map(_._1.presentationOrdinal) != Vector(1, 2) || parts.map(_._1.partId).distinct.size != 2 then
      refuse("parts require unique presentation ordinals 1 and 2 and unique identities")
    else if parts.exists(_._1.ticksPerSecond <= 0L) then refuse("part timebase is not positive")
    else
      val rate = parts.map(p => BigInt(p._1.ticksPerSecond)).reduce((a, b) => a / a.gcd(b) * b)
      val lengths = parts.map { (part, _) => BigInt(part.durationTicks) * rate / part.ticksPerSecond }
      val streams = parts.flatMap(_._2.streams)
      val authority = streams.map(_.id)
      for
        ticksPerSecond <- asLong(rate)
        total <- asLong(lengths.sum)
        timebase <- RationalTimebase.of(1L, ticksPerSecond)
        edition <- EditionId.from("sherlock-nn2017-composed")
        primary <- SourceBundle.editionPlaybackAxis(edition, streams, authority, 0L, total, timebase)
        receipt <- SourceDerivationReceipt.of("sherlock/presentation-composition/v1",
          "manifest ordinal concatenation; exact integral target ticks; native loci retained",
          Vector(input.repairRecord.checksum, input.manifest.annotationSha256) ++ parts.map(_._2.identity))
        segments <- parts.zip(lengths.scanLeft(BigInt(0))(_ + _)).zip(lengths)
          .foldLeft[Either[DomainError, Vector[(String, CompositionSegment)]]](Right(Vector.empty)) {
            case (acc, (((part, bundle), start), length)) =>
              for
                previous <- acc
                source <- PlaybackInterval.on(bundle.primaryAxis, 0L, part.durationTicks)
                first <- asLong(start)
                last <- asLong(start + length)
                target <- PlaybackInterval.on(primary, first, last)
                occurrence <- OccurrenceId.from(s"sherlock-part-${part.presentationOrdinal}")
                segment <- CompositionSegment.of(source, target, occurrence)
              yield previous :+ (part.partId -> segment)
          }
        mappings <- segments.foldLeft[Either[DomainError, Map[String, TrackComposition]]](Right(Map.empty)) {
          case (acc, (part, segment)) => for
            previous <- acc
            mapping <- TrackComposition.of(segment.source.axis, primary.id, Vector(segment), receipt)
          yield previous.updated(part, mapping)
        }
        bundle <- SourceBundle.of(Some(edition), SourceKind.FilmEdition, streams, primary,
          authority, parts.map(p => mappings(p._1.partId)))
        rows <- input.rows.foldLeft[Either[DomainError, Map[Int, NarrativeProposalUnit]]](Right(Map.empty)) {
          (acc, row) =>
            for
              previous <- acc
              locus <- input.mediaByRow.get(row.row).toRight(DomainError.InvariantViolation(
                "sherlock/source-atlas", s"row ${row.row} has no admitted locus"))
              segment <- segments.find(_._1 == locus.part).map(_._2).toRight(DomainError.InvariantViolation(
                "sherlock/source-atlas", "row names an unknown part occurrence"))
              stream <- streams.find(_.nativeAxis == segment.source.axis).toRight(DomainError.InvariantViolation(
                "sherlock/source-atlas", "part stream is missing"))
              anchors <- locus match
                case SherlockAnnotations.MediaLocus.Extent(_, interval) =>
                  for
                    start <- project(interval.start, segment)
                    end <- project(interval.endExclusive, segment)
                    projected <- PlaybackInterval.on(primary, start, end)
                  yield Vector(
                    EvidenceAnchor.MediaTime(bundle.id, stream.id, interval.axis, PlaybackIntervalSet.one(interval)),
                    EvidenceAnchor.MediaTime(bundle.id, stream.id, primary.id, PlaybackIntervalSet.one(projected)))
                case SherlockAnnotations.MediaLocus.Instant(_, at) =>
                  for
                    tick <- project(at.at, segment)
                    projected <- PlaybackInstant.on(primary, tick)
                  yield Vector(EvidenceAnchor.MediaPoint(bundle.id, stream.id, at),
                    EvidenceAnchor.MediaPoint(bundle.id, stream.id, projected))
              support <- EvidenceSupport.of(bundle, anchors)
              id <- NarrativeProposalUnitId.from(f"sherlock:row:${row.row}%04d")
            yield previous.updated(row.row, NarrativeProposalUnit.of(id, support, None))
        }
        scenes <- input.scenes.foldLeft[Either[DomainError, Map[Int, NarrativeProposalUnit]]](Right(Map.empty)) {
          (acc, scene) => for
            previous <- acc
            support <- EvidenceSupport.of(bundle, input.rows.filter(r => r.row >= scene.firstRow && r.row <= scene.lastRow)
              .flatMap(r => rows(r.row).support.anchors.toVector))
            id <- NarrativeProposalUnitId.from(f"sherlock:scene:${scene.ordinal}%03d")
          yield previous.updated(scene.ordinal, NarrativeProposalUnit.of(id, support, None))
        }
        atlas <- AnchoredNarrativeAtlas.of(bundle,
          rows.toVector.sortBy(_._1).map(_._2) ++ scenes.toVector.sortBy(_._1).map(_._2), None)
      yield new SherlockSourceAtlas(atlas, rows, scenes, mappings)
