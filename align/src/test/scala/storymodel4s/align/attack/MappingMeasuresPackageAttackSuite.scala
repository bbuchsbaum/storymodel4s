package storymodel4s.align.attack

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*

class MappingMeasuresPackageAttackSuite extends FunSuite:
  private val dependencies = Vector(
    classOf[DerivationBinding],
    classOf[RawScores],
    classOf[ModelPosterior],
    classOf[TransportMass],
    classOf[MappingLink],
    classOf[CalibratedProbability]
  )
  test("checked measure builders are public") {
    assertEquals(dependencies.size, 6)
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; DerivationBinding.of(??? : HsmmResult, ??? : RecallGraph[Checked], ??? : RecallInventory, ??? : SourceView, ??? : SourceRepresentation)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; ModelPosterior.of(??? : HsmmResult, ??? : RecallUnitId, ??? : DerivationBinding, ??? : StageEntryId)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; RawScores.of("a", ScoreDirection.HigherIsBetter, "b", Map.empty, ??? : StageEntryId)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; MappingLink.ungated(??? : Destination, ??? : UnitStageRefs, ??? : CandidateSetId)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; MappingLink.fromResult(??? : HsmmResult, ??? : DerivationBinding, ??? : RecallGraph[Checked], ??? : SourceView, ??? : SourceRepresentation, ??? : RecallUnitId, ??? : AlignState, ??? : UnitStageRefs, ??? : CandidateSetId)"""
      ).isEmpty
    )
  }
  test("posterior requires a result not a matrix or transport") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; ModelPosterior.of(??? : AlignmentMatrix, ??? : RecallUnitId, ??? : DerivationBinding, ??? : StageEntryId)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; val posterior: ModelPosterior = (??? : TransportMass)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; type Alias = TransportMass; val posterior: ModelPosterior = (??? : Alias)"""
      ).nonEmpty
    )
  }
  test("gated and assessed statuses have no caller door") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new GateOutcome.NoContradictionDetected()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; GateOutcome.NoContradictionDetected.derived()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new GateOutcome.Contradicted(??? : NonEmptySet[Facet])"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; GateOutcome.Contradicted.derived(??? : NonEmptySet[Facet])"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new FidelityStatus.Assessed(??? : FidelityReport)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; FidelityStatus.Assessed.derived(??? : FidelityReport)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new TermSupportStatus.Evaluated(??? : SupportAssessment)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; TermSupportStatus.Evaluated.derived(??? : SupportAssessment)"""
      ).nonEmpty
    )
  }
  test("calibrated probability has no door") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new CalibratedProbability(??? : CalibratedEvent, ??? : Probability, ??? : CalibrationArtifactId)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; CalibratedProbability.of(??? : CalibratedEvent, ??? : Probability, ??? : CalibrationArtifactId)"""
      ).nonEmpty
    )
  }
  test("binding and measure products require checked constructors") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new DerivationBinding(??? : Checksum, ??? : Checksum, ??? : Checksum, ??? : ViewFingerprint, ??? : Checksum, ??? : Checksum)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new RawScores("a", ScoreDirection.HigherIsBetter, "b", Map.empty, ??? : StageEntryId, MeasureDerivation.Supplied)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new NormalizedScoreMass(??? : TargetUniverseId, ??? : ReferencePriorId, 1.0, Map.empty, ??? : StageEntryId)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new TransportMass(1.0, Map.empty, ??? : StageEntryId)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new ModelPosterior(??? : RecallUnitId, Map.empty, ??? : StageEntryId, ??? : DerivationBinding)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new UnitMeasures(Vector.empty, None, None, None)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new MappingLink(??? : Destination, ??? : GateOutcome, ??? : FidelityStatus, ??? : TermSupportStatus, ??? : StageEntryId, ??? : CandidateSetId, MeasureDerivation.Supplied)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; new MeasureDerivation.FromResult(??? : DerivationBinding, ??? : RecallUnitId)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; MeasureDerivation.FromResult.checked(??? : DerivationBinding, ??? : RecallUnitId)"""
      ).nonEmpty
    )
  }
  test("measure products have no generic reconstruction") {
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: DerivationBinding) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; DerivationBinding.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[DerivationBinding]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: RawScores) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; RawScores.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[RawScores]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: NormalizedScoreMass) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; NormalizedScoreMass.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[NormalizedScoreMass]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: TransportMass) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; TransportMass.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[TransportMass]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: ModelPosterior) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; ModelPosterior.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[ModelPosterior]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: UnitMeasures) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; UnitMeasures.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[UnitMeasures]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: MappingLink) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; MappingLink.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[MappingLink]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: MeasureDerivation.FromResult) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; MeasureDerivation.FromResult.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[MeasureDerivation.FromResult]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: GateOutcome.NoContradictionDetected) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; GateOutcome.NoContradictionDetected.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[GateOutcome.NoContradictionDetected]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: GateOutcome.Contradicted) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; GateOutcome.Contradicted.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[GateOutcome.Contradicted]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: FidelityStatus.Assessed) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; FidelityStatus.Assessed.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[FidelityStatus.Assessed]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: TermSupportStatus.Evaluated) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; TermSupportStatus.Evaluated.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[TermSupportStatus.Evaluated]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; (x: CalibratedProbability) => x.copy()"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; CalibratedProbability.fromProduct(EmptyTuple)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.recall.RecallGraphStatus.Checked; import cats.data.*; summon[scala.deriving.Mirror.ProductOf[CalibratedProbability]]"""
      ).nonEmpty
    )
  }
