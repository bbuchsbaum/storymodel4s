package storymodel4s.align

import munit.FunSuite
import storymodel4s.proposition.GraphOrder
import storymodel4s.recall.*

/** Laws of the S2a-2 content projection (mote bd-01M379MH86VMN6SNVNRH32G3G6).
  *
  * Canonical content is a function of content: it must not move with node storage order, recall
  * participant order or unit identity. Source content must keep both historical orders, because the
  * historical shims reproduce their output from it bit for bit.
  */
class ContentProjectionSuite extends FunSuite:
  import AnnaFixture.*

  private def canonical(u: RecallUnit, n: SourceNodeRef, v: SourceView) =
    ContentProjection
      .canonical(u, v.node(n).getOrElse(fail(s"no node $n")), v)
      .fold(r => fail(s"refused: $r"), identity)

  private val reversedView = view.copy(nodes = view.nodes.reverse)

  private val twoParticipants = u2.copy(proposition =
    u2.proposition.copy(participants =
      Vector(
        SketchParticipant(SketchRole.Agent, None, "she", aliases = Set("anna")),
        SketchParticipant(SketchRole.Patient, None, "brother")
      )
    )
  )
  private def reversedParticipants(u: RecallUnit) =
    u.copy(proposition = u.proposition.copy(participants = u.proposition.participants.reverse))

  test("precondition: the fixture's segments have members, in an order storage can change") {
    assert(view.structuralMembers(sc2).size >= 2)
    assertNotEquals(view.leavesUnder(sc2), reversedView.leavesUnder(sc2))
  }

  test("canonical content does not move with node storage order") {
    for n <- Vector(e2, e4, sc1, sc2, root) do
      assertEquals(canonical(u2, n, reversedView), canonical(u2, n, view), s"node $n")
  }

  test("canonical content does not move with recall participant order") {
    assertEquals(
      canonical(reversedParticipants(twoParticipants), e5, view)._1.participants,
      canonical(twoParticipants, e5, view)._1.participants
    )
  }

  test("canonical content does not see the unit's id or ordinal") {
    val renamed = u2.copy(id = RecallUnitId.unsafe("renamed"), ordinal = 99)
    assertEquals(canonical(renamed, e4, view), canonical(u2, e4, view))
  }

  test("teeth: source content keeps the sketch's participant order") {
    val (a, _, _) = ContentProjection.source(twoParticipants, view.node(e5).get, view)
    val (b, _, _) =
      ContentProjection.source(reversedParticipants(twoParticipants), view.node(e5).get, view)
    assertEquals(a.participants.reverse, b.participants)
    assertNotEquals(a.participants, b.participants)
  }

  test("source members keep reference order for reduction and storage order for the gate") {
    val (_, t, _) = ContentProjection.source(u2, view.node(sc2).get, view)
    val (_, r, _) = ContentProjection.source(u2, reversedView.node(sc2).get, reversedView)
    val predicates =
      (m: Members[GraphOrder.Source]) => (m.structural.map(_.predicate), m.leaves.map(_.predicate))
    assertEquals(predicates(t.members)._1, predicates(r.members)._1, "reference order is stable")
    assertEquals(
      predicates(t.members)._2.reverse,
      predicates(r.members)._2,
      "storage order is kept"
    )
  }

  test("members are counted from the source view, and equal as a multiset") {
    val (_, t, _) = ContentProjection.source(u2, view.node(sc2).get, view)
    val (_, r, _) = ContentProjection.source(u2, reversedView.node(sc2).get, reversedView)
    assertEquals(t.members.count, view.structuralMembers(sc2).size)
    assertEquals(t.members.withEvidence, view.structuralMembers(sc2).count(_.hasEvidence))
    assertEquals(t.members, r.members)
  }

  test("grain is the view's deepest level") {
    assertEquals(canonical(u2, e4, view)._3.maxLevel, view.maxLevel)
  }
