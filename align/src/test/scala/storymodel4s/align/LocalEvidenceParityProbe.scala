package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.CanonicalDouble

/** Regression guard, not a positive control: it pins the digests the engine produced on main
  * `8c70dfb1`, BEFORE local evidence was extracted from `GraphHsmm.run`, so the extraction is shown
  * to be byte-identical on the gated, refined and ungated paths. The digests were captured by
  * running this probe's computation on a detached checkout of that commit.
  *
  * These unchanged pins were captured on the historical Mac JVM. The owned numerical producer
  * now requires every qualified target to reproduce them exactly. The legacy runtime-math
  * differences remain retained evidence under bd-01M1D215EY4T5BR0VRJ694AMBQ, not alternate current
  * expectations. No tolerance or platform selection is applied.
  */
class LocalEvidenceParityProbe extends FunSuite:
  import AnnaFixture.{candidates, costModel, recall, view}

  private def gated(config: HsmmConfig): HsmmResult =
    GraphHsmm
      .infer(recall, view, candidates, costModel, config)
      .fold(e => fail(e.message), identity)

  private def ablationDigest(ab: AblationResult): String =
    MappingRender
      .digest(
        ab.viterbi.map(_.key) ++ Vector(CanonicalDouble.render(ab.logLikelihood)) ++
          ab.rows.map { (u, m) =>
            u.value + "|" + m.toVector
              .sortBy(_._1.key)
              .map((s, v) => s.key + "=" + CanonicalDouble.render(v))
              .mkString(",")
          }
      )
      .hex

  test("gated inference is byte-identical to the pre-extraction engine (main 8c70dfb1)") {
    assertEquals(
      MappingBindingRender.result(gated(HsmmConfig.default)).hex,
      "ad2551c9c7c0e011807c16a447342b99af2f4c8db1bf862ba9bf55620d1e8035"
    )
  }

  test("refined inference is byte-identical to the pre-extraction engine (main 8c70dfb1)") {
    assertEquals(
      MappingBindingRender.result(gated(HsmmConfig.unsafe(refinementPasses = 3))).hex,
      "f56fd4b1291d5bd87380219fafcab9ea167ffd5e7af30ec4dd6ed0148e6f2b63"
    )
  }

  test("ungated ablation is byte-identical to the pre-extraction engine (main 8c70dfb1)") {
    val ab = GraphHsmm
      .ablationUngated(recall, view, candidates, costModel)
      .fold(e => fail(e.message), identity)
    assertEquals(
      ablationDigest(ab),
      "2626880e4f41a777d07e116078f823da40cd02a570de8753db2e2aebbf3ff31b"
    )
  }
