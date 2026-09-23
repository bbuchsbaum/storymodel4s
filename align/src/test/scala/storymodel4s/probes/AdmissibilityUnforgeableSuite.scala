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

  // Each door is its own test with a same-shape positive control, so a mutant that reopens one door
  // fails that door's test by name while the others pass. `typeChecks` is false for ANY error (and
  // needs a literal), so a refusal without a control that can pass proves nothing. The control,
  // FunctionPrior, is a public case class in align that is meant to be constructible.
  test(
    "door Mirror.ProductOf: refused for Admissibility and StructuralCoverage, open for the control"
  ) {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[FunctionPrior]]"
      ),
      "control does not compile"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[Admissibility]]"
      ),
      "Admissibility Mirror.ProductOf is open"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[StructuralCoverage]]"
      ),
      "StructuralCoverage Mirror.ProductOf is open"
    )
  }

  test(
    "door companion fromProduct: refused for Admissibility and StructuralCoverage, open for the control"
  ) {
    assert(
      typeChecks("import storymodel4s.align.*; FunctionPrior.fromProduct(???)"),
      "control does not compile"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; Admissibility.fromProduct(???)"),
      "Admissibility companion fromProduct is open"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralCoverage.fromProduct(???)"),
      "StructuralCoverage companion fromProduct is open"
    )
  }

  test("door copy: refused for Admissibility and StructuralCoverage, open for the control") {
    assert(
      typeChecks("import storymodel4s.align.*; (??? : FunctionPrior).copy()"),
      "control does not compile"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : Admissibility).copy()"),
      "Admissibility copy is open"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : StructuralCoverage).copy()"),
      "StructuralCoverage copy is open"
    )
  }

  test("door apply: refused for Admissibility and StructuralCoverage, open for the control") {
    assert(
      typeChecks("import storymodel4s.align.*; FunctionPrior(Map.empty)"),
      "control does not compile"
    )
    assert(typeChecks("AdmissibilityShape(Vector.empty)"), "same-signature control")
    assert(typeChecks("CoverageShape(0, 2, 1)"), "same-signature control")
    assert(
      !typeChecks("import storymodel4s.align.*; Admissibility(Vector.empty)"),
      "Admissibility apply is open"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralCoverage(0, 2, 1)"),
      "StructuralCoverage apply is open"
    )
  }

  test("door new: refused for Admissibility and StructuralCoverage, open for the control") {
    assert(
      typeChecks("import storymodel4s.align.*; new FunctionPrior(Map.empty)"),
      "control does not compile"
    )
    assert(typeChecks("new AdmissibilityShape(Vector.empty)"), "same-signature control")
    assert(typeChecks("new CoverageShape(0, 2, 1)"), "same-signature control")
    assert(
      !typeChecks("import storymodel4s.align.*; new Admissibility(Vector.empty)"),
      "Admissibility new is open"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; new StructuralCoverage(0, 2, 1)"),
      "StructuralCoverage new is open"
    )
  }

  test("only the checked StructuralCoverage factory is public; the by-construction one is not") {
    assert(
      typeChecks("import storymodel4s.align.*; StructuralCoverage.of(0, 1, 1)"),
      "control: the checked factory"
    )
    assert(
      typeChecks(
        "import storymodel4s.align.*; classOf[Admissibility]; classOf[StructuralCoverage]"
      ),
      "visibility"
    )
    assert(typeChecks("CoverageShape.counted(0, Vector(true))"), "same-signature control")
    assert(
      !typeChecks("import storymodel4s.align.*; StructuralCoverage.counted(0, Vector(true))"),
      "the by-construction builder is visible outside align"
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

/** Same-signature positive controls for the apply, new and counted probes (AGENTS rule 8): each
  * refusal above is paired with a snippet of identical shape that is meant to compile, so a refusal
  * cannot pass because of an argument-shape error.
  */
final case class AdmissibilityShape(contradictions: Vector[storymodel4s.align.Contradiction])

final case class CoverageShape(level: Int, membersWithEvidence: Int, members: Int)

object CoverageShape:
  def counted(level: Int, evidenced: Vector[Boolean]): CoverageShape =
    CoverageShape(level, evidenced.count(identity), evidenced.size)
