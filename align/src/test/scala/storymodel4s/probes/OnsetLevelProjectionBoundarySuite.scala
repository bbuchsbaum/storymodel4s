package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Outside-package refusal probes for the onset-level projection boundary types. */
class OnsetLevelProjectionBoundarySuite extends FunSuite:

  test("Bin constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; final class Control(val start: ExactRational, val endExclusive: ExactRational); new Control(??? : ExactRational, ??? : ExactRational)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new OnsetLevelProjection.Bin(??? : ExactRational, ??? : ExactRational)"""
      ).nonEmpty
    )
  }

  test("Bin apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(start: ExactRational, endExclusive: ExactRational); Control(??? : ExactRational, ??? : ExactRational)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; OnsetLevelProjection.Bin(??? : ExactRational, ??? : ExactRational)"""
      ).nonEmpty
    )
  }

  test("Bin Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(start: ExactRational, endExclusive: ExactRational); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[OnsetLevelProjection.Bin]]"""
      ).nonEmpty
    )
  }

  test("Bins constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; final class Control(val clock: RecallTiming.Clock, val bins: Vector[OnsetLevelProjection.Bin]); new Control(??? : RecallTiming.Clock, Vector.empty[OnsetLevelProjection.Bin])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new OnsetLevelProjection.Bins(??? : RecallTiming.Clock, Vector.empty[OnsetLevelProjection.Bin])"""
      ).nonEmpty
    )
  }

  test("Bins apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(clock: RecallTiming.Clock, bins: Vector[OnsetLevelProjection.Bin]); Control(??? : RecallTiming.Clock, Vector.empty[OnsetLevelProjection.Bin])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; OnsetLevelProjection.Bins(??? : RecallTiming.Clock, Vector.empty[OnsetLevelProjection.Bin])"""
      ).nonEmpty
    )
  }

  test("Bins Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(clock: RecallTiming.Clock, bins: Vector[OnsetLevelProjection.Bin]); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[OnsetLevelProjection.Bins]]"""
      ).nonEmpty
    )
  }

  test("Request constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; final class Control(val timing: RecallTiming, val declaration: OnsetLevelProjection.Declaration, val nesting: OnsetLevelProjection.Nesting, val bins: OnsetLevelProjection.Bins, val samples: Vector[OnsetLevelProjection.Sample[Double]], val reducer: OnsetLevelProjection.Reducer[Double, Double]); new Control(??? : RecallTiming, ??? : OnsetLevelProjection.Declaration, OnsetLevelProjection.Nesting.All, ??? : OnsetLevelProjection.Bins, Vector.empty[OnsetLevelProjection.Sample[Double]], OnsetLevelProjection.Reducer.Mean)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new OnsetLevelProjection.Request[Double, Double](??? : RecallTiming, ??? : OnsetLevelProjection.Declaration, OnsetLevelProjection.Nesting.All, ??? : OnsetLevelProjection.Bins, Vector.empty[OnsetLevelProjection.Sample[Double]], OnsetLevelProjection.Reducer.Mean)"""
      ).nonEmpty
    )
  }

  test("Request apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(timing: RecallTiming, declaration: OnsetLevelProjection.Declaration, nesting: OnsetLevelProjection.Nesting, bins: OnsetLevelProjection.Bins, samples: Vector[OnsetLevelProjection.Sample[Double]], reducer: OnsetLevelProjection.Reducer[Double, Double]); Control(??? : RecallTiming, ??? : OnsetLevelProjection.Declaration, OnsetLevelProjection.Nesting.All, ??? : OnsetLevelProjection.Bins, Vector.empty[OnsetLevelProjection.Sample[Double]], OnsetLevelProjection.Reducer.Mean)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; OnsetLevelProjection.Request[Double, Double](??? : RecallTiming, ??? : OnsetLevelProjection.Declaration, OnsetLevelProjection.Nesting.All, ??? : OnsetLevelProjection.Bins, Vector.empty[OnsetLevelProjection.Sample[Double]], OnsetLevelProjection.Reducer.Mean)"""
      ).nonEmpty
    )
  }

  test("Request Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(timing: RecallTiming, declaration: OnsetLevelProjection.Declaration, nesting: OnsetLevelProjection.Nesting, bins: OnsetLevelProjection.Bins, samples: Vector[OnsetLevelProjection.Sample[Double]], reducer: OnsetLevelProjection.Reducer[Double, Double]); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[OnsetLevelProjection.Request[Double, Double]]]"""
      ).nonEmpty
    )
  }

  test("Ledger constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; final class Control(val applicableReferences: Int, val notApplicableReferences: Int, val coveredWords: Int, val uncoveredMemberWords: Int, val uncoveredUnassignedWords: Int); new Control(1, 0, 1, 0, 0)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new OnsetLevelProjection.Ledger(1, 0, 1, 0, 0)"""
      ).nonEmpty
    )
  }

  test("Ledger apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(applicableReferences: Int, notApplicableReferences: Int, coveredWords: Int, uncoveredMemberWords: Int, uncoveredUnassignedWords: Int); Control(1, 0, 1, 0, 0)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; OnsetLevelProjection.Ledger(1, 0, 1, 0, 0)"""
      ).nonEmpty
    )
  }

  test("Ledger Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(applicableReferences: Int, notApplicableReferences: Int, coveredWords: Int, uncoveredMemberWords: Int, uncoveredUnassignedWords: Int); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[OnsetLevelProjection.Ledger]]"""
      ).nonEmpty
    )
  }

  test("BinResult constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; final class Control(val bin: OnsetLevelProjection.Bin, val ledger: OnsetLevelProjection.Ledger, val applicable: Vector[SpanSet], val notApplicable: Vector[(SpanSet, OnsetLevelProjection.NotApplicableReason)], val value: OnsetLevelProjection.BinValue[Double]); new Control(??? : OnsetLevelProjection.Bin, ??? : OnsetLevelProjection.Ledger, Vector.empty[SpanSet], Vector.empty[(SpanSet, OnsetLevelProjection.NotApplicableReason)], OnsetLevelProjection.BinValue.Present(1.0))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new OnsetLevelProjection.BinResult[Double](??? : OnsetLevelProjection.Bin, ??? : OnsetLevelProjection.Ledger, Vector.empty[SpanSet], Vector.empty[(SpanSet, OnsetLevelProjection.NotApplicableReason)], OnsetLevelProjection.BinValue.Present(1.0))"""
      ).nonEmpty
    )
  }

  test("BinResult apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(bin: OnsetLevelProjection.Bin, ledger: OnsetLevelProjection.Ledger, applicable: Vector[SpanSet], notApplicable: Vector[(SpanSet, OnsetLevelProjection.NotApplicableReason)], value: OnsetLevelProjection.BinValue[Double]); Control(??? : OnsetLevelProjection.Bin, ??? : OnsetLevelProjection.Ledger, Vector.empty[SpanSet], Vector.empty[(SpanSet, OnsetLevelProjection.NotApplicableReason)], OnsetLevelProjection.BinValue.Present(1.0))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; OnsetLevelProjection.BinResult[Double](??? : OnsetLevelProjection.Bin, ??? : OnsetLevelProjection.Ledger, Vector.empty[SpanSet], Vector.empty[(SpanSet, OnsetLevelProjection.NotApplicableReason)], OnsetLevelProjection.BinValue.Present(1.0))"""
      ).nonEmpty
    )
  }

  test("BinResult Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(bin: OnsetLevelProjection.Bin, ledger: OnsetLevelProjection.Ledger, applicable: Vector[SpanSet], notApplicable: Vector[(SpanSet, OnsetLevelProjection.NotApplicableReason)], value: OnsetLevelProjection.BinValue[Double]); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[OnsetLevelProjection.BinResult[Double]]]"""
      ).nonEmpty
    )
  }

  test("Projection constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; final class Control(val request: OnsetLevelProjection.Request[Double, Double], val bins: Vector[OnsetLevelProjection.BinResult[Double]], val missingTimingWords: Vector[RecallWordId], val outsideBinWords: Vector[RecallWordId], val wordlessReferences: Vector[SpanSet], val unsampledReferences: Vector[SpanSet]); new Control(??? : OnsetLevelProjection.Request[Double, Double], Vector.empty[OnsetLevelProjection.BinResult[Double]], Vector.empty[RecallWordId], Vector.empty[RecallWordId], Vector.empty[SpanSet], Vector.empty[SpanSet])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new OnsetLevelProjection.Projection[Double, Double](??? : OnsetLevelProjection.Request[Double, Double], Vector.empty[OnsetLevelProjection.BinResult[Double]], Vector.empty[RecallWordId], Vector.empty[RecallWordId], Vector.empty[SpanSet], Vector.empty[SpanSet])"""
      ).nonEmpty
    )
  }

  test("Projection apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(request: OnsetLevelProjection.Request[Double, Double], bins: Vector[OnsetLevelProjection.BinResult[Double]], missingTimingWords: Vector[RecallWordId], outsideBinWords: Vector[RecallWordId], wordlessReferences: Vector[SpanSet], unsampledReferences: Vector[SpanSet]); Control(??? : OnsetLevelProjection.Request[Double, Double], Vector.empty[OnsetLevelProjection.BinResult[Double]], Vector.empty[RecallWordId], Vector.empty[RecallWordId], Vector.empty[SpanSet], Vector.empty[SpanSet])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; OnsetLevelProjection.Projection[Double, Double](??? : OnsetLevelProjection.Request[Double, Double], Vector.empty[OnsetLevelProjection.BinResult[Double]], Vector.empty[RecallWordId], Vector.empty[RecallWordId], Vector.empty[SpanSet], Vector.empty[SpanSet])"""
      ).nonEmpty
    )
  }

  test("Projection Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(request: OnsetLevelProjection.Request[Double, Double], bins: Vector[OnsetLevelProjection.BinResult[Double]], missingTimingWords: Vector[RecallWordId], outsideBinWords: Vector[RecallWordId], wordlessReferences: Vector[SpanSet], unsampledReferences: Vector[SpanSet]); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[OnsetLevelProjection.Projection[Double, Double]]]"""
      ).nonEmpty
    )
  }

  test("CategoryShares constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; final class Control(val counts: Map[Int, Int], val references: Int); new Control(Map(1 -> 1), 1)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; new OnsetLevelProjection.CategoryShares[Int](Map(1 -> 1), 1)"""
      ).nonEmpty
    )
  }

  test("CategoryShares apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(counts: Map[Int, Int], references: Int); Control(Map(1 -> 1), 1)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; OnsetLevelProjection.CategoryShares[Int](Map(1 -> 1), 1)"""
      ).nonEmpty
    )
  }

  test("CategoryShares Mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; case class Control(counts: Map[Int, Int], references: Int); summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; summon[scala.deriving.Mirror.ProductOf[OnsetLevelProjection.CategoryShares[Int]]]"""
      ).nonEmpty
    )
  }

  test("Reducer is sealed: no amount reducer can be added outside the package") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; trait Open[A, O] { def id: String }; object Sum extends Open[Double, Double] { val id = "sum" }"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; object Sum extends OnsetLevelProjection.Reducer[Double, Double] { val id = "sum" }"""
      ).nonEmpty
    )
  }

  test("a level projection is not a temporal-query allocation") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; val a: TemporalQuery.Allocation = TemporalQuery.Allocation.UniformIntervals"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; val a: TemporalQuery.Allocation = OnsetLevelProjection.Reducer.Mean"""
      ).nonEmpty
    )
  }

  test("a floating category type is refused for Share and Presence with an Int control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*; OnsetLevelProjection.Reducer.Share[Int](); OnsetLevelProjection.Reducer.Presence[String]()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; OnsetLevelProjection.Reducer.Share[Double]()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; OnsetLevelProjection.Reducer.Presence[Float]()"""
      ).nonEmpty
    )
  }
