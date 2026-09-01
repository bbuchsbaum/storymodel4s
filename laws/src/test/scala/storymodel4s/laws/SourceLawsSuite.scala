package storymodel4s.laws

import munit.DisciplineSuite
import storymodel4s.core.*

class SourceLawsSuite extends DisciplineSuite:
  checkAll("SourceRationalLaws", SourceLaws.rationals)
  checkAll("SourceRescaleLaws", SourceLaws.rescale)
  checkAll("SourceTimestampLaws", SourceLaws.timestamps)
  checkAll("SourceBundleLaws", SourceLaws.bundles)

  test("capacity: a foil with PTS < DTS is refused while the sibling lawful pair is admitted"):
    assert(PacketTimeFields.of(TimestampField.present(1L), TimestampField.present(2L)).isLeft)
    assert(PacketTimeFields.of(TimestampField.present(2L), TimestampField.present(1L)).isRight)
