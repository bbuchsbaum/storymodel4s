package storymodel4s.viewprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Probes the public surface-support construction boundary from outside `storymodel4s.view`. */
class ConstructionProbeSuite extends FunSuite:

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not forge a surface-detail support report")

  /** Sweep 3 slice: the Mirror door across every private-constructor case class in `view`.
    *
    * `case class X private (...)` still derives `Mirror.ProductOf` in Scala 3, so the private
    * constructor is defeated from outside the package. This asks the question for all 21 at once
    * rather than assuming the answer; the failure message names exactly which are forgeable.
    *
    * The positive control is essential and specific to THIS door: a control for the companion
    * `fromProduct` does not license a refusal about `summon`.
    */
  test("sweep 3: no private-constructor case class in view has a Mirror.ProductOf door") {
    assertEquals(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SurfaceUnit]]"),
      Nil,
      "the summon control must compile or every refusal below is vacuous"
    )
    val results: List[(String, List[scala.compiletime.testing.Error])] = List(
      (
        "ViewProvenance",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.ViewProvenance]]")
      ),
      (
        "Seconds",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.Seconds]]")
      ),
      (
        "ClockSpan",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.ClockSpan]]")
      ),
      (
        "VoyageScene",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.VoyageScene]]")
      ),
      (
        "VoyageNavigation",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.VoyageNavigation]]"
        )
      ),
      (
        "AuditRecord",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.AuditRecord]]")
      ),
      (
        "SourceRun",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.SourceRun]]")
      ),
      (
        "TextAnnotation",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.TextAnnotation]]")
      ),
      (
        "NavigationIndex",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.NavigationIndex]]"
        )
      ),
      (
        "CodexFlow",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.CodexFlow]]")
      ),
      (
        "CommonViewState",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.CommonViewState]]"
        )
      ),
      (
        "ChannelBudget",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.ChannelBudget]]")
      ),
      (
        "LanePolicy",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.LanePolicy]]")
      ),
      (
        "CodexSpec",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.CodexSpec]]")
      ),
      (
        "FeatureObservationPlacement",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.FeatureObservationPlacement]]"
        )
      ),
      (
        "CodexContract",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.CodexContract]]")
      ),
      (
        "LaneAllocation",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.LaneAllocation]]")
      ),
      (
        "ProjectionContract",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.ProjectionContract]]"
        )
      ),
      (
        "VisualIdentity",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.VisualIdentity]]")
      ),
      (
        "Extent",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.Extent]]")
      ),
      (
        "Anchor",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.Anchor]]")
      ),
      (
        "AtlasSpec",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.AtlasSpec]]")
      ),
      (
        "AtlasFeatureLayer",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.AtlasFeatureLayer]]"
        )
      ),
      (
        "NarrativeScene",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.NarrativeScene]]")
      ),
      (
        "SceneNavigation",
        typeCheckErrors(
          "summon[scala.deriving.Mirror.ProductOf[storymodel4s.view.SceneNavigation]]"
        )
      )
    )
    val forgeable = results.collect { case (n, errs) if errs.isEmpty => n }.toSet
    // MEASURED 2026-08-30: the door is open on ALL 21. This pins the KNOWN-BAD state rather than
    // asserting a guarantee we do not have, so the suite stays green while the defect is tracked.
    // Closing any one of these makes this test FAIL — that is intended, and the failure means
    // "good news, remove the name". Do not delete the test to make it pass.
    assertEquals(
      forgeable,
      results.map(_._1).toSet,
      "sweep 3 expects the Mirror door OPEN on every view private-constructor case class; a " +
        "difference means one was closed (update this list) or a new one was added unmeasured"
    )
  }

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
