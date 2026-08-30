package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks

/** The construction boundary of `CellCoordinates`, probed FROM OUTSIDE package `align`.
  *
  * The boundary is `private[align]`, so it only bites out here - an in-package court would call the
  * constructor legitimately and fail while the type was perfectly sealed. That is not hypothetical:
  * the first version of this court lived in `storymodel4s.align` and reported "public apply is
  * available" against a correctly private constructor. A court has to stand where the wall is.
  *
  * Why this type needs a boundary at all: lawful unit A, lawful node A and a lawful breakdown B
  * form a FALSE TUPLE when B was computed for a different cell. `CellCoordinates.of` proves the
  * relation by looking the breakdown up in the result rather than accepting one, and by checking
  * the node against the state's anchor. Product construction would restore exactly the tuple the
  * factory exists to refuse - a trace naming words for a cell it did not come from.
  */
class CellCoordinatesUnforgeableSuite extends FunSuite:

  test("no product door reconstructs the type from outside align") {
    // POSITIVE CONTROLS FIRST, same shape as the negatives: `typeChecks` returns false when a
    // snippet fails for ANY reason, so a negative probe without a matching positive proves nothing.
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[CostBreakdown]]"
      ),
      "control: a case class in align DOES derive a Mirror from here, so the probe mechanism works"
    )
    assert(
      typeChecks("import storymodel4s.align.*; classOf[CellCoordinates]"),
      "control: the type itself is visible from outside align"
    )

    // NEGATIVES: every product door named by the construction-boundary rule.
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[CellCoordinates]]"
      ),
      "CellCoordinates derives a Mirror; fromProduct would rebuild the false tuple"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; CellCoordinates.fromProduct(???)"),
      "the companion exposes fromProduct"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : CellCoordinates).copy()"),
      "copy is available and would rebuild the tuple field by field"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; CellCoordinates(???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "public apply or constructor is available from outside align"
    )
  }

  test("the checked factory is the door that remains open") {
    // The boundary must not be a wall with no gate: `of` has to stay reachable from outside align,
    // or the type is unusable and the courts above would pass vacuously on an inaccessible type.
    assert(
      typeChecks(
        "import storymodel4s.align.*; (r: HsmmResult, g: storymodel4s.recall.RecallGraph[storymodel4s.recall.RecallGraphStatus.Checked], v: SourceView, i: storymodel4s.recall.RecallUnitId, s: AlignState) => CellCoordinates.of(r, g, v, i, s)"
      ),
      // STALE ON THE PREVIOUS REVISION: this probed the obsolete four-argument signature, so it
      // reported the real factory unreachable. It survived my own gate because `typeChecks` is a
      // COMPILE-TIME macro and incremental compilation served the expansion from before the
      // signature changed - the same `alignJVM/test` gave 220/0 then and 220/1 on a fresh build.
      "the checked factory is not reachable from outside align"
    )
  }
