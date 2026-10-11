package storymodel4s.codec

import storymodel4s.align.*
import storymodel4s.core.*

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

  /** Successful projections retain untimed rows, external mass, and sparse zero elision. */
  val projections: Vector[(String, TemporalProjection.Projection)] = Vector(
    "narrow" -> projection(Vector(10L -> 11L), TemporalQuery.Allocation.UniformIntervals),
    "broad" -> projection(Vector(10L -> 20L), TemporalQuery.Allocation.UniformIntervals),
    "disjoint" -> projection(Vector(10L -> 11L, 14L -> 15L), TemporalQuery.Allocation.UniformIntervals),
    "mixture-half" -> projection(Vector(10L -> 15L), TemporalQuery.Allocation.Mixture(0.5)),
    "support-only" -> projection(Vector(10L -> 15L), TemporalQuery.Allocation.SupportOnly)
  )

  def main(args: Array[String]): Unit =
    require(args.isEmpty, "no arguments")
    projections.foreach { (name, value) =>
      println("TEMPORAL_PROJECTION_FIXTURE\t" + name + "\t" + TemporalProjectionCodecs.encode(value))
    }
