package storymodel4s.pipeline.agentmapping

import munit.FunSuite

/** The view rule: results of different producer conditions are different estimands and are never
  * combined; results of one estimand, including replicates with another seed or salt, are. A study
  * readout also refuses duplicate bundles and, unless pooling is declared, bundles of different
  * producers.
  */
class ConditionViewSuite extends FunSuite:
  import ConditionFixture.*

  private def loaded(c: RecordedCondition): StudyBundle.Loaded = right(decode(bundle(c), c))

  private val local = declared("annotation", contentLocal(2))
  private val whole = declared("annotation", wholeRecall)

  test("a content-local reference bundle and a whole-recall study bundle are refused together"):
    val reference = right(referenceRun(local, referenceBatches(2)).loaded)
    val study = loaded(whole)
    assertEquals(
      ConditionView.combine(Vector(reference.provenance.condition, study.condition))(identity),
      Left(ConditionView.Refusal.DifferentEstimands(local, whole))
    )

  test("positive control: content-local reference bundles of one condition combine"):
    val a = right(referenceRun(local, referenceBatches(2)).loaded)
    val b = right(referenceRun(local, referenceBatches(2)).loaded)
    val combined = right(ConditionView.combine(Vector(a, b))(_.provenance.condition))
    assertEquals(combined.estimand, Right(contentLocal(2).estimand))
    assertEquals(combined.items.size, 2)

  test("a study readout refuses a legacy and a declared whole-recall bundle together"):
    val legacyBundle = loaded(legacy("annotation"))
    assertEquals(
      ConditionView.readout(Vector(legacyBundle, loaded(whole))),
      Left(ConditionView.Refusal.DifferentEstimands(legacy("annotation"), whole))
    )

  private def as(b: StudyBundle.Loaded, session: String) = b.copy(session = session)

  test("positive control: a study readout of two sessions of one condition and producer"):
    val b = loaded(whole)
    val out =
      right(ConditionView.readout(Vector(b, as(b, "s2").copy(records = records(legacy("x"))))))
    assertEquals(out.hcursor.get[Int]("bundles").toOption, Some(2))
    assertEquals(out.hcursor.get[Int]("units").toOption, Some(12))
    assertEquals(
      out.hcursor.downField("judgements").downField("referent").get[Int]("scene").toOption,
      Some(2)
    )
    assertEquals(
      out.hcursor.downField("estimand").get[String]("window").toOption,
      Some("whole-recall")
    )
    assertEquals(
      out.hcursor.downField("pooling").get[String]("producer").toOption,
      Some("cli/m/strict-chunked/v1")
    )

  test("a study readout refuses the same bundle twice, or its records under another session"):
    val b = loaded(whole)
    assertEquals(
      ConditionView.readout(Vector(b, b)),
      Left(ConditionView.Refusal.Duplicate("p1", "s1"))
    )
    assertEquals(
      ConditionView.readout(Vector(b, as(b, "s2"))),
      Left(ConditionView.Refusal.Duplicate("p1", "s1"))
    )

  test("a study readout pools different models only when pooling is declared"):
    val b = loaded(whole)
    val other = as(b, "s2").copy(model = "m2", records = records(legacy("x")))
    assertEquals(
      ConditionView.readout(Vector(b, other)),
      Left(
        ConditionView.Refusal
          .DifferentProducers("cli/m/strict-chunked/v1", "cli/m2/strict-chunked/v1")
      )
    )
    val declaredPool =
      right(
        ConditionView.readout(
          Vector(b, other),
          ConditionView.Pooling.AcrossProducers("model comparison")
        )
      )
    assertEquals(
      declaredPool.hcursor.downField("pooling").get[String]("acrossProducers").toOption,
      Some("model comparison")
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
