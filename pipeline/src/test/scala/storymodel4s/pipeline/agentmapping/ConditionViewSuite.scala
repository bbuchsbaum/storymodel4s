package storymodel4s.pipeline.agentmapping

import munit.FunSuite

/** The view rule: bundles of different producer conditions are different estimands and are never
  * combined; bundles of one estimand, including replicates with another seed or salt, are.
  */
class ConditionViewSuite extends FunSuite:
  import ConditionFixture.*

  private def loaded(c: RecordedCondition): StudyBundle.Loaded = right(decode(bundle(c), c))

  private val local = declared("annotation", contentLocal(2))
  private val whole = declared("annotation", wholeRecall)

  test("a content-local and a whole-recall bundle are refused together"):
    assertEquals(
      ConditionView.readout(Vector(loaded(local), loaded(whole))),
      Left(ConditionView.Refusal.DifferentEstimands(local, whole))
    )

  test("positive control: two bundles of the same condition are read out together"):
    val out = right(ConditionView.readout(Vector(loaded(local), loaded(local))))
    assertEquals(out.hcursor.get[Int]("bundles").toOption, Some(2))
    assertEquals(out.hcursor.get[Int]("units").toOption, Some(12))
    assertEquals(
      out.hcursor.downField("judgements").downField("referent").get[Int]("scene").toOption,
      Some(2)
    )
    assertEquals(
      out.hcursor.downField("estimand").get[String]("window").toOption,
      Some("preceding-2")
    )

  test("replicates with another shuffle seed and salt are one estimand"):
    val replicate = declared("annotation", contentLocal(2, seed = 5L, salt = "another"))
    val combined = right(ConditionView.combine(Vector(local, replicate))(identity))
    assertEquals(combined.conditions, Vector(local, replicate))
    assertEquals(combined.estimand, Right(contentLocal(2).estimand))

  test("another window, a legacy name, or another study name is another estimand"):
    def refused(a: RecordedCondition, b: RecordedCondition) =
      assert(ConditionView.combine(Vector(a, b))(identity).isLeft, s"$a with $b")
    refused(local, declared("annotation", contentLocal(3)))
    refused(local, legacy("annotation"))
    refused(legacy("annotation"), whole)
    refused(local, declared("other", contentLocal(2)))
    refused(legacy("annotation"), legacy("other"))
    assert(
      ConditionView.combine(Vector(legacy("annotation"), legacy("annotation")))(identity).isRight
    )

  test("nothing to combine is refused"):
    assertEquals(
      ConditionView.combine(Vector.empty[RecordedCondition])(identity).left.toOption,
      Some(ConditionView.Refusal.Empty)
    )
