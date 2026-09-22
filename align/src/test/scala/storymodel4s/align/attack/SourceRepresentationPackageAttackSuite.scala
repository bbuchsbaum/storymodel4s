package storymodel4s.align.attack

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*

class SourceRepresentationPackageAttackSuite extends FunSuite:
  private val dependencies = Vector(
    classOf[SourceRepresentation],
    classOf[DeclaredComposition],
    classOf[SourceRepresentation.Target],
    classOf[SupportCoverage.Complete],
    classOf[SourceSupportStatus.Located]
  )
  test("checked source builders are public") {
    assertEquals(dependencies.size, 5)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceRepresentation.of(??? : SourceView, ??? : NonEmptyVector[BundleEntry], None, Map.empty)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; DeclaredComposition.of(??? : SourceBundle, ??? : NonEmptyVector[SourceBundle])"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceSupportStatus.located(??? : TypedSupport)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource)"""
      ).isEmpty
    )
  }
  test("source and composition have no raw construction") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; new SourceRepresentation(??? : NonEmptyVector[BundleEntry], None, Vector.empty, ??? : ViewFingerprint, ??? : Checksum)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; new DeclaredComposition(??? : SourceBundle, ??? : NonEmptyVector[SourceBundle])"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceRepresentation(??? : NonEmptyVector[BundleEntry], None, Vector.empty, ??? : ViewFingerprint, ??? : Checksum)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; DeclaredComposition(??? : SourceBundle, ??? : NonEmptyVector[SourceBundle])"""
      ).nonEmpty
    )
  }
  test("target and coverage are owner derived") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; new SourceRepresentation.Target(??? : SourceNodeRef, 0, None, ??? : SourceSupportStatus, Map.empty, ??? : SupportCoverage, PropositionalScope.Declared)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceRepresentation.Target.derived(??? : NodeSummary, ??? : SourceSupportStatus, Map.empty, ??? : SupportCoverage)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceRepresentation.Coverage.derive(??? : SourceNodeRef, Vector.empty, Map.empty)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; new SupportCoverage.Complete()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SupportCoverage.Complete.derived()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; new SupportCoverage.Partial(??? : NonEmptySet[SourceNodeRef])"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SupportCoverage.Partial.derived(??? : NonEmptySet[SourceNodeRef])"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; new SupportCoverage.Unknown(CoverageUnknownReason.TargetUnlocated)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SupportCoverage.Unknown.derived()"""
      ).nonEmpty
    )
  }
  test("support products have no unchecked owner door") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; new SourceSupportStatus.Located(??? : TypedSupport)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceSupportStatus.Located.of(??? : TypedSupport)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; new SourceSupportStatus.Unlocated(UnlocatedReason.NoLocusInSource)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceSupportStatus.Unlocated.of(UnlocatedReason.NoLocusInSource)"""
      ).nonEmpty
    )
  }
  test("checked source products have no generic reconstruction") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; (x: SourceRepresentation) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceRepresentation.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[SourceRepresentation]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; (x: DeclaredComposition) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; DeclaredComposition.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[DeclaredComposition]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; (x: SourceRepresentation.Target) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceRepresentation.Target.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[SourceRepresentation.Target]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; (x: SupportCoverage.Complete) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SupportCoverage.Complete.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[SupportCoverage.Complete]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; (x: SupportCoverage.Partial) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SupportCoverage.Partial.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[SupportCoverage.Partial]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; (x: SupportCoverage.Unknown) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SupportCoverage.Unknown.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[SupportCoverage.Unknown]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; (x: SourceSupportStatus.Located) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceSupportStatus.Located.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[SourceSupportStatus.Located]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; (x: SourceSupportStatus.Unlocated) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; SourceSupportStatus.Unlocated.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[SourceSupportStatus.Unlocated]]"""
      ).nonEmpty
    )
  }
