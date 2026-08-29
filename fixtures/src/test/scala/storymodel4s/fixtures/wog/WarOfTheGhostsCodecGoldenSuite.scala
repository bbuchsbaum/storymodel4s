package storymodel4s.fixtures.wog

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.align.bridge.StorySourceView
import storymodel4s.codec.{Canonical, HsmmResultCodec}
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Locks `hsmm/v2` to a real inferred War of the Ghosts result without serializing story text. */
class WarOfTheGhostsCodecGoldenSuite extends FunSuite:
  import WarOfTheGhostsCodecGolden.*

  test("the WOG hsmm/v2 bytes are canonical, sparse, and contain no story text") {
    assertEquals(Canonical.parse(encoded).map(Canonical.print), Right(encoded))
    assert(encoded.length < 100000, s"unexpectedly large golden candidate: ${encoded.length} bytes")
    assert(!encoded.contains(context.recall.transcript.canonicalText))
    assert(!encoded.contains(WarOfTheGhostsText.text))
    val surfaceCanaries =
      context.recall.atlas.sentences.map(context.recall.atlas.text) ++
        WarOfTheGhostsModel.atlas.sentences.map(WarOfTheGhostsModel.atlas.text) :+
        WarOfTheGhostsText.title
    surfaceCanaries.filter(_.nonEmpty).foreach { surface =>
      assert(!encoded.contains(surface), s"golden leaked story text: $surface")
    }
  }

  test("the WOG hsmm/v2 bytes contextually decode to the inferred result") {
    val decoded = HsmmResultCodec.decode(encoded, context.recall, context.view)
    assertEquals(decoded, Right(context.result))
    assertEquals(decoded.map(HsmmResultCodec.encode), Right(encoded))
  }

private[wog] object WarOfTheGhostsCodecGolden:
  import WarOfTheGhostsExpectations.*

  /** Re-cut on 2026-08-29 for bd-01M16S2Y6YS82TE6WBWQPKNZR3: empty sensoryTerms is recorded missing
    * instead of present-at-0.0. Sensory moves from present-at-0.0 to absent; totals, flow,
    * posterior and viewFingerprint are byte-identical. This is a recut of the record, not an
    * estimand change: the 0.0 term was already a no-op in the sum.
    *
    * Previous: 3c183c3cb4dc01dad930fcae7354fa828dbd2e8714b1901449c792552fc4fb9f, 25,598 bytes.
    */
  val ExpectedChecksum: String =
    "13222a02bbdc6e49fe0882102e5f748f8a9ab1f141d7f439bdf2f6de19dd14f9"

  lazy val context: GoldenContext = GoldenContext.build()
  lazy val encoded: String = HsmmResultCodec.encode(context.result)

  final case class GoldenContext(
      recall: RecallGraph[Checked],
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
