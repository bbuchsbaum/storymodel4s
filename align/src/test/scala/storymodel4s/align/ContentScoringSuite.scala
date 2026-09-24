package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.*

/** Laws of the content scorers on the strict path (mote bd-01M379MH86VMN6SNVNRH32G3G6). */
class ContentScoringSuite extends FunSuite:
  import AnnaFixture.*

  private val reversedView = view.copy(nodes = view.nodes.reverse)
  private val units = Vector(u0, u1, u2, u3)
  private val refs = view.nodes.map(_.ref)

  private def strictGate(u: storymodel4s.recall.RecallUnit, ref: SourceNodeRef, v: SourceView) =
    ContentProjection
      .canonical(u, v.node(ref).get, v)
      .fold(r => fail(s"refused: $r"), identity) match
      case (uc, tc, _) => ContentScoring.modeGate(uc, tc)

  test("the strict mode gate does not move with node storage order") {
    for u <- units; r <- refs do
      assertEquals(strictGate(u, r, reversedView), strictGate(u, r, view), s"${u.id.value} on $r")
  }

  test("the strict mode gate agrees with the historical gate on which pairs are faithful") {
    // Same rules, different order policy: faithfulness and the contradiction SET must agree.
    for u <- units; r <- refs do
      val strict = strictGate(u, r, view).fold(g => fail(s"ambiguous gates $g"), identity)
      val historical = ModeGate.assess(u, view.node(r).get, view)
      assertEquals(strict.faithful, historical.faithful, s"${u.id.value} on $r")
      assertEquals(strict.contradictions.toSet, historical.contradictions.toSet)
  }

  test("strict inherited contradictions are in content order") {
    for u <- units; r <- refs do
      val cs = strictGate(u, r, view).fold(g => fail(s"$g"), identity).contradictions
      assertEquals(cs, cs.sortBy(_.ordinal))
  }

  // --- a segment whose engaged leaves raise different contradictions -----------------------------

  private val seg = AnnaFixture.seg("s")
  private val (l1, l2) = (AnnaFixture.sit("l1"), AnnaFixture.sit("l2"))
  private val template = nodes.head.copy(participants = Vector.empty, evidence = None)
  private def twoLeaves(order: Vector[NodeSummary]) =
    InMemorySourceView(order, Map.empty, None, storyText.length)
  private val leafNodes = Vector(
    template.copy(ref = seg, level = 1, parent = None, predicate = Some("run")),
    template.copy(
      ref = l1,
      level = 0,
      parent = Some(seg),
      predicate = Some("run"),
      modality = ModalityTag.Intended
    ),
    template.copy(
      ref = l2,
      level = 0,
      parent = Some(seg),
      predicate = Some("run"),
      polarity = PolarityTag.Negative
    )
  )
  private val runs = u0.copy(
    proposition = PropositionSketch(
      Some("run"),
      Vector.empty,
      PolarityTag.Positive,
      ModalityTag.Asserted,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Set.empty
    ),
    evidence = None
  )
  private val forward = twoLeaves(leafNodes)
  private val backward = twoLeaves(leafNodes.head +: leafNodes.tail.reverse)

  test("teeth: the historical gate orders inherited contradictions by leaf storage order") {
    val a = ModeGate.assess(runs, forward.node(seg).get, forward).contradictions
    val b = ModeGate.assess(runs, backward.node(seg).get, backward).contradictions
    assertEquals(a.toSet, Set(Contradiction.ModalityConflict, Contradiction.PolarityConflict))
    assertEquals(b, a.reverse)
  }

  test("the strict gate orders the same contradictions by content, whatever the storage order") {
    val a = strictGate(runs, seg, forward)
    assertEquals(strictGate(runs, seg, backward), a)
    assertEquals(
      a.map(_.contradictions),
      Right(Vector(Contradiction.PolarityConflict, Contradiction.ModalityConflict))
    )
  }
