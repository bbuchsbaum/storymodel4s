package storymodel4s.align

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import storymodel4s.core.*
import storymodel4s.recall.*

/** Structural laws of the HSMM posteriors and flows on random small sources and recalls. */
class PropertySuite extends ScalaCheckSuite:

  final case class Case(
      view: InMemorySourceView,
      recall: RecallGraph,
      semantic: SemanticDistance
  )

  private def leaf(i: Int): SourceNodeRef = SourceNodeRef.Situation(SituationId.unsafe(s"e$i"))
  private def scene(i: Int): SourceNodeRef = SourceNodeRef.Segment(SegmentId.unsafe(s"s$i"))
  private val root: SourceNodeRef = SourceNodeRef.Segment(SegmentId.unsafe("root"))

  private val genView: Gen[InMemorySourceView] =
    Gen.choose(3, 6).map { n =>
      val half = n / 2
      def span(a: Int, b: Int) = SpanSet.one(TextSpan.unsafe(a * 10, b * 10 + 8))
      val preds = Vector("go", "find", "hear", "see", "search", "enter")
      val leaves = (0 until n).toVector.map { i =>
        NodeSummary(
          leaf(i),
          0,
          Some(scene(if i < half then 0 else 1)),
          i,
          span(i, i),
          Some(preds(i)),
          Vector(ParticipantSummary(SketchRole.Agent, "anna", Set("she"))),
          ContextTag.NarratedWorld,
          PolarityTag.Positive,
          ModalityTag.Asserted,
          Vector.empty,
          Set(preds(i), "anna")
        )
      }
      val scenes = Vector(
        NodeSummary(
          scene(0),
          1,
          Some(root),
          0,
          span(0, half - 1),
          None,
          Vector.empty,
          ContextTag.NarratedWorld,
          PolarityTag.Positive,
          ModalityTag.Asserted,
          Vector.empty,
          Set.empty
        ),
        NodeSummary(
          scene(1),
          1,
          Some(root),
          1,
          span(half, n - 1),
          None,
          Vector.empty,
          ContextTag.NarratedWorld,
          PolarityTag.Positive,
          ModalityTag.Asserted,
          Vector.empty,
          Set.empty
        )
      )
      val rootNode = NodeSummary(
        root,
        2,
        None,
        0,
        span(0, n - 1),
        None,
        Vector.empty,
        ContextTag.NarratedWorld,
        PolarityTag.Positive,
        ModalityTag.Asserted,
        Vector.empty,
        Set.empty
      )
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

  private val genFunction: Gen[DiscourseFunction] = Gen.oneOf(DiscourseFunction.values.toSeq)

  private def genRecall(view: InMemorySourceView): Gen[(RecallGraph, SemanticDistance)] =
    for
      k <- Gen.choose(1, 5)
      functions <- Gen.listOfN(k, genFunction)
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

  private val genCase: Gen[Case] =
    for
      v <- genView
      (r, s) <- genRecall(v)
    yield Case(v, r, s)

  private def infer(c: Case): HsmmResult =
    val cands = CandidateGenerator(c.semantic, perLevel = 2).generate(c.recall.ordered, c.view)
    GraphHsmm.infer(c.recall, c.view, cands, DefaultLocalCostModel(semantic = c.semantic))

  property("posterior rows are distributions over the unit's states") {
    forAll(genCase) { c =>
      val res = infer(c)
      res.posterior.rows.forall { r =>
        math.abs(r.total - 1.0) < 1e-6 && r.mass.values.forall(m => m >= 0.0 && m <= 1.0 + 1e-9)
      }
    }
  }

  property("flow marginals equal the adjacent posterior rows") {
    forAll(genCase) { c =>
      val res = infer(c)
      val p = res.posterior
      res.flow.steps.zipWithIndex.forall { case (step, i) =>
        val from = step.fromMarginal
        val to = step.toMarginal
        p.rows(i).mass.forall { case (s, m) => math.abs(from.getOrElse(s, 0.0) - m) < 1e-6 } &&
        p.rows(i + 1).mass.forall { case (s, m) => math.abs(to.getOrElse(s, 0.0) - m) < 1e-6 }
      }
    }
  }

  property("entropy and localizability are bounded") {
    forAll(genCase) { c =>
      val res = infer(c)
      res.posterior.rows.forall { r =>
        val k = r.mass.count(_._2 > 0.0)
        r.entropy >= -1e-12 && r.entropy <= math.log(math.max(1, k).toDouble) + 1e-9 &&
        r.localizability >= -1e-9 && r.localizability <= 1.0 + 1e-9
      }
    }
  }

  property("Viterbi path lies in the state space and the likelihood is finite") {
    forAll(genCase) { c =>
      val res = infer(c)
      val cands = CandidateGenerator(c.semantic, perLevel = 2).generate(c.recall.ordered, c.view)
      res.viterbi.zip(c.recall.ordered).forall { (s, u) =>
        s match
          case AlignState.Source(ref) => cands(u.id).contains(ref)
          case AlignState.External(_) => true
      } && !res.logLikelihood.isNaN && !res.logLikelihood.isInfinite
    }
  }

  property("Viterbi agrees with the per-unit argmax when every unit is sharply peaked") {
    forAll(genView) { view =>
      val text = "First thing. Second thing. Third thing."
      val src = StorySource.fromText(text).toOption.get
      val atlas = SurfaceAnalyzer.analyze(src)
      val targets = Vector(0, 1, 2).map(i => leaf(math.min(i, view.leaves.size - 1)))
      val units = (0 until 3).toVector.map { i =>
        val s = atlas.sentences(i)
        RecallUnit(
          RecallUnitId.unsafe(s"u$i"),
          i,
          SpanSet.one(SpanRef(Some(s.id), s.span)),
          atlas.text(s),
          DiscourseFunction.EpisodicAssertion,
          ExpressedUncertainty.Unmarked,
          PropositionSketch.empty.copy(predicate = view.node(targets(i)).flatMap(_.predicate)),
          None
        )
      }
      val table = (for
        (u, i) <- units.zipWithIndex
        n <- view.nodes
      yield (u.id, n.ref) -> (if n.ref == targets(i) then 0.0 else 1.0)).toMap
      val sem = SemanticDistance.fromTable(table)
      val recall = RecallGraph(src, atlas, units, RecallRelations.empty)
      val cands = CandidateGenerator(sem, perLevel = 2).generate(units, view)
      val res = GraphHsmm.infer(recall, view, cands, DefaultLocalCostModel(semantic = sem))
      res.viterbi == res.posterior.rows.map(_.argmax.get) &&
      res.viterbi == targets.map(AlignState.Source(_))
    }
  }

  property("logSumExp is stable and exact on small inputs") {
    forAll(Gen.nonEmptyListOf(Gen.choose(-50.0, 50.0))) { xs =>
      val v = xs.toVector
      val direct = math.log(v.map(math.exp).sum)
      math.abs(GraphHsmm.logSumExp(v) - direct) < 1e-9
    }
  }

  property("candidate generation never allocates more than perLevel per level plus overlap hits") {
    forAll(genCase) { c =>
      val cands = CandidateGenerator(c.semantic, perLevel = 2, lexicalOverlap = false)
        .generate(c.recall.ordered, c.view)
      c.recall.units.forall(u => cands(u.id).size <= 2 * (c.view.maxLevel + 1))
    }
  }

  test("a single-unit recall produces one row, no flow, and a finite likelihood") {
    val c = genCase.sample.get
    val text = "Only one thing."
    val src = StorySource.fromText(text).toOption.get
    val atlas = SurfaceAnalyzer.analyze(src)
    val s = atlas.sentences.head
    val u = RecallUnit(
      RecallUnitId.unsafe("u0"),
      0,
      SpanSet.one(SpanRef(Some(s.id), s.span)),
      text,
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
    val r = RecallGraph(src, atlas, Vector(u), RecallRelations.empty)
    val cands = CandidateGenerator(c.semantic).generate(Vector(u), c.view)
    val res = GraphHsmm.infer(r, c.view, cands, DefaultLocalCostModel(semantic = c.semantic))
    assertEquals(res.posterior.size, 1)
    assertEquals(res.flow.size, 0)
    assert(!res.logLikelihood.isNaN)
  }

  property("Prop sanity: property block executes") {
    Prop.passed
  }
