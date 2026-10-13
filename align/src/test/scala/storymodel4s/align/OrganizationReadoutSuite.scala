package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.RecallUnitId

/** Exhaustive trajectories are an independent oracle for expectations and coherent extrema. */
class OrganizationReadoutSuite extends FunSuite:
  import OrganizationReadout.*
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def target(n: Int): Destination = Destination.Target(AnnaFixture.sit(s"organization-$n"))
  private def row(i: Int, ranks: Vector[Int]): Row = right(
    Row.of(
      RecallUnitId.unsafe(s"u$i"),
      ranks.map(n => target(n) -> (1.0 / ranks.size)).toMap,
      ranks.map(target)
    )
  )
  private def order(ranks: Vector[Int]): Order = right(
    Order.declared(ranks.distinct.map(n => AnnaFixture.sit(s"organization-$n") -> n.toDouble))
  )
  private def compute(sets: Vector[Vector[Int]]): Result = right(
    OrganizationReadout.compute(
      sets.zipWithIndex.map((ns, i) => row(i, ns)),
      order(sets.flatten),
      Dependence.DeclaredIndependentRows
    )
  )
  // This oracle compares integer ranks directly and never calls the production classifier or DP.
  private def counts(path: Vector[Int]): Vector[Int] =
    val pairs = path.zip(path.drop(1))
    Vector(
      pairs.count((a, b) => b < a),
      pairs.count((a, b) => b > a),
      pairs.count((a, b) => b == a)
    )
  private def paths(sets: Vector[Vector[Int]]): Vector[Vector[Int]] =
    sets.foldLeft(Vector(Vector.empty[Int]))((ps, ns) => ps.flatMap(p => ns.map(p :+ _)))
  private val categories = Vector(Category.Backward, Category.Forward, Category.SameTarget)

  test("point path has exact backward forward same counts and original denominator") {
    val r = compute(Vector(2, 7, 3, 8).map(n => Vector(n)))
    assertEquals(categories.map(r.counts), Vector(1.0, 2.0, 0.0))
    assertEquals(r.comparable, 3.0)
    assertEquals(r.backwardRate, Some(1.0 / 3.0))
    assertEquals(
      r.transitions.map(t => (t.left.value, t.right.value)),
      Vector(("u0", "u1"), ("u1", "u2"), ("u2", "u3"))
    )
    assertEquals(r.information, Information.DistinguishedRows)
  }
  test("uniform assignments retain conditional low information and ambiguity bounds") {
    val r = compute(Vector(Vector(2, 7), Vector(2, 7)))
    assertEquals(categories.map(r.counts), Vector(0.25, 0.25, 0.5))
    assertEquals(r.information, Information.UniformRows)
    assertEquals(r.bounds.get(Category.Backward).minimum, 0)
    assertEquals(r.bounds.get(Category.Backward).maximum, 1)
  }
  test("coherent middle assignment forbids the unattainable pairwise maximum of two") {
    val r = compute(Vector(Vector(2), Vector(1, 3), Vector(2)))
    assertEquals(r.bounds.get(Category.Backward).minimum, 1)
    assertEquals(r.bounds.get(Category.Backward).maximum, 1)
    assertEquals(r.bounds.get(Category.Forward).minimum, 1)
    assertEquals(r.bounds.get(Category.Forward).maximum, 1)
  }
  test("sparse DP and expected counts agree with complete independent enumeration") {
    val subsets = Vector(
      Vector(1),
      Vector(2),
      Vector(3),
      Vector(1, 2),
      Vector(1, 3),
      Vector(2, 3),
      Vector(1, 2, 3)
    )
    for a <- subsets; b <- subsets; c <- subsets do
      val sets = Vector(a, b, c)
      val all = paths(sets).map(counts)
      val r = compute(sets)
      categories.zipWithIndex.foreach { (category, i) =>
        assertEqualsDouble(r.counts(category), all.map(_(i)).sum.toDouble / all.size, 1e-12)
        assertEquals(r.bounds.get(category).minimum, all.map(_(i)).min)
        assertEquals(r.bounds.get(category).maximum, all.map(_(i)).max)
      }
  }
  test("missing middle retains two unresolved adjacencies and no direct observation") {
    val rows =
      Vector(row(0, Vector(2)), Row.unavailable(RecallUnitId.unsafe("u1")), row(2, Vector(7)))
    val r = right(
      OrganizationReadout.compute(rows, order(Vector(2, 7)), Dependence.DeclaredIndependentRows)
    )
    assertEquals(r.transitions.size, 2)
    assertEquals(r.counts(Category.Unresolved), 2.0)
    assertEquals(r.comparable, 0.0)
    assertEquals(r.backwardRate, None)
    assertEquals(r.bounds, None)
    assertEquals(r.measuredUnits, 2)
    assertEquals(r.measuredTransitions, 0)
  }
  test("external failed and missing chronology remain separate") {
    val ext = Destination.External(ExternalState.Intrusion)
    val rows = Vector(
      row(0, Vector(2)),
      right(Row.of(RecallUnitId.unsafe("u1"), Map(ext -> 1.0), Vector(ext))),
      Row.unavailable(RecallUnitId.unsafe("u2"), true)
    )
    val r = right(
      OrganizationReadout.compute(
        rows,
        right(Order.declared(Vector.empty)),
        Dependence.DeclaredIndependentRows
      )
    )
    assertEquals(r.counts(Category.External), 1.0)
    assertEquals(r.counts(Category.Failed), 1.0)
    val noOrder = right(
      OrganizationReadout.compute(
        Vector(row(0, Vector(2)), row(1, Vector(7))),
        right(Order.declared(Vector.empty)),
        Dependence.DeclaredIndependentRows
      )
    )
    assertEquals(noOrder.counts(Category.Incomparable), 1.0)
    assertEquals(noOrder.backwardRate, None)
  }
  test("equal row marginals with different joints give different transition quantities") {
    val a = row(0, Vector(2, 7)); val b = row(1, Vector(2, 7)); val o = order(Vector(2, 7))
    val same =
      right(Joint.of(a, b, Map((target(2), target(2)) -> 0.5, (target(7), target(7)) -> 0.5)))
    val cross =
      right(Joint.of(a, b, Map((target(2), target(7)) -> 0.5, (target(7), target(2)) -> 0.5)))
    val rs = Vector(same, cross).map(j =>
      right(
        OrganizationReadout.compute(Vector(a, b), o, Dependence.AdjacentJoints(Vector(j)), false)
      )
    )
    assertEquals(categories.map(rs.head.counts), Vector(0.0, 0.0, 1.0))
    assertEquals(categories.map(rs(1).counts), Vector(0.5, 0.5, 0.0))
    assert(
      OrganizationReadout.compute(Vector(a, b), o, Dependence.AdjacentJoints(Vector(same))).isLeft
    )
    assert(
      OrganizationReadout
        .compute(Vector(a, b), o, Dependence.AdjacentJoints(Vector.empty), false)
        .isLeft
    )
    val point = row(0, Vector(2))
    assert(
      OrganizationReadout
        .compute(Vector(point, b), o, Dependence.AdjacentJoints(Vector(same)), false)
        .isLeft
    )
  }
  test("nonfinite masses order entries duplicates and invalid marginal joints refuse") {
    val a = row(0, Vector(2)); val b = row(1, Vector(7))
    Vector(Double.NaN, Double.PositiveInfinity, -1.0).foreach { n =>
      assert(Row.of(a.unit, Map(target(2) -> n), Vector(target(2))).isLeft)
      assert(Order.declared(Vector(AnnaFixture.sit("organization-2") -> n)).isLeft == !n.isFinite)
    }
    assert(
      OrganizationReadout
        .compute(Vector(a, a), order(Vector(2)), Dependence.DeclaredIndependentRows)
        .isLeft
    )
    assert(Joint.of(a, b, Map((target(2), target(7)) -> 0.5)).isLeft)
    assert(Row.of(a.unit, Map(target(2) -> 1.0), Vector(target(7))).isLeft)
  }
  test("candidate storage permutation preserves quantities and bounds") {
    val sets = Vector(Vector(1, 2, 3), Vector(2, 3), Vector(1, 3))
    val a = compute(sets); val b = compute(sets.map(_.reverse))
    assertEquals(a.counts, b.counts)
    categories.foreach(c =>
      assertEquals(
        (a.bounds.get(c).minimum, a.bounds.get(c).maximum),
        (b.bounds.get(c).minimum, b.bounds.get(c).maximum)
      )
    )
  }
  test(
    "media order uses native parts preserves checked composition and refuses unlocated targets"
  ) {
    import SherlockShapedSource.*
    val ranks = nodes.map(n => n.ref -> n.discoursePosition.toDouble)
    val raw = right(Order.onSource(ranks, source(false), MeasurementCompatibility.Axis.Discourse))
    val composed =
      right(Order.onSource(ranks, source(true), MeasurementCompatibility.Axis.Discourse))
    assertEquals(
      category(Destination.Target(e(2)), Destination.Target(e(7)), raw),
      Category.Incomparable
    )
    assertEquals(
      category(Destination.Target(e(2)), Destination.Target(e(7)), composed),
      Category.Forward
    )
    assertEquals(
      category(Destination.Target(e(1)), Destination.Target(e(2)), composed),
      Category.Forward
    )
    assertEquals(
      category(Destination.Target(e(4)), Destination.Target(e(7)), raw),
      Category.Incomparable
    )
    assertEquals(
      category(Destination.Target(e(4)), Destination.Target(e(7)), composed),
      Category.Incomparable
    )
    val reversedRanks = right(
      Order.onSource(
        ranks.map((r, p) => r -> -p),
        source(true),
        MeasurementCompatibility.Axis.Discourse
      )
    )
    assertEquals(
      category(Destination.Target(e(2)), Destination.Target(e(7)), reversedRanks),
      Category.Forward
    )
  }
