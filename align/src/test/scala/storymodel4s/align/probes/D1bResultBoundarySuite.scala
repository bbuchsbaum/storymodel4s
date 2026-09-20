package storymodel4s.align.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.HsmmResult

class D1bResultBoundarySuite extends FunSuite:
  private val dependency = classOf[HsmmResult]
  test("accepting control: result validation derives its own exact support map"):
    assert(dependency.getName.nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.recall.*
      import storymodel4s.recall.RecallGraphStatus.Checked
      def checked(r: HsmmResult, recall: RecallGraph[Checked], view: SourceView) =
        HsmmResult.validated(recall, view, r.candidateAnchors, r.posterior, r.flow,
          r.viterbi, r.logLikelihood, r.costs, r.refinementPasses)
    """).isEmpty)

  test("result constructor is inaccessible even inside align"):
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      def forged(r: HsmmResult): HsmmResult =
        new HsmmResult(r.posterior, r.flow, r.viterbi, r.logLikelihood, r.costs,
          r.candidateAnchors, r.admissibility, r.viewFingerprint, r.recallChecksum,
          r.refinementPasses, r.sourceSupport, true)
    """).nonEmpty)

  test("result has no Product or Mirror construction door"):
    assert(typeCheckErrors("""
      def product(r: storymodel4s.align.HsmmResult): Product = r
    """).nonEmpty)
    assert(typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.HsmmResult]]").nonEmpty)
