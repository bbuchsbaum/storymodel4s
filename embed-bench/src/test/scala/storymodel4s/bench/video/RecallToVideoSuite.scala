package storymodel4s.bench.video

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.{
  Checksum,
  EditionId,
  PlaybackInterval,
  RationalTimebase,
  SituationId,
  SourceBundle,
  StorySource
}
import storymodel4s.recall.{RecallSegmenter, RecallUnitId}

/** Courts for the general recall-to-video pipeline: the timed-segment view builder (flat and
  * grouped), the timestamped-transcript timing, and the anchor-confidence quantities. Nothing in
  * here knows a story or an annotation format.
  */
class RecallToVideoSuite extends FunSuite:

  test("a flat annotation (no groups) builds a leaf-only view whose supports slice back") {
    val segments = Vector(
      TimedSegment(1, "A man walks through rain.", None),
      TimedSegment(2, "He finds a red door.", None),
      TimedSegment(3, "The door opens.", None)
    )
    val built = TimedSourceView
      .build(segments, WorldOrderFixtures.syntheticLinear)
      .fold(e => fail(e.message), identity)
    assertEquals(built.view.leaves.size, 3)
    assertEquals(built.view.nodes.size, 3)
    assertEquals(built.view.maxLevel, 0)
    assertEquals(built.media, Map.empty[SourceNodeRef, MediaLocus])
    segments.foreach { seg =>
      val ref = built.segmentByRef.collectFirst { case (r, s) if s.ordinal == seg.ordinal => r }.get
      val span = built.view.node(ref).get.support.minSpan
      assertEquals(built.document.substring(span.start, span.endExclusive), seg.text)
    }
    assertEquals(built.nodeTexts.map(_._2), segments.map(_.text))
  }

  test("a group hull is minted on the part's own axis, and refused without one") {
    val bundle = (for
      edition <- EditionId.from("video-test-part")
      timebase <- RationalTimebase.of(1L, 1000L)
      b <- SourceBundle.filmEdition(edition, Checksum.ofText("synthetic"), 0L, 100000L, timebase)
    yield b).fold(e => throw new IllegalStateException(e.message), identity)
    val axis = bundle.primaryAxis
    def extent(start: Long, end: Long): MediaLocus =
      MediaLocus.Extent(
        "part-x",
        PlaybackInterval
          .on(axis, start, end)
          .fold(e => throw new IllegalStateException(e.message), identity)
      )
    val group = TimedSegment.Group(1, "1. Opening")
    val segments = Vector(
      TimedSegment(1, "A man walks through rain.", Some(extent(1000L, 2000L)), Some(group)),
      TimedSegment(2, "He finds a red door.", Some(extent(3000L, 4000L)), Some(group))
    )

    val withAxis = TimedSourceView
      .build(segments, WorldOrderFixtures.syntheticLinear, axes = Map("part-x" -> axis))
      .fold(e => fail(e.message), identity)
    assertEquals(withAxis.view.nodes.size, 3)
    val groupRef = withAxis.groupByRef.keys.head
    withAxis.media(groupRef) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "part-x")
        assertEquals((iv.start, iv.endExclusive), (1000L, 4000L))
      case other => fail(s"the group must carry a hull extent, got $other")

    val withoutAxis = TimedSourceView
      .build(segments, WorldOrderFixtures.syntheticLinear)
      .fold(e => fail(e.message), identity)
    val bareGroupRef = withoutAxis.groupByRef.keys.head
    assertEquals(withoutAxis.media.get(bareGroupRef), None)
    // the leaves keep their exact loci either way
    assertEquals(withoutAxis.media.size, 2)
  }

  test("word spans slice the joined transcript back to each word") {
    val words = Vector(
      RecallWordsCsv.RecallWord("The", Some(1.0)),
      RecallWordsCsv.RecallWord("man", Some(1.4)),
      RecallWordsCsv.RecallWord("knocked.", Some(2.0))
    )
    val transcript = RecallWordsCsv.transcriptText(words)
    assertEquals(transcript, "The man knocked.")
    val spans = RecallTiming.wordSpans(words)
    assertEquals(spans.map(s => (s.start, s.endExclusive)), Vector((0, 3), (4, 7), (8, 16)))
    words.zip(spans).foreach { case (w, s) =>
      assertEquals(transcript.substring(s.start, s.endExclusive), w.word)
    }
  }

  test("a unit's onset is its first measured word onset; unmeasured words never impute a zero") {
    val words = Vector(
      RecallWordsCsv.RecallWord("The", None),
      RecallWordsCsv.RecallWord("man", Some(1.4)),
      RecallWordsCsv.RecallWord("knocked.", Some(2.0))
    )
    val transcript = StorySource
      .fromText(RecallWordsCsv.transcriptText(words), Some("timing fixture"))
      .fold(e => throw new IllegalStateException(e.message), identity)
    val recall = RecallSegmenter.segment(transcript)
    assertEquals(recall.ordered.size, 1)
    val timing = RecallTiming.unitTimings(words, recall.ordered)(recall.ordered.head.id)
    assertEquals(timing.onsetSeconds, Some(1.4))
    assertEquals(timing.lastWordOnsetSeconds, Some(2.0))

    val untimed = words.map(_.copy(onsetSeconds = None))
    val bare = RecallTiming.unitTimings(untimed, recall.ordered)(recall.ordered.head.id)
    assertEquals(bare.onsetSeconds, None)
    assertEquals(bare.lastWordOnsetSeconds, None)
  }

  test("anchor confidence publishes MAP mass, runner-up, and localizability, raw") {
    def ref(n: Int): SourceNodeRef =
      SourceNodeRef.Situation(SituationId.unsafe(f"conf:row:$n%04d"))
    val row = AlignmentRow
      .of(
        RecallUnitId.unsafe("conf:unit:1"),
        Map(
          AlignState.Source(ref(1)) -> 0.5,
          AlignState.Source(ref(2)) -> 0.3,
          AlignState.External(ExternalState.Commentary) -> 0.2
        )
      )
      .fold(e => throw new IllegalStateException(e.message), identity)
    val c = AnchorConfidence.of(row, sourceNodeCount = 10)
    assertEquals(c.mapAnchorMass, Some(0.5))
    assertEquals(c.runnerUpAnchor, Some(ref(2)))
    assertEquals(c.runnerUpMass, Some(0.3))
    // independent recomputation: H over {0.5, 0.3}/0.8, normalized by log K
    val (p1, p2) = (0.5 / 0.8, 0.3 / 0.8)
    val expected = 1.0 - (-(p1 * math.log(p1) + p2 * math.log(p2))) / math.log(10.0)
    assert(
      c.localizability.exists(l => math.abs(l - expected) < 1e-12),
      s"localizability ${c.localizability} != $expected"
    )

    val allExternal = AlignmentRow
      .of(
        RecallUnitId.unsafe("conf:unit:2"),
        Map(AlignState.External(ExternalState.Commentary) -> 1.0)
      )
      .fold(e => throw new IllegalStateException(e.message), identity)
    val none = AnchorConfidence.of(allExternal, sourceNodeCount = 10)
    assertEquals(none.mapAnchorMass, None)
    assertEquals(none.runnerUpAnchor, None)
    assertEquals(none.localizability, None)
  }
