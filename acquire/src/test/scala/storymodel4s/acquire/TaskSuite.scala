package storymodel4s.acquire

import munit.FunSuite
import storymodel4s.core.*

class TaskSuite extends FunSuite:
  import Fixtures.*

  private val source =
    StorySource.fromText("One sentence here. Another one follows!").toOption.get
  private val atlas = SurfaceAnalyzer.analyze(source)

  test("references to existing sentences and tokens validate"):
    val refs = TaskReferences(
      atlas.sentences.map(_.id),
      atlas.tokens.take(2).map(_.id),
      Vector.empty,
      Vector.empty
    )
    assert(refs.validateAgainst(atlas).isValid)

  test("unknown or mis-kinded references are all reported"):
    val refs = TaskReferences(
      Vector(atlas.tokens.head.id, SurfaceUnitId.unsafe("nope")),
      Vector(atlas.sentences.head.id),
      Vector.empty,
      Vector.empty
    )
    refs.validateAgainst(atlas).fold(errs => assertEquals(errs.length, 3L), _ => fail("valid"))

  test("node and claim existence are checked when a universe is supplied"):
    val refs =
      TaskReferences(Vector.empty, Vector.empty, Vector("n1"), Vector(ClaimId.unsafe("c1")))
    assert(refs.validateWith((_, _) => true, _ == "n1", _ => true).isValid)
    assert(refs.validateWith((_, _) => true, _ => false, _ => false).isInvalid)

  test("budgets reject non-positive timeouts and negative retries"):
    assert(TaskBudget.of(None, 0L, 0).isLeft)
    assert(TaskBudget.of(None, 10L, -1).isLeft)
    assert(TaskBudget.of(Some(0L), 10L, 0).isLeft)
    assert(TaskBudget.of(Some(10L), 10L, 0).isRight)

  test("packets map their input only"):
    val p = TaskPacket(
      task,
      TaskKind.LocalSemantics,
      "window",
      TaskReferences.empty,
      Vector(StandardsRef("amr-guidelines", "1.2.6", "3")),
      promptRef,
      TaskBudget.unsafe(None, 1000L, 1)
    )
    val q = p.map(_.length)
    assertEquals(q.input, 6)
    assertEquals(q.copy(input = "window"), p)
