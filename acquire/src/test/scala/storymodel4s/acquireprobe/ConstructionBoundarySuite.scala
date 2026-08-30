package storymodel4s.acquireprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that validated acquire values have no case-class construction bypass. */
class ConstructionBoundarySuite extends FunSuite:
  /** A REAL REFERENCE, so the incremental compiler knows this suite depends on what it guards.
    *
    * Every assertion below names its type inside a STRING passed to a compile-time macro, so Zinc
    * sees no dependency edge from this file to the sources it protects. Measured 2026-08-30:
    * un-sealing CandidateLedger and re-running incrementally left the macro's OLD result baked in
    * and the court PASSED; the same mutation under `Test/clean` failed correctly. A guard that does
    * not re-run when the thing it guards changes is not a guard.
    *
    * These bindings are never used. They exist so that touching resolve.scala invalidates this
    * suite and forces the macros to be re-evaluated.
    */
  private val dependsOn: (Class[?], Class[?]) =
    (classOf[storymodel4s.acquire.CandidateLedger[?]], classOf[storymodel4s.acquire.LedgerEntry[?]])

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

  /** `CandidateLedger` is SEALED, and this court is what keeps it that way.
    *
    * It is an APPEND-ONLY RECORD -- its scaladoc cites design record 85.4 and 94: entries are never
    * removed or overwritten, so the machine record survives adjudication. `add` enforces that,
    * refusing a duplicate ClaimId and refusing a `supersedes` that points at a claim the ledger
    * does not hold.
    *
    * Before it was sealed it was a `case class` with a private constructor, which closes `apply`
    * and `copy` and leaves BOTH product doors open. Anyone outside `acquire` could mint a ledger
    * with entries removed, with `order` inconsistent with `entries`, or with a supersedes chain
    * pointing nowhere. AN APPEND-ONLY RECORD THAT CAN BE CONSTRUCTED WITH ARBITRARY CONTENTS IS NOT
    * APPEND-ONLY, and "the machine record survives adjudication" was the guarantee it did not keep.
    */
  test("CandidateLedger has no product construction bypass") {
    refused(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.acquire.CandidateLedger[Int]]]"""
      ),
      "CandidateLedger Mirror.ProductOf"
    )
    refused(
      typeCheckErrors("""storymodel4s.acquire.CandidateLedger.fromProduct(EmptyTuple)"""),
      "CandidateLedger.fromProduct"
    )
    refused(
      typeCheckErrors(
        """storymodel4s.acquire.CandidateLedger[Int](Map.empty, Vector.empty)"""
      ),
      "CandidateLedger public apply"
    )
  }
