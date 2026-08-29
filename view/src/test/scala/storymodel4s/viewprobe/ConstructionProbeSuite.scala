package storymodel4s.viewprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Probes the public surface-support construction boundary from outside `storymodel4s.view`. */
class ConstructionProbeSuite extends FunSuite:

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not forge a surface-detail support report")

  test("positive control: a same-shaped case class exposes every prohibited forge") {
    val errors = typeCheckErrors(
      """{
           final case class SupportCanary(
             availableKinds: Set[storymodel4s.core.SurfaceUnitKind],
             supportedDetails: Set[storymodel4s.view.SurfaceDetail],
             unsupportedTokenIds: Vector[storymodel4s.core.SurfaceUnitId]
           )
           val applied = SupportCanary(Set.empty, Set.empty, Vector.empty)
           val copied = applied.copy(supportedDetails = Set(storymodel4s.view.SurfaceDetail.Tokens))
           val fields = (Set.empty[storymodel4s.core.SurfaceUnitKind],
             Set.empty[storymodel4s.view.SurfaceDetail],
             Vector.empty[storymodel4s.core.SurfaceUnitId])
           val mirrorForged = summon[scala.deriving.Mirror.ProductOf[SupportCanary]]
             .fromProduct(fields)
           (applied, copied, mirrorForged)
         }"""
    )
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("the public inspector and support observations remain visible") {
    val errors = typeCheckErrors(
      """(atlas: storymodel4s.core.SurfaceAtlas) =>
           val support = storymodel4s.view.SurfaceDetailSupport.inspect(atlas)
           (support.availableKinds, support.supportedDetails, support.unsupportedTokenIds,
            support.supports(storymodel4s.view.SurfaceDetail.Hidden))"""
    )
    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("SurfaceDetailSupport has no public apply, copy, or fromProduct forge") {
    refused(
      typeCheckErrors(
        """storymodel4s.view.SurfaceDetailSupport(
             Set.empty[storymodel4s.core.SurfaceUnitKind],
             Set(storymodel4s.view.SurfaceDetail.Tokens),
             Vector.empty[storymodel4s.core.SurfaceUnitId]
           )"""
      ),
      "SurfaceDetailSupport.apply"
    )
    refused(
      typeCheckErrors(
        """(support: storymodel4s.view.SurfaceDetailSupport) =>
             support.copy(supportedDetails = Set(storymodel4s.view.SurfaceDetail.Tokens))"""
      ),
      "SurfaceDetailSupport.copy"
    )
    refused(
      typeCheckErrors("storymodel4s.view.SurfaceDetailSupport.fromProduct(EmptyTuple)"),
      "SurfaceDetailSupport.fromProduct"
    )
  }
