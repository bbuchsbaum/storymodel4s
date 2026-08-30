package storymodel4s.interview

import munit.FunSuite

import storymodel4s.interview.scoring.{
  Conditional,
  ExclusionCause,
  PlacementGrain,
  PlacementResolution
}

/** The vehicle five estimand fixes share. Its whole job is to make one error unrepresentable —
  * renormalizing resolved mass to fill the gap left by unresolved and excluded mass — so the tests
  * are mostly about what it refuses.
  */
class ResolutionSuite extends FunSuite:

  private val eps = 1e-12

  private def res(r: Double, u: Double, e: Double = 0.0) =
    PlacementResolution
      .of(
        PlacementGrain.Detail,
        r,
        u,
        e,
        excludedBy = if e > 0.0 then Map(ExclusionCause.RepetitionPolicy -> e) else Map.empty
      )
      .fold(err => fail(err.message), identity)

  test("the three masses account for all of the placement") {
    val p = res(0.4, 0.5, 0.1)
    assertEqualsDouble(p.resolved + p.unresolved + p.excluded, 1.0, eps)
  }

  test("within-tolerance slack is absorbed so the stored parts sum to one") {
    // Permission, not occurrence: no production caller has been measured to hand `of` a
    // 1 +/- Tolerance triple. The pin is that the door that admits the slack does not then
    // publish the unabsorbed value. `1.0 + Tolerance` is the constructed literal; IEEE makes
    // (1.0 + 1e-9) - 1.0 slightly larger than 1e-9, which is why the guard folds one ulp.
    // Gap-filling (0.4 + 0.0) stays refused below.
    val t = PlacementResolution.Tolerance
    val high = PlacementResolution
      .of(PlacementGrain.Detail, 1.0 + t, 0.0, 0.0)
      .fold(err => fail(s"1 + Tolerance refused: ${err.message}"), identity)
    assertEqualsDouble(high.resolved + high.unresolved + high.excluded, 1.0, eps)
    assertEqualsDouble(high.resolved, 1.0, eps)
    assertEqualsDouble(high.unresolved, 0.0, eps)
    assertEqualsDouble(high.excluded, 0.0, eps)
    val low = PlacementResolution
      .of(PlacementGrain.Detail, 1.0 - t, 0.0, 0.0)
      .fold(err => fail(s"1 - Tolerance refused: ${err.message}"), identity)
    assertEqualsDouble(low.resolved + low.unresolved + low.excluded, 1.0, eps)
    assertEqualsDouble(low.resolved, 1.0, eps)
    assert(
      PlacementResolution.of(PlacementGrain.Detail, 1.0 + 2 * t, 0.0, 0.0).isLeft,
      "1 + 2*Tolerance absorbed"
    )
    assert(
      PlacementResolution.of(PlacementGrain.Detail, 1.0 - 2 * t, 0.0, 0.0).isLeft,
      "1 - 2*Tolerance absorbed"
    )
  }

  test("a summary that does not account for all the mass is refused") {
    // 0.4 resolved and nothing else is not "40% resolved", it is an incomplete summary - the other
    // 0.6 has to be attributed to something before the figure means anything.
    assert(PlacementResolution.of(PlacementGrain.Detail, 0.4, 0.0).isLeft, "gap accepted")
    assert(PlacementResolution.of(PlacementGrain.Detail, 0.7, 0.7).isLeft, "overfull accepted")
    assert(PlacementResolution.of(PlacementGrain.Detail, -0.1, 1.1).isLeft, "negative accepted")
    assert(
      PlacementResolution.of(PlacementGrain.Detail, Double.NaN, 0.0).isLeft,
      "NaN accepted"
    )
    assert(
      PlacementResolution.of(PlacementGrain.Detail, 1.0, 0.0, 0.0, 1.5).isLeft,
      "out-of-range threshold accepted"
    )
  }

  test("excluded mass is neither resolved nor unresolved") {
    // A repetition dropped by policy was not placed, and it was not something we failed to place.
    // Folding it into either would misattribute a scoring decision as a model outcome.
    val p = res(0.5, 0.2, 0.3)
    assertEqualsDouble(p.resolved, 0.5, eps)
    assertEqualsDouble(p.unresolved, 0.2, eps)
    assertEqualsDouble(p.excluded, 0.3, eps)
  }

  test("resolved mass is never renormalized to fill the gap") {
    // The error the type exists to prevent: a detail we placed 40% of must not report as fully
    // placed just because the rest was unresolved.
    val partial = res(0.4, 0.6)
    assertEqualsDouble(partial.resolved, 0.4, eps)
    assert(partial.resolved < 1.0, partial.render)
    assertNotEquals(partial.resolved, 1.0)
  }

  test("the abstention threshold is carried, not invented per site") {
    // Five consumers must not drift onto five cutoffs; the summary states the one in force.
    assert(!res(0.4, 0.6).clearsThreshold, "0.4 cleared the default 0.5 threshold")
    assert(res(0.5, 0.5).clearsThreshold, "0.5 did not clear the default 0.5 threshold")
    val strict = PlacementResolution
      .of(PlacementGrain.Detail, 0.6, 0.4, 0.0, abstentionThreshold = 0.8)
      .fold(e => fail(e.message), identity)
    assert(!strict.clearsThreshold, strict.render)
  }

  test("a conditional quantity cannot be read without meeting its condition") {
    val poorly = Conditional(0.93, res(0.2, 0.8))
    assertEquals(poorly.whenResolved, None, "a barely-placed ratio was published as a number")
    val well = Conditional(0.93, res(0.9, 0.1))
    assertEquals(well.whenResolved, Some(0.93))
    // The escape hatch exists for a caller explicitly reporting the pair, and is named so that
    // dropping the condition is visible at the call site rather than accidental.
    assertEquals(poorly.valueIgnoringResolution, 0.93)
  }

  test("there is no accessor that returns the bare value unconditionally") {
    // Structural, not documentary: an `apply`, `get`, or `value` on Conditional would let the
    // number be quoted without its resolution and this stops compiling-as-false.
    // The second review found the real escape hatch: as a CASE class, Conditional exposed the
    // payload through generated Product members - _1, productElement(0) and unapply - so the
    // "no unconditional accessor" claim was false while .value and .get were absent. Checking only
    // the names I had thought of is how a tripwire misses the door that is actually open.
    //
    // Note the asymmetry with PlacementResolution below: there the Product surface is harmless
    // because every field is public anyway and only fromProduct (which CONSTRUCTS) is a defect.
    // Here the payload is deliberately hidden, so the read-only Product members ARE the hole. A
    // probe for `copy` is deliberately absent: Scala 3 suppresses copy when the constructor is
    // private, so asserting its absence tests the compiler rather than this code.
    assert(!scala.compiletime.testing.typeChecks("Conditional(0.5, res(1.0, 0.0)).value"))
    assert(!scala.compiletime.testing.typeChecks("Conditional(0.5, res(1.0, 0.0)).get"))
    assert(!scala.compiletime.testing.typeChecks("Conditional(0.5, res(1.0, 0.0))._1"))
    assert(
      !scala.compiletime.testing.typeChecks("Conditional(0.5, res(1.0, 0.0)).productElement(0)")
    )
    assert(!scala.compiletime.testing.typeChecks("""
      Conditional(0.5, res(1.0, 0.0)) match { case Conditional(v, _) => v }
    """))
    // Control: the accessors that should exist do.
    assert(scala.compiletime.testing.typeChecks("Conditional(0.5, res(1.0, 0.0)).whenResolved"))
  }

  test("grain is part of the summary, because the same number means different things") {
    val d = PlacementResolution.of(PlacementGrain.Detail, 0.7, 0.3).toOption.get
    val s = PlacementResolution.of(PlacementGrain.Situation, 0.7, 0.3).toOption.get
    assertNotEquals(d, s)
    assert(d.render.contains("detail"), d.render)
    assert(s.render.contains("situation"), s.render)
  }

  test("complete cannot smuggle an invalid threshold past the smart constructor") {
    // It used to take a threshold and construct directly, so complete(Detail, NaN) and
    // complete(Detail, -1.0) produced invalid public states while `of` rejected them.
    assert(
      !scala.compiletime.testing.typeChecks(
        "PlacementResolution.complete(PlacementGrain.Detail, Double.NaN)"
      )
    )
    assert(PlacementResolution.of(PlacementGrain.Detail, 1.0, 0.0, 0.0, Double.NaN).isLeft)
    assert(PlacementResolution.of(PlacementGrain.Detail, 1.0, 0.0, 0.0, -1.0).isLeft)
  }

  test("complete is the only way to assert full resolution, and it is explicit") {
    val c = PlacementResolution.complete(PlacementGrain.Phase)
    assertEqualsDouble(c.resolved, 1.0, eps)
    assertEqualsDouble(c.unresolved, 0.0, eps)
    assert(c.clearsThreshold)
  }

  test("the smart constructor cannot be walked past through the derived Mirror") {
    // A case class with a PRIVATE constructor still derives Mirror.ProductOf, and its public
    // fromProduct rebuilds the type field by field without consulting `of`. Demonstrated on this
    // type before the fix: fromProduct produced masses summing to 3.0 with a threshold of -5.0,
    // every invariant violated, while `of` rejected the identical values. Making it a non-case
    // class removes the Mirror, so this no longer compiles.
    assert(
      !scala.compiletime.testing.typeChecks(
        "summon[scala.deriving.Mirror.ProductOf[PlacementResolution]]"
      ),
      "PlacementResolution still derives a Mirror; fromProduct forges invalid states"
    )
    assert(
      !scala.compiletime.testing.typeChecks(
        "PlacementResolution.fromProduct((PlacementGrain.Detail, 1.0, 1.0, 1.0, -5.0))"
      )
    )
    // The invariants the forge used to bypass are still enforced on the real door.
    assert(PlacementResolution.of(PlacementGrain.Detail, 1.0, 1.0, 1.0, -5.0).isLeft)
  }

  test("excluded mass must say which rule removed it") {
    // Chief's requirement on this bead: "record WHICH threshold each exclusion failed. Six metrics
    // moving with an undifferentiated `excluded` tells a later reader that something changed and
    // not what." So unattributed exclusion is not a lesser record, it is refused.
    assert(
      PlacementResolution.of(PlacementGrain.Detail, 0.7, 0.0, 0.3).isLeft,
      "excluded mass with no attribution was accepted"
    )
    // ... and the attribution may not name mass that was never excluded.
    assert(
      PlacementResolution
        .of(
          PlacementGrain.Detail,
          1.0,
          0.0,
          0.0,
          excludedBy = Map(ExclusionCause.RepetitionPolicy -> 0.3)
        )
        .isLeft,
      "an attribution without excluded mass was accepted"
    )
  }

  test("the attribution must account for the whole exclusion, not merely accompany it") {
    // The failure this forbids is subtler than an absent map: naming one cause for a tenth of the
    // mass and staying silent about the rest is the undifferentiated `excluded` the ruling
    // rejected, wearing a label.
    assert(
      PlacementResolution
        .of(
          PlacementGrain.Detail,
          0.7,
          0.0,
          0.3,
          excludedBy = Map(ExclusionCause.RepetitionPolicy -> 0.1)
        )
        .isLeft,
      "an attribution that under-accounts for the exclusion was accepted"
    )
    val split = PlacementResolution
      .of(
        PlacementGrain.Detail,
        0.7,
        0.0,
        0.3,
        excludedBy =
          Map(ExclusionCause.RepetitionPolicy -> 0.1, ExclusionCause.BelowMembership(0.5) -> 0.2)
      )
      .fold(e => fail(e.message), identity)
    assertEqualsDouble(split.excludedBy.values.sum, split.excluded, eps)
    assertEqualsDouble(split.excludedBy(ExclusionCause.BelowMembership(0.5)), 0.2, eps)
  }

  test("the two exclusion causes are different claims and stay apart") {
    // A repetition dropped by policy is a scoring decision. Mass resolved but too spread to be a
    // member of any class failed the MEMBERSHIP threshold, which is a different number from the
    // abstention threshold and a different question. Folding them together would report a scoring
    // decision and a measurement limit as the same event.
    val policy = PlacementResolution
      .of(
        PlacementGrain.Detail,
        0.7,
        0.0,
        0.3,
        excludedBy = Map(ExclusionCause.RepetitionPolicy -> 0.3)
      )
      .fold(e => fail(e.message), identity)
    val membership = PlacementResolution
      .of(
        PlacementGrain.Detail,
        0.7,
        0.0,
        0.3,
        excludedBy = Map(ExclusionCause.BelowMembership(0.5) -> 0.3)
      )
      .fold(e => fail(e.message), identity)
    assertEqualsDouble(policy.excluded, membership.excluded, eps)
    assertNotEquals(policy.excludedBy, membership.excludedBy)
    assertNotEquals(policy, membership)
  }

  test("absorbing float slack rescales the attribution with the masses") {
    // `of` divides the three masses by their total to absorb within-tolerance slack. If the
    // attribution were not scaled by the same factor it would stop summing to the `excluded` it
    // explains, and the invariant above would hold at construction and be false in the stored
    // value - true on the way in, wrong once published.
    val t = PlacementResolution.Tolerance
    val r = PlacementResolution
      .of(
        PlacementGrain.Detail,
        0.7 + t,
        0.0,
        0.3,
        excludedBy = Map(ExclusionCause.BelowMembership(0.5) -> 0.3)
      )
      .fold(e => fail(e.message), identity)
    assertEqualsDouble(r.excludedBy.values.sum, r.excluded, eps)
  }

  test("an admitted inner mismatch is absorbed, not stored") {
    // collab's BLOCK on 067e35b. The tolerance ADMITS an excludedBy that misses `excluded` by less
    // than Tolerance; the first version then merely divided it by the outer total, which PRESERVES
    // the discrepancy. So the whole-attribution invariant held at the door and was false in the
    // stored value - 0.3000000005 against an excluded of 0.3, failing this suite's own eps.
    // Absorbing is what the three masses already do; this is the same obligation one field over.
    val t = PlacementResolution.Tolerance
    val r = PlacementResolution
      .of(
        PlacementGrain.Detail,
        0.7,
        0.0,
        0.3,
        excludedBy = Map(ExclusionCause.RepetitionPolicy -> (0.3 + t / 2))
      )
      .fold(e => fail(e.message), identity)
    assertEqualsDouble(r.excludedBy.values.sum, r.excluded, eps)
    assertEqualsDouble(r.excludedBy(ExclusionCause.RepetitionPolicy), 0.3, eps)
  }

  test("a mismatch beyond the tolerance is refused, so absorption is not a licence") {
    // The other side of the boundary collab asked to be pinned. Absorbing a near-miss must not
    // become "any attribution is normalised onto the excluded mass" - that would make the sum
    // invariant unfalsifiable, which is the failure mode this whole bead is about.
    val t = PlacementResolution.Tolerance
    assert(
      PlacementResolution
        .of(
          PlacementGrain.Detail,
          0.7,
          0.0,
          0.3,
          excludedBy = Map(ExclusionCause.RepetitionPolicy -> (0.3 + 4 * t))
        )
        .isLeft,
      "an attribution well outside the tolerance was absorbed instead of refused"
    )
  }

  test("BelowMembership names the threshold it failed, not just the rule") {
    // Chief required "record WHICH THRESHOLD each exclusion failed". I implemented "which rule" and
    // flagged it as an interpretation; scout blocked it because the numeric value was erased. Two
    // exclusions at different membership thresholds are different claims and must not compare equal.
    val strict = PlacementResolution
      .of(
        PlacementGrain.Detail,
        0.7,
        0.0,
        0.3,
        excludedBy = Map(ExclusionCause.BelowMembership(0.8) -> 0.3)
      )
      .fold(e => fail(e.message), identity)
    val lax = PlacementResolution
      .of(
        PlacementGrain.Detail,
        0.7,
        0.0,
        0.3,
        excludedBy = Map(ExclusionCause.BelowMembership(0.5) -> 0.3)
      )
      .fold(e => fail(e.message), identity)
    assertNotEquals(strict.excludedBy, lax.excludedBy)
    assertNotEquals(strict, lax)
    assert(strict.render.contains("0.8000"), strict.render)
  }

  test("a membership threshold must be finite and in [0, 1]") {
    // collab's BLOCK on 3c3b617. `abstentionThreshold` is guarded three lines above in the same
    // constructor; the threshold a CAUSE carries was not, so BelowMembership(NaN) and friends were
    // accepted and published as the rule in force. A receipt naming an impossible cutoff is worse
    // than one naming none, because it reads as measured.
    //
    // isNaN is tested explicitly in the guard rather than relying on the range comparison: NaN
    // fails every comparison, so `t < 0.0 || t > 1.0` alone FAILS OPEN and admits it.
    val bad = Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, -0.1, 1.1)
    bad.foreach { t =>
      assert(
        PlacementResolution
          .of(
            PlacementGrain.Detail,
            0.7,
            0.0,
            0.3,
            excludedBy = Map(ExclusionCause.BelowMembership(t) -> 0.3)
          )
          .isLeft,
        s"a membership threshold of $t was accepted and published"
      )
    }
    // Controls: the boundary values that ARE lawful must still be admitted, or the guard is a wall.
    Vector(0.0, 0.5, 1.0).foreach { t =>
      assert(
        PlacementResolution
          .of(
            PlacementGrain.Detail,
            0.7,
            0.0,
            0.3,
            excludedBy = Map(ExclusionCause.BelowMembership(t) -> 0.3)
          )
          .isRight,
        s"a lawful membership threshold of $t was refused"
      )
    }
  }

  test("render does not depend on how the attribution map was built") {
    // collab's BLOCK on fa1c4e1. `sortBy(_._1.ordinal)` is not a total order over ExclusionCause -
    // every BelowMembership shares one ordinal - so two resolutions that COMPARE EQUAL rendered
    // their exclusions in whichever order the map happened to iterate. A receipt whose text depends
    // on construction order cannot be diffed, quoted or audited.
    val a = ExclusionCause.BelowMembership(0.3)
    val b = ExclusionCause.BelowMembership(0.8)
    val forward = PlacementResolution
      .of(PlacementGrain.Detail, 0.4, 0.0, 0.6, excludedBy = Map(a -> 0.2, b -> 0.4))
      .fold(e => fail(e.message), identity)
    val reversed = PlacementResolution
      .of(PlacementGrain.Detail, 0.4, 0.0, 0.6, excludedBy = Map(b -> 0.4, a -> 0.2))
      .fold(e => fail(e.message), identity)
    assertEquals(forward, reversed, "the two resolutions must be equal to make this a real court")
    assertEquals(forward.render, reversed.render)
    // And the order is the SEMANTIC one, ascending by threshold, not an accident of iteration.
    assert(
      forward.render.indexOf("0.3000") < forward.render.indexOf("0.8000"),
      forward.render
    )
  }
