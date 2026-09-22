package storymodel4s.codec

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.features.MissingReason
import storymodel4s.laws.MappingMiniature
import storymodel4s.recall.*

class MappingCodecSuite extends FunSuite:
  private lazy val miniature = MappingMiniature.record
  private def context(record: MappingResult): ExpectedMappingContext =
    ExpectedMappingContext(record.inventory, record.source)
  private def roundTrip(record: MappingResult, expected: ExpectedMappingContext): MappingResult =
    val encoded = MappingCodecs.encode(record)
    val decoded = MappingCodecs.decode(encoded, expected).fold(e => fail(e.message), identity)
    assertEquals(decoded.digest, record.digest)
    assertEquals(MappingCodecs.encode(decoded), encoded)
    decoded
  test("miniature round-trips and retains p6") {
    val decoded = roundTrip(miniature, context(miniature))
    assertEquals(decoded.outcomes.size, 8)
    assert(
      decoded
        .outcome(RecallUnitId.unsafe("p6"))
        .get
        .processing
        .isInstanceOf[ProcessingStatus.Failed]
    )
    assertEquals(decoded.derivation, DerivationSource.NoDerivedValues)
  }
  test("historical text round-trips identically") {
    val record = MappingCodecFixture.record()
    val decoded = roundTrip(record, MappingCodecFixture.context())
    assert(
      decoded.outcomes.flatMap(_.links).exists(_.fidelity.isInstanceOf[FidelityStatus.Assessed])
    )
    decoded.outcomes.foreach { row =>
      assertEquals(
        row.measures.posterior.get.mass.map((s, p) => s -> java.lang.Double.doubleToLongBits(p)),
        MappingCodecFixture.result.posterior
          .row(row.unit)
          .get
          .mass
          .map((s, p) => s -> java.lang.Double.doubleToLongBits(p))
      )
    }
  }
  test("each unmixed result needs its own context") {
    val a = MappingCodecFixture.record()
    val b = MappingCodecFixture.record(MappingCodecFixture.variant)
    roundTrip(a, MappingCodecFixture.context())
    roundTrip(b, MappingCodecFixture.context(MappingCodecFixture.variant))
    assertEquals(
      MappingCodecs
        .decode(MappingCodecs.encode(a), MappingCodecFixture.context(MappingCodecFixture.variant))
        .left
        .toOption,
      Some(MappingCodecError.DigestMismatch("result_digest"))
    )
    assertEquals(
      MappingCodecs.decode(MappingCodecs.encode(b), MappingCodecFixture.context()).left.toOption,
      Some(MappingCodecError.DigestMismatch("result_digest"))
    )
  }
  test("posterior needs result context") {
    val record = MappingCodecFixture.record()
    assertEquals(
      MappingCodecs.decode(MappingCodecs.encode(record), context(record)).left.toOption,
      Some(MappingCodecError.ContextRequired("derivation_source"))
    )
  }
  test("Declared twin context refuses") {
    val twin = MappingCodecFixture.view.copy(nodes =
      MappingCodecFixture.view.nodes.map(
        _.copy(propositional = PropositionalScope.Undeclared(MissingReason.ProviderAbstained))
      )
    )
    val record = MappingCodecFixture.record()
    assertEquals(
      MappingCodecs
        .decode(MappingCodecs.encode(record), MappingCodecFixture.context(v = twin))
        .left
        .toOption,
      Some(MappingCodecError.Rejected(MappingRefusal.BindingMismatch("scopeDigest")))
    )
    val source = MappingCodecFixture.sourceFor(twin)
    roundTrip(
      MappingCodecFixture.record(v = twin, source = source),
      MappingCodecFixture.context(v = twin, source = source)
    )
  }
