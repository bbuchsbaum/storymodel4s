package storymodel4s.story

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.SituationK

/** Joined envelope courts use literal coordinates and deliberately distinct source identities. */
class D1aEnvelopeSuite extends FunSuite:
  private def right[A](value: Either[DomainError, A]): A = value.fold(e => fail(e.message), identity)
  private val world = ContextId.unsafe("film-world")
  private def film(end: Long = 100L, timebase: RationalTimebase = RationalTimebase.Millisecond): SourceBundle =
    right(SourceBundle.filmEdition(EditionId.unsafe("envelope-film"), Checksum.ofText("picture"), 0L, end, timebase))
  private def anchors(bundle: SourceBundle, start: Long = 1L, end: Long = 3L): EvidenceSupport =
    right(EvidenceSupport.media(bundle, bundle.streams.head.id,
      PlaybackIntervalSet.one(right(PlaybackInterval.on(bundle.primaryAxis, start, end)))))
  private def meta(key: String): ClaimMeta = Small.meta(key, EpistemicStatus.Hypothesized, None)
  private def graph(bundle: SourceBundle): NarrativeGraph =
    NarrativeGraph(Map.empty, Map.empty, Map.empty,
      Map(world -> ContextFrame(world, None, ContextKind.NarratedWorld,
        TypedSupport.Anchored(anchors(bundle)), meta("world"))), RelationLayers.empty)
  private def atlas(bundle: SourceBundle, surface: Option[BoundProposalSurface] = None): AnchoredNarrativeAtlas =
    right(AnchoredNarrativeAtlas.of(bundle, Vector.empty, surface))
  private def draft(bundle: SourceBundle, surface: Option[BoundProposalSurface] = None): StoryModel[ModelStatus.Draft] =
    right(StoryModel.draft(atlas(bundle, surface), graph(bundle), NarrativeHierarchy.empty, DiscourseTrajectory.empty))
  private def event(bundle: SourceBundle, key: String, start: Long, end: Long, modality: Modality = Modality.Asserted): SituationNode =
    SituationNode.Event(EventNode(SituationId.unsafe(key), Predicate("happen", None, "happen"), key,
      world, Polarity.Positive, modality, None, TypedSupport.Anchored(anchors(bundle, start, end)),
      NonEmptyVector.one(MentionId.unsafe[SituationK]("mention:" + key)), meta(key)))

  test("accepting control: anchored draft validates and has no canonical text witness"):
    val model = draft(film())
    assertEquals(model.bundle, model.atlas.bundle)
    assertEquals(StoryModel.asText(model), None)
    assertEquals(StoryValidator.validate(model).report.violations, Vector.empty)
    assert(StoryValidator.validate(model).validated.nonEmpty)

  test("text identity and witness survive validation and adjudication without replacement"):
    val source = right(StorySource.fromText("Alpha.", explicitId = Some(StoryId.unsafe("explicit-text-id"))))
    val surface = SurfaceAnalyzer.analyze(source)
    val context = ContextFrame(world, None, ContextKind.NarratedWorld,
      SpanSet.one(TextSpan.unsafe(0, 5)), meta("text-world"))
    val g = NarrativeGraph(Map.empty, Map.empty, Map.empty, Map(world -> context), RelationLayers.empty)
    val model = right(StoryModel.draftText(surface, g, NarrativeHierarchy.empty, DiscourseTrajectory.empty))
    assertEquals(model.storyId, source.id)
    assertEquals(model.sourceChecksum, source.canonicalChecksum)
    assertEquals(model.source, surface.source)
    assertEquals(StoryModel.asText(model.model), Some(model))
    assertEquals(StoryModel.asText(model.model).get.hashCode, model.hashCode)
    val validated = StoryValidator.validate(model).validated.getOrElse(fail("text promotion refused"))
    val adjudicated = StoryModel.adjudicated(validated)
    assert(validated.text eq model.text)
    assert(adjudicated.text eq model.text)
    assertEquals(validated.model, model.model)
    assertEquals(adjudicated.model, model.model)

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
      BoundProposalSurface.of(SurfaceAnalyzer.analyze(right(StorySource.fromText(text))),
        right(SourceDerivationReceipt.of(algorithm, "parameters", Vector(Checksum.ofText("input")))))
    val bundle = film()
    val first = bound("First caption.", "captioner")
    val variants = Vector(None, Some(bound("Other caption.", "captioner")), Some(bound("First caption.", "other-captioner")))
    variants.foreach { surface =>
      assertNotEquals(draft(bundle, surface).storyId, draft(bundle, Some(first)).storyId)
      assertNotEquals(draft(bundle, surface).sourceChecksum, draft(bundle, Some(first)).sourceChecksum)
    }

  test("receipt joins check story id and full source checksum for draft and copy"):
    val base = draft(film())
    val receipt = BuildReceipt(base.storyId, base.sourceChecksum, StoryModel.SchemaVersion, Vector.empty, 0L)
    def remake(r: BuildReceipt) = StoryModel.draft(base.atlas, base.graph, base.hierarchy, base.trajectory, receipt = Some(r))
    assert(remake(receipt).isRight)
    assert(base.copy[ModelStatus.Draft](receipt = Some(receipt)).isRight)
    Vector(receipt.copy(storyId = StoryId.unsafe("wrong-story")), receipt.copy(sourceChecksum = Checksum.ofText("wrong-source"))).foreach { wrong =>
      assert(remake(wrong).isLeft)
      assert(base.copy[ModelStatus.Draft](receipt = Some(wrong)).isLeft)
    }

  test("anchored draft and copy refuse bare or twin claim evidence"):
    val bundle = film()
    val base = draft(bundle)
    val context = base.graph.contexts(world)
    val original = context.meta.evidence.head
    val good = original.copy(anchors = Some(anchors(bundle)))
    def changed(evidence: Evidence): NarrativeGraph = base.graph.copy(contexts = Map(world ->
      context.copy(meta = right(context.meta.withEvidence(NonEmptyVector.one(evidence))))))
    assert(base.copy[ModelStatus.Draft](graph = changed(good)).isRight)
    Vector(original.copy(spans = Some(SpanSet.one(TextSpan.unsafe(0, 1)))),
      good.copy(spans = Some(SpanSet.one(TextSpan.unsafe(0, 1))))).foreach { wrong =>
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
    val changed = base.graph.copy(contexts = Map(world -> original.copy(meta = right(original.meta.withEvidence(evidence)))))
    assert(base.copy[ModelStatus.Draft](graph = changed).isLeft)

  test("generic anchored validation retains root Reported and retrospective duplicate rules"):
    val bundle = film()
    val base = draft(bundle)
    val a = event(bundle, "a", 1L, 3L)
    val b = event(bundle, "b", 4L, 6L, Modality.Reported)
    val g = base.graph.copy(situations = Map(a.id -> a, b.id -> b))
    val reported = right(base.copy[ModelStatus.Draft](graph = g))
    assert(StoryValidator.validate(reported).report.errors.exists(_.law == "reported-content-not-root-without-root-claim"))
    val asserted = event(bundle, "b", 4L, 6L)
    val ref = ReferenceEdge(a.id, NarrativeReference.Retrospective, asserted.id, meta("reference"))
    val repeated = right(base.copy[ModelStatus.Draft](graph = g.copy(situations = Map(a.id -> a, asserted.id -> asserted), relations = RelationLayers.empty.copy(references = Vector(ref)))))
    assert(StoryValidator.validate(repeated).report.warnings.exists(_.law == "no-duplicate-occurrence-via-retrospective-reference"))
    assert(!StoryValidator.validate(repeated).report.violations.exists(_.law == "explicit-causal-requires-span-with-causal-cue"))
