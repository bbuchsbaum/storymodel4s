package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.align.StructuralCoverage

/** Construction boundaries of `Admissibility` and `StructuralCoverage`, probed from outside package
  * `align` (bd-01M17ZNXY6AS1CMBQJRH3JMNVX; docs/api-stability.md "Signature closure").
  *
  * Why these two: both were case classes whose fields stand in a relation. `Admissibility` stored
  * `faithful` and `distortion` beside the contradictions they are functions of, so
  * `(no contradictions, not faithful)` was a representable record; `StructuralCoverage` let
  * `(0, 2, 1)` exist and throw from `Coverage.unsafe` at read time. A private constructor on a case
  * class did not close `fromProduct` or `Mirror.ProductOf`, which are public from here.
  */
class AdmissibilityUnforgeableSuite extends FunSuite:

  test("controls: the probe mechanism can pass for a same-shape case class in align") {
    // `typeChecks` is false for ANY error, so every refusal below needs a control that shows the
    // same snippet shape compiles against a type that is meant to be constructible.
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[FunctionPrior]]"
      ),
      "control: a case class in align derives a Mirror from here"
    )
    assert(
      typeChecks("import storymodel4s.align.*; FunctionPrior.fromProduct(???)"),
      "control: a case-class companion exposes fromProduct from here"
    )
    assert(
      typeChecks("import storymodel4s.align.*; (??? : FunctionPrior).copy()"),
      "control: a case class exposes copy from here"
    )
    assert(
      typeChecks(
        "import storymodel4s.align.*; classOf[Admissibility]; classOf[StructuralCoverage]"
      ),
      "control: both types are visible from outside align"
    )
  }

  test("Admissibility has no product door outside align") {
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[Admissibility]]"
      ),
      "Admissibility derives a Mirror; fromProduct would rebuild a self-contradicting record"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; Admissibility.fromProduct(???)"),
      "the Admissibility companion exposes fromProduct"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : Admissibility).copy()"),
      "Admissibility exposes copy"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; Admissibility(Vector.empty)"),
      "Admissibility exposes apply"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; new Admissibility(Vector.empty)"),
      "Admissibility exposes its constructor"
    )
  }

  test("StructuralCoverage has no product door; construction goes through the checked factory") {
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StructuralCoverage]]"
      ),
      "StructuralCoverage derives a Mirror; fromProduct would admit (0, 2, 1)"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralCoverage.fromProduct(???)"),
      "the StructuralCoverage companion exposes fromProduct"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : StructuralCoverage).copy()"),
      "StructuralCoverage exposes copy"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralCoverage(0, 2, 1)"),
      "StructuralCoverage exposes apply"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; new StructuralCoverage(0, 2, 1)"),
      "StructuralCoverage exposes its constructor"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralCoverage.counted(0, Vector(true))"),
      "the by-construction builder is visible outside align"
    )
    assert(
      typeChecks("import storymodel4s.align.*; StructuralCoverage.of(0, 1, 1)"),
      "control: the checked factory is public"
    )
  }

  test("the checked factory refuses exactly the count violations") {
    assert(StructuralCoverage.of(0, 2, 1).isLeft, "more evidenced members than members")
    assert(StructuralCoverage.of(0, -1, 1).isLeft, "negative evidenced count")
    assert(StructuralCoverage.of(0, -1, -1).isLeft, "negative member count")
    val lawful = StructuralCoverage.of(2, 1, 3).fold(e => fail(e.message), identity)
    assertEquals(
      (lawful.level, lawful.membersWithEvidence, lawful.members),
      (2, 1, 3)
    )
    assertEquals(StructuralCoverage.of(0, 0, 0).map(_.isEmpty), Right(true))
    assertEquals(StructuralCoverage.of(1, 3, 3).map(_.isComplete), Right(true))
    // Level is a single-field range enforced by AlignWire, not by this type (source.scala doc).
    assert(StructuralCoverage.of(-1, 0, 1).isRight)
  }

  test("StructuralCoverage has structural equality after the case-class conversion") {
    assertEquals(StructuralCoverage.of(1, 2, 3), StructuralCoverage.of(1, 2, 3))
    assertNotEquals(StructuralCoverage.of(1, 2, 3), StructuralCoverage.of(1, 2, 4))
    assertEquals(
      StructuralCoverage.of(1, 2, 3).map(_.hashCode),
      StructuralCoverage.of(1, 2, 3).map(_.hashCode)
    )
  }
