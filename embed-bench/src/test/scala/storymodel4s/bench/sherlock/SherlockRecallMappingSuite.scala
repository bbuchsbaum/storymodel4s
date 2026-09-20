package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import munit.FunSuite
import storymodel4s.corpus.intake.{SherlockAnnotations, TimebaseRepair}
import storymodel4s.align.*
import storymodel4s.bench.video.{
  MediaLocus,
  RecallWordsCsv,
  TimedSourceView,
  WorldOrderAbsence,
  WorldOrderInput
}
import storymodel4s.bench.{BenchChannels, SemanticChannelKind}
import storymodel4s.core.{Checksum, StorySource}
import storymodel4s.embed.onnx.{OnnxSentenceArtifacts, OnnxSentenceEmbedder, OnnxSentenceModel}
import storymodel4s.recall.RecallSegmenter

/** Courts for the Sherlock adapter over the general pipeline: the atlas-to-segment conversion,
  * media crosswalk fidelity, world order across the run boundary, and one end-to-end smoke on
  * wholly synthetic material - a recall clause about a distinctive annotated event must anchor to
  * that event's row and resolve to its exact media coordinate.
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
    val relative = "corpus-intake/src/test/resources/sherlock-synthetic-repair.json"
    val path =
      if java.nio.file.Files.exists(Paths.get(relative)) then Paths.get(relative)
      else Paths.get("..", relative)
    // Preserve the previously frozen synthetic source/edition identities.
    val json = java.nio.file.Files
      .readString(path)
      .replace("0" * 64, Checksum.ofBytes(bytes).hex)
      .replace(Checksum.ofText("synthetic-part-a").hex, Checksum.ofText("synthetic-a").hex)
      .replace(Checksum.ofText("synthetic-part-b").hex, Checksum.ofText("synthetic-b").hex)
    val record = TimebaseRepair.parse(json).fold(e => fail(e.message), identity)
    SherlockAnnotations.parse(bytes, record).fold(e => fail(e.message), identity)

  private def refOfRow(built: TimedSourceView.Built, row: Int): SourceNodeRef =
    built.segmentByRef.collectFirst { case (r, s) if s.ordinal == row => r }.get

  private def view(atlas: SherlockAnnotations.Atlas): TimedSourceView.Built =
    SherlockAnnotationView.build(atlas).fold(e => fail(e.message), identity)

  /** `ViewFingerprint.of` on the fixture atlas at origin/main 913f3a8e, before the world-order
    * declaration existed. Declaring what the builder used to assume must not move one identity.
    */
  private val fingerprintBeforeDeclaration =
    "0ffa14649aff8382f718478d89a84496d9de5d89d5e72f188acc3320b8fb2c54"

  test("clock provenance covers all rows and binds the record report and actual axes") {
    val a = atlas
    val report = Checksum.ofText("synthetic report")
    val json = SherlockAnnotationView.clockProvenance(a, view(a), report)
    val c = json.hcursor
    assertEquals(c.get[String]("reportSha256"), Right(report.hex))
    assertEquals(c.get[String]("recordSha256"), Right(a.repairRecord.checksum.hex))
    assertEquals(c.get[String]("sourceFingerprint"), Right(fingerprintBeforeDeclaration))
    assertEquals(c.get[String]("notebookProvenanceStatus"), Right("declared-unverified"))
    val repairs = c.get[Vector[io.circe.Json]]("repairs").toOption.get
    assertEquals(
      repairs.map(_.hcursor.get[String]("targetAxis").toOption.get),
      Vector(a.partABundle.primaryAxis.id.value, a.partBBundle.primaryAxis.id.value)
    )
    val rows = c.get[Vector[io.circe.Json]]("rows").toOption.get
    assertEquals(rows.map(_.hcursor.get[Int]("row").toOption.get), (1 to 6).toVector)
    rows.zipWithIndex.foreach { (row, i) =>
      val number = i + 1
      assertEquals(
        row.hcursor.get[String]("receiptId"),
        Right(a.repairByRow(number).receipt.identity.hex)
      )
      assertEquals(
        row.hcursor.get[String]("startTick"),
        Right(a.mediaByRow(number).startTick.toString)
      )
      assertEquals(row.hcursor.get[String]("endTick"), Right(a.mediaByRow(number).endTick.toString))
    }
    assert(a.rows.forall(r => !json.noSpaces.contains(r.description)))
  }

  test("the bridge exposes every microsegment and scene exactly once, with resolvable supports") {
    val built = view(atlas)
    assertEquals(built.view.leaves.size, 6)
    assertEquals(built.view.nodes.size, 8)
    assertEquals(built.view.maxLevel, 1)
    // every leaf support slices back to its own description on the derived document
    atlas.rows.foreach { row =>
      val span = built.view.node(refOfRow(built, row.row)).get.scoringPosition.get.spans.minSpan
      assertEquals(built.document.substring(span.start, span.endExclusive), row.description)
    }
  }

  test("every node carrying media resolves to its crosswalked coordinate") {
    val built = view(atlas)
    // all six leaves carry media; both scenes are single-part so they carry hulls
    assertEquals(built.media.size, 8)
    built.media(refOfRow(built, 2)) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-a")
        assertEquals((iv.start, iv.endExclusive), (25000L, 50000L))
      case other => fail(s"row 2 must carry an extent, got $other")
    val scene2 = built.groupByRef.collectFirst { case (r, g) if g.ordinal == 2 => r }.get
    built.media(scene2) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-b")
        assertEquals((iv.start, iv.endExclusive), (0L, 30000L))
      case other => fail(s"scene 2 must carry a hull extent, got $other")
  }

  test(
    "under the declared SameAsPresentation clock, world order follows film order across the run boundary"
  ) {
    val built = view(atlas)
    assertEquals(built.worldOrder, SherlockAnnotationView.worldOrder)
    val order = built.view.worldOrder
      .getOrElse(fail("a SameAsPresentation declaration must yield a world order"))
    val row4 = refOfRow(built, 4)
    val row5 = refOfRow(built, 5)
    assert(order(row4) < order(row5), "run 2 rows must come after run 1 rows in world order")
    assertEquals(ViewFingerprint.of(built.view).toString, fingerprintBeforeDeclaration)
  }

  test("an Unknown clock drops the world-time layer and the world order from the Sherlock view") {
    val built = SherlockAnnotationView
      .buildWith(atlas, WorldOrderInput.Unknown(WorldOrderAbsence.EditionNonlinear))
      .fold(e => fail(e.message), identity)
    assertEquals(built.view.worldOrder, None)
    assertEquals(
      built.view.adjacency(RelationLayer.WorldTime),
      Map.empty[SourceNodeRef, Map[SourceNodeRef, Double]]
    )
    assert(
      built.view.adjacency(RelationLayer.DiscourseSuccession).exists(_._2.nonEmpty),
      "the discourse clock is not the one declared unknown"
    )
    assertNotEquals(ViewFingerprint.of(built.view).toString, fingerprintBeforeDeclaration)
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

  test("the bridge states each node's embedding text: descriptions for leaves, labels for scenes") {
    val built = view(atlas)
    assertEquals(built.nodeTexts.size, 8)
    val byRef = built.nodeTexts.toMap
    atlas.rows.foreach { row =>
      assertEquals(byRef(refOfRow(built, row.row)), row.description)
    }
    built.groupByRef.foreach { case (ref, group) => assertEquals(byRef(ref), group.label) }
  }

  test("a recall clause about a distinctive annotated event maps to that row's media coordinate") {
    val built = view(atlas)
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
    assertEquals(
      built.segmentByRef.get(anchor).map(_.ordinal),
      Some(2),
      s"expected the red-door row, got ${anchor.key}"
    )
    built.media(anchor) match
      case MediaLocus.Extent(part, iv) =>
        assertEquals(part, "media-part-a")
        assertEquals((iv.start, iv.endExclusive), (25000L, 50000L))
      case other => fail(s"the anchored row must carry its exact media extent, got $other")
  }

  // Runs only when the pinned MiniLM artifacts are supplied, like WogDiagnosticSuite's neural leg:
  // the checksums inside OnnxSentenceModel.AllMiniLmL6V2 refuse any other bytes at open.
  test("the neural channel puts the red-door unit nearer its row than an unrelated row") {
    val supplied = for
      model <- sys.env.get("STORYMODEL4S_ONNX_MODEL")
      tokenizer <- sys.env.get("STORYMODEL4S_ONNX_TOKENIZER")
    yield OnnxSentenceArtifacts(Paths.get(model), Paths.get(tokenizer))
    assume(supplied.nonEmpty, "STORYMODEL4S_ONNX_MODEL / STORYMODEL4S_ONNX_TOKENIZER not set")
    val built = view(atlas)
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
        built.view.node(refOfRow(built, n)).get
      val toDoor = channel.semantic(unit, nodeAtRow(2)).toOption.getOrElse(fail("door abstained"))
      val toPopcorn =
        channel.semantic(unit, nodeAtRow(6)).toOption.getOrElse(fail("popcorn abstained"))
      assert(
        toDoor + 0.05 < toPopcorn,
        s"neural distance must separate the rows: door=$toDoor popcorn=$toPopcorn"
      )
    finally embedder.close()
  }
