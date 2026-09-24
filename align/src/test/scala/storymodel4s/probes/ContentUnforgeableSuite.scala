package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks

/** Construction and read boundaries of the S2a-2 content types, probed from outside package `align`
  * (mote bd-01M379MH86VMN6SNVNRH32G3G6; ADR 0019 S2).
  *
  * Two properties. The types cannot be built except by `ContentProjection`, so a caller cannot hand
  * a strict scorer content that did not come from a projection. And they expose no coordinate: the
  * reads a strict scorer must not make fail to typecheck here, each beside the same read on the
  * coordinate-bearing original, which does typecheck.
  */
class ContentUnforgeableSuite extends FunSuite:

  test("door new: refused for the content types, open for a public class") {
    assert(typeChecks("new StringBuilder()"), "control does not compile")
    assert(!typeChecks("import storymodel4s.align.*; new ContentGrain(1)"), "ContentGrain new")
    assert(
      !typeChecks("import storymodel4s.align.*; new Members(Vector.empty, Vector.empty)"),
      "Members new"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.recall.*; " +
          "new UnitContent(DiscourseFunction.EpisodicAssertion, None, Vector.empty, " +
          "PolarityTag.Unknown, ModalityTag.Asserted, None, Vector.empty, Set.empty, None)"
      ),
      "UnitContent new"
    )
  }

  test("doors Mirror.ProductOf, fromProduct and copy: absent for the content types") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[FunctionPrior]]"
      ),
      "control does not compile"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[ContentGrain]]"
      ),
      "ContentGrain Mirror"
    )
    assert(!typeChecks("import storymodel4s.align.*; ContentGrain.fromProduct(???)"), "fromProduct")
    assert(!typeChecks("import storymodel4s.align.*; (??? : ContentGrain).copy()"), "copy")
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).copy()"),
      "UnitContent copy"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).copy()"),
      "TargetContent copy"
    )
  }

  test("a unit's coordinates are readable on RecallUnit and not on UnitContent") {
    assert(typeChecks("import storymodel4s.recall.*; (??? : RecallUnit).id"), "control id")
    assert(typeChecks("import storymodel4s.recall.*; (??? : RecallUnit).span"), "control span")
    assert(typeChecks("import storymodel4s.recall.*; (??? : RecallUnit).text"), "control text")
    assert(
      typeChecks("import storymodel4s.recall.*; (??? : RecallUnit).ordinal"),
      "control ordinal"
    )
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).id"), "id")
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).span"), "span")
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).text"), "text")
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).ordinal"), "ordinal")
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).evidence"), "evidence")
  }

  test("a node's coordinates are readable on NodeSummary and not on TargetContent") {
    assert(typeChecks("import storymodel4s.align.*; (??? : NodeSummary).ref"), "control ref")
    assert(
      typeChecks("import storymodel4s.align.*; (??? : NodeSummary).support"),
      "control support"
    )
    assert(
      typeChecks("import storymodel4s.align.*; (??? : NodeSummary).discoursePosition"),
      "control position"
    )
    assert(!typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).ref"), "ref")
    assert(!typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).parent"), "parent")
    assert(!typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).support"), "support")
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).discoursePosition"),
      "discoursePosition"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).scoringPosition"),
      "scoringPosition"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).importance"),
      "importance"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).evidence"),
      "evidence"
    )
  }

  test("members cannot be iterated from outside align; only reductions are public") {
    assert(typeChecks("import storymodel4s.align.*; (??? : Members[?]).count"), "control count")
    assert(!typeChecks("import storymodel4s.align.*; (??? : Members[?]).structural"), "structural")
    assert(!typeChecks("import storymodel4s.align.*; (??? : Members[?]).leaves"), "leaves")
    assert(!typeChecks("import storymodel4s.align.*; (??? : Members[?]).iterator"), "iterator")
    assert(!typeChecks("import storymodel4s.align.*; (??? : Members[?]).toVector"), "toVector")
  }

  test("the source-order projection is not public") {
    assert(
      typeChecks("import storymodel4s.align.*; ContentProjection.canonical(???, ???, ???)"),
      "control: the canonical projection"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; ContentProjection.source(???, ???, ???)"),
      "source projection is visible outside align"
    )
  }
