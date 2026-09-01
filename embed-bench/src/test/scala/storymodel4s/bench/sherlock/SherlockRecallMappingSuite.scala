package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import munit.FunSuite
import storymodel4s.acquire.SherlockAnnotations
import storymodel4s.acquire.SherlockAnnotations.{MediaLocus, MediaManifest, PartIdentity}
import storymodel4s.align.*
import storymodel4s.bench.{BenchChannels, SemanticChannelKind}
import storymodel4s.core.{Checksum, SituationId, StorySource}
import storymodel4s.embed.onnx.{OnnxSentenceArtifacts, OnnxSentenceEmbedder, OnnxSentenceModel}
import storymodel4s.recall.{RecallSegmenter, RecallUnitId}

/** Courts for the annotation-to-SourceView bridge and the recall CSV reader, plus one end-to-end
  * smoke on wholly synthetic material: a recall clause about a distinctive annotated event must
  * anchor to that event's row and resolve to its exact media coordinate.
  */
class SherlockRecallMappingSuite extends FunSuite:

  private val header =
    "Segment Number\tStart Time (s) \tEnd Time (s) \tStart TR\tEnd TR\tScene Segments\t" +
      "Scene Details - A Level \tSpace-In/Outdoor\tName - All\tName - Focus\tName - Speaking\t" +
      "Location\tCamera Angle\tMusic Presence \tWords on Screen "

  private val fixtureRows = Vector(
    "1\t0\t10\t1\t7\t1. Opening\tA man walks alone through heavy rain.\tOutdoor\tMan\tMan\t\tStreet\tLong\tNo\t",
    "2\t10\t20\t8\t14\t\tThe man finds a red door and knocks twice.\tOutdoor\tMan\t\tMan\tStreet\tMedium\tNo\t",
    "3\t20\t20\t14\t14\t\tSmash cut to black.\tIndoor\t\t\t\t\tClose\tNo\t",
    "4\t20\t30\t\t\t\tBlack screen while the projector is switched.\tIndoor\t\t\t\t\tLong\tNo\t",
    "5\t0\t5\t21\t24\t2. Cartoon\tPeople in costumes parade and sing about popcorn.\tIndoor\tSingers\tSingers\tSingers\tCartoon World\tLong\tYes\t",
    "6\t5\t12\t25\t29\t\tPopcorn pops in a glass machine.\tIndoor\tSinger\t\tSinger\tCartoon World\tMedium\tYes\t"
  )

  private def atlas: SherlockAnnotations.Atlas =
    val bytes = (header +: fixtureRows).mkString("\n").getBytes(StandardCharsets.UTF_8)
    val manifest = MediaManifest(
      annotationSha256 = Checksum.ofBytes(bytes),
      partA = PartIdentity("media-part-a", 1, Checksum.ofText("synthetic-a"), 76000L, 2500L),
      partB = PartIdentity("media-part-b", 2, Checksum.ofText("synthetic-b"), 30000L, 2500L),
      totalRows = 6,
      run1EndRow = 4
    )
    SherlockAnnotations
      .parse(bytes, manifest)
      .fold(e => throw new IllegalStateException(e.message), identity)

  test("the bridge exposes every microsegment and scene exactly once, with resolvable supports") {
    val built = SherlockAnnotationView.build(atlas)
    assertEquals(built.view.leaves.size, 6)
    assertEquals(built.view.nodes.size, 8)
    assertEquals(built.view.maxLevel, 1)
    // every leaf support slices back to its own description on the derived document
    atlas.rows.foreach { row =>
      val ref = built.rowByRef.collectFirst { case (r, n) if n == row.row => r }.get
      val span = built.view.node(ref).get.support.minSpan
      assertEquals(built.document.substring(span.start, span.endExclusive), row.description)
    }
  }

  test("every node carrying media resolves to its crosswalked coordinate") {
    val built = SherlockAnnotationView.build(atlas)
    // all six leaves carry media; both scenes are single-part so they carry hulls
    assertEquals(built.media.size, 8)
    val row2 = built.rowByRef.collectFirst { case (r, n) if n == 2 => r }.get
    built.media(row2) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-a")
        assertEquals((iv.start, iv.endExclusive), (25000L, 50000L))
      case other => fail(s"row 2 must carry an extent, got $other")
    val scene2 = built.sceneByRef.collectFirst { case (r, s) if s.ordinal == 2 => r }.get
    built.media(scene2) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-b")
        assertEquals((iv.start, iv.endExclusive), (0L, 30000L))
      case other => fail(s"scene 2 must carry a hull extent, got $other")
  }

  test("world order follows film order across the run boundary") {
    val built = SherlockAnnotationView.build(atlas)
    val order = built.view.worldOrder.get
    val row4 = built.rowByRef.collectFirst { case (r, n) if n == 4 => r }.get
    val row5 = built.rowByRef.collectFirst { case (r, n) if n == 5 => r }.get
    assert(order(row4) < order(row5), "run 2 rows must come after run 1 rows in world order")
  }

  test("the recall CSV reader takes the word column and refuses a foreign header") {
    val csv = "Words,Onset (sec)\nSo,4.8\nthe,5.1\nman,5.3\nknocked,5.8\n"
    val words = RecallWordsCsv.parse(csv).fold(e => fail(e), identity)
    assertEquals(words.map(_.word), Vector("So", "the", "man", "knocked"))
    assertEquals(words.head.onsetSeconds, Some(4.8))
    assertEquals(RecallWordsCsv.transcriptText(words), "So the man knocked")
    assert(RecallWordsCsv.parse("Time,Word\n1,so\n").isLeft)
    assert(RecallWordsCsv.parse("Words,Onset (sec)\n").isLeft)
  }

  test("a recall clause about a distinctive annotated event maps to that row's media coordinate") {
    val built = SherlockAnnotationView.build(atlas)
    val transcript = StorySource
      .fromText(
        "The man knocked on a red door, and then people in costumes were singing about popcorn.",
        Some("synthetic recall")
      )
      .fold(e => throw new IllegalStateException(e.message), identity)
    val recall = RecallSegmenter.segment(transcript)
    val semantic = SemanticDistance.lexicalJaccard
    val candidates =
      CandidateGenerator(semantic, perLevel = 3, lexicalOverlap = false)
        .generate(recall.ordered, built.view)
    val result = GraphHsmm
      .infer(recall, built.view, candidates, DefaultLocalCostModel(semantic = semantic))
      .fold(e => throw new IllegalStateException(e.message), identity)

    val doorUnit = recall.ordered
      .find(_.text.toLowerCase.contains("red door"))
      .getOrElse(fail("the segmenter must keep the red-door clause"))
    val row = result.posterior.rows
      .find(_.unit == doorUnit.id)
      .getOrElse(fail("the posterior must carry the red-door unit"))
    val anchor = row.mapSource.getOrElse(fail("the red-door unit must anchor to the source"))
    val anchoredRow = built.rowByRef.get(anchor)
    assertEquals(anchoredRow, Some(2), s"expected the red-door row, got ${anchor.key}")
    built.media(anchor) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-a")
        assertEquals((iv.start, iv.endExclusive), (25000L, 50000L))
      case other => fail(s"the anchored row must carry its exact media extent, got $other")
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

  test("the bridge states each node's embedding text: descriptions for leaves, labels for scenes") {
    val built = SherlockAnnotationView.build(atlas)
    assertEquals(built.nodeTexts.size, 8)
    val byRef = built.nodeTexts.toMap
    atlas.rows.foreach { row =>
      val ref = built.rowByRef.collectFirst { case (r, n) if n == row.row => r }.get
      assertEquals(byRef(ref), row.description)
    }
    built.sceneByRef.foreach { case (ref, scene) => assertEquals(byRef(ref), scene.label) }
  }

  // Runs only when the pinned MiniLM artifacts are supplied, like WogDiagnosticSuite's neural leg:
  // the checksums inside OnnxSentenceModel.AllMiniLmL6V2 refuse any other bytes at open.
  test("the neural channel puts the red-door unit nearer its row than an unrelated row") {
    val supplied = for
      model <- sys.env.get("STORYMODEL4S_ONNX_MODEL")
      tokenizer <- sys.env.get("STORYMODEL4S_ONNX_TOKENIZER")
    yield OnnxSentenceArtifacts(Paths.get(model), Paths.get(tokenizer))
    assume(supplied.nonEmpty, "STORYMODEL4S_ONNX_MODEL / STORYMODEL4S_ONNX_TOKENIZER not set")
    val built = SherlockAnnotationView.build(atlas)
    val transcript = StorySource
      .fromText("The man knocked on a red door.", Some("synthetic recall"))
      .fold(e => throw new IllegalStateException(e.message), identity)
    val recall = RecallSegmenter.segment(transcript)
    val embedder = OnnxSentenceEmbedder
      .open(OnnxSentenceModel.AllMiniLmL6V2, supplied.get)
      .fold(e => fail(e.message), identity)
    try
      val channel = BenchChannels
        .neural(embedder, recall.ordered, built.nodeTexts)
        .fold(e => fail(e.message), identity)
      assertEquals(channel.semanticIdentity.kind, SemanticChannelKind.NeuralEncoder)
      val unit = recall.ordered.head
      def nodeAtRow(n: Int): NodeSummary =
        val ref = built.rowByRef.collectFirst { case (r, row) if row == n => r }.get
        built.view.node(ref).get
      val toDoor = channel.semantic(unit, nodeAtRow(2)).toOption.getOrElse(fail("door abstained"))
      val toPopcorn =
        channel.semantic(unit, nodeAtRow(6)).toOption.getOrElse(fail("popcorn abstained"))
      assert(
        toDoor + 0.05 < toPopcorn,
        s"neural distance must separate the rows: door=$toDoor popcorn=$toPopcorn"
      )
    finally embedder.close()
  }
