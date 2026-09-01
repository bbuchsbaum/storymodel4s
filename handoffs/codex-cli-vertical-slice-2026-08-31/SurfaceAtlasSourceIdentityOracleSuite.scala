package storymodel4s.codec

import munit.FunSuite
import storymodel4s.core.*

class SurfaceAtlasSourceIdentityOracleSuite extends FunSuite:
  test("a foreign source cannot reuse the artifact StoryId to bypass checksum binding") {
    val admitted = StorySource.fromText("Admitted source text.").toOption.get
    val artifact = SurfaceAtlasArtifactCodec.encode(SurfaceAnalyzer.analyze(admitted))
    val foreign =
      StorySource
        .fromText("Foreign source text.", explicitId = Some(admitted.id))
        .toOption
        .get

    assertEquals(foreign.id, admitted.id)
    assertNotEquals(foreign.canonicalChecksum, admitted.canonicalChecksum)

    val refusal = SurfaceAtlasArtifactCodec.decode(foreign, artifact)
    assert(
      refusal.left.exists {
        case CodecError.Decode("$.canonicalSourceChecksum", _) => true
        case _                                                  => false
      },
      refusal.toString
    )
  }

  test("a canonical-equivalent raw variant shares the artifact but not raw identity") {
    val first = StorySource.fromText("Same\r\n\r\n\r\ntext  \n").toOption.get
    val second = StorySource.fromText("Same\n\ntext").toOption.get
    val artifact = SurfaceAtlasArtifactCodec.encode(SurfaceAnalyzer.analyze(first))

    assertEquals(first.id, second.id)
    assertEquals(first.canonicalChecksum, second.canonicalChecksum)
    assertNotEquals(first.rawChecksum, second.rawChecksum)
    assert(SurfaceAtlasArtifactCodec.decode(second, artifact).isRight)
  }
