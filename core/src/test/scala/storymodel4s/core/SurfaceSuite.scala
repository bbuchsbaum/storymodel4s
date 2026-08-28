package storymodel4s.core

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen

class SurfaceSuite extends ScalaCheckSuite:
  import Gens.*

  private def seqOf(text: String): SurfaceSequence =
    SurfaceSequence(SurfaceAnalyzer.analyze(StorySource.fromText(text).toOption.get))

  private val sample = seqOf(
    "The lamp flickered twice, then died. Nobody spoke; 3 of us waited.\n\nOutside, rain kept falling."
  )

  test("token classes: words, numbers, punctuation") {
    val classes = sample.tokens.map(t => (sample.atlas.text(t.unit), t.tokenClass))
    assertEquals(classes.find(_._1 == "lamp").map(_._2), Some(TokenClass.Word))
    assertEquals(classes.find(_._1 == "3").map(_._2), Some(TokenClass.Number))
    assertEquals(classes.find(_._1 == ",").map(_._2), Some(TokenClass.Punctuation))
    assertEquals(classes.find(_._1 == ";").map(_._2), Some(TokenClass.Punctuation))
    assert(
      sample.tokens
        .filter(_.tokenClass == TokenClass.Word)
        .forall(_.normalized.exists(_.forall(!_.isUpper)))
    )
  }

  test("lexical tokens exclude punctuation and are a subsequence of tokens") {
    assert(sample.lexicalTokens.forall(_.isLexical))
    assertEquals(sample.lexicalTokens.size, sample.tokens.count(_.isLexical))
    assertEquals(sample.lexicalIndices, sample.lexicalIndices.sorted)
  }

  test("previous/next navigate the axis and stop at the edges") {
    assertEquals(sample.previous(TokenIndex.Zero), None)
    assertEquals(sample.next(TokenIndex.unsafe(sample.size - 1)), None)
    assertEquals(
      sample.next(TokenIndex.Zero).map(_.unit),
      sample.at(TokenIndex.unsafe(1)).map(_.unit)
    )
  }

  test("covering(sentence span) returns exactly that sentence's tokens") {
    sample.atlas.sentences.foreach { s =>
      val expected = sample.atlas.childrenOf(s.id).map(_.id)
      val got = sample.covering(SpanSet.one(s.span)).map(_.unit.id)
      assertEquals(got, expected)
    }
  }

  test("word windows 20 by 5 tile the lexical axis, keep partial edges, support inside text") {
    val plan = WindowPlan.words(width = 4, step = 2)
    val ws = sample.windows(plan).toVector
    val n = sample.lexicalSize
    assertEquals(ws.size, (0 until n by 2).size)
    ws.foreach { w =>
      assert(w.support.minSpan.endExclusive <= sample.atlas.source.canonicalText.length)
      assert(w.lexicalTokenCount <= 4)
      assertEquals(w.lexicalTokenCount, sample.slice(w.tokenRange).count(_.isLexical))
    }
    assert(ws.init.forall(_.complete))
    assertEquals(ws.head.tokenRange.start.value, sample.lexicalIndices.head.value)
  }

  test("DropPartial emits only full windows; Pad records the shortfall") {
    val drop = sample.windows(WindowPlan.words(4, 2, EdgePolicy.DropPartial)).toVector
    assert(drop.forall(w => w.complete && w.lexicalTokenCount == 4))
    val pad = sample.windows(WindowPlan.words(4, 2, EdgePolicy.Pad)).toVector
    assert(pad.exists(w => !w.complete && w.padding > 0))
    assert(pad.filter(_.complete).forall(_.padding == 0))
  }

  test("sentence windows cover each sentence's tokens exactly") {
    val ws = sample.windows(WindowPlan.sentences()).toVector
    assertEquals(ws.size, sample.atlas.sentences.size)
    ws.zip(sample.atlas.sentences).foreach { (w, s) =>
      assertEquals(
        sample.slice(w.tokenRange).map(_.unit.id),
        sample.atlas.childrenOf(s.id).map(_.id)
      )
    }
  }

  test("number-shaped tokens classify as Number; supplementary letters as Word") {
    val s = seqOf("It cost 3.5 dollars and 1,000 cents; 𝔸 is a letter, 😀 is not.")
    val classes = s.tokens.map(t => (s.atlas.text(t.unit), t.tokenClass)).toMap
    assertEquals(classes.get("3.5"), Some(TokenClass.Number))
    assertEquals(classes.get("1,000"), Some(TokenClass.Number))
    assertEquals(classes.get("𝔸"), Some(TokenClass.Word))
    assertEquals(classes.get("😀"), Some(TokenClass.Symbol))
    assertEquals(s.tokens.find(t => s.atlas.text(t.unit) == "It").flatMap(_.normalized), Some("it"))
  }

  test("centeredWindows yields one clipped window per basis position") {
    val ws = sample.centeredWindows(2, WindowBasis.LexicalTokens).toVector
    assertEquals(ws.size, sample.lexicalSize)
    assertEquals(ws.map(_.ordinal), (0 until sample.lexicalSize).toVector)
    assert(!ws.head.complete && !ws.last.complete)
    assert(ws.forall(w => w.lexicalTokenCount <= 5 && w.lexicalTokenCount >= 3))
    // the first position has a window centered on itself, unlike the sliding plan
    assertEquals(ws.head.tokenRange.start.value, sample.lexicalIndices.head.value)
  }

  test("contextAround supports the sentence basis") {
    val idx = sample.indexOf(sample.atlas.childrenOf(sample.atlas.sentences(1).id).head.id).get
    val w = sample.contextAround(idx, 1, WindowBasis.Sentences).get
    assertEquals(w.ordinal, 1)
    assert(w.complete)
    assert(w.support.minSpan.contains(sample.atlas.sentences(0).span))
    assert(w.support.minSpan.contains(sample.atlas.sentences(2).span))
  }

  test("centered context clips at the edges") {
    val first = sample.contextAround(TokenIndex.Zero, 3, WindowBasis.AllTokens).get
    assertEquals(first.tokenRange.start.value, 0)
    assertEquals(first.tokenRange.length, 4)
    assert(!first.complete)
    val mid = sample.contextAround(TokenIndex.unsafe(6), 2, WindowBasis.AllTokens).get
    assertEquals(mid.tokenRange.length, 5)
    assert(mid.complete)
  }

  property("all-token windows tile: every token appears in ceil(width/step) windows at most") {
    forAll(storyText, Gen.chooseNum(1, 6), Gen.chooseNum(1, 6)) { (text, width, step) =>
      val seq = seqOf(text)
      val ws = seq.windows(WindowPlan.tokens(width, step)).toVector
      val counts = Array.fill(seq.size)(0)
      ws.foreach(w => w.tokenRange.indices.foreach(i => counts(i) += 1))
      val maxCover = math.ceil(width.toDouble / step).toInt
      val minCover = if step <= width then 1 else 0
      (seq.size == 0 || (counts.forall(c => c >= minCover && c <= maxCover))) &&
      ws.forall(w => w.support.minSpan.endExclusive <= seq.atlas.source.canonicalText.length)
    }
  }

  property("covering a token's own span yields that token") {
    forAll(storyText) { text =>
      val seq = seqOf(text)
      seq.tokens.zipWithIndex.forall { (t, i) =>
        seq.coveringIndices(SpanSet.one(t.span)) == Vector(TokenIndex.unsafe(i))
      }
    }
  }

  test("TokenRange and PositiveInt reject invalid values") {
    assert(TokenRange.of(-1, 2).isLeft)
    assert(TokenRange.of(3, 2).isLeft)
    assert(PositiveInt.from(0).isLeft)
    assert(TokenIndex.from(-1).isLeft)
  }
