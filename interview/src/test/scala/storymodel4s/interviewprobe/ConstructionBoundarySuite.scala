package storymodel4s.interviewprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that validated interview values have no case-class construction bypass.
  */
class ConstructionBoundarySuite extends FunSuite:
  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what still exposes fromProduct")

  test("probe control detects a case-class fromProduct") {
    assertEquals(
      typeCheckErrors(
        "storymodel4s.interview.InterviewModel.fromProduct(EmptyTuple)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.interview.Cue]]"
      ),
      Nil
    )
  }

  test("interview smart-construction boundaries expose no fromProduct") {
    refused(
      typeCheckErrors("storymodel4s.interview.Distribution.fromProduct(EmptyTuple)"),
      "Distribution"
    )
    refused(
      typeCheckErrors("storymodel4s.interview.InductionConfig.fromProduct(EmptyTuple)"),
      "InductionConfig"
    )
    refused(
      typeCheckErrors("storymodel4s.interview.EpisodeModel.fromProduct(EmptyTuple)"),
      "EpisodeModel"
    )
    refused(
      typeCheckErrors("storymodel4s.interview.InterviewSource.fromProduct(EmptyTuple)"),
      "InterviewSource"
    )
  }

  test("InterviewSource exposes no unchecked construction or edit door") {
    refused(
      typeCheckErrors(
        """storymodel4s.interview.InterviewSource(
          ??? : storymodel4s.core.TranscriptAtlas,
          ??? : storymodel4s.interview.Cue,
          Vector.empty[storymodel4s.interview.Probe],
          None
        )"""
      ),
      "InterviewSource.apply"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.interview.InterviewSource) => x.copy()"),
      "InterviewSource.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.interview.InterviewSource) => x: Product"),
      "InterviewSource as Product"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.interview.InterviewSource]]"
      ),
      "InterviewSource Mirror.ProductOf"
    )
  }

  test("interview observations remain public") {
    assertEquals(
      typeCheckErrors(
        """(x: storymodel4s.interview.Distribution[String]) => x.weights.size"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """(x: storymodel4s.interview.EpisodeModel) =>
             x.id.value.length + x.situations.size + x.entities.size + x.status.ordinal"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """(t: storymodel4s.core.TranscriptAtlas) =>
             storymodel4s.interview.InterviewSource.of(
               t,
               storymodel4s.interview.Cue("cue", None, None),
               Vector.empty[storymodel4s.interview.Probe],
               None
             )"""
      ),
      Nil
    )
  }
