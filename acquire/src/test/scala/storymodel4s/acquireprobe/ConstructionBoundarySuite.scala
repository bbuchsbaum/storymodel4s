package storymodel4s.acquireprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that validated acquire values have no case-class construction bypass. */
class ConstructionBoundarySuite extends FunSuite:
  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what still exposes fromProduct")

  test("probe control detects a case-class fromProduct") {
    assertEquals(
      typeCheckErrors(
        "storymodel4s.acquire.CandidateLedger.fromProduct(EmptyTuple)"
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
