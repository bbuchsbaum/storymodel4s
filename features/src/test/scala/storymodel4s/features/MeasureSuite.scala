package storymodel4s.features

import munit.FunSuite
import storymodel4s.core.*

/** The measure layer: a lexicon table's refusals and identity, the two computed measures, and the
  * track builder's missingness. Every number is a literal a reader can check against the text.
  */
class MeasureSuite extends FunSuite:
  private val text = "Red boats crossed quietly. Red ideas remained, and 3 boats sank."
  private val source = StorySource.fromText(text).fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val sequence = SurfaceSequence(atlas)

  private def table(rows: (String, Double)*): LexiconTable =
    LexiconTable.of("demo", "demo norms", Some("rating"), rows).fold(e => fail(e.message), identity)

  /** Each token's word with its estimate: a number, or the missing reason's name. Numbers stay
    * numbers here because `Double.toString` differs between the JVM and Scala.js (`2.0` vs `2`).
    */
  private def values(
      track: FeatureTrack[FeatureTarget.Token, Double]
  ): Vector[(String, Either[String, Double])] =
    track.observations.map { o =>
      val word = atlas.text(sequence.tokens(o.target.index.value).unit)
      o.estimate match
        case Estimate.Observed(v, _)  => word -> Right(v)
        case Estimate.Missing(reason) => word -> Left(reason.toString)
    }

  private def obs(word: String, value: Double): (String, Either[String, Double]) =
    word -> Right(value)
  private def miss(word: String, reason: String): (String, Either[String, Double]) =
    word -> Left(reason)

  test("a lexicon table folds keys like the surface sequence and has a content identity") {
    val a = table("Red" -> 5.5, "boats" -> 6.7, " crossed " -> 4.0)
    val b = table("red" -> 5.5, "crossed" -> 4.0, "BOATS" -> 6.7)
    assertEquals(a.entries, Map("red" -> 5.5, "boats" -> 6.7, "crossed" -> 4.0))
    assertEquals(a.identity, b.identity, "same content, different spelling and order: one identity")
    val c = table("red" -> 5.5, "boats" -> 6.7, "crossed" -> 4.5)
    assertNotEquals(a.identity, c.identity, "one changed value: another identity")
    assertEquals(table("x" -> -0.0).identity, table("x" -> 0.0).identity)
  }

  test("a lexicon table refuses what it cannot answer for") {
    def refused(rows: (String, Double)*)(fragment: String): Unit =
      LexiconTable.of("demo", "d", None, rows) match
        case Left(e)  => assert(e.message.contains(fragment), e.message)
        case Right(t) => fail(s"accepted $t")
    refused()("empty table")
    refused("red" -> Double.NaN)("non-finite")
    refused("red" -> Double.PositiveInfinity)("non-finite")
    refused("red" -> 1.0, "RED" -> 2.0)("conflicting values")
    refused(" " -> 1.0)("empty key")
    assert(LexiconTable.of("has space", "d", None, Vector("a" -> 1.0)).isLeft)
    assert(LexiconTable.of("a:b", "d", None, Vector("a" -> 1.0)).isLeft)
    assert(LexiconTable.of("", "d", None, Vector("a" -> 1.0)).isLeft)
    // Two rows that agree are one entry, not a conflict.
    assertEquals(table("red" -> 1.0, "RED" -> 1.0).size, 1)
  }

  test(
    "a lexicon measure values known words and marks the rest NotInLexicon; punctuation is excluded"
  ) {
    val m = LexiconMeasure(table("red" -> 5.5, "boats" -> 6.7))
    val track = TokenTracks.measure(sequence, m).fold(e => fail(e.message), identity)
    assertEquals(track.size, sequence.size)
    assertEquals(
      values(track).take(6),
      Vector(
        obs("Red", 5.5),
        obs("boats", 6.7),
        miss("crossed", "NotInLexicon"),
        miss("quietly", "NotInLexicon"),
        miss(".", "Excluded"),
        obs("Red", 5.5)
      )
    )
    assertEquals(track.coverage.observed, 4)
    assertEquals(track.coverage.eligible, sequence.size)
    assertEquals(track.space.id.value, s"lexicon:demo:${m.table.identity.short(12)}")
    assertEquals(track.provenance.storyChecksum, Some(source.canonicalChecksum))
    assertEquals(track.provenance.provenance.configHash, m.table.identity)
    assert(track.isRaw)
  }

  test("token length counts code points of lexical tokens only") {
    val track = TokenTracks.measure(sequence, TokenLength).fold(e => fail(e.message), identity)
    assertEquals(
      values(track).take(5),
      Vector(
        obs("Red", 3.0),
        obs("boats", 5.0),
        obs("crossed", 7.0),
        obs("quietly", 7.0),
        miss(".", "Excluded")
      )
    )
    assertEquals(track.coverage.observed, sequence.lexicalSize)
    assertEquals(track.space.units, Some("codepoints"))
  }

  test("type frequency counts a normalized form across the whole text, numbers included") {
    val track = TokenTracks.measure(sequence, TypeFrequency).fold(e => fail(e.message), identity)
    val byWord = values(track)
    assert(byWord.contains(obs("Red", 2.0)), byWord.toString)
    assert(byWord.contains(obs("boats", 2.0)), byWord.toString)
    assert(byWord.contains(obs("crossed", 1.0)), byWord.toString)
    assert(byWord.contains(obs("3", 1.0)), byWord.toString)
    assert(byWord.contains(miss(",", "Excluded")), byWord.toString)
  }

  test("the sentence reduction carries each sentence's support and lexical coverage") {
    val raw = TokenTracks.measure(sequence, TokenLength).fold(e => fail(e.message), identity)
    val perSentence = TokenTracks.perSentence(raw, sequence).fold(e => fail(e.message), identity)
    assertEquals(perSentence.size, atlas.sentences.size)
    assertEquals(perSentence.size, 2)
    val first = perSentence.observations.head
    assertEquals(first.target, FeatureTarget.Sentence(atlas.sentences.head.id))
    assertEquals(first.support, Some(SpanSet.one(atlas.sentences.head.span)))
    assertEquals(first.coverage.map(c => (c.eligible, c.observed)), Some((4, 4)))
    assertEquals(first.estimate.toOption, Some((3.0 + 5.0 + 7.0 + 7.0) / 4.0))
    assert(perSentence.derivation.nonEmpty, "a reduction records its recipe")
    assert(perSentence.space.id.value.startsWith("derived:"))
  }

  test("a sentence with no measurable token is Missing in the reduction, not zero") {
    val m = LexiconMeasure(table("red" -> 5.5))
    val raw = TokenTracks.measure(sequence, m).fold(e => fail(e.message), identity)
    val perSentence = TokenTracks.perSentence(raw, sequence).fold(e => fail(e.message), identity)
    val second = perSentence.observations(1)
    // "Red ideas remained, and 3 boats sank." has one "red": observed; a sentence with none would
    // be Missing. Restrict the table to a word in the first sentence only to see the second miss.
    assertEquals(second.estimate.toOption, Some(5.5))
    val only = LexiconMeasure(table("crossed" -> 4.0))
    val raw2 = TokenTracks.measure(sequence, only).fold(e => fail(e.message), identity)
    val red2 = TokenTracks.perSentence(raw2, sequence).fold(e => fail(e.message), identity)
    assertEquals(red2.observations(1).estimate.isObserved, false)
    assertEquals(red2.observations(1).coverage.map(_.observed), Some(0))
  }
