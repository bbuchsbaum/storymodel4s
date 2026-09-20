package storymodel4s.story

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.SituationK

class D1aAlignmentSourceSuite extends FunSuite:
  private def right[A](value: Either[DomainError, A]): A =
    value.fold(e => fail(e.message), identity)
  private val built = Small.build(3, 1)
  private val text = StoryModel.asText(StoryValidator.validate(built.draft()).validated.get).get
  private val general = AlignmentSource(text.model)

  test("accepting control: validated and adjudicated sources preserve ordered nodes and relations"):
    val sources = Vector(
      general,
      AlignmentSource(text),
      AlignmentSource.adjudicated(StoryModel.adjudicated(text.model)),
      AlignmentSource.adjudicated(StoryModel.adjudicated(text))
    )
    val expected =
      built.situations.map(NarrativeNodeId.Situation(_)) ++ Vector(built.scene, built.root).map(
        NarrativeNodeId.Segment(_)
      )
    sources.foreach { source =>
      assertEquals(source.allNodes, expected)
      RelationLayer.values.foreach(layer =>
        assertEquals(source.relationMatrix(layer), general.relationMatrix(layer))
      )
      assertEquals(source.hierarchyMembership, general.hierarchyMembership)
    }

  test("general text source returns complete typed evidence and primary projection"):
    val nodes = built.situations.map(id =>
      NarrativeNodeId.Situation(id) -> built.graph.situations(id).support
    ) ++
      Vector(built.scene, built.root).map(id =>
        NarrativeNodeId.Segment(id) -> built.graph.segments(id).support
      )
    nodes.foreach { (node, support) =>
      assertEquals(general.evidenceOf(node), Some(support))
      val spans = support.textSpans.get
      assertEquals(
        general.primaryOf(node),
        Some(PrimaryProjection.TextSpans(text.bundle.primaryAxis.id, spans))
      )
    }

  test("text source keeps its own witness and exact span references"):
    Vector(AlignmentSource(text), AlignmentSource.adjudicated(StoryModel.adjudicated(text)))
      .foreach { source =>
        assert(source.text eq text.text)
        assertEquals(source.text.source, built.source)
        assertEquals(source.text.surface, built.atlas)
        source.allNodes.foreach { node =>
          assertEquals(source.sourceSupport(node), source.evidenceOf(node).flatMap(_.textSpans))
          assert(source.sourceSupport(node).get.refs.exists(_.unit.nonEmpty))
        }
      }

  test("missing situation and segment have no evidence projection or text support"):
    val missing = Vector(
      NarrativeNodeId.Situation(SituationId.unsafe("missing")),
      NarrativeNodeId.Segment(SegmentId.unsafe("missing"))
    )
    val source = AlignmentSource(text)
    missing.foreach { node =>
      assertEquals(source.evidenceOf(node), None)
      assertEquals(source.primaryOf(node), None)
      assertEquals(source.sourceSupport(node), None)
    }

  test("general film source preserves native evidence and the complete gapped primary union"):
    val (bundle, otherPrimary) = D1aBundleFixtures.twoAxes()
    val primary = right(
      PlaybackIntervalSet.of(
        Vector(
          right(PlaybackInterval.on(bundle.primaryAxis, 1L, 3L)),
          right(PlaybackInterval.on(bundle.primaryAxis, 7L, 9L))
        )
      )
    )
    val native =
      PlaybackIntervalSet.one(right(PlaybackInterval.on(otherPrimary.primaryAxis, 20L, 21L)))
    val a = right(EvidenceSupport.media(bundle, bundle.streams.head.id, primary))
    val b = right(EvidenceSupport.media(bundle, bundle.streams(1).id, native))
    val evidence = right(EvidenceSupport.of(bundle, a.anchors.toVector ++ b.anchors.toVector))
    val support = TypedSupport.Anchored(evidence)
    def meta(key: String) = Small.meta(key, EpistemicStatus.Hypothesized, None)
    val world = ContextId.unsafe("film-world")
    val id = SituationId.unsafe("film-event")
    val root = SegmentId.unsafe("film-root")
    val event = EventNode(
      id,
      Predicate("happen", None, "happen"),
      "event",
      world,
      Polarity.Positive,
      Modality.Asserted,
      None,
      support,
      NonEmptyVector.one(MentionId.unsafe[SituationK]("film-mention")),
      meta("event")
    )
    val segment = SegmentNode(
      root,
      SegmentKind.Story,
      1,
      meta("segment"),
      SegmentSummary.Stated(Resolved("story", meta("summary"), Vector.empty)),
      support
    )
    val graph = NarrativeGraph(
      Map.empty,
      Map(id -> SituationNode.Event(event)),
      Map(root -> segment),
      Map(world -> ContextFrame(world, None, ContextKind.NarratedWorld, support, meta("world"))),
      RelationLayers.empty
    )
    val hierarchy = NarrativeHierarchy(
      Vector(
        ContainmentEdge(
          NarrativeMember.Situation(id),
          root,
          HierarchyKind.PrimarySegmentation,
          1.0,
          meta("containment")
        )
      ),
      Vector.empty
    )
    val draft = right(
      StoryModel.draft(
        right(AnchoredNarrativeAtlas.of(bundle, Vector.empty)),
        graph,
        hierarchy,
        DiscourseTrajectory.empty
      )
    )
    val outcome = StoryValidator.validate(draft)
    assertEquals(outcome.report.violations, Vector.empty)
    val model = outcome.validated.get
    Vector(AlignmentSource(model), AlignmentSource.adjudicated(StoryModel.adjudicated(model)))
      .foreach { source =>
        Vector(NarrativeNodeId.Situation(id), NarrativeNodeId.Segment(root)).foreach { node =>
          assertEquals(source.evidenceOf(node), Some(support))
          assertEquals(
            source.primaryOf(node),
            Some(PrimaryProjection.Playback(bundle.primaryAxis.id, primary))
          )
        }
      }
