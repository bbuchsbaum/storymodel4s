package storymodel4s.pipeline

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Consumer-side seam: this package may use, but does not define, the temporal projection API. */
class TemporalProjectionConsumerContractSuite extends FunSuite:

  test("the existing temporal query dependency is callable from this consumer"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
           import storymodel4s.core.Checksum
           import storymodel4s.recall.RecallUnitId
           TemporalQuery.prepare(
             ??? : MappingResult,
             ??? : RecallUnitId,
             TemporalQuery.Measure.NormalizedScoreMass,
             TemporalSupport.Selection.Part(??? : Checksum),
             Vector.empty[TemporalQuery.Declaration]
           )"""
      ),
      Nil
    )

  test("a caller outside align can compile the agreed temporal projection request and compiler"):
    val errors = typeCheckErrors(
      """import storymodel4s.align.*
         import storymodel4s.core.Checksum
         import storymodel4s.recall.RecallUnitId
         val request = TemporalProjection.Request(
           TemporalSupport.Selection.Part(??? : Checksum),
           Vector.empty[TemporalQuery.Declaration],
           Vector(0L -> 1L)
         )
         TemporalProjection.compile(
           ??? : MappingResult,
           TemporalQuery.Measure.NormalizedScoreMass,
           request,
           None
         )"""
    )
    assertEquals(errors, Nil, s"temporal projection consumer seam is missing: $errors")
