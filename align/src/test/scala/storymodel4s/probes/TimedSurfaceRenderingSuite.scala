package storymodel4s.probes

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.align.bridge.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Consumer witnesses keep timed payloads tied to the closed timed-source inventory. */
class TimedSurfaceRenderingSuite extends FunSuite:
  private def value[A, E](result: Either[E, A]): A =
    result.fold(error => fail(error.toString), identity)

  private val bundle = SourceBundle
    .filmEdition(
      EditionId.unsafe("timed-rendering-fixture"),
      Checksum.ofText("timed-rendering-picture"),
      0L,
      100L,
      RationalTimebase.Millisecond
    )
    .toOption
    .get
  private val artifact = Checksum.ofText("timed-rendering-annotation")

  private def locus(start: Long) =
    MediaLocus.Extent(
      "part",
      PlaybackInterval.on(bundle.primaryAxis, start, start + 10L).toOption.get
    )

  private def timed(
      firstPayload: Option[String] = Some("first override"),
      firstStart: Long = 0L,
      groupLabel: String = "scene label"
  ): TimedSourceView =
    val group = TimedSegment.Group(
      1,
      groupLabel,
      Some("scene override"),
      Some("fixed lexical group declaration")
    )
    value(
      TimedSourceView.of(
        Vector(
          TimedSegment(
            1,
            "first text",
            Some(locus(firstStart)),
            Some(group),
            embedText = firstPayload
          ),
          TimedSegment(2, "second text", Some(locus(10L)), Some(group))
        ),
        Map("part" -> bundle),
        artifact
      )
    )

  private def recall: RecallGraph[Checked] =
    val source = value(StorySource.fromText("remembered"))
    val atlas = SurfaceAnalyzer.analyze(source)
    val unit = RecallUnit(
      RecallUnitId.unsafe("timed-rendering-recall"),
      0,
      SpanSet.one(TextSpan.unsafe(0, 10)),
      "remembered",
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
    value(RecallGraph.validated(source, atlas, Vector(unit), RecallRelations.empty).toEither)

  test("prepareTimed exposes every checked leaf and group with their checked refs") {
    val source = timed()
    val context = value(SurfaceRendering.prepareTimed(recall, source))
    assertEquals(context.policy, SurfaceRendering.TimedPolicy)
    assertEquals(context.queries.size, 1)
    assertEquals(context.documents.map(_._1), source.nodes.map(_.ref))
    assert(context.binds(recall, source))
    assertEquals(
      context.documents.map(_._2.text),
      Vector("first override", "second text", "scene override")
    )
    val leaf = source.nodes.find(_.level == 0).get.ref
    val groupRef = source.nodes.find(_.level == 1).get.ref
    val leafPair = value(context.pair(recall.units.head.id, leaf))
    val groupPair = value(context.pair(recall.units.head.id, groupRef))
    assertEquals(leafPair.targetText, "first override")
    assertEquals(groupPair.targetText, "scene override")
    assertEquals(leafPair.policy, SurfaceRendering.TimedPolicy)
    assertEquals(leafPair.query.policy, SurfaceRendering.TimedPolicy)
    assertEquals(leafPair.document.policy, SurfaceRendering.TimedPolicy)
  }

  test("timed override payload changes context identity without changing checked target refs") {
    val original = timed(Some("first override"))
    val changed = timed(Some("different override"))
    val a = value(SurfaceRendering.prepareTimed(recall, original))
    val b = value(SurfaceRendering.prepareTimed(recall, changed))
    assertEquals(a.documents.map(_._1), b.documents.map(_._1))
    assertNotEquals(a, b)
    assertEquals(a.documents.head._2.text, "first override")
    assertEquals(b.documents.head._2.text, "different override")
  }

  test("same visible timed payload with differing clocks remains a distinct context") {
    val a = value(SurfaceRendering.prepareTimed(recall, timed(firstStart = 0L)))
    val b = value(SurfaceRendering.prepareTimed(recall, timed(firstStart = 20L)))
    assertEquals(a.documents.map(_._2.text), b.documents.map(_._2.text))
    assertNotEquals(a, b)
  }

  test(
    "non-rendered timed declarations remain context identity even with the same view and payload"
  ) {
    val a = value(SurfaceRendering.prepareTimed(recall, timed(groupLabel = "scene label")))
    val b = value(SurfaceRendering.prepareTimed(recall, timed(groupLabel = "renamed scene")))
    assertEquals(ViewFingerprint.of(a.source), ViewFingerprint.of(b.source))
    assertEquals(a.source.nodes, b.source.nodes)
    assertEquals(a.representation.digest, b.representation.digest)
    assertEquals(a.documents.map(_._2.text), b.documents.map(_._2.text))
    assertNotEquals(a, b)
  }

  test("unpaired Unicode in a timed rendering payload is refused") {
    val invalid = timed(Some("bad\uD800"))
    assertEquals(
      SurfaceRendering.prepareTimed(recall, invalid),
      Left(SurfaceRenderingRefusal.InvalidUnicode)
    )
  }
