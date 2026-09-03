package storymodel4s.pipeline

import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import storymodel4s.features.*

/** The feature request vocabulary and the lexicon file loader: what a caller can ask for, how the
  * command line says it, and what a table file may and may not contain.
  */
class FeatureStageSuite extends FunSuite:
  private val demo: Path = Paths.get(getClass.getResource("/lexicons/synthetic-demo.tsv").toURI)

  test("feature specs parse to requests and render back") {
    assertEquals(FeatureRequest.parse("token-length"), Right(FeatureRequest.TokenLength))
    assertEquals(FeatureRequest.parse(" type-frequency "), Right(FeatureRequest.TypeFrequency))
    assertEquals(
      FeatureRequest.parse("lexicon=/tmp/norms/concreteness.tsv"),
      Right(FeatureRequest.Lexicon(Path.of("/tmp/norms/concreteness.tsv"), "concreteness"))
    )
    assertEquals(
      FeatureRequest.parse("lexicon=imag@/data/mrc.csv"),
      Right(FeatureRequest.Lexicon(Path.of("/data/mrc.csv"), "imag"))
    )
    assertEquals(FeatureRequest.parse("lexicon=").isLeft, true)
    assertEquals(FeatureRequest.parse("imageability").isLeft, true)
    assertEquals(FeatureRequest.Lexicon(Path.of("x.tsv"), "x").render, "lexicon=x@x.tsv")
  }

  test("trailing arguments split into feature flags and the rest, in order") {
    val args =
      Vector("The War of the Ghosts", "--feature", "token-length", "--feature=type-frequency")
    FeatureRequest.fromArgs(args) match
      case Right((features, rest)) =>
        assertEquals(features, Vector(FeatureRequest.TokenLength, FeatureRequest.TypeFrequency))
        assertEquals(rest, Vector("The War of the Ghosts"))
      case Left(e) => fail(e.message)
    assertEquals(FeatureRequest.fromArgs(Vector.empty), Right((Vector.empty, Vector.empty)))
    assert(FeatureRequest.fromArgs(Vector("--feature")).isLeft, "a flag with no spec")
    assert(FeatureRequest.fromArgs(Vector("--feature", "nope")).isLeft, "an unknown spec")
  }

  test("a lexicon file loads with its header skipped, comments ignored, and a content identity") {
    val table = LexiconFile.load(demo, "synthetic-demo").fold(e => fail(e.message), identity)
    assertEquals(table.name, "synthetic-demo")
    assertEquals(table.size, 17)
    assertEquals(table.lookup("canoe"), Some(6.5))
    assertEquals(table.lookup("CANOE"), None, "lookups are by normalized form; the table folded")
    val again = LexiconFile.load(demo, "synthetic-demo").fold(e => fail(e.message), identity)
    assertEquals(table.identity, again.identity)
    val renamed = LexiconFile.load(demo, "other").fold(e => fail(e.message), identity)
    assertNotEquals(table.identity, renamed.identity, "the name is part of the identity")
  }

  test("a lexicon file refuses a bad row by line number rather than dropping it") {
    def parse(text: String) = LexiconFile.parse(text, "t")
    assertEquals(
      parse("word\tvalue\nred\t1.5\nblue,2").map(_.toVector),
      Right(Vector("red" -> 1.5, "blue" -> 2.0))
    )
    assertEquals(
      parse("red\t1.5\n\n# note\nblue\t2\n").map(_.toVector),
      Right(Vector("red" -> 1.5, "blue" -> 2.0))
    )
    parse("red\t1.5\nblue\tmany\n") match
      case Left(PipelineError.FeatureRefused(detail)) => assert(detail.contains("line 2"), detail)
      case other                                      => fail(s"accepted: $other")
    parse("red\t1.5\nblue\n") match
      case Left(PipelineError.FeatureRefused(detail)) => assert(detail.contains("line 2"), detail)
      case other                                      => fail(s"accepted: $other")
    parse("red\tNaN\n") match
      case Left(PipelineError.FeatureRefused(detail)) =>
        assert(detail.contains("non-finite"), detail)
      case other => fail(s"accepted: $other")
    assert(LexiconFile.load(Path.of("/nonexistent/none.tsv"), "none").isLeft)
  }

  test("a file with only a header row is refused by that row: a header needs rows after it") {
    val dir = Files.createTempDirectory("lexicon")
    val path = dir.resolve("empty.tsv")
    Files.writeString(path, "word\tvalue\n")
    LexiconFile.load(path, "empty") match
      case Left(PipelineError.FeatureRefused(detail)) => assert(detail.contains("line 1"), detail)
      case other                                      => fail(s"accepted: $other")
  }
