package storymodel4s.core

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import storymodel4s.core.NarrativeKind.*

class IdsSuite extends ScalaCheckSuite:
  test("valid identifiers round-trip their value"):
    assertEquals(StoryId.from("wog:1").map(_.value), Right("wog:1"))

  test("identifiers reject empty, whitespace, control, and overlong input"):
    assert(StoryId.from("").isLeft)
    assert(StoryId.from("a b").isLeft)
    assert(StoryId.from("a\tb").isLeft)
    assert(StoryId.from("x" * 257).isLeft)
    assert(StoryId.from("x" * 256).isRight)

  property("every alphanumeric-ish string is a valid identifier"):
    forAll(Gens.idString) { s => StoryId.from(s).isRight }

  test("distinct identifier kinds do not unify"):
    val err = compileErrors("val x: StoryId = EntityId.unsafe(\"e1\")")
    assert(err.nonEmpty, "StoryId and EntityId must be distinct types")

  test("mention kinds are phantom-distinct at compile time"):
    val err = compileErrors(
      "val m: MentionId[EntityK] = MentionId.unsafe[SituationK](\"m1\")"
    )
    assert(err.nonEmpty)
    val ok: MentionId[EntityK] = MentionId.unsafe[EntityK]("m1")
    assertEquals(ok.value, "m1")

  test("cats instances are available for ids"):
    import cats.syntax.all.*
    assertEquals(StoryId.unsafe("a").show, "a")
    assert(StoryId.unsafe("a") < StoryId.unsafe("b"))
    assertEquals(
      Vector(EntityId.unsafe("b"), EntityId.unsafe("a")).sorted.map(_.value),
      Vector("a", "b")
    )

  property("probability accepts exactly [0,1]"):
    forAll { (d: Double) =>
      val ok = !d.isNaN && d >= 0.0 && d <= 1.0
      Probability.from(d).isRight == ok
    }

  test("probability rejects NaN and out-of-range values"):
    assert(Probability.from(Double.NaN).isLeft)
    assert(Probability.from(1.0000001).isLeft)
    assert(Probability.from(-0.0000001).isLeft)
    assertEquals(Probability.unsafe(0.25).complement.value, 0.75)

  test("credence: calibrated value requires a calibration model"):
    val p = Probability.unsafe(0.5)
    assert(Credence.from(0.1, Some(p), None).isLeft)
    assert(Credence.from(0.1, None, Some("m")).isLeft)
    assert(Credence.from(0.1, Some(p), Some("m")).isRight)
    assert(Credence.from(0.1, None, None).isRight)
    assert(Credence.calibrated(0.1, p, "  ").isLeft)
    assert(Credence.raw(Double.NaN).isLeft)
    assert(Credence.raw(Double.PositiveInfinity).isLeft)

  property("credence law holds for all generated values"):
    forAll(Gens.credence) { c => c.calibrated.nonEmpty == c.calibrationModel.nonEmpty }
