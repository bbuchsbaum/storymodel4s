package storymodel4s.proposition

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** The strict compare's relation assignment is only as order-free as this solver is exact. */
class MaxWeightAssignmentSuite extends ScalaCheckSuite:

  /** Every partial one-to-one assignment, enumerated: the definition the solver must meet. */
  private def bruteForce(w: Array[Array[Long]]): Long =
    def go(i: Int, used: Set[Int]): Long =
      if i == w.length then 0L
      else
        val skip = go(i + 1, used)
        w(i).indices.filterNot(used).foldLeft(skip) { (best, j) =>
          if w(i)(j) > 0 then math.max(best, w(i)(j) + go(i + 1, used + j)) else best
        }
    go(0, Set.empty)

  private val matrix: Gen[Array[Array[Long]]] =
    for
      rows <- Gen.chooseNum(0, 5)
      cols <- Gen.chooseNum(1, 5)
      cells <- Gen.listOfN(
        rows * cols,
        Gen.frequency(2 -> Gen.const(0L), 3 -> Gen.chooseNum(1L, 40L))
      )
    yield cells.grouped(cols).map(_.toArray).toArray

  property("the solver finds the brute-force optimum") {
    forAll(matrix)(w => MaxWeightAssignment.solve(w) == bruteForce(w))
  }

  property("the optimum does not depend on row or column order") {
    forAll(matrix) { w =>
      val flipped = w.reverse.map(_.reverse)
      MaxWeightAssignment.solve(flipped) == MaxWeightAssignment.solve(w)
    }
  }

  test("greedy's trap: taking the locally best pair first is not optimal") {
    // Row 0 prefers column 0 (9 over 8); row 1 can only use column 0 (7). Greedy gives 9, the
    // optimum is 8 + 7 = 15.
    assertEquals(MaxWeightAssignment.solve(Array(Array(9L, 8L), Array(7L, 0L))), 15L)
  }
