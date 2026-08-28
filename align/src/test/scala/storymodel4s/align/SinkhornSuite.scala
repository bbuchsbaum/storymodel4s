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

  property("the plan is non-negative and finite") {
    forAll(genCost) { c =>
      val m = c.size
      val n = c.head.size
      val res = UnbalancedSinkhorn.solve(c, Vector.fill(m)(1.0), Vector.fill(n)(m.toDouble / n))
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
        val r = UnbalancedSinkhorn.solve(c, a, b, SinkhornConfig(0.1, rho, rho, 300))
        r.rowDeviation(a) + r.colDeviation(b)
      }
      devs(0) >= devs(1) - 1e-6 && devs(1) >= devs(2) - 1e-6
    }
  }

  test("with a large penalty the transport is nearly balanced") {
    val c = Vector(Vector(0.1, 0.9, 0.5), Vector(0.8, 0.2, 0.6))
    val a = Vector(1.0, 1.0)
    val b = Vector(2.0 / 3, 2.0 / 3, 2.0 / 3)
    val r = UnbalancedSinkhorn.solve(c, a, b, SinkhornConfig(0.05, 100.0, 100.0, 500))
    assert(r.rowDeviation(a) < 0.05, r.rowDeviation(a).toString)
    assert(r.colDeviation(b) < 0.05, r.colDeviation(b).toString)
    // cheapest cells carry the most mass
    assert(r.plan(0)(0) > r.plan(0)(1))
    assert(r.plan(1)(1) > r.plan(1)(0))
  }

  test("the baseline aligner produces one row per unit over the candidate union") {
    import AnnaFixture.*
    val rows = BaselineAligner.align(recall, view, candidates, semantic).rows
    assertEquals(rows.size, recall.size)
    assert(rows.forall(_.mass.keys.forall(_.isSource)))
    // the embedding-only baseline still finds the easy anchors
    assertEquals(rows(2).mapSource, Some(e5))
  }
