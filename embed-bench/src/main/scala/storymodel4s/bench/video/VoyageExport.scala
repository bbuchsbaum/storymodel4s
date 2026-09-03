package storymodel4s.bench.video

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import storymodel4s.align.{AlignmentRow, HsmmResult, SourceNodeRef}
import storymodel4s.codec.Canonical
import storymodel4s.codec.VoyageCodecs.given
import storymodel4s.core.{Checksum, DomainError}
import storymodel4s.recall.RecallUnit
import storymodel4s.view.*

/** Builds the Recall Voyage document (ADR 0002 §14) from a timed-source run and writes it beside
  * the report.
  *
  * Why this lives in the bench and not in `view`: the voyage's source clock is a fact about a
  * presented film, and only the adapter that built the [[TimedSourceView]] knows each node's media
  * locus and each part's timebase. Everything here is a re-statement of what the run already holds;
  * the proven join (`RecallVoyageInput.of`) is what refuses a run whose refs, rows and decisions do
  * not agree, and the document is written only after it holds.
  */
object VoyageExport:

  /** Seconds on one continuous source clock: parts in id order, each placed after the previous. */
  final case class SourceClock(
      offsets: Map[String, Double],
      seconds: (String, Long) => Option[Double]
  ):
    def at(locus: MediaLocus): Option[ClockSpan] =
      for
        start <- seconds(locus.part, locus.startTick)
        end <- seconds(locus.part, locus.endTick)
        offset <- offsets.get(locus.part)
        span <- ClockSpan.of(start + offset, end + offset).toOption
      yield span

  object SourceClock:
    def of(
        built: TimedSourceView.Built,
        seconds: (String, Long) => Option[Double]
    ): SourceClock =
      val parts = built.media.values.map(_.part).toVector.distinct.sorted
      val durations = parts.map { part =>
        part -> built.media.values
          .filter(_.part == part)
          .flatMap(l => seconds(part, l.endTick))
          .maxOption
          .getOrElse(0.0)
      }.toMap
      val offsets = parts
        .foldLeft((0.0, Map.empty[String, Double])) { case ((acc, m), part) =>
          (acc + durations(part), m.updated(part, acc))
        }
        ._2
      SourceClock(offsets, seconds)

  /** Every segment as a level-0 node and every group as a level-1 node on the source clock. */
  def timeline(
      built: TimedSourceView.Built,
      clock: SourceClock
  ): Either[DomainError, SourceTimeline] =
    val segments = built.segmentByRef.toVector.sortBy(_._1.key)
    val missing = segments.collectFirst {
      case (ref, seg) if seg.locus.flatMap(clock.at).isEmpty => ref
    }
    missing match
      case Some(ref) =>
        Left(
          DomainError.InvariantViolation(
            s"bench/voyage/timeline/${ref.key}",
            "segment has no media locus on a timed axis, so it has no place on the source clock"
          )
        )
      case None =>
        val leaves = segments.map { case (ref, seg) =>
          SourceTimelineNode(
            ref,
            0,
            seg.group.map(_.ordinal),
            seg.locus.flatMap(clock.at).get,
            s"segment ${seg.ordinal}"
          )
        }
        val byGroup = leaves.groupBy(_.group).collect { case (Some(g), members) => g -> members }
        val groupSpan = byGroup.view
          .mapValues(members =>
            ClockSpan
              .of(members.map(_.span.start.value).min, members.map(_.span.end.value).max)
              .toOption
              .get
          )
          .toMap
        val groups = built.groupByRef.values.toVector
          .distinctBy(_.ordinal)
          .sortBy(_.ordinal)
          .flatMap(g =>
            groupSpan.get(g.ordinal).map(span => SourceTimelineGroup(g.ordinal, g.label, span))
          )
        val groupNodes = built.groupByRef.toVector.sortBy(_._1.key).flatMap { case (ref, g) =>
          groupSpan
            .get(g.ordinal)
            .map(span => SourceTimelineNode(ref, 1, Some(g.ordinal), span, g.label))
        }
        SourceTimeline.of(leaves ++ groupNodes, groups)

  /** What the run concluded per unit, with the origin the row itself justifies. */
  def decisions(
      built: TimedSourceView.Built,
      rows: Vector[AlignmentRow],
      chosen: Vector[Option[SourceNodeRef]],
      decoded: Vector[MonotoneScene.Decision]
  ): Vector[VoyageDecision] =
    rows.zipWithIndex.map { case (row, i) =>
      val anchor = chosen(i)
      val origin =
        if anchor == row.mapSource then AnchorOrigin.PosteriorArgmax
        else if anchor.exists(ref => row.anchorMass.getOrElse(ref, 0.0) == 0.0) then
          AnchorOrigin.DecodeFilled
        else AnchorOrigin.DecodeBound
      val group =
        decoded.lift(i).map(_.scene).orElse(anchor.flatMap(MonotoneScene.sceneOf(built, _)))
      VoyageDecision(row.unit, anchor, group, origin)
    }

  def units(
      ordered: Vector[RecallUnit],
      timings: Map[storymodel4s.recall.RecallUnitId, RecallTiming.UnitTiming]
  ): Vector[VoyageUnit] =
    ordered.map { u =>
      val t = timings.getOrElse(u.id, RecallTiming.UnitTiming(None, None))
      VoyageUnit(
        u.id,
        u.ordinal,
        u.text,
        t.onsetSeconds.flatMap(s => Seconds.of(s).toOption),
        t.lastWordOnsetSeconds.flatMap(s => Seconds.of(s).toOption)
      )
    }

  def document(
      built: TimedSourceView.Built,
      ordered: Vector[RecallUnit],
      timings: Map[storymodel4s.recall.RecallUnitId, RecallTiming.UnitTiming],
      result: HsmmResult,
      chosen: Vector[Option[SourceNodeRef]],
      decoded: Vector[MonotoneScene.Decision],
      seconds: (String, Long) => Option[Double],
      coding: Option[IndependentCoding],
      configRendering: String
  ): Either[DomainError, RecallVoyageDocument] =
    val clock = SourceClock.of(built, seconds)
    val us = units(ordered, timings)
    val length =
      us.flatMap(u => u.lastWordOnset.orElse(u.onset)).map(_.value).maxOption.getOrElse(0.0)
    for
      tl <- timeline(built, clock)
      recallLength <- Seconds.of(length)
      input <- RecallVoyageInput.of(
        us,
        result.posterior,
        tl,
        decisions(built, result.posterior.rows, chosen, decoded),
        coding,
        recallLength
      )
      provenance <- ViewProvenance.of(
        result.viewFingerprint.checksum,
        None,
        ViewBasis.AlignmentRun,
        VoyageCompiler.compilerVersion,
        Checksum.ofText(configRendering)
      )
    yield RecallVoyageDocument(input, provenance)

  /** Canonical JSON beside the report: `<report>.voyage.json`. */
  def write(reportPath: Path, doc: RecallVoyageDocument): Path =
    val path = reportPath.resolveSibling(reportPath.getFileName.toString + ".voyage.json")
    Files.write(path, Canonical.encode(doc).getBytes(StandardCharsets.UTF_8))
    path
