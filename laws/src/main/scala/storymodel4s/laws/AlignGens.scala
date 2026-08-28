package storymodel4s.laws

import org.scalacheck.Gen
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*

/** Generators for small in-memory sources and recalls with table-driven semantic distances. */
object AlignGens:
  final case class Case(view: InMemorySourceView, recall: RecallGraph, semantic: SemanticDistance)

  def leaf(i: Int): SourceNodeRef = SourceNodeRef.Situation(SituationId.unsafe(s"e$i"))
  def scene(i: Int): SourceNodeRef = SourceNodeRef.Segment(SegmentId.unsafe(s"s$i"))
  val root: SourceNodeRef = SourceNodeRef.Segment(SegmentId.unsafe("root"))

  private def summary(
      ref: SourceNodeRef,
      level: Int,
      parent: Option[SourceNodeRef],
      position: Int,
      support: SpanSet,
      predicate: Option[String],
      lemmas: Set[String]
  ): NodeSummary =
    NodeSummary(
      ref,
      level,
      parent,
      position,
      support,
      predicate,
      predicate.toVector.map(_ => ParticipantSummary(SketchRole.Agent, "anna", Set("she"))),
      ContextTag.NarratedWorld,
      PolarityTag.Positive,
      ModalityTag.Asserted,
      Vector.empty,
      lemmas
    )

  /** A chain of 3–6 leaves under two scenes under a root, with discourse, world-time, and one
    * causal edge.
    */
  val view: Gen[InMemorySourceView] =
    Gen.choose(3, 6).map { n =>
      val half = n / 2
      def span(a: Int, b: Int) = SpanSet.one(TextSpan.unsafe(a * 10, b * 10 + 8))
      val preds = Vector("go", "find", "hear", "see", "search", "enter")
      val leaves = (0 until n).toVector.map { i =>
        summary(
          leaf(i),
          0,
          Some(scene(if i < half then 0 else 1)),
          i,
          span(i, i),
          Some(preds(i)),
          Set(preds(i), "anna")
        )
      }
      val scenes = Vector(
        summary(scene(0), 1, Some(root), 0, span(0, half - 1), None, Set.empty),
        summary(scene(1), 1, Some(root), 1, span(half, n - 1), None, Set.empty)
      )
      val rootNode = summary(root, 2, None, 0, span(0, n - 1), None, Set.empty)
      val chain = (0 until n - 1).toVector.map(i => (leaf(i), leaf(i + 1), 1.0))
      InMemorySourceView(
        leaves ++ scenes :+ rootNode,
        Map(
          RelationLayer.DiscourseSuccession -> chain,
          RelationLayer.WorldTime -> chain,
          RelationLayer.Causal -> chain.take(1)
        ),
        Some((0 until n).map(i => leaf(i) -> i).toMap),
        n * 10
      )
    }

  val discourseFunction: Gen[DiscourseFunction] = Gen.oneOf(DiscourseFunction.values.toSeq)

  /** A recall of 1–5 units with random functions, predicates, polarities, and distances. */
  def recall(view: InMemorySourceView): Gen[(RecallGraph, SemanticDistance)] =
    for
      k <- Gen.choose(1, 5)
      functions <- Gen.listOfN(k, discourseFunction)
      preds <- Gen.listOfN(k, Gen.option(Gen.oneOf("go", "find", "hear", "see")))
      polarities <- Gen.listOfN(k, Gen.oneOf(PolarityTag.values.toSeq))
      dists <- Gen.listOfN(k * view.nodes.size, Gen.choose(0.0, 1.0))
    yield
      val text = (0 until k).map(i => s"Unit number $i happened.").mkString(" ")
      val src = StorySource.fromText(text).toOption.get
      val atlas = SurfaceAnalyzer.analyze(src)
      val units = (0 until k).toVector.map { i =>
        val s = atlas.sentences(i)
        RecallUnit(
          RecallUnitId.unsafe(s"u$i"),
          i,
          SpanSet.one(SpanRef(Some(s.id), s.span)),
          atlas.text(s),
          functions(i),
          ExpressedUncertainty.Unmarked,
          PropositionSketch.empty.copy(predicate = preds(i), polarity = polarities(i)),
          None
        )
      }
      val table = (for
        (u, i) <- units.zipWithIndex
        (n, j) <- view.nodes.zipWithIndex
      yield (u.id, n.ref) -> dists(i * view.nodes.size + j)).toMap
      (RecallGraph(src, atlas, units, RecallRelations.empty), SemanticDistance.fromTable(table))

  val alignCase: Gen[Case] =
    for
      v <- view
      (r, s) <- recall(v)
    yield Case(v, r, s)

  def infer(c: Case): HsmmResult =
    val cands = CandidateGenerator(c.semantic, perLevel = 2).generate(c.recall.ordered, c.view)
    GraphHsmm
      .infer(c.recall, c.view, cands, DefaultLocalCostModel(semantic = c.semantic))
      .fold(e => throw new IllegalStateException(e.message), identity)
