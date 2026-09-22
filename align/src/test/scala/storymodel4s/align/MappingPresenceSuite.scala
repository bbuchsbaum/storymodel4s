package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

class MappingPresenceSuite extends FunSuite:
  import MappingMeasureFixture.{inventory, source, stages}
  private def recall(
      predicate: Option[String],
      outcome: Option[String],
      cause: Option[String]
  ): RecallGraph[Checked] =
    val unit = AnnaFixture.u0.copy(proposition =
      PropositionSketch.empty.copy(predicate = predicate, outcome = outcome, cause = cause)
    )
    RecallGraph
      .validated(AnnaFixture.recall.copy(units = Vector(unit), relations = RecallRelations.empty))
      .toOption
      .get
  private def view(
      predicate: Option[String],
      outcome: Option[String],
      cause: Option[String]
  ): InMemorySourceView =
    InMemorySourceView(
      Vector(
        AnnaFixture.nodes.head.copy(
          parent = None,
          predicate = predicate,
          outcome = outcome,
          cause = cause,
          participants = Vector.empty
        )
      ),
      Map.empty,
      None,
      AnnaFixture.storyText.length
    )
  private def infer(r: RecallGraph[Checked], v: SourceView): HsmmResult =
    val semantic = SemanticDistance.fromTable(Map((r.ordered.head.id, v.nodes.head.ref) -> 0.1))
    GraphHsmm
      .infer(
        r,
        v,
        CandidateGenerator(semantic, perLevel = 1).generate(r.ordered, v),
        DefaultLocalCostModel(semantic = semantic)
      )
      .toOption
      .get
  private def binding(
      r: RecallGraph[Checked],
      v: SourceView,
      result: HsmmResult
  ): DerivationBinding =
    DerivationBinding.of(result, r, inventory(r), v, source(v)).toOption.get
  private def link(
      r: RecallGraph[Checked],
      v: SourceView,
      result: HsmmResult,
      b: DerivationBinding
  ): Either[MappingRefusal, MappingLink] =
    val unit = r.ordered.head.id
    val state = result.costs(unit).keys.find(_.isSource).get
    MappingLink.fromResult(
      result,
      b,
      r,
      v,
      source(v),
      unit,
      state,
      stages,
      CandidateSetId.of(
        stages.candidates,
        unit,
        result.posterior.row(unit).get.mass.keySet.map(Destination.of)
      )
    )
  private val present = Some("")
  private val cases =
    Vector("predicate" -> Facet.Action, "outcome" -> Facet.Outcome, "cause" -> Facet.Cause)
  cases.foreach { (name, facet) =>
    test(s"recall $name preserves None versus Some empty") {
      def r(value: Option[String]) = recall(
        if name == "predicate" then value else present,
        if name == "outcome" then value else present,
        if name == "cause" then value else present
      )
      val a = r(None)
      val b = r(present)
      val v = view(present, present, present)
      assertEquals(AlignWire.recallChecksum(a), AlignWire.recallChecksum(b))
      val ra = infer(a, v)
      val rb = infer(b, v)
      val ba = binding(a, v, ra)
      val bb = binding(b, v, rb)
      assertNotEquals(ba.recallSupplement, bb.recallSupplement)
      val la = link(a, v, ra, ba).toOption.get
      val lb = link(b, v, rb, bb).toOption.get
      assertNotEquals(
        la.fidelity.asInstanceOf[FidelityStatus.Assessed].report(facet),
        lb.fidelity.asInstanceOf[FidelityStatus.Assessed].report(facet)
      )
      assertEquals(
        link(b, v, ra, ba).left.toOption,
        Some(MappingRefusal.BindingMismatch("recallSupplement"))
      )
    }
    test(s"source $name preserves None versus Some empty") {
      def v(value: Option[String]) = view(
        if name == "predicate" then value else present,
        if name == "outcome" then value else present,
        if name == "cause" then value else present
      )
      val a = v(None)
      val b = v(present)
      val r = recall(present, present, present)
      assertEquals(ViewFingerprint.of(a), ViewFingerprint.of(b))
      val ra = infer(r, a)
      val rb = infer(r, b)
      val ba = binding(r, a, ra)
      val bb = binding(r, b, rb)
      assertNotEquals(ba.scopeDigest, bb.scopeDigest)
      val la = link(r, a, ra, ba).toOption.get
      val lb = link(r, b, rb, bb).toOption.get
      assertNotEquals(
        la.fidelity.asInstanceOf[FidelityStatus.Assessed].report(facet),
        lb.fidelity.asInstanceOf[FidelityStatus.Assessed].report(facet)
      )
      assertEquals(
        link(r, b, ra, ba).left.toOption,
        Some(MappingRefusal.BindingMismatch("scopeDigest"))
      )
    }
  }
  test("fidelity strings retain exact admitted UTF-16 code units") {
    val a = recall(Some(String.valueOf(0xd800.toChar)), None, None)
    val b = recall(Some(String.valueOf(0xd801.toChar)), None, None)
    assertEquals(AlignWire.recallChecksum(a), AlignWire.recallChecksum(b))
    val v = view(a.ordered.head.proposition.predicate, None, None)
    val ra = infer(a, v)
    val rb = infer(b, v)
    assertNotEquals(binding(a, v, ra).recallSupplement, binding(b, v, rb).recallSupplement)
    assertEquals(
      link(b, v, ra, binding(a, v, ra)).left.toOption,
      Some(MappingRefusal.BindingMismatch("recallSupplement"))
    )
    assert(link(b, v, rb, binding(b, v, rb)).isRight)
  }
