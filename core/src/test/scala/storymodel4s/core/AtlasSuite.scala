package storymodel4s.core

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

class AtlasSuite extends ScalaCheckSuite:
  val wog =
    """One night two young men from Egulac went down to the river to hunt seals, and while they were there it became foggy and calm. Then they heard war-cries, and they thought: "Maybe this is a war-party." They escaped to the shore, and hid behind a log.
      |
      |Now canoes came up, and they heard the noise of paddles, and saw one canoe coming up to them. There were five men in the canoe, and they said:
      |
      |"What do you think? We wish to take you along. We are going up the river to make war on the people."
      |""".stripMargin

  def source(t: String): StorySource = StorySource.fromText(t).toOption.get

  test("canonicalization normalizes line endings, trailing space, blank runs"):
    val c = StorySource.canonicalize("a  \r\nb\r\n\r\n\r\n\r\nc\n\n")
    assertEquals(c, "a\nb\n\nc")
    assert(StorySource.fromText("   ").isLeft)

  test("story id is content-addressed from the canonical text"):
    val a = source("Hello world.\r\n")
    val b = source("Hello world.\n\n\n")
    assertEquals(a.id, b.id)
    assertEquals(a.canonicalChecksum, b.canonicalChecksum)
    assertNotEquals(a.rawChecksum, b.rawChecksum)

  test("WOG excerpt: paragraphs, sentences, quotes, and abbreviations"):
    val src = source(wog)
    val atlas = SurfaceAnalyzer.analyze(src)
    assert(SurfaceAtlas.validated(atlas).isRight)
    assertEquals(atlas.paragraphs.size, 3)
    val sents = atlas.sentences.map(atlas.text)
    assertEquals(sents.size, 8, sents.mkString("\n"))
    assert(sents(1).endsWith("\"Maybe this is a war-party.\""), sents(1))
    assertEquals(sents(4), "There were five men in the canoe, and they said:")
    assertEquals(sents(5), "\"What do you think?")
    assertEquals(sents(6), "We wish to take you along.")
    assert(sents(7).endsWith("people.\""))

  test("abbreviations and initials do not split sentences"):
    val atlas = SurfaceAnalyzer.analyze(source("Dr. Smith met J. K. Rowling at 5 p.m. It rained."))
    val sents = atlas.sentences.map(atlas.text)
    assertEquals(sents, Vector("Dr. Smith met J. K. Rowling at 5 p.m.", "It rained."))

  test("terminal runs and lowercase continuations"):
    val atlas = SurfaceAnalyzer.analyze(source("Wait... what?! he said. Then nothing."))
    val sents = atlas.sentences.map(atlas.text)
    assertEquals(sents, Vector("Wait... what?! he said.", "Then nothing."))

  test("tokens: words with internal apostrophes/hyphens, punctuation separate"):
    val atlas = SurfaceAnalyzer.analyze(source("They didn't hear war-cries, \"quickly\"."))
    assertEquals(
      atlas.tokens.map(atlas.text),
      Vector("They", "didn't", "hear", "war-cries", ",", "\"", "quickly", "\"", ".")
    )

  test("indexes: unitAt, childrenOf, parentOf"):
    val src = source(wog)
    val atlas = SurfaceAnalyzer.analyze(src)
    val s0 = atlas.sentences.head
    assertEquals(atlas.unitAt(s0.span.start + 3, SurfaceUnitKind.Sentence).map(_.id), Some(s0.id))
    assertEquals(atlas.unitAt(s0.span.start, SurfaceUnitKind.Paragraph).map(_.ordinal), Some(0))
    assert(atlas.childrenOf(s0.id).nonEmpty)
    assert(atlas.childrenOf(s0.id).forall(_.kind == SurfaceUnitKind.Token))
    assertEquals(atlas.parentOf(s0).map(_.kind), Some(SurfaceUnitKind.Paragraph))
    assertEquals(atlas.unitAt(-1, SurfaceUnitKind.Token), None)

  property("tokens reproduce the text modulo whitespace and nest in sentences in paragraphs"):
    forAll(Gens.storyText) { t =>
      val src = source(t)
      val atlas = SurfaceAnalyzer.analyze(src)
      val text = src.canonicalText
      val joined = atlas.tokens.map(atlas.text).mkString
      val nested = atlas.units.forall { u =>
        u.parent.forall(p => atlas.byId(p).span.contains(u.span))
      }
      val sentCover = atlas.sentences.map(atlas.text).mkString.filterNot(_.isWhitespace) ==
        text.filterNot(_.isWhitespace)
      joined == text.filterNot(_.isWhitespace) && nested && sentCover &&
      SurfaceAtlas.validated(atlas).isRight
    }

  property("every sentence starts and ends on non-whitespace"):
    forAll(Gens.storyText) { t =>
      val atlas = SurfaceAnalyzer.analyze(source(t))
      atlas.sentences.forall { s =>
        val x = atlas.text(s)
        !x.head.isWhitespace && !x.last.isWhitespace
      }
    }

  test("validation rejects escaped children, bad ordinals, missing parents"):
    val src = source("Alpha beta. Gamma delta.")
    val atlas = SurfaceAnalyzer.analyze(src)
    val sent = atlas.sentences.head
    val escaped = atlas.copy(units = atlas.units.map { u =>
      if u.kind == SurfaceUnitKind.Token && u.parent.contains(sent.id) && u.ordinal == 0 then
        u.copy(span = TextSpan.unsafe(0, src.canonicalText.length))
      else u
    })
    assert(SurfaceAtlas.validated(escaped).isLeft)
    val badOrd = atlas.copy(units =
      atlas.units.map(u => if u.kind == SurfaceUnitKind.Sentence then u.copy(ordinal = 0) else u)
    )
    assert(SurfaceAtlas.validated(badOrd).isLeft)
    val orphan = atlas.copy(units =
      atlas.units :+ SurfaceUnit(
        SurfaceUnitId.unsafe("orphan"),
        SurfaceUnitKind.Token,
        TextSpan.unsafe(0, 1),
        999,
        Some(SurfaceUnitId.unsafe("nope"))
      )
    )
    assert(SurfaceAtlas.validated(orphan).isLeft)
    val tooLong = atlas.copy(units =
      atlas.units :+ SurfaceUnit(
        SurfaceUnitId.unsafe("long"),
        SurfaceUnitKind.Paragraph,
        TextSpan.unsafe(0, 10000),
        5,
        None
      )
    )
    assert(SurfaceAtlas.validated(tooLong).isLeft)
    val dup = atlas.copy(units = atlas.units :+ atlas.units.head)
    assert(SurfaceAtlas.validated(dup).isLeft)
    val inverted = atlas.copy(units =
      atlas.units.map(u =>
        if u.kind == SurfaceUnitKind.Sentence then
          u.copy(parent = atlas.tokens.headOption.map(_.id))
        else u
      )
    )
    assert(SurfaceAtlas.validated(inverted).isLeft)

  test("language tags"):
    assert(LanguageTag.from("en").isRight)
    assert(LanguageTag.from("en-CA").isRight)
    assert(LanguageTag.from("e").isLeft)
    assert(LanguageTag.from("en_CA").isLeft)

class QuoteSuite extends munit.FunSuite:
  test("lowercase continuation after a closing quote does not split; parentheses guard"):
    val atlas = SurfaceAnalyzer.analyze(
      StorySource
        .fromText("\"Go home!\" she shouted. He (who was tired. Very tired) left.")
        .toOption
        .get
    )
    assertEquals(
      atlas.sentences.map(atlas.text),
      Vector("\"Go home!\" she shouted.", "He (who was tired. Very tired) left.")
    )
