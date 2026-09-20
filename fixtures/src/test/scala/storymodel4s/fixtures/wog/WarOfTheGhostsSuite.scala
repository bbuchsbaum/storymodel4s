package storymodel4s.fixtures.wog

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.SituationK
import storymodel4s.story.*
import WarOfTheGhostsModel.{C, E, G, S}

/** Acceptance tests for the hand fixture: it validates, every span comes from the atlas, every
  * plain-language expectation holds mechanically, each prohibited failure of design record §27.2 /
  * §52.3 is impossible in this model, and an adversarial mutant realizing each prohibition is
  * caught by the validator, the consistency checker, or the expectation evaluator.
  */
class WarOfTheGhostsSuite extends munit.FunSuite:

  val m = WarOfTheGhostsModel
  val g = m.graph
  val src = m.alignmentSource
  private def sit(id: SituationId) = NarrativeNodeId.Situation(id)

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
    g.situations.values.foreach(s => check(s.support.textSpans.get, s.id.value))
    g.entities.values.foreach(e => check(e.support.textSpans.get, e.id.value))
    g.segments.values.foreach(s => check(s.support.textSpans.get, s.id.value))
    g.contexts.values.foreach(c => check(c.support.textSpans.get, c.id.value))
  }

  test("claim ledger derives without duplicates and covers labels, summaries, hypotheses") {
    assert(m.model.ledger.isRight, m.model.ledger.left.toOption.map(_.message).getOrElse(""))
    assertEquals(m.model.ledger.toOption.get.size, m.model.claims.size)
    val ids = m.model.claims.map(_.id).toSet
    g.entities.values.foreach(e => assert(ids.contains(e.label.meta.id)))
    g.segments.values.foreach { s =>
      assert(ids.contains(s.meta.id))
      s.summary.stated.foreach(r => assert(ids.contains(r.meta.id)))
    }
    m.hypotheses.foreach(h => assert(ids.contains(h.meta.id)))
  }

  // --- every plain-language expectation holds mechanically ------------------------------------

  test("every expectation check passes against the fixture") {
    val failures = WarOfTheGhostsExpectations.evaluate(m.model)
    assertEquals(
      failures.map((e, c, why) => s"${e.phenomenon} / $c: $why"),
      Vector.empty
    )
    assertEquals(WarOfTheGhostsExpectations.all.size, 12)
    WarOfTheGhostsExpectations.all.foreach(e => assert(e.checks.nonEmpty, e.phenomenon))
  }

  // --- §27.2 prohibitions, structurally --------------------------------------------------------

  test("the battle is not duplicated by its retelling: one asserted narrated-world fight") {
    val fights = g.situations.values.filter(s =>
      s.isEvent && s.predicate.lemma == "fight" && g.rootContext.contains(s.context) &&
        s.modality == Modality.Asserted
    )
    assertEquals(fights.map(_.id).toVector, Vector(S.battle))
    assert(
      g.referencesOut(S.weFought)
        .exists(r => r.to == S.battle && r.mode == NarrativeReference.Retrospective)
    )
    assertEquals(
      g.contexts(g.situations(S.weFought).context).kind,
      ContextKind.Speech(ContextHolder.Named(E.ym2))
    )
  }

  test("the announced battle is a prospective reference, not an occurrence") {
    val a = g.situations(S.announcedWar)
    assertEquals(a.modality, Modality.Intended)
    assertEquals(g.contexts(a.context).kind, ContextKind.Speech(ContextHolder.Named(E.warriors)))
    assert(
      g.referencesOut(S.announcedWar)
        .exists(r => r.to == S.battle && r.mode == NarrativeReference.Prospective)
    )
  }

  test("the man never objectively knows the warriors are ghosts") {
    // the belief content is belief-scoped and its theme is the one warriors entity
    assertEquals(
      g.contexts(g.situations(S.warriorsAreGhosts).context).kind,
      ContextKind.Belief(ContextHolder.Named(E.ym2))
    )
    assert(g.participantsOf(S.warriorsAreGhosts).contains((ParticipantRole.Theme, E.warriors)))
    // no narrated-world state has the warriors as theme with a belief-only predicate
    val rootStatesAboutWarriors = g.situations.values.filter(s =>
      s.isState && g.rootContext.contains(s.context) &&
        g.participantsOf
          .getOrElse(s.id, Vector.empty)
          .exists(_ == (ParticipantRole.Theme, E.warriors))
    )
    assertEquals(rootStatesAboutWarriors.map(_.id).toSet, Set(S.fiveMenInCanoe))
    // the ghost attribute exists only under the belief context
    val w = g.entities(E.warriors)
    assert(w.attributes.exists(_.value == "ghosts"))
    w.attributes.foreach(a => assert(!g.rootContext.contains(a.context), a.toString))
  }

  test("warriors and ghosts are one entity: every ghost/people predication targets E.warriors") {
    Vector(S.warriorsAreGhosts, S.warriorsArePeople, S.accompaniedGhosts, S.fiveMenInCanoe)
      .foreach { s =>
        val themes = g.participantsOf(s).collect { case (ParticipantRole.Theme, e) => e }
        assertEquals(themes, Vector(E.warriors), s.value)
      }
    val kinds =
      g.entities(E.warriors).attributes.map(a => (g.contexts(a.context).kind, a.value)).toSet
    assertEquals(
      kinds,
      Set(
        (ContextKind.Belief(ContextHolder.Named(E.ym2)), "people"),
        (ContextKind.Belief(ContextHolder.Named(E.ym2)), "ghosts")
      )
    )
    // the belief change is a state transition, not a coreference between contradictory contents
    assert(
      g.stateChangesByEvent(S.ym2ConcludesGhosts)
        .exists(e => e.change == StateChangeKind.Terminates && e.state == S.warriorsArePeople)
    )
    assert(
      g.stateChangesByEvent(S.ym2ConcludesGhosts)
        .exists(e => e.change == StateChangeKind.Initiates && e.state == S.warriorsAreGhosts)
    )
  }

  test("the reported wound is speech-scoped; its narrated-world truth is an open hypothesis") {
    val r = g.situations(S.reportedShot)
    assertEquals(r.modality, Modality.Reported)
    assertEquals(g.contexts(r.context).kind, ContextKind.Speech(ContextHolder.Named(E.warriors)))
    // no asserted narrated-world shooting of the young man
    val assertedRootShotOfYm2 = g.situations.values.filter(s =>
      g.rootContext.contains(s.context) && s.predicate.lemma == "shoot" &&
        s.modality == Modality.Asserted &&
        g.participantsOf.getOrElse(s.id, Vector.empty).exists(_._2 == E.ym2)
    )
    assertEquals(assertedRootShotOfYm2.toVector, Vector.empty)
    // the hypothesis is there with both readings
    val inj = g.situations(S.ym2Injured)
    assertEquals(inj.meta.status, EpistemicStatus.Hypothesized)
    assertEquals(inj.modality, Modality.Possible)
    val hyp = m.hypotheses.find(_.subject == S.ym2Injured).get
    assertEquals(hyp.reading.alternatives.size, 1)
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

  test(
    "wound-caused-death is never explicit: only competing hypotheses from narrated-world nodes"
  ) {
    val intoDeath = g.causalIn(S.dead)
    assertEquals(intoDeath.size, 2)
    intoDeath.foreach(e => assertEquals(e.meta.status, EpistemicStatus.Hypothesized, e.toString))
    intoDeath.foreach(e =>
      assert(g.rootContext.contains(g.situations(e.cause).context), e.cause.value)
    )
    assert(intoDeath.exists(_.cause == S.ym2Injured))
    assert(intoDeath.exists(_.cause == S.ym2Accompanies))
  }

  test("discourse order and narrated-world chronology diverge at the recounting") {
    assert(m.model.discoursePosition(S.weFought) > m.model.discoursePosition(S.ym2ToHouse))
    assert(m.model.discoursePosition(S.battle) < m.model.discoursePosition(S.ym2ToHouse))
    val world = src.relationMatrix(RelationLayer.WorldTime)
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
    // the chain is closed from the battle's internal events through the return to the death
    assert(reaches(S.fellowShot, S.dead))
    assert(reaches(S.ym2ConcludesGhosts, S.dead))
  }

  test("world time is root-scoped: speech-internal order never enters the narrated-world view") {
    val world = src.relationMatrix(RelationLayer.WorldTime)
    assertEquals(
      world.size,
      g.relations.temporal.count(e => e.relation.isStrictPrecedence && e.context == C.world)
    )
    assert(!world.contains((sit(S.reportedShot), sit(S.letUsGoHome))))
    val inSpeech = src.relationMatrixIn(RelationLayer.WorldTime, C.speechWarriors3)
    assert(inSpeech.contains((sit(S.reportedShot), sit(S.letUsGoHome))))
    world.keys.foreach { case (a, b) =>
      assert(
        src.contextIdOf(a).contains(C.world) && src.contextIdOf(b).contains(C.world),
        s"$a -> $b"
      )
    }
  }

  // --- §88.3 plain-language questions ----------------------------------------------------------

  test("who acted on whom: warriors carried the shot fellow into the canoe") {
    val ps = src.participantsOf(sit(S.carryIntoCanoe))
    assert(ps.exists(p => p.role == ParticipantRole.Agent && p.entity == E.warriors))
    assert(ps.exists(p => p.role == ParticipantRole.Patient && p.entity == E.fellow))
  }

  test("asserted vs reported vs believed are distinguishable through the API") {
    assertEquals(src.contextOf(sit(S.battle)), Some(ContextKind.NarratedWorld))
    assertEquals(src.modalityOf(sit(S.battle)), Some(Modality.Asserted))
    assertEquals(
      src.contextOf(sit(S.reportedShot)),
      Some(ContextKind.Speech(ContextHolder.Named(E.warriors)))
    )
    assertEquals(src.modalityOf(sit(S.reportedShot)), Some(Modality.Reported))
    assertEquals(
      src.contextOf(sit(S.warriorsAreGhosts)),
      Some(ContextKind.Belief(ContextHolder.Named(E.ym2)))
    )
  }

  test("same event vs prospective vs retrospective") {
    val refs = src.relationMatrix(RelationLayer.Reference)
    assert(refs.contains((sit(S.announcedWar), sit(S.battle))))
    assert(refs.contains((sit(S.weFought), sit(S.battle))))
    assert(!refs.contains((sit(S.battle), sit(S.weFought))))
    // status survives in the edge view
    assert(
      src
        .relationEdges(RelationLayer.Reference)
        .forall(_.status == EpistemicStatus.LinguisticallyEntailed)
    )
  }

  test("polarity of the felt-sick state is negative; its retelling agrees") {
    assertEquals(src.polarityOf(sit(S.notFeelSick)), Some(Polarity.Negative))
    assertEquals(src.polarityOf(sit(S.iDidNotFeelSick)), Some(Polarity.Negative))
    assert(g.referencesOut(S.iDidNotFeelSick).exists(_.to == S.notFeelSick))
  }

  test("nested speech: the warriors' words inside the young man's retelling") {
    val inner = g.contexts(C.speechYm2Inner)
    assertEquals(inner.parent, Some(C.speechYm2))
    assertEquals(inner.kind, ContextKind.Speech(ContextHolder.Named(E.warriors)))
    assertEquals(g.situations(S.iWasShot).context, C.speechYm2Inner)
    assert(g.contextWithin(C.speechYm2Inner, C.world))
  }

  test(
    "the two young men are distinct entities and members of the pair; the pair's actions reach them"
  ) {
    assert(E.ym1 != E.ym2)
    assert(g.groupsOf(E.ym1).contains(E.youngMen))
    assert(g.groupsOf(E.ym2).contains(E.youngMen))
    assert(m.model.situationsByEntity(E.ym2).contains(S.huntSeals))
    val cont = src.relationMatrix(RelationLayer.EntityContinuity)
    assert(cont.contains((sit(S.huntSeals), sit(S.ym2Accompanies))))
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
    val succ = src.relationMatrix(RelationLayer.DiscourseSuccession)
    assertEquals(succ.size, g.situations.size - 1)
    val cont = src.relationMatrix(RelationLayer.EntityContinuity)
    assert(cont.nonEmpty)
    assert(cont.values.forall(w => w > 0.0 && w <= 1.0))
    assert(cont.size < g.situations.size.toLong * g.situations.size)
    assertEquals(src.hierarchyMembership.size, m.hierarchy.containment.size)
    assertEquals(src.parentOf(sit(S.battle)), Some(G.sc2c))
    assertEquals(src.parentOf(NarrativeNodeId.Segment(G.sc2c)), Some(G.ep2))
    assert(
      src.discoursePosition(NarrativeNodeId.Segment(G.ep3)).get > src
        .discoursePosition(NarrativeNodeId.Segment(G.ep2))
        .get
    )
    // hypothesized causal edges are weighted below explicit ones and keep their status
    val causal = src.relationEdges(RelationLayer.Causal)
    causal
      .filter(_.status == EpistemicStatus.Hypothesized)
      .foreach(e => assertEquals(e.weight, 0.25))
    causal
      .filter(_.status == EpistemicStatus.LinguisticallyEntailed)
      .foreach(e => assertEquals(e.weight, 1.0))
  }

  test(
    "trajectory: no narrated-world backward step; the only backward step is inside the warriors' speech"
  ) {
    val t = m.trajectory
    assertEquals(t.steps.size, g.situations.size - 1)
    t.steps.foreach(s => assertEquals(s.worldTime.meta.status, EpistemicStatus.StructurallyDerived))
    val rootBackward = t.stepsIn(C.world).filter(_.worldTime.value.isBackward)
    assertEquals(rootBackward.map(s => (s.from, s.to)), Vector.empty)
    val backward = t.steps.filter(_.worldTime.value.isBackward)
    assertEquals(
      backward.map(s => (s.from, s.to, s.worldTimeContext)),
      Vector((S.letUsGoHome, S.reportedShot, Some(C.speechWarriors3)))
    )
    val into = t.stepFrom(S.makeFire).get
    assertEquals(into.to, S.recounting)
    assertEquals(into.worldTime.value, WorldTimeTransition.JumpForward(None))
    assertEquals(into.worldTimeContext, Some(C.world))
  }

  test("recall paraphrases reference existing targets") {
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

  // --- adversarial mutants: each §27.2 prohibition, realized, is caught ------------------------

  private def redraft(graph: NarrativeGraph, hierarchy: NarrativeHierarchy = m.hierarchy) =
    StoryModel
      .draftText(
        m.atlas,
        graph,
        hierarchy,
        DiscourseTrajectory
          .derive(graph, hierarchy, m.atlas)
          .fold(error => throw new IllegalArgumentException(error.message), identity),
        descriptors = m.descriptors,
        hypotheses = m.hypotheses
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  private def caught(draft: TextModel[ModelStatus.Draft], law: String): Unit =
    val vs = StoryValidator.validate(draft).report.violations
    assert(
      vs.exists(_.law == law),
      s"expected $law, got:\n${vs.map(_.law).distinct.mkString("\n")}"
    )

  test("mutant: the retold battle encoded as a second narrated-world occurrence is caught") {
    val battle = g.situations(S.battle) match
      case SituationNode.Event(n) => n
      case _                      => fail("battle is an event")
    val dupId = SituationId.unsafe("wog:sit:mutant-battle-again")
    val dup = SituationNode.Event(
      battle.copy(
        id = dupId,
        support = TypedSupport.Text(m.sp(39)),
        mentions = NonEmptyVector.one(MentionId.unsafe[SituationK]("wog:m:sit:mutant:s39")),
        meta = m.meta("mutant:battle-again", EpistemicStatus.SurfaceExplicit, Some(m.sp(39)))
      )
    )
    val ref = ReferenceEdge(
      dupId,
      NarrativeReference.Retrospective,
      S.battle,
      m.meta("mutant:ref", EpistemicStatus.LinguisticallyEntailed, Some(m.sp(39)))
    )
    val parts = g
      .participantsOf(S.battle)
      .map((r, e) =>
        ParticipantEdge(
          dupId,
          r,
          e,
          m.meta(
            s"mutant:part:${r.render}:${e.value}",
            EpistemicStatus.SurfaceExplicit,
            Some(m.sp(39))
          )
        )
      )
    val contain = ContainmentEdge(
      NarrativeMember.Situation(dupId),
      G.sc3b,
      HierarchyKind.PrimarySegmentation,
      1.0,
      m.meta("mutant:contain", EpistemicStatus.HumanAdjudicated, None)
    )
    val graph = g.copy(
      situations = g.situations.updated(dupId, dup),
      relations = g.relations.copy(
        references = g.relations.references :+ ref,
        participants = g.relations.participants ++ parts
      )
    )
    val h = m.hierarchy.copy(containment = m.hierarchy.containment :+ contain)
    caught(redraft(graph, h), "no-duplicate-occurrence-via-retrospective-reference")
    // and the expectation "exactly one asserted narrated-world fight" fails
    assert(WarOfTheGhostsExpectations.evaluate(redraft(graph, h)).nonEmpty)
  }

  test("mutant: the reported injury promoted to narrated-world fact is caught") {
    // (a) the report itself moved to the root with Reported modality
    val moved = g.situations(S.reportedShot) match
      case SituationNode.Event(n) => SituationNode.Event(n.copy(context = C.world))
      case SituationNode.State(n) => SituationNode.State(n.copy(context = C.world))
    val graphA = g.copy(situations = g.situations.updated(S.reportedShot, moved))
    caught(redraft(graphA), "reported-content-not-root-without-root-claim")
    // (b) a new asserted narrated-world "shot" of the young man on the report's span, unlinked
    val promotedId = SituationId.unsafe("wog:sit:mutant-shot-fact")
    val promoted = g.situations(S.reportedShot) match
      case SituationNode.Event(n) =>
        SituationNode.Event(
          n.copy(
            id = promotedId,
            context = C.world,
            modality = Modality.Asserted,
            mentions =
              NonEmptyVector.one(MentionId.unsafe[SituationK]("wog:m:sit:mutant-shot:s30")),
            meta = m.meta("mutant:shot-fact", EpistemicStatus.SurfaceExplicit, Some(m.sp(30)))
          )
        )
      case SituationNode.State(_) => fail("reported shot is an event")
    val part = ParticipantEdge(
      promotedId,
      ParticipantRole.Patient,
      E.ym2,
      m.meta("mutant:shot-part", EpistemicStatus.SurfaceExplicit, Some(m.sp(30)))
    )
    val contain = ContainmentEdge(
      NarrativeMember.Situation(promotedId),
      G.sc3a,
      HierarchyKind.PrimarySegmentation,
      1.0,
      m.meta("mutant:contain2", EpistemicStatus.HumanAdjudicated, None)
    )
    val graphB = g.copy(
      situations = g.situations.updated(promotedId, promoted),
      relations = g.relations.copy(participants = g.relations.participants :+ part)
    )
    val h = m.hierarchy.copy(containment = m.hierarchy.containment :+ contain)
    caught(redraft(graphB, h), "reported-content-not-root-without-root-claim")
  }

  test("mutant: an explicit injury-caused-death edge is caught") {
    val explicitEdge = CausalEdge(
      S.ym2Injured,
      CausalRelation.Causes,
      S.dead,
      m.meta("mutant:cause", EpistemicStatus.SurfaceExplicit, Some(m.sp(30, 47)))
    )
    val graph = g.copy(relations = g.relations.copy(causal = g.relations.causal :+ explicitEdge))
    caught(redraft(graph), "explicit-causal-requires-span-with-causal-cue")
    val failures = WarOfTheGhostsExpectations.evaluate(redraft(graph))
    assert(failures.exists(_._1.phenomenon == "death cause uncertain"))
  }

  test(
    "mutant: splitting the warriors into a separate ghosts entity is caught by the expectations"
  ) {
    val ghostsId = EntityId.unsafe("wog:ent:mutant-ghosts")
    val ghosts = g
      .entities(E.warriors)
      .copy(
        id = ghostsId,
        label = Resolved(
          "the ghosts",
          m.meta("mutant:ghosts:label", EpistemicStatus.SurfaceExplicit, Some(m.sp(31))),
          Vector.empty
        ),
        mentions = NonEmptyVector.one(
          MentionId.unsafe[storymodel4s.core.NarrativeKind.EntityK]("wog:m:ent:mutant-ghosts:s31")
        ),
        attributes = Vector.empty,
        support = TypedSupport.Text(m.sp(31)),
        meta = m.meta("mutant:ghosts", EpistemicStatus.SurfaceExplicit, Some(m.sp(31)))
      )
    val retargeted = g.relations.participants.map(p =>
      if p.situation == S.warriorsAreGhosts && p.role == ParticipantRole.Theme then
        p.copy(entity = ghostsId)
      else p
    )
    val graph = g.copy(
      entities = g.entities.updated(ghostsId, ghosts),
      relations = g.relations.copy(participants = retargeted)
    )
    val draft = redraft(graph)
    assertEquals(StoryValidator.validate(draft).report.errors, Vector.empty)
    val failures = WarOfTheGhostsExpectations.evaluate(draft)
    assert(failures.exists(_._1.phenomenon == "man concludes they are ghosts"), failures.toString)
  }

  test("mutant: a narrated-world fact ordered by a belief-scoped edge does not reach world time") {
    val beliefEdge = TemporalEdge(
      S.dead,
      TemporalRelation.Before,
      S.battle,
      C.beliefGhosts,
      m.meta("mutant:belief-order", EpistemicStatus.Hypothesized, None)
    )
    val graph = g.copy(relations = g.relations.copy(temporal = g.relations.temporal :+ beliefEdge))
    val out = StoryValidator.validate(redraft(graph))
    assertEquals(out.report.errors, Vector.empty, out.report.render)
    val world = AlignmentSource(out.validated.get.model).relationMatrix(RelationLayer.WorldTime)
    assert(!world.contains((sit(S.dead), sit(S.battle))))
  }
