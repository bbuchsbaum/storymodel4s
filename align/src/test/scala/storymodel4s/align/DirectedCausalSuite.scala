package storymodel4s.align

import munit.FunSuite

import storymodel4s.core.{SituationId, SpanSet, TextSpan}
import storymodel4s.recall.{ModalityTag, PolarityTag}

/** Courts for the directed causal transition kinds (ADR 0014). `CauseToEffect` and `EffectToCause`
  * tell a move from a cause to its effect apart from the move back; the symmetrized
  * `CausalNeighbor` stays as it was, and the shipped model weights the new kinds at zero, so no
  * landed score moves.
  */
class DirectedCausalSuite extends FunSuite:
  import TransitionKind.*

  private def ref(name: String): SourceNodeRef = SourceNodeRef.Situation(SituationId.unsafe(name))
  private val a = ref("a")
  private val b = ref("b")
  private val c = ref("c")

  private def node(r: SourceNodeRef, i: Int): NodeSummary =
    NodeSummary(
      r,
      0,
      None,
      i,
      SpanSet.one(TextSpan.unsafe(i * 10, i * 10 + 8)),
      None,
      Vector.empty,
      ContextTag.NarratedWorld,
      PolarityTag.Unknown,
      ModalityTag.Unknown,
      Vector.empty,
      Set.empty
    )

  // a causes b; b and c cause each other. Reciprocal edges are legal: only self-edges are refused.
  private val view = InMemorySourceView(
    nodes = Vector(node(a, 0), node(b, 1), node(c, 2)),
    edges = Map(RelationLayer.Causal -> Vector((a, b, 1.0), (b, c, 1.0), (c, b, 1.0))),
    worldOrder = None,
    scoringLength = 30
  )

  private def f(s: SourceNodeRef, t: SourceNodeRef): Map[TransitionKind, Double] =
    TransitionFeatures.between(view, s, t).values

  private def score(s: SourceNodeRef, t: SourceNodeRef, model: TransitionModel): Double =
    TransitionFeatures.between(view, s, t).score(model)

  test(
    "cause to effect and effect to cause are two features; the symmetrized one fires either way"
  ) {
    assertEquals(f(a, b)(CauseToEffect), 1.0)
    assertEquals(f(a, b)(EffectToCause), 0.0)
    assertEquals(f(a, b)(CausalNeighbor), 1.0)
    assertEquals(f(b, a)(CauseToEffect), 0.0)
    assertEquals(f(b, a)(EffectToCause), 1.0)
    assertEquals(f(b, a)(CausalNeighbor), 1.0)
    assertEquals(f(a, c)(CauseToEffect), 0.0)
    assertEquals(f(a, c)(EffectToCause), 0.0)
    assertEquals(f(a, c)(CausalNeighbor), 0.0)
  }

  test("on a reciprocal pair both directions fire and the OR fires once: not a re-weighting") {
    assertEquals(f(b, c)(CauseToEffect), 1.0)
    assertEquals(f(b, c)(EffectToCause), 1.0)
    assertEquals(f(b, c)(CausalNeighbor), 1.0)
    val directed = TransitionModel(
      TransitionModel.default.theta ++
        Map(CausalNeighbor -> 0.0, CauseToEffect -> 0.8, EffectToCause -> 0.8)
    )
    val symmetrized = TransitionModel.default
    // one direction present: the two models agree exactly
    assertEqualsDouble(score(a, b, directed), score(a, b, symmetrized), 1e-12)
    // both directions present: the directed model counts the pair twice, the OR once
    assertEqualsDouble(score(b, c, directed) - score(b, c, symmetrized), 0.8, 1e-12)
  }

  test(
    "the shipped model carries no directed weight, so every score is unchanged by construction"
  ) {
    assert(!TransitionModel.default.theta.contains(CauseToEffect))
    assert(!TransitionModel.default.theta.contains(EffectToCause))
    assertEquals(TransitionModel.default(CauseToEffect), 0.0)
    assertEquals(TransitionModel.default(EffectToCause), 0.0)
    val older = declaredOrder.dropRight(2)
    for (s, t) <- Vector((a, b), (b, a), (b, c), (a, c), (a, a)) do
      val features = TransitionFeatures.between(view, s, t)
      val summedOverOlderKinds =
        older.map(k => TransitionModel.default(k) * features.values.getOrElse(k, 0.0)).sum
      // bit-identical, not approximately equal: the appended kinds add +0.0 at the end of the same
      // sum, and the twelve-term partial sum is never -0.0 (its first term is +0.0 or 0.5)
      assertEquals(
        java.lang.Double.doubleToRawLongBits(features.score(TransitionModel.default)),
        java.lang.Double.doubleToRawLongBits(summedOverOlderKinds)
      )
  }

  /** The declaration order every landed score was summed in, then the two appended kinds. A
    * reordering of the first twelve would move the last bits of every transition score; an
    * insertion before the last two would too.
    */
  private val declaredOrder: Vector[TransitionKind] = Vector(
    Stay,
    DiscourseSuccessor,
    WorldTimeSuccessor,
    CausalNeighbor,
    HierarchyUp,
    HierarchyDown,
    SameEntityThread,
    SemanticNeighbor,
    Backward,
    LongJump,
    ExternalIn,
    ExternalStay,
    CauseToEffect,
    EffectToCause
  )

  test("the declaration order is pinned in full: twelve older kinds, then the two directed ones") {
    assertEquals(TransitionKind.values.toVector, declaredOrder)
    assertEquals(
      TransitionKind.features.toSet,
      declaredOrder.toSet -- Set(ExternalIn, ExternalStay)
    )
  }
