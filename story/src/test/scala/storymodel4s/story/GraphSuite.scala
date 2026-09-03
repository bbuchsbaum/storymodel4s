package storymodel4s.story

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.core.*
import storymodel4s.features.Estimate
import Gens.given

/** Index consistency, alignment-source consistency, trajectory derivation, renderer determinism. */
class GraphSuite extends ScalaCheckSuite:

  property("discourse order is a permutation of situations, ordered by first span") {
    forAll { (b: Small.Built) =>
      val g = b.graph
      val ord = g.discourseOrder
      ord.toSet == g.situations.keySet && ord.size == g.situations.size &&
      ord.map(id => g.situations(id).support.refs.head.span.start) ==
        ord.map(id => g.situations(id).support.refs.head.span.start).sorted
    }
  }

  property("situationsByEntity and participantsOf are inverse views of the participant layer") {
    forAll { (b: Small.Built) =>
      val g = b.graph
      val fromEntity = g.situationsByEntity.toVector.flatMap((e, ss) => ss.map(s => (s, e))).toSet
      val fromSit = g.participantsOf.toVector.flatMap((s, ps) => ps.map((_, e) => (s, e))).toSet
      fromEntity == fromSit
    }
  }

  property("AlignmentSource matrices are consistent with the stored layers and sparse") {
    forAll { (b: Small.Built) =>
      val v = StoryValidator.validate(b.draft()).validated.get
      val src = AlignmentSource(v)
      val n = v.graph.situations.size
      val world = src.relationMatrix(RelationLayer.WorldTime)
      val succ = src.relationMatrix(RelationLayer.DiscourseSuccession)
      val cont = src.relationMatrix(RelationLayer.EntityContinuity)
      world.size == v.graph.relations.temporal.count(_.relation.isStrictPrecedence) &&
      succ.size == math.max(0, n - 1) &&
      cont.keys.forall((x, y) => x != y) &&
      cont.values.forall(w => w > 0.0 && w <= 1.0) &&
      src.hierarchyMembership.size == v.hierarchy.containment.size &&
      src.allNodes.size == n + v.graph.segments.size &&
      src.alignableNodes(Set(0)).size == n &&
      src.alignableNodes(Set(1, 2)).size == 2 &&
      src.allNodes.forall(node => src.sourceSupport(node).isDefined) &&
      src.allNodes.forall(node =>
        node == NarrativeNodeId.Segment(b.root) || src.parentOf(node).isDefined
      )
    }
  }

  property("derived trajectory has one step per adjacent pair with derived claims") {
    forAll { (b: Small.Built) =>
      val t = b.draft().trajectory
      val n = b.graph.situations.size
      t.steps.size == math.max(0, n - 1) &&
      t.steps.forall(_.worldTime.meta.status == EpistemicStatus.StructurallyDerived) &&
      t.steps.forall(s => s.entityTurnover.toOption.forall(v => v >= 0.0 && v <= 1.0)) &&
      t.steps.forall(s =>
        if b.graph.relations.temporal.exists(e => e.from == s.from && e.to == s.to) then
          s.worldTime.value == WorldTimeTransition.JumpForward(None)
        else s.worldTime.value == WorldTimeTransition.Unresolved(Vector.empty)
      )
    }
  }

  test("entity turnover is 1 − Jaccard of participant sets") {
    val b = Small.build(3, 3)
    // situation i has agent entity i % 3 → disjoint sets → turnover 1.0
    b.draft().trajectory.steps.foreach(s => assertEquals(s.entityTurnover, Estimate.observed(1.0)))
    val b2 = Small.build(3, 1)
    b2.draft().trajectory.steps.foreach(s => assertEquals(s.entityTurnover, Estimate.observed(0.0)))
  }

  property("renderer output is deterministic and lists every situation and entity") {
    forAll { (b: Small.Built) =>
      val m = b.draft()
      val t1 = Renderer.text(m)
      val t2 = Renderer.text(m)
      val d = Renderer.dot(m)
      t1 == t2 && d == Renderer.dot(m) &&
      b.graph.situations.keys.forall(id => t1.contains(id.value) && d.contains(id.value)) &&
      b.graph.entities.keys.forall(id => t1.contains(id.value))
    }
  }

  test("renderer is invariant to map insertion order") {
    val b = Small.build(5, 2)
    val g = b.graph
    val shuffled = g.copy(
      situations = g.situations.toVector.reverse.toMap,
      entities = g.entities.toVector.reverse.toMap
    )
    assertEquals(Renderer.text(b.draft(graph = shuffled)), Renderer.text(b.draft()))
  }

  test("hierarchy helpers") {
    val b = Small.build(3, 1)
    val h = b.hierarchy
    assertEquals(h.primaryRoot(b.graph.segments.keys), Some(b.root))
    assertEquals(h.ancestors(NarrativeMember.Situation(b.situations(0))), Vector(b.scene, b.root))
    assertEquals(h.situationsUnder(b.root).toSet, b.situations.toSet)
    assertEquals(
      h.descendantsAtLevel(b.root, 1, b.graph.segments),
      Vector(NarrativeMember.Segment(b.scene))
    )
    assertEquals(h.levelOf(NarrativeMember.Situation(b.situations(0)), b.graph.segments), 0)
  }

  test("context helpers") {
    val b = Small.build(1, 1)
    val speech = ContextId.unsafe("ctx:s")
    val inner = ContextId.unsafe("ctx:i")
    val sp = SpanSet.one(b.atlas.sentences(0).span)
    val m = Small.meta("x", EpistemicStatus.SurfaceExplicit, Some(sp))
    val g = b.graph.copy(contexts =
      b.graph.contexts ++ Map(
        speech -> ContextFrame(
          speech,
          Some(b.world),
          ContextKind.Speech(ContextHolder.Named(b.entities(0))),
          sp,
          m
        ),
        inner -> ContextFrame(
          inner,
          Some(speech),
          ContextKind.Belief(ContextHolder.Named(b.entities(0))),
          sp,
          m
        )
      )
    )
    assertEquals(g.contextAncestors(inner), Vector(speech, b.world))
    assert(g.contextWithin(inner, b.world))
    assert(!g.contextWithin(b.world, inner))
    assertEquals(g.contextRoots, Vector(b.world))
    assertEquals(g.contextChildren(b.world), Vector(speech))
  }

  test("adjudication is a pure status change") {
    val b = Small.build(2, 1)
    val v = StoryValidator.validate(b.draft()).validated.get
    val adj = StoryModel.adjudicated(v)
    assertEquals(adj.graph, v.graph)
    assertEquals(AlignmentSource.adjudicated(adj).allNodes, AlignmentSource(v).allNodes)
  }
