package storymodel4s.codec

import cats.data.NonEmptyVector
import storymodel4s.align.*
import storymodel4s.core.*

/** Checked producer fixtures for consumers of sparse temporal-projection/v1 JSON. */
object TemporalProjectionFixtures:
  private def checked[A](value: Either[?, A]): A =
    value.fold(error => throw IllegalStateException(error.toString), identity)

  private val mapping = TemporalQueryFixture.mapping
  private val bundle = mapping.source.bundles.head.asInstanceOf[BundleEntry.Media].bundle
  private val target = SourceNodeRef.Situation(SituationId.unsafe("e2"))

  private def projection(
      bins: Vector[(Long, Long)],
      allocation: TemporalQuery.Allocation
  ): TemporalProjection.Projection =
    checked(
      TemporalProjection.compile(
        mapping,
        TemporalQuery.Measure.NormalizedScoreMass,
        TemporalProjection.Request(
          TemporalSupport.Selection.Part(bundle.identity),
          Vector(
            TemporalQuery.Declaration(
              target,
              TemporalQuery.Assumption.SuppliedSupportContainsReferent,
              allocation
            )
          ),
          bins
        ),
        None
      )
    )

  private def miniature(
      value: MappingResult,
      target: SourceNodeRef,
      bundle: SourceBundle,
      bins: Vector[(Long, Long)],
      allocation: TemporalQuery.Allocation
  ): TemporalProjection.Projection =
    checked(
      TemporalProjection.compile(
        value,
        TemporalQuery.Measure.NormalizedScoreMass,
        TemporalProjection.Request(
          TemporalSupport.Selection.Part(bundle.identity),
          Vector(
            TemporalQuery.Declaration(
              target,
              TemporalQuery.Assumption.SuppliedSupportContainsReferent,
              allocation
            )
          ),
          bins
        ),
        None
      )
    )

  private def anchored(
      b: SourceBundle,
      intervals: Vector[(Long, Long)],
      points: Vector[Long]
  ): SourceSupportStatus =
    val anchors = intervals.map { (start, end) =>
      EvidenceAnchor.MediaTime(
        b.id,
        b.streams.head.id,
        b.primaryAxis.id,
        PlaybackIntervalSet.one(checked(PlaybackInterval.on(b.primaryAxis, start, end)))
      )
    } ++ points.map(p =>
      EvidenceAnchor.MediaPoint(
        b.id,
        b.streams.head.id,
        checked(PlaybackInstant.on(b.primaryAxis, p))
      )
    )
    SourceSupportStatus.located(TypedSupport.Anchored(checked(EvidenceSupport.of(b, anchors))))

  private def withSource(source: SourceRepresentation): MappingResult =
    checked(
      MappingResult.checked(
        mapping.inventory,
        source,
        mapping.policies,
        mapping.roles,
        mapping.ledger,
        mapping.outcomes
      )
    )

  private def shaped(
      intervals: Vector[(Long, Long)],
      points: Vector[Long],
      allocation: TemporalQuery.Allocation
  ): TemporalProjection.Projection =
    val physical = mapping.source.targets
      .map(t => t.ref -> t.sourceSupport)
      .toMap
      .updated(target, anchored(bundle, intervals, points))
    val source = checked(
      SourceRepresentation.of(
        MappingCompositionFixture.view,
        mapping.source.bundles,
        None,
        physical
      )
    )
    miniature(
      withSource(source),
      target,
      bundle,
      Vector(10L -> 11L, 11L -> 19L, 19L -> 20L),
      allocation
    )

  private def repeated: Vector[(String, TemporalProjection.Projection)] =
    val axis = checked(
      SourceBundle.editionPlaybackAxis(
        EditionId.unsafe("synthetic-repeat-projection"),
        bundle.streams,
        bundle.authorityTracks,
        0L,
        80L,
        MappingCompositionFixture.timebase
      )
    )
    val segments = Vector(0L, 40L).zipWithIndex.map { (start, i) =>
      checked(
        CompositionSegment.of(
          checked(PlaybackInterval.on(bundle.primaryAxis, 0L, 40L)),
          checked(PlaybackInterval.on(axis, start, start + 40L)),
          OccurrenceId.unsafe(s"repeat-$i")
        )
      )
    }
    val receipt = checked(
      SourceDerivationReceipt.of(
        "synthetic-repeat-projection/v1",
        "two presentations",
        Vector(bundle.identity)
      )
    )
    val transform = checked(TrackComposition.of(bundle.primaryAxis.id, axis.id, segments, receipt))
    val composed = checked(
      SourceBundle.of(
        axis.edition,
        SourceKind.FilmEdition,
        bundle.streams,
        axis,
        bundle.authorityTracks,
        Vector(transform)
      )
    )
    val physical = mapping.source.targets
      .map(t => t.ref -> SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource))
      .toMap
      .updated(target, anchored(composed, Vector(10L -> 20L, 50L -> 60L), Vector.empty))
    val source = checked(
      SourceRepresentation.of(
        MappingCompositionFixture.view,
        NonEmptyVector.one(BundleEntry.media(bundle)),
        Some(checked(DeclaredComposition.of(composed, NonEmptyVector.one(bundle)))),
        physical
      )
    )
    val m = withSource(source)
    segments.zipWithIndex.map { (segment, i) =>
      s"repeated-media-occurrence-$i" -> checked(
        TemporalProjection.compile(
          m,
          TemporalQuery.Measure.NormalizedScoreMass,
          TemporalProjection.Request(
            TemporalSupport.Selection.Occurrence(transform.identity, segment.occurrence),
            Vector(
              TemporalQuery.Declaration(
                target,
                TemporalQuery.Assumption.SuppliedSupportContainsReferent,
                TemporalQuery.Allocation.UniformIntervals
              )
            ),
            Vector((10L + i * 40L) -> (20L + i * 40L))
          ),
          None
        )
      )
    }

  /** Each name identifies the producer-side geometry and ledger witness used by an external reader.
    * `MappingMiniature` also retains repeated and failed recall units, plus explicit external mass.
    */
  private val large = MappingCompositionFixture.largeCoordinates
  private val largeBundle = large.source.bundles.toVector.collectFirst {
    case media: BundleEntry.Media => media.bundle
  }.get

  val projections: Vector[(String, TemporalProjection.Projection)] = Vector(
    "narrow" -> projection(Vector(10L -> 11L), TemporalQuery.Allocation.UniformIntervals),
    "broad" -> projection(Vector(10L -> 20L), TemporalQuery.Allocation.UniformIntervals),
    "disjoint" -> projection(
      Vector(10L -> 11L, 14L -> 15L),
      TemporalQuery.Allocation.UniformIntervals
    ),
    "incompatible-mixture" -> projection(Vector(10L -> 15L), TemporalQuery.Allocation.Mixture(0.5)),
    "support-only" -> projection(Vector(10L -> 15L), TemporalQuery.Allocation.SupportOnly),
    "multimodal-disjoint-support" -> shaped(
      Vector(10L -> 11L, 19L -> 20L),
      Vector.empty,
      TemporalQuery.Allocation.UniformIntervals
    ),
    "mixture-half" -> shaped(
      Vector(10L -> 20L),
      Vector(15L),
      TemporalQuery.Allocation.Mixture(0.5)
    ),
    "external-mass" -> projection(Vector(10L -> 20L), TemporalQuery.Allocation.UniformIntervals),
    "repeated-target-units" -> projection(
      Vector(10L -> 20L),
      TemporalQuery.Allocation.UniformIntervals
    ),
    "point-only-support" -> shaped(
      Vector.empty,
      Vector(15L),
      TemporalQuery.Allocation.UniformPoints
    ),
    "failed-unit-rows" -> projection(Vector(10L -> 20L), TemporalQuery.Allocation.UniformIntervals),
    "large-tick-9007199254740993" -> miniature(
      withSource(large.source),
      target,
      largeBundle,
      Vector(MappingCompositionFixture.bigTick -> (MappingCompositionFixture.bigTick + 1L)),
      TemporalQuery.Allocation.UniformPoints
    )
  ) ++ repeated

  def main(args: Array[String]): Unit =
    require(args.isEmpty, "no arguments")
    projections.foreach { (name, value) =>
      println(
        "TEMPORAL_PROJECTION_FIXTURE\t" + name + "\t" + TemporalProjectionCodecs.encode(value)
      )
    }
