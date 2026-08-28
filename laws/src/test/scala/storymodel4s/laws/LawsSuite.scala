package storymodel4s.laws

import munit.DisciplineSuite
import org.scalacheck.Arbitrary

class LawsSuite extends DisciplineSuite:
  import ChartGens.given
  import StoryGens.given

  given Arbitrary[AlignGens.Case] = Arbitrary(AlignGens.alignCase)

  checkAll("ChartLaws", ChartLaws.chart)
  checkAll("TemporalLaws", TemporalLaws.temporal)
  checkAll("AlignmentLaws", AlignmentLaws.alignment)
  checkAll("EstimateLaws", EstimateLaws.estimates)
