package storymodel4s.laws

import storymodel4s.story.*

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*
import storymodel4s.features.{FeatureRef, FeatureTarget}

/** A model with exactly one injected violation and the law expected to report it.
  *
  * `exact` means the injection cannot trip any other law; for the others the injected law must
  * still be reported, but consequential violations (e.g. a dangling endpoint after a key remap) are
  * allowed.
  */
final case class Mutant(law: String, exact: Boolean, draft: TextModel[ModelStatus.Draft])

/** Minimally-invalid story models: one mutation per validator law (design record §32.6). */
object InvalidStoryGens:
  import StorySmall.meta

  /** The fixed base every mutation starts from: 2 episodes × 2 scenes × 3 situations. */
  val baseShape: StoryShape =
    StoryShape(2, 2, 3, 2, true, true, true, true, true, true, false, true, true)

  private def world(b: StorySmall.Built) = b.world
  private def speech(b: StorySmall.Built) = b.speech.get
  private def missingSit = SituationId.unsafe("sit:missing")
  private def missingEnt = EntityId.unsafe("ent:missing")
  private def missingSeg = SegmentId.unsafe("seg:missing")
  private def missingCtx = ContextId.unsafe("ctx:missing")

  private def explicitMeta(key: String, b: StorySmall.Built) =
    meta(key, EpistemicStatus.SurfaceExplicit, Some(b.span(0)))
  private def inferredMeta(key: String) = meta(key, EpistemicStatus.Hypothesized, None)

  private def withRelations(b: StorySmall.Built)(f: RelationLayers => RelationLayers) =
    b.graph.copy(relations = f(b.graph.relations))

  private def temporal(
      b: StorySmall.Built,
      from: Int,
      rel: TemporalRelation,
      to: Int,
      ctx: ContextId
  ) =
    TemporalEdge(b.situations(from), rel, b.situations(to), ctx, inferredMeta(s"mut:t:$from:$to"))

  /** `(law, exact, mutation)`; a mutation returns the mutated draft. */
  val mutations: Vector[(String, Boolean, StorySmall.Built => TextModel[ModelStatus.Draft])] =
    Vector(
      (
        "ids.key-consistency",
        false,
        b =>
          val e0 = b.entities(0)
          b.draft(graph =
            b.graph.copy(entities =
              b.graph.entities.map((k, e) =>
                (if k == e0 then EntityId.unsafe("ent:wrong-key") else k) -> e
              )
            )
          )
      ),
      (
        "claims.unique-ids",
        true,
        b =>
          b.draft(graph =
            withRelations(b)(r => r.copy(participants = r.participants :+ r.participants.head))
          )
      ),
      (
        "claims.spans-in-text",
        true,
        b =>
          val len = b.source.canonicalText.length
          val bad = SpanSet.one(SpanRef(None, TextSpan.unsafe(len + 5, len + 10)))
          val p = b.graph.relations.participants.head
            .copy(meta = meta("mut:oob", EpistemicStatus.SurfaceExplicit, Some(bad)))
          b.draft(graph = withRelations(b)(r => r.copy(participants = p +: r.participants.tail)))
      ),
      (
        "support.in-text",
        true,
        b =>
          val len = b.source.canonicalText.length
          val e0 = b.entities(0)
          val ent = b.graph
            .entities(e0)
            .copy(support =
              TypedSupport.Text(SpanSet.one(SpanRef(None, TextSpan.unsafe(len + 1, len + 3))))
            )
          b.draft(graph = b.graph.copy(entities = b.graph.entities.updated(e0, ent)))
      ),
      (
        "entity.mentions-unique",
        true,
        b =>
          val e1 = b.entities(1)
          val ent = b.graph.entities(e1).copy(mentions = b.graph.entities(b.entities(0)).mentions)
          b.draft(graph = b.graph.copy(entities = b.graph.entities.updated(e1, ent)))
      ),
      (
        "situation.mentions-unique",
        true,
        b =>
          val s1 = b.situations(1)
          val shared = NonEmptyVector.one(
            MentionId.unsafe[storymodel4s.core.NarrativeKind.SituationK]("m:sit:0")
          )
          val node = b.graph.situations(s1) match
            case SituationNode.Event(e) => SituationNode.Event(e.copy(mentions = shared))
            case SituationNode.State(s) => SituationNode.State(s.copy(mentions = shared))
          b.draft(graph = b.graph.copy(situations = b.graph.situations.updated(s1, node)))
      ),
      (
        "endpoints.participant",
        true,
        b =>
          val p = ParticipantEdge(
            b.situations(0),
            ParticipantRole.Patient,
            missingEnt,
            inferredMeta("mut:p")
          )
          b.draft(graph = withRelations(b)(r => r.copy(participants = r.participants :+ p)))
      ),
      (
        "endpoints.temporal",
        true,
        b =>
          val t = TemporalEdge(
            b.situations(0),
            TemporalRelation.Before,
            missingSit,
            world(b),
            inferredMeta("mut:te")
          )
          b.draft(graph = withRelations(b)(r => r.copy(temporal = r.temporal :+ t)))
      ),
      (
        "temporal.no-self",
        false,
        b =>
          b.draft(graph =
            withRelations(b)(r =>
              r.copy(temporal = r.temporal :+ temporal(b, 0, TemporalRelation.Before, 0, world(b)))
            )
          )
      ),
      (
        "temporal.canonical-relation",
        false,
        b =>
          b.draft(graph =
            withRelations(b)(r =>
              r.copy(temporal = r.temporal :+ temporal(b, 0, TemporalRelation.After, 1, world(b)))
            )
          )
      ),
      (
        "temporal.context-scope",
        true,
        b =>
          // situation 2 lives in the speech context; a world-scoped edge touching it is out of scope
          b.draft(graph =
            withRelations(b)(r =>
              r.copy(temporal = r.temporal :+ temporal(b, 2, TemporalRelation.During, 0, world(b)))
            )
          )
      ),
      (
        "causal.no-self",
        true,
        b =>
          val c = CausalEdge(
            b.situations(0),
            CausalRelation.Causes,
            b.situations(0),
            inferredMeta("mut:c")
          )
          b.draft(graph = withRelations(b)(r => r.copy(causal = r.causal :+ c)))
      ),
      (
        "endpoints.causal",
        true,
        b =>
          val c =
            CausalEdge(b.situations(0), CausalRelation.Causes, missingSit, inferredMeta("mut:c2"))
          b.draft(graph = withRelations(b)(r => r.copy(causal = r.causal :+ c)))
      ),
      (
        "statechange.target-is-state",
        true,
        b =>
          val sc = StateChangeEdge(
            b.situations(0),
            StateChangeKind.Initiates,
            b.situations(1),
            inferredMeta("mut:sc")
          )
          b.draft(graph = withRelations(b)(r => r.copy(stateChanges = r.stateChanges :+ sc)))
      ),
      (
        "reference.no-self",
        true,
        b =>
          val e = ReferenceEdge(
            b.situations(0),
            NarrativeReference.Anaphoric,
            b.situations(0),
            inferredMeta("mut:ref")
          )
          b.draft(graph = withRelations(b)(r => r.copy(references = r.references :+ e)))
      ),
      (
        "endpoints.entity-attribute-context",
        true,
        b =>
          val e0 = b.entities(0)
          val ent = b.graph.entities(e0)
          val attr = ScopedAttribute(missingCtx, "k", "v", inferredMeta("mut:attr"))
          b.draft(graph =
            b.graph.copy(entities =
              b.graph.entities.updated(e0, ent.copy(attributes = ent.attributes :+ attr))
            )
          )
      ),
      (
        "descriptor.target-exists",
        true,
        b =>
          b.draft(descriptors =
            Vector(
              DescriptorClaim(missingSeg, DescriptorKind.Summary, "x", inferredMeta("mut:desc"))
            )
          )
      ),
      (
        "situation.context-exists",
        false,
        b =>
          val s0 = b.situations(0)
          val node = b.graph.situations(s0) match
            case SituationNode.Event(e) => SituationNode.Event(e.copy(context = missingCtx))
            case SituationNode.State(s) => SituationNode.State(s.copy(context = missingCtx))
          b.draft(graph = b.graph.copy(situations = b.graph.situations.updated(s0, node)))
      ),
      (
        // a dangling parent detaches the speech context from the world, so its scoped edges
        // also fall out of scope
        "context.parent-exists",
        false,
        b =>
          val c = b.graph.contexts(speech(b)).copy(parent = Some(missingCtx))
          b.draft(graph = b.graph.copy(contexts = b.graph.contexts.updated(speech(b), c)))
      ),
      (
        "context.holder-exists",
        true,
        b =>
          val c = b.graph
            .contexts(speech(b))
            .copy(kind = ContextKind.Speech(ContextHolder.Named(missingEnt)))
          b.draft(graph = b.graph.copy(contexts = b.graph.contexts.updated(speech(b), c)))
      ),
      (
        "context.root-is-narrated-world",
        true,
        b =>
          val c = b.graph.contexts(world(b)).copy(kind = ContextKind.Hypothetical)
          b.draft(graph = b.graph.copy(contexts = b.graph.contexts.updated(world(b), c)))
      ),
      (
        "context.narrated-world-is-root",
        true,
        b =>
          val c = b.graph.contexts(speech(b)).copy(kind = ContextKind.NarratedWorld)
          b.draft(graph = b.graph.copy(contexts = b.graph.contexts.updated(speech(b), c)))
      ),
      (
        "context.single-root",
        true,
        b =>
          val id = ContextId.unsafe("ctx:second-root")
          val c = ContextFrame(
            id,
            None,
            ContextKind.NarratedWorld,
            b.span(0),
            explicitMeta("mut:ctx2", b)
          )
          b.draft(graph = b.graph.copy(contexts = b.graph.contexts.updated(id, c)))
      ),
      (
        "context.acyclic",
        false,
        b =>
          val w = b.graph.contexts(world(b)).copy(parent = Some(speech(b)))
          b.draft(graph = b.graph.copy(contexts = b.graph.contexts.updated(world(b), w)))
      ),
      (
        "endpoints.containment",
        true,
        b =>
          val e = ContainmentEdge(
            NarrativeMember.Situation(b.situations(0)),
            missingSeg,
            HierarchyKind.GoalArc,
            0.5,
            inferredMeta("mut:cont")
          )
          b.draft(hierarchy = b.hierarchy.copy(containment = b.hierarchy.containment :+ e))
      ),
      (
        "containment.weight-in-unit",
        true,
        b =>
          val e = ContainmentEdge(
            NarrativeMember.Situation(b.situations(0)),
            b.episodes(1),
            HierarchyKind.GoalArc,
            1.5,
            inferredMeta("mut:w")
          )
          b.draft(hierarchy = b.hierarchy.copy(containment = b.hierarchy.containment :+ e))
      ),
      (
        "containment.acyclic",
        true,
        b =>
          val e = ContainmentEdge(
            NarrativeMember.Segment(b.scenes(0)),
            b.scenes(0),
            HierarchyKind.GoalArc,
            0.5,
            inferredMeta("mut:self")
          )
          b.draft(hierarchy = b.hierarchy.copy(containment = b.hierarchy.containment :+ e))
      ),
      (
        "hierarchy.single-primary-parent",
        false, // consequential laws also fire (post story fix pass)
        b =>
          val e = ContainmentEdge(
            NarrativeMember.Situation(b.situations(0)),
            b.scenes(1),
            HierarchyKind.PrimarySegmentation,
            1.0,
            inferredMeta("mut:pp")
          )
          b.draft(hierarchy = b.hierarchy.copy(containment = b.hierarchy.containment :+ e))
      ),
      (
        "hierarchy.single-primary-root",
        true,
        b =>
          val cut = b.hierarchy.containment.filterNot(e =>
            e.isPrimary && e.member == NarrativeMember.Segment(b.episodes(1))
          )
          b.draft(hierarchy = b.hierarchy.copy(containment = cut))
      ),
      (
        "hierarchy.situation-root-reachable",
        true,
        b =>
          val cut = b.hierarchy.containment.filterNot(e =>
            e.isPrimary && e.member == NarrativeMember.Situation(b.situations(1))
          )
          b.draft(hierarchy = b.hierarchy.copy(containment = cut))
      ),
      (
        "hierarchy.no-empty-primary-segment",
        true,
        b =>
          val id = SegmentId.unsafe("seg:empty")
          val seg = SegmentNode(
            id,
            SegmentKind.Scene,
            1,
            inferredMeta("mut:empty:claim"),
            SegmentSummary.Stated(Resolved("empty", inferredMeta("mut:empty"), Vector.empty)),
            b.span(0)
          )
          val e = ContainmentEdge(
            NarrativeMember.Segment(id),
            b.episodes(0),
            HierarchyKind.PrimarySegmentation,
            1.0,
            inferredMeta("mut:empty-cont")
          )
          b.draft(
            graph = b.graph.copy(segments = b.graph.segments.updated(id, seg)),
            hierarchy = b.hierarchy.copy(containment = b.hierarchy.containment :+ e)
          )
      ),
      (
        "hierarchy.level-consistent",
        true,
        b =>
          val s0 = b.scenes(0)
          b.draft(graph =
            b.graph
              .copy(segments = b.graph.segments.updated(s0, b.graph.segments(s0).copy(level = 0)))
          )
      ),
      (
        "boundary.unit-exists",
        true,
        b =>
          val bb =
            b.hierarchy.boundaryBeliefs.head.copy(afterUnit = SurfaceUnitId.unsafe("unit:missing"))
          b.draft(hierarchy =
            b.hierarchy.copy(boundaryBeliefs = bb +: b.hierarchy.boundaryBeliefs.tail)
          )
      ),
      (
        "boundary.level",
        true,
        b =>
          val bb = b.hierarchy.boundaryBeliefs.head.copy(level = 0)
          b.draft(hierarchy =
            b.hierarchy.copy(boundaryBeliefs = bb +: b.hierarchy.boundaryBeliefs.tail)
          )
      ),
      (
        "temporal.strict-acyclic",
        false, // consequential laws also fire (post story fix pass)
        b =>
          b.draft(graph =
            withRelations(b)(r =>
              r.copy(temporal = r.temporal :+ temporal(b, 1, TemporalRelation.Before, 0, world(b)))
            )
          )
      ),
      (
        "temporal.containment-acyclic",
        false, // consequential laws also fire (post story fix pass)
        b =>
          b.draft(graph =
            withRelations(b)(r =>
              r.copy(temporal =
                r.temporal :+ temporal(b, 0, TemporalRelation.During, 1, world(b)) :+ temporal(
                  b,
                  1,
                  TemporalRelation.During,
                  0,
                  world(b)
                )
              )
            )
          )
      ),
      // Equal merges the endpoints for the strict check, so the chain edge becomes a self-cycle too
      (
        "temporal.equal-consistent",
        false,
        b =>
          b.draft(graph =
            withRelations(b)(r =>
              r.copy(temporal = r.temporal :+ temporal(b, 0, TemporalRelation.Equal, 1, world(b)))
            )
          )
      ),
      (
        "feature.space-exists",
        true,
        b =>
          val ref = FeatureRef.unsafe(
            FeatureTarget.Situation(b.situations(0)),
            FeatureSpaceId.unsafe("space:missing"),
            0
          )
          b.draft(featureRefs = b.featureRefs :+ ref)
      ),
      (
        "feature.row-in-range",
        true,
        b =>
          val head = b.featureRefs.head
          b.draft(featureRefs =
            b.featureRefs :+ FeatureRef.unsafe(head.target, head.space, b.situations.size)
          )
      ),
      (
        "feature.target-exists",
        true,
        b =>
          val head = b.featureRefs.head
          b.draft(featureRefs =
            b.featureRefs :+ FeatureRef.unsafe(
              FeatureTarget.Situation(missingSit),
              head.space,
              head.row
            )
          )
      ),
      (
        "feature.sidecar-space",
        true,
        b =>
          val (id, m) = b.sidecars.head
          b.draft(sidecars =
            Map(
              id -> SidecarManifest.unsafe(
                FeatureSpaceId.unsafe("space:other"),
                m.dimension,
                m.rowCount,
                m.dtype,
                m.checksum,
                m.layout
              )
            )
          )
      ),
      (
        "sensory.target-exists",
        true,
        b => b.draft(sensoryProfiles = Map(missingSit -> Vector.empty))
      ),
      (
        "causal.cross-context-explicit",
        false, // consequential laws also fire (post story fix pass)
        b =>
          // situation 2 is speech-scoped; an explicit causal edge from the world into it is a warning
          val c = CausalEdge(
            b.situations(0),
            CausalRelation.Causes,
            b.situations(2),
            explicitMeta("mut:xctx", b)
          )
          b.draft(graph = withRelations(b)(r => r.copy(causal = r.causal :+ c)))
      )
    )

  val laws: Vector[String] = mutations.map(_._1)

  val base: StorySmall.Built = StorySmall.build(baseShape)

  val mutant: Gen[Mutant] =
    Gen.oneOf(mutations).map((law, exact, f) => Mutant(law, exact, f(base)))

  given Arbitrary[Mutant] = Arbitrary(mutant)
