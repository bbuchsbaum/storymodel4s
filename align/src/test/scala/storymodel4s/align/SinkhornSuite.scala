package storymodel4s.align

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class SinkhornSuite extends ScalaCheckSuite:

  private val genCost: Gen[Vector[Vector[Double]]] =
    for
      m <- Gen.choose(2, 4)
      n <- Gen.choose(2, 5)
      xs <- Gen.listOfN(m * n, Gen.choose(0.0, 1.0))
    yield xs.toVector.grouped(n).toVector

  private def solve(
      c: Vector[Vector[Double]],
      a: Vector[Double],
      b: Vector[Double],
      cfg: SinkhornConfig = SinkhornConfig()
  ): SinkhornResult =
    UnbalancedSinkhorn.solve(c, a, b, cfg).fold(e => throw new AssertionError(e.message), identity)

  property("the plan is non-negative and finite") {
    forAll(genCost) { c =>
      val m = c.size
      val n = c.head.size
      val res = solve(c, Vector.fill(m)(1.0), Vector.fill(n)(m.toDouble / n))
      res.plan.flatten.forall(x => x >= 0.0 && !x.isNaN && !x.isInfinite)
    }
  }

  property("marginal deviation decreases monotonically as the KL penalty grows") {
    forAll(genCost) { c =>
      val m = c.size
      val n = c.head.size
      val a = Vector.fill(m)(1.0)
      val b = Vector.fill(n)(m.toDouble / n)
      val devs = Vector(0.05, 1.0, 50.0).map { rho =>
        val r = solve(c, a, b, SinkhornConfig(0.1, rho, rho, 300, 1e-9))
        r.rowDeviation(a) + r.colDeviation(b)
      }
      devs(0) >= devs(1) - 1e-6 && devs(1) >= devs(2) - 1e-6
    }
  }

  property("iteration stops at convergence and reports it") {
    forAll(genCost) { c =>
      val m = c.size
      val n = c.head.size
      val r = solve(
        c,
        Vector.fill(m)(1.0),
        Vector.fill(n)(m.toDouble / n),
        SinkhornConfig(0.1, 1.0, 1.0, 1000, 1e-8)
      )
      r.converged && r.iterations < 1000 && r.iterations >= 1
    }
  }

  test("a zero row penalty leaves rows unconstrained without producing NaN") {
    val c = Vector(Vector(0.1, 0.9), Vector(0.8, 0.2))
    val r = solve(c, Vector(1.0, 0.0), Vector(1.0, 1.0), SinkhornConfig(0.1, 0.0, 1.0, 100))
    assert(r.plan.flatten.forall(x => !x.isNaN && x >= 0.0), r.plan.toString)
    val r2 = solve(c, Vector(1.0, 0.0), Vector(1.0, 1.0), SinkhornConfig(0.1, 1.0, 1.0, 100))
    assert(r2.plan(1).forall(_ == 0.0), "a zero target mass forces the row to zero")
  }

  test("size mismatches and bad configs are typed errors") {
    assert(UnbalancedSinkhorn.solve(Vector(Vector(0.1)), Vector(1.0, 1.0), Vector(1.0)).isLeft)
    assert(
      UnbalancedSinkhorn
        .solve(Vector(Vector(0.1)), Vector(1.0), Vector(1.0), SinkhornConfig(epsilon = 0.0))
        .isLeft
    )
  }

  test("invalid scale parameters are InvalidConfig and name the field") {
    val unnamed = SinkhornSuite.scaleCases.flatMap { case (label, cfg) =>
      UnbalancedSinkhorn.solve(
        SinkhornSuite.probeCost,
        SinkhornSuite.probeA,
        SinkhornSuite.probeB,
        cfg
      ) match
        case Left(AlignError.InvalidConfig("SinkhornConfig", detail)) if detail.contains(label) =>
          None
        case other => Some(s"$label -> $other")
    }
    assertEquals(unnamed, Vector.empty)
    val zeroRho = UnbalancedSinkhorn.solve(
      SinkhornSuite.probeCost,
      SinkhornSuite.probeA,
      SinkhornSuite.probeB,
      SinkhornConfig(rhoRows = 0.0)
    )
    assert(zeroRho.exists(_.plan.flatten.exists(_ > 0.0)), s"zero rho is lawful: $zeroRho")
  }

  test("invalid stopping parameters are InvalidConfig and name the field") {
    val unnamed = SinkhornSuite.stopCases.flatMap { case (label, cfg) =>
      UnbalancedSinkhorn.solve(
        SinkhornSuite.probeCost,
        SinkhornSuite.probeA,
        SinkhornSuite.probeB,
        cfg
      ) match
        case Left(AlignError.InvalidConfig("SinkhornConfig", detail)) if detail.contains(label) =>
          None
        case other => Some(s"$label -> $other")
    }
    assertEquals(unnamed, Vector.empty)
  }

  test("cost NaN and -Inf are InvalidConfig and name the cell") {
    val unnamed = SinkhornSuite.costCases.flatMap { case (label, c) =>
      UnbalancedSinkhorn.solve(c, SinkhornSuite.probeA, SinkhornSuite.probeB) match
        case Left(AlignError.InvalidConfig("cost", detail)) if detail.contains(label) =>
          None
        case other => Some(s"$label -> $other")
    }
    assertEquals(unnamed, Vector.empty)
  }

  test("non-finite or negative marginals are InvalidConfig and name the side") {
    val unnamed = SinkhornSuite.marginalCases.flatMap { case (label, (a, b)) =>
      UnbalancedSinkhorn.solve(SinkhornSuite.probeCost, a, b) match
        case Left(AlignError.InvalidConfig("marginals", detail)) if detail.contains(label) =>
          None
        case other => Some(s"$label -> $other")
    }
    assertEquals(unnamed, Vector.empty)
  }

  test("a +Inf cost cell is a forbidden edge with exact zero mass") {
    val c = Vector(Vector(0.1, Double.PositiveInfinity), Vector(0.8, 0.2))
    val r = solve(c, Vector(1.0, 1.0), Vector(1.0, 1.0))
    assertEquals(r.plan(0)(1), 0.0)
    assert(r.plan(0)(0) > 0.0, r.plan.toString)
    assert(r.plan.flatten.forall(x => x >= 0.0 && x.isFinite), r.plan.toString)
  }

  test("an all-+Inf row with positive mass is refused") {
    val c =
      Vector(Vector(Double.PositiveInfinity, Double.PositiveInfinity), Vector(0.8, 0.2))
    UnbalancedSinkhorn.solve(c, Vector(1.0, 1.0), Vector(1.0, 1.0)) match
      case Left(AlignError.InvalidConfig("cost", detail)) =>
        assert(detail.contains("row 0"), detail)
      case other => fail(other.toString)
  }

  test("an all-+Inf row with zero mass is lawful and stays zero") {
    val c =
      Vector(Vector(Double.PositiveInfinity, Double.PositiveInfinity), Vector(0.8, 0.2))
    val r = solve(c, Vector(0.0, 1.0), Vector(1.0, 1.0))
    assert(r.plan(0).forall(_ == 0.0), r.plan.toString)
    assert(r.plan(1).exists(_ > 0.0), r.plan.toString)
  }

  test("an all-+Inf column with positive mass is refused") {
    val c = Vector(Vector(0.1, Double.PositiveInfinity), Vector(0.8, Double.PositiveInfinity))
    UnbalancedSinkhorn.solve(c, Vector(1.0, 1.0), Vector(1.0, 1.0)) match
      case Left(AlignError.InvalidConfig("cost", detail)) =>
        assert(detail.contains("column 1"), detail)
      case other => fail(other.toString)
  }

  test("with a large penalty the transport is nearly balanced") {
    val c = Vector(Vector(0.1, 0.9, 0.5), Vector(0.8, 0.2, 0.6))
    val a = Vector(1.0, 1.0)
    val b = Vector(2.0 / 3, 2.0 / 3, 2.0 / 3)
    val r = solve(c, a, b, SinkhornConfig(0.05, 100.0, 100.0, 500, 1e-9))
    assert(r.rowDeviation(a) < 0.05, r.rowDeviation(a).toString)
    assert(r.colDeviation(b) < 0.05, r.colDeviation(b).toString)
    // cheapest cells carry the most mass
    assert(r.plan(0)(0) > r.plan(0)(1))
    assert(r.plan(1)(1) > r.plan(1)(0))
  }

  test("the baseline aligner produces one row per unit over the candidate union") {
    import AnnaFixture.*
    val rows = BaselineAligner
      .align(recall, view, candidates, semantic)
      .fold(e => fail(e.message), identity)
      .rows
    assertEquals(rows.size, recall.size)
    assert(rows.forall(_.mass.keys.forall(_.isSource)))
    // the embedding-only baseline still finds the easy anchors
    assertEquals(rows(2).mapSource, Some(e5))
  }

  test("the baseline substitutes the same neutral distance as the HSMM when a provider abstains") {
    import AnnaFixture.*
    val abstain = SemanticDistance.fromTableOrAbstain(Map((u2.id, e5) -> 0.1))
    val cands = CandidateGenerator(abstain, perLevel = 2).generate(recall.ordered, view)
    val half = BaselineAligner.align(recall, view, cands, abstain, missingDistance = 0.5)
    val one = BaselineAligner.align(recall, view, cands, abstain, missingDistance = 1.0)
    assert(half.isRight && one.isRight)
    assertNotEquals(half.toOption.get.rows(0).mass, one.toOption.get.rows(0).mass)
  }

