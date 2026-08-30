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

  /** Re-cut on 2026-08-29 for bd-01M16M5ETH0QHFZ62KXZJ54PN9: hsmm/v3 records `imputedTerms`, the
    * terms priced from a declared constant rather than measured.
    *
    * A RECUT OF THE RECORD, NOT AN ESTIMAND CHANGE, and the file proves it rather than asserting
    * it. The delta is 25,960 -> 26,284 bytes = 324 = 18 x len("imputedTerms":[]), one empty entry
    * per cost breakdown, and the same 18 breakdowns the supportWeight recut touched. Every entry is
    * EMPTY: WOG runs on `lexicalJaccard`, which never abstains, so nothing here is imputed and no
    * total, flow, posterior or fingerprint moves. The field is how a corpus WITH an abstaining
    * provider would differ from this one - on WOG it is a receipt that says "nothing substituted",
    * which is a claim worth being able to make.
    *
    * Previous: 13222a02bbdc6e49fe0882102e5f748f8a9ab1f141d7f439bdf2f6de19dd14f9, 25,960 bytes.
    */
  val ExpectedChecksum: String =
    "ce61e761a131c1e2ffeebcf912a9c04d9dd2bf2fb4c09f9d4bda0c2741f2cf1a"

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
