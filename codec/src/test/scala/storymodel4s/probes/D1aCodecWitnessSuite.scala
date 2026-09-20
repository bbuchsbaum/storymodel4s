package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.codec.StoryModelCodec

class D1aCodecWitnessSuite extends FunSuite:
  test("accepting control: canonical model codecs accept and return text witnesses"):
    assert(StoryModelCodec != null)
    assert(typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.codec.StoryModelCodec
      import StoryModelCodec.given
      summon[io.circe.Encoder[TextModel[ModelStatus.Draft]]]
      summon[io.circe.Decoder[TextModel[ModelStatus.Draft]]]
      def encode(model: TextModel[ModelStatus.Draft]) = StoryModelCodec.encode(model)
      def checksum(model: TextModel[ModelStatus.Draft]) = StoryModelCodec.contentChecksum(model)
      def decode(wire: String): Either[storymodel4s.codec.CodecError, TextModel[ModelStatus.Draft]] =
        StoryModelCodec.decode(wire)
    """))

  test("generic models have no canonical Encoder"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.codec.StoryModelCodec.given
      summon[io.circe.Encoder[StoryModel[ModelStatus.Draft]]]
    """))

  test("generic models have no canonical Decoder"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.codec.StoryModelCodec.given
      summon[io.circe.Decoder[StoryModel[ModelStatus.Draft]]]
    """))

  test("generic models cannot enter text encode and checksum methods"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.codec.StoryModelCodec
      def encode(model: StoryModel[ModelStatus.Draft]) = StoryModelCodec.encode(model)
    """))
    assert(!typeChecks("""
      import storymodel4s.story.*
      import storymodel4s.codec.StoryModelCodec
      def checksum(model: StoryModel[ModelStatus.Draft]) = StoryModelCodec.contentChecksum(model)
    """))
