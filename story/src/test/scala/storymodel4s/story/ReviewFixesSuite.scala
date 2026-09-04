package storymodel4s.story

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.SituationK
import Gens.given

/** Laws introduced by the review fix pass: context-scoped world time, interval consistency, atlas
  * agreement, hierarchy span containment, alternatives, trajectory completeness, entity relations,
  * status-weighted relation views, and the narrative-consistency rules.
  */
class ReviewFixesSuite extends ScalaCheckSuite:

  private def laws(m: StoryModel[ModelStatus.Draft]): Set[String] =
    StoryValidator.check(m).map(_.law).toSet

  private def sp(b: Small.Built, i: Int): SpanSet =
    SpanSet.one(SpanRef(Some(b.atlas.sentences(i).id), b.atlas.sentences(i).span))

  // --- #1 / #27: world time is context-scoped -------------------------------------------------

  test("a Before edge scoped to a belief never enters narrated-world chronology") {
    val b = Small.build(3, 1, chain = false)
    val belief = ContextId.unsafe("ctx:belief")
    val frame = ContextFrame(
      belief,
      Some(b.world),
      ContextKind.Belief(ContextHolder.Named(b.entities(0))),
      sp(b, 0),
      Small.meta("ctx:b", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0)))
    )
    val rootEdge = Mutations.edge(b, 0, TemporalRelation.Before, 1, "root")
    val beliefEdge = Mutations.edge(b, 2, TemporalRelation.Before, 0, "belief", Some(belief))
    val g = b.graph.copy(
      contexts = b.graph.contexts + (belief -> frame),
      relations = b.graph.relations.copy(temporal = Vector(rootEdge, beliefEdge))
    )
    val out = StoryValidator.validate(b.draft(graph = g), ValidationPolicy.strict)
    assertEquals(out.report.violations, Vector.empty, out.report.render)
    val src = AlignmentSource(out.validated.get)
    val world = src.relationMatrix(RelationLayer.WorldTime)
    assertEquals(
      world.keySet,
      Set((NarrativeNodeId.Situation(b.situations(0)), NarrativeNodeId.Situation(b.situations(1))))
    )
    val inBelief = src.relationMatrixIn(RelationLayer.WorldTime, belief)
    assertEquals(
      inBelief.keySet,
      Set((NarrativeNodeId.Situation(b.situations(2)), NarrativeNodeId.Situation(b.situations(0))))
    )
    // the trajectory step 1 -> 2 has no root-scoped edge: unresolved, scoped to the root
    val t = out.validated.get.trajectory
    val step = t.stepFrom(b.situations(1)).get
    assertEquals(step.worldTime.value, WorldTimeTransition.Unresolved(Vector.empty))
    assertEquals(step.worldTimeContext, Some(b.world))
  }

  test("contradictory orders in different contexts are legal and kept apart") {
    val b = Small.build(2, 1, chain = false)
    val belief = ContextId.unsafe("ctx:belief")
    val frame = ContextFrame(
      belief,
      Some(b.world),
      ContextKind.Belief(ContextHolder.Named(b.entities(0))),
      sp(b, 0),
      Small.meta("ctx:b", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0)))
    )
    val g = b.graph.copy(
      contexts = b.graph.contexts + (belief -> frame),
      relations = b.graph.relations.copy(temporal =
        Vector(
          Mutations.edge(b, 0, TemporalRelation.Before, 1, "root"),
          Mutations.edge(b, 1, TemporalRelation.Before, 0, "belief", Some(belief))
        )
      )
    )
    assertEquals(laws(b.draft(graph = g)), Set.empty)
  }

  // --- #4: interval consistency ----------------------------------------------------------------

  test("temporal.interval-consistent rejects A Before B, C During B, C Before A") {
    val b = Small.build(3, 1, chain = false)
    assertEquals(laws(Mutations.intervalContradiction1(b)), Set("temporal.interval-consistent"))
  }

  test("temporal.interval-consistent rejects A Before B, A Starts B") {
    val b = Small.build(2, 1, chain = false)
    assertEquals(laws(Mutations.intervalContradiction2(b)), Set("temporal.interval-consistent"))
  }

  test("a consistent mixed interval model validates") {
    val b = Small.build(4, 1, chain = false)
    assertEquals(laws(Mutations.intervalConsistent(b)), Set.empty)
  }

  test("IntervalConsistency composes Allen relations through endpoints") {
    val a = SituationId.unsafe("a"); val c = SituationId.unsafe("c");
    val d = SituationId.unsafe("d")
    val ctx = ContextId.unsafe("x")
    def e(x: SituationId, r: TemporalRelation, y: SituationId) =
      TemporalEdge(
        x,
        r,
        y,
        ctx,
        Small.meta(s"${x.value}${y.value}", EpistemicStatus.Hypothesized, None)
      )
    // A Meets C, C Finishes D, D Before A  ⇒ A.e = C.s, C.e = D.e, D.e < A.s < A.e = C.s < C.e: cycle
    assert(
      IntervalConsistency
        .cycle(
          Vector(
            e(a, TemporalRelation.Meets, c),
            e(c, TemporalRelation.Finishes, d),
            e(d, TemporalRelation.Before, a)
          )
        )
        .isDefined
    )
    // A Overlaps C, C Overlaps D is fine
    assertEquals(
      IntervalConsistency.cycle(
        Vector(e(a, TemporalRelation.Overlaps, c), e(c, TemporalRelation.Overlaps, d))
      ),
      None
    )
  }

  // --- #26 pairwise temporal laws --------------------------------------------------------------

  test("temporal.no-duplicate and temporal.pair-consistent") {
    val b = Small.build(2, 1, chain = false)
    assertEquals(laws(Mutations.duplicateTemporal(b)), Set("temporal.no-duplicate"))
    assertEquals(laws(Mutations.unclearPlusStrict(b)), Set("temporal.pair-consistent"))
    val ls = laws(Mutations.overlapsBothWays(b))
    assert(ls.contains("temporal.pair-consistent"), ls.toString)
  }

  // --- #5 atlas agreement -------------------------------------------------------------------------

  test("atlas.source-match: an atlas of a different text is rejected") {
    val b = Small.build(2, 1)
    val ls = laws(Mutations.foreignAtlas(b))
    assert(ls.contains("atlas.source-match"), ls.toString)
  }

  // --- #26 hierarchy / alternatives / trajectory / features --------------------------------------

  test("hierarchy.member-within-parent") {
    val b = Small.build(3, 1)
    assert(laws(Mutations.sceneTooSmall(b)).contains("hierarchy.member-within-parent"))
  }

  test("claims.alternatives-distinct") {
    val b = Small.build(1, 1)
    assertEquals(laws(Mutations.selfAlternative(b)), Set("claims.alternatives-distinct"))
  }

  test("trajectory.complete") {
    val b = Small.build(3, 1)
    assertEquals(laws(Mutations.incompleteTrajectory(b)), Set("trajectory.complete"))
  }

  test("feature.dimension-consistent") {
    val b = Small.build(1, 1)
    val ls = laws(Mutations.dimensionMismatch(b))
    assert(ls.contains("feature.dimension-consistent"), ls.toString)
  }

  // --- #3 claims include labels and summaries ------------------------------------------------------

  property("every entity label and segment summary claim is in the model's claims") {
    forAll { (b: Small.Built) =>
      val ids = b.draft().claims.map(_.id).toSet
      b.graph.entities.values.forall(e => ids.contains(e.label.meta.id)) &&
      b.graph.segments.values.forall(s =>
        ids.contains(s.meta.id) && s.summary.stated.forall(r => ids.contains(r.meta.id))
      )
    }
  }

  // --- #34 discourse order by hull ------------------------------------------------------------------

  test("discourse order uses the earliest span even when it is not the first stored ref") {
    val b = Small.build(3, 1)
    val s2 = b.graph.situations(b.situations(2))
    // give situation 2 an additional earlier span listed second: it should now come first
    val support = SpanSet.of(Vector(sp(b, 2).refs.head, sp(b, 0).refs.head)).get
    val moved = s2 match
      case SituationNode.Event(n) => SituationNode.Event(n.copy(support = support))
      case SituationNode.State(n) => SituationNode.State(n.copy(support = support))
    val g = b.graph.copy(situations = b.graph.situations.updated(moved.id, moved))
    // its hull now starts with sentence 0, so it precedes situation 1 (and ties with 0 on start,
    // losing on the longer hull end)
    assert(g.discourseOrder.indexOf(b.situations(2)) < g.discourseOrder.indexOf(b.situations(1)))
    assertEquals(g.discourseOrder.head, b.situations(0))
  }

  // --- #35 status-weighted relation views -----------------------------------------------------------

  test("relationEdges keeps status and the matrix weight follows StatusWeight") {
    val b = Small.build(2, 1)
    val edge = CausalEdge(
      b.situations(0),
      CausalRelation.Causes,
      b.situations(1),
      Small.meta("cx", EpistemicStatus.Hypothesized, Some(sp(b, 0)))
    )
    val g = b.graph.copy(relations = b.graph.relations.copy(causal = Vector(edge)))
    val v = StoryValidator.validate(b.draft(graph = g)).validated.get
    val src = AlignmentSource(v)
    val es = src.relationEdges(RelationLayer.Causal)
    assertEquals(es.map(_.status), Vector(EpistemicStatus.Hypothesized))
    assertEquals(src.relationMatrix(RelationLayer.Causal).values.toVector, Vector(0.25))
    assertEquals(StatusWeight.of(EpistemicStatus.SurfaceExplicit), 1.0)
  }

  // --- #20 entity relations ---------------------------------------------------------------------------

  test("MemberOf makes a member continuous with its group's situations") {
    val b = Small.build(2, 2, chain = false)
    // situation 0 has entity 0 (the group), situation 1 has entity 1; make entity 1 a member of 0
    val member = EntityEdge(
      b.entities(1),
      EntityRelation.MemberOf,
      b.entities(0),
      Small.meta("mem", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0)))
    )
    val g = b.graph.copy(relations = b.graph.relations.copy(entityRelations = Vector(member)))
    assertEquals(g.situationsByEntity(b.entities(1)).toSet, Set(b.situations(0), b.situations(1)))
    val v = StoryValidator.validate(b.draft(graph = g), ValidationPolicy.strict)
    assertEquals(v.report.violations, Vector.empty, v.report.render)
    val cont = AlignmentSource(v.validated.get).relationMatrix(RelationLayer.EntityContinuity)
    assert(
      cont.contains(
        (NarrativeNodeId.Situation(b.situations(0)), NarrativeNodeId.Situation(b.situations(1)))
      )
    )
    // without the edge the two situations share nothing
    assert(
      !AlignmentSource(StoryValidator.validate(b.draft()).validated.get)
        .relationMatrix(RelationLayer.EntityContinuity)
        .contains(
          (NarrativeNodeId.Situation(b.situations(0)), NarrativeNodeId.Situation(b.situations(1)))
        )
    )
  }

  test("entity-relation.membership-acyclic") {
    val b = Small.build(1, 2)
    assert(laws(Mutations.membershipCycle(b)).contains("entity-relation.membership-acyclic"))
  }

  // --- hypotheses ----------------------------------------------------------------------------------------

  test("hypothesis laws: status, alternatives, subject") {
    val b = Small.build(1, 1)
    val good = HypothesisClaim(
      b.situations(0),
      Resolved(
        "it happened",
        Small.meta("h", EpistemicStatus.Hypothesized, None),
        Vector(("it did not happen", Credence.unsafeRaw(0.4, ScorerId.unsafe("test-scorer"))))
      )
    )
    val draft = StoryModel.draft(
      b.source,
      b.atlas,
      b.graph,
      b.hierarchy,
      DiscourseTrajectory.derive(b.graph, b.hierarchy, b.atlas),
      hypotheses = Vector(good)
    )
    // the subject is SurfaceExplicit in Small.build, so the consistency checker warns
    val out = StoryValidator.validate(draft)
    assertEquals(out.report.errors, Vector.empty)
    assertEquals(out.report.warnings.map(_.law), Vector("hypothesis-subject-explicit"))
    val noAlt = good.copy(reading = good.reading.copy(alternatives = Vector.empty))
    assert(laws(draft.copy(hypotheses = Vector(noAlt))).contains("hypothesis.has-alternatives"))
    val wrongStatus = good.copy(reading =
      good.reading.copy(meta = Small.meta("h2", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0))))
    )
    assert(laws(draft.copy(hypotheses = Vector(wrongStatus))).contains("hypothesis.status"))
  }

  // --- narrative consistency ----------------------------------------------------------------------------

  test("reported modality in the narrated world is an error") {
    val b = Small.build(1, 1)
    val s = b.graph.situations(b.situations(0)) match
      case SituationNode.Event(n) => SituationNode.Event(n.copy(modality = Modality.Reported))
      case SituationNode.State(n) => SituationNode.State(n.copy(modality = Modality.Reported))
    val g = b.graph.copy(situations = b.graph.situations.updated(s.id, s))
    val out = StoryValidator.validate(b.draft(graph = g))
    assertEquals(
      out.report.errors.map(_.law),
      Vector("reported-content-not-root-without-root-claim")
    )
  }

  test("a retelling duplicated as an asserted occurrence in the same context is flagged") {
    val b = Small.build(2, 1, chain = false)
    val s0 = b.graph.situations(b.situations(0))
    val dup = s0 match
      case SituationNode.Event(n) =>
        SituationNode.Event(
          n.copy(
            id = b.situations(1),
            support = sp(b, 1),
            mentions = NonEmptyVector.one(MentionId.unsafe[SituationK]("m:dup")),
            meta = Small.meta("dup", EpistemicStatus.SurfaceExplicit, Some(sp(b, 1)))
          )
        )
      case SituationNode.State(n) => SituationNode.State(n)
    val ref = ReferenceEdge(
      b.situations(1),
      NarrativeReference.Retrospective,
      b.situations(0),
      Small.meta("r", EpistemicStatus.LinguisticallyEntailed, Some(sp(b, 1)))
    )
    val parts = b.graph.relations.participants.map(p => p.copy(situation = b.situations(0))) ++
      b.graph.relations.participants.map(p =>
        p.copy(
          situation = b.situations(1),
          meta = Small.meta("p2", EpistemicStatus.SurfaceExplicit, Some(sp(b, 1)))
        )
      )
    val g = b.graph.copy(
      situations = b.graph.situations.updated(dup.id, dup),
      relations = b.graph.relations.copy(references = Vector(ref), participants = parts.distinct)
    )
    val out = StoryValidator.validate(b.draft(graph = g))
    val ws = out.report.warnings.map(_.law).toSet
    assert(ws.contains("no-duplicate-occurrence-via-retrospective-reference"), out.report.render)
  }
