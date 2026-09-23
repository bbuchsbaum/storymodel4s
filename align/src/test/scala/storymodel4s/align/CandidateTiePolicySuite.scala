package storymodel4s.align

import munit.FunSuite
import storymodel4s.features.Estimate
import storymodel4s.recall.{RecallUnit, RecallUnitId}

/** The strict candidate tie policy (ADR 0019 "Candidate tie policy"; Fray #55): checked budgets,
  * exact tie completion with dense ranks, a union-wide budget whose overflow withholds the level
  * and stays visible, uniform semantic levels recorded with their population, and refusal of
  * non-finite scores. Every expectation is recomputed from the distance table, not read back from
  * the generator.
  */
class CandidateTiePolicySuite extends FunSuite:
  import AnnaFixture.{costModel, recall, table, view}

  private val default = 0.9 // SemanticDistance.fromTable's value for unlisted pairs
  private val semantic = SemanticDistance.fromTable(table, default)

  private def config(perLevel: Int, budget: TieBudgetRequest, lexical: Boolean = false) =
    StrictCandidateConfig.of(perLevel, budget, lexical, None).fold(e => fail(e.message), identity)

  private def strict(c: StrictCandidateConfig, s: SemanticDistance = semantic): StrictCandidates =
    StrictCandidateGenerator(s, c)
      .generate(recall.ordered, view)
      .fold(e => fail(e.message), identity)

  private def distance(u: RecallUnitId, r: SourceNodeRef): Double = table.getOrElse((u, r), default)

  test("configuration is checked: perLevel and budget cannot bypass the guarantee") {
    assertEquals(
      StrictCandidateConfig.of(0, TieBudgetRequest.Unbounded, false, None).left.map(_.message),
      Left("perLevel must be positive, got 0")
    )
    assert(StrictCandidateConfig.of(3, TieBudgetRequest.AtMost(2), false, None).isLeft)
    assert(StrictCandidateConfig.of(3, TieBudgetRequest.AtMost(0), false, None).isLeft)
    assert(StrictCandidateConfig.of(3, TieBudgetRequest.AtMost(3), false, None).isRight)
  }

  test("tie completion keeps exactly the candidates at or below the k-th score, per level") {
    val perLevel = 2
    val out = strict(config(perLevel, TieBudgetRequest.Unbounded))
    var tiesSeen = 0
    for u <- recall.ordered; (level, nodes) <- view.byLevel do
      val ds = nodes.map(n => n.ref -> distance(u.id, n.ref)).sortBy(_._2)
      val expected =
        if ds.size <= perLevel then ds.map(_._1).toSet
        else
          val cutoff = ds(perLevel - 1)._2
          if ds.count(_._2 <= cutoff) > perLevel then tiesSeen += 1 // completion kept more than k
          ds.filter(_._2 <= cutoff).map(_._1).toSet
      val got = out
        .set(u.id)
        .set
        .nominations
        .filter(_.receipt.contains(s"level:$level"))
        .map(_.ref)
        .toSet
      assertEquals(got, expected, s"unit ${u.id.value} level $level")
    assert(tiesSeen > 0, "capacity to fail: the fixture must tie at some cut")
  }

  test("tied candidates share a dense rank; ranks carry no identifier order") {
    val out = strict(config(2, TieBudgetRequest.Unbounded))
    for u <- recall.ordered do
      val sem = out.set(u.id).set.nominations.filter(_.rawScore.nonEmpty)
      sem.groupBy(_.receipt).values.foreach { level =>
        val distinct = level.flatMap(_.rawScore).distinct.sorted
        level.foreach(n => assertEquals(n.rank, distinct.indexOf(n.rawScore.get)))
      }
  }

  test("a union over the budget withholds the level and records the overflow") {
    val perLevel = 2
    val out = strict(config(perLevel, TieBudgetRequest.AtMost(perLevel), lexical = true))
    var overflowSeen = 0
    for u <- recall.ordered; (level, nodes) <- view.byLevel do
      val ds = nodes.map(n => n.ref -> distance(u.id, n.ref)).sortBy(_._2)
      val semanticKept =
        if ds.size <= perLevel then ds.map(_._1).toSet
        else ds.filter(_._2 <= ds(perLevel - 1)._2).map(_._1).toSet
      val lexical = CandidateGenerator.lexicalHits(u, view).toSet.intersect(nodes.map(_.ref).toSet)
      val union = (semanticKept ++ lexical).size
      val set = out.set(u.id)
      val atLevel = set.set.nominations.filter(n => nodes.exists(_.ref == n.ref))
      if union > perLevel then
        overflowSeen += 1
        assert(set.overflow.exists(o => o.level == level && o.unionSize == union), s"$level")
        assert(atLevel.isEmpty, s"an overflowed level must be withheld, not truncated: $level")
      else assert(!set.overflow.exists(_.level == level))
    assert(overflowSeen > 0, "capacity to fail: some level must overflow")
  }

  test("a uniform semantic level is recorded with its scored population") {
    val flat = SemanticDistance.fromTable(Map.empty, default)
    val out = strict(config(2, TieBudgetRequest.Unbounded), flat)
    val u = recall.ordered.head.id
    val expected = view.byLevel.collect {
      case (level, nodes) if nodes.size >= 2 => (level, nodes.size)
    }.toSet
    assert(expected.nonEmpty)
    assertEquals(out.set(u).uniformSemantic.map(x => (x.level, x.scoredCount)).toSet, expected)
    assert(
      strict(config(2, TieBudgetRequest.Unbounded)).set(u).uniformSemantic.size < expected.size
    )
  }

  test("the uniform flag describes every scored candidate, not only the kept ones") {
    // A level whose two best candidates tie (so the KEPT set is uniform) while the scored
    // population is not: no flag may be raised for it.
    val (level, nodes) = view.byLevel.find(_._2.size >= 3).getOrElse(fail("need a level of 3+"))
    val u = recall.ordered.head.id
    val split = nodes.zipWithIndex.map((n, i) => (u, n.ref) -> (if i < 2 then 0.1 else 0.5)).toMap
    val out = strict(config(2, TieBudgetRequest.Unbounded), SemanticDistance.fromTable(split))
    val kept = out.set(u).set.nominations.filter(_.receipt.contains(s"level:$level"))
    assertEquals(
      kept.flatMap(_.rawScore).distinct,
      Vector(0.1),
      "capacity to fail: kept is uniform"
    )
    assert(!out.set(u).uniformSemantic.exists(_.level == level), out.set(u).uniformSemantic)
  }

  test("a non-finite score is refused, not tied or dropped") {
    val poison = view.nodes.head.ref
    val nan = new SemanticDistance:
      def apply(unit: RecallUnit, node: NodeSummary): Estimate[Double] =
        if node.ref == poison then Estimate.observed(Double.NaN) else semantic(unit, node)
    val refused = StrictCandidateGenerator(nan, config(2, TieBudgetRequest.Unbounded))
      .generate(recall.ordered, view)
    assert(refused.left.exists(_.isInstanceOf[CandidateRefusal.NonFiniteScore]), refused)
  }

  test("evidence identity binds the tie policy, the budget and the overflow") {
    val unbounded = strict(config(2, TieBudgetRequest.Unbounded))
    val generous = strict(config(2, TieBudgetRequest.AtMost(1000)))
    assertEquals(unbounded.candidates, generous.candidates, "same nominations, different budget")
    def ev(s: StrictCandidates) =
      LocalEvidence.compute(recall, view, s, costModel).fold(e => fail(e.message), identity)
    val plain = LocalEvidence
      .compute(recall, view, unbounded.candidates, costModel, gate = true)
      .fold(e => fail(e.message), identity)
    assertNotEquals(ev(unbounded).identity, ev(generous).identity)
    assertNotEquals(ev(unbounded).identity, plain.identity)
    assertEquals(plain.provenance, CandidateProvenance.Unattested)
    val tight = ev(strict(config(2, TieBudgetRequest.AtMost(2), lexical = true)))
    val strictProv = tight.provenance match
      case s: CandidateProvenance.Strict => s
      case other                         => fail(s"expected strict provenance, got $other")
    assert(recall.ordered.indices.exists(strictProv.overflowed), "overflow must reach evidence")
  }
