package storymodel4s.align

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.*

/** Synthetic text witnesses discriminate content aliases and invalid declaration-to-text joins. */
class SurfaceRenderingSuite extends FunSuite:
  private def value[A, E](x: Either[E, A]): A = x.fold(e => fail(s"fixture refused: $e"), identity)
  private def spans(ranges: (Int, Int)*): SpanSet =
    SpanSet.unsafe(ranges.map((a, b) => SpanRef(TextSpan.unsafe(a, b)))*)

  private def source(text: String): StorySource = value(StorySource.fromText(text))
  private def atlas(text: String): SurfaceAtlas = SurfaceAnalyzer.analyze(source(text))
  private val unitId = RecallUnitId.unsafe("synthetic-unit")
  private val targetRef = SourceNodeRef.Situation(SituationId.unsafe("synthetic-target"))

  private def recall(text: String, support: SpanSet) =
    val a = atlas(text)
    val hull = support.minSpan
    val u = RecallUnit(
      unitId,
      0,
      support,
      a.source.canonicalText.substring(hull.start, hull.endExclusive),
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
    RecallGraph
      .validated(a.source, a, Vector(u), RecallRelations.empty)
      .fold(e => fail(s"recall fixture refused: $e"), identity)

  private def view(support: SpanSet, ref: SourceNodeRef = targetRef) =
    InMemorySourceView(
      Vector(
        NodeSummary(
          ref,
          0,
          None,
          0,
          support,
          None,
          Vector.empty,
          ContextTag.NarratedWorld,
          PolarityTag.Unknown,
          ModalityTag.Unknown,
          Vector.empty,
          Set.empty
        )
      ),
      Map.empty,
      None,
      support.minSpan.endExclusive
    )

  private def representation(v: SourceView, a: SurfaceAtlas, physical: Option[SpanSet] = None) =
    value(
      SourceRepresentation.of(
        v,
        NonEmptyVector.one(BundleEntry.text(a.source.canonicalChecksum)),
        None,
        v.nodes
          .map(n =>
            n.ref -> SourceSupportStatus.located(
              physical.fold(n.support)(TypedSupport.Text.apply)
            )
          )
          .toMap
      )
    )

  private def pair(
      sourceText: String = "red GAP blue",
      sourceSpans: SpanSet = spans(0 -> 3, 8 -> 12),
      recallText: String = "red GAP blue",
      recallSpans: SpanSet = spans(0 -> 3, 8 -> 12)
  ) =
    val a = atlas(sourceText)
    val v = view(sourceSpans)
    SurfaceRendering.pair(
      recall(recallText, recallSpans),
      unitId,
      v,
      representation(v, a),
      a,
      targetRef
    )

  test("gappy recall retains hull while target renders only declared union") {
    val p = value(pair())
    assertEquals(p.unitText, "red GAP blue")
    assertEquals(p.targetText, "red\nblue")
    assertEquals(p.policy, SurfaceRendering.Policy)
  }

  test("overlapping and touching target spans are rendered once") {
    assertEquals(value(pair(sourceSpans = spans(0 -> 2, 1 -> 3, 3 -> 7))).targetText, "red GAP")
  }

  test("empty spans retain empty text rather than a fabricated score") {
    val p = value(pair(sourceSpans = spans(1 -> 1), recallSpans = spans(1 -> 1)))
    assertEquals(p.targetText, "")
    assertEquals(p.unitText, "")
  }

  test("empty spans do not add gaps to nonempty target rendering") {
    assertEquals(value(pair(sourceSpans = spans(0 -> 0, 8 -> 12))).targetText, "blue")
  }

  test("separated empty spans render exactly empty text") {
    assertEquals(value(pair(sourceSpans = spans(0 -> 0, 8 -> 8))).targetText, "")
  }

  test("self-cycle and disconnected two-node cycle return typed hierarchy refusal") {
    val a = atlas("red blue")
    val leaf = view(spans(0 -> 3)).nodes.head
    val first = SourceNodeRef.Segment(SegmentId.unsafe("cycle-first"))
    val second = SourceNodeRef.Segment(SegmentId.unsafe("cycle-second"))
    def parent(ref: SourceNodeRef, above: SourceNodeRef) = NodeSummary(
      ref,
      1,
      Some(above),
      0,
      spans(0 -> 3),
      None,
      Vector.empty,
      ContextTag.NarratedWorld,
      PolarityTag.Unknown,
      ModalityTag.Unknown,
      Vector.empty,
      Set.empty
    )
    for cyclic <- Vector(
        Vector(parent(first, first)),
        Vector(parent(first, second), parent(second, first))
      )
    do
      val v = InMemorySourceView(leaf +: cyclic, Map.empty, None, 8)
      // Capture the mutant's recursion failure as data so the named typed-refusal assertion,
      // rather than a test-runner error, distinguishes the removed guard.
      val outcome =
        try
          Right(
            SurfaceRendering.pair(
              recall("red", spans(0 -> 3)),
              unitId,
              v,
              representation(v, a),
              a,
              targetRef
            )
          )
        catch case _: StackOverflowError => Left("stack-overflow")
      assertEquals(outcome, Right(Left(SurfaceRenderingRefusal.InvalidHierarchy)))
  }

  test("ordinary nested hierarchy permits surface rendering") {
    val a = atlas("red blue")
    val parentRef = SourceNodeRef.Segment(SegmentId.unsafe("parent"))
    val parent = NodeSummary(
      parentRef,
      1,
      None,
      0,
      spans(0 -> 8),
      None,
      Vector.empty,
      ContextTag.NarratedWorld,
      PolarityTag.Unknown,
      ModalityTag.Unknown,
      Vector.empty,
      Set.empty
    )
    val leaf = NodeSummary(
      targetRef,
      0,
      Some(parentRef),
      0,
      spans(0 -> 3),
      None,
      Vector.empty,
      ContextTag.NarratedWorld,
      PolarityTag.Unknown,
      ModalityTag.Unknown,
      Vector.empty,
      Set.empty
    )
    val v = InMemorySourceView(Vector(leaf, parent), Map.empty, None, 8)
    assertEquals(
      value(
        SurfaceRendering.pair(
          recall("red", spans(0 -> 3)),
          unitId,
          v,
          representation(v, a),
          a,
          parentRef
        )
      ).targetText,
      "red blue"
    )
  }

  test("same semantic target with reordered surface strings has distinct rendered identity") {
    val a = value(pair(sourceText = "red blue", sourceSpans = spans(0 -> 8)))
    val b = value(pair(sourceText = "blue red", sourceSpans = spans(0 -> 8)))
    assertEquals(a.unit, b.unit)
    assertEquals(a.target, b.target)
    assertNotEquals(a, b)
    assertEquals(a.hashCode, b.hashCode, "public hash must not derive from submitted text")
  }

  test("same semantic recall with reordered surface strings has distinct rendered identity") {
    val a = value(pair(recallText = "red blue", recallSpans = spans(0 -> 8)))
    val b = value(pair(recallText = "blue red", recallSpans = spans(0 -> 8)))
    assertEquals(a.unit, b.unit)
    assertEquals(a.target, b.target)
    assertNotEquals(a, b)
    assertEquals(a.hashCode, b.hashCode)
  }

  test("source declaration refuses different bytes even at identical text length") {
    val a = atlas("red blue")
    val wrong = atlas("blue red")
    val v = view(spans(0 -> 8))
    assertEquals(
      SurfaceRendering
        .pair(recall("red", spans(0 -> 3)), unitId, v, representation(v, a), wrong, targetRef),
      Left(SurfaceRenderingRefusal.SourceIdentity)
    )
  }

  test("representation physical support must equal node support") {
    val a = atlas("red blue")
    val v = view(spans(0 -> 3))
    assertEquals(
      SurfaceRendering.pair(
        recall("red", spans(0 -> 3)),
        unitId,
        v,
        representation(v, a, Some(spans(4 -> 8))),
        a,
        targetRef
      ),
      Left(SurfaceRenderingRefusal.PhysicalSupport)
    )
  }

  test("rendering uses one captured inventory rather than live lookup") {
    val a = atlas("red blue")
    val v = view(spans(0 -> 3))
    var reads = 0
    val changing = new SourceView:
      def nodes: Vector[NodeSummary] =
        reads += 1
        if reads == 1 then v.nodes else Vector.empty
      def node(ref: SourceNodeRef): Option[NodeSummary] =
        fail("live node lookup must not be read")
      def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
        Map.empty
      def worldOrder: Option[Map[SourceNodeRef, Int]] = None
      def scoringLength: Int = v.scoringLength
    val result = value(
      SurfaceRendering.pair(
        recall("red", spans(0 -> 3)),
        unitId,
        changing,
        representation(v, a),
        a,
        targetRef
      )
    )
    assertEquals(result.targetText, "red")
    assertEquals(reads, 1)
  }

  test("source representation must bind the frozen view") {
    val a = atlas("red blue")
    val v = view(spans(0 -> 3))
    val changed = view(spans(4 -> 8))
    assertEquals(
      SurfaceRendering
        .pair(recall("red", spans(0 -> 3)), unitId, changed, representation(v, a), a, targetRef),
      Left(SurfaceRenderingRefusal.SourceViewBinding)
    )
  }

  test("foreign source anchors are refused before merging spans") {
    val s = SpanSet.one(SpanRef(Some(SurfaceUnitId.unsafe("foreign")), TextSpan.unsafe(0, 3)))
    assertEquals(pair(sourceSpans = s), Left(SurfaceRenderingRefusal.InvalidAnchor))
  }

  test("known anchors must contain the original span") {
    val a = atlas("red blue")
    val token = a.tokens.find(_.text(a.source) == "red").get
    val support = SpanSet.one(SpanRef(Some(token.id), TextSpan.unsafe(4, 8)))
    val v = view(support)
    assertEquals(
      SurfaceRendering
        .pair(recall("red", spans(0 -> 3)), unitId, v, representation(v, a), a, targetRef),
      Left(SurfaceRenderingRefusal.InvalidAnchor)
    )
    val valid = SpanSet.one(SpanRef(Some(token.id), TextSpan.unsafe(0, 3)))
    val control = view(valid)
    assertEquals(
      value(
        SurfaceRendering.pair(
          recall("red", spans(0 -> 3)),
          unitId,
          control,
          representation(control, a),
          a,
          targetRef
        )
      ).targetText,
      "red"
    )
  }

  test("out of bounds source spans are refused rather than truncated") {
    assertEquals(pair(sourceSpans = spans(0 -> 13)), Left(SurfaceRenderingRefusal.InvalidSpan))
  }

  test("source spans cannot split a surrogate pair") {
    assertEquals(
      pair(sourceText = "x😀y", sourceSpans = spans(1 -> 2)),
      Left(SurfaceRenderingRefusal.InvalidSpan)
    )
    assertEquals(value(pair(sourceText = "x😀y", sourceSpans = spans(1 -> 3))).targetText, "😀")
  }

  test("checked recall does not license split surrogate boundaries") {
    assertEquals(
      pair(recallText = "x😀y", recallSpans = spans(1 -> 2)),
      Left(SurfaceRenderingRefusal.InvalidSpan)
    )
  }

  test("unpaired source Unicode is refused before rendering") {
    assertEquals(
      pair(sourceText = "x\uD800y", sourceSpans = spans(0 -> 1)),
      Left(SurfaceRenderingRefusal.InvalidUnicode)
    )
  }

  test("unit anchor is checked even when the recall graph accepts it") {
    val bad = SpanSet.one(SpanRef(Some(SurfaceUnitId.unsafe("foreign")), TextSpan.unsafe(0, 3)))
    assertEquals(pair(recallSpans = bad), Left(SurfaceRenderingRefusal.InvalidAnchor))
  }

  test("relocating spans and renaming ids preserves rendered content equality") {
    val original = value(
      pair(
        sourceText = "red",
        sourceSpans = spans(0 -> 3),
        recallText = "red",
        recallSpans = spans(0 -> 3)
      )
    )
    val a = atlas("xx red")
    val ref = SourceNodeRef.Situation(SituationId.unsafe("renamed-target"))
    val v = view(spans(3 -> 6), ref)
    val renamed = RecallUnitId.unsafe("renamed-unit")
    val r = recall("xx red", spans(3 -> 6))
    val u = r.units.head.copy(id = renamed)
    val rr = RecallGraph
      .validated(r.transcript, r.atlas, Vector(u), RecallRelations.empty)
      .fold(e => fail(s"renamed recall refused: $e"), identity)
    val moved = value(SurfaceRendering.pair(rr, renamed, v, representation(v, a), a, ref))
    assertEquals(moved, original)
    assertEquals(moved.hashCode, original.hashCode)
    assertEquals(moved.toString, "RenderedContentPair(unitChars=3, targetChars=3)")
  }

  test("raw and half cosine retain exact declared units") {
    import EmbeddingMetric.*
    for (raw, half) <- Vector(0.0 -> 0.0, 1.0 -> 0.5, 2.0 -> 1.0) do
      val a = value(CosineDistance.fromCosine(raw))
      val b = value(HalfCosineDistance.fromCosine(raw))
      assertEquals(a.value, raw)
      assertEquals(b.value, half)
      assertEquals(a.metric, CosineDistance)
      assertEquals(b.metric, HalfCosineDistance)
      assertNotEquals(a, b)
    assertEquals(CosineDistance.maximum, 2.0)
    assertEquals(HalfCosineDistance.maximum, 1.0)
  }

  test("cosine conversion refuses nonfinite and out of range rather than clamping") {
    for metric <- EmbeddingMetric.values do
      for d <- Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
        assertEquals(metric.fromCosine(d), Left(EmbeddingMetricRefusal.NonFinite))
      for d <- Vector(-0.1, 2.1) do
        assertEquals(metric.fromCosine(d), Left(EmbeddingMetricRefusal.OutsideCosineRange))
  }

  test("cosine conversion preserves missing, ineligible and observed credence") {
    val metric = EmbeddingMetric.HalfCosineDistance
    val credence = Some(Credence.unsafeRaw(0.6, ScorerId.unsafe("synthetic")))
    assertEquals(value(metric.fromCosineEstimate(Estimate.Ineligible)), Estimate.Ineligible)
    val missing = Estimate.missing(MissingReason.ProviderAbstained)
    assertEquals(value(metric.fromCosineEstimate(missing)), missing)
    val result = value(metric.fromCosineEstimate(Estimate.Observed(1.0, credence)))
    assertEquals(result.toOption.map(_.value), Some(0.5))
    assertEquals(result, Estimate.Observed(value(metric.fromCosine(1.0)), credence))
  }
