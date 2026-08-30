package storymodel4s.acquireprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that validated acquire values have no case-class construction bypass. */
class ConstructionBoundarySuite extends FunSuite:
  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what still exposes fromProduct")

  /** The positive control MUST be anchored on a type whose public construction is a DESIGN
    * DECISION, never on one that is merely unsealed yet.
    *
    * This control previously used `CandidateLedger`, and that was a trap. `CandidateLedger` has a
    * PRIVATE constructor and its scaladoc calls it an append-only record whose entries "are never
    * removed or overwritten, so the machine record survives adjudication" -- so its open
    * `fromProduct` is a DEFECT awaiting repair, not a design decision. Anchoring here meant that
    * SEALING IT WOULD HAVE BROKEN THIS SUITE, taking the genuine boundary courts below red with it:
    * a test whose correctness depended on a bug persisting, silently penalising its own repair.
    *
    * `LedgerEntry` is the right anchor. It is three lines above `CandidateLedger` in the same file,
    * carries no `private`, and is documented as plain data -- "the bundle a claim was resolved
    * from, its state, and what it supersedes". Ask of any anchor: IF SOMEONE SEALED THIS TOMORROW,
    * WOULD THEY BE FIXING A BUG? For `CandidateLedger` the answer is yes; for `LedgerEntry`, no.
    */
  test("probe control detects a case-class fromProduct") {
    assertEquals(
      typeCheckErrors(
        "storymodel4s.acquire.LedgerEntry.fromProduct(EmptyTuple)"
      ),
      Nil
    )
  }

  test("acquire smart-construction boundaries expose no fromProduct") {
    refused(
      typeCheckErrors("storymodel4s.acquire.FamilyPolicy.fromProduct(EmptyTuple)"),
      "FamilyPolicy"
    )
    refused(
      typeCheckErrors("storymodel4s.acquire.BudgetPolicy.fromProduct(EmptyTuple)"),
      "BudgetPolicy"
    )
    refused(
      typeCheckErrors("storymodel4s.acquire.TaskBudget.fromProduct(EmptyTuple)"),
      "TaskBudget"
    )
    refused(
      typeCheckErrors("storymodel4s.acquire.AgentProposal.fromProduct(EmptyTuple)"),
      "AgentProposal"
    )
  }

  test("acquire observations remain public") {
    assertEquals(
      typeCheckErrors(
        """(x: storymodel4s.acquire.TaskBudget) =>
             x.maxTokens.size + x.timeoutMillis.toInt + x.maxRetries"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """(x: storymodel4s.acquire.AgentProposal[String]) =>
             x.taskId.value.length + x.value.size + x.evidence.size + x.conflicts.size"""
      ),
      Nil
    )
  }
