package storymodel4s.acquire

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

class FoilSuite extends ScalaCheckSuite:
  import Fixtures.given

  private def o(k: FoilKind, preferred: Boolean) = FoilOutcome(k, preferred, Vector.empty)

  test("empty report has no rates and passes vacuously"):
    assertEquals(FoilReport.empty.overall, None)
    assertEquals(FoilReport.empty.preferenceRate(FoilKind.SwapRoles), None)
    assert(FoilReport.empty.passes(1.0))

  test("per-kind and overall rates"):
    val r = FoilReport(
      Vector(
        o(FoilKind.SwapRoles, true),
        o(FoilKind.SwapRoles, false),
        o(FoilKind.FlipPolarity, true)
      )
    )
    assertEquals(r.preferenceRate(FoilKind.SwapRoles), Some(0.5))
    assertEquals(r.preferenceRate(FoilKind.FlipPolarity), Some(1.0))
    assertEquals(r.preferenceRate(FoilKind.RemoveCausalCue), None)
    assertEquals(r.overall, Some(2.0 / 3.0))
    assert(r.passes(0.5))
    assert(!r.passes(0.75))
    assertEquals(r.failures.map(_.kind), Vector(FoilKind.SwapRoles))

  property("overall rate is the preferred count over trials and lies in [0,1]"):
    forAll { (os: Vector[FoilOutcome]) =>
      val r = FoilReport(os)
      r.overall match
        case None    => os.isEmpty
        case Some(v) =>
          v >= 0.0 && v <= 1.0 && v == os.count(_.criticPreferredOriginal).toDouble / os.size
    }

  property("kind stats partition the outcomes"):
    forAll { (os: Vector[FoilOutcome]) =>
      val stats = FoilReport(os).byKind
      stats.values.map(_.trials).sum == os.size &&
      stats.values.map(_.preferredOriginal).sum == os.count(_.criticPreferredOriginal)
    }

  property("concatenation is associative and empty is identity"):
    forAll { (a: Vector[FoilOutcome], b: Vector[FoilOutcome], c: Vector[FoilOutcome]) =>
      val (ra, rb, rc) = (FoilReport(a), FoilReport(b), FoilReport(c))
      ((ra ++ rb) ++ rc) == (ra ++ (rb ++ rc)) && (ra ++ FoilReport.empty) == ra
    }
