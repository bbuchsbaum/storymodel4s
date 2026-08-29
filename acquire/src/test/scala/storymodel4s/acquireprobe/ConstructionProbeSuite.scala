package storymodel4s.acquireprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

import storymodel4s.acquire.{PermittedOperation, PromptPackageManifest, PromptRole}

/** Probes from OUTSIDE `storymodel4s.acquire`. Live hole demonstrated first: `fromProduct` minted a
  * blank-name manifest that `validate` refused. That door is closed.
  */
class ConstructionProbeSuite extends FunSuite:

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not be constructible outside storymodel4s.acquire")

  test("positive control: a remaining case class still has fromProduct") {
    val errors = typeCheckErrors(
      """storymodel4s.acquire.PromptPackageRef.fromProduct(
           ("n", "1", storymodel4s.core.Checksum.ofText("x"))
         )"""
    )
    assert(
      errors.isEmpty,
      s"if PromptPackageRef.fromProduct fails to typecheck, refusals beside it are meaningless:\n${errors.mkString("\n")}"
    )
  }

  test("PromptPackageManifest has no derived fromProduct bypass") {
    refused(
      typeCheckErrors("""storymodel4s.acquire.PromptPackageManifest.fromProduct(EmptyTuple)"""),
      "PromptPackageManifest.fromProduct"
    )
  }

  test("PromptPackageManifest has no copy door") {
    refused(
      typeCheckErrors(
        """(m: storymodel4s.acquire.PromptPackageManifest) => m.copy(name = " ")"""
      ),
      "PromptPackageManifest.copy"
    )
  }

  test("PromptPackageManifest retains public read access") {
    assert(
      typeCheckErrors(
        """(m: storymodel4s.acquire.PromptPackageManifest) => (m.name, m.version, m.checksum, m.ref)"""
      ).isEmpty
    )
  }

  test("of remains the public path and still rejects a blank name") {
    assert(
      typeCheckErrors(
        """storymodel4s.acquire.PromptPackageManifest.of(
             "local-semantics", "1.0.0",
             storymodel4s.acquire.PromptRole.LocalSemanticsProposer,
             "schema:in", "schema:out",
             Vector(storymodel4s.acquire.PermittedOperation.Abstain),
             Vector.empty, Vector.empty, Vector.empty, Vector.empty,
             Vector("abstain"), Vector("check"), "bench:1"
           )"""
      ).isEmpty
    )
    assert(
      PromptPackageManifest
        .of(
          " ",
          "1.0.0",
          PromptRole.LocalSemanticsProposer,
          "schema:in",
          "schema:out",
          Vector(PermittedOperation.Abstain),
          Vector.empty,
          Vector.empty,
          Vector.empty,
          Vector.empty,
          Vector("abstain"),
          Vector("check"),
          "bench:1"
        )
        .isInvalid
    )
  }
