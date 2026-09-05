package storymodel4s.bench.video

import io.circe.parser.parse
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.{Checksum, StorySource}
import storymodel4s.recall.RecallSegmenter

/** Synthetic engineering controls, not human annotations or a narrative acceptance dataset. */
class StageTraceSuite extends FunSuite:
  private def recall(text: String) = RecallSegmenter.segment(
    StorySource.fromText(text).fold(e => fail(e.message), identity)
  )
  private val built = TimedSourceView
    .build(
      Vector("Alpha opens.", "Beta closes.", "Gamma waits.").zipWithIndex.map { (t, i) =>
        TimedSegment(i + 1, t, None, Some(TimedSegment.Group(i + 1, s"group ${i + 1}")))
      },
      WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied)
    )
    .fold(e => fail(e.message), identity)

  test("independent emissions obey an analytic odds ratio and retain ambiguity"):
    val p = StageTrace.localMass(Vector(0.0, math.log(3.0), math.log(3.0)), 1.0)
    assertEqualsDouble(p.head, 0.6, 1e-12)
    assertEqualsDouble(p(1), 0.2, 1e-12)
    assertEqualsDouble(p(2), 0.2, 1e-12)

  test("cost translation and state permutation preserve local evidence"):
    val c = Vector(0.2, 0.7, 2.3)
    val p = StageTrace.localMass(c, 0.15)
    StageTrace.localMass(c.map(_ + 100), 0.15).zip(p).foreach { (a, b) =>
      assertEqualsDouble(a, b, 1e-12)
    }
    StageTrace.localMass(c.reverse, 0.15).zip(p.reverse).foreach { (a, b) =>
      assertEqualsDouble(a, b, 1e-12)
    }

  test("invalid costs and temperature fail visibly"):
    intercept[IllegalArgumentException](StageTrace.localMass(Vector(Double.NaN), 1.0))
    intercept[IllegalArgumentException](StageTrace.localMass(Vector(0.0), Double.PositiveInfinity))
    intercept[IllegalArgumentException](StageTrace.localMass(Vector.empty, 1.0))

  test("one-unit HSMM is an independent oracle for the local comparator including external states"):
    val r = recall("Alpha opens.")
    assertEquals(r.ordered.size, 1)
    val c = CandidateGenerator(SemanticDistance.lexicalJaccard, perLevel = 3)
      .generate(r.ordered, built.view)
    val result = GraphHsmm
      .infer(r, built.view, c, DefaultLocalCostModel())
      .fold(e => fail(e.message), identity)
    val costs = result.costs(r.ordered.head.id).toVector.filterNot(_._2.excluded).sortBy(_._1.key)
    val p = StageTrace.localMass(costs.map(_._2.total), HsmmConfig.default.temperature)
    costs.zip(p).foreach { case ((state, _), mass) =>
      assertEqualsDouble(mass, result.posterior.rows.head(state), 1e-12)
    }
    assert(costs.exists(_._1.isExternal), "external alternatives were removed")
    val rendered = StageTrace.render(
      built,
      r.ordered,
      c,
      result,
      HsmmConfig.default,
      Vector.empty,
      result.posterior.rows.map(_.mapSource),
      Checksum.ofBytes(Array.emptyByteArray),
      None
    )
    val json = parse(rendered).fold(e => fail(e.message), identity)
    assertEquals(
      json.hcursor.get[String]("schema").toOption,
      Some("storymodel4s.bench.stage-trace/v1")
    )
    assert(!rendered.contains("Alpha opens"), "trace leaked prose")

  test("hard scene order cannot preserve a supported revisit; the control exposes that cost"):
    val r = recall("Alpha opens. Gamma waits. Alpha opens.")
    assertEquals(r.ordered.size, 3)
    val leaves = built.segmentByRef.keys.toVector.sortBy(_.key)
    val expected = Vector(leaves(0), leaves(2), leaves(0))
    val rows = r.ordered.zip(expected).map { (u, target) =>
      val masses: Map[AlignState, Double] = leaves.map { ref =>
        (AlignState.Source(ref): AlignState) -> (if ref == target then 0.9 else 0.05)
      }.toMap
      AlignmentRow.of(u.id, masses).fold(e => fail(e.message), identity)
    }
    assertEquals(rows.flatMap(_.mapSource), expected)
    val hard = MonotoneScene.decide(built, rows)
    assert(
      hard.map(_.anchor) != expected.map(Some(_)),
      "hard constraint unexpectedly preserved revisit"
    )
    assert(hard.forall(_.anchor.nonEmpty), "control discarded difficult units")
