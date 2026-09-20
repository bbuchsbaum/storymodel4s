package storymodel4s.story

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.SituationK

/** Joined envelope courts use literal coordinates and deliberately distinct source identities. */
class D1aEnvelopeSuite extends FunSuite:
  private def right[A](value: Either[DomainError, A]): A =
    value.fold(e => fail(e.message), identity)
  private val world = ContextId.unsafe("film-world")
  private def film(
      end: Long = 100L,
      timebase: RationalTimebase = RationalTimebase.Millisecond
  ): SourceBundle =
    right(
      SourceBundle.filmEdition(
        EditionId.unsafe("envelope-film"),
        Checksum.ofText("picture"),
        0L,
        end,
        timebase
      )
    )
  private def anchors(bundle: SourceBundle, start: Long = 1L, end: Long = 3L): EvidenceSupport =
    right(
      EvidenceSupport.media(
        bundle,
        bundle.streams.head.id,
        PlaybackIntervalSet.one(right(PlaybackInterval.on(bundle.primaryAxis, start, end)))
      )
    )
  private def meta(key: String): ClaimMeta = Small.meta(key, EpistemicStatus.Hypothesized, None)
  private def graph(bundle: SourceBundle): NarrativeGraph =
    NarrativeGraph(
      Map.empty,
      Map.empty,
      Map.empty,
      Map(
        world -> ContextFrame(
          world,
          None,
          ContextKind.NarratedWorld,
          TypedSupport.Anchored(anchors(bundle)),
          meta("world")
        )
      ),
      RelationLayers.empty
    )
  private def atlas(
      bundle: SourceBundle,
      surface: Option[BoundProposalSurface] = None
  ): AnchoredNarrativeAtlas =
    right(AnchoredNarrativeAtlas.of(bundle, Vector.empty, surface))
  private def draft(
      bundle: SourceBundle,
      surface: Option[BoundProposalSurface] = None
  ): StoryModel[ModelStatus.Draft] =
    right(
      StoryModel.draft(
        atlas(bundle, surface),
        graph(bundle),
        NarrativeHierarchy.empty,
        DiscourseTrajectory.empty
      )
    )
  private def event(
      bundle: SourceBundle,
      key: String,
      start: Long,
      end: Long,
      modality: Modality = Modality.Asserted
  ): SituationNode =
    SituationNode.Event(
      EventNode(
        SituationId.unsafe(key),
        Predicate("happen", None, "happen"),
        key,
        world,
        Polarity.Positive,
        modality,
        None,
        TypedSupport.Anchored(anchors(bundle, start, end)),
        NonEmptyVector.one(MentionId.unsafe[SituationK]("mention:" + key)),
        meta(key)
      )
    )

  test("accepting control: anchored draft validates and has no canonical text witness"):
    val model = draft(film())
    assertEquals(model.bundle, model.atlas.bundle)
    assertEquals(StoryModel.asText(model), None)
    assertEquals(StoryValidator.validate(model).report.violations, Vector.empty)
    assert(StoryValidator.validate(model).validated.nonEmpty)

  test("text identity and witness survive validation and adjudication without replacement"):
    val source =
      right(StorySource.fromText("Alpha.", explicitId = Some(StoryId.unsafe("explicit-text-id"))))
    val surface = SurfaceAnalyzer.analyze(source)
    val context = ContextFrame(
      world,
      None,
      ContextKind.NarratedWorld,
      SpanSet.one(TextSpan.unsafe(0, 5)),
      meta("text-world")
    )
    val g =
      NarrativeGraph(Map.empty, Map.empty, Map.empty, Map(world -> context), RelationLayers.empty)
    val model =
      right(StoryModel.draftText(surface, g, NarrativeHierarchy.empty, DiscourseTrajectory.empty))
    assertEquals(model.storyId, source.id)
    assertEquals(model.sourceChecksum, source.canonicalChecksum)
    assertEquals(model.source, surface.source)
    assertEquals(StoryModel.asText(model.model), Some(model))
    assertEquals(StoryModel.asText(model.model).get.hashCode, model.hashCode)
    val validated =
      StoryValidator.validate(model).validated.getOrElse(fail("text promotion refused"))
    val adjudicated = StoryModel.adjudicated(validated)
    assert(validated.text eq model.text)
    assert(adjudicated.text eq model.text)
    assert(validated.model == model.model)
    assert(adjudicated.model == model.model)

  test("non-text identity binds full axis extent and timebase despite equal legacy bundle ids"):
    val base = film()
    val variants = Vector(film(200L), film(timebase = right(RationalTimebase.of(1L, 100L))))
    variants.foreach { changed =>
      assertEquals(changed.id, base.id)
      assertNotEquals(draft(changed).storyId, draft(base).storyId)
      assertNotEquals(draft(changed).sourceChecksum, draft(base).sourceChecksum)
    }
    val parts = Vector("story-anchored", base.identity.hex, "surface:none")
    assertEquals(draft(base).storyId.value, ContentAddress.of(parts.head, parts.tail*))
    assertEquals(draft(base).sourceChecksum, ContentAddress.digest(parts))
    assertEquals(draft(base).sourceChecksum.hex.length, 64)

  test("non-text identity binds bound surface content, units and receipt association"):
    def bound(text: String, algorithm: String): BoundProposalSurface =
      BoundProposalSurface.of(
        SurfaceAnalyzer.analyze(right(StorySource.fromText(text))),
        right(SourceDerivationReceipt.of(algorithm, "parameters", Vector(Checksum.ofText("input"))))
      )
    val bundle = film()
    val first = bound("First caption.", "captioner")
    val token = first.surface.tokens.head
    val changedUnits = right(
      SurfaceAtlas.of(
        first.surface.source,
        first.surface.units.map(u =>
          if u.id == token.id then u.copy(id = SurfaceUnitId.unsafe("renamed-token")) else u
        )
      )
    )
    val structuralVariant = BoundProposalSurface.of(changedUnits, first.receipt)
    assertEquals(structuralVariant.surface.source, first.surface.source)
    val variants = Vector(
      None,
      Some(bound("Other caption.", "captioner")),
      Some(bound("First caption.", "other-captioner")),
      Some(structuralVariant)
    )
    variants.foreach { surface =>
      assertNotEquals(draft(bundle, surface).storyId, draft(bundle, Some(first)).storyId)
      assertNotEquals(
        draft(bundle, surface).sourceChecksum,
        draft(bundle, Some(first)).sourceChecksum
      )
    }

  test("receipt joins check story id and full source checksum for draft and copy"):
    val base = draft(film())
    val receipt =
      BuildReceipt(base.storyId, base.sourceChecksum, StoryModel.SchemaVersion, Vector.empty, 0L)
    def remake(r: BuildReceipt) =
      StoryModel.draft(base.atlas, base.graph, base.hierarchy, base.trajectory, receipt = Some(r))
    assert(remake(receipt).isRight)
    assert(base.copy[ModelStatus.Draft](receipt = Some(receipt)).isRight)
    Vector(
      receipt.copy(storyId = StoryId.unsafe("wrong-story")),
      receipt.copy(sourceChecksum = Checksum.ofText("wrong-source"))
    ).foreach { wrong =>
      assert(remake(wrong).isLeft)
      assert(base.copy[ModelStatus.Draft](receipt = Some(wrong)).isLeft)
    }

  test("anchored draft and copy refuse bare or twin claim evidence"):
    val bundle = film()
    val base = draft(bundle)
    val context = base.graph.contexts(world)
    val original = context.meta.evidence.head
    val good = original.copy(anchors = Some(anchors(bundle)))
    def changed(evidence: Evidence): NarrativeGraph = base.graph.copy(contexts =
      Map(
        world ->
          context.copy(meta = right(context.meta.withEvidence(NonEmptyVector.one(evidence))))
      )
    )
    assert(base.copy[ModelStatus.Draft](graph = changed(good)).isRight)
    Vector(
      original.copy(spans = Some(SpanSet.one(TextSpan.unsafe(0, 1)))),
      good.copy(spans = Some(SpanSet.one(TextSpan.unsafe(0, 1))))
    ).foreach { wrong =>
      val g = changed(wrong)
      assert(StoryModel.draft(base.atlas, g, base.hierarchy, base.trajectory).isLeft)
      assert(base.copy[ModelStatus.Draft](graph = g).isLeft)
    }

  test("anchored evidence is rebound to the resulting bundle even when legacy ids agree"):
    val bundle = film()
    val base = draft(bundle)
    val original = base.graph.contexts(world)
    val foreign = anchors(film(timebase = right(RationalTimebase.of(1L, 100L))))
    assertEquals(foreign.anchors.head.anchorBundle, bundle.id)
    val evidence = original.meta.evidence.map(_.copy(anchors = Some(foreign)))
    val changed = base.graph.copy(contexts =
      Map(world -> original.copy(meta = right(original.meta.withEvidence(evidence))))
    )
    assert(base.copy[ModelStatus.Draft](graph = changed).isLeft)

  test("generic anchored validation retains root Reported and retrospective duplicate rules"):
    val bundle = film()
    val base = draft(bundle)
    val a = event(bundle, "a", 1L, 3L)
    val b = event(bundle, "b", 4L, 6L, Modality.Reported)
    val g = base.graph.copy(situations = Map(a.id -> a, b.id -> b))
    val reported = right(base.copy[ModelStatus.Draft](graph = g))
    assert(
      StoryValidator
        .validate(reported)
        .report
        .errors
        .exists(_.law == "reported-content-not-root-without-root-claim")
    )
    val asserted = event(bundle, "b", 4L, 6L)
    val ref = ReferenceEdge(a.id, NarrativeReference.Retrospective, asserted.id, meta("reference"))
    val repeated = right(
      base.copy[ModelStatus.Draft](graph =
        g.copy(
          situations = Map(a.id -> a, asserted.id -> asserted),
          relations = RelationLayers.empty.copy(references = Vector(ref))
        )
      )
    )
    assert(
      StoryValidator
        .validate(repeated)
        .report
        .warnings
        .exists(_.law == "no-duplicate-occurrence-via-retrospective-reference")
    )
    assert(
      !StoryValidator
        .validate(repeated)
        .report
        .violations
        .exists(_.law == "explicit-causal-requires-span-with-causal-cue")
    )

  private def twoAxes(): (SourceBundle, SourceBundle) = D1aBundleFixtures.twoAxes()

  private def mediaAt(
      bundle: SourceBundle,
      stream: SourceStream,
      axis: PresentationAxis,
      start: Long,
      end: Long
  ): EvidenceAnchor =
    EvidenceAnchor.MediaTime(
      bundle.id,
      stream.id,
      axis.id,
      PlaybackIntervalSet.one(right(PlaybackInterval.on(axis, start, end)))
    )

  test(
    "explicit bundle rebind recomputes primary selection and reverses order while retaining native evidence"
  ):
    val (a, b) = twoAxes()
    assertEquals(a.id, b.id)
    def mixed(aStart: Long, bStart: Long): TypedSupport =
      TypedSupport.Anchored(
        right(
          EvidenceSupport.of(
            a,
            Vector(
              mediaAt(a, a.streams(0), a.primaryAxis, aStart, aStart + 2L),
              mediaAt(a, a.streams(1), b.primaryAxis, bStart, bStart + 2L)
            )
          )
        )
      )
    val firstSupport = mixed(1L, 50L)
    val secondSupport = mixed(10L, 2L)
    def supported(key: String, support: TypedSupport): SituationNode = event(a, key, 1L, 3L) match
      case SituationNode.Event(n) => SituationNode.Event(n.copy(support = support))
      case _                      => fail("event fixture changed")
    val first = supported("first", firstSupport)
    val second = supported("second", secondSupport)
    val context = graph(a).contexts(world).copy(support = firstSupport)
    val g = graph(a).copy(
      contexts = Map(world -> context),
      situations = Map(first.id -> first, second.id -> second)
    )
    val before =
      right(StoryModel.draft(atlas(a), g, NarrativeHierarchy.empty, DiscourseTrajectory.empty))
    assert(before.copy[ModelStatus.Draft](atlas = atlas(b)).isLeft)
    def rebound(value: TypedSupport): TypedSupport = value match
      case TypedSupport.Anchored(support) =>
        TypedSupport.Anchored(right(EvidenceSupport.of(b, support.anchors.toVector)))
      case other => other
    val reboundGraph = g.copy(
      contexts = Map(world -> context.copy(support = rebound(firstSupport))),
      situations = g.situations.view.mapValues {
        case SituationNode.Event(node) =>
          SituationNode.Event(node.copy(support = rebound(node.support)))
        case _ => fail("event fixture changed")
      }.toMap
    )
    val after = right(before.copy[ModelStatus.Draft](atlas = atlas(b), graph = reboundGraph))
    assertEquals(before.discourseOrder, Vector(first.id, second.id))
    assertEquals(after.discourseOrder, Vector(second.id, first.id))
    assertEquals(after.situationsByContext(world), Vector(second.id, first.id))
    assertEquals(after.graph.situations.keySet, before.graph.situations.keySet)
    assertEquals(after.bundle.streams, before.bundle.streams)
    assertEquals(after.bundle.id, before.bundle.id)
    assertEquals(before.projectionOf(firstSupport).bounds, (1L, 3L))
    assertEquals(after.projectionOf(rebound(firstSupport)).bounds, (50L, 52L))
    assertEquals(after.graph.situations(first.id).support, rebound(firstSupport))
    assertNotEquals(before.sourceChecksum, after.sourceChecksum)
    assertNotEquals(before.storyId, after.storyId)

  test("failed text validation retains the same report and no promoted witness"):
    val built = Small.build(2, 1)
    val before = built.draft()
    val context = before.graph.contexts(built.world)
    val outside =
      TypedSupport.Text(SpanSet.one(TextSpan.unsafe(0, built.source.canonicalText.length + 1)))
    val model = right(
      before.copy[ModelStatus.Draft](graph =
        before.graph.copy(contexts = Map(built.world -> context.copy(support = outside)))
      )
    )
    val text = StoryModel.asText(model).getOrElse(fail("text witness lost"))
    val general = StoryValidator.validate(model)
    val witnessed = StoryValidator.validate(text)
    assertEquals(witnessed.report, general.report)
    assertEquals(witnessed.report.errors.map(_.law), Vector("support.in-text"))
    assertEquals(witnessed.validated, None)
    assertEquals(general.validated, None)

  test("node membership is rechecked independently of an available primary interval"):
    val (a, b) = twoAxes()
    val support = right(
      EvidenceSupport.of(
        a,
        Vector(
          mediaAt(a, a.streams(0), a.primaryAxis, 1L, 3L),
          mediaAt(a, a.streams(1), b.primaryAxis, 50L, 52L)
        )
      )
    )
    val original = graph(a).contexts(world)
    val g = graph(a).copy(contexts =
      Map(world -> original.copy(support = TypedSupport.Anchored(support)))
    )
    val accepted =
      right(StoryModel.draft(atlas(a), g, NarrativeHierarchy.empty, DiscourseTrajectory.empty))
    val other = a.streams(1)
    val narrowed = right(
      SourceStream.of(
        other.id,
        other.kind,
        other.checksum,
        other.nativeAxis,
        right(AxisExtent.playbackTicks(0L, 40L, RationalTimebase.Millisecond)),
        other.timebase,
        other.derivedFrom
      )
    )
    val changed = right(
      SourceBundle.of(
        a.edition,
        a.sourceKind,
        Vector(a.streams(0), narrowed),
        a.primaryAxis,
        a.authorityTracks,
        a.mappings
      )
    )
    assertEquals(a.id, changed.id)
    assert(right(support.intervalsOn(changed.primaryAxis.id)).intervals.toVector.nonEmpty)
    assert(accepted.copy[ModelStatus.Draft](atlas = atlas(changed)).isLeft)
    assert(
      StoryModel
        .draft(atlas(changed), g, NarrativeHierarchy.empty, DiscourseTrajectory.empty)
        .isLeft
    )

  test("non-primary coordinate metadata and mapping payload reach both model identity fields"):
    val (base, otherPrimary) = twoAxes()
    val secondary = base.streams(1)
    val changedStream = right(
      SourceStream.of(
        secondary.id,
        secondary.kind,
        secondary.checksum,
        secondary.nativeAxis,
        right(AxisExtent.playbackTicks(0L, 150L, RationalTimebase.Millisecond)),
        secondary.timebase,
        secondary.derivedFrom
      )
    )
    val changedMetadata = right(
      SourceBundle.of(
        base.edition,
        base.sourceKind,
        Vector(base.streams(0), changedStream),
        base.primaryAxis,
        base.authorityTracks,
        Vector.empty
      )
    )
    val receipt =
      right(SourceDerivationReceipt.of("clock", "parameters", Vector(Checksum.ofText("input"))))
    def mapped(offset: Long): SourceBundle =
      val repair = right(
        ClockRepair.of(
          otherPrimary.primaryAxis.id,
          base.primaryAxis.id,
          right(ExactRational.of(1L, 1L)),
          right(ExactRational.of(offset, 1L)),
          receipt
        )
      )
      right(
        SourceBundle.of(
          base.edition,
          base.sourceKind,
          base.streams,
          base.primaryAxis,
          base.authorityTracks,
          Vector(repair)
        )
      )
    Vector((base, changedMetadata), (mapped(0L), mapped(1L))).foreach { (before, after) =>
      assertEquals(before.id, after.id)
      assertNotEquals(draft(before).storyId, draft(after).storyId)
      assertNotEquals(draft(before).sourceChecksum, draft(after).sourceChecksum)
    }

  test("primary interval union preserves gaps and selects every direct primary anchor"):
    val bundle = film()
    val stream = bundle.streams.head.id
    def iv(a: Long, b: Long) = right(PlaybackInterval.on(bundle.primaryAxis, a, b))
    val support = right(
      EvidenceSupport.of(
        bundle,
        Vector(
          EvidenceAnchor.MediaTime(
            bundle.id,
            stream,
            bundle.primaryAxis.id,
            right(PlaybackIntervalSet.of(Vector(iv(1L, 5L), iv(10L, 12L))))
          ),
          EvidenceAnchor.Shot(bundle.id, stream, ShotId.unsafe("shot"), iv(4L, 8L)),
          EvidenceAnchor.Track(
            bundle.id,
            stream,
            TrackId.unsafe("track"),
            PlaybackIntervalSet.one(iv(20L, 21L))
          )
        )
      )
    )
    val projection = right(PrimaryProjection.on(bundle, TypedSupport.Anchored(support)))
    projection match
      case PrimaryProjection.Playback(axis, intervals) =>
        assertEquals(axis, bundle.primaryAxis.id)
        assertEquals(
          intervals.intervals.toVector.map(i => (i.start, i.endExclusive)),
          Vector((1L, 8L), (10L, 12L), (20L, 21L))
        )
      case _ => fail("lost playback projection")
    assertEquals(support.anchors.length, 3)

  test("native-only support is refused even when an explicit map exists"):
    val (base, other) = twoAxes()
    val receipt = right(SourceDerivationReceipt.of("clock", "parameters", Vector.empty))
    val repair = right(
      ClockRepair.of(
        other.primaryAxis.id,
        base.primaryAxis.id,
        right(ExactRational.of(1L, 1L)),
        right(ExactRational.of(0L, 1L)),
        receipt
      )
    )
    val bundle = right(
      SourceBundle.of(
        base.edition,
        base.sourceKind,
        base.streams,
        base.primaryAxis,
        base.authorityTracks,
        Vector(repair)
      )
    )
    val native = right(
      EvidenceSupport.of(
        bundle,
        Vector(mediaAt(bundle, bundle.streams(1), other.primaryAxis, 1L, 3L))
      )
    )
    assert(PrimaryProjection.on(bundle, TypedSupport.Anchored(native)).isLeft)
    val context = graph(bundle).contexts(world).copy(support = TypedSupport.Anchored(native))
    assert(
      StoryModel
        .draft(
          atlas(bundle),
          graph(bundle).copy(contexts = Map(world -> context)),
          NarrativeHierarchy.empty,
          DiscourseTrajectory.empty
        )
        .isLeft
    )
