package storymodel4s.story

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.core.*
import Gens.given

class RelationsSuite extends ScalaCheckSuite:

  private val a = SituationId.unsafe("a")
  private val b = SituationId.unsafe("b")
  private val ctx = ContextId.unsafe("ctx")
  private val meta = Small.meta("t", EpistemicStatus.Hypothesized, None)

  property("converse is an involution on relations") {
    forAll { (r: TemporalRelation) => r.converse.converse == r }
  }

  property("every fact has a canonical form: a relation or its converse is canonical") {
    forAll { (r: TemporalRelation) =>
      import TemporalRelation.{Contains, During}
      val bothCanonical = r.isCanonical && r.converse.isCanonical
      (r.isCanonical || r.converse.isCanonical) &&
      (!bothCanonical || r == r.converse || Set(r, r.converse) == Set(During, Contains))
    }
  }

  property("TemporalEdge.inverse is an involution that swaps endpoints") {
    forAll { (r: TemporalRelation) =>
      val e = TemporalEdge(a, r, b, ctx, meta)
      e.inverse.inverse == e && e.inverse.from == b && e.inverse.to == a
    }
  }

  property("canonical form is canonical and idempotent") {
    forAll { (r: TemporalRelation) =>
      val e = TemporalEdge(a, r, b, ctx, meta)
      e.canonical.relation.isCanonical && e.canonical.canonical == e.canonical
    }
  }

  test("converse table matches Allen's algebra") {
    import TemporalRelation.*
    assertEquals(Before.converse, After)
    assertEquals(Meets.converse, MetBy)
    assertEquals(Overlaps.converse, OverlappedBy)
    assertEquals(During.converse, Contains)
    assertEquals(Starts.converse, StartedBy)
    assertEquals(Finishes.converse, FinishedBy)
    assertEquals(Equal.converse, Equal)
    assertEquals(Unclear.converse, Unclear)
    assertEquals(TemporalRelation.values.count(_.isStrictPrecedence), 2)
  }

  test("ContextKind.holderEntity") {
    val e = EntityId.unsafe("e")
    assertEquals(ContextKind.NarratedWorld.holderEntity, None)
    assertEquals(ContextKind.Hypothetical.holderEntity, None)
    assertEquals(ContextKind.Speech(e).holderEntity, Some(e))
    assertEquals(ContextKind.Imagination(e).holderEntity, Some(e))
  }

  test("ParticipantRole.render distinguishes custom roles") {
    assertEquals(ParticipantRole.Agent.render, "Agent")
    assertEquals(ParticipantRole.Custom("pb", "ARG2").render, "pb:ARG2")
  }
