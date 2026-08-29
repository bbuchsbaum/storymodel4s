package storymodel4s.consumer

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that population values expose observations but no construction bypass. */
class PopulationBoundarySuite extends FunSuite:
  test("population aggregate cannot be forged through case-class construction") {
    val fromProduct = typeCheckErrors(
      """storymodel4s.align.PopulationAggregate.fromProduct(
          (null.asInstanceOf[storymodel4s.align.SourceView],
           Vector.empty[storymodel4s.align.SubjectAlignment])
        )"""
    )
    assert(fromProduct.nonEmpty, "fromProduct reconstructed an unchecked aggregate")
  }

  test("population receipt construction stays behind its align-internal factory") {
    val constructor = typeCheckErrors(
      """new storymodel4s.align.PopulationReceipt(
          null.asInstanceOf[storymodel4s.align.ViewFingerprint],
          null.asInstanceOf[cats.data.NonEmptyVector[
            storymodel4s.align.PopulationMemberReceipt
          ]]
        )"""
    )
    val fromProduct = typeCheckErrors(
      "storymodel4s.align.PopulationReceipt.fromProduct(EmptyTuple)"
    )
    val factory = typeCheckErrors(
      """storymodel4s.align.PopulationReceipt.from(
          null.asInstanceOf[storymodel4s.align.ViewFingerprint],
          null.asInstanceOf[cats.data.NonEmptyVector[
            storymodel4s.align.PopulationMemberReceipt
          ]]
        )"""
    )
    assert(constructor.nonEmpty, "the private receipt constructor was public")
    assert(fromProduct.nonEmpty, "fromProduct reconstructed an unchecked receipt")
    assert(factory.nonEmpty, "the align-private receipt factory was public")
  }

  test("public read access remains available without a construction bypass") {
    val aggregateRead = typeCheckErrors(
      """val value = null.asInstanceOf[storymodel4s.align.PopulationAggregate]
        value.subjects.size + value.view.nodes.size"""
    )
    val receiptRead = typeCheckErrors(
      """val value = null.asInstanceOf[storymodel4s.align.PopulationReceipt]
        value.subjectCount + value.members.length + value.subjectsWithNoRecallUnits.size +
          value.recallChecksums.size"""
    )
    assertEquals(aggregateRead, Nil)
    assertEquals(receiptRead, Nil)
  }
