package storymodel4s.codec

import munit.FunSuite
import storymodel4s.core.Checksum

/** Test-only capture of live synthetic record identity, with no alternate expected hash. */
class NumericalRecordCaptureSuite extends FunSuite:
  test("capture the historical adapter's live canonical record identity") {
    val record = MappingCodecFixture.record()
    val encoded = MappingCodecs.encode(record)
    println(
      s"NUMERICAL_RECORD=${Checksum.ofText(encoded).hex}:${record.digest.hex}:${encoded.length}"
    )
    assertEquals(
      MappingCodecs.decode(encoded, MappingCodecFixture.context()).map(MappingCodecs.encode),
      Right(encoded)
    )
  }
