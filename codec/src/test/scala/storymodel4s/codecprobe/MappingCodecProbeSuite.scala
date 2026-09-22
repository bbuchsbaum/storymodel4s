package storymodel4s.codecprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.codec.*

class MappingCodecProbeSuite extends FunSuite:
  private val dependencies = Vector(classOf[ExpectedMappingContext], classOf[DerivationContext])
  test("contextual decode is the public construction route") {
    assertEquals(dependencies.size, 2)
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; MappingCodecs.decode("{}", ??? : ExpectedMappingContext)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; MappingCodecs.encode(??? : storymodel4s.align.MappingResult)"""
      ).isEmpty
    )
  }
  test("a raw recall graph is not derivation context") {
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.align.*; import storymodel4s.recall.*; DerivationContext(??? : HsmmResult, ??? : RecallGraph[RecallGraphStatus.Unchecked], ??? : SourceView)"""
      ).nonEmpty
    )
  }
  test("no context-free mapping decoder is published") {
    assert(typeCheckErrors("""import storymodel4s.codec.*; MappingCodecs.decode("{}")""").nonEmpty)
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.MappingCodecs.*; summon[io.circe.Decoder[storymodel4s.align.MappingResult]]"""
      ).nonEmpty
    )
  }
  test("wire reconstruction helpers are codec-private") {
    assert(typeCheckErrors("storymodel4s.codec.MappingOutcomeRead").nonEmpty)
    assert(typeCheckErrors("storymodel4s.codec.MappingMetadataRead").nonEmpty)
    assert(typeCheckErrors("storymodel4s.codec.MappingRecordWire").nonEmpty)
  }
