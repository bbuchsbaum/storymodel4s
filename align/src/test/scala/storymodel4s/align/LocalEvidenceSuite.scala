package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.RecallGraph

/** Local evidence is computed once, identified by its content, and bound to the recall, view and
  * gate it was computed under (mote bd-01M2TACM78289S4TECE91GT5K2 AC1).
  */
class LocalEvidenceSuite extends FunSuite:
  import AnnaFixture.{candidates, costModel, e5, recall, view}

  private def evidence(
      c: Candidates = candidates,
      m: LocalCostModel = costModel,
      v: SourceView = view,
      gate: Boolean = true
  ): LocalEvidence =
    LocalEvidence.compute(recall, v, c, m, gate).fold(e => fail(e.message), identity)

  private def digest(r: Either[AlignError, HsmmResult]): String =
    MappingBindingRender.result(r.fold(e => fail(e.message), identity)).hex

  test("inference over precomputed evidence equals the delegating signature") {
    assertEquals(
      digest(GraphHsmm.infer(recall, view, evidence(), HsmmConfig.default)),
      digest(GraphHsmm.infer(recall, view, candidates, costModel))
    )
  }

  test("identity is a function of content: two computations agree") {
    assertEquals(evidence().identity, evidence().identity)
    assertEquals(evidence(), evidence())
  }

  test("identity ignores nomination storage order, which carries no information") {
    val reversed = Candidates(candidates.byUnit.view.mapValues { s =>
      CandidateSet(s.nominations.reverse, s.abstained)
    }.toMap)
    assert(
      candidates.byUnit.values.exists(_.nominations.size > 1),
      "fixture must have a unit with several nominations, or reversal tests nothing"
    )
    assertEquals(evidence(reversed).identity, evidence().identity)
  }

  test("identity changes when a local price changes") {
    val repriced = evidence(m = AnnaFixture.costModel.copy(externalFloor = 2.0))
    assertNotEquals(
      repriced.breakdowns,
      evidence().breakdowns,
      "capacity to fail: the repricing must change a breakdown"
    )
    assertNotEquals(repriced.identity, evidence().identity)
  }

  test("identity changes when a nomination changes even though inference cannot see it") {
    val (unit, set) =
      candidates.byUnit.toVector.sortBy(_._1.value).find(_._2.nominations.nonEmpty).get
    val relabeled = set.nominations.head.copy(receipt = Some("another-provider-receipt"))
    val changed = Candidates(
      candidates.byUnit
        .updated(unit, CandidateSet(relabeled +: set.nominations.tail, set.abstained))
    )
    assertEquals(
      evidence(changed).breakdowns,
      evidence().breakdowns,
      "the relabeling must be invisible to pricing, or this does not isolate nominations"
    )
    assertNotEquals(evidence(changed).identity, evidence().identity)
  }

  test("ungated evidence cannot feed gated inference") {
    val refused = GraphHsmm.infer(recall, view, evidence(gate = false), HsmmConfig.default)
    assert(refused.left.exists(_.message.contains("gate=false")), refused)
  }

  test("evidence computed for another view is refused") {
    val other = InMemorySourceView(
      view.nodes.map(n => if n.ref == e5 then n.copy(context = ContextTag.Speech) else n),
      view.edges,
      view.worldOrder,
      view.scoringLength
    )
    val refused = GraphHsmm.infer(recall, other, evidence(), HsmmConfig.default)
    assert(refused.left.exists(_.message.contains("different source view")), refused)
  }

  test("evidence computed for another recall is refused") {
    val other = RecallGraph
      .validated(
        recall.transcript,
        recall.atlas,
        recall.units.map(u => u.copy(proposition = u.proposition.copy(times = Vector("at dusk")))),
        recall.relations
      )
      .fold(e => fail(s"invalid recall: $e"), identity)
    assertEquals(
      MappingBindingRender.recall(other),
      MappingBindingRender.recall(recall),
      "isolation: the supplement must not see this change, so only the legacy check can refuse it"
    )
    assertNotEquals(
      AlignWire.recallChecksum(other),
      AlignWire.recallChecksum(recall),
      "capacity to fail: the legacy checksum must see this change"
    )
    val refused = GraphHsmm.infer(other, view, evidence(), HsmmConfig.default)
    assert(refused.left.exists(_.message.contains("different recall")), refused)
  }
