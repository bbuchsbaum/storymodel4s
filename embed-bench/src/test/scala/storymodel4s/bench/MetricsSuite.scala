package storymodel4s.bench

import munit.FunSuite

import storymodel4s.align.SourceNodeRef
import storymodel4s.core.{Checksum, SituationId}
import storymodel4s.features.Estimate
import storymodel4s.recall.RecallUnitId

/** Hand-checkable aggregation: story-macro means, coverage, missing-vs-zero, and a seeded bootstrap
  * that is a pure function of its inputs.
  */
class MetricsSuite extends FunSuite:
  private def u(i: Int) = RecallUnitId.unsafe(s"u$i")
  private def obs(caseId: String, name: String, values: Vector[Option[Double]]): CaseObservations =
    CaseObservations(
      caseId,
      Map(name -> values.zipWithIndex.map { case (v, i) => UnitObservation(u(i), v) })
    )
  private val inputs = Vector(Checksum.ofText("a"), Checksum.ofText("b"))

  test("perfect alignment: recall 1 and MRR 1 with full coverage") {
    val c1 = obs("s1", Metrics.Names.mrr, Vector(Some(1.0), Some(1.0)))
    val c2 = obs("s2", Metrics.Names.mrr, Vector(Some(1.0)))
    val m = Metrics.aggregate(Metrics.Names.mrr, Vector(c1, c2), inputs, seed = 1L)
    assertEquals(m.value, Estimate.observed(1.0))
    assertEquals(m.coverage.eligible, 3)
    assertEquals(m.coverage.observed, 3)
    assertEquals(m.stories, 2)
    assert(m.interval.exists(i => i.lower == 1.0 && i.upper == 1.0))
  }

  test("story-macro: a long story does not dominate — means of means, not a pooled mean") {
    val long = obs("long", "x", Vector.fill(9)(Some(0.0)))
    val short = obs("short", "x", Vector(Some(1.0)))
    val m = Metrics.aggregate("x", Vector(long, short), inputs, seed = 1L)
    assertEquals(m.value, Estimate.observed(0.5))
    assertEquals(m.coverage.eligible, 10)
  }

  test(
    "ineligible units count toward eligibility but not the mean; an all-missing metric is Missing"
  ) {
    val c = obs("s", "x", Vector(None, Some(0.5), None))
    val m = Metrics.aggregate("x", Vector(c), inputs, seed = 1L)
    assertEquals(m.value, Estimate.observed(0.5))
    assertEquals(m.coverage.eligible, 3)
    assertEquals(m.coverage.observed, 1)
    val none = Metrics.aggregate("x", Vector(obs("s", "x", Vector(None, None))), inputs, 1L)
    assert(!none.value.isObserved, none.render)
    assertEquals(none.stories, 0)
    assertEquals(none.interval, None)
  }

  test("bootstrap is deterministic under a seed and changes with it") {
    val cases = Vector(
      obs("a", "x", Vector(Some(0.2))),
      obs("b", "x", Vector(Some(0.9))),
      obs("c", "x", Vector(Some(0.4))),
      obs("d", "x", Vector(Some(0.7)))
    )
    val m1 = Metrics.aggregate("x", cases, inputs, seed = 42L)
    val m2 = Metrics.aggregate("x", cases, inputs, seed = 42L)
    val m3 = Metrics.aggregate("x", cases, inputs, seed = 43L)
    assertEquals(m1, m2)
    assert(m1.interval.nonEmpty)
    assert(m1.interval.exists(i => i.lower <= 0.55 && i.upper >= 0.55), m1.render)
    assertNotEquals(m1.interval, m3.interval)
    assertNotEquals(m1.receipt, m3.receipt)
  }

  test("the receipt names the inputs: different input checksums, different receipt") {
    val c = obs("s", "x", Vector(Some(1.0)))
    val a = Metrics.aggregate("x", Vector(c), Vector(Checksum.ofText("one")), 1L)
    val b = Metrics.aggregate("x", Vector(c), Vector(Checksum.ofText("two")), 1L)
    assertNotEquals(a.receipt, b.receipt)
    assertEquals(a.value, b.value)
  }

  test("every named metric is either source-anchor or open-world, never both") {
    val all = Metrics.Names.all
    assertEquals(all.distinct.size, all.size)
    assert(Metrics.Names.openWorld.subsetOf(all.toSet))
    assert(Metrics.Names.openWorld.forall(_.startsWith("open-world:")))
    assert(all.filterNot(Metrics.Names.openWorld).forall(n => !n.startsWith("open-world:")))
  }

  // --- the scoring arithmetic itself, not merely its aggregation ---

  private def ref(i: Int) = SourceNodeRef.Situation(SituationId.unsafe(s"n$i"))

  test("reciprocal rank is exactly 1, 1/2, 1/3 by position, and 0 when the target is absent") {
    val ranking = Vector(ref(1), ref(2), ref(3))
    assertEquals(Metrics.reciprocalRank(ranking, Set(ref(1))), 1.0)
    assertEquals(Metrics.reciprocalRank(ranking, Set(ref(2))), 0.5)
    assertEquals(Metrics.reciprocalRank(ranking, Set(ref(3))), 1.0 / 3.0)
    assertEquals(Metrics.reciprocalRank(ranking, Set(ref(9))), 0.0)
    // the FIRST target found wins, not the best-numbered one
    assertEquals(Metrics.reciprocalRank(ranking, Set(ref(2), ref(3))), 0.5)
    assertEquals(Metrics.reciprocalRank(Vector.empty, Set(ref(1))), 0.0)
  }

  test("strict recall at k is a step function at exactly k, with no off-by-one") {
    val ranking = Vector(ref(1), ref(2), ref(3), ref(4))
    assertEquals(Metrics.recallAt(ranking, Set(ref(3)), 2), 0.0)
    assertEquals(Metrics.recallAt(ranking, Set(ref(3)), 3), 1.0)
    assertEquals(Metrics.recallAt(ranking, Set(ref(1)), 1), 1.0)
    assertEquals(Metrics.recallAt(ranking, Set(ref(2)), 1), 0.0)
    assertEquals(Metrics.recallAt(ranking, Set(ref(1)), 0), 0.0)
    assertEquals(Metrics.recallAt(ranking, Set(ref(9)), 4), 0.0)
  }

  test("step direction is the sign of the move, so a route can be compared to gold") {
    assertEquals(Metrics.stepDirection(1, 5), 1)
    assertEquals(Metrics.stepDirection(5, 1), -1)
    assertEquals(Metrics.stepDirection(3, 3), 0)
    // magnitude must not leak into the comparison: a long jump forward and a short step forward
    // are the same DIRECTION, and a route metric that distinguished them would be scoring
    // distance, not route.
    assertEquals(Metrics.stepDirection(0, 99), Metrics.stepDirection(0, 1))
  }

  test("false gating abstains when the gate never saw the anchor") {
    // The anchor was not nominated, so the gate did not refuse it - the candidate generator missed
    // it, which strict-recall@k and candidate-burden already measure. Scoring 0.0 here would report
    // the gate as well behaved precisely when it was never exercised, and a channel that nominated
    // nothing would post a perfect false-gating score.
    assertEquals(Metrics.falseGate(None), None)
    // (The Some cases are exercised end to end by WogDiagnosticSuite: Admissibility construction is
    // private[align], so a real gate record is the only honest source of one.)
  }
