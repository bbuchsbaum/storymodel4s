package storymodel4s.codec

import munit.FunSuite
import storymodel4s.core.Checksum
import storymodel4s.laws.MappingMiniature

/** Canonical byte pins; each fixture is rebuilt with public checked producers. */
class MappingCodecDigestSuite extends FunSuite:
  test("miniature canonical bytes and record identity are pinned") {
    val record = MappingMiniature.record
    val encoded = MappingCodecs.encode(record)
    assertEquals(
      Checksum.ofText(encoded).hex,
      "11472f2ce40607676e3a7052af9e80a5fae388c8893dd27dcc1043aa81ec6286"
    )
    assertEquals(
      record.digest.hex,
      "c33d007b01bc22fffac71930fa166487af1fb22cb15318f77efcd01482626e2c"
    )
    assertEquals(encoded.length, 45099)
  }
  test("sherlock-shaped canonical bytes and record identity are pinned") {
    val record = MappingCompositionFixture.record
    val encoded = MappingCodecs.encode(record)
    assertEquals(
      Checksum.ofText(encoded).hex,
      "bc14faf9eda49e5e8536d6ba61f987acd7f2e0fe38bbdaa6619e9fe5d261d3ce"
    )
    assertEquals(
      record.digest.hex,
      "203da0087b5e60aacfaf151c9bf11aa3af6d0731519f5b1b59d1a018105f2237"
    )
    assertEquals(encoded.length, 50731)
  }
  // The owned producer has one exact current pin on every target. Historical runtime Native
  // bytes19cd12e4.../recorda171dc25... remain retained in git and numerical-policy evidence.
  test("historical canonical bytes and record identity are pinned") {
    val record = MappingCodecFixture.record()
    val encoded = MappingCodecs.encode(record)
    assertEquals(
      Checksum.ofText(encoded).hex,
      "acea0831a78b03f5b7cfa548e81f037aa997d37dbdae8b6cdfd4b24ac82f59c7"
    )
    assertEquals(
      record.digest.hex,
      "50089af8b939848e8c0d44180095eddd7ffae2f3c010c5a89f8a29deb91c9790"
    )
    assertEquals(encoded.length, 32202)
  }
  test("large-coordinates canonical bytes and record identity are pinned") {
    val record = MappingCompositionFixture.largeCoordinates
    val encoded = MappingCodecs.encode(record)
    assertEquals(
      Checksum.ofText(encoded).hex,
      "47f271def80ad4bafc668c54a11ae39baed4fa9758fcdd0b3e935da16952f076"
    )
    assertEquals(
      record.digest.hex,
      "ebeb02d864617978779b14bfe9c958baadebb026835ee81d722d9052a7d9b34d"
    )
    assertEquals(encoded.length, 43604)
  }