object SinkhornSuite:
  private val probeCost: Vector[Vector[Double]] =
    Vector(Vector(0.1, 0.9), Vector(0.8, 0.2))
  private val probeA: Vector[Double] = Vector(1.0, 1.0)
  private val probeB: Vector[Double] = Vector(1.0, 1.0)

  /** Scout-measured scale siblings plus the polarity cases. Restoring `<= 0 || < 0` leaves NaN/+Inf
    * unlabelled Rights in this table.
    */
  private val scaleCases: Vector[(String, SinkhornConfig)] =
    Vector(
      "epsilon" -> SinkhornConfig(epsilon = Double.NaN),
      "epsilon" -> SinkhornConfig(epsilon = Double.PositiveInfinity),
      "epsilon" -> SinkhornConfig(epsilon = Double.NegativeInfinity),
      "epsilon" -> SinkhornConfig(epsilon = 0.0),
      "epsilon" -> SinkhornConfig(epsilon = -1.0),
      "rhoRows" -> SinkhornConfig(rhoRows = Double.NaN),
      "rhoRows" -> SinkhornConfig(rhoRows = Double.PositiveInfinity),
      "rhoRows" -> SinkhornConfig(rhoRows = -1.0),
      "rhoCols" -> SinkhornConfig(rhoCols = Double.NaN),
      "rhoCols" -> SinkhornConfig(rhoCols = Double.PositiveInfinity),
      "rhoCols" -> SinkhornConfig(rhoCols = -1.0)
    )

  /** Scout-measured cost siblings. Restoring the pre-input-boundary solve leaves Rights here.
    */
  private val costCases: Vector[(String, Vector[Vector[Double]])] =
    Vector(
      "(0,0)" -> Vector(Vector(Double.NaN, 0.9), Vector(0.8, 0.2)),
      "(0,0)" -> Vector(Vector(Double.NegativeInfinity, 0.9), Vector(0.8, 0.2)),
      "(1,1)" -> Vector(Vector(0.1, 0.9), Vector(0.8, Double.NaN))
    )

  /** Scout-measured marginal siblings. Zero mass is lawful and is not in this table.
    */
  private val marginalCases: Vector[(String, (Vector[Double], Vector[Double]))] =
    Vector(
      "a(0)" -> (Vector(Double.NaN, 1.0), Vector(1.0, 1.0)),
      "a(1)" -> (Vector(1.0, Double.PositiveInfinity), Vector(1.0, 1.0)),
      "a(0)" -> (Vector(-1.0, 1.0), Vector(1.0, 1.0)),
      "b(0)" -> (Vector(1.0, 1.0), Vector(Double.NaN, 1.0)),
      "b(1)" -> (Vector(1.0, 1.0), Vector(1.0, Double.NegativeInfinity)),
      "b(0)" -> (Vector(1.0, 1.0), Vector(-0.1, 1.0))
    )

  /** Scout-measured stopping siblings. The old epsilon/rho guard never mentions these fields.
    */
  private val stopCases: Vector[(String, SinkhornConfig)] =
    Vector(
      "tolerance" -> SinkhornConfig(tolerance = Double.NaN),
      "tolerance" -> SinkhornConfig(tolerance = 0.0),
      "tolerance" -> SinkhornConfig(tolerance = -1.0),
      "tolerance" -> SinkhornConfig(tolerance = Double.PositiveInfinity),
      "maxIterations" -> SinkhornConfig(maxIterations = 0),
      "maxIterations" -> SinkhornConfig(maxIterations = -1)
    )
