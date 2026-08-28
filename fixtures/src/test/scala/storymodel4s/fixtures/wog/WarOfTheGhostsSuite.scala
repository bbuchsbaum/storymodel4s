package storymodel4s.fixtures.wog

import storymodel4s.core.*
import storymodel4s.story.*
import WarOfTheGhostsModel.{C, E, G, S}

/** Acceptance tests for the hand fixture: it validates, every span comes from the atlas, and each
  * prohibited failure of design record §27.2 / §52.3 is impossible in this model.
  */
class WarOfTheGhostsSuite extends munit.FunSuite:

  val m = WarOfTheGhostsModel
  val g = m.graph
  val src = m.alignmentSource

  test("fixture validates with no errors") {
    assertEquals(m.validation.report.errors, Vector.empty, m.validation.report.render)
    assert(m.validation.validated.isDefined)
  }

  test("fixture has no warnings either") {
    assertEquals(m.validation.report.warnings, Vector.empty, m.validation.report.render)
  }

  test("source is the Boas text and the atlas has 50 sentences") {
    assertEquals(m.source.title, Some(WarOfTheGhostsText.title))
    assertEquals(m.atlas.sentences.size, 50)
  }

  test("every span in every claim and support comes from the atlas") {
    val units = m.atlas.byId
    def check(ss: SpanSet, where: String): Unit =
      ss.refs.toVector.foreach { r =>
        val u = r.unit.flatMap(units.get)
        assert(u.isDefined, s"$where: span ${r.span} has no atlas unit")
        assert(u.get.span.contains(r.span), s"$where: ${r.span} escapes unit ${u.get.span}")
      }
    m.model.claims.foreach(c => c.evidence.toVector.flatMap(_.spans).foreach(check(_, c.id.value)))
    g.situations.values.foreach(s => check(s.support, s.id.value))
    g.entities.values.foreach(e => check(e.support, e.id.value))
    g.segments.values.foreach(s => check(s.support, s.id.value))
    g.contexts.values.foreach(c => check(c.support, c.id.value))
  }

  test("claim ledger derives without duplicates") {
    assert(m.model.ledger.isRight, m.model.ledger.left.toOption.map(_.message).getOrElse(""))
    assertEquals(m.model.ledger.toOption.get.size, m.model.claims.size)
  }

  // --- §27.2 prohibitions ----------------------------------------------------------------------

  test("the battle is not duplicated by its retelling: exactly one narrated-world fight") {
    val fights = g.situations.values.filter(s =>
      s.predicate.lemma == "fight" && s.context == C.world && s.modality == Modality.Asserted
    )
    assertEquals(fights.map(_.id).toVector, Vector(S.battle))
    // the retold fight refers back rather than duplicating
    val retold = g.referencesOut(S.weFought)
    assert(retold.exists(r => r.to == S.battle && r.mode == NarrativeReference.Retrospective))
    assertEquals(g.contexts(g.situations(S.weFought).context).kind, ContextKind.Speech(E.ym2))
  }

  test("the announced battle is a prospective reference, not an occurrence") {
    val a = g.situations(S.announcedWar)
    assertEquals(a.modality, Modality.Intended)
    assertEquals(g.contexts(a.context).kind, ContextKind.Speech(E.warriors))
    assert(
      g.referencesOut(S.announcedWar)
        .exists(r => r.to == S.battle && r.mode == NarrativeReference.Prospective)
    )
  }

  test("the man never objectively knows the warriors are ghosts") {
    val ghosts = g.situations(S.warriorsAreGhosts)
    assertEquals(g.contexts(ghosts.context).kind, ContextKind.Belief(E.ym2))
    val w = g.entities(E.warriors)
    val ghostAttrs = w.attributes.filter(_.value == "ghosts")
    assert(ghostAttrs.nonEmpty)
    ghostAttrs.foreach(a => assertEquals(g.contexts(a.context).kind, ContextKind.Belief(E.ym2)))
    assert(
      !g.situations.values.exists(s =>
        s.context == C.world && s.description.contains("ghosts") && s.isState
      )
    )
  }

  test("warriors and ghosts are one entity with an identity hypothesis, not two groups") {
    assert(!g.entities.values.exists(_.label.value.toLowerCase.contains("ghost")))
    val kinds =
      g.entities(E.warriors).attributes.map(a => (g.contexts(a.context).kind, a.value)).toSet
    assertEquals(
      kinds,
      Set((ContextKind.Belief(E.ym2), "people"), (ContextKind.Belief(E.ym2), "ghosts"))
    )
  }

  test("the reported wound is speech-scoped and never a narrated-world fact") {
    val r = g.situations(S.reportedShot)
    assertEquals(r.modality, Modality.Reported)
    assertEquals(g.contexts(r.context).kind, ContextKind.Speech(E.warriors))
    val worldShotOfYm2 = g.situations.values.filter(s =>
      s.context == C.world && s.predicate.lemma == "shoot" &&
        g.participantsOf.getOrElse(s.id, Vector.empty).exists(_._2 == E.ym2)
    )
    assertEquals(worldShotOfYm2.toVector, Vector.empty)
    // the shot fellow is a different, narrated-world event with a different patient
    assertEquals(g.situations(S.fellowShot).context, C.world)
    assert(g.participantsOf(S.fellowShot).contains((ParticipantRole.Patient, E.fellow)))
  }

  test("the felt-illness state is negated and lives in the narrated world alongside the report") {
    val s = g.situations(S.notFeelSick)
    assertEquals(s.polarity, Polarity.Negative)
    assertEquals(s.context, C.world)
    assert(s.isState)
  }

  test("wound-caused-death is never explicit: only competing hypotheses") {
    val intoDeath = g.causalIn(S.dead)
    assert(intoDeath.size >= 2)
    intoDeath.foreach(e => assertEquals(e.meta.status, EpistemicStatus.Hypothesized, e.toString))
    assert(intoDeath.exists(_.cause == S.reportedShot))
    assert(intoDeath.exists(_.cause == S.warriorsAreGhosts))
  }

  test("discourse order and story-world chronology diverge at the recounting") {
    val posFought = g.discoursePosition(S.weFought)
    val posHouse = g.discoursePosition(S.ym2ToHouse)
    val posBattle = g.discoursePosition(S.battle)
    assert(posFought > posHouse)
    assert(posBattle < posHouse)
    val world = src.relationMatrix(RelationLayer.WorldTime)
    // strict world-time closure: battle precedes going home (battle → warriors go home → … → house)
    def reaches(a: SituationId, b: SituationId): Boolean =
      var frontier = Set(a)
      var seen = Set.empty[SituationId]
      var found = false
      while frontier.nonEmpty && !found do
        val next = frontier.flatMap(x =>
          world.keys.collect {
            case (NarrativeNodeId.Situation(f), NarrativeNodeId.Situation(t)) if f == x => t
          }
        )
        if next.contains(b) then found = true
        seen ++= frontier
        frontier = next -- seen
      found
    assert(reaches(S.battle, S.ym2ToHouse))
    assert(g.referencesOut(S.weFought).exists(_.to == S.battle))
  }

  // --- §88.3 plain-language questions ----------------------------------------------------------

  test("who acted on whom: warriors carried the shot fellow into the canoe") {
    val ps = src.participantsOf(NarrativeNodeId.Situation(S.carryIntoCanoe))
    assert(ps.exists(p => p.role == ParticipantRole.Agent && p.entity == E.warriors))
    assert(ps.exists(p => p.role == ParticipantRole.Patient && p.entity == E.fellow))
  }

  test("asserted vs reported vs believed are distinguishable through the API") {
    assertEquals(
      src.contextOf(NarrativeNodeId.Situation(S.battle)),
      Some(ContextKind.NarratedWorld)
    )
    assertEquals(src.modalityOf(NarrativeNodeId.Situation(S.battle)), Some(Modality.Asserted))
    assertEquals(
      src.contextOf(NarrativeNodeId.Situation(S.reportedShot)),
      Some(ContextKind.Speech(E.warriors))
    )
    assertEquals(src.modalityOf(NarrativeNodeId.Situation(S.reportedShot)), Some(Modality.Reported))
    assertEquals(
      src.contextOf(NarrativeNodeId.Situation(S.warriorsAreGhosts)),
      Some(ContextKind.Belief(E.ym2))
    )
  }

  test("same event vs prospective vs retrospective") {
    val refs = src.relationMatrix(RelationLayer.Reference)
    assert(
      refs.contains(
        (NarrativeNodeId.Situation(S.announcedWar), NarrativeNodeId.Situation(S.battle))
      )
    )
    assert(
      refs.contains((NarrativeNodeId.Situation(S.weFought), NarrativeNodeId.Situation(S.battle)))
    )
    assert(
      !refs.contains((NarrativeNodeId.Situation(S.battle), NarrativeNodeId.Situation(S.weFought)))
    )
  }

  test("polarity of the felt-sick state is negative; its retelling agrees") {
    assertEquals(src.polarityOf(NarrativeNodeId.Situation(S.notFeelSick)), Some(Polarity.Negative))
    assertEquals(
      src.polarityOf(NarrativeNodeId.Situation(S.iDidNotFeelSick)),
      Some(Polarity.Negative)
    )
    assert(g.referencesOut(S.iDidNotFeelSick).exists(_.to == S.notFeelSick))
  }

  test("nested speech: the warriors' words inside the young man's retelling") {
    val inner = g.contexts(C.speechYm2Inner)
    assertEquals(inner.parent, Some(C.speechYm2))
    assertEquals(inner.kind, ContextKind.Speech(E.warriors))
    assertEquals(g.situations(S.iWasShot).context, C.speechYm2Inner)
    assert(g.contextWithin(C.speechYm2Inner, C.world))
  }

  // --- hierarchy and alignment contract --------------------------------------------------------

  test("three-episode primary hierarchy with a single story root") {
    assertEquals(m.hierarchy.primaryRoot(g.segments.keys), Some(G.story))
    assertEquals(m.hierarchy.childrenOf(G.story).size, 3)
    assertEquals(m.hierarchy.descendantsAtLevel(G.story, 1, g.segments).size, 9)
    assertEquals(m.hierarchy.situationsUnder(G.story).toSet, g.situations.keySet)
  }

  test("alignment source exposes every level and sparse, edge-consistent matrices") {
    assertEquals(src.alignableNodes(Set(0)).size, g.situations.size)
    assertEquals(src.alignableNodes(Set(1)).size, 9)
    assertEquals(src.alignableNodes(Set(2)).size, 3)
    assertEquals(src.alignableNodes(Set(3)).size, 1)
    val world = src.relationMatrix(RelationLayer.WorldTime)
    assertEquals(world.size, g.relations.temporal.count(_.relation.isStrictPrecedence))
    val succ = src.relationMatrix(RelationLayer.DiscourseSuccession)
    assertEquals(succ.size, g.situations.size - 1)
    val cont = src.relationMatrix(RelationLayer.EntityContinuity)
    assert(cont.nonEmpty)
    assert(cont.values.forall(w => w > 0.0 && w <= 1.0))
    assert(cont.size < g.situations.size.toLong * g.situations.size)
    assertEquals(src.hierarchyMembership.size, m.hierarchy.containment.size)
    assertEquals(src.parentOf(NarrativeNodeId.Situation(S.battle)), Some(G.sc2c))
    assertEquals(src.parentOf(NarrativeNodeId.Segment(G.sc2c)), Some(G.ep2))
    assert(
      src.discoursePosition(NarrativeNodeId.Segment(G.ep3)).get > src
        .discoursePosition(NarrativeNodeId.Segment(G.ep2))
        .get
    )
  }

  test(
    "trajectory: world-time transitions carry derived claims; the recounting is a thread switch or unresolved"
  ) {
    val t = m.trajectory
    assertEquals(t.steps.size, g.situations.size - 1)
    t.steps.foreach(s => assertEquals(s.worldTime.meta.status, EpistemicStatus.StructurallyDerived))
    // the only discourse-backward world-time step: inside the warriors' speech the call to go
    // home is voiced before the shooting that motivates it
    val backward = t.steps.filter(_.worldTime.value.isBackward).map(s => (s.from, s.to))
    assertEquals(backward, Vector((S.letUsGoHome, S.reportedShot)))
    val into = t.stepFrom(S.makeFire).get
    assertEquals(into.to, S.recounting)
    assertEquals(into.worldTime.value, WorldTimeTransition.JumpForward(None))
  }

  test(
    "expectations reference existing fixture nodes and recall paraphrases reference existing targets"
  ) {
    WarOfTheGhostsExpectations.all.foreach { e =>
      e.fixtureNodes.foreach(id =>
        assert(g.situations.contains(id), s"${e.phenomenon}: ${id.value}")
      )
      e.sentences.foreach(n => assert(n >= 0 && n < 50))
    }
    WarOfTheGhostsExpectations.recallParaphrases.foreach { p =>
      p.targets.foreach {
        case NarrativeNodeId.Situation(id) => assert(g.situations.contains(id))
        case NarrativeNodeId.Segment(id)   => assert(g.segments.contains(id))
      }
    }
    assertEquals(WarOfTheGhostsExpectations.recallParaphrases.size, 10)
  }

  test("renderer is deterministic and mentions every situation") {
    val a = Renderer.text(m.model)
    val b = Renderer.text(m.model)
    assertEquals(a, b)
    g.situations.keys.foreach(id => assert(a.contains(id.value)))
    val dot = Renderer.dot(m.model)
    assert(dot.startsWith("digraph story {"))
    assertEquals(dot, Renderer.dot(m.model))
  }
