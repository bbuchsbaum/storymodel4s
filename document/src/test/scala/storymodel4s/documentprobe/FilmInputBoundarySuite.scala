package storymodel4s.documentprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Compile-door court for the F1 checked joins.
  *
  * Fixture admission: synthetic identifiers only; no source, caption, or participant text. The
  * witness was independently reviewed as a construction-boundary probe, not a narrative fixture.
  */
class FilmInputBoundarySuite extends FunSuite:
  private val guarded: List[Class[?]] = List(
    classOf[storymodel4s.document.FilmProposalSurface],
    classOf[storymodel4s.document.FilmCompilerInput]
  )

  test(
    "FilmProposalSurface and FilmCompilerInput have no unchecked constructor, copy or Product doors"
  ) {
    assert(guarded.forall(_ != null))
    val prohibited = List(
      typeCheckErrors(
        "new storymodel4s.document.FilmProposalSurface(null, Vector.empty, Vector.empty, Vector.empty, null, null)"
      ),
      typeCheckErrors("storymodel4s.document.FilmProposalSurface.fromProduct(null)"),
      typeCheckErrors(
        "(x: storymodel4s.document.FilmProposalSurface) => x.copy(origins = Vector.empty)"
      ),
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.FilmProposalSurface]]"
      ),
      typeCheckErrors(
        "new storymodel4s.document.FilmCompilerInput(null, Vector.empty, Vector.empty, null)"
      ),
      typeCheckErrors("storymodel4s.document.FilmCompilerInput.fromProduct(null)"),
      typeCheckErrors(
        "(x: storymodel4s.document.FilmCompilerInput) => x.copy(charts = Vector.empty)"
      ),
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.FilmCompilerInput]]"
      )
    )
    assert(prohibited.forall(_.nonEmpty))
  }

  test("checked factories and ordinary record values remain visible") {
    assert(
      typeCheckErrors(
        "storymodel4s.document.FilmProposalSurface.derive(null, Vector.empty, Vector.empty, null)"
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        "storymodel4s.document.FilmCompilerInput.of(null, Vector.empty, Vector.empty, Vector.empty)"
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        "storymodel4s.document.FilmAttemptScope(\"synthetic\", Vector.empty, Vector.empty, null)"
      ).isEmpty
    )
  }
