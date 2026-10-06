package storymodel4s.fixtures.wog

import io.circe.Json
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.align.bridge.StorySourceView
import storymodel4s.codec.{Canonical, CodecError, HsmmCodecError, HsmmResultCodec}
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Locks `hsmm/v4` to a real inferred War of the Ghosts result without serializing story text.
  *
  * Portable: every backend checks its live bytes against the ONE owned-producer pin
  * ([[WogGoldenBackend]]), and checks that nothing but support moved relative to its own `hsmm/v3`
  * baseline. The committed resource files are read on the JVM only
  * (`WarOfTheGhostsCodecGoldenResourceSuite`), which also proves each pin is the checksum of its
  * committed file.
  */
class WarOfTheGhostsCodecGoldenSuite extends FunSuite:
  import WarOfTheGhostsCodecGolden.*

  test("the WOG hsmm/v4 bytes are canonical, sparse, and contain no story text") {
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

  test("the WOG hsmm/v4 bytes contextually decode to the inferred result") {
    val decoded = HsmmResultCodec.decode(encoded, context.recall, context.view)
    assertEquals(decoded, Right(context.result))
    assertEquals(decoded.flatMap(HsmmResultCodec.encode), Right(encoded))
  }

  test("the live WOG artifact is the portable producer's committed hsmm/v4 golden") {
    val backend = WogGoldenPlatform.current
    assertEquals(
      Checksum.ofText(encoded).hex,
      backend.encodedV4,
      s"$backend produced bytes other than its golden ${backend.goldenResource}"
    )
  }

  test("portable hsmm/v4 retains the historical Mac JVM numbers outside support") {
    // Posterior, flow, Viterbi path, log-likelihood, every total and term, receipts, anchors and
    // fingerprints: the artifact with schemaVersion and each cost's support removed is exactly the
    // v3 artifact with schemaVersion and each supportWeight removed, on this backend.
    val backend = WogGoldenPlatform.current
    assertEquals(Checksum.ofText(withoutSupport(encoded)).hex, backend.withoutSupport)
  }

  test("no WOG external cell publishes a number; every WOG source cell is assessed") {
    // THE DEFECT, on the golden it was found in: the ten external cells published supportWeight 1.0
    // with zero terms, above the real anchors' 0.955.
    val cells = context.result.costs.values.flatMap(_.toVector).toVector
    val external = cells.collect { case (s, b) if s.isExternal => b.support }
    val source = cells.collect { case (s, b) if !s.isExternal => b.support }
    assertEquals(external.size, 10)
    assertEquals(source.size, 8)
    assert(external.forall(_ == SupportAssessment.externalState), external.toString)
    source.foreach {
      case assessed: SupportAssessment.Assessed =>
        assert(assessed.share < 1.0, s"a WOG source cell claims nothing was assumed: $assessed")
      case other => fail(s"a WOG source cell published $other")
    }
  }

  test("a WOG artifact tagged hsmm/v3 is refused with the typed UnsupportedSchema") {
    val json = Canonical.parse(encoded).fold(e => fail(e.message), identity)
    val v3 = json.mapObject(_.add("schemaVersion", Json.fromString("hsmm/v3")))
    assertEquals(
      HsmmResultCodec.decodeJson(v3, context.recall, context.view),
      Left(HsmmCodecError.Wire(CodecError.UnsupportedSchema("hsmm/v3", Vector("hsmm/v4"))))
    )
  }

/** Retained historical runtime pins and the one current portable producer pin.
  *
  * Historical runtime math produced identical Mac JVM/JS bytes and five adjacent-bit Native
  * differences. Those original resources and their pins remain intact. The owned producer is
  * qualified to reproduce the historical Mac JVM bytes on every target; current expectations
  * select Portable unconditionally, never by platform or observed output.
  *
  * `encodedV4` is the SHA-256 of the canonical encoding; `goldenFile` of the committed resource,
  * which is that encoding plus one terminal newline; `withoutSupport` of the artifact with
  * `schemaVersion` and every cost's support field removed, which is identical for the `hsmm/v3`
  * baseline (`v3File`) and the `hsmm/v4` golden of the same backend.
  */
private[wog] enum WogGoldenBackend(
    val goldenResource: String,
    val encodedV4: String,
    val goldenFile: String,
    val v3Resource: String,
    val v3File: String,
    val withoutSupport: String
):
  case JVM
      extends WogGoldenBackend(
        "/golden/hsmm-v4-wog.json",
        "6388aaaf70e9cb6782c3342ca0108d5e085bb892a114d6b10af470bf6826fa2a",
        "6ee06ebbb90709ebe323e7bd98e99f9c839809489a780e113d4c2441b7abf1e6",
        "/golden/hsmm-v3-wog.json",
        "ce61e761a131c1e2ffeebcf912a9c04d9dd2bf2fb4c09f9d4bda0c2741f2cf1a",
        "70af0bf6bab5bfe30202e1b4d72f7ddbcc2d41bc4dff8017f0f9dc0cb1ee0e81"
      )
  case JS
      extends WogGoldenBackend(
        "/golden/hsmm-v4-wog.json",
        "6388aaaf70e9cb6782c3342ca0108d5e085bb892a114d6b10af470bf6826fa2a",
        "6ee06ebbb90709ebe323e7bd98e99f9c839809489a780e113d4c2441b7abf1e6",
        "/golden/hsmm-v3-wog.json",
        "ce61e761a131c1e2ffeebcf912a9c04d9dd2bf2fb4c09f9d4bda0c2741f2cf1a",
        "70af0bf6bab5bfe30202e1b4d72f7ddbcc2d41bc4dff8017f0f9dc0cb1ee0e81"
      )
  case Native
      extends WogGoldenBackend(
        "/golden/hsmm-v4-wog.native.json",
        "7b2922e72e328f1d3c61ecffc48548bfcd4b8ac4a54b663b71248f65303901a3",
        "12a263119a8799cac9e548b65238ffad2f29f1dc23234a86e57b22f752398a8d",
        "/golden/hsmm-v3-wog.native.json",
        "ccb0b98f21c7c2e1c9d5d81bad3f0858ed4c2f5d4f4c3ac671a6f45b152af012",
        "7505f523b5ccd440b7e742cb563b3f8f943bb322ba09ec8af189d6d5b5fa6b7e"
      )

  case Portable
      extends WogGoldenBackend(
        "/golden/hsmm-v4-wog.json",
        "6388aaaf70e9cb6782c3342ca0108d5e085bb892a114d6b10af470bf6826fa2a",
        "6ee06ebbb90709ebe323e7bd98e99f9c839809489a780e113d4c2441b7abf1e6",
        "/golden/hsmm-v3-wog.json",
        "ce61e761a131c1e2ffeebcf912a9c04d9dd2bf2fb4c09f9d4bda0c2741f2cf1a",
        "70af0bf6bab5bfe30202e1b4d72f7ddbcc2d41bc4dff8017f0f9dc0cb1ee0e81"
      )

private[wog] object WarOfTheGhostsCodecGolden:
  import WarOfTheGhostsExpectations.*

  /** Re-cut on 2026-09-21 for bd-01M19956MFSG7076QE4J66T7E9: `hsmm/v4` replaces the numeric
    * `supportWeight` with tagged support. A RECUT OF THE RECORD, NOT AN ESTIMAND CHANGE, and the
    * suite proves it: with support removed, each backend's v4 artifact is its v3 baseline exactly
    * (`WogGoldenBackend.withoutSupport`). The 8 source cells' assessed shares are bit-identical to
    * their v3 `supportWeight` (0.9552238805970149 and kin); the 10 external cells, which published
    * `1.0` with zero terms, now publish `NotApplicable(ExternalState)`. 26,284 -> 30,682 bytes.
    *
    * History: hsmm/v3 (2026-08-29, bd-01M16M5ETH0QHFZ62KXZJ54PN9) added one empty `imputedTerms`
    * per breakdown, ce61e761…, 26,284 bytes, kept byte-identical as the refusal fixture
    * `hsmm-v3-wog.json`; before it 13222a02…, 25,960 bytes.
    */
  lazy val encoded: String = HsmmResultCodec
    .encode(context.result)
    .fold(e => throw new IllegalStateException(e.message), identity)

  /** The artifact with `schemaVersion` and every cost's support field (`support` in v4,
    * `supportWeight` in v3) removed, canonically printed: what must not move across the recut.
    */
  def withoutSupport(text: String): String =
    val json = Canonical.parse(text).fold(e => throw new IllegalStateException(e.message), identity)
    def each(values: Option[Json])(f: Json => Json): Json =
      Json.fromValues(values.flatMap(_.asArray).getOrElse(Vector.empty).map(f))
    Canonical.print(json.mapObject { root =>
      root
        .remove("schemaVersion")
        .add(
          "costs",
          each(root("costs")) { unit =>
            unit.mapObject { unitFields =>
              unitFields.add(
                "costs",
                each(unitFields("costs")) { stateCost =>
                  stateCost.mapObject { fields =>
                    fields("cost").fold(fields)(cost =>
                      fields
                        .add("cost", cost.mapObject(_.remove("support").remove("supportWeight")))
                    )
                  }
                }
              )
            }
          }
        )
    })

  lazy val context: GoldenContext = GoldenContext.build()

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
