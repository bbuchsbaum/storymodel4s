package storymodel4s.laws

import munit.DisciplineSuite
import org.scalacheck.Arbitrary

class LawsSuite extends DisciplineSuite:
  import ChartGens.given
  import StoryGens.given
  import InvalidStoryGens.given

  given Arbitrary[AlignGens.Case] = Arbitrary(AlignGens.alignCase)

  checkAll("ChartLaws", ChartLaws.chart)
  checkAll("StoryLaws", StoryLaws.validator)
  checkAll("TemporalLaws", TemporalLaws.temporal)
  checkAll("AlignmentLaws", AlignmentLaws.alignment)
  checkAll("EstimateLaws", EstimateLaws.estimates)

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
      "feature.row-nonnegative",
      "feature.row-in-range",
      "feature.target-exists",
      "feature.sidecar-space",
      "feature.sidecar-valid",
      "sensory.target-exists",
      "causal.cross-context-explicit"
    )
    assertEquals(InvalidStoryGens.laws.toSet, expected)
    assertEquals(InvalidStoryGens.laws.size, InvalidStoryGens.laws.distinct.size)
  }
