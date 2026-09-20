package storymodel4s.acquire

import munit.FunSuite
import storymodel4s.core.*

class D1aTaskReferencesSuite extends FunSuite:
  import D1aAcquireFixtures.*
  private val valid = TaskReferences(surface.sentences.map(_.id), surface.tokens.map(_.id),
    Vector("unchecked-node"), Vector(ClaimId.unsafe("unchecked-claim")))
  private val unknown = SurfaceUnitId.unsafe("unknown")
  private val cases = Vector(valid, TaskReferences.empty,
    valid.copy(sentenceIds = Vector(unknown)), valid.copy(tokenIds = Vector(unknown)),
    valid.copy(sentenceIds = Vector(surface.tokens.head.id)),
    valid.copy(tokenIds = Vector(surface.sentences.head.id)),
    valid.copy(sentenceIds = Vector(unknown, surface.tokens.head.id),
      tokenIds = Vector(surface.sentences.head.id, unknown)))

  test("text atlas overload preserves complete validation and error order"):
    val text: NarrativeSourceAtlas = TextNarrativeAtlas.of(surface).toOption.get
    cases.foreach(refs => assertEquals(refs.validateAgainst(text), refs.validateAgainst(surface)))

  test("accepting control: anchored references use bound parser surface units"):
    assert(valid.validateAgainst(atlas).isValid)
    assert(TaskReferences.empty.validateAgainst(noSurface).isValid)
    cases.foreach(refs => assertEquals(refs.validateAgainst(atlas), refs.validateAgainst(surface)))

  test("anchored references refuse missing and wrong-kind parser units"):
    for refs <- cases.drop(2) do assert(refs.validateAgainst(atlas).isInvalid)
    assert(valid.copy(sentenceIds = Vector(SurfaceUnitId.unsafe(film.id.value)))
      .validateAgainst(atlas).isInvalid)

  test("absence of a bound proposal surface refuses all parser references"):
    assert(valid.validateAgainst(noSurface).isInvalid)
    assert(TaskReferences.empty.copy(sentenceIds = Vector(surface.sentences.head.id))
      .validateAgainst(noSurface).isInvalid)
    assert(TaskReferences.empty.copy(tokenIds = Vector(surface.tokens.head.id))
      .validateAgainst(noSurface).isInvalid)
