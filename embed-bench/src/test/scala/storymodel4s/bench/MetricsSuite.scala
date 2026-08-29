package storymodel4s.bench

import munit.FunSuite

import storymodel4s.core.Checksum
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
