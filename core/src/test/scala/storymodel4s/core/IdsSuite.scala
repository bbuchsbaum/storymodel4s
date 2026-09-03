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

  test("credence: a calibrated probability rests on a measured raw score"):
    val p = Probability.unsafe(0.5)
    val scorer = ScorerId.unsafe("test-scorer")
    val model = CalibrationModelId.unsafe("m")
    assert(Credence.of(Score.Unmeasured, CredenceBasis.Calibrated(p, model)).isLeft)
    assert(Credence.of(Score.Raw(0.1, scorer), CredenceBasis.Calibrated(p, model)).isRight)
    assert(Credence.of(Score.Unmeasured, CredenceBasis.Uncalibrated).isRight)
    assert(Credence.of(Score.Unmeasured, CredenceBasis.Determined(RuleId.unsafe("r"))).isRight)
    assert(CalibrationModelId.from("  ").isLeft)
    assert(Credence.raw(Double.NaN, scorer).isLeft)
    assert(Credence.raw(Double.PositiveInfinity, scorer).isLeft)
    assert(Credence.of(Score.Raw(Double.NaN, scorer), CredenceBasis.Uncalibrated).isLeft)

  test("credence: negative zero folds onto zero so equal scores have one identity"):
    val scorer = ScorerId.unsafe("test-scorer")
    assertEquals(Credence.raw(-0.0, scorer), Credence.raw(0.0, scorer))
    assertEquals(Credence.raw(-0.0, scorer).map(_.score), Right(Score.Raw(0.0, scorer)))
    assert(
      Credence.raw(-0.0, scorer).toOption.get.score match
        case Score.Raw(v, _) => java.lang.Double.doubleToLongBits(v) == 0L
        case _               => false
    )

  property("credence law holds for all generated values"):
    forAll(Gens.credence) { c =>
      (c.calibrated.nonEmpty == c.calibrationModel.nonEmpty) &&
      (c.calibrated.isEmpty || c.rawScore.nonEmpty)
    }
