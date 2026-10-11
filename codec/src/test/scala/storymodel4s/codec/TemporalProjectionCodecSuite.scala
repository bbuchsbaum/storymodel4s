package storymodel4s.codec

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.laws.MappingMiniature

class TemporalProjectionCodecSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  private def request(m: MappingResult, share: Double = 0.5): TemporalProjection.Request =
    val bundle = m.source.bundles.head.asInstanceOf[BundleEntry.Media].bundle
    TemporalProjection.Request(
      TemporalSupport.Selection.Part(bundle.identity),
      Vector(
        TemporalQuery.Declaration(
          SourceNodeRef.Situation(SituationId.unsafe("e2")),
          TemporalQuery.Assumption.SuppliedSupportContainsReferent,
          TemporalQuery.Allocation.Mixture(share)
        )
      ),
      Vector(10L -> 15L)
    )

  private def projection(share: Double = 0.5): (MappingResult, TemporalProjection.Projection) =
    val mapping = TemporalQueryFixture.mapping
    mapping -> right(
      TemporalProjection.compile(
        mapping,
        TemporalQuery.Measure.NormalizedScoreMass,
        request(mapping, share),
        None
      )
    )

  test("request and checked projection roundtrip with canonical binary64 mixtures") {
    Vector(-0.0, 0.5).foreach { share =>
      val (mapping, value) = projection(share)
      val requestText = TemporalProjectionCodecs.encodeRequest(value.request)
      assertEquals(TemporalProjectionCodecs.decodeRequest(requestText, mapping.source), Right(value.request))
      val text = TemporalProjectionCodecs.encode(value)
      assertEquals(TemporalProjectionCodecs.decode(text, mapping, None).map(TemporalProjectionCodecs.encode), Right(text))
    }
  }

  test("numeric ticks, altered cells, and foreign mapping context refuse") {
    val (mapping, value) = projection()
    val requestText = TemporalProjectionCodecs.encodeRequest(value.request)
    val numericTick = requestText.replace("\"start\":\"10\"", "\"start\":9007199254740993")
    assert(TemporalProjectionCodecs.decodeRequest(numericTick, mapping.source).isLeft)

    val text = TemporalProjectionCodecs.encode(value)
    val rehashedCell = text.replaceFirst("\"mass\":\"0x[0-9a-f]{16}\"", "\"mass\":\"0x3ff0000000000000\"")
    assertNotEquals(rehashedCell, text)
    assert(TemporalProjectionCodecs.decode(rehashedCell, mapping, None).isLeft)
    assert(TemporalProjectionCodecs.decode(text, MappingMiniature.record, None).isLeft)
  }
