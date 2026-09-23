package storymodel4s.codec

import munit.FunSuite
import storymodel4s.core.ExactRational

class WorkspaceClockNumberSuite extends FunSuite:
  private def q(n: Long, d: Long = 1L) = ExactRational.of(n, d).toOption.get

  test("legacy clock conversion accepts exact binary observations and refuses rounding") {
    assertEquals(WorkspaceClocksCodec.legacySeconds(q(5, 2)).map(_.value), Some(2.5))
    assertEquals(WorkspaceClocksCodec.legacySeconds(q(0)).map(_.value), Some(0.0))
    assertEquals(
      WorkspaceClocksCodec.legacySeconds(q(9007199254740992L)).map(_.value),
      Some(9007199254740992.0)
    )
    assertEquals(WorkspaceClocksCodec.legacySeconds(q(9007199254740993L)), None)
    assertEquals(WorkspaceClocksCodec.legacySeconds(q(1, 10)), None)
    assertEquals(WorkspaceClocksCodec.legacySeconds(q(-1)), None)
  }
