package storymodel4s.bench.filmfestival

import java.nio.file.Files

import munit.FunSuite
import storymodel4s.bench.video.{WorldOrderAbsence, WorldOrderInput}
import storymodel4s.core.Checksum

/** Synthetic adapter courts; no corpus or participant prose is embedded here. */
class FilmFestivalSuite extends FunSuite:
  test("independent film blocks never acquire a fabricated world clock") {
    val table = FilmFestivalAnnotation.Table(
      Vector(
        FilmFestivalAnnotation
          .Row(1, "run-01", "Run 1", "synthetic one", Some(1), 0, Some(5), "Synthetic alpha."),
        FilmFestivalAnnotation
          .Row(2, "run-02", "Run 2", "synthetic two", Some(107), 0, None, "Synthetic beta.")
      ),
      Checksum.ofText("synthetic fixture")
    )
    val (built, axes) = FilmFestivalAnnotationView.build(table).fold(e => fail(e.message), identity)
    assertEquals(built.worldOrder, WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied))
    assertEquals(built.view.worldOrder, None)
    assertEquals(axes.size, 2)
    assertEquals(built.segmentByRef.values.flatMap(_.group.map(_.ordinal)).toSet, Set(1, 107))
    assert(built.segmentByRef.keys.forall(built.media.contains))
    // The instant-only second group has no nonzero hull; its leaf retains its own instant.
    assertEquals(built.groupByRef.keys.count(built.media.contains), 1)
  }

  test("requested ONNX fails closed unless both artifacts exist") {
    assert(FilmFestivalAnnotationView.requireEncoder("onnx", Map.empty).isLeft)
    val path = Files.createTempFile("filmfestival-synthetic-artifact", ".bin")
    try
      val one = Map("STORYMODEL4S_ONNX_MODEL" -> path.toString)
      assert(FilmFestivalAnnotationView.requireEncoder("onnx", one).isLeft)
      assert(
        FilmFestivalAnnotationView
          .requireEncoder("onnx", one + ("STORYMODEL4S_ONNX_TOKENIZER" -> path.toString))
          .isRight
      )
      assert(FilmFestivalAnnotationView.requireEncoder("lexical", one).isLeft)
      assert(FilmFestivalAnnotationView.requireEncoder("lexical", Map.empty).isRight)
      assert(FilmFestivalAnnotationView.requireEncoder("typo", Map.empty).isLeft)
    finally Files.delete(path)
  }
