package storymodel4s.laws

import munit.DisciplineSuite
import org.scalacheck.Arbitrary

class LawsSuite extends DisciplineSuite:
  import ChartGens.given
  import StoryGens.given
  import InvalidStoryGens.given

  given Arbitrary[AlignGens.Case] = Arbitrary(AlignGens.alignCase)
  given Arbitrary[AlignGens.FoilCase] = Arbitrary(AlignGens.foilCase)

  checkAll("ChartLaws", ChartLaws.chart)
  checkAll("StoryLaws", StoryLaws.validator)
  checkAll("TemporalLaws", TemporalLaws.temporal)
  checkAll("AlignmentLaws", AlignmentLaws.alignment)
  checkAll("ModeGateLaws", ModeGateLaws.modeGate)
  checkAll("GateProofLaws", GateProofLaws.gateProof)
  checkAll("WireLaws", WireLaws.wire)
  checkAll("PopulationLaws", PopulationLaws.population)
  checkAll("EstimateLaws", EstimateLaws.estimates)
  checkAll("SourceViewLaws", SourceViewLaws.sourceView)

  // POSITIVE CONTROL for SourceViewLaws. Both real implementations satisfy the totality
  // contract, so the law above passes without ever demonstrating it CAN fail -- and a law with
  // no capacity to fail is not evidence. This foil violates the contract in the cheapest
  // possible way: it exposes a ref through `adjacency` that its node index does not contain.
  test(
    "SourceView totality: the law rejects a view whose adjacency names a ref outside its node index"
  ) {
    import storymodel4s.align.*
    import storymodel4s.core.SituationId
    val phantom = SourceNodeRef.Situation(SituationId.unsafe("ghost"))
    object Foil extends SourceView:
      val nodes: Vector[NodeSummary] = Vector.empty
      def node(ref: SourceNodeRef): Option[NodeSummary] = None
      def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
        Map(phantom -> Map.empty)
      def worldOrder: Option[Map[SourceNodeRef, Int]] = None
      def textLength: Int = 100

    assert(SourceViewLaws.exposedRefs(Foil).contains(phantom), "the foil must expose the ref")
    assert(!SourceViewLaws.total(Foil), "the totality law must REJECT this view")
    assert(!SourceViewLaws.positionsAreMeasured(Foil), "the span law must REJECT this view")

    // And this is why the contract is load-bearing rather than tidy: the violation is silent.
    // Nothing throws; the phantom is simply reported as occurring at the very start of the
    // discourse, which is a real position and indistinguishable from a measured one.
    assertEquals(Foil.relativeSpan(phantom), None)
    assertEquals(Foil.relativePosition(phantom), 0.0)
  }

  test("HsmmResult, rows, matrices, and admissibility cannot be constructed outside align") {
    import scala.compiletime.testing.typeCheckErrors
    // A `private[align]` constructor reports "cannot be accessed"; the companion `apply` it
    // generates is simply invisible here, which the compiler reports as "does not take
    // parameters". Both are proofs of inaccessibility; the direct constructor form is asserted
    // to carry the access error verbatim.
    inline def inaccessible(inline code: String, what: String, viaApply: Boolean = false): Unit =
      val errors = typeCheckErrors(code)
      val ok = errors.exists { e =>
        e.message.contains("cannot be accessed") ||
        (viaApply && e.message.contains("does not take parameters"))
      }
      assert(ok, s"$what must be private to align (expected an access error): $errors")
    inaccessible(
      """new storymodel4s.align.HsmmResult(???, ???, ???, 0.0, ???, ???, ???, ???, ???, 0)""",
      "the HsmmResult constructor"
    )
    inaccessible(
      """new storymodel4s.align.AlignmentRow(???, ???)""",
      "the AlignmentRow constructor"
    )
    inaccessible(
      """storymodel4s.align.AlignmentRow(???, ???)""",
      "AlignmentRow.apply",
      viaApply = true
    )
    inaccessible(
      """new storymodel4s.align.AlignmentMatrix(???)""",
      "the AlignmentMatrix constructor"
    )
    inaccessible(
      """storymodel4s.align.AlignmentMatrix(???)""",
      "AlignmentMatrix.apply",
      viaApply = true
    )
    inaccessible(
      """new storymodel4s.align.Admissibility(Vector.empty, true, None)""",
      "the Admissibility constructor"
    )
    inaccessible(
      """storymodel4s.align.Admissibility(Vector.empty, true, None)""",
      "Admissibility.apply",
      viaApply = true
    )
    inaccessible("""storymodel4s.align.Admissibility.faithfulOnly""", "Admissibility.faithfulOnly")
    inaccessible("""storymodel4s.align.Admissibility.of(Vector.empty)""", "Admissibility.of")
  }

  {
    import AddressGens.given
    checkAll(
      "AddressableLaws[CoreRef]",
      AddressableLaws.addressable[storymodel4s.core.CoreRef]("core")
    )
    checkAll(
      "AddressableLaws[StoryRef]",
      AddressableLaws.addressable[storymodel4s.story.StoryRef]("story")
    )
    checkAll(
      "AddressableLaws[FeatureAddress]",
      AddressableLaws.addressable[storymodel4s.features.FeatureAddress]("features")
    )
    checkAll(
      "AddressableLaws[RecallRef]",
      AddressableLaws.addressable[storymodel4s.recall.RecallRef]("recall")
    )
    checkAll(
      "AddressableLaws[AlignRef]",
      AddressableLaws.addressable[storymodel4s.align.AlignRef]("align")
    )
  }

  test("every mutation reports its law, and exact mutations report nothing else") {
    import storymodel4s.story.{StoryValidator, ValidationPolicy}
    val base = InvalidStoryGens.base
    val failures = InvalidStoryGens.mutations.flatMap { (law, exact, mutate) =>
      val laws = StoryValidator.validate(mutate(base), ValidationPolicy.strict).report.byLaw.keySet
      if !laws.contains(law) then Some(s"$law: not reported (got $laws)")
      else if exact && laws != Set(law) then Some(s"$law: exact but reported $laws")
      else None
    }
    assert(failures.isEmpty, failures.mkString("\n"))
  }

  test("every validator law has a minimally-invalid mutant") {
    val expected = Set(
      "ids.key-consistency",
      "claims.unique-ids",
      "claims.spans-in-text",
      "support.in-text",
      "entity.mentions-unique",
      "situation.mentions-unique",
      "endpoints.participant",
      "endpoints.temporal",
      "temporal.no-self",
      "temporal.canonical-relation",
      "temporal.context-scope",
      "causal.no-self",
      "endpoints.causal",
      "statechange.target-is-state",
      "reference.no-self",
      "endpoints.entity-attribute-context",
      "descriptor.target-exists",
      "situation.context-exists",
      "context.parent-exists",
      "context.holder-exists",
      "context.root-is-narrated-world",
      "context.narrated-world-is-root",
      "context.single-root",
      "context.acyclic",
      "endpoints.containment",
      "containment.weight-in-unit",
      "containment.acyclic",
      "hierarchy.single-primary-parent",
      "hierarchy.single-primary-root",
      "hierarchy.situation-root-reachable",
      "hierarchy.no-empty-primary-segment",
      "hierarchy.level-consistent",
      "boundary.unit-exists",
      "boundary.level",
      "temporal.strict-acyclic",
      "temporal.containment-acyclic",
      "temporal.equal-consistent",
      "feature.space-exists",
      "feature.row-in-range",
      "feature.target-exists",
      "feature.sidecar-space",
      "sensory.target-exists",
      "causal.cross-context-explicit"
    )
    assertEquals(InvalidStoryGens.laws.toSet, expected)
    assertEquals(InvalidStoryGens.laws.size, InvalidStoryGens.laws.distinct.size)
  }
