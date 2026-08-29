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
    val surfaceCanaries =
      context.recall.atlas.sentences.map(context.recall.atlas.text) ++
        WarOfTheGhostsModel.atlas.sentences.map(WarOfTheGhostsModel.atlas.text) :+
        WarOfTheGhostsText.title
    surfaceCanaries.filter(_.nonEmpty).foreach { surface =>
      assert(!encoded.contains(surface), s"golden leaked story text: $surface")
    }
  }

  test("the WOG hsmm/v1 bytes contextually decode to the inferred result") {
    val decoded = HsmmResultCodec.decode(encoded, context.recall, context.view)
    assertEquals(decoded, Right(context.result))
    assertEquals(decoded.map(HsmmResultCodec.encode), Right(encoded))
  }

private[wog] object WarOfTheGhostsCodecGolden:
  import WarOfTheGhostsExpectations.*

  /** Re-cut on 2026-08-29 for bd-01M16C9HT9V9Q80F7411V87BBY: NodeSummary.importance changed its
    * default from observed(1.0) to Missing, and importance is rendered into the view's content
    * address (wire.scala), so viewFingerprint moved by design. The golden CAUGHT that change - a
    * ratified estimand change reaching the wire is exactly what it is for, and a fingerprint that
    * had NOT moved would have meant the content address was not addressing the content.
    *
    * Previous: e3a667f6ea228661d591cce094252633e2b36aa47e9f0d856e348fc66c9f6f68, 25,598 bytes.
    */
  val ExpectedChecksum: String =
    "3c183c3cb4dc01dad930fcae7354fa828dbd2e8714b1901449c792552fc4fb9f"

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
