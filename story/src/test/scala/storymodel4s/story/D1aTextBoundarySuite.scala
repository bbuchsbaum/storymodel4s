package storymodel4s.story

import munit.FunSuite
import storymodel4s.core.*

/** Joined text admission, including evidence outside the ordinary claim ledger. */
class D1aTextBoundarySuite extends FunSuite:
  private val built = Small.build(2, 1)
  private val ordinary = built.draft()
  private val film = SourceBundle.filmEdition(EditionId.unsafe("boundary-film"),
    Checksum.ofText("film"), 0L, 100L, RationalTimebase.Millisecond).toOption.get
  private val anchors = EvidenceSupport.media(film, film.streams.head.id,
    PlaybackIntervalSet.one(PlaybackInterval.on(film.primaryAxis, 1L, 2L).toOption.get)).toOption.get
  private val anchored = TypedSupport.Anchored(anchors)
  private val context = built.graph.contexts(built.world)
  private val textEvidence = context.meta.evidence
  private val anchoredEvidence = textEvidence.map(_.copy(anchors = Some(anchors)))
  private def draft(graph: NarrativeGraph = built.graph,
      hierarchy: NarrativeHierarchy = built.hierarchy,
      trajectory: DiscourseTrajectory = ordinary.trajectory) =
    StoryModel.draft(built.source, built.atlas, graph, hierarchy, trajectory)
  private def belief(evidence: cats.data.NonEmptyVector[Evidence]) =
    BoundaryBelief(built.atlas.sentences.head.id, 0, 0.5, None, evidence)

  test("accepting control: ordinary text draft and internal copy remain constructible"):
    assertEquals(draft(), Right(ordinary))
    assertEquals(ordinary.copy[ModelStatus.Draft](), Right(ordinary))
    assert(StoryValidator.validate(ordinary).validated.nonEmpty)

  test("text draft and copy refuse anchored ordinary claim evidence before export"):
    val changed = context.copy(meta = context.meta.withEvidence(anchoredEvidence).toOption.get)
    val graph = built.graph.copy(contexts = built.graph.contexts.updated(built.world, changed))
    assert(draft(graph = graph).isLeft)
    assert(ordinary.copy(graph = graph).isLeft)

  test("text draft and copy refuse anchored hierarchy boundary evidence"):
    val hierarchy = built.hierarchy.copy(boundaryBeliefs = Vector(belief(anchoredEvidence)))
    assert(draft(hierarchy = hierarchy).isLeft)
    assert(ordinary.copy(hierarchy = hierarchy).isLeft)

  test("accepting control: text boundary evidence may live only on a trajectory step"):
    val step = ordinary.trajectory.steps.head.copy(boundaryBeliefs = Vector(belief(textEvidence)))
    val trajectory = ordinary.trajectory.copy(steps = Vector(step))
    assert(draft(trajectory = trajectory).isRight)
    assert(ordinary.copy(trajectory = trajectory).isRight)

  test("text draft and copy refuse step-only anchored and twin boundary evidence"):
    Vector(anchoredEvidence, anchoredEvidence.map(_.copy(spans = None))).foreach { evidence =>
      val step = ordinary.trajectory.steps.head.copy(boundaryBeliefs = Vector(belief(evidence)))
      val trajectory = ordinary.trajectory.copy(steps = Vector(step))
      assertEquals(built.hierarchy.boundaryBeliefs, Vector.empty)
      assert(draft(trajectory = trajectory).isLeft)
      assert(ordinary.copy(trajectory = trajectory).isLeft)
    }

  private val supportChanges: Vector[(String, NarrativeGraph)] =
    val g = built.graph
    val entity = g.entities.values.head
    val event = g.situations.values.collectFirst { case SituationNode.Event(n) => n }.get
    val state = StateNode(event.id, event.predicate, event.description, event.context, event.polarity, event.modality, event.support, event.mentions, event.meta)
    val segment = g.segments.values.head
    Vector(
      "entity" -> g.copy(entities = g.entities.updated(entity.id, entity.copy(support = anchored))),
      "event" -> g.copy(situations = g.situations.updated(event.id, SituationNode.Event(event.copy(support = anchored)))),
      "state" -> g.copy(situations = g.situations.updated(state.id, SituationNode.State(state.copy(support = anchored)))),
      "segment" -> g.copy(segments = g.segments.updated(segment.id, segment.copy(support = anchored))),
      "context" -> g.copy(contexts = g.contexts.updated(context.id, context.copy(support = anchored))),
      "circumstance" -> g.copy(relations = g.relations.copy(circumstances = Vector(
        CircumstanceEdge(event.id, CircumstanceKind.Time, "time", anchored, event.meta)
      )))
    )

  supportChanges.foreach { (kind, graph) =>
    test(s"text draft and copy refuse anchored $kind support"):
      assert(draft(graph = graph).isLeft)
      assert(ordinary.copy(graph = graph).isLeft)
  }
