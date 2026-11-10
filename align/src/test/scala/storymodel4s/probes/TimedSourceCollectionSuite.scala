package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*
import storymodel4s.align.bridge.*
import storymodel4s.core.*
import storymodel4s.recall.*

/** Same-shape control for the constructor probe: public, so the probe's failure is about access. */
final class OpenTimedControl(val segments: Vector[TimedSegment], val artifact: Checksum)

/** The collection level and the target sets of a timed source, seen from outside `align`. */
class TimedSourceCollectionSuite extends FunSuite:
  private def bundle(name: String): SourceBundle = SourceBundle
    .filmEdition(
      EditionId.unsafe(s"collection-fixture-$name"),
      Checksum.ofText(s"collection-fixture-picture-$name"),
      0L,
      1000L,
      RationalTimebase.Millisecond
    )
    .toOption
    .get
  private val parts = Map("a" -> bundle("a"), "b" -> bundle("b"))
  private val artifact = Checksum.ofText("collection-fixture-annotation")

  private def locus(part: String, start: Long) =
    MediaLocus.Extent(
      part,
      PlaybackInterval.on(parts(part).primaryAxis, start, start + 10L).toOption.get
    )

  private val first = TimedSegment.Collection(1, "Lantern film")
  private val second = TimedSegment.Collection(2, "Cartoon")
  private val third = TimedSegment.Collection(3, "Split film")
  private def group(n: Int) = TimedSegment.Group(n, s"scene $n")

  private def row(
      ordinal: Int,
      g: Option[Int],
      c: Option[TimedSegment.Collection],
      part: Option[String] = Some("a"),
      target: Boolean = true
  ): TimedSegment =
    TimedSegment(
      ordinal,
      s"moment $ordinal",
      part.map(locus(_, ordinal * 10L)),
      g.map(group),
      collection = c,
      target = target
    )

  /** Group 1 mixes a target and a non-target row; group 2 and all of collection 2 are not targets;
    * collection 3 has one group in each part.
    */
  private val rows = Vector(
    row(1, Some(1), Some(first)),
    row(2, Some(1), Some(first), target = false),
    row(3, Some(2), Some(first), target = false),
    row(4, Some(3), Some(second), part = Some("b"), target = false),
    row(5, Some(3), Some(second), part = Some("b"), target = false),
    row(6, Some(4), Some(third)),
    row(7, Some(5), Some(third), part = Some("b"))
  )

  private def build(segments: Vector[TimedSegment]): Either[MappingRefusal, TimedSourceView] =
    TimedSourceView.of(segments, parts, artifact)
  private def refusal(detail: String) = Left(MappingRefusal.InvalidValue("timed-source", detail))
  private lazy val view = build(rows).fold(e => fail(e.message), identity)
  private def segment(prefix: String, n: Int): SourceNodeRef =
    SourceNodeRef.Segment(SegmentId.unsafe(s"$prefix:${artifact.hex}:$n"))

  test("new fields default to no collection and a target row") {
    val plain = TimedSegment(1, "text", None)
    assertEquals(plain.collection, None)
    assertEquals(plain.target, true)
  }

  test("a source without collections has no level-2 node and no group parent") {
    val flat = build(rows.map(_.copy(collection = None))).fold(e => fail(e.message), identity)
    assertEquals(flat.nodes.map(_.level).distinct.sorted, Vector(0, 1))
    assertEquals(flat.nodes.filter(_.level == 1).flatMap(_.parent), Vector.empty)
    assertEquals(flat.collectionRefs, Map.empty[Int, SourceNodeRef])
    assertEquals(flat.targetCollections, Vector.empty)
    assertEquals(flat.groupRefs.keySet, Set(1, 2, 3, 4, 5))
  }

  test("collections are level-2 nodes named by artifact and ordinal, and parent their groups") {
    assertEquals(
      view.collectionRefs,
      Vector(1, 2, 3).map(n => n -> segment("timed-collection", n)).toMap
    )
    assertEquals(view.groupRefs, (1 to 5).map(n => n -> segment("timed-group", n)).toMap)
    val level2 = view.nodes.filter(_.level == 2)
    assertEquals(level2.map(_.ref), Vector(1, 2, 3).map(view.collectionRefs))
    assertEquals(level2.map(_.discoursePosition), Vector(0, 1, 2))
    assertEquals(level2.flatMap(_.parent), Vector.empty)
    val parents =
      view.groupRefs.map((n, ref) => n -> view.node(ref).flatMap(_.parent)).toMap
    assertEquals(
      parents,
      Map(1 -> 1, 2 -> 1, 3 -> 2, 4 -> 3, 5 -> 3).map((g, c) => g -> view.collectionRefs.get(c))
    )
    assertEquals(view.nodes.count(_.level == 0), 7)
  }

  test("a collection node spans its rows and carries its label's and its groups' lemmas") {
    val node = view.node(view.collectionRefs(1)).get
    val leaves = view.nodes.filter(_.level == 0).sortBy(_.discoursePosition)
    val span = node.scoringPosition.get.spans.minSpan
    assertEquals(span.start, leaves(0).scoringPosition.get.spans.minSpan.start)
    assertEquals(span.endExclusive, leaves(2).scoringPosition.get.spans.minSpan.endExclusive)
    assert(node.lemmas.contains("lantern"), node.lemmas.toString)
    val groups = Vector(1, 2).map(n => view.node(view.groupRefs(n)).get)
    assert(groups.flatMap(_.lemmas).toSet.subsetOf(node.lemmas))
    assert(!view.node(view.collectionRefs(2)).get.lemmas.contains("lantern"))
  }

  test("a group is a target if any row is, and a collection if any group is") {
    assertEquals(view.targetGroups, Vector(1, 4, 5).map(view.groupRefs))
    assertEquals(view.targetCollections, Vector(1, 3).map(view.collectionRefs))
  }

  test("rows, groups and collections that are not targets are still nodes of the source") {
    val refs = view.nodes.map(_.ref).toSet
    assert(refs.contains(view.groupRefs(2)))
    assert(refs.contains(view.groupRefs(3)))
    assert(refs.contains(view.collectionRefs(2)))
    assertEquals(view.nodes.size, 7 + 5 + 3)
    assertEquals(view.representation.targets.size, 15)
  }

  test("target marks do not move a source's nodes or representation digest") {
    val all = build(rows.map(_.copy(target = true))).fold(e => fail(e.message), identity)
    assertEquals(all.representation.digest, view.representation.digest)
    assertEquals(all.targetGroups.size, 5)
    assertNotEquals(all, view)
  }

  test("a collection is located only when all its located rows sit in one part") {
    def located(ref: SourceNodeRef): Boolean =
      view.representation.target(ref).get.sourceSupport.isInstanceOf[SourceSupportStatus.Located]
    assertEquals(located(view.collectionRefs(1)), true)
    assertEquals(located(view.collectionRefs(2)), true)
    assertEquals(located(view.collectionRefs(3)), false)
    assertEquals(located(view.groupRefs(4)), true)
    assertEquals(located(view.groupRefs(5)), true)
    val bare = build(rows.map(_.copy(locus = None))).fold(e => fail(e.message), identity)
    assertEquals(
      bare.representation
        .target(bare.collectionRefs(1))
        .get
        .sourceSupport
        .isInstanceOf[SourceSupportStatus.Located],
      false
    )
  }

  test("a group whose rows name two collections refuses") {
    assertEquals(
      build(rows.updated(1, rows(1).copy(collection = Some(second)))),
      refusal(TimedSourceView.GroupInSeveralCollections)
    )
  }

  test("a group with a collection on some rows only refuses") {
    assertEquals(
      build(rows.updated(1, rows(1).copy(collection = None))),
      refusal(TimedSourceView.GroupInSeveralCollections)
    )
  }

  test("a source where only some groups have a collection refuses") {
    assertEquals(
      build(rows.updated(2, rows(2).copy(collection = None))),
      refusal(TimedSourceView.MixedCollections)
    )
  }

  test("a row outside every group cannot name a collection") {
    assertEquals(
      build(rows :+ row(8, None, Some(first))),
      refusal(TimedSourceView.CollectionWithoutGroup)
    )
    assert(build(rows :+ row(8, None, None)).isRight, "control: an ungrouped row is lawful")
  }

  test("collection ordinals are positive and unique, and labels are not blank") {
    def withCollection(c: TimedSegment.Collection) =
      build(rows.updated(5, rows(5).copy(collection = Some(c))))
    assert(withCollection(TimedSegment.Collection(9, "Other film")).isRight, "control")
    Vector(
      TimedSegment.Collection(0, "Other film"),
      TimedSegment.Collection(-1, "Other film"),
      TimedSegment.Collection(9, "  "),
      TimedSegment.Collection(1, "A second label for ordinal 1")
    ).foreach(c => assertEquals(withCollection(c), refusal(TimedSourceView.CollectionInvalid)))
  }

  test("embedding-surface rendering refuses a source with collections; it has no film payload") {
    val transcript = StorySource.fromText("remembered").toOption.get
    val unit = RecallUnit(
      RecallUnitId.unsafe("collection-fixture-recall"),
      0,
      SpanSet.one(TextSpan.unsafe(0, 10)),
      "remembered",
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
    val recall = RecallGraph
      .validated(
        transcript,
        SurfaceAnalyzer.analyze(transcript),
        Vector(unit),
        RecallRelations.empty
      )
      .toOption
      .get
    val flat = build(rows.map(_.copy(collection = None))).fold(e => fail(e.message), identity)
    assert(SurfaceRendering.prepareTimed(recall, flat).isRight, "control: no collections")
    assertEquals(
      SurfaceRendering.prepareTimed(recall, view).left.toOption,
      Some(SurfaceRenderingRefusal.SourceViewBinding)
    )
  }

  test("the view that carries the target sets cannot be constructed outside its factory") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.probes.*
new OpenTimedControl(???, ???)"""
      ),
      Nil
    )
    val errors = typeCheckErrors(
      """import storymodel4s.align.bridge.*
new TimedSourceView(???, ???, ???, ???, ???, ???)"""
    )
    assertEquals(errors.size, 1, errors.toString)
    assert(errors.head.message.contains("cannot be accessed"), errors.head.message)
  }
