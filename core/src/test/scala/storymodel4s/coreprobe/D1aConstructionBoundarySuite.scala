package storymodel4s.coreprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class D1aConstructionBoundarySuite extends FunSuite:
  test("accepting control: same-module value carriers retain construction doors"):
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SurfaceUnit]]"
      ).isEmpty
    )
    assert(typeCheckErrors("storymodel4s.core.SurfaceUnit.fromProduct(null)").isEmpty)
    assert(typeCheckErrors("(x: storymodel4s.core.SurfaceUnit) => x.copy(ordinal = 1)").isEmpty)
    assert(
      typeCheckErrors(
        "storymodel4s.core.SurfaceUnit(storymodel4s.core.SurfaceUnitId.unsafe(\"u\"), storymodel4s.core.SurfaceUnitKind.Token, storymodel4s.core.TextSpan.unsafe(0, 1), 1, None)"
      ).isEmpty
    )

  test("NarrativeProposalUnit has no unchecked constructor, copy or Product doors"):
    assert(
      typeCheckErrors("new storymodel4s.core.NarrativeProposalUnit(null, null, None)").nonEmpty
    )
    assert(typeCheckErrors("storymodel4s.core.NarrativeProposalUnit(null, null, None)").nonEmpty)
    assert(
      typeCheckErrors(
        "(x: storymodel4s.core.NarrativeProposalUnit) => x.copy(surface = None)"
      ).nonEmpty
    )
    assert(typeCheckErrors("storymodel4s.core.NarrativeProposalUnit.fromProduct(null)").nonEmpty)
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.NarrativeProposalUnit]]"
      ).nonEmpty
    )

  test("AnchoredNarrativeAtlas has no unchecked constructor, copy or Product doors"):
    assert(
      typeCheckErrors(
        "new storymodel4s.core.AnchoredNarrativeAtlas(null, Vector.empty, None)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors("storymodel4s.core.AnchoredNarrativeAtlas(null, Vector.empty, None)").nonEmpty
    )
    assert(
      typeCheckErrors(
        "(x: storymodel4s.core.AnchoredNarrativeAtlas) => x.copy(units = Vector.empty)"
      ).nonEmpty
    )
    assert(typeCheckErrors("storymodel4s.core.AnchoredNarrativeAtlas.fromProduct(null)").nonEmpty)
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.AnchoredNarrativeAtlas]]"
      ).nonEmpty
    )

  test("BoundProposalSurface has no unchecked constructor, copy or Product doors"):
    assert(
      typeCheckErrors("new storymodel4s.core.BoundProposalSurface(null, null, null, null)").nonEmpty
    )
    assert(
      typeCheckErrors("storymodel4s.core.BoundProposalSurface(null, null, null, null)").nonEmpty
    )
    assert(
      typeCheckErrors(
        "(x: storymodel4s.core.BoundProposalSurface) => x.copy(receipt = null)"
      ).nonEmpty
    )
    assert(typeCheckErrors("storymodel4s.core.BoundProposalSurface.fromProduct(null)").nonEmpty)
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.BoundProposalSurface]]"
      ).nonEmpty
    )

  test("NarrativeSourceAtlas cannot be extended outside core"):
    assert(typeCheckErrors("""class ForeignAtlas extends storymodel4s.core.NarrativeSourceAtlas {
      def bundle: storymodel4s.core.SourceBundle = ???
      def units: Vector[storymodel4s.core.NarrativeProposalUnit] = Vector.empty
      def unit(id: storymodel4s.core.NarrativeProposalUnitId): Option[storymodel4s.core.NarrativeProposalUnit] = None
      def supportOf(id: storymodel4s.core.NarrativeProposalUnitId): Option[storymodel4s.core.EvidenceSupport] = None
    }""").nonEmpty)

  test("checked factories remain visible"):
    assert(typeCheckErrors("storymodel4s.core.SurfaceAtlasConformance.bundleOf(null)").isEmpty)
    assert(
      typeCheckErrors(
        "storymodel4s.core.NarrativeProposalUnit.of(storymodel4s.core.NarrativeProposalUnitId.unsafe(\"u\"), null, None)"
      ).isEmpty
    )
    assert(
      typeCheckErrors("storymodel4s.core.AnchoredNarrativeAtlas.of(null, Vector.empty)").isEmpty
    )
    assert(typeCheckErrors("storymodel4s.core.BoundProposalSurface.of(null, null)").isEmpty)
