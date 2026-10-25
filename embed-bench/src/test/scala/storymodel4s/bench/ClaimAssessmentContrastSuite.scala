package storymodel4s.bench

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.ClaimId

/** Expected labels are fixed synthetic controls, not measured participant accuracy. */
class ClaimAssessmentContrastSuite extends FunSuite:
  private lazy val receipt = ClaimAssessmentContrast.run()

  test("all frozen contrast expectations are reproduced by the narrow baseline") {
    assertEquals(ClaimAssessmentContrast.cases.size, 17)
    ClaimAssessmentContrast.cases.foreach { c =>
      assertEquals(receipt.status(ClaimId.unsafe(c.id)), Some(c.expected), c.id)
    }
  }

  test("partial memory keeps one supported and one contradicted claim on the same event") {
    val a = receipt.questions.find(_.id.value == "partial-memory-supported").get
    val b = receipt.questions.find(_.id.value == "partial-memory-contradicted").get
    assertEquals(a.unit, b.unit)
    assertEquals(a.event, b.event)
    assertEquals(receipt.status(a.id), Some(ClaimAssessmentStatus.Supported))
    assertEquals(receipt.status(b.id), Some(ClaimAssessmentStatus.Contradicted))
    assertEquals(
      receipt.mapping.outcome(a.unit).get.decision.get.chosen,
      Some(Destination.Target(a.event))
    )
    assertEquals(
      receipt.mapping.outcome(a.unit).get.measures.raw.head.values(Destination.Target(a.event)),
      0.8
    )
  }

  test("missing source context and unknown reference do not become counterevidence") {
    Vector(
      "source-missing",
      "reported-content",
      "unknown-participant",
      "different-event",
      "different-participant",
      "different-predicate",
      "disjunction"
    ).foreach { id =>
      assertEquals(receipt.submissions.find(_.question.value == id).get.counterevidence.size, 0, id)
    }
  }

  test("fixed population accounting retains unresolved and omitted questions") {
    assertEquals(receipt.totalQuestions, 17)
    assertEquals(receipt.expressedQuestions, 15)
    assertEquals(receipt.resolvedQuestions, 5)
    assertEquals(receipt.counts.values.sum, 17)
    assertEquals(receipt.counts(ClaimAssessmentStatus.Conflicted), 1)
    assertEquals(receipt.counts(ClaimAssessmentStatus.Supported), 3)
    assertEquals(receipt.counts(ClaimAssessmentStatus.Contradicted), 2)
    assertEquals(receipt.counts(ClaimAssessmentStatus.Unresolved), 8)
  }

  test("duplicate source descriptions do not multiply supporting credit") {
    val c = ClaimAssessmentContrast.cases.find(_.id == "exact").get
    val once = ClaimAssessmentContrast.compare(c.recalled.get, c.sources.head)
    val twice = Vector.fill(2)(ClaimAssessmentContrast.compare(c.recalled.get, c.sources.head))
    assert(once.support)
    assert(twice.forall(_ == once))
    val s = receipt.submissions.find(_.question.value == "exact").get
    val duplicate = receipt.citations
      .find(e => s.supporting(e.id))
      .get
      .copy(id = storymodel4s.core.EvidenceId.unsafe("duplicate-control"))
    val next = ClaimAssessmentReceipt
      .checked(
        receipt.mapping,
        receipt.questions,
        receipt.citations :+ duplicate,
        receipt.submissions.map(v =>
          if v.question == s.question then v.copy(supporting = v.supporting + duplicate.id) else v
        )
      )
      .toOption
      .get
    assertEquals(next.counts, receipt.counts)
    assertEquals(next.populationDigest, receipt.populationDigest)
    assertEquals(next.mappingDigest, receipt.mappingDigest)
  }
