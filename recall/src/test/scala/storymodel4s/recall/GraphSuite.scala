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

  test("a unit whose text is not the words at its span is rejected") {
    // The discriminating case. Without this law "which words support this cell" has two answers:
    // the span resolved against the transcript, and unit.text. The only code that turns a unit
    // into a vector reads text; a span-based trace reads the transcript. Nothing required them to
    // agree, so a unit could be embedded as one sentence and explained as another.
    val lying =
      good.copy(units = Vector(unit(0).copy(text = "THE GHOSTS WERE ANGRY"), unit(1), unit(2)))
    assert(RecallGraph.validated(lying).isInvalid)
    // Control, and it is the half that matters: the honest graph must still pass, or the law is
    // just a way of rejecting everything.
    assert(RecallGraph.validated(good).isValid)
  }

  test("a near miss is still a miss: trailing whitespace is not the same words") {
    // Pinned deliberately. A tolerant comparison here would let the trace quote text that is not
    // in the transcript, which is the whole failure being closed - and trimming is the tempting
    // "harmless" relaxation someone will reach for the first time this law fails on real input.
    val off = good.copy(units = Vector(unit(0).copy(text = unit(0).text + " "), unit(1), unit(2)))
    assert(RecallGraph.validated(off).isInvalid)
  }

  test("a unit spanning two sentences carries the text between them") {
    // The check compares against the hull, not against concatenated per-ref slices, so a unit whose
    // support is discontinuous keeps the material in the gap. Asserting the accepted text IS the
    // hull slice, so a change to per-ref concatenation fails here rather than silently narrowing
    // what a legitimate multi-sentence unit is allowed to say.
    val s0 = atlas.sentences(0)
    val s1 = atlas.sentences(1)
    val merged = SpanSet.unsafe(SpanRef(Some(s0.id), s0.span), SpanRef(Some(s1.id), s1.span))
    val hull = merged.minSpan
    val hullText = text.substring(hull.start, hull.endExclusive)
    val wide =
      good.copy(units = Vector(unit(0).copy(span = merged, text = hullText), unit(1), unit(2)))
    assert(RecallGraph.validated(wide).isValid, s"hull text refused: [$hullText]")
    assert(hullText.contains("Then the second"), hullText)
  }

  test("a span past the end of the transcript is refused, not thrown on") {
    // validated accumulates rather than short-circuits, so the text check runs even when the span
    // check has already failed. Without its guard this substring call throws and the caller gets a
    // StringIndexOutOfBoundsException instead of the typed refusal it asked for.
    val past = TextSpan.unsafe(text.length - 2, text.length + 40)
    val bad = good.copy(units =
      Vector(unit(0).copy(span = SpanSet.one(SpanRef(None, past))), unit(1), unit(2))
    )
    assert(RecallGraph.validated(bad).isInvalid)
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
