package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** External construction courts keep a checked content table and cost model unforgeable. */
class StrictContentBoundarySuite extends FunSuite:
  private val tableControl = typeCheckErrors("""
    import storymodel4s.align.*
    import storymodel4s.features.Estimate
    import storymodel4s.proposition.GraphOrder.Canonical
    case class TableControl(entries: Map[(UnitContent[Canonical], TargetContent[Canonical]), Estimate[Double]], otherwise: Estimate[Double])
    val value = TableControl(Map.empty, Estimate.observed(0.0))
    value.copy(otherwise = Estimate.Ineligible)
    summon[scala.deriving.Mirror.ProductOf[TableControl]].fromProduct((Map.empty, Estimate.observed(0.0)))
    summon[scala.deriving.Mirror.ProductOf[TableControl]]
  """)
  private val modelControl = typeCheckErrors("""
    import storymodel4s.align.*
    case class ModelControl(semantic: StrictSemanticChannel, weights: CostWeights, functionPrior: FunctionPrior, externalFloor: Double, externalMismatch: Double, missingSemantic: Double, distortionPenalty: Double, surfaceSession: Option[SurfaceScoringSession])
    val value = ModelControl(StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3, None)
    value.copy(missingSemantic = 0.0)
    summon[scala.deriving.Mirror.ProductOf[ModelControl]].fromProduct((StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3, None))
    summon[scala.deriving.Mirror.ProductOf[ModelControl]]
  """)
  private val summaryControl = typeCheckErrors("""
    import storymodel4s.features.MissingReason
    case class SummaryControl(level: Int, observed: Int, ineligible: Int, missing: Map[MissingReason, Int])
    val value = SummaryControl(0, 1, 0, Map.empty)
    value.copy(observed = 0)
    summon[scala.deriving.Mirror.ProductOf[SummaryControl]].fromProduct((0, 1, 0, Map.empty))
    summon[scala.deriving.Mirror.ProductOf[SummaryControl]]
  """)
  private val resultControl = typeCheckErrors("""
    import storymodel4s.align.*
    import storymodel4s.core.*
    import storymodel4s.recall.RecallUnitId
    case class ResultControl(posterior: AlignmentMatrix, flow: TransitionFlow, viterbi: Vector[AlignState], logLikelihood: Double, costs: Map[RecallUnitId, Map[AlignState, CostBreakdown]], candidateAnchors: Map[RecallUnitId, Vector[SourceNodeRef]], admissibility: Map[RecallUnitId, Map[SourceNodeRef, Admissibility]], viewFingerprint: ViewFingerprint, recallChecksum: Checksum, refinementPasses: Int, sourceSupport: Map[SourceNodeRef, TypedSupport], textWireCompatible: Boolean, gateSemantics: GateSemantics)
    def lift(r: HsmmResult) = new ResultControl(r.posterior, r.flow, r.viterbi, r.logLikelihood, r.costs,
      r.candidateAnchors, r.admissibility, r.viewFingerprint, r.recallChecksum,
      r.refinementPasses, r.sourceSupport, r.textWireCompatible, r.gateSemantics)
    def apply(r: HsmmResult) = ResultControl(r.posterior, r.flow, r.viterbi, r.logLikelihood, r.costs,
      r.candidateAnchors, r.admissibility, r.viewFingerprint, r.recallChecksum,
      r.refinementPasses, r.sourceSupport, r.textWireCompatible, r.gateSemantics)
    (??? : ResultControl).copy(gateSemantics = GateSemantics.CanonicalContent)
    summon[scala.deriving.Mirror.ProductOf[ResultControl]].fromProduct(???)
    summon[scala.deriving.Mirror.ProductOf[ResultControl]]
  """)

  test("checked issuance and same-shape controls compile") {
    assertEquals(tableControl, Nil)
    assertEquals(modelControl, Nil)
    assertEquals(summaryControl, Nil)
    assertEquals(resultControl, Nil)
    // Direct companions acquire this compiler-synthesized method only after their defining run.
    assertEquals(typeCheckErrors("storymodel4s.align.FunctionPrior.fromProduct(???)"), Nil)
    assertEquals(
      typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.observed(0.0))
      StrictCostModel.of(StrictSemanticChannel.Lexical)
    """),
      Nil
    )
  }

  test("content table constructor is closed outside align") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      new StrictSemanticChannel.ContentTable(Map.empty, Estimate.observed(0.0))
    """).nonEmpty)
  }
  test("content table apply is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      StrictSemanticChannel.ContentTable(Map.empty, Estimate.observed(0.0))
    """).nonEmpty)
  }
  test("content table copy is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      StrictSemanticChannel.ContentTable.of(Vector.empty).toOption.get.copy(otherwise = Estimate.Ineligible)
    """).nonEmpty)
  }
  test("content table fromProduct is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      StrictSemanticChannel.ContentTable.fromProduct((Map.empty, Estimate.observed(0.0)))
    """).nonEmpty)
  }
  test("content table Mirror is closed") {
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.StrictSemanticChannel.ContentTable]]"
      ).nonEmpty
    )
  }
  test("strict cost constructor is closed outside align") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      new StrictCostModel(StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3, None)
    """).nonEmpty)
  }
  test("strict cost apply is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      StrictCostModel(StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3, None)
    """).nonEmpty)
  }
  test("strict cost copy is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      StrictCostModel.of(StrictSemanticChannel.Lexical).toOption.get.copy(missingSemantic = 0.0)
    """).nonEmpty)
  }
  test("strict cost fromProduct is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      StrictCostModel.fromProduct((StrictSemanticChannel.Lexical, CostWeights.default, FunctionPrior.default, 1.0, 0.5, 0.5, 0.3, None))
    """).nonEmpty)
  }
  test("strict cost Mirror is closed") {
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.StrictCostModel]]"
      ).nonEmpty
    )
  }
  test("outcome summary constructor is closed outside align") {
    assert(
      typeCheckErrors("new storymodel4s.align.SemanticOutcomeSummary(0, 1, 0, Map.empty)").nonEmpty
    )
  }
  test("outcome summary apply is closed") {
    assert(
      typeCheckErrors("storymodel4s.align.SemanticOutcomeSummary(0, 1, 0, Map.empty)").nonEmpty
    )
  }
  test("outcome summary copy is closed") {
    assert(
      typeCheckErrors(
        "(??? : storymodel4s.align.SemanticOutcomeSummary).copy(observed = 0)"
      ).nonEmpty
    )
  }
  test("outcome summary fromProduct is closed") {
    assert(
      typeCheckErrors(
        "storymodel4s.align.SemanticOutcomeSummary.fromProduct((0, 1, 0, Map.empty))"
      ).nonEmpty
    )
  }
  test("outcome summary Mirror is closed") {
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.SemanticOutcomeSummary]]"
      ).nonEmpty
    )
  }
  test("HSMM result constructor is closed outside align with the complete field shape") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      def forged(r: HsmmResult) = new HsmmResult(r.posterior, r.flow, r.viterbi, r.logLikelihood, r.costs,
        r.candidateAnchors, r.admissibility, r.viewFingerprint, r.recallChecksum,
        r.refinementPasses, r.sourceSupport, r.textWireCompatible, r.gateSemantics)
    """).nonEmpty)
  }
  test("HSMM result apply is closed") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      def forged(r: HsmmResult) = HsmmResult(r.posterior, r.flow, r.viterbi, r.logLikelihood, r.costs,
        r.candidateAnchors, r.admissibility, r.viewFingerprint, r.recallChecksum,
        r.refinementPasses, r.sourceSupport, r.textWireCompatible, r.gateSemantics)
    """).nonEmpty)
  }
  test("HSMM result copy is closed") {
    assert(
      typeCheckErrors(
        "(??? : storymodel4s.align.HsmmResult).copy(gateSemantics = storymodel4s.align.GateSemantics.CanonicalContent)"
      ).nonEmpty
    )
  }
  test("HSMM result fromProduct is closed") {
    assert(typeCheckErrors("storymodel4s.align.HsmmResult.fromProduct(???)").nonEmpty)
  }
  test("HSMM result Mirror is closed") {
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.align.HsmmResult]]"
      ).nonEmpty
    )
  }
  test("an arbitrary semantic callback cannot be a strict adapter") {
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      StrictCostModel.of(SemanticDistance.lexicalJaccard)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      import storymodel4s.features.Estimate
      import storymodel4s.proposition.GraphOrder.Canonical
      new StrictSemanticChannel {
        val kind = "captured-coordinates"
        def score(u: UnitContent[Canonical], t: TargetContent[Canonical]): Estimate[Double] = Estimate.observed(0.0)
      }
    """).nonEmpty)
  }
