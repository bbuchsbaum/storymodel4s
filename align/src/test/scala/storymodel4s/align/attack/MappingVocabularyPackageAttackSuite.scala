package storymodel4s.align.attack
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*
class MappingVocabularyPackageAttackSuite extends FunSuite:
  private val dependencies = Vector(
    classOf[StageEntryId],
    classOf[CandidateSetId],
    classOf[TargetUniverseId],
    classOf[StageProvenance.Derived],
    classOf[StageLedger]
  )
  test("public builder control") {
    assertEquals(dependencies.size, 5)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*;  StageEntry.of(Stage.Scoring, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*;  StageLedger.of(Stage.values.toVector.map(s => StageEntry.of(s, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None)).toOption.get))"""
      ).isEmpty
    )
  }
  test("identifiers have no string door") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; StageEntryId.from("0" * 64)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; CandidateSetId.from("0" * 64)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; TargetUniverseId.from("0" * 64)"""
      ).nonEmpty
    )
  }
  test("no Derived door") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new StageProvenance.Derived(??? : StageReceipt)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; StageProvenance.Derived(??? : StageReceipt)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; StageProvenance.Derived.of(??? : StageReceipt)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; StageProvenance.Derived.fromProduct(Tuple1(??? : StageReceipt))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; (d: StageProvenance.Derived) => d.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[StageProvenance.Derived]]"""
      ).nonEmpty
    )
  }
  test("checked components have no raw product construction") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new StageEntryId(??? : Checksum)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new CandidateSetId(??? : Checksum)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new TargetUniverseId(??? : Checksum)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new StageLedger(Vector.empty)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; new StageProvenance.Unknown(UnknownProvenanceReason.NotRun, None)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; StageProvenance.Unknown.of(UnknownProvenanceReason.NotRun, None)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; StageLedger.fromProduct(Tuple1(Vector.empty))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; (x: StageLedger) => x.copy(entries = Vector.empty)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[StageLedger]]"""
      ).nonEmpty
    )
  }
