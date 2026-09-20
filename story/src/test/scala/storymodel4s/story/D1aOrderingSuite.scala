package storymodel4s.story

import munit.FunSuite
import storymodel4s.core.*

class D1aOrderingSuite extends FunSuite:
  private val built = Small.build(3, 1)
  private val bundle = SourceBundle.writtenText(built.source).toOption.get
  private val ids = built.situations
  private def withSupport(node: SituationNode, spans: SpanSet): SituationNode = node match
    case SituationNode.Event(n) => SituationNode.Event(n.copy(support = TypedSupport.Text(spans)))
    case SituationNode.State(n) => SituationNode.State(n.copy(support = TypedSupport.Text(spans)))
  private def spans(parts: (Int, Int)*): SpanSet =
    SpanSet.of(parts.map((a, b) => SpanRef(TextSpan.unsafe(a, b)))).get
  private def graph(supports: Vector[SpanSet]): NarrativeGraph =
    built.graph.copy(situations =
      ids
        .zip(supports)
        .map((id, support) => id -> withSupport(built.graph.situations(id), support))
        .toMap
    )
  private def draft(g: NarrativeGraph) = StoryModel
    .draft(built.source, built.atlas, g, built.hierarchy, DiscourseTrajectory(Vector.empty))
    .fold(error => fail(error.message), identity)

  test("accepting control: projection order uses start then full hull end then id"):
    val g = graph(Vector(spans(1 -> 2, 90 -> 100), spans(1 -> 50), spans(0 -> 0)))
    val expected = Vector(ids(2), ids(1), ids(0))
    assertEquals(g.discourseOrderOn(bundle), Right(expected))
    val model = draft(g)
    assertEquals(model.discourseOrder, expected)
    assertEquals(model.discoursePosition, expected.zipWithIndex.toMap)
    assertEquals(model.situationsByContext(built.world), expected)
    assertEquals(model.situationsWithin(built.world), expected)
    assertEquals(model.situationsByEntity(built.entities.head), expected)
    assertEquals(model.situationsCovering(TextSpan.unsafe(1, 2)), Vector(ids(1), ids(0)))

  test("equal projected hulls use id independent of map insertion order"):
    val g = graph(Vector.fill(3)(spans(1 -> 4)))
    val expected = ids.sorted
    assertEquals(g.discourseOrderOn(bundle), Right(expected))
    assertEquals(
      g.copy(situations = g.situations.toVector.reverse.toMap).discourseOrderOn(bundle),
      Right(expected)
    )

  test("internal graph copy recomputes every ordered model query"):
    val before = draft(graph(Vector(spans(1 -> 2), spans(3 -> 4), spans(5 -> 6))))
    val changed = graph(Vector(spans(5 -> 6), spans(3 -> 4), spans(1 -> 2)))
    val after =
      before.copy[ModelStatus.Draft](graph = changed).fold(error => fail(error.message), identity)
    val expected = ids.reverse
    assertEquals(before.discourseOrder, ids)
    assertEquals(after.graph, changed)
    assertEquals(after.discourseOrder, expected)
    assertEquals(after.discoursePosition, expected.zipWithIndex.toMap)
    assertEquals(after.situationsByEntity(built.entities.head), expected)
    assertEquals(after.situationsByContext(built.world), expected)
    assertEquals(after.situationsWithin(built.world), expected)
    assertEquals(after.situationsCovering(TextSpan.unsafe(0, 7)), expected)

  test("out-of-text support is admitted as Draft and diagnosed by validation"):
    val length = built.source.canonicalText.length
    val model =
      draft(graph(Vector(spans(0 -> 1), spans(1 -> 2), spans((length + 1) -> (length + 2)))))
    assert(StoryValidator.validate(model).report.violations.exists(_.law == "support.in-text"))

  test("empty graph still refuses non-text ordering axis"):
    val film = SourceBundle
      .filmEdition(
        EditionId.unsafe("order-film"),
        Checksum.ofText("film"),
        0L,
        100L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get
    val empty = built.graph.copy(situations = Map.empty)
    assertEquals(empty.discourseOrderOn(bundle), Right(Vector.empty))
    assert(empty.discourseOrderOn(film).isLeft)
