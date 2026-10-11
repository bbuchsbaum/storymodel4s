package storymodel4s.codec

import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.laws.MappingMiniature

/** Checked producer fixtures for consumers of sparse temporal-projection/v1 JSON. */
object TemporalProjectionFixtures:
  private def checked[A](value: Either[?, A]): A = value.fold(error => throw IllegalStateException(error.toString), identity)

  private val mapping = TemporalQueryFixture.mapping
  private val bundle = mapping.source.bundles.head.asInstanceOf[BundleEntry.Media].bundle
  private val target = SourceNodeRef.Situation(SituationId.unsafe("e2"))

  private def projection(
      bins: Vector[(Long, Long)],
      allocation: TemporalQuery.Allocation
  ): TemporalProjection.Projection =
    checked(TemporalProjection.compile(
      mapping,
      TemporalQuery.Measure.NormalizedScoreMass,
      TemporalProjection.Request(
        TemporalSupport.Selection.Part(bundle.identity),
        Vector(TemporalQuery.Declaration(
          target,
          TemporalQuery.Assumption.SuppliedSupportContainsReferent,
          allocation
        )),
        bins
      ),
      None
    ))

  private def miniature(
      value: MappingResult,
      target: SourceNodeRef,
      bundle: SourceBundle,
      bins: Vector[(Long, Long)],
      allocation: TemporalQuery.Allocation = TemporalQuery.Allocation.UniformIntervals
  ): TemporalProjection.Projection =
    checked(TemporalProjection.compile(
      value,
      TemporalQuery.Measure.NormalizedScoreMass,
      TemporalProjection.Request(
        TemporalSupport.Selection.Part(bundle.identity),
        Vector(TemporalQuery.Declaration(
          target,
          TemporalQuery.Assumption.SuppliedSupportContainsReferent,
          allocation
        )),
        bins
      ),
      None
    ))

  private def multimodal: TemporalProjection.Projection =
    checked(TemporalProjection.compile(
      mapping,
      TemporalQuery.Measure.NormalizedScoreMass,
      TemporalProjection.Request(
        TemporalSupport.Selection.Part(bundle.identity),
        Vector(target, SourceNodeRef.Situation(SituationId.unsafe("e4"))).map(ref =>
          TemporalQuery.Declaration(
            ref,
            TemporalQuery.Assumption.SuppliedSupportContainsReferent,
            TemporalQuery.Allocation.UniformIntervals
          )
        ),
        Vector(10L -> 15L)
      ),
      None
    ))

  /** Each name identifies the producer-side geometry and ledger witness used by an external reader.
    * `MappingMiniature` also retains repeated and failed recall units, plus explicit external mass.
    */
  private val miniatureRecord = MappingMiniature.record
  private val miniatureBundles = miniatureRecord.source.bundles.toVector.collect {
    case media: BundleEntry.Media => media.bundle
  }
  private val miniatureE1 = SourceNodeRef.Situation(SituationId.unsafe("e1"))
  private val miniatureE2 = SourceNodeRef.Situation(SituationId.unsafe("e2"))
  private val miniatureE8 = SourceNodeRef.Situation(SituationId.unsafe("e8"))
  private val large = MappingCompositionFixture.largeCoordinates
  private val largeBundle = large.source.bundles.toVector.collectFirst {
    case media: BundleEntry.Media => media.bundle
  }.get

  val projections: Vector[(String, TemporalProjection.Projection)] = Vector(
    "narrow" -> projection(Vector(10L -> 11L), TemporalQuery.Allocation.UniformIntervals),
    "broad" -> projection(Vector(10L -> 20L), TemporalQuery.Allocation.UniformIntervals),
    "disjoint" -> projection(Vector(10L -> 11L, 14L -> 15L), TemporalQuery.Allocation.UniformIntervals),
    "mixture-half" -> projection(Vector(10L -> 15L), TemporalQuery.Allocation.Mixture(0.5)),
    "support-only" -> projection(Vector(10L -> 15L), TemporalQuery.Allocation.SupportOnly),
    "multimodal-e2-e4" -> multimodal,
    "external-mass" -> miniature(miniatureRecord, miniatureE1, miniatureBundles.head, Vector(0L -> 10L)),
    "repeated-target-units" -> miniature(miniatureRecord, miniatureE2, miniatureBundles.head, Vector(10L -> 20L)),
    "point-only-support" -> miniature(
      miniatureRecord,
      miniatureE8,
      miniatureBundles(1),
      Vector(30L -> 31L),
      TemporalQuery.Allocation.UniformPoints
    ),
    "failed-unit-rows" -> miniature(miniatureRecord, miniatureE1, miniatureBundles.head, Vector(0L -> 10L)),
    "large-tick-9007199254740993" -> miniature(
      large,
      SourceNodeRef.Situation(SituationId.unsafe("e1")),
      largeBundle,
      Vector(MappingCompositionFixture.bigTick -> (MappingCompositionFixture.bigTick + 1L))
    )
  )

  def main(args: Array[String]): Unit =
    require(args.isEmpty, "no arguments")
    projections.foreach { (name, value) =>
      println("TEMPORAL_PROJECTION_FIXTURE\t" + name + "\t" + TemporalProjectionCodecs.encode(value))
    }
