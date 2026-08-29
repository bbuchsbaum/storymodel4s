package storymodel4s.fixtures.wog

import java.nio.charset.StandardCharsets
import munit.FunSuite
import storymodel4s.codec.HsmmResultCodec
import storymodel4s.core.Checksum

/** Checks the committed golden resource on the JVM, where class-path resources are available. */
class WarOfTheGhostsCodecGoldenResourceSuite extends FunSuite:
  import WarOfTheGhostsCodecGolden.*

  test("the committed hsmm/v1 resource is the inferred artifact and contextually decodes") {
    val path = "/golden/hsmm-v1-wog.json"
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse {
      fail(s"test resource not available: $path")
    }
    val committed =
      try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()

    assertEquals(committed, encoded)
    assertEquals(Checksum.ofText(committed).hex, ExpectedChecksum)
    val surfaceCanaries =
      context.recall.atlas.sentences.map(context.recall.atlas.text) ++
        WarOfTheGhostsModel.atlas.sentences.map(WarOfTheGhostsModel.atlas.text) :+
        WarOfTheGhostsText.title
    surfaceCanaries.filter(_.nonEmpty).foreach { surface =>
      assert(!committed.contains(surface), s"golden leaked story text: $surface")
    }
    assertEquals(
      HsmmResultCodec.decode(committed, context.recall, context.view),
      Right(context.result)
    )
  }
