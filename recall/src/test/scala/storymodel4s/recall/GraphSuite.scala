package storymodel4s.recall

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import storymodel4s.core.*

class GraphSuite extends ScalaCheckSuite:

  private val text = "First thing happened. Then the second thing. Finally the third."
  private val src = StorySource.fromText(text).toOption.get
  private val atlas = SurfaceAnalyzer.analyze(src)

  private def unit(i: Int, ordinal: Int = -1): RecallUnit =
    val s = atlas.sentences(i)
    RecallUnit(
      RecallUnitId.unsafe(s"u$i"),
      if ordinal < 0 then i else ordinal,
      SpanSet.one(SpanRef(Some(s.id), s.span)),
      atlas.text(s),
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )

  private val good =
    RecallGraph(src, atlas, Vector(unit(0), unit(1), unit(2)), RecallRelations.empty)

  test("a well-formed graph validates and exposes the chain") {
    assert(RecallGraph.validated(good).isValid)
    assertEquals(good.chain.map { case (a, b) => (a.ordinal, b.ordinal) }, Vector((0, 1), (1, 2)))
  }

  test("non-contiguous ordinals are rejected") {
    val bad = good.copy(units = Vector(unit(0), unit(1, 5), unit(2)))
    assert(RecallGraph.validated(bad).isInvalid)
  }

  test("duplicate unit ids are rejected") {
    val bad = good.copy(units = Vector(unit(0), unit(0).copy(ordinal = 1), unit(2)))
    assert(RecallGraph.validated(bad).isInvalid)
  }

  test("unknown relation endpoints and self edges are rejected") {
    val ghost = RecallUnitId.unsafe("ghost")
    val bad1 = good.copy(relations =
      RecallRelations.empty.copy(temporal =
        Vector(RecallTemporalEdge(good.units(0).id, RecallTemporalRelation.Before, ghost, None))
      )
    )
    val bad2 = good.copy(relations =
      RecallRelations.empty.copy(causal =
        Vector(RecallCausalEdge(good.units(0).id, good.units(0).id, None))
      )
    )
    assert(RecallGraph.validated(bad1).isInvalid)
    assert(RecallGraph.validated(bad2).isInvalid)
  }

  test("participants must reference declared recall entities") {
    val e = RecallEntityId.unsafe("re0")
    val withRef = unit(0).copy(proposition =
      PropositionSketch.empty.copy(participants =
        Vector(SketchParticipant(SketchRole.Agent, Some(e), "she"))
      )
    )
    val bad = good.copy(units = Vector(withRef, unit(1), unit(2)))
    assert(RecallGraph.validated(bad).isInvalid)
    val ok = bad.copy(relations =
      RecallRelations.empty.copy(entities = Vector(RecallEntity(e, "she", Vector.empty)))
    )
    assert(RecallGraph.validated(ok).isValid)
  }

  test("spans beyond the transcript are rejected") {
    val far = unit(0).copy(span = SpanSet.one(TextSpan.unsafe(0, text.length + 10)))
    val bad = good.copy(units = Vector(far, unit(1), unit(2)))
    assert(RecallGraph.validated(bad).isInvalid)
  }

  property("temporal relation inversion is an involution") {
    forAll(Gen.oneOf(RecallTemporalRelation.values.toSeq)) { r =>
      r.inverse.inverse == r
    }
  }

  property("edge inversion is an involution and swaps endpoints") {
    val ids = Gen.oneOf("a", "b", "c").map(RecallUnitId.unsafe)
    forAll(ids, ids, Gen.oneOf(RecallTemporalRelation.values.toSeq)) { (a, b, r) =>
      val e = RecallTemporalEdge(a, r, b, None)
      e.inverse.inverse == e && e.inverse.from == b && e.inverse.to == a
    }
  }

  property("stemmer maps every inflected form of a word family to one stem") {
    val families = Gen.oneOf(
      Vector("walk", "walked", "walking", "walks"),
      Vector("cry", "cries", "cried", "crying"),
      Vector("house", "houses"),
      Vector("go", "goes", "went", "gone", "going"),
      Vector("find", "found", "finds", "finding"),
      Vector("box", "boxes"),
      Vector("scream", "screams", "screamed", "screaming"),
      Vector("paddle", "paddles", "paddled", "paddling"),
      Vector("shoot", "shot", "shoots", "shooting"),
      Vector("carry", "carried", "carries", "carrying")
    )
    forAllNoShrink(families) { fam =>
      val stems = fam.map(RecallSegmenter.lemma).distinct
      (stems.size == 1) :| s"$fam -> ${fam.map(RecallSegmenter.lemma)}"
    }
  }

  test("stemming is story-agnostic: no synonym collapsing") {
    assertNotEquals(Lexical.stem("boat"), Lexical.stem("canoe"))
    assertNotEquals(Lexical.stem("misty"), Lexical.stem("fog"))
    assertNotEquals(Lexical.stem("back"), Lexical.stem("return"))
  }

  test("lowercasing is locale-independent and per code point") {
    // simple (per code point) case mapping: dotted capital I maps to plain i on every platform
    assertEquals(Lexical.lower("İSTANBUL Ünïcode"), "istanbul ünïcode")
    assertEquals(Lexical.lower("ABC"), "abc")
    assertEquals(Lexical.words("Anna's house, 😀 cellar!"), Vector("anna", "house", "cellar"))
  }
