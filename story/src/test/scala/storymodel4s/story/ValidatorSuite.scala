package storymodel4s.story

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.SituationK
import Gens.given

/** One test per structural law: a generated valid model passes; a minimal mutation violates exactly
  * the targeted law.
  */
class ValidatorSuite extends ScalaCheckSuite:

  private def laws(m: StoryModel[ModelStatus.Draft]): Set[String] =
    StoryValidator.check(m).map(_.law).toSet

  private def sp(b: Small.Built, i: Int): SpanSet =
    SpanSet.one(SpanRef(Some(b.atlas.sentences(i).id), b.atlas.sentences(i).span))

  property("generated models validate under the default and strict policies") {
    forAll { (b: Small.Built) =>
      val out = StoryValidator.validate(b.draft(), ValidationPolicy.strict)
      assertEquals(out.report.violations, Vector.empty, out.report.render)
      out.validated.isDefined
    }
  }

  test("validated models expose an AlignmentSource; drafts cannot") {
    val b = Small.build(4, 2)
    val v = StoryValidator.validate(b.draft()).validated.get
    assertEquals(AlignmentSource(v).alignableNodes(Set(0)).size, 4)
    // compile-time: AlignmentSource(b.draft()) does not type-check
  }

  test("situation.context-exists") {
    val b = Small.build(2, 1)
    val g = b.graph.copy(contexts = Map.empty)
    val ls = laws(b.draft(graph = g))
    assert(ls.contains("situation.context-exists"), ls.toString)
    assert(ls.contains("context.single-root"))
  }

  test("temporal.strict-acyclic") {
    val b = Small.build(3, 1)
    val back = TemporalEdge(
      b.situations(2),
      TemporalRelation.Before,
      b.situations(0),
      b.world,
      Small.meta("back", EpistemicStatus.Hypothesized, None)
    )
    val g = b.graph.copy(relations =
      b.graph.relations.copy(temporal = b.graph.relations.temporal :+ back)
    )
    assertEquals(laws(b.draft(graph = g)), Set("temporal.strict-acyclic"))
  }

  test("temporal.strict-acyclic treats Equal endpoints as one node") {
    val b = Small.build(3, 1, chain = false)
    val s = b.situations
    val edges = Vector(
      TemporalEdge(
        s(0),
        TemporalRelation.Before,
        s(1),
        b.world,
        Small.meta("a", EpistemicStatus.Hypothesized, None)
      ),
      TemporalEdge(
        s(1),
        TemporalRelation.Equal,
        s(2),
        b.world,
        Small.meta("b", EpistemicStatus.Hypothesized, None)
      ),
      TemporalEdge(
        s(2),
        TemporalRelation.Before,
        s(0),
        b.world,
        Small.meta("c", EpistemicStatus.Hypothesized, None)
      )
    )
    val g = b.graph.copy(relations = b.graph.relations.copy(temporal = edges))
    assert(laws(b.draft(graph = g)).contains("temporal.strict-acyclic"))
  }

  test("temporal.equal-consistent and temporal.no-self") {
    val b = Small.build(2, 1)
    val s = b.situations
    val eq = TemporalEdge(
      s(0),
      TemporalRelation.Equal,
      s(1),
      b.world,
      Small.meta("eq", EpistemicStatus.Hypothesized, None)
    )
    val self = TemporalEdge(
      s(0),
      TemporalRelation.Before,
      s(0),
      b.world,
      Small.meta("self", EpistemicStatus.Hypothesized, None)
    )
    val g = b.graph.copy(relations =
      b.graph.relations.copy(temporal = b.graph.relations.temporal ++ Vector(eq, self))
    )
    val ls = laws(b.draft(graph = g))
    assert(ls.contains("temporal.equal-consistent"))
    assert(ls.contains("temporal.no-self"))
  }

  test("temporal.canonical-relation rejects stored converse forms") {
    val b = Small.build(2, 1, chain = false)
    val e = TemporalEdge(
      b.situations(1),
      TemporalRelation.After,
      b.situations(0),
      b.world,
      Small.meta("x", EpistemicStatus.Hypothesized, None)
    )
    val g = b.graph.copy(relations = b.graph.relations.copy(temporal = Vector(e)))
    assertEquals(laws(b.draft(graph = g)), Set("temporal.canonical-relation"))
    // its canonical form is accepted
    val g2 = b.graph.copy(relations = b.graph.relations.copy(temporal = Vector(e.canonical)))
    assertEquals(laws(b.draft(graph = g2)), Set.empty)
  }

  test("temporal.containment-acyclic") {
    val b = Small.build(2, 1, chain = false)
    val s = b.situations
    val edges = Vector(
      TemporalEdge(
        s(0),
        TemporalRelation.Contains,
        s(1),
        b.world,
        Small.meta("a", EpistemicStatus.Hypothesized, None)
      ),
      TemporalEdge(
        s(0),
        TemporalRelation.During,
        s(1),
        b.world,
        Small.meta("b", EpistemicStatus.Hypothesized, None)
      )
    )
    val g = b.graph.copy(relations = b.graph.relations.copy(temporal = edges))
    val ls = laws(b.draft(graph = g))
    assert(ls.contains("temporal.containment-acyclic"), ls.toString)
    // the same pair also carries During and Contains in contradictory orientations
    assert(ls.contains("temporal.pair-consistent"), ls.toString)
    assertEquals(ls, Set("temporal.containment-acyclic", "temporal.pair-consistent"))
  }

  test(
    "temporal.context-scope: a speech-scoped situation cannot be ordered in the narrated world"
  ) {
    val b = Small.build(2, 1, chain = false)
    val speech = ContextId.unsafe("ctx:speech")
    val frame = ContextFrame(
      speech,
      Some(b.world),
      ContextKind.Speech(b.entities(0)),
      sp(b, 0),
      Small.meta("ctx:speech", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0)))
    )
    val s0 = b.graph.situations(b.situations(0)) match
      case SituationNode.Event(n) => SituationNode.Event(n.copy(context = speech))
      case SituationNode.State(n) => SituationNode.State(n.copy(context = speech))
    val bad = TemporalEdge(
      b.situations(0),
      TemporalRelation.Before,
      b.situations(1),
      b.world,
      Small.meta("bad", EpistemicStatus.Hypothesized, None)
    )
    val good =
      bad.copy(context = speech, meta = Small.meta("good", EpistemicStatus.Hypothesized, None))
    val base = b.graph.copy(
      contexts = b.graph.contexts + (speech -> frame),
      situations = b.graph.situations.updated(s0.id, s0)
    )
    assertEquals(
      laws(b.draft(graph = base.copy(relations = base.relations.copy(temporal = Vector(bad))))),
      Set("temporal.context-scope")
    )
    assertEquals(
      laws(b.draft(graph = base.copy(relations = base.relations.copy(temporal = Vector(good))))),
      Set.empty
    )
  }

  test("statechange.target-is-state") {
    val b = Small.build(3, 1)
    val s = b.situations
    val badEdge = StateChangeEdge(
      s(0),
      StateChangeKind.Initiates,
      s(1),
      Small.meta("sc", EpistemicStatus.Hypothesized, None)
    )
    val okEdge = StateChangeEdge(
      s(0),
      StateChangeKind.Initiates,
      s(2),
      Small.meta("sc2", EpistemicStatus.Hypothesized, None)
    )
    val g = b.graph.copy(relations = b.graph.relations.copy(stateChanges = Vector(badEdge, okEdge)))
    assertEquals(laws(b.draft(graph = g)), Set("statechange.target-is-state"))
  }

  test("endpoints.* for dangling references") {
    val b = Small.build(2, 1)
    val ghost = SituationId.unsafe("sit:ghost")
    val rel = b.graph.relations.copy(
      causal = Vector(
        CausalEdge(
          ghost,
          CausalRelation.Causes,
          b.situations(0),
          Small.meta("c", EpistemicStatus.Hypothesized, None)
        )
      ),
      references = Vector(
        ReferenceEdge(
          b.situations(0),
          NarrativeReference.Summary,
          ghost,
          Small.meta("r", EpistemicStatus.Hypothesized, None)
        )
      ),
      goals = Vector(
        GoalEdge(
          ghost,
          GoalRelation.Motivates,
          ghost,
          Small.meta("g", EpistemicStatus.Hypothesized, None)
        )
      ),
      participants = b.graph.relations.participants :+ ParticipantEdge(
        ghost,
        ParticipantRole.Agent,
        EntityId.unsafe("nope"),
        Small.meta("p", EpistemicStatus.Hypothesized, None)
      )
    )
    val ls = laws(b.draft(graph = b.graph.copy(relations = rel)))
    assertEquals(
      ls,
      Set("endpoints.causal", "endpoints.reference", "endpoints.goal", "endpoints.participant")
    )
  }

  test("causal.cross-context-explicit is a warning that the default policy tolerates") {
    val b = Small.build(2, 1)
    val belief = ContextId.unsafe("ctx:belief")
    val frame = ContextFrame(
      belief,
      Some(b.world),
      ContextKind.Belief(b.entities(0)),
      sp(b, 0),
      Small.meta("ctx:b", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0)))
    )
    val s1 = b.graph.situations(b.situations(1)) match
      case SituationNode.Event(n) => SituationNode.Event(n.copy(context = belief))
      case SituationNode.State(n) => SituationNode.State(n.copy(context = belief))
    val edge = CausalEdge(
      b.situations(0),
      CausalRelation.Causes,
      b.situations(1),
      Small.meta("cx", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0)))
    )
    val g = b.graph.copy(
      contexts = b.graph.contexts + (belief -> frame),
      situations = b.graph.situations.updated(s1.id, s1),
      relations = b.graph.relations.copy(temporal = Vector.empty, causal = Vector(edge))
    )
    val out = StoryValidator.validate(b.draft(graph = g))
    assertEquals(out.report.warnings.map(_.law), Vector("causal.cross-context-explicit"))
    // a surface-explicit causal edge whose evidence carries no causal cue is an error
    assertEquals(
      out.report.errors.map(_.law),
      Vector("explicit-causal-requires-span-with-causal-cue")
    )
    assert(out.validated.isEmpty)
    // the same edge as an entailment: no warning, no error, validates under both policies
    val entailed =
      edge.copy(meta = Small.meta("cx2", EpistemicStatus.LinguisticallyEntailed, Some(sp(b, 0))))
    val g2 = g.copy(relations = g.relations.copy(causal = Vector(entailed)))
    val out2 = StoryValidator.validate(b.draft(graph = g2), ValidationPolicy.strict)
    assertEquals(out2.report.violations, Vector.empty, out2.report.render)
    assert(out2.validated.isDefined)
  }

  test("hierarchy.single-primary-root and hierarchy.no-empty-primary-segment") {
    val b = Small.build(2, 1)
    val extra = SegmentId.unsafe("seg:extra")
    val node = SegmentNode(
      extra,
      SegmentKind.Scene,
      1,
      Resolved("x", Small.meta("x", EpistemicStatus.Hypothesized, None), Vector.empty),
      sp(b, 0)
    )
    val g = b.graph.copy(segments = b.graph.segments + (extra -> node))
    val ls = laws(b.draft(graph = g))
    assertEquals(ls, Set("hierarchy.single-primary-root", "hierarchy.no-empty-primary-segment"))
  }

  test("hierarchy.situation-root-reachable") {
    val b = Small.build(2, 1)
    val h = b.hierarchy.copy(containment =
      b.hierarchy.containment.filterNot(_.member == NarrativeMember.Situation(b.situations(1)))
    )
    assertEquals(laws(b.draft(hierarchy = h)), Set("hierarchy.situation-root-reachable"))
  }

  test("containment.acyclic and hierarchy.level-consistent") {
    val b = Small.build(1, 1)
    val cyc = ContainmentEdge(
      NarrativeMember.Segment(b.root),
      b.scene,
      HierarchyKind.PrimarySegmentation,
      1.0,
      Small.meta("cyc", EpistemicStatus.Hypothesized, None)
    )
    val h = b.hierarchy.copy(containment = b.hierarchy.containment :+ cyc)
    val ls = laws(b.draft(hierarchy = h))
    assert(ls.contains("containment.acyclic"), ls.toString)
    assert(ls.contains("hierarchy.level-consistent"))
  }

  test("hierarchy.single-primary-parent") {
    val b = Small.build(1, 1)
    val dup = ContainmentEdge(
      NarrativeMember.Situation(b.situations(0)),
      b.root,
      HierarchyKind.PrimarySegmentation,
      1.0,
      Small.meta("dup", EpistemicStatus.Hypothesized, None)
    )
    val h = b.hierarchy.copy(containment = b.hierarchy.containment :+ dup)
    assert(laws(b.draft(hierarchy = h)).contains("hierarchy.single-primary-parent"))
  }

  test("claims.unique-ids (explicit-without-spans is unrepresentable in ClaimMeta)") {
    val b = Small.build(2, 1)
    // a SurfaceExplicit claim without spans cannot be constructed at all
    intercept[IllegalArgumentException] {
      Small.meta("never", EpistemicStatus.SurfaceExplicit, None)
    }
    val dupId =
      Small.meta("t:0", EpistemicStatus.Hypothesized, None) // same id as the chain edge claim
    val e = TemporalEdge(b.situations(0), TemporalRelation.Meets, b.situations(1), b.world, dupId)
    val g =
      b.graph.copy(relations = b.graph.relations.copy(temporal = b.graph.relations.temporal :+ e))
    val ls = laws(b.draft(graph = g))
    assert(ls.contains("claims.unique-ids"))
  }

  test("claims.spans-in-text and support.in-text") {
    val b = Small.build(1, 1)
    val far = SpanSet.one(TextSpan.unsafe(10_000, 10_005))
    val s0 = b.graph.situations(b.situations(0)) match
      case SituationNode.Event(n) =>
        SituationNode.Event(
          n.copy(
            support = far,
            meta = Small.meta("far", EpistemicStatus.SurfaceExplicit, Some(far))
          )
        )
      case SituationNode.State(n) =>
        SituationNode.State(
          n.copy(
            support = far,
            meta = Small.meta("far", EpistemicStatus.SurfaceExplicit, Some(far))
          )
        )
    val g = b.graph.copy(situations = Map(s0.id -> s0))
    val ls = laws(b.draft(graph = g))
    assert(ls.contains("claims.spans-in-text"))
    assert(ls.contains("support.in-text"))
  }

  test("context laws: root kind, acyclic parents, holder exists") {
    val b = Small.build(1, 1)
    val a = ContextId.unsafe("ctx:a")
    val c = ContextId.unsafe("ctx:c")
    val frames = Map(
      a -> ContextFrame(
        a,
        Some(c),
        ContextKind.Speech(EntityId.unsafe("nobody")),
        sp(b, 0),
        Small.meta("a", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0)))
      ),
      c -> ContextFrame(
        c,
        Some(a),
        ContextKind.Hypothetical,
        sp(b, 0),
        Small.meta("c", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0)))
      )
    )
    val g = b.graph.copy(contexts = b.graph.contexts ++ frames)
    val ls = laws(b.draft(graph = g))
    assert(ls.contains("context.acyclic"), ls.toString)
    assert(ls.contains("context.holder-exists"))
    val g2 = b.graph.copy(contexts =
      Map(
        a -> ContextFrame(
          a,
          None,
          ContextKind.Hypothetical,
          sp(b, 0),
          Small.meta("a", EpistemicStatus.SurfaceExplicit, Some(sp(b, 0)))
        )
      )
    )
    assert(laws(b.draft(graph = g2)).contains("context.root-is-narrated-world"))
  }

  test("feature laws") {
    val b = Small.build(1, 1)
    val space = FeatureSpaceId.unsafe("fs:sem")
    val refs = Vector(
      FeatureRef(FeatureTarget.Situation(b.situations(0)), space, 0),
      FeatureRef(
        FeatureTarget.Situation(SituationId.unsafe("sit:none")),
        FeatureSpaceId.unsafe("fs:none"),
        -1
      )
    )
    val spaces: Map[FeatureSpaceId, FeatureSpace[?]] = Map(
      space -> FeatureSpace[Vector[Double]](
        space,
        "sem",
        FeatureValueSchema.Vector(8),
        None,
        Small.fp,
        normalized = true
      )
    )
    val ls = laws(b.draft(featureSpaces = spaces, featureRefs = refs))
    assertEquals(
      ls,
      Set("feature.space-exists", "feature.target-exists", "feature.row-nonnegative")
    )
  }

  test("entity.mentions-unique") {
    val b = Small.build(1, 2)
    val e0 = b.graph.entities(b.entities(0))
    val e1 = b.graph.entities(b.entities(1)).copy(mentions = e0.mentions)
    val g = b.graph.copy(entities = b.graph.entities.updated(e1.id, e1))
    assertEquals(laws(b.draft(graph = g)), Set("entity.mentions-unique"))
  }

  test("findCycle finds a cycle and ignores acyclic graphs") {
    def s(i: Int) = SituationId.unsafe(s"x:$i")
    val acyclic = Map(s(0) -> Vector(s(1), s(2)), s(1) -> Vector(s(2)))
    assertEquals(StoryValidator.findCycle(acyclic), None)
    val cyclic = Map(s(0) -> Vector(s(1)), s(1) -> Vector(s(2)), s(2) -> Vector(s(0)))
    val cyc = StoryValidator.findCycle(cyclic).get
    assertEquals(cyc.head, cyc.last)
    assertEquals(cyc.size, 4)
  }

  test("ledger derivation fails on duplicate claim ids") {
    val b = Small.build(2, 1)
    val dupMeta = b.graph.situations(b.situations(0)).meta
    val s1 = b.graph.situations(b.situations(1)) match
      case SituationNode.Event(n) =>
        SituationNode.Event(
          n.copy(
            meta = dupMeta,
            mentions = NonEmptyVector.one(MentionId.unsafe[SituationK]("m:other"))
          )
        )
      case SituationNode.State(n) =>
        SituationNode.State(
          n.copy(
            meta = dupMeta,
            mentions = NonEmptyVector.one(MentionId.unsafe[SituationK]("m:other"))
          )
        )
    val g = b.graph.copy(situations = b.graph.situations.updated(s1.id, s1))
    assert(b.draft(graph = g).ledger.isLeft)
    assert(b.draft().ledger.isRight)
  }
