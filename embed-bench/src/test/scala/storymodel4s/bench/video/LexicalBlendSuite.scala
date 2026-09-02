package storymodel4s.bench.video

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.StorySource
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.RecallSegmenter

/** Courts for the lexical re-ranking of the semantic channel.
  *
  * The claims that make this change safe to judge are structural rather than statistical, so they
  * are tested rather than observed: at full semantic weight the re-ranking is the identity, every
  * unit keeps the exact multiset of distances the underlying channel produced for it, and a pair
  * the channel declined to score stays declined. The first two are what let sequential coherence
  * and concentration both judge this arm; if either broke, the study's comparisons would be
  * measuring a rescaling instead of a re-ranking.
  */
class LexicalBlendSuite extends FunSuite:

  private val segments = Vector(
    TimedSegment(0, "A man walks alone through heavy rain on an empty street.", None),
    TimedSegment(1, "The man knocks twice on a red door.", None),
    TimedSegment(2, "A woman pours tea in a bright kitchen.", None),
    TimedSegment(3, "The detective examines a bloodstained pink suitcase.", None),
    TimedSegment(4, "A taxi pulls away from the kerb at night.", None)
  )

  private val built = TimedSourceView.build(segments)

  private val recall = RecallSegmenter.segment(
    StorySource
      .fromText(
        "He knocked on the red door. Then she poured the tea. " +
          "The pink suitcase had blood on it. A taxi drove off.",
        Some("recall")
      )
      .fold(e => throw new IllegalArgumentException(e.message), identity)
  )

  /** A deterministic stand-in for the encoder, deliberately not a text similarity.
    *
    * If the stand-in ranked by word overlap it would agree with BM25 by construction, the
    * permutation would be the identity for every weight, and the re-ranking test would pass while
    * measuring nothing. Ranking by discourse position instead guarantees the two sides can
    * disagree.
    */
  private val base: SemanticDistance = SemanticDistance.of { (_, node) =>
    0.1 + 0.15 * (node.discoursePosition % 5)
  }

  private def distances(d: SemanticDistance): Map[String, Vector[Double]] =
    recall.ordered.map { u =>
      u.id.toString -> built.view.nodes.flatMap(n => d(u, n).toOption)
    }.toMap

  test("at full semantic weight the re-ranking is the identity"):
    val blended =
      LexicalBlend.blended(base, recall.ordered, built.view, built.nodeTexts, alpha = 1.0)
    for
      unit <- recall.ordered
      node <- built.view.nodes
    do
      assertEquals(
        blended(unit, node).toOption,
        base(unit, node).toOption,
        s"pair ${unit.id} / ${node.ref} changed at alpha=1.0"
      )

  test("every unit keeps the exact multiset of distances the channel produced"):
    for alpha <- Vector(0.2, 0.5, 0.8, 1.0) do
      val blended =
        LexicalBlend.blended(base, recall.ordered, built.view, built.nodeTexts, alpha = alpha)
      val before = distances(base)
      val after = distances(blended)
      assertEquals(after.keySet, before.keySet, s"units changed at alpha=$alpha")
      before.foreach { case (unit, ds) =>
        assertEquals(
          after(unit).sorted,
          ds.sorted,
          s"unit $unit was rescaled rather than re-ranked at alpha=$alpha"
        )
      }

  test("the blend actually re-ranks at a weight that admits the lexical side"):
    val blended =
      LexicalBlend.blended(base, recall.ordered, built.view, built.nodeTexts, alpha = 0.5)
    val changed = recall.ordered.exists { u =>
      built.view.nodes.exists(n => blended(u, n).toOption != base(u, n).toOption)
    }
    assert(changed, "alpha=0.5 left every pair untouched, so the lexical side is not reaching it")

  test("a pair the channel declined to score stays declined"):
    val declining: SemanticDistance = (unit, node) =>
      if node.ref == built.view.nodes.head.ref then
        Estimate.missing(MissingReason.ProviderAbstained)
      else base(unit, node)
    val blended =
      LexicalBlend.blended(declining, recall.ordered, built.view, built.nodeTexts, alpha = 0.8)
    recall.ordered.foreach { u =>
      assert(
        blended(u, built.view.nodes.head).toOption.isEmpty,
        "an abstained pair was filled in with a confident value"
      )
    }

  test("the two field policies are distinguishable, and both stay permutations"):
    val withLemmas = LexicalBlend.blended(
      base,
      recall.ordered,
      built.view,
      built.nodeTexts,
      alpha = 0.5,
      fields = LexicalBlend.LexicalFields.WithLemmas
    )
    val textOnly = LexicalBlend.blended(
      base,
      recall.ordered,
      built.view,
      built.nodeTexts,
      alpha = 0.5,
      fields = LexicalBlend.LexicalFields.TextOnly
    )
    // Both remain permutations of the same per-unit distances; only the ordering may differ.
    val a = distances(withLemmas)
    val b = distances(textOnly)
    a.foreach { case (unit, ds) => assertEquals(ds.sorted, b(unit).sorted) }
