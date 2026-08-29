package storymodel4s.benchprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

import storymodel4s.align.{Facet, InMemorySourceView}
import storymodel4s.bench.{Gold, GoldUnit, Groundedness}
import storymodel4s.recall.{DiscourseFunction, RecallUnitId}

/** Probes from OUTSIDE `storymodel4s.bench`. Live hole demonstrated first:
  *
  *   - `GoldForgeSuite` (2/2, 2026-08-29, unpiped `embedBench/testOnly`) minted
  *     `Gold.fromProduct(Tuple1(Map(unit -> distorted)))` while `Gold.validated` refused the same
  *     unit as `DistortedWithoutAnchor`. That suite is gone because the door it called no longer
  *     exists.
  */
class ConstructionProbeSuite extends FunSuite:

  private val unit = RecallUnitId.unsafe("u-probe")
  private val emptyView = InMemorySourceView(Vector.empty, Map.empty, None, 0)

  private def distorted: GoldUnit =
    GoldUnit(
      unit,
      Vector.empty,
      None,
      Set(Facet.Actor),
      Groundedness.Intrusion,
      None,
      DiscourseFunction.EpisodicAssertion,
      false
    )

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not be constructible outside storymodel4s.bench")

  test("positive control: a remaining case class still has fromProduct") {
    val errors = typeCheckErrors(
      """storymodel4s.bench.GoldUnit.fromProduct((
        storymodel4s.recall.RecallUnitId.unsafe("u-ctrl"),
        Vector.empty[storymodel4s.bench.GoldTarget],
        None,
        Set.empty[storymodel4s.align.Facet],
        storymodel4s.bench.Groundedness.Intrusion,
        None,
        storymodel4s.recall.DiscourseFunction.EpisodicAssertion,
        false
      ))"""
    )
    assert(
      errors.isEmpty,
      s"if GoldUnit.fromProduct fails to typecheck, every refusal beside it is meaningless:\n${errors.mkString("\n")}"
    )
  }

  test("Gold has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.bench.Gold.fromProduct(Tuple1(Map.empty[storymodel4s.recall.RecallUnitId, storymodel4s.bench.GoldUnit]))"""
      ),
      "Gold.fromProduct"
    )
  }

  test("Gold has no copy door") {
    refused(
      typeCheckErrors("""(g: storymodel4s.bench.Gold) => g.copy(byUnit = Map.empty)"""),
      "Gold.copy"
    )
  }

  test("Gold retains public read access") {
    assert(
      typeCheckErrors(
        """(g: storymodel4s.bench.Gold) => (g.byUnit, g.size, g(storymodel4s.recall.RecallUnitId.unsafe("x")))"""
      ).isEmpty
    )
  }

  test("the checked factory remains the public path") {
    assert(
      typeCheckErrors(
        """storymodel4s.bench.Gold.validated(Vector.empty, storymodel4s.align.InMemorySourceView(Vector.empty, Map.empty, None, 0))"""
      ).isEmpty
    )
    assertEquals(Gold.validated(Vector.empty, emptyView).map(_.size), Right(0))
    assertEquals(
      Gold.validated(Vector(distorted), emptyView),
      Left(Gold.GoldError.DistortedWithoutAnchor(unit))
    )
  }
