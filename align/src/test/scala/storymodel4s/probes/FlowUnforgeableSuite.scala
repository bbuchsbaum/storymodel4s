package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.align.{AlignState, FlowStep, TransitionFlow}
import storymodel4s.recall.RecallUnitId

/** Construction boundaries of `FlowStep` and `TransitionFlow`, probed from outside package `align`
  * (bd-01M37C6DJXHTATH3QV17J5QPM9; docs/api-stability.md "Signature closure").
  *
  * Why these two: both were case classes reached through `HsmmResult.flow`. A `FlowStep` could hold
  * NaN, infinite or negative mass, or join a unit to itself; a `TransitionFlow` could hold lawful
  * steps that do not chain. Only an enclosing `HsmmResult.validated` noticed.
  */
class FlowUnforgeableSuite extends FunSuite:

  // Each door is its own test with a same-shape positive control, so a mutant that reopens one door
  // fails that door's test by name. `typeChecks` is false for ANY error, so a refusal without a
  // control that can pass proves nothing.
  test("door Mirror.ProductOf: refused for FlowStep and TransitionFlow, open for the controls") {
    assert(typeChecks("summon[scala.deriving.Mirror.ProductOf[FlowStepShape]]"), "control")
    assert(typeChecks("summon[scala.deriving.Mirror.ProductOf[FlowShape]]"), "control")
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[FlowStep]]"
      ),
      "FlowStep Mirror.ProductOf is open"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[TransitionFlow]]"
      ),
      "TransitionFlow Mirror.ProductOf is open"
    )
  }

  test(
    "door companion fromProduct: refused for FlowStep and TransitionFlow, open for the controls"
  ) {
    // The control is a case class compiled in `main`: `fromProduct` is synthesized after typer, so
    // `typeChecks` (which expands during typer) cannot see it on a case class from this test run.
    assert(
      typeChecks("import storymodel4s.align.*; FunctionPrior.fromProduct(???)"),
      "control does not compile"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; FlowStep.fromProduct(???)"),
      "FlowStep companion fromProduct is open"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; TransitionFlow.fromProduct(???)"),
      "TransitionFlow companion fromProduct is open"
    )
  }

  test("door copy: refused for FlowStep and TransitionFlow, open for the controls") {
    assert(typeChecks("(??? : FlowStepShape).copy()"), "control")
    assert(typeChecks("(??? : FlowShape).copy()"), "control")
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : FlowStep).copy()"),
      "FlowStep copy is open"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TransitionFlow).copy()"),
      "TransitionFlow copy is open"
    )
  }

  test("door apply: refused for FlowStep and TransitionFlow, open for the controls") {
    assert(typeChecks("FlowStepShape(???, ???, Map.empty)"), "same-signature control")
    assert(typeChecks("FlowShape(Vector.empty)"), "same-signature control")
    assert(
      !typeChecks("import storymodel4s.align.*; FlowStep(???, ???, Map.empty)"),
      "FlowStep apply is open"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; TransitionFlow(Vector.empty)"),
      "TransitionFlow apply is open"
    )
  }

  test("door new: refused for FlowStep and TransitionFlow, open for the controls") {
    assert(typeChecks("new FlowStepShape(???, ???, Map.empty)"), "same-signature control")
    assert(typeChecks("new FlowShape(Vector.empty)"), "same-signature control")
    assert(
      !typeChecks("import storymodel4s.align.*; new FlowStep(???, ???, Map.empty)"),
      "FlowStep new is open"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; new TransitionFlow(Vector.empty)"),
      "TransitionFlow new is open"
    )
  }

  // --- the checked factories -------------------------------------------------------------------

  private val (u1, u2, u3) =
    (RecallUnitId.unsafe("u1"), RecallUnitId.unsafe("u2"), RecallUnitId.unsafe("u3"))
  private val s = AlignState.unranked
  private def step(from: RecallUnitId, to: RecallUnitId, m: Double) =
    FlowStep.of(from, to, Map((s, s) -> m))

  test("FlowStep.of refuses NaN mass") {
    assert(step(u1, u2, Double.NaN).isLeft)
  }

  test("FlowStep.of refuses negative mass") {
    assert(step(u1, u2, -1e-12).isLeft)
  }

  test("FlowStep.of refuses infinite mass") {
    assert(step(u1, u2, Double.PositiveInfinity).isLeft)
  }

  test("FlowStep.of refuses a step from a unit to itself") {
    assert(step(u1, u1, 1.0).isLeft)
  }

  test("FlowStep.of accepts zero and positive finite mass, and keeps what it was given") {
    val zero = step(u1, u2, 0.0).fold(e => fail(e.message), identity)
    assertEquals(zero(s, s), 0.0)
    val one = step(u1, u2, 1.0).fold(e => fail(e.message), identity)
    assertEquals((one.from, one.to, one.mass), (u1, u2, Map((s, s) -> 1.0)))
    assertEquals(one, step(u1, u2, 1.0).fold(e => fail(e.message), identity), "structural equality")
  }

  test("TransitionFlow.of refuses steps that do not chain") {
    val a = step(u1, u2, 1.0).fold(e => fail(e.message), identity)
    val c = step(u1, u3, 1.0).fold(e => fail(e.message), identity)
    assert(TransitionFlow.of(Vector(a, c)).isLeft)
  }

  test("TransitionFlow.of accepts an empty flow and a chained one") {
    val a = step(u1, u2, 1.0).fold(e => fail(e.message), identity)
    val b = step(u2, u3, 1.0).fold(e => fail(e.message), identity)
    assertEquals(TransitionFlow.of(Vector.empty).map(_.size), Right(0))
    assertEquals(TransitionFlow.of(Vector(a, b)).map(_.steps), Right(Vector(a, b)))
  }

/** Same-shape controls: public case classes with the sealed types' field signatures. */
final case class FlowStepShape(
    from: RecallUnitId,
    to: RecallUnitId,
    mass: Map[(AlignState, AlignState), Double]
)
final case class FlowShape(steps: Vector[FlowStep])
