package storymodel4s.interview.scoring

import cats.data.NonEmptyVector
import munit.FunSuite

import storymodel4s.core.*
import storymodel4s.features.Estimate
import storymodel4s.interview.*
import storymodel4s.recall.*

/** The PRODUCTION producer of PlacementResolution, courted directly.
  *
  * Scout blocked 067e35b because `TraditionalScoring.placementResolution` supplies `excluded`
  * without `excludedBy`, and the constructor REFUSES unattributed exclusion - so a live path threw
  * IllegalStateException. My own three-module gate passed, and so did fixtures: mutating the fix
  * away leaves interview 99/99 and fixtures 117/117 GREEN. NOTHING ANYWHERE EXERCISED THE
  * RepetitionRule.Ignore BRANCH WITH NON-ZERO EXCLUDED MASS. That is why this suite exists and why
  * it lives in `scoring` - the producer is `private[scoring]`, so a court for it cannot be written
  * from the package the other interview suites use.
  */
class PlacementProducerSuite extends FunSuite:

  private def detail(text: String): Detail =
    val src = StorySource.fromText(text).toOption.get
    val u = RecallSegmenter.segment(src).ordered.head
    AtomProjection.fromUnit(u, TurnId.unsafe("t"), chart = None).head

  private def metaFor(d: Detail): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(s"c:${d.id.value}"),
      EpistemicStatus.Hypothesized,
      Credence.unsafeRaw(0.5, ScorerId.unsafe("test-scorer")),
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe(s"e:${d.id.value}"),
          Some(d.support),
          Set.empty,
          Fingerprint.unsafe("test"),
          StageId.unsafe("s")
        )
      ),
      Provenance.deterministic("test", Checksum.ofText("cfg"))
    )

  private def assessed(d: Detail, addr: Distribution[MemoryAddress]): DetailAssessment =
    DetailAssessment(
      d,
      addr,
      Distribution.point(DetailFacet.Event),
      Estimate.observed(0.5),
      ExperientialEvidence.none,
      EpistemicStatus.Hypothesized,
      PromptContext(InterviewPhase.FreeRecall, None),
      None,
      metaFor(d)
    )

  test("a repetition ignored by policy produces an attributed resolution rather than throwing") {
    val d = detail("We ate cake.")
    val rep = MemoryAddress.Discourse(InterviewDiscourseFunction.Repetition(DetailId.unsafe("x")))
    val as = Vector(assessed(d, Distribution.point(rep)))
    val ignore = AiScoringPolicy.Standard.copy(repetitionRule = RepetitionRule.Ignore)

    // Before the fix this threw IllegalStateException from the producer's own fold.
    val r = TraditionalScoring.placementResolution(as, ignore)

    assert(r.excluded > 0.0, s"fixture: the Ignore branch must remove mass - ${r.render}")
    assertEqualsDouble(r.excludedBy.values.sum, r.excluded, 1e-12)
    assertEquals(
      r.excludedBy.keySet,
      Set[ExclusionCause](ExclusionCause.RepetitionPolicy),
      "the only exclusion this producer can make is the repetition policy"
    )
  }

  test("an account with no ignored repetition attributes nothing") {
    // The other side: excludedBy must be EMPTY when nothing was excluded, or the constructor
    // refuses it. Without this, "always attribute RepetitionPolicy" would pass the test above.
    val d = detail("We ate cake.")
    val as = Vector(assessed(d, Distribution.point(MemoryAddress.GeneralKnowledge)))
    val r = TraditionalScoring.placementResolution(as, AiScoringPolicy.Standard)
    assertEqualsDouble(r.excluded, 0.0, 1e-12)
    assert(r.excludedBy.isEmpty, s"attributed an exclusion that did not happen: ${r.render}")
  }
