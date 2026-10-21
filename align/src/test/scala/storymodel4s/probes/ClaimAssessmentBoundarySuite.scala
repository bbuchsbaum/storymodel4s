package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Independent-package probes protect receipt construction without restricting raw submissions. */
class ClaimAssessmentBoundarySuite extends FunSuite:
  test("constructor is closed with a same-shape positive control") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; case class Control(mapping: MappingResult, questions: Vector[ClaimAssessmentQuestion], citations: Vector[ClaimSourceCitation], submissions: Vector[ClaimAssessmentSubmission]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : MappingResult, ??? : Vector[ClaimAssessmentQuestion], ??? : Vector[ClaimSourceCitation], ??? : Vector[ClaimAssessmentSubmission])"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; new ClaimAssessmentReceipt(??? : MappingResult, ??? : Vector[ClaimAssessmentQuestion], ??? : Vector[ClaimSourceCitation], ??? : Vector[ClaimAssessmentSubmission])""").nonEmpty)
  }

  test("apply is closed with a same-shape positive control") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; case class Control(mapping: MappingResult, questions: Vector[ClaimAssessmentQuestion], citations: Vector[ClaimSourceCitation], submissions: Vector[ClaimAssessmentSubmission]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : MappingResult, ??? : Vector[ClaimAssessmentQuestion], ??? : Vector[ClaimSourceCitation], ??? : Vector[ClaimAssessmentSubmission])"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; ClaimAssessmentReceipt(??? : MappingResult, ??? : Vector[ClaimAssessmentQuestion], ??? : Vector[ClaimSourceCitation], ??? : Vector[ClaimAssessmentSubmission])""").nonEmpty)
  }

  test("copy is closed with a same-shape positive control") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; case class Control(mapping: MappingResult, questions: Vector[ClaimAssessmentQuestion], citations: Vector[ClaimSourceCitation], submissions: Vector[ClaimAssessmentSubmission]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; (??? : ClaimAssessmentReceipt).copy()""").nonEmpty)
  }

  test("fromProduct is closed with a same-shape positive control") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; case class Control(mapping: MappingResult, questions: Vector[ClaimAssessmentQuestion], citations: Vector[ClaimSourceCitation], submissions: Vector[ClaimAssessmentSubmission]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple4(??? : MappingResult, ??? : Vector[ClaimAssessmentQuestion], ??? : Vector[ClaimSourceCitation], ??? : Vector[ClaimAssessmentSubmission]))"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; ClaimAssessmentReceipt.fromProduct(Tuple4(??? : MappingResult, ??? : Vector[ClaimAssessmentQuestion], ??? : Vector[ClaimSourceCitation], ??? : Vector[ClaimAssessmentSubmission]))""").nonEmpty)
  }

  test("mirror is closed with a same-shape positive control") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; case class Control(mapping: MappingResult, questions: Vector[ClaimAssessmentQuestion], citations: Vector[ClaimSourceCitation], submissions: Vector[ClaimAssessmentSubmission]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[ClaimAssessmentReceipt]]""").nonEmpty)
  }

  test("checked factory remains available outside the defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; ClaimAssessmentReceipt.checked(??? : MappingResult, Vector.empty, Vector.empty, Vector.empty)"""), Nil)
  }
