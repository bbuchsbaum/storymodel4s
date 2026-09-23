package storymodel4s.coreprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** External probes with an independent positive control for each construction door. */
class TextSourceConstructionSuite extends FunSuite:
  test("checked factory compiles but package construction, copy and product doors do not"):
    assert(typeCheckErrors("storymodel4s.core.TextSourcePackage.fromText(\"A.\")").isEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      def forge(a: SurfaceAtlas, n: TextNarrativeAtlas) = new TextSourcePackage(a, n, TextSegmentationProfile.SuppliedAtlasV1, Vector.empty)
    """).nonEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      def forge(p: TextSourcePackage) = p.copy(segments = Vector.empty)
    """).nonEmpty)
    assert(typeCheckErrors("storymodel4s.core.TextSourcePackage.fromProduct(EmptyTuple)").nonEmpty)
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.TextSourcePackage]]"
      ).nonEmpty
    )
    assert(typeCheckErrors("""import storymodel4s.core.*
      def forge(id: SegmentId, u: SurfaceUnit) = new TextSourcePackage.Segment(id, u)
    """).nonEmpty)

  test("all four product-door controls compile for an honest product of the same fields"):
    assert(typeCheckErrors("""import storymodel4s.core.*
      case class Control(atlas: SurfaceAtlas, sourceAtlas: TextNarrativeAtlas, profile: TextSegmentationProfile, segments: Vector[TextSourceSegment])
      def make(a: SurfaceAtlas, n: TextNarrativeAtlas) = new Control(a, n, TextSegmentationProfile.SuppliedAtlasV1, Vector.empty)
    """).isEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      case class Control(atlas: SurfaceAtlas, sourceAtlas: TextNarrativeAtlas, profile: TextSegmentationProfile, segments: Vector[TextSourceSegment])
      def rebuild(c: Control) = c.copy(segments = Vector.empty)
    """).isEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      case class Control(atlas: SurfaceAtlas, sourceAtlas: TextNarrativeAtlas, profile: TextSegmentationProfile, segments: Vector[TextSourceSegment])
      object Control:
        def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p)
      def rebuild(c: Control) = Control.fromProduct(c)
    """).isEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      case class Control(atlas: SurfaceAtlas, sourceAtlas: TextNarrativeAtlas, profile: TextSegmentationProfile, segments: Vector[TextSourceSegment])
      def rebuild(c: Control) = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(c)
    """).isEmpty)

  test("segment product doors are unavailable with matching positive controls"):
    assert(typeCheckErrors("""import storymodel4s.core.*
      def forge(s: TextSourceSegment) = s.copy(id = s.id)
    """).nonEmpty)
    assert(
      typeCheckErrors(
        "storymodel4s.core.TextSourcePackage.Segment.fromProduct(EmptyTuple)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.TextSourceSegment]]"
      ).nonEmpty
    )
    assert(typeCheckErrors("""import storymodel4s.core.*
      case class Control(id: SegmentId, unit: SurfaceUnit)
      object Control:
        def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p)
      def construct(id: SegmentId, u: SurfaceUnit) = new Control(id, u)
      def copy(c: Control) = c.copy(id = c.id)
      def rebuild(c: Control) = Control.fromProduct(c)
      def mirror(c: Control) = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(c)
    """).isEmpty)
