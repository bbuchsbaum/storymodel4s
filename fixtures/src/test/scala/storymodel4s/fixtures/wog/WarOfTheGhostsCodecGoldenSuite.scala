package storymodel4s.fixtures.wog

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.align.bridge.StorySourceView
import storymodel4s.codec.{Canonical, HsmmResultCodec}
import storymodel4s.core.*
import storymodel4s.recall.*

/** Locks `hsmm/v1` to a real inferred War of the Ghosts result without serializing story text. */
class WarOfTheGhostsCodecGoldenSuite extends FunSuite:
  import WarOfTheGhostsCodecGolden.*

  test("the WOG hsmm/v1 bytes are canonical, sparse, and contain no story text") {
    assertEquals(Canonical.parse(encoded).map(Canonical.print), Right(encoded))
    assert(encoded.length < 100000, s"unexpectedly large golden candidate: ${encoded.length} bytes")
    assert(!encoded.contains(context.recall.transcript.canonicalText))
    assert(!encoded.contains(WarOfTheGhostsText.text))
  }

  test("the WOG hsmm/v1 bytes contextually decode to the inferred result") {
    val decoded = HsmmResultCodec.decode(encoded, context.recall, context.view)
    assertEquals(decoded, Right(context.result))
    assertEquals(decoded.map(HsmmResultCodec.encode), Right(encoded))
  }

private[wog] object WarOfTheGhostsCodecGolden:
  import WarOfTheGhostsExpectations.*

  val ExpectedChecksum: String =
    "e3a667f6ea228661d591cce094252633e2b36aa47e9f0d856e348fc66c9f6f68"

  lazy val context: GoldenContext = GoldenContext.build()
  lazy val encoded: String = HsmmResultCodec.encode(context.result)

  final case class GoldenContext(
      recall: RecallGraph,
      view: SourceView,
      result: HsmmResult
  )

  object GoldenContext:
    def build(): GoldenContext =
      // Initialize the outer fixture before the expectations object touches nested E/S/G ids.
      val model = WarOfTheGhostsModel.model
      val paraphrase = recallParaphrases.find(_.kind == ParaphraseKind.Precise).get
      val transcript = StorySource.fromText(paraphrase.text, Some("wog-hsmm-v1")).toOption.get
      val recall = RecallSegmenter.segment(transcript)
      val view = StorySourceView.validated(model)
      val semantic = SemanticDistance.lexicalJaccard
      // One winner per hierarchy level keeps a codec golden sparse; candidate-quality calibration
      // belongs to the alignment acceptance suite rather than this byte-contract fixture.
      val candidates =
        CandidateGenerator(semantic, perLevel = 1, lexicalOverlap = false)
          .generate(recall.units, view)
      val costModel = DefaultLocalCostModel(semantic = semantic)
      val result = GraphHsmm
        .infer(recall, view, candidates, costModel)
        .fold(error => throw new IllegalStateException(error.message), identity)
      GoldenContext(recall, view, result)
