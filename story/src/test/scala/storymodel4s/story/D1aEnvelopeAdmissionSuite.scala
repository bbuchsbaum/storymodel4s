package storymodel4s.story

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}

/** Each refusal changes one joined input; the matching admitted input remains a control. */
class D1aEnvelopeAdmissionSuite extends FunSuite:
  private def right[A](value: Either[DomainError, A]): A =
    value.fold(e => fail(e.message), identity)
  private val bundle = right(
    SourceBundle.filmEdition(
      EditionId.unsafe("admission-film"),
      Checksum.ofText("picture"),
      0L,
      100L,
      RationalTimebase.Millisecond
    )
  )
  private val surface =
    SurfaceAnalyzer.analyze(right(StorySource.fromText("A caption. Another caption.")))
  private val bound = BoundProposalSurface.of(
    surface,
    right(SourceDerivationReceipt.of("caption", "fixture", Vector(Checksum.ofText("picture"))))
  )
  private val atlas = right(AnchoredNarrativeAtlas.of(bundle, Vector.empty, Some(bound)))
  private val world = ContextId.unsafe("world")
  private val a = SituationId.unsafe("a")
  private val b = SituationId.unsafe("b")
  private val root = SegmentId.unsafe("root")
  private val entityId = EntityId.unsafe("entity")
  private def support(parts: (Long, Long)*): TypedSupport =
    TypedSupport.Anchored(
      right(
        EvidenceSupport.media(
          bundle,
          bundle.streams.head.id,
          right(
            PlaybackIntervalSet.of(
              parts.toVector.map((start, end) =>
                right(PlaybackInterval.on(bundle.primaryAxis, start, end))
              )
            )
          )
        )
      )
    )
  private val anchored = support(1L -> 2L)
  private val evidence = Small
    .meta("evidence", EpistemicStatus.Hypothesized, None)
    .evidence
    .head
    .copy(anchors = anchored match
      case TypedSupport.Anchored(value) => Some(value)
      case _                            => fail("fixture support changed"))
  private def meta(key: String, ev: Evidence = evidence): ClaimMeta =
    right(
      Small
        .meta(key, EpistemicStatus.Hypothesized, None)
        .withEvidence(NonEmptyVector.one(ev.copy(id = EvidenceId.unsafe("e:" + key))))
    )
  private val event = EventNode(
    a,
    Predicate("happen", None, "happen"),
    "event",
    world,
    Polarity.Positive,
    Modality.Asserted,
    None,
    support(1L -> 2L),
    NonEmptyVector.one(MentionId.unsafe[SituationK]("event-mention")),
    meta("event")
  )
  private val state = StateNode(
    b,
    Predicate("remain", None, "remain"),
    "state",
    world,
    Polarity.Positive,
    Modality.Asserted,
    support(8L -> 9L),
    NonEmptyVector.one(MentionId.unsafe[SituationK]("state-mention")),
    meta("state")
  )
  private val entity = EntityNode(
    entityId,
    Resolved("person", meta("label"), Vector.empty),
    EntityType.Person,
    NonEmptyVector.one(MentionId.unsafe[EntityK]("entity-mention")),
    Vector.empty,
    anchored,
    meta("entity")
  )
  private val segment = SegmentNode(
    root,
    SegmentKind.Story,
    1,
    meta("segment"),
    SegmentSummary.Stated(Resolved("story", meta("summary"), Vector.empty)),
    support(0L -> 10L)
  )
  private val context =
    ContextFrame(world, None, ContextKind.NarratedWorld, anchored, meta("context"))
  private val circumstance =
    CircumstanceEdge(a, CircumstanceKind.Time, "time", anchored, meta("circumstance"))
  private val graph = NarrativeGraph(
    Map(entityId -> entity),
    Map(a -> SituationNode.Event(event), b -> SituationNode.State(state)),
    Map(root -> segment),
    Map(world -> context),
    RelationLayers.empty.copy(circumstances = Vector(circumstance))
  )
  private val hierarchy = NarrativeHierarchy(
    Vector(a, b).map(id =>
      ContainmentEdge(
        NarrativeMember.Situation(id),
        root,
        HierarchyKind.PrimarySegmentation,
        1.0,
        meta("containment:" + id.value)
      )
    ),
    Vector.empty
  )
  private val template = Small.build(2, 1).draft().trajectory.steps.head
  private val trajectory = DiscourseTrajectory(
    Vector(
      template.copy(
        from = a,
        to = b,
        worldTime = template.worldTime.copy(meta = meta("world-time")),
        worldTimeContext = Some(world),
        boundaryBeliefs = Vector.empty
      )
    )
  )
  private val descriptor =
    DescriptorClaim(root, DescriptorKind.Summary, "description", meta("descriptor"))
  private val hypothesis = HypothesisClaim(
    a,
    Resolved(
      "reading",
      meta("hypothesis"),
      Vector("rival" -> Credence.unsafeRaw(0.2, ScorerId.unsafe("test-scorer")))
    )
  )
  private case class Parts(
      g: NarrativeGraph = graph,
      h: NarrativeHierarchy = hierarchy,
      t: DiscourseTrajectory = trajectory,
      d: Vector[DescriptorClaim] = Vector(descriptor),
      y: Vector[HypothesisClaim] = Vector(hypothesis)
  ):
    def draft = StoryModel.draft(atlas, g, h, t, descriptors = d, hypotheses = y)
    def copied = base.copy[ModelStatus.Draft](
      graph = g,
      hierarchy = h,
      trajectory = t,
      descriptors = d,
      hypotheses = y
    )
  private lazy val base = right(Parts().draft)
  private def belief(ev: Evidence) =
    BoundaryBelief(surface.sentences.head.id, 1, 0.5, None, NonEmptyVector.one(ev))

  test("accepting control: fully anchored graph, hierarchy, trajectory and claims validate"):
    assertEquals(StoryValidator.validate(base).report.errors, Vector.empty)
    assert(StoryValidator.validate(base).validated.nonEmpty)
    assertEquals(Parts().copied, Right(base))

  private val evidenceSites: Vector[(String, Evidence => Parts)] = Vector(
    "graph" -> (ev =>
      Parts(g = graph.copy(contexts = Map(world -> context.copy(meta = meta("context", ev)))))
    ),
    "hierarchy" -> (ev =>
      Parts(h =
        hierarchy.copy(containment =
          hierarchy.containment
            .updated(0, hierarchy.containment.head.copy(meta = meta("containment:a", ev)))
        )
      )
    ),
    "trajectory" -> (ev =>
      Parts(t =
        trajectory.copy(steps =
          Vector(
            trajectory.steps.head
              .copy(worldTime = template.worldTime.copy(meta = meta("world-time", ev)))
          )
        )
      )
    ),
    "descriptor" -> (ev => Parts(d = Vector(descriptor.copy(meta = meta("descriptor", ev))))),
    "hypothesis" -> (ev =>
      Parts(y =
        Vector(hypothesis.copy(reading = hypothesis.reading.copy(meta = meta("hypothesis", ev))))
      )
    ),
    "hierarchy boundary" -> (ev => Parts(h = hierarchy.copy(boundaryBeliefs = Vector(belief(ev))))),
    "step boundary" -> (ev =>
      Parts(t =
        trajectory.copy(steps =
          Vector(trajectory.steps.head.copy(boundaryBeliefs = Vector(belief(ev))))
        )
      )
    )
  )
  private val foreignBundle = right(
    SourceBundle.filmEdition(
      bundle.edition.get,
      Checksum.ofText("picture"),
      0L,
      100L,
      right(RationalTimebase.of(1L, 100L))
    )
  )
  private val foreignSupport = right(
    EvidenceSupport.media(
      foreignBundle,
      foreignBundle.streams.head.id,
      PlaybackIntervalSet.one(right(PlaybackInterval.on(foreignBundle.primaryAxis, 1L, 2L)))
    )
  )
  private val spans = SpanSet.one(TextSpan.unsafe(0, 1))

  evidenceSites.foreach { (name, replace) =>
    test(s"$name evidence is checked on both draft and copy"):
      assert(replace(evidence).draft.isRight)
      assert(replace(evidence).copied.isRight)
      assertEquals(
        StoryValidator.validate(right(replace(evidence).draft)).report.errors,
        Vector.empty
      )
      Vector(
        evidence.copy(spans = Some(spans), anchors = None),
        evidence.copy(spans = Some(spans)),
        evidence.copy(anchors = Some(foreignSupport))
      ).foreach { wrong =>
        assert(replace(wrong).draft.isLeft)
        assert(replace(wrong).copied.isLeft)
      }
  }

  private val supportSites: Vector[(String, TypedSupport => NarrativeGraph)] = Vector(
    "entity" -> (s => graph.copy(entities = Map(entityId -> entity.copy(support = s)))),
    "event" -> (s =>
      graph.copy(situations =
        graph.situations.updated(a, SituationNode.Event(event.copy(support = s)))
      )
    ),
    "state" -> (s =>
      graph.copy(situations =
        graph.situations.updated(b, SituationNode.State(state.copy(support = s)))
      )
    ),
    "segment" -> (s => graph.copy(segments = Map(root -> segment.copy(support = s)))),
    "context" -> (s => graph.copy(contexts = Map(world -> context.copy(support = s)))),
    "circumstance" -> (s =>
      graph.copy(relations =
        graph.relations.copy(circumstances = Vector(circumstance.copy(support = s)))
      )
    )
  )
  supportSites.foreach { (name, replace) =>
    test(s"anchored $name support cannot be replaced with bare text"):
      assert(Parts(g = replace(anchored)).draft.isRight)
      assert(Parts(g = replace(anchored)).copied.isRight)
      assert(Parts(g = replace(TypedSupport.Text(spans))).draft.isLeft)
      assert(Parts(g = replace(TypedSupport.Text(spans))).copied.isLeft)
  }

  test("playback containment checks the interval union rather than its hull"):
    val enclosing = segment.copy(support = support(0L -> 3L, 7L -> 10L))
    def withChild(s: TypedSupport) = right(
      Parts(g =
        graph.copy(
          segments = Map(root -> enclosing),
          situations = graph.situations.updated(a, SituationNode.Event(event.copy(support = s)))
        )
      ).draft
    )
    val control = withChild(support(1L -> 2L, 8L -> 9L))
    assertEquals(StoryValidator.validate(control).report.errors, Vector.empty)
    Vector(support(2L -> 8L), support(4L -> 5L), support(1L -> 2L, 8L -> 11L)).foreach { wrong =>
      val errors = StoryValidator.validate(withChild(wrong)).report.errors
      assertEquals(errors.map(_.law), Vector("hierarchy.member-within-parent"))
    }

  test("bound surface unit ids are checked as surface ids without granting text access"):
    val admitted =
      right(Parts(h = hierarchy.copy(boundaryBeliefs = Vector(belief(evidence)))).draft)
    assertEquals(StoryModel.asText(admitted), None)
    assertEquals(StoryValidator.validate(admitted).report.errors, Vector.empty)
    val unknown = belief(evidence).copy(afterUnit = SurfaceUnitId.unsafe("missing-unit"))
    val refused = right(Parts(h = hierarchy.copy(boundaryBeliefs = Vector(unknown))).draft)
    assertEquals(
      StoryValidator.validate(refused).report.errors.map(_.law),
      Vector("boundary.unit-exists")
    )

  test("film SurfaceExplicit remains unavailable without the separate licensing decision"):
    val explicit = Small.meta("event", EpistemicStatus.SurfaceExplicit, Some(spans))
    // ClaimMeta still requires text spans; a joined film model cannot carry those bare spans.
    assert(explicit.withEvidence(NonEmptyVector.one(evidence)).isLeft)
    val g = graph.copy(situations =
      graph.situations.updated(a, SituationNode.Event(event.copy(meta = explicit)))
    )
    assert(Parts(g = g).draft.isLeft)
    assert(Parts(g = g).copied.isLeft)

  test("text receipt joins check id and checksum independently on draft and copy"):
    val built = Small.build(2, 1)
    val text = built.draft()
    val receipt =
      BuildReceipt(text.storyId, text.sourceChecksum, StoryModel.SchemaVersion, Vector.empty, 0L)
    def draft(r: BuildReceipt) = StoryModel.draftText(
      built.atlas,
      text.graph,
      text.hierarchy,
      text.trajectory,
      receipt = Some(r)
    )
    assert(draft(receipt).isRight)
    assert(text.copy[ModelStatus.Draft](receipt = Some(receipt)).isRight)
    Vector(
      receipt.copy(storyId = StoryId.unsafe("other")),
      receipt.copy(sourceChecksum = Checksum.ofText("other"))
    ).foreach { wrong =>
      assert(draft(wrong).isLeft)
      assert(text.copy[ModelStatus.Draft](receipt = Some(wrong)).isLeft)
    }

  test("text evidence refuses anchors that otherwise belong to its own bundle"):
    val built = Small.build(2, 1)
    val text = built.draft()
    val context = text.graph.contexts(built.world)
    val spans = context.support.textSpans.getOrElse(fail("text fixture support changed"))
    val local = right(EvidenceSupport.text(text.bundle, text.bundle.streams.head.id, spans))
    assert(EvidenceSupport.of(text.bundle, local.anchors.toVector).isRight)
    val ordinary = Small.meta("ctx:world", EpistemicStatus.Hypothesized, Some(spans))
    val control = text.graph.copy(contexts = Map(built.world -> context.copy(meta = ordinary)))
    assert(text.copy[ModelStatus.Draft](graph = control).isRight)
    Vector(None, Some(spans)).foreach { bare =>
      val changed = right(ordinary.withEvidence(ordinary.evidence.map(_.copy(spans = bare, anchors = Some(local)))))
      val graph = text.graph.copy(contexts = Map(built.world -> context.copy(meta = changed)))
      assert(StoryModel.draftText(built.atlas, graph, text.hierarchy, text.trajectory).isLeft)
      assert(text.copy[ModelStatus.Draft](graph = graph).isLeft)
    }

  test("text context and circumstance bounds remain validation laws with exact paths"):
    val built = Small.build(2, 1)
    val text = built.draft()
    val outside =
      TypedSupport.Text(SpanSet.one(TextSpan.unsafe(0, built.source.canonicalText.length + 1)))
    val ctx = built.graph.contexts(built.world)
    val edge = CircumstanceEdge(
      built.situations.head,
      CircumstanceKind.Time,
      "time",
      TypedSupport.Text(spans),
      Small.meta("new-circumstance", EpistemicStatus.Hypothesized, None)
    )
    val control = right(
      text.copy[ModelStatus.Draft](graph =
        built.graph.copy(relations = built.graph.relations.copy(circumstances = Vector(edge)))
      )
    )
    assertEquals(StoryValidator.validate(control).report.errors, Vector.empty)
    Vector(
      "contexts/" + built.world.value -> control.graph.copy(contexts =
        Map(built.world -> ctx.copy(support = outside))
      ),
      "circumstances/0" -> control.graph.copy(relations =
        control.graph.relations.copy(circumstances = Vector(edge.copy(support = outside)))
      )
    ).foreach { (path, changed) =>
      val model = right(control.copy[ModelStatus.Draft](graph = changed))
      assertEquals(
        StoryValidator.validate(model).report.errors.map(v => (v.law, v.path)),
        Vector("support.in-text" -> path)
      )
    }
